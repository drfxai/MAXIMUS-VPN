package com.example.vpn.hub

import com.example.vpn.diagnostics.FailureStage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The life of a free config. The list builder runs outside Iran, so the best it can say is
 * GLOBAL_VERIFIED. Everything after that comes from this phone's own measurements on its own network:
 * a config earns IRAN_VERIFIED only by carrying traffic here, more than once.
 *
 * The names follow the plan; the app shows them as "verified on your network" rather than as a claim
 * about Iran as a whole.
 */
enum class FreeConfigLifecycle {
    NEW,
    GLOBAL_VERIFIED,
    IRAN_PROBATION,
    IRAN_VERIFIED,
    DEGRADED,
    DEAD,
    QUARANTINED;

    /** Words for the screen; never "works in Iran". */
    val label: String get() = when (this) {
        NEW -> "New"
        GLOBAL_VERIFIED -> "Checked by the list builder (outside Iran)"
        IRAN_PROBATION -> "Worked once on your network"
        IRAN_VERIFIED -> "Verified on your network"
        DEGRADED -> "Failing lately on your network"
        DEAD -> "Not working on your network"
        QUARANTINED -> "Refused: unsafe settings"
    }
}

/** Global evidence (from the list builder) and Iran evidence (from this phone) are kept apart. */
enum class GlobalStatus { GLOBAL_VERIFIED, GLOBAL_FAILED, UNKNOWN }

/** One anonymous measurement of one config on this phone's network. No content, no identity. */
data class ConnectivityMeasurement(
    val timestamp: Long,
    val kind: Kind,
    val success: Boolean,
    /** Anonymous network bucket (NetworkCapabilityProfile.key()), or null when not measured. */
    val networkKey: String? = null,
    val rttMs: Long? = null,
    val failureStage: FailureStage? = null,
    /** How long the VPN session stayed up on this config, for [Kind.CONNECTION]. */
    val connectedMs: Long? = null
) {
    enum class Kind {
        /** A real request through the config before connecting (Ping / Test all). */
        TEST,
        /** A VPN session whose traffic was verified through the tunnel. */
        CONNECTION
    }
}

/** Everything this phone has measured for one config, by its fingerprint. */
data class LocalEvidence(
    val fingerprint: String,
    val lifecycle: FreeConfigLifecycle = FreeConfigLifecycle.GLOBAL_VERIFIED,
    val attempts: Int = 0,
    val successes: Int = 0,
    val consecutiveFailures: Int = 0,
    val verifiedConnections: Int = 0,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val lastFailureStage: FailureStage? = null,
    /** Latest round-trip times, newest last, at most [RTT_SAMPLES]. */
    val rttMs: List<Long> = emptyList(),
    val connectedMsTotal: Long = 0,
    val lastNetworkKey: String? = null
) {
    val failureRate: Double get() = if (attempts == 0) 0.0 else (attempts - successes).toDouble() / attempts

    fun toJson(): JSONObject = JSONObject().apply {
        put("f", fingerprint)
        put("l", lifecycle.name)
        put("a", attempts)
        put("s", successes)
        put("cf", consecutiveFailures)
        put("vc", verifiedConnections)
        lastSuccessAt?.let { put("ls", it) }
        lastFailureAt?.let { put("lf", it) }
        lastFailureStage?.let { put("st", it.name) }
        put("r", JSONArray(rttMs))
        put("ct", connectedMsTotal)
        lastNetworkKey?.let { put("n", it) }
    }

    companion object {
        const val RTT_SAMPLES = 8

        fun fromJson(o: JSONObject): LocalEvidence = LocalEvidence(
            fingerprint = o.getString("f"),
            lifecycle = runCatching { FreeConfigLifecycle.valueOf(o.optString("l")) }.getOrDefault(FreeConfigLifecycle.GLOBAL_VERIFIED),
            attempts = o.optInt("a"),
            successes = o.optInt("s"),
            consecutiveFailures = o.optInt("cf"),
            verifiedConnections = o.optInt("vc"),
            lastSuccessAt = if (o.has("ls")) o.optLong("ls") else null,
            lastFailureAt = if (o.has("lf")) o.optLong("lf") else null,
            lastFailureStage = o.optString("st").takeIf { it.isNotBlank() }?.let { runCatching { FailureStage.valueOf(it) }.getOrNull() },
            rttMs = o.optJSONArray("r")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty(),
            connectedMsTotal = o.optLong("ct"),
            lastNetworkKey = o.optString("n").ifBlank { null }
        )
    }
}

