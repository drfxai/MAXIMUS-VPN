package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.vpn.smart.NetworkCapabilityProfile
import org.json.JSONArray
import org.json.JSONObject

/** How old a piece of evidence is. Old evidence only changes what is tried first; it never proves anything now. */
enum class Freshness(val maxAgeMs: Long) {
    FRESH(6L * 3_600_000), AGING(24L * 3_600_000), STALE(72L * 3_600_000), EXPIRED(Long.MAX_VALUE);

    companion object {
        fun of(measuredAt: Long, now: Long): Freshness = entries.first { now - measuredAt <= it.maxAgeMs }
    }
}

/** One tested method in the ranked result. */
data class RankedMethod(
    val profileId: String,
    val name: String,
    val family: PathFamily,
    val status: PathStatus,
    val stage: ConnectionStage,
    val latencyMs: Long?,
    val confidence: Double?,
    /** Why it was tested and what happened, in words. */
    val why: String,
    val attempts: Int,
    val passes: Int
) {
    /** A saved config as it is (not a derived copy, which keeps its parent's id): safe to select directly. */
    val savedConfig: Boolean get() = !why.startsWith("safe copy")

    fun toJson(): JSONObject = JSONObject().put("p", profileId).put("n", name).put("f", family.name).put("s", status.name).put("g", stage.name)
        .put("l", latencyMs ?: JSONObject.NULL).put("c", confidence ?: JSONObject.NULL).put("w", why).put("a", attempts).put("o", passes)

    companion object {
        fun fromJson(o: JSONObject): RankedMethod? = runCatching {
            RankedMethod(o.getString("p"), o.getString("n"), PathFamily.valueOf(o.getString("f")), PathStatus.valueOf(o.getString("s")),
                ConnectionStage.valueOf(o.getString("g")), if (o.isNull("l")) null else o.getLong("l"), if (o.isNull("c")) null else o.getDouble("c"),
                o.optString("w"), o.optInt("a"), o.optInt("o"))
        }.getOrNull()
    }
}

/** The result of one START FULL ANALYSIS run on one network session. Saved per network. */
data class AnalysisReport(
    val sessionId: String,
    val contextKey: String,
    val networkLabel: String,
    val startedAt: Long,
    val finishedAt: Long,
    val state: String,
    val confidence: Double,
    val mode: String,
    val planReasons: List<String>,
    val restrictions: List<String>,
    val paths: List<LivePath>,
    val ranked: List<RankedMethod>,
    val untested: List<String>,
    val note: String,
    /** The first config that passed a real request in this run (Fast Recovery). */
    val firstWorking: String? = null,
    /** How the plan changed during the run, in order. */
    val events: List<String> = emptyList(),
    /** Why the run stopped. */
    val stopReason: String? = null
) {
    val best: RankedMethod? get() = ranked.firstOrNull { it.stage.carriesTraffic }

    fun toJson(): JSONObject = JSONObject().put("s", sessionId).put("k", contextKey).put("l", networkLabel).put("t0", startedAt).put("t1", finishedAt)
        .put("st", state).put("c", confidence).put("m", mode).put("pr", JSONArray(planReasons)).put("r", JSONArray(restrictions))
        .put("pa", JSONArray().apply { paths.forEach { p ->
            put(JSONObject().put("k", p.key).put("t", p.title).put("s", p.status.name).put("g", p.stage.name).put("c", p.confidence ?: JSONObject.NULL)
                .put("l", p.latencyMs ?: JSONObject.NULL).put("at", p.checkedAt ?: JSONObject.NULL).put("r", p.reason).put("tr", p.tried).put("ps", p.passed))
        } })
        .put("rk", JSONArray().apply { ranked.forEach { put(it.toJson()) } }).put("u", JSONArray(untested)).put("n", note)
        .put("fw", firstWorking ?: JSONObject.NULL).put("ev", JSONArray(events)).put("sr", stopReason ?: JSONObject.NULL)

    companion object {
        private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()

        fun fromJson(o: JSONObject): AnalysisReport? = runCatching {
            val paths = o.optJSONArray("pa")?.let { a -> (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                runCatching {
                    LivePath(p.getString("k"), p.getString("t"), PathStatus.valueOf(p.getString("s")), ConnectionStage.valueOf(p.getString("g")),
                        if (p.isNull("c")) null else p.getDouble("c"), if (p.isNull("l")) null else p.getLong("l"), if (p.isNull("at")) null else p.getLong("at"),
                        p.optString("r"), p.optInt("tr"), p.optInt("ps"))
                }.getOrNull()
            } } ?: emptyList()
            AnalysisReport(o.getString("s"), o.getString("k"), o.optString("l"), o.getLong("t0"), o.getLong("t1"), o.optString("st"), o.optDouble("c", 0.0),
                o.optString("m"), strings(o.optJSONArray("pr")), strings(o.optJSONArray("r")), paths,
                o.optJSONArray("rk")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RankedMethod::fromJson) } } ?: emptyList(),
                strings(o.optJSONArray("u")), o.optString("n"),
                if (o.isNull("fw") || !o.has("fw")) null else o.optString("fw"), strings(o.optJSONArray("ev")),
                if (o.isNull("sr") || !o.has("sr")) null else o.optString("sr"))
        }.getOrNull()
    }
}

