package com.example.vpn.smart

import com.example.core.SecretRedactor
import com.example.data.model.ServerCategory
import com.example.data.model.VlessProfile
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.stealth.ConnectionKind
import com.example.vpn.stealth.StealthPathFinder
import com.example.vpn.stealth.StealthVariants
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
 * cannot empty a whole round. [run] probes five at a time, together with a disguised form (a split
 * handshake, say) of the best TLS-looking servers, and stops at the first batch with a working path.
 *
 * A race only runs on a hostile network (the chosen server just carried no traffic), so the winner is
 * the working path most likely to keep working, not the fastest: kinds that look like ordinary HTTPS
 * (REALITY, CDN, TLS) before QUIC, WireGuard and unencrypted ones, which censors drop first when they
 * escalate. Latency decides within a kind. In the censorship simulator, taking the fastest landed on
 * a kind the next filter killed and cost three extra outages in eight minutes.
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
        val latencyMs: Long,
        /** The StealthVariants key when the winner is a disguised form of [owner]. */
        val variantKey: String? = null
    )

    /**
     * [resolve] turns a host name into an address outside the tunnel; candidates are resolved in
     * parallel and one that cannot be resolved is skipped. At most [maxBatches] rounds are probed.
     */
    fun run(
        candidates: List<VlessProfile>,
        resolve: (VlessProfile) -> VlessProfile,
        timeoutSec: Int,
        maxBatches: Int = MAX_BATCHES,
        /** Called for each candidate that carried no traffic. */
        onFailure: (VlessProfile) -> Unit = {},
        /**
         * Servers whose saved form just failed (the one the watchdog gave up on): only their disguised
         * form is tried, ahead of the others'. A filter that starts reading server names cuts a REALITY
         * connection, but the same server with a split handshake usually still works.
         */
        disguiseOnly: List<VlessProfile> = emptyList()
    ): Winner? {
        if (candidates.isEmpty() && disguiseOnly.isEmpty()) return null
        val dead = disguiseOnly.filter { ConnectionKind.of(it) in TLS_LOOKING }.take(DISGUISED)
        val paths = withDisguises(resolveAll(dead + candidates, resolve), dead.map { it.id }.toSet()).take(MAX_CANDIDATES)
        val batches = StealthPathFinder.parallelSafeBatches(paths) { it.profile }.take(maxBatches)
        for ((round, batch) in batches.withIndex()) {
            val outcomes = probe(batch.map { it.profile }, timeoutSec)
            if (outcomes.all { it is RealDelayProbe.Outcome.NotRun }) {
                log("Server test unavailable (${(outcomes.first() as RealDelayProbe.Outcome.NotRun).reason}).")
                return null
            }
            batch.zip(outcomes).filter { it.second is RealDelayProbe.Outcome.Failed }.forEach { onFailure(it.first.owner) }
            val winner = batch.zip(outcomes)
                .mapNotNull { (c, o) -> (o as? RealDelayProbe.Outcome.Delay)?.let { Winner(c.profile, c.owner, it.latencyMs, c.variantKey) } }
                .minWithOrNull(compareBy<Winner>({ kindRank(it.profile) }, { it.latencyMs }))
            if (winner != null) {
                log("Working server in round ${round + 1}: ${SecretRedactor.redact(winner.owner.name)}" +
                    "${if (winner.variantKey != null) " (disguised)" else ""} (${winner.latencyMs} ms).")
                return winner
            }
            log("Round ${round + 1}: none of ${batch.size} servers carried traffic.")
        }
        return null
    }

    private data class Path(val owner: VlessProfile, val profile: VlessProfile, val variantKey: String? = null)

    /**
     * Each server as saved (except [deadIds], whose saved form just failed); after each of the first
     * [DISGUISED] TLS-looking ones, its first stealth alternate.
     */
    private fun withDisguises(prepared: List<Pair<VlessProfile, VlessProfile>>, deadIds: Set<String>): List<Path> {
        val paths = mutableListOf<Path>()
        var disguised = 0
        for ((owner, resolved) in prepared) {
            if (owner.id !in deadIds) paths += Path(owner, resolved)
            if (disguised < DISGUISED && ConnectionKind.of(resolved) in TLS_LOOKING) {
                StealthVariants.of(resolved).firstOrNull()?.let {
                    paths += Path(owner, it.profile, it.key)
                    disguised++
                }
            }
        }
        return paths
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
        private const val DISGUISED = 2
        private val TLS_LOOKING = setOf("REALITY", "CDN", "TLS")

        internal fun kindRank(profile: VlessProfile): Int =
            ConnectionKind.ORDER.indexOf(ConnectionKind.of(profile)).let { if (it < 0) ConnectionKind.ORDER.size else it }
        const val MAX_CANDIDATES = RealDelayProbe.MAX_BATCH * MAX_BATCHES
        private const val RESOLVE_LIMIT_SEC = 6L
        private const val RECENT_MS = 24 * 60 * 60 * 1000L

        /**
         * The candidates to race, best first, at most [limit]. [workingKinds] are the kinds that worked
         * on this network (most recent first), [failedKinds] the ones that recently carried no traffic
         * there (they go last: a filter that just started rarely lifts within minutes), [first] goes
         * ahead of everything (the server the user picked), and [exclude] are left out.
         */
        fun rank(
            profiles: List<VlessProfile>,
            workingKinds: List<String> = emptyList(),
            failedKinds: Set<String> = emptySet(),
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
                if (ConnectionKind.of(p) in failedKinds) s -= 1000.0
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