/**
 * Deterministic lifecycle rules over measurements. Nothing here guesses: each state names the
 * evidence that produced it.
 */
object FreeConfigLifecycleRules {
    /** Successes needed (with no recent failure streak) before a config counts as verified here. */
    const val VERIFY_SUCCESSES = 3
    /** A verified VPN session counts as this many test successes: real traffic passed. */
    const val CONNECTION_WEIGHT = 2
    const val DEGRADED_AFTER_FAILURES = 2
    const val DEAD_AFTER_FAILURES = 4
    /** A config with no success for this long, after failures, is dead rather than degraded. */
    const val DEAD_AFTER_MS = 24L * 60 * 60 * 1000

    fun apply(e: LocalEvidence, m: ConnectivityMeasurement): LocalEvidence {
        if (e.lifecycle == FreeConfigLifecycle.QUARANTINED) return e
        val weight = if (m.kind == ConnectivityMeasurement.Kind.CONNECTION && m.success) CONNECTION_WEIGHT else 1
        val next = if (m.success) e.copy(
            attempts = e.attempts + weight,
            successes = e.successes + weight,
            consecutiveFailures = 0,
            verifiedConnections = e.verifiedConnections + if (m.kind == ConnectivityMeasurement.Kind.CONNECTION) 1 else 0,
            lastSuccessAt = m.timestamp,
            rttMs = (e.rttMs + listOfNotNull(m.rttMs)).takeLast(LocalEvidence.RTT_SAMPLES),
            connectedMsTotal = e.connectedMsTotal + (m.connectedMs ?: 0),
            lastNetworkKey = m.networkKey ?: e.lastNetworkKey
        ) else e.copy(
            attempts = e.attempts + 1,
            consecutiveFailures = e.consecutiveFailures + 1,
            lastFailureAt = m.timestamp,
            lastFailureStage = m.failureStage ?: FailureStage.UNKNOWN,
            lastNetworkKey = m.networkKey ?: e.lastNetworkKey
        )
        return next.copy(lifecycle = lifecycleOf(next, m.timestamp))
    }

    fun lifecycleOf(e: LocalEvidence, now: Long): FreeConfigLifecycle = when {
        e.lifecycle == FreeConfigLifecycle.QUARANTINED -> FreeConfigLifecycle.QUARANTINED
        e.attempts == 0 -> e.lifecycle
        e.consecutiveFailures >= DEAD_AFTER_FAILURES &&
            (e.lastSuccessAt == null || now - e.lastSuccessAt >= DEAD_AFTER_MS) -> FreeConfigLifecycle.DEAD
        e.consecutiveFailures >= DEGRADED_AFTER_FAILURES && e.successes > 0 -> FreeConfigLifecycle.DEGRADED
        e.consecutiveFailures >= DEGRADED_AFTER_FAILURES -> FreeConfigLifecycle.DEAD
        e.successes >= VERIFY_SUCCESSES && e.consecutiveFailures == 0 -> FreeConfigLifecycle.IRAN_VERIFIED
        e.successes > 0 -> FreeConfigLifecycle.IRAN_PROBATION
        else -> FreeConfigLifecycle.GLOBAL_VERIFIED
    }
}

/**
 * The phone's measurements for free configs, by fingerprint, kept across restarts and bounded to
 * [MAX_ENTRIES] (least recently measured dropped first). Stores no link, address or credential.
 */
