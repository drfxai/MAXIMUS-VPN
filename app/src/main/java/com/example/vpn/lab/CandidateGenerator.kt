package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.BpbRecoveryEngine
import com.example.vpn.connectivity.FieldChange
import com.example.vpn.connectivity.RecoveryProfile
import com.example.vpn.connectivity.RecoveryProfiles
import com.example.vpn.connectivity.TransportCapabilityEngine
import com.example.vpn.smart.NetworkCapabilityProfile
import java.security.MessageDigest

/** A testable idea for a failure, before any candidate is built. */
data class Hypothesis(val strategy: String, val why: String)

/**
 * Deterministic hypotheses and candidates (spec section 20: HypothesisGenerator → CandidateGenerator).
 *
 * Hypotheses come from the failure category and the measured network; candidates come only from the reviewed,
 * expiring built-in recovery profiles and the transport fallbacks TransportCapabilityEngine already offers,
 * each checked by [CandidateMutationPolicy] (which includes the security gate). Candidates the policy refuses are
 * returned as refused and never tested. Strategies already retired on this network are skipped.
 */
class CandidateGenerator(
    private val profiles: List<RecoveryProfile> = RecoveryProfiles.BUILT_IN,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun hypotheses(category: LabFailureCategory?, net: NetworkCapabilityProfile?): List<Hypothesis> {
        if (!FailureClassifier.allowsCandidates(category)) return emptyList()
        val out = mutableListOf<Hypothesis>()
        when (category) {
            LabFailureCategory.DNS_RESOLUTION_FAILED, LabFailureCategory.DNS_RESPONSE_INVALID, LabFailureCategory.TCP_CONNECT_FAILED,
            LabFailureCategory.IPV4_PATH_FAILED -> {
                out += Hypothesis("endpoint", "the server's usual address may be blocked; a locally validated edge address may not be")
                if (net?.ipv6Available != false) out += Hypothesis("address-family", "IPv6 paths may pass where IPv4 ones are filtered")
            }
            LabFailureCategory.QUIC_UNAVAILABLE -> out += Hypothesis("transport-h2", "QUIC is blocked here; the config's HTTP/2 form may work")
            else -> {
                out += Hypothesis("fragment", "splitting the TLS ClientHello may get past SNI inspection")
                out += Hypothesis("fingerprint", "a different TLS fingerprint may not match a blocked pattern")
                if (net?.echCapable != false) out += Hypothesis("ech", "ECH hides the server name from the network")
                out += Hypothesis("alpn", "HTTP/1.1 ALPN changes how the handshake looks")
                out += Hypothesis("endpoint", "a validated edge address may avoid an address block")
            }
        }
        return out
    }

    private fun strategiesFor(h: Hypothesis): Set<RecoveryProfile.Strategy> = when (h.strategy) {
        "fragment" -> setOf(RecoveryProfile.Strategy.FRAGMENT)
        "fingerprint" -> setOf(RecoveryProfile.Strategy.FINGERPRINT)
        "ech" -> setOf(RecoveryProfile.Strategy.ECH)
        "alpn" -> setOf(RecoveryProfile.Strategy.ALPN)
        "endpoint" -> setOf(RecoveryProfile.Strategy.ALT_ENDPOINT_V4, RecoveryProfile.Strategy.ALT_ENDPOINT_V6)
        else -> emptySet()
    }

    data class Built(val candidate: LabCandidate, val profile: VlessProfile?)

    /**
     * Candidates for [parent] in [experimentId]. [endpoints] are edge addresses validated on this network
     * (EndpointScoringEngine), never raw hints. At most [max] are returned, refused ones included.
     */
    fun generate(
        experimentId: String,
        parent: VlessProfile,
        category: LabFailureCategory?,
        net: NetworkCapabilityProfile?,
        endpoints: List<String> = emptyList(),
        retired: Set<String> = emptySet(),
        max: Int = 5
    ): List<Built> {
        val now = clock()
        val out = mutableListOf<Built>()
        val seen = mutableSetOf<String>()
        fun add(mutationId: String, endpoint: String?, derived: VlessProfile) {
            if (out.size >= max || mutationId + endpoint in seen || mutationId in retired) return
            // A mutation whose capability is not supported end to end is never offered as working.
            if (CoreCapabilityRegistry.forMutation(mutationId)?.usable == false) return
            seen += mutationId + endpoint
            val verdict = CandidateMutationPolicy.check(parent, derived)
            val candidate = LabCandidate(
                candidateId = idOf(experimentId, mutationId, endpoint), experimentId = experimentId, parentProfileId = parent.id,
                parentFingerprint = parent.effectiveFingerprint, mutationProfileId = mutationId, endpoint = endpoint,
                changes = safeChanges(parent, derived), createdAt = now, securityPassed = verdict.allowed, securityReason = verdict.reason,
                state = if (verdict.allowed) PromotionState.EXPERIMENTAL else PromotionState.REJECTED
            )
            out += Built(candidate, derived.takeIf { verdict.allowed })
        }
        for (h in hypotheses(category, net)) {
            when (h.strategy) {
                "transport-h2" -> TransportCapabilityEngine.h2Fallback(parent)?.let { add(TRANSPORT_H2, null, it) }
                "address-family" -> if (!isIp(parent.address) && parent.security.equals("tls", true)) add(FAMILY_V6, null, parent.copy(targetStrategy = "UseIPv6v4"))
                else -> {
                    val wanted = strategiesFor(h)
                    profiles.filter { !it.isExpired(now) && it.networkConditions.any { s -> s in wanted } && it.appliesTo(parent) }
                        .sortedWith(compareBy({ -it.confidence }, { it.key }))
                        .forEach { rp ->
                            if (rp.addressFamily == "ipv6" && net?.ipv6Available == false) return@forEach
                            val targets: List<String?> = if (rp.needsEndpoint())
                                endpoints.filter { BpbRecoveryEngine.familyOf(it) == rp.addressFamily }.distinct().take(2) else listOf(null)
                            targets.forEach { ep -> rp.derive(parent, ep)?.takeIf { it != parent }?.let { add(rp.key, ep, it) } }
                        }
                }
            }
        }
        return out
    }

    /** Recorded changes without the user's own server name: the original address and SNI are summarized. */
    private fun safeChanges(parent: VlessProfile, derived: VlessProfile): List<FieldChange> =
        BpbRecoveryEngine.diff(parent, derived).map { c ->
            when (c.field) {
                "address" -> c.copy(from = "original", to = c.to)
                "echConfigList" -> c.copy(from = if (c.from.isBlank()) "" else "set", to = if (c.to.isBlank()) "" else "ECH via DoH")
                else -> c
            }
        } + if (parent.targetStrategy != derived.targetStrategy) listOf(FieldChange("targetStrategy", parent.targetStrategy, derived.targetStrategy)) else emptyList()

    private fun isIp(h: String) = com.example.vpn.connectivity.RecoverySecurityGate.isIpLiteral(h.removePrefix("[").removeSuffix("]"))

    /** Rebuilds a stored candidate's copy from its original; null when the mutation no longer exists or passes. */
    fun rebuild(parent: VlessProfile, mutationProfileId: String, endpoint: String?): VlessProfile? {
        val derived = when (mutationProfileId) {
            TRANSPORT_H2 -> TransportCapabilityEngine.h2Fallback(parent)
            FAMILY_V6 -> parent.copy(targetStrategy = "UseIPv6v4")
            else -> profiles.firstOrNull { it.key == mutationProfileId && !it.isExpired(clock()) }?.derive(parent, endpoint)
        } ?: return null
        return derived.takeIf { CandidateMutationPolicy.check(parent, it).allowed }
    }

    companion object {
        const val TRANSPORT_H2 = "transport-h2-fallback@v1"
        const val FAMILY_V6 = "address-family-ipv6-first@v1"

        fun idOf(experimentId: String, mutation: String, endpoint: String?): String {
            val d = MessageDigest.getInstance("SHA-256").digest("$experimentId|$mutation|${endpoint.orEmpty()}".toByteArray())
            return "CAND-" + d.take(3).joinToString("") { "%02X".format(it) }
        }
    }
}
