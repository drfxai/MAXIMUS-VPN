package com.example.vpn.lab

import com.example.vpn.connectivity.FieldChange
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maximus LAB, Network Intelligence: the deterministic records. Nothing here holds an address the user typed,
 * a link, a credential, an IMSI or a phone number. Configs are named by their fingerprint and the app's own
 * profile id; derived candidates are stored as "parent + mutation" and rebuilt when needed, so a derived copy
 * (which would carry the parent's credential) is never written to disk.
 */

/** The LAB's failure taxonomy (spec section 23). Measurements produce these; AI never does. */
enum class LabFailureCategory {
    DNS_RESOLUTION_FAILED, DNS_RESPONSE_INVALID, TCP_CONNECT_FAILED, TLS_HANDSHAKE_FAILED, CERTIFICATE_VALIDATION_FAILED,
    PROTOCOL_HANDSHAKE_FAILED, ENGINE_START_FAILED, TUN_ESTABLISH_FAILED, HTTP_CONNECTIVITY_FAILED,
    /** Older records only: meant what [DNS_THROUGH_TUNNEL_FAILED] means now. Kept so stored results still load. */
    DNS_TUNNEL_FAILED,
    IPV4_PATH_FAILED, IPV6_PATH_FAILED, UDP_UNAVAILABLE, QUIC_UNAVAILABLE, NETWORK_CHANGED, TIMEOUT, SECURITY_REJECTED,
    /** The network's resolver answered with block-page or private addresses. */
    DNS_TAMPERED,
    /** Encrypted DNS (DoH) could not be reached on the physical network. */
    DOH_UNAVAILABLE,
    /** The server answered TLS but its certificate did not match the expected name. */
    TLS_IDENTITY_FAILED,
    /** TLS was cut on a network where a filtered name is cut and a neutral one passes to the same address. */
    SNI_INTERFERENCE_SUSPECTED,
    /** The proxy refused the credential (UUID, password, key). */
    AUTHENTICATION_FAILED,
    /** Traffic passes the tunnel but names do not resolve through it. */
    DNS_THROUGH_TUNNEL_FAILED,
    UPLOAD_CONSTRAINED, PACKET_LOSS_HIGH, MTU_PROBLEM,
    /** A subscription, update or other control service could not be reached; the tunnel itself may be fine. */
    CONTROL_PLANE_UNAVAILABLE,
    /** No international reference answered on the physical network; config changes cannot fix this. */
    NO_INTERNATIONAL_EGRESS,
    /** The installed engine cannot run this config at all. */
    UNSUPPORTED,
    UNKNOWN
}

/** What was measured. Never interpreted. */
data class Observation(val text: String, val category: LabFailureCategory?, val at: Long)

/** What a measurement or a model suggests. Always marked by who made it and never shown as fact. */
data class Assessment(val text: String, val by: Source, val confidence: Double) {
    enum class Source { DETERMINISTIC_RULE, AI_MODEL }
    init { require(confidence in 0.0..1.0) }
}

/** How much the LAB may do on its own (spec section 32). */
enum class AutomationLevel(val title: String, val detail: String) {
    OBSERVE("Observe", "Measure only"),
    RECOMMEND("Recommend", "Measure and suggest"),
    AUTO_LAB("Auto LAB", "Test safe derived copies; never change saved configs"),
    AUTO_APPLY("Auto apply", "Also use a verified copy when you connect, with automatic rollback");

    val mayExperiment: Boolean get() = this == AUTO_LAB || this == AUTO_APPLY
}

/** Experiment lifecycle (spec section 26). */
enum class ExperimentState(val terminal: Boolean = false) {
    CREATED, QUEUED, TESTING, VERIFYING, CANDIDATE, VERIFIED(true),
    REJECTED(true), FAILED(true), CANCELLED(true), DEGRADED(true), RETIRED(true)
}

/** Candidate / profile promotion lifecycle (spec section 28). */
enum class PromotionState { EXPERIMENTAL, CANDIDATE, VERIFIED, DEGRADED, RETIRED, REJECTED }

/**
 * The network the phone is on right now, as LAB sees it. [contextKey] groups measurements: the link kind,
 * the carrier code (MCC+MNC, never a name or number) and which address families exist. [sessionId] changes on
 * every network transition, so measurements from two network sessions are never mixed.
 */
data class NetworkContext(
    val sessionId: String,
    val networkKey: String,
    val families: String,
    val startedAt: Long,
    /** A readable label such as "Irancell" or "Wi-Fi"; display only. */
    val label: String
) {
    val contextKey: String get() = "$networkKey|$families"
}

/** Counts for one candidate across the rounds of an experiment. */
data class CandidateStats(
    val attempts: Int = 0,
    val successes: Int = 0,
    val latenciesMs: List<Long> = emptyList(),
    val dnsThroughTunnelOk: Boolean? = null,
    val failures: Map<LabFailureCategory, Int> = emptyMap(),
    val firstSuccessAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val consecutiveFailures: Int = 0
) {
    val successRate: Double get() = if (attempts == 0) 0.0 else successes.toDouble() / attempts
    val medianLatencyMs: Long? get() = latenciesMs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    val jitterMs: Long? get() = latenciesMs.takeIf { it.size >= 2 }?.let { l -> l.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toLong() }

    fun record(success: Boolean, latencyMs: Long?, failure: LabFailureCategory?, now: Long): CandidateStats = if (success) copy(
        attempts = attempts + 1, successes = successes + 1, latenciesMs = (latenciesMs + listOfNotNull(latencyMs)).takeLast(20),
        firstSuccessAt = firstSuccessAt ?: now, lastSuccessAt = now, consecutiveFailures = 0
    ) else copy(
        attempts = attempts + 1, lastFailureAt = now, consecutiveFailures = consecutiveFailures + 1,
        failures = failures + ((failure ?: LabFailureCategory.UNKNOWN) to ((failures[failure ?: LabFailureCategory.UNKNOWN] ?: 0) + 1))
    )

    fun toJson(): JSONObject = JSONObject().put("a", attempts).put("s", successes).put("l", JSONArray(latenciesMs))
        .put("d", dnsThroughTunnelOk ?: JSONObject.NULL)
        .put("f", JSONObject().apply { failures.forEach { (k, v) -> put(k.name, v) } })
        .put("fs", firstSuccessAt ?: JSONObject.NULL).put("ls", lastSuccessAt ?: JSONObject.NULL)
        .put("lf", lastFailureAt ?: JSONObject.NULL).put("cf", consecutiveFailures)

    companion object {
        fun fromJson(o: JSONObject?): CandidateStats {
            o ?: return CandidateStats()
            fun l(k: String): Long? = if (o.isNull(k)) null else o.optLong(k)
            return CandidateStats(
                attempts = o.optInt("a"), successes = o.optInt("s"),
                latenciesMs = o.optJSONArray("l")?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty(),
                dnsThroughTunnelOk = if (o.isNull("d")) null else o.optBoolean("d"),
                failures = o.optJSONObject("f")?.let { f ->
                    f.keys().asSequence().mapNotNull { k -> runCatching { LabFailureCategory.valueOf(k) }.getOrNull()?.let { it to f.optInt(k) } }.toMap()
                }.orEmpty(),
                firstSuccessAt = l("fs"), lastSuccessAt = l("ls"), lastFailureAt = l("lf"), consecutiveFailures = o.optInt("cf")
            )
        }
    }
}

/**
 * A derived experimental candidate. The original config is never changed: this record is its parent's id and
 * fingerprint plus one approved mutation profile, from which the copy is rebuilt (spec section 25).
 */
data class LabCandidate(
    val candidateId: String,
    val experimentId: String,
    val parentProfileId: String,
    val parentFingerprint: String,
    /** The approved mutation: a reviewed recovery profile key, or a transport fallback id. */
    val mutationProfileId: String,
    val endpoint: String?,
    val changes: List<FieldChange>,
    val createdAt: Long,
    val securityPassed: Boolean,
    val securityReason: String? = null,
    val state: PromotionState = PromotionState.EXPERIMENTAL,
    val stats: CandidateStats = CandidateStats()
) {
    /** Rollback is always "use the parent as it is": the candidate is dropped, nothing needs restoring. */
    val rollback: String get() = "use the original config $parentProfileId unchanged"

    fun toJson(): JSONObject = JSONObject()
        .put("id", candidateId).put("exp", experimentId).put("pid", parentProfileId).put("pfp", parentFingerprint)
        .put("mut", mutationProfileId).put("ep", endpoint ?: JSONObject.NULL)
        // Field names only, plus values for the non-identity fields the policy allows: no credential is ever a mutable field.
        .put("ch", JSONArray().apply { changes.forEach { put(JSONObject().put("f", it.field).put("from", it.from).put("to", it.to)) } })
        .put("at", createdAt).put("sec", securityPassed).put("why", securityReason ?: JSONObject.NULL)
        .put("st", state.name).put("stats", stats.toJson())

    companion object {
        fun fromJson(o: JSONObject): LabCandidate? = runCatching {
            LabCandidate(
                candidateId = o.getString("id"), experimentId = o.getString("exp"), parentProfileId = o.getString("pid"),
                parentFingerprint = o.getString("pfp"), mutationProfileId = o.getString("mut"),
                endpoint = if (o.isNull("ep")) null else o.optString("ep"),
                changes = o.optJSONArray("ch")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { FieldChange(it.optString("f"), it.optString("from"), it.optString("to")) } } }.orEmpty(),
                createdAt = o.optLong("at"), securityPassed = o.optBoolean("sec"),
                securityReason = if (o.isNull("why")) null else o.optString("why"),
                state = runCatching { PromotionState.valueOf(o.optString("st")) }.getOrDefault(PromotionState.EXPERIMENTAL),
                stats = CandidateStats.fromJson(o.optJSONObject("stats"))
            )
        }.getOrNull()
    }
}