/** The pure decisions of a Full Analysis: what to test, how to rank it, what to show. Tested without Android. */
object FullAnalysis {

    data class Pick(val profile: VlessProfile, val family: PathFamily)

    /**
     * Picks at most [budget] configs: families the plan skips are left out; preferred families first;
     * one config per family before a second from any family (diversity); inside a family, configs that
     * worked on this network recently ([provenHere], fingerprints) go first.
     */
    fun select(
        profiles: List<Pair<VlessProfile, PathFamily>>,
        plan: ExperimentPlanner.Plan,
        provenHere: Set<String> = emptySet()
    ): List<Pick> {
        if (plan.budget <= 0) return emptyList()
        val byFamily = profiles.filter { (_, f) -> f !in plan.skip }
            .groupBy({ it.second }, { it.first })
            .mapValues { (_, list) -> list.sortedByDescending { it.effectiveFingerprint in provenHere } }
        val order = plan.prefer.filter { it in byFamily && it !in plan.lowPrior } +
            byFamily.keys.filter { it !in plan.prefer && it !in plan.lowPrior }.sortedBy { it.engine } +
            byFamily.keys.filter { it in plan.lowPrior }
        val picks = mutableListOf<Pick>()
        var round = 0
        while (picks.size < plan.budget) {
            var added = false
            for (f in order) {
                val p = byFamily[f]?.getOrNull(round) ?: continue
                picks += Pick(p, f)
                added = true
                if (picks.size >= plan.budget) break
            }
            if (!added) break
            round++
        }
        return picks
    }

