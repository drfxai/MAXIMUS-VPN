package com.example.vpn.smart

import com.example.core.SecretRedactor
import com.example.data.model.ServerCategory
import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.ConnectionScore
import com.example.vpn.connectivity.DiversitySelector
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
 * A race after a failure (the chosen server just carried no traffic, failover) wants the working path
 * most likely to keep working, found fast: kinds that look like ordinary HTTPS (REALITY, CDN, TLS)
 * before QUIC, WireGuard and unencrypted ones, which censors drop first when they escalate. Latency
 * decides within a kind. In the censorship simulator, taking the fastest landed on a kind the next
 * filter killed and cost three extra outages in eight minutes.
 *
 * Smart Connect pressed by the user asks for [contenders] > 1: the race goes on until that many paths
 * carried a request, rechecks them a few seconds apart as the LAB's Full Analysis does, and picks with
 * [ContenderPick] (a path that passed every recheck first, then what the LAB proved here, then request
 * time with a kind and measured-speed penalty). The first path that answered is often the one a filter
 * cuts seconds later, or a slow one; the others become [Winner.runnersUp].
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
        val variantKey: String? = null,
        /** Other paths that carried traffic in the same race, best first (only when contenders were compared). */
        val runnersUp: List<Winner> = emptyList()
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
        disguiseOnly: List<VlessProfile> = emptyList(),
        /**
         * Checked before each round so a cancelled attempt (the user pressed Stop, or a newer Connect
         * arrived) stops racing at once instead of probing every remaining batch; probing is a blocking
         * thread-pool call, so coroutine cancellation alone cannot break into it.
         */
        shouldStop: () -> Boolean = { false },
        /** Gather this many working paths before choosing (1: the first round with one wins, as after a failure). */
        contenders: Int = 1,
        /** Paths already known to work now (the selected server just passed); they compete with the race. */
        seed: List<Winner> = emptyList(),
        /** Saved servers the LAB's Full Analysis found carrying traffic on this network. */
        preferred: Set<String> = emptySet(),
        /** Request time added to a server's score from earlier measurements on this network (RouteSpeedBook). */
        penaltyMs: (VlessProfile) -> Long = { 0L },
        rechecks: Int = ContenderPick.RECHECKS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        clock: () -> Long = System::currentTimeMillis
    ): Winner? {
        if (contenders > 1) return compare(candidates, resolve, timeoutSec, maxBatches, onFailure, disguiseOnly, shouldStop,
            contenders, seed, preferred, penaltyMs, rechecks, sleep, clock)
        if (candidates.isEmpty() && disguiseOnly.isEmpty()) return null
        val dead = disguiseOnly.filter { ConnectionKind.of(it) in TLS_LOOKING }.take(DISGUISED)
        val paths = withDisguises(resolveAll(dead + candidates, resolve), dead.map { it.id }.toSet()).take(maxBatches * RealDelayProbe.MAX_BATCH)
        val batches = StealthPathFinder.parallelSafeBatches(paths) { it.profile }.take(maxBatches)
        for ((round, batch) in batches.withIndex()) {
            if (shouldStop()) return null
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

    /** See the class comment: gather [want] working paths, recheck them, pick with [ContenderPick]. */
    private fun compare(
        candidates: List<VlessProfile>,
        resolve: (VlessProfile) -> VlessProfile,
        timeoutSec: Int,
        maxBatches: Int,
        onFailure: (VlessProfile) -> Unit,
        disguiseOnly: List<VlessProfile>,
        shouldStop: () -> Boolean,
        want: Int,
        seed: List<Winner>,
        preferred: Set<String>,
        penaltyMs: (VlessProfile) -> Long,
        rechecks: Int,
        sleep: (Long) -> Unit,
        clock: () -> Long
    ): Winner? {
        class Found(val winner: Winner, val samples: MutableList<Long?>)
        val found = seed.map { Found(it, mutableListOf(it.latencyMs)) }.toMutableList()
        var lastProbeAt = clock()
        if (found.size < want && (candidates.isNotEmpty() || disguiseOnly.isNotEmpty())) {
            val dead = disguiseOnly.filter { ConnectionKind.of(it) in TLS_LOOKING }.take(DISGUISED)
            val seeded = seed.map { it.owner.id }.toSet()
            val paths = withDisguises(resolveAll(dead + candidates.filter { it.id !in seeded }, resolve), dead.map { it.id }.toSet())
                .take(maxBatches * RealDelayProbe.MAX_BATCH)
            val batches = StealthPathFinder.parallelSafeBatches(paths) { it.profile }.take(maxBatches)
            for ((round, batch) in batches.withIndex()) {
                if (shouldStop()) return null
                val outcomes = probe(batch.map { it.profile }, timeoutSec)
                lastProbeAt = clock()
                if (outcomes.all { it is RealDelayProbe.Outcome.NotRun }) {
                    log("Server test unavailable (${(outcomes.first() as RealDelayProbe.Outcome.NotRun).reason}).")
                    break
                }
                batch.zip(outcomes).forEach { (c, o) ->
                    when (o) {
                        is RealDelayProbe.Outcome.Failed -> onFailure(c.owner)
                        // One path per server: its disguised form only counts when the saved form did not answer.
                        is RealDelayProbe.Outcome.Delay -> if (found.none { it.winner.owner.id == c.owner.id }) {
                            found += Found(Winner(c.profile, c.owner, o.latencyMs, c.variantKey), mutableListOf(o.latencyMs))
                        }
                        is RealDelayProbe.Outcome.NotRun -> Unit
                    }
                }
                log("Round ${round + 1}: ${outcomes.count { it is RealDelayProbe.Outcome.Delay }} of ${batch.size} servers carried traffic " +
                    "(${found.size} working so far).")
                if (found.size >= want) break
            }
        }
        if (found.isEmpty()) return null
        // The best few by their first request are rechecked together, a few seconds apart.
        val pool = found.sortedBy { it.winner.latencyMs + kindRank(it.winner.profile) * ContenderPick.KIND_STEP_MS + penaltyMs(it.winner.owner) }
            .take(RealDelayProbe.MAX_BATCH)
        if (pool.size > 1) {
            for (i in 0 until rechecks) {
                if (shouldStop()) return null
                sleep(ContenderPick.waitBeforeRecheck(lastProbeAt, clock()))
                if (shouldStop()) return null
                val outcomes = probe(pool.map { it.winner.profile }, timeoutSec)
                lastProbeAt = clock()
                if (outcomes.all { it is RealDelayProbe.Outcome.NotRun }) break
                pool.zip(outcomes).forEach { (f, o) ->
                    when (o) {
                        is RealDelayProbe.Outcome.Delay -> f.samples += o.latencyMs
                        is RealDelayProbe.Outcome.Failed -> f.samples += null
                        is RealDelayProbe.Outcome.NotRun -> Unit
                    }
                }
            }
        }
        val ordered = ContenderPick.order(pool.map { f ->
            ContenderPick.Contender(f, kindRank(f.winner.profile), f.samples.toList(), f.winner.owner.id in preferred, penaltyMs(f.winner.owner))
        })
        ordered.forEach { c ->
            log("Contender ${SecretRedactor.redact(c.item.winner.owner.name)}: ${c.samples.joinToString("/") { it?.toString() ?: "x" }} ms" +
                "${if (c.preferred) ", proven by LAB" else ""}${if (c.penaltyMs > 0) ", slow here before (+${c.penaltyMs} ms)" else ""}" +
                "${if (!c.stable) ", failed a recheck" else ""}.")
        }
        val chosen = ordered.map { it.item.winner.copy(latencyMs = it.medianMs.coerceAtLeast(1)) }
        val best = chosen.first()
        log("Chose ${SecretRedactor.redact(best.owner.name)}${if (best.variantKey != null) " (disguised)" else ""} of ${found.size} working " +
            "(${best.latencyMs} ms median).")
        return best.copy(runnersUp = chosen.drop(1))
    }

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
        // A thread pool of size 0 throws; every server can be skipped as failed here moments ago.
        if (candidates.isEmpty()) return emptyList()
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
        /**
         * Smart Connect and a failed connect race every saved server up to [FULL_LIMIT] (plus the
         * disguised forms), not only the best ten: on a filtered network the few that still work are
         * often far down the ranking, and Test All on the same list found them where two rounds did not.
         */
        const val FULL_LIMIT = 30
        const val FULL_BATCHES = (FULL_LIMIT + DISGUISED + RealDelayProbe.MAX_BATCH - 1) / RealDelayProbe.MAX_BATCH
        /** Below this a request through a filtered network times out before it finishes (Test All uses 6 s). */
        const val FULL_MIN_TIMEOUT_SEC = 6
        private val TLS_LOOKING = setOf("REALITY", "CDN", "TLS")

        internal fun kindRank(profile: VlessProfile): Int =
            ConnectionKind.ORDER.indexOf(ConnectionKind.of(profile)).let { if (it < 0) ConnectionKind.ORDER.size else it }
        const val MAX_CANDIDATES = RealDelayProbe.MAX_BATCH * MAX_BATCHES
        private const val RESOLVE_LIMIT_SEC = 6L
        private const val PREFERRED_FIRST = 2
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
            limit: Int = MAX_CANDIDATES,
            /** Iran intelligence: a bounded nudge (±5) for eligible servers only; see IranIntelligence. */
            adjust: (VlessProfile) -> Double = { 0.0 },
            /** Saved servers the LAB's Full Analysis found carrying traffic on this network: raced first. */
            preferred: Set<String> = emptySet(),
            /** Request time from earlier measurements on this network (RouteSpeedBook): a stall or a slow download ranks lower. */
            penaltyMs: (VlessProfile) -> Long = { 0L }
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
                s += adjust(p).coerceIn(-ConnectionScore.MAX_ADJUSTMENT, ConnectionScore.MAX_ADJUSTMENT)
                s -= penaltyMs(p) / 50.0
                return s
            }
            val sorted = eligible.sortedByDescending(::score)
            // What the LAB proved here is in the first round, right after the user's own server.
            val proven = sorted.filter { it.id in preferred }.take(PREFERRED_FIRST)
            val byScore = sorted - proven.toSet()
            val head = listOfNotNull(first?.takeIf { it.id !in exclude && RuntimeCapabilities.unsupportedReason(it) == null }) + proven
            // The few that are raced never all sit on one CDN, network or source (DiversitySelector); ties keep their order.
            val position = byScore.withIndex().associate { (i, p) -> p.id to "%06d".format(i) }
            val diverse = DiversitySelector.select(byScore, ::score, DiversitySelector::traitsOf, { position.getValue(it.id) },
                limit = (limit - head.size).coerceIn(0, DiversitySelector.MAX))
            return (head + spreadKinds(diverse.toMutableList(), head.map { ConnectionKind.of(it) })).take(limit)
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