/** One step of an experiment, for the Live Tests screen and the audit trail (spec sections 43 and 52). */
data class LabMeasurement(
    val experimentId: String,
    val candidateId: String?,
    val stage: String,
    val startTime: Long,
    val endTime: Long,
    val success: Boolean?,
    val failure: LabFailureCategory? = null,
    val valueMs: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().put("e", experimentId).put("c", candidateId ?: JSONObject.NULL).put("s", stage)
        .put("t0", startTime).put("t1", endTime).put("ok", success ?: JSONObject.NULL)
        .put("f", failure?.name ?: JSONObject.NULL).put("v", valueMs ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): LabMeasurement = LabMeasurement(
            o.optString("e"), if (o.isNull("c")) null else o.optString("c"), o.optString("s"), o.optLong("t0"), o.optLong("t1"),
            if (o.isNull("ok")) null else o.optBoolean("ok"),
            if (o.isNull("f")) null else runCatching { LabFailureCategory.valueOf(o.optString("f")) }.getOrNull(),
            if (o.isNull("v")) null else o.optLong("v")
        )
    }
}

data class LabExperiment(
    val experimentId: String,
    val sessionId: String,
    val contextKey: String,
    val networkLabel: String,
    val parentProfileId: String,
    val parentFingerprint: String,
    val baselineFailure: LabFailureCategory?,
    val state: ExperimentState,
    val startTime: Long,
    val endTime: Long? = null,
    val candidates: List<LabCandidate> = emptyList(),
    val measurements: List<LabMeasurement> = emptyList(),
    /** The AI suggestion that led to this experiment, if any (it never runs anything by itself). */
    val aiRecommendationId: String? = null,
    val note: String? = null
) {
    val best: LabCandidate? get() = candidates.filter { it.securityPassed && it.stats.successes > 0 }
        .maxWithOrNull(compareBy({ it.stats.successRate }, { -(it.stats.medianLatencyMs ?: Long.MAX_VALUE) }))

    fun toJson(): JSONObject = JSONObject()
        .put("id", experimentId).put("sid", sessionId).put("ctx", contextKey).put("lbl", networkLabel)
        .put("pid", parentProfileId).put("pfp", parentFingerprint).put("bf", baselineFailure?.name ?: JSONObject.NULL)
        .put("st", state.name).put("t0", startTime).put("t1", endTime ?: JSONObject.NULL)
        .put("c", JSONArray().apply { candidates.forEach { put(it.toJson()) } })
        .put("m", JSONArray().apply { measurements.takeLast(MAX_MEASUREMENTS).forEach { put(it.toJson()) } })
        .put("ai", aiRecommendationId ?: JSONObject.NULL).put("n", note ?: JSONObject.NULL)

    companion object {
        const val MAX_MEASUREMENTS = 60

        fun fromJson(o: JSONObject): LabExperiment? = runCatching {
            LabExperiment(
                experimentId = o.getString("id"), sessionId = o.optString("sid"), contextKey = o.optString("ctx"),
                networkLabel = o.optString("lbl"), parentProfileId = o.optString("pid"), parentFingerprint = o.optString("pfp"),
                baselineFailure = if (o.isNull("bf")) null else runCatching { LabFailureCategory.valueOf(o.optString("bf")) }.getOrNull(),
                state = ExperimentState.valueOf(o.optString("st")), startTime = o.optLong("t0"),
                endTime = if (o.isNull("t1")) null else o.optLong("t1"),
                candidates = o.optJSONArray("c")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(LabCandidate::fromJson) } }.orEmpty(),
                measurements = o.optJSONArray("m")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(LabMeasurement::fromJson) } }.orEmpty(),
                aiRecommendationId = if (o.isNull("ai")) null else o.optString("ai"),
                note = if (o.isNull("n")) null else o.optString("n")
            )
        }.getOrNull()
    }
}

