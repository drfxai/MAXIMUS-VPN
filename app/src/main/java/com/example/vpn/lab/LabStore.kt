package com.example.vpn.lab

import org.json.JSONArray
import org.json.JSONObject

/**
 * The LAB's local memory, JSON behind load/save lambdas like the other connectivity stores (SharedPreferences
 * on the phone, memory in tests): recent experiments, Verified Network Profiles, discoveries and the networks
 * seen. Bounded in size; never uploaded; unreadable data starts empty rather than crashing.
 */
class LabStore(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** The last real measurement of a network. Shown as "last measured", never as current, once the phone left it. */
    data class NetworkSeen(val contextKey: String, val label: String, val lastMeasuredAt: Long, val health: Int?, val summary: String) {
        fun toJson(): JSONObject = JSONObject().put("k", contextKey).put("l", label).put("t", lastMeasuredAt).put("h", health ?: JSONObject.NULL).put("s", summary)
        companion object {
            fun fromJson(o: JSONObject) = NetworkSeen(o.optString("k"), o.optString("l"), o.optLong("t"), if (o.isNull("h")) null else o.optInt("h"), o.optString("s"))
        }
    }

    private val experiments = mutableListOf<LabExperiment>()
    private val verified = mutableListOf<VerifiedNetworkProfile>()
    private val discoveries = mutableListOf<LabDiscovery>()
    private val networks = linkedMapOf<String, NetworkSeen>()
    private var automation = AutomationLevel.RECOMMEND
    private var nextProfileNumber = 1
    private var nextExperimentNumber = 1

    init { parse(load()) }

    @Synchronized fun automation(): AutomationLevel = automation
    @Synchronized fun setAutomation(level: AutomationLevel) { automation = level; persist() }

    @Synchronized fun experiments(): List<LabExperiment> = experiments.toList()
    @Synchronized fun verifiedProfiles(): List<VerifiedNetworkProfile> = verified.toList()
    @Synchronized fun discoveries(): List<LabDiscovery> = discoveries.sortedByDescending { it.at }
    @Synchronized fun networks(): List<NetworkSeen> = networks.values.sortedByDescending { it.lastMeasuredAt }

    @Synchronized fun newExperimentId(): String = "EXP-%03d".format(nextExperimentNumber++).also { persist() }

    /** Interrupted experiments (process killed mid-run) are closed as CANCELLED when loaded; nothing resumes silently. */
    @Synchronized
    fun saveExperiment(e: LabExperiment) {
        experiments.removeAll { it.experimentId == e.experimentId }
        experiments += e
        while (experiments.size > MAX_EXPERIMENTS) experiments.removeAt(0)
        persist()
    }

    @Synchronized
    fun recordNetwork(context: NetworkContext, health: Int?, summary: String) {
        networks.remove(context.contextKey)
        networks[context.contextKey] = NetworkSeen(context.contextKey, context.label, clock(), health, summary)
        while (networks.size > MAX_NETWORKS) networks.remove(networks.keys.first())
        persist()
    }

    @Synchronized
    fun addDiscovery(d: LabDiscovery) {
        // One card per subject: a newer finding replaces the older one instead of piling up.
        discoveries.removeAll { it.id == d.id }
        discoveries += d
        while (discoveries.size > MAX_DISCOVERIES) discoveries.remove(discoveries.minByOrNull { it.at }!!)
        persist()
    }

    /**
     * Folds a finished experiment's candidates into Verified Network Profiles for its network: VERIFIED and
     * CANDIDATE copies become (or update) profiles; a retired copy retires its profile. Returns what changed.
     */
    @Synchronized
    fun absorb(e: LabExperiment, policy: CandidatePromotionPolicy = CandidatePromotionPolicy.DEFAULT): List<VerifiedNetworkProfile> {
        val now = clock()
        val changed = mutableListOf<VerifiedNetworkProfile>()
        for (c in e.candidates.filter { it.securityPassed && it.stats.attempts > 0 }) {
            val i = verified.indexOfFirst { it.contextKey == e.contextKey && it.parentFingerprint == c.parentFingerprint && it.mutationProfileId == c.mutationProfileId && it.endpoint == c.endpoint }
            if (i < 0 && c.state != PromotionState.VERIFIED && c.state != PromotionState.CANDIDATE) continue
            val previous = verified.getOrNull(i)
            val stats = previous?.let { merge(it.stats, c.stats) } ?: c.stats
            val state = policy.evaluate(stats, true, previous?.state ?: c.state, now)
            val profile = VerifiedNetworkProfile(
                profileId = previous?.profileId ?: "LAB-NET-%03d".format(nextProfileNumber++), contextKey = e.contextKey,
                networkLabel = e.networkLabel, parentProfileId = c.parentProfileId, parentFingerprint = c.parentFingerprint,
                mutationProfileId = c.mutationProfileId, endpoint = c.endpoint, strategy = strategyName(c.mutationProfileId),
                state = state, stats = stats, confidence = policy.confidence(stats, now),
                lastVerifiedAt = stats.lastSuccessAt ?: previous?.lastVerifiedAt ?: now, disabled = previous?.disabled ?: false
            )
            if (i >= 0) verified[i] = profile else verified += profile
            changed += profile
        }
        while (verified.size > MAX_PROFILES) verified.remove(verified.filter { it.state == PromotionState.RETIRED }.minByOrNull { it.lastVerifiedAt } ?: verified.minByOrNull { it.lastVerifiedAt }!!)
        persist()
        return changed
    }

    private fun merge(a: CandidateStats, b: CandidateStats) = CandidateStats(
        attempts = a.attempts + b.attempts, successes = a.successes + b.successes, latenciesMs = (a.latenciesMs + b.latenciesMs).takeLast(20),
        dnsThroughTunnelOk = b.dnsThroughTunnelOk ?: a.dnsThroughTunnelOk,
        failures = (a.failures.keys + b.failures.keys).associateWith { (a.failures[it] ?: 0) + (b.failures[it] ?: 0) },
        firstSuccessAt = a.firstSuccessAt ?: b.firstSuccessAt, lastSuccessAt = b.lastSuccessAt ?: a.lastSuccessAt,
        lastFailureAt = b.lastFailureAt ?: a.lastFailureAt,
        consecutiveFailures = if (b.attempts == 0) a.consecutiveFailures else if (b.successes > 0) b.consecutiveFailures else a.consecutiveFailures + b.consecutiveFailures
    )

    @Synchronized
    fun setDisabled(profileId: String, disabled: Boolean) {
        val i = verified.indexOfFirst { it.profileId == profileId }
        if (i >= 0) { verified[i] = verified[i].copy(disabled = disabled); persist() }
    }

    @Synchronized
    fun retire(profileId: String) {
        val i = verified.indexOfFirst { it.profileId == profileId }
        if (i >= 0) { verified[i] = verified[i].copy(state = PromotionState.RETIRED); persist() }
    }

    /** Mutations retired for a config on a network: not generated again there. */
    @Synchronized
    fun retiredMutations(contextKey: String, parentFingerprint: String): Set<String> =
        verified.filter { it.contextKey == contextKey && it.parentFingerprint == parentFingerprint && it.state == PromotionState.RETIRED }.map { it.mutationProfileId }.toSet() +
            experiments.filter { it.contextKey == contextKey && it.parentFingerprint == parentFingerprint }
                .flatMap { e -> e.candidates.filter { it.state == PromotionState.RETIRED || it.state == PromotionState.REJECTED }.map { it.mutationProfileId } }

    /**
     * Network Memory (spec section 31): when a network returns, the profiles that worked there, best first, and
     * whether broader exploration is needed (none usable, or all degraded).
     */
    data class ReturnPlan(val revalidate: List<VerifiedNetworkProfile>, val explore: Boolean, val reason: String)

    @Synchronized
    fun planFor(contextKey: String, policy: CandidatePromotionPolicy = CandidatePromotionPolicy.DEFAULT): ReturnPlan {
        val now = clock()
        val usable = verified.filter { it.contextKey == contextKey && !it.disabled && it.state in setOf(PromotionState.VERIFIED, PromotionState.CANDIDATE, PromotionState.DEGRADED) }
            .sortedWith(compareByDescending<VerifiedNetworkProfile> { it.state == PromotionState.VERIFIED }.thenByDescending { policy.confidence(it.stats, now) })
        return when {
            usable.isEmpty() -> ReturnPlan(emptyList(), true, "Nothing verified on this network yet.")
            usable.all { it.state == PromotionState.DEGRADED } -> ReturnPlan(usable.take(2), true, "Earlier profiles degraded; retest them and look further.")
            else -> ReturnPlan(usable.take(3), false, "Reusing what worked here; a quick recheck first.")
        }
    }

    private fun strategyName(mutation: String): String = when {
        mutation.startsWith("bpb-fragment") || mutation.startsWith("fragment") -> "Fragment"
        mutation.startsWith("fingerprint") -> "TLS fingerprint"
        mutation.startsWith("ech") -> "ECH"
        mutation.startsWith("alpn") -> "ALPN HTTP/1.1"
        mutation.startsWith("endpoint-ipv6") -> "IPv6 edge address"
        mutation.startsWith("endpoint") -> "Edge address"
        mutation == CandidateGenerator.TRANSPORT_H2 -> "HTTP/2 instead of QUIC"
        mutation == CandidateGenerator.FAMILY_V6 -> "IPv6 first"
        else -> mutation.substringBefore('@')
    }

    private fun persist() {
        val root = JSONObject()
            .put("v", 1).put("auto", automation.name).put("np", nextProfileNumber).put("ne", nextExperimentNumber)
            .put("e", JSONArray().apply { experiments.forEach { put(it.toJson()) } })
            .put("p", JSONArray().apply { verified.forEach { put(it.toJson()) } })
            .put("d", JSONArray().apply { discoveries.forEach { put(it.toJson()) } })
            .put("n", JSONArray().apply { networks.values.forEach { put(it.toJson()) } })
        runCatching { save(root.toString()) }
    }

    private fun parse(text: String?) {
        val o = runCatching { JSONObject(text ?: return) }.getOrNull() ?: return
        automation = runCatching { AutomationLevel.valueOf(o.optString("auto")) }.getOrDefault(AutomationLevel.RECOMMEND)
        nextProfileNumber = o.optInt("np", 1).coerceAtLeast(1)
        nextExperimentNumber = o.optInt("ne", 1).coerceAtLeast(1)
        o.optJSONArray("e")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let(LabExperiment::fromJson)?.let { e ->
                experiments += if (e.state.terminal) e else e.copy(state = ExperimentState.CANCELLED, endTime = e.endTime ?: clock(), note = "Interrupted (the app was closed).")
            }
        }
        o.optJSONArray("p")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(VerifiedNetworkProfile::fromJson)?.let { verified += it } }
        o.optJSONArray("d")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(LabDiscovery::fromJson)?.let { discoveries += it } }
        o.optJSONArray("n")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { NetworkSeen.fromJson(it) }?.let { networks[it.contextKey] = it } }
    }

    companion object {
        const val MAX_EXPERIMENTS = 30
        const val MAX_PROFILES = 60
        const val MAX_DISCOVERIES = 40
        const val MAX_NETWORKS = 12
    }
}

/**
 * Tracks network sessions (spec section 50). A new session starts when the link kind, carrier code or address
 * families change, or the network is lost; experiments of the old session are then stale.
 */
class NetworkSessionTracker(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile var current: NetworkContext? = null
        private set
    private var counter = 0

    /** Returns the context for these observations and whether it is a new session. */
    @Synchronized
    fun observe(networkKey: String, families: String, label: String): Pair<NetworkContext, Boolean> {
        val c = current
        if (c != null && c.networkKey == networkKey && c.families == families) return c to false
        counter++
        val next = NetworkContext("NS-${clock().toString(36)}-$counter", networkKey, families, clock(), label)
        current = next
        return next to true
    }

    @Synchronized
    fun lost() { current = null }

    fun isCurrent(sessionId: String): Boolean = current?.sessionId == sessionId

    companion object {
        fun families(ipv4: Boolean?, ipv6: Boolean?): String = when {
            ipv4 == true && ipv6 == true -> "IPv4+IPv6"
            ipv6 == true -> "IPv6"
            ipv4 == true -> "IPv4"
            else -> "unknown"
        }
    }
}
