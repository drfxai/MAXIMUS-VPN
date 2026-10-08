package com.example.vpn.connectivity

import com.example.vpn.diagnostics.FailureStage
import org.json.JSONArray
import org.json.JSONObject

/**
 * TLS ClientHello fragmentation, as a small set of versioned, bounded profiles (the FRAGMENT entries of
 * [RecoveryProfiles]), each measured against the same config without fragmentation on the same network.
 *
 * Fragmentation is kept only when it does better than the plain config: more requests through, or the
 * same success with no large latency cost. When it makes things worse it is reverted automatically.
 * Nothing here generates new random patterns.
 */
class FragmentProfileEngine(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** One test of one profile (or of the plain config, profileKey [PLAIN]) on one network. */
    data class Trial(
        val profileKey: String,
        val network: String?,
        val at: Long,
        val handshakeOk: Boolean,
        val internetOk: Boolean,
        val latencyMs: Long? = null,
        /** How long the session stayed up, when the trial was a real session. */
        val stableMs: Long? = null,
        val failureStage: FailureStage? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("k", profileKey); network?.let { put("n", it) }; put("t", at); put("h", handshakeOk); put("i", internetOk)
            latencyMs?.let { put("l", it) }; stableMs?.let { put("s", it) }; failureStage?.let { put("f", it.name) }
        }

        companion object {
            fun fromJson(o: JSONObject) = Trial(
                o.getString("k"), o.optString("n").ifBlank { null }, o.optLong("t"), o.optBoolean("h"), o.optBoolean("i"),
                if (o.has("l")) o.optLong("l") else null, if (o.has("s")) o.optLong("s") else null,
                o.optString("f").ifBlank { null }?.let { runCatching { FailureStage.valueOf(it) }.getOrNull() }
            )
        }
    }

    data class Stats(val trials: Int, val successes: Int, val medianLatencyMs: Long?) {
        val rate: Double get() = if (trials == 0) 0.0 else successes.toDouble() / trials
    }

    enum class Decision { KEEP, REVERT, UNDECIDED }

    private var trials: MutableList<Trial>? = null

    @Synchronized
    private fun list(): MutableList<Trial> = trials ?: runCatching {
        val a = JSONArray(load() ?: "[]")
        (0 until a.length()).map { Trial.fromJson(a.getJSONObject(it)) }.toMutableList()
    }.getOrDefault(mutableListOf()).also { trials = it }

    @Synchronized
    fun record(trial: Trial) {
        val all = list()
        all.removeAll { clock() - it.at > TTL_MS }
        all += trial
        while (all.size > MAX_TRIALS) all.removeAt(0)
        val a = JSONArray()
        all.forEach { a.put(it.toJson()) }
        runCatching { save(a.toString()) }
    }

    @Synchronized
    fun stats(profileKey: String, network: String?): Stats {
        val ts = list().filter { it.profileKey == profileKey && it.network == network && clock() - it.at <= TTL_MS }
        val lat = ts.mapNotNull { it.latencyMs.takeIf { _ -> it.internetOk } }.sorted()
        return Stats(ts.size, ts.count { it.internetOk }, lat.getOrNull(lat.size / 2))
    }

    /** Keep [profileKey] on [network] only while it beats the plain config there. */
    fun decide(profileKey: String, network: String?): Decision = compare(stats(PLAIN, network), stats(profileKey, network))

    companion object {
        const val PLAIN = "plain"
        const val MIN_TRIALS = 2
        const val MAX_TRIALS = 400
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** Fragmentation that costs more than this factor in latency, for no gain in success, is reverted. */
        const val LATENCY_TOLERANCE = 1.5

        fun compare(plain: Stats, fragment: Stats): Decision {
            if (fragment.trials < MIN_TRIALS) return Decision.UNDECIDED
            if (fragment.successes == 0) return Decision.REVERT
            if (plain.trials < MIN_TRIALS) return Decision.KEEP
            return when {
                fragment.rate > plain.rate -> Decision.KEEP
                fragment.rate < plain.rate -> Decision.REVERT
                plain.medianLatencyMs != null && fragment.medianLatencyMs != null &&
                    fragment.medianLatencyMs > plain.medianLatencyMs * LATENCY_TOLERANCE -> Decision.REVERT
                plain.successes > 0 -> Decision.REVERT // equal success: the plain config needs no extra setting
                else -> Decision.KEEP
            }
        }

        /** The bounded, versioned fragmentation profiles. */
        val PROFILES: List<RecoveryProfile> get() = RecoveryProfiles.BUILT_IN.filter { RecoveryProfile.Strategy.FRAGMENT in it.networkConditions }
    }
}