/**
 * A strategy measured to work on a kind of network (spec section 30): which original config, which approved
 * mutation, where, how well and how recently. Holds no personal identifier.
 */
data class VerifiedNetworkProfile(
    val profileId: String,
    val contextKey: String,
    val networkLabel: String,
    val parentProfileId: String,
    val parentFingerprint: String,
    val mutationProfileId: String,
    val endpoint: String?,
    val strategy: String,
    val state: PromotionState,
    val stats: CandidateStats,
    val confidence: Double,
    val lastVerifiedAt: Long,
    val securityPassed: Boolean = true,
    val disabled: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", profileId).put("ctx", contextKey).put("lbl", networkLabel).put("pid", parentProfileId).put("pfp", parentFingerprint)
        .put("mut", mutationProfileId).put("ep", endpoint ?: JSONObject.NULL).put("str", strategy).put("st", state.name)
        .put("stats", stats.toJson()).put("conf", confidence).put("lv", lastVerifiedAt).put("sec", securityPassed).put("off", disabled)

    companion object {
        fun fromJson(o: JSONObject): VerifiedNetworkProfile? = runCatching {
            VerifiedNetworkProfile(
                o.getString("id"), o.optString("ctx"), o.optString("lbl"), o.optString("pid"), o.optString("pfp"), o.optString("mut"),
                if (o.isNull("ep")) null else o.optString("ep"), o.optString("str"), PromotionState.valueOf(o.optString("st")),
                CandidateStats.fromJson(o.optJSONObject("stats")), o.optDouble("conf", 0.0), o.optLong("lv"),
                o.optBoolean("sec", true), o.optBoolean("off", false)
            )
        }.getOrNull()
    }
}

/** A card on the Discoveries screen. [verified] is false for anything not measured on this phone. */
data class LabDiscovery(
    val id: String,
    val kind: Kind,
    val title: String,
    val detail: String,
    val source: String,
    val confidence: Double,
    val verified: Boolean,
    val at: Long
) {
    enum class Kind { NETWORK_BEHAVIOR, PROFILE_DEGRADED, PROFILE_VERIFIED, RESEARCH_CANDIDATE, CORE_CAPABILITY, PROVIDER_MODEL, TRANSPORT_DEGRADED }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("k", kind.name).put("t", title).put("d", detail).put("s", source)
        .put("c", confidence).put("v", verified).put("at", at)

    companion object {
        fun fromJson(o: JSONObject): LabDiscovery? = runCatching {
            LabDiscovery(o.getString("id"), Kind.valueOf(o.optString("k")), o.optString("t"), o.optString("d"), o.optString("s"),
                o.optDouble("c", 0.0), o.optBoolean("v"), o.optLong("at"))
        }.getOrNull()
    }
}