class FreeConfigEvidenceStore(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val entries = LinkedHashMap<String, LocalEvidence>()
    private var loaded = false

    @Synchronized
    private fun ensure() {
        if (loaded) return
        loaded = true
        runCatching {
            val array = JSONArray(load() ?: return)
            for (i in 0 until array.length()) {
                val e = LocalEvidence.fromJson(array.getJSONObject(i))
                entries[e.fingerprint] = e
            }
        }
    }

    @Synchronized
    fun get(fingerprint: String): LocalEvidence? {
        ensure()
        return entries[fingerprint]
    }

    @Synchronized
    fun all(): Map<String, LocalEvidence> {
        ensure()
        return LinkedHashMap(entries)
    }

    @Synchronized
    fun record(fingerprint: String, m: ConnectivityMeasurement): LocalEvidence {
        ensure()
        val current = entries.remove(fingerprint) ?: LocalEvidence(fingerprint)
        val next = FreeConfigLifecycleRules.apply(current, m)
        entries[fingerprint] = next
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        persist()
        return next
    }

    /** Drops stale test metadata for configs that left the list; diagnostic history is kept elsewhere. */
    @Synchronized
    fun forget(fingerprints: Collection<String>) {
        ensure()
        if (fingerprints.isEmpty()) return
        fingerprints.forEach { entries.remove(it) }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        entries.values.forEach { array.put(it.toJson()) }
        runCatching { save(array.toString()) }
    }

    /** Lifecycle as of now (a DEGRADED config can age into DEAD without a new measurement). */
    fun lifecycle(fingerprint: String): FreeConfigLifecycle =
        get(fingerprint)?.let { FreeConfigLifecycleRules.lifecycleOf(it, clock()) } ?: FreeConfigLifecycle.GLOBAL_VERIFIED

    companion object {
        const val MAX_ENTRIES = 300
    }
}

/**
 * The phone's deterministic score for a free config, 0..100, with its reasons. The same weights as the
 * list builder (tools/aggregator/scripts/pipeline.py), with this phone's measurements in place of the
 * runner's. Latency counts little: a stable 250 ms server beats an unstable 80 ms one.
 */
object FreeConfigScore {
    val WEIGHTS = linkedMapOf(
        "reliability" to 30.0, "stability" to 15.0, "http" to 10.0, "security" to 15.0,
        "latency" to 10.0, "iran" to 15.0, "history" to 5.0
    )

    data class Result(val score: Double, val reasons: Map<String, String>)

    fun of(evidence: LocalEvidence?, security: String, globalReach: Int = 0): Result {
        val e = evidence ?: LocalEvidence("")
        val reliability = if (e.attempts == 0) 0.5 else e.successes.toDouble() / e.attempts
        val samples = e.rttMs
        val median = samples.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val stability = when {
            samples.size < 2 || median == null -> 0.5
            else -> {
                val mean = samples.average()
                val sd = kotlin.math.sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
                (1.0 - sd / maxOf(median.toDouble(), 50.0)).coerceIn(0.0, 1.0)
            }
        }
        val http = (globalReach / 3.0).coerceIn(0.0, 1.0)
        val sec = when (security.lowercase()) { "reality" -> 1.0; "tls" -> 0.85; else -> 0.0 }
        val latency = when {
            median == null -> 0.0
            median <= 300 -> 1.0
            else -> (1.0 - (median - 300) / 1700.0).coerceIn(0.0, 1.0)
        }
        val iran = when {
            e.attempts == 0 -> 0.5
            else -> e.successes.toDouble() / e.attempts
        }
        val history = when (e.lifecycle) {
            FreeConfigLifecycle.IRAN_VERIFIED -> 1.0
            FreeConfigLifecycle.IRAN_PROBATION -> 0.75
            FreeConfigLifecycle.DEGRADED -> 0.25
            FreeConfigLifecycle.DEAD, FreeConfigLifecycle.QUARANTINED -> 0.0
            else -> 0.5
        }
        val parts = mapOf(
            "reliability" to (reliability to if (e.attempts == 0) "not tested here" else "${e.successes}/${e.attempts} succeeded here"),
            "stability" to (stability to if (samples.size < 2) "too few samples" else "${samples.size} round-trip samples"),
            "http" to (http to "exit reached $globalReach/3 test services from the list builder (global only)"),
            "security" to (sec to security.ifBlank { "none" }),
            "latency" to (latency to (median?.let { "median $it ms here" } ?: "no latency yet")),
            "iran" to (iran to (if (e.attempts == 0) "no evidence on this network" else "${e.verifiedConnections} verified sessions")),
            "history" to (history to e.lifecycle.label)
        )
        val score = parts.entries.sumOf { (k, v) -> WEIGHTS.getValue(k) * v.first }
        return Result((score * 100).toLong() / 100.0, parts.mapValues { (k, v) -> "%.1f/%.0f: %s".format(java.util.Locale.US, WEIGHTS.getValue(k) * v.first, WEIGHTS.getValue(k), v.second) })
    }
}