    /**
     * Best first: real traffic beats everything; then two passes beat one (stability), then the plan's
     * preferred families, then latency. Security is a gate before this, never a score.
     */
    fun rank(methods: List<RankedMethod>, prefer: List<PathFamily>): List<RankedMethod> =
        methods.sortedWith(
            compareByDescending<RankedMethod> { it.stage.carriesTraffic }
                .thenBy { it.status == PathStatus.DEGRADED }
                .thenByDescending { it.status == PathStatus.VERIFIED }
                .thenByDescending { it.passes }
                .thenBy { prefer.indexOf(it.family).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        )

    /**
     * Verification verdict from the real requests of one method, in order. Two passes at least
     * [STABLE_WINDOW_MS] apart with no failure after the first pass: VERIFIED. A pass followed by a failed
     * recheck: DEGRADED. One pass (or two too close together): CANDIDATE. Only failures: FAILED.
     */
    fun verdict(outcomes: List<Boolean>, spanMs: Long): PathStatus {
        val first = outcomes.indexOf(true)
        return when {
            outcomes.isEmpty() -> PathStatus.NOT_TESTED
            first < 0 -> PathStatus.FAILED
            outcomes.drop(first).any { !it } -> PathStatus.DEGRADED
            outcomes.count { it } >= 2 && spanMs >= STABLE_WINDOW_MS -> PathStatus.VERIFIED
            else -> PathStatus.CANDIDATE
        }
    }

    /** The least time between the first and the last pass for a path to count as stable. */
    const val STABLE_WINDOW_MS = 3_000L

    /** Confidence from what was measured only: passes over attempts, capped below certainty. */
    fun confidence(passes: Int, attempts: Int): Double? = when {
        attempts == 0 -> null
        passes == 0 -> 0.0
        else -> (0.45 + 0.45 * passes / attempts).coerceAtMost(0.9)
    }

    /** The network-level rows of LIVE CONNECTIVITY PATHS, straight from the measurements. */
    fun networkPaths(p: NetworkCapabilityProfile?, now: Long): List<LivePath> {
        if (p == null) return emptyList()
        fun row(key: String, title: String, value: Boolean?, yes: PathStatus, no: PathStatus, why: String) =
            LivePath(key, title, when (value) { true -> yes; false -> no; null -> PathStatus.NOT_TESTED }, checkedAt = if (value == null) null else p.measuredAt,
                reason = if (value == null) "not measured" else why)
        val plainDns = when {
            p.dnsManipulated == true -> LivePath("dns-plain", "Plain DNS", PathStatus.BLOCKED, checkedAt = p.measuredAt, reason = "foreign names answered with block-page addresses")
            else -> row("dns-plain", "Plain DNS", p.dnsWorking, PathStatus.AVAILABLE, PathStatus.FAILED, "system resolver for a foreign name")
        }
        val quic = when (p.quicStatus) {
            "QUIC_AVAILABLE" -> PathStatus.AVAILABLE
            "QUIC_DEGRADED" -> PathStatus.DEGRADED
            // Suspected is never shown as blocked: silence alone does not prove a block.
            "QUIC_BLOCKED_SUSPECTED" -> PathStatus.BLOCKED_SUSPECTED
            "QUIC_UNRESPONSIVE" -> PathStatus.UNRESPONSIVE
            else -> PathStatus.NOT_TESTED
        }
        return listOf(
            plainDns,
            row("dns-doh", "Encrypted DNS (DoH)", p.dohReachable, PathStatus.AVAILABLE, PathStatus.BLOCKED_SUSPECTED, "one question to an IP-literal DoH resolver"),
            row("dns-dot", "DNS over TLS", p.dotReachable, PathStatus.AVAILABLE, PathStatus.BLOCKED_SUSPECTED, "verified TLS to port 853"),
            LivePath("dns-egress", "Recursive DNS abroad", when (p.recursiveDnsEgress) { true -> PathStatus.AVAILABLE; false -> PathStatus.FAILED; null -> PathStatus.NOT_TESTED },
                reason = if (p.recursiveDnsEgress == null) "needs a foreign authoritative test zone; not inferred" else "nonce observed"),
            LivePath("quic", "QUIC / HTTP/3", quic, checkedAt = if (quic == PathStatus.NOT_TESTED) null else p.measuredAt,
                reason = p.quicStatus?.lowercase()?.replace('_', ' ') ?: "not measured"),
            row("ipv6", "IPv6 abroad", if (p.ipv6Available == false) null else p.ipv6TlsOk, PathStatus.AVAILABLE, PathStatus.FAILED, "verified TLS over IPv6")
                .let { if (p.ipv6Available == false) it.copy(status = PathStatus.UNSUPPORTED, reason = "this network has no IPv6") else it }
        )
    }

    /** The best honest resolver measured on this network, or why none qualifies. */
    fun resolverPath(results: List<ResolverIntelligence.Result>): LivePath? {
        if (results.isEmpty()) return null
        val best = ResolverIntelligence.rank(results).first()
        val usable = results.count { it.usable }
        return if (best.usable) LivePath("dns-resolver", "Best DNS resolver here", PathStatus.AVAILABLE, checkedAt = best.measuredAt,
            latencyMs = best.rttMs, reason = "${best.label} ${best.address}: " + listOfNotNull("UDP".takeIf { best.udp == true }, "TCP".takeIf { best.tcp == true }).joinToString("+") +
                ", no block page or NXDOMAIN hijack · $usable of ${results.size} resolvers honest", tried = results.size, passed = usable)
        else LivePath("dns-resolver", "Best DNS resolver here", PathStatus.FAILED, checkedAt = best.measuredAt,
            reason = "none of ${results.size} resolvers answered honestly", tried = results.size)
    }

    /** One row per method family that has saved configs, from the tests of this run. */
    fun familyPaths(
        families: Set<PathFamily>,
        results: List<RankedMethod>,
        plan: ExperimentPlanner.Plan,
        notTestedReason: Map<PathFamily, String>,
        now: Long,
        notRequired: Set<PathFamily> = emptySet(),
        /** Families with no saved config: NOT_CONFIGURED, never FAILED. */
        notConfigured: Set<PathFamily> = emptySet()
    ): List<LivePath> = families.sortedBy { it.ordinal }.map { f ->
        val mine = results.filter { it.family == f }
        val passed = mine.filter { it.stage.carriesTraffic }
        val best = passed.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
        when {
            best != null -> LivePath("family-${f.name}", f.title,
                passed.firstOrNull { it.status == PathStatus.VERIFIED }?.status ?: passed.firstOrNull { it.status == PathStatus.CANDIDATE }?.status ?: best.status,
                best.stage, best.confidence, best.latencyMs, now, "real request passed through ${best.name}", mine.size, passed.size)
            f in notRequired -> LivePath("family-${f.name}", f.title, PathStatus.NOT_REQUIRED, reason = notTestedReason[f] ?: "enough verified paths already")
            f in notConfigured && mine.isEmpty() -> LivePath("family-${f.name}", f.title, PathStatus.NOT_CONFIGURED, reason = notTestedReason[f] ?: "no config of this family is saved")
            mine.isNotEmpty() && mine.all { it.status == PathStatus.RECENTLY_FAILED } -> LivePath("family-${f.name}", f.title, PathStatus.RECENTLY_FAILED,
                reason = mine.first().why, tried = mine.size)
            // A skip is a planning choice from other measurements, never proof that the family is blocked.
            plan.skip[f] != null -> LivePath("family-${f.name}", f.title, PathStatus.NOT_TESTED, reason = "skipped: ${plan.skip[f]}")
            mine.any { !it.status.equals(PathStatus.NOT_TESTED) } -> LivePath("family-${f.name}", f.title,
                if (mine.all { it.status == PathStatus.UNSUPPORTED }) PathStatus.UNSUPPORTED else PathStatus.FAILED,
                mine.maxOf { it.stage }, null, null, now, mine.first { it.status != PathStatus.NOT_TESTED }.why, mine.size, 0)
            else -> LivePath("family-${f.name}", f.title, PathStatus.NOT_TESTED, reason = notTestedReason[f] ?: mine.firstOrNull()?.why ?: "not reached within this run's test budget")
        }
    }

    /**
     * The family rows of the live view while the analysis runs: QUEUED until a config of the family is
     * tested, TESTING while one is, then its verdict. [finished] turns the leftover queue into NOT_TESTED
     * (or NOT_REQUIRED) with the reason. Pure.
     */
    fun liveFamilyRows(
        tracks: List<AdaptivePlanner.Track>,
        current: String?,
        plan: ExperimentPlanner.Plan,
        notTestedReason: Map<PathFamily, String>,
        sessionId: String,
        now: Long,
        finished: Boolean = false,
        stopReason: String? = null,
        notRequired: Set<PathFamily> = emptySet(),
        notConfigured: Set<PathFamily> = emptySet()
    ): List<LivePath> {
        val byFamily = tracks.groupBy { it.candidate.family }
        return (byFamily.keys + notTestedReason.keys).distinct().sortedBy { it.ordinal }.map { f ->
            val mine = byFamily[f].orEmpty()
            val tested = mine.filter { it.outcomes.isNotEmpty() }
            val working = tested.filter { it.status == PathStatus.VERIFIED || it.status == PathStatus.CANDIDATE || it.status == PathStatus.DEGRADED }
            val best = working.sortedWith(compareByDescending<AdaptivePlanner.Track> { it.status == PathStatus.VERIFIED }
                .thenBy { it.status == PathStatus.DEGRADED }.thenBy { it.bestLatency ?: Long.MAX_VALUE }).firstOrNull()
            val testing = mine.firstOrNull { it.candidate.id == current }
            val attempts = tested.sumOf { it.outcomes.size }
            val base = LivePath("family-${f.name}", f.title, PathStatus.NOT_TESTED, tried = tested.size, passed = working.size, attempts = attempts, sessionId = sessionId)
            when {
                testing != null -> base.copy(status = PathStatus.TESTING, latencyMs = best?.bestLatency, checkedAt = now,
                    reason = "testing ${testing.candidate.name}" + if (testing.outcomes.isNotEmpty()) " again (stability)" else "")
                best != null -> base.copy(status = best.status, stage = if (best.status == PathStatus.VERIFIED) ConnectionStage.STABILITY_VERIFIED else ConnectionStage.APPLICATION_REQUEST_PASSED,
                    confidence = confidence(best.passes, best.outcomes.size), latencyMs = best.bestLatency, checkedAt = best.lastPassAt ?: now,
                    reason = when (best.status) {
                        PathStatus.VERIFIED -> "${best.passes} real requests passed through ${best.candidate.name}, ${STABLE_WINDOW_MS / 1000}s+ apart"
                        PathStatus.DEGRADED -> "${best.candidate.name} passed, then failed its recheck"
                        else -> "one real request passed through ${best.candidate.name}; recheck pending"
                    })
                tested.isNotEmpty() -> base.copy(status = PathStatus.FAILED, confidence = 0.0, checkedAt = now,
                    reason = tested.firstNotNullOfOrNull { it.failure } ?: "no real request passed")
                f in notRequired -> base.copy(status = PathStatus.NOT_REQUIRED, reason = "not needed: ${stopReason ?: "enough verified paths"}")
                f in notConfigured && mine.isEmpty() -> base.copy(status = PathStatus.NOT_CONFIGURED, reason = notTestedReason[f] ?: "no config of this family is saved")
                mine.isNotEmpty() && mine.all { it.notTested?.startsWith("recently failed") == true } ->
                    base.copy(status = PathStatus.RECENTLY_FAILED, reason = mine.first().notTested!!)
                plan.skip[f] != null -> base.copy(reason = "skipped: ${plan.skip[f]}")
                !finished && mine.any { it.notTested == null } -> base.copy(status = PathStatus.QUEUED, reason = "waiting for its turn")
                else -> base.copy(reason = notTestedReason[f] ?: mine.firstNotNullOfOrNull { it.notTested }?.let { "not tested: $it" }
                    ?: stopReason?.let { "not reached: $it" } ?: "not reached within this run's test budget")
            }
        }
    }

    /** What this run did not measure, said plainly. */
    fun untested(p: NetworkCapabilityProfile?, reading: NetworkStateReading?, burstMeasured: Boolean = false): List<String> =
        reading?.notTested.orEmpty() + listOf(
            "ECH handshake (no ECH-capable measurement on this phone yet)",
            "MTU / path MTU",
            "Upload / download throughput and asymmetry",
            "External DNS leak test",
            "Multi-vantage quorum (needs a signed probe manifest; design only)"
        ) + (if (!burstMeasured) listOf("Packet loss and jitter") else emptyList()) +
            if (p?.recursiveDnsEgress == null) listOf("Recursive DNS egress (needs a controlled foreign DNS zone)") else emptyList()

    /** The packet loss / jitter row, from a small UDP DNS burst to the best resolver. */
    fun burstPath(b: ResolverIntelligence.Burst): LivePath {
        val loss = b.lossPercent
        val status = when {
            b.answered == 0 -> PathStatus.FAILED
            loss >= 30 || b.rateLimited -> PathStatus.DEGRADED
            else -> PathStatus.AVAILABLE
        }
        return LivePath("udp-burst", "Packet loss / jitter (UDP)", status, checkedAt = b.measuredAt, latencyMs = b.medianMs, attempts = b.sent,
            reason = "${b.sent} DNS queries to ${b.label}: $loss% lost" + (b.jitterMs?.let { ", jitter $it ms" } ?: "") +
                if (b.rateLimited) ", answers stopped part-way (rate limiting suspected)" else "")
    }
}
