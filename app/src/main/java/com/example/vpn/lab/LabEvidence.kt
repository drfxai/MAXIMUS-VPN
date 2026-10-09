package com.example.vpn.lab

import org.json.JSONObject

/**
 * Network Memory V2: one evidence record per tested method on one network, the same shape for every engine.
 * It holds no address, UUID, password, key or config text: the config is named by its fingerprint and the
 * network by its LAB key (a salted fingerprint for Wi-Fi). History only changes what is tried first; it never
 * makes a path count as working without a fresh request (see [AdaptivePlanner.Priors]).
 */
data class LabEvidence(
    /** NetworkContext.contextKey: network key plus address families. */
    val network: String,
    val family: PathFamily,
    val profileFingerprint: String,
    val success: Boolean,
    /** How far the method got (ConnectionStage name). */
    val stage: ConnectionStage,
    val latencyMs: Long?,
    /** Passing requests over requests in that run. */
    val passes: Int,
    val attempts: Int,
    /** Plain-DNS result on the network at the time (AVAILABLE / FAILED / BLOCKED / NOT_TESTED). */
    val dnsResult: String?,
    /** "passed" when the security gate allowed the method; "rejected" otherwise. */
    val securityResult: String,
    val at: Long,
    val expiresAt: Long,
    /** App build that measured it; a different build makes the record STALE at most. */
    val runtimeVersion: Int
) {
    enum class Freshness { FRESH, AGING, STALE, EXPIRED }

    fun freshness(now: Long, currentRuntime: Int): Freshness {
        val age = now - at
        val byAge = when {
            now >= expiresAt -> Freshness.EXPIRED
            age < FRESH_MS -> Freshness.FRESH
            age < AGING_MS -> Freshness.AGING
            else -> Freshness.STALE
        }
        return if (runtimeVersion != currentRuntime && byAge < Freshness.STALE) Freshness.STALE else byAge
    }

    fun toJson(): JSONObject = JSONObject().put("n", network).put("f", family.name).put("p", profileFingerprint).put("ok", success).put("g", stage.name)
        .put("l", latencyMs ?: JSONObject.NULL).put("ps", passes).put("a", attempts).put("d", dnsResult ?: JSONObject.NULL).put("s", securityResult)
        .put("t", at).put("x", expiresAt).put("v", runtimeVersion)

    companion object {
        const val FRESH_MS = 6L * 60 * 60 * 1000
        const val AGING_MS = 24L * 60 * 60 * 1000
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        fun fromJson(o: JSONObject): LabEvidence? = runCatching {
            LabEvidence(o.getString("n"), PathFamily.valueOf(o.getString("f")), o.getString("p"), o.getBoolean("ok"), ConnectionStage.valueOf(o.getString("g")),
                if (o.isNull("l")) null else o.getLong("l"), o.optInt("ps"), o.optInt("a"), if (o.isNull("d")) null else o.optString("d"), o.optString("s"),
                o.getLong("t"), o.getLong("x"), o.optInt("v"))
        }.getOrNull()

        /**
         * Soft per-family priors for one network in -1..1, from FRESH and AGING records only (AGING counts
         * half). STALE and EXPIRED records never move the plan. Pure.
         */
        fun priors(records: List<LabEvidence>, network: String, now: Long, runtime: Int): Map<PathFamily, Double> =
            records.filter { it.network == network }.mapNotNull { r ->
                val w = when (r.freshness(now, runtime)) { Freshness.FRESH -> 1.0; Freshness.AGING -> 0.5; else -> return@mapNotNull null }
                r.family to (if (r.success) w else -w)
            }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> (v.sum() / v.size).coerceIn(-1.0, 1.0) }
    }
}
