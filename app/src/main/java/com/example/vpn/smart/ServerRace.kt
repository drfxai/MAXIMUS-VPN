package com.example.vpn.smart

import com.example.core.SecretRedactor
import com.example.data.model.ServerCategory
import com.example.data.model.VlessProfile
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.stealth.ConnectionKind
import com.example.vpn.stealth.StealthPathFinder
import com.example.xray.RealDelayProbe
import com.example.xray.XrayLogManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds a working server among the saved ones with real requests, several at a time: used by Smart
 * Connect, when the chosen server carries no traffic at connect, and when the watchdog gives up on a
 * running connection.
 *
 * [rank] orders the candidates (what worked on this network first, then recent successes, favorites
 * and scores) and spreads the kinds of connection over each batch, so a filter that blocks one kind
 * cannot empty a whole round. [run] probes five at a time and stops at the first batch with a working
 * server, taking the fastest one in it.
 */
class ServerRace(
    private val probe: (List<VlessProfile>, Int) -> List<RealDelayProbe.Outcome> = { p, t -> RealDelayProbe.measure(p, t) },
    private val log: (String) -> Unit = { XrayLogManager.i("SMART", it) }
) {
    data class Winner(
        /** What the engine runs (the host may be replaced by its address). */
        val profile: VlessProfile,
        /** The saved profile. */
        val owner: VlessProfile,
        val latencyMs: Long
    )

    /**
     * [resolve] turns a host name into an address outside the tunnel; candidates are resolved in
     * parallel and one that cannot be resolved is skipped. At most [maxBatches] rounds are probed.
     */
    fun run(
        candidates: List<VlessProfile>,
        resolve: (VlessProfile) -> VlessProfile,
        timeoutSec: Int,
        maxBatches: Int = MAX_BATCHES
    ): Winner? {
        if (candidates.isEmpty()) return null
        val prepared = resolveAll(candidates, resolve)
        val batches = StealthPathFinder.parallelSafeBatches(prepared) { it.second }.take(maxBatches)
        for ((round, batch) in batches.withIndex()) {
            val outcomes = probe(batch.map { it.second }, timeoutSec)
            if (outcomes.all { it is RealDelayProbe.Outcome.NotRun }) {
                log("Server test unavailable (${(outcomes.first() as RealDelayProbe.Outcome.NotRun).reason}).")
                return null
            }
            val winner = batch.zip(outcomes)
                .mapNotNull { (c, o) -> (o as? RealDelayProbe.Outcome.Delay)?.let { Winner(c.second, c.first, it.latencyMs) } }
                .minByOrNull { it.latencyMs }
            if (winner != null) {
                log("Fastest working server in round ${round + 1}: ${SecretRedactor.redact(winner.owner.name)} (${winner.latencyMs} ms).")
                return winner
            }
            log("Round ${round + 1}: none of ${batch.size} servers carried traffic.")
        }
        return null
    }

    private fun resolveAll(candidates: List<VlessProfile>, resolve: (VlessProfile) -> VlessProfile): List<Pair<VlessProfile, VlessProfile>> {
        val pool = Executors.newFixedThreadPool(candidates.size.coerceAtMost(6)) { r -> Thread(r, "server-race").apply { isDaemon = true } }
        try {
            val futures = candidates.map { c -> c to pool.submit(Callable { resolve(c) }) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RESOLVE_LIMIT_SEC)
            return futures.mapNotNull { (c, f) ->
                runCatching { c to f.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
                    .onFailure { log("Skipped ${SecretRedactor.redact(c.name)}: address not resolved.") }
                    .getOrNull()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        const val MAX_BATCHES = 2
        const val MAX_CANDIDATES = RealDelayProbe.MAX_BATCH * MAX_BATCHES
        private const val RESOLVE_LIMIT_SEC = 6L
        private const val RECENT_MS = 24 * 60 * 60 * 1000L

        /**
         * The candidates to race, best first, at most [limit]. [workingKinds] are the kinds that worked
         * on this network (most recent first), [first] goes ahead of everything (the server the user
         * picked), and [exclude] are left out (a server that just failed).
         */
        fun rank(
            profiles: List<VlessProfile>,
            workingKinds: List<String> = emptyList(),
            first: VlessProfile? = null,
            exclude: Set<String> = emptySet(),
            now: Long = System.currentTimeMillis(),
            limit: Int = MAX_CANDIDATES
        ): List<VlessProfile> {
            val eligible = profiles.filter {
                it.id !in exclude && it.id != first?.id && !it.id.startsWith("bridge-") && !it.id.startsWith("mesh-") &&
                    RuntimeCapabilities.unsupportedReason(it) == null
            }.distinctBy { it.effectiveFingerprint }
            fun score(p: VlessProfile): Double {
                var s = p.overallScore / 10.0
                val kindRank = workingKinds.indexOf(ConnectionKind.of(p))
                if (kindRank >= 0) s += 100.0 - 10 * kindRank
                val latency = p.lastLatencyMs
                if (latency != null && latency > 0 && (p.lastTestedTimestamp ?: 0L) > now - RECENT_MS) {
                    s += 40.0 + (20.0 - latency / 100.0).coerceAtLeast(0.0)
                }
                if (p.isFavorite) s += 15.0
                if (p.category == ServerCategory.OFFLINE) s -= 50.0
                return s
            }
            val sorted = eligible.sortedByDescending(::score).toMutableList()
            val head = listOfNotNull(first?.takeIf { it.id !in exclude && RuntimeCapabilities.unsupportedReason(it) == null })
            return (head + spreadKinds(sorted, head.map { ConnectionKind.of(it) })).take(limit)
        }

        /** Keeps the order but, within each batch of five, takes a kind not yet in that batch when one is left. */
        private fun spreadKinds(sorted: MutableList<VlessProfile>, firstBatchKinds: List<String>): List<VlessProfile> {
            val result = mutableListOf<VlessProfile>()
            val batchKinds = firstBatchKinds.toMutableSet()
            var slot = firstBatchKinds.size
            while (sorted.isNotEmpty()) {
                if (slot % RealDelayProbe.MAX_BATCH == 0) batchKinds.clear()
                val pick = sorted.firstOrNull { ConnectionKind.of(it) !in batchKinds } ?: sorted.first()
                sorted.remove(pick)
                batchKinds += ConnectionKind.of(pick)
                result += pick
                slot++
            }
            return result
        }
    }
}
