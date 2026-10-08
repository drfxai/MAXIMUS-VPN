package com.example.vpn.connectivity

import com.example.vpn.diagnostics.FailureStage

/**
 * One step of checking a config or a running tunnel, in the order they happen. ICMP is deliberately not
 * a step: a server that drops ping can carry traffic, so ping never decides health.
 */
enum class ProbeStep(val passVerdict: ProbeVerdict, val failure: FailureStage) {
    DNS_RESOLUTION(ProbeVerdict.DNS_OK, FailureStage.DNS_RESOLUTION_FAILED),
    DNS_QUERY(ProbeVerdict.DNS_OK, FailureStage.DNS_RESPONSE_INVALID),
    TCP_CONNECT(ProbeVerdict.TCP_OK, FailureStage.TCP_CONNECT_FAILED),
    TLS_HANDSHAKE(ProbeVerdict.TLS_OK, FailureStage.TLS_HANDSHAKE_FAILED),
    PROTOCOL_HANDSHAKE(ProbeVerdict.PROTOCOL_OK, FailureStage.PROXY_HANDSHAKE_FAILED),
    ENGINE_START(ProbeVerdict.ENGINE_OK, FailureStage.ENGINE_START_FAILED),
    TUN_ESTABLISH(ProbeVerdict.TUN_OK, FailureStage.TUN_ESTABLISH_FAILED),
    HTTP_THROUGH_TUNNEL(ProbeVerdict.INTERNET_OK, FailureStage.HTTP_REQUEST_FAILED),
    DNS_THROUGH_TUNNEL(ProbeVerdict.DNS_TUNNEL_OK, FailureStage.DNS_TUNNEL_FAILED),
    STABILITY(ProbeVerdict.INTERNET_OK, FailureStage.HTTP_REQUEST_FAILED)
}

/** What a probe concluded. The *_OK values name the furthest step that passed. */
enum class ProbeVerdict {
    DNS_OK, TCP_OK, TLS_OK, PROTOCOL_OK, ENGINE_OK, TUN_OK, INTERNET_OK, DNS_TUNNEL_OK,
    DEGRADED, BLOCKED, TIMEOUT, INVALID, SECURITY_REJECTED, UNKNOWN
}

/** One measured step. Holds no address, link or credential: the config is named by its fingerprint. */
data class ProbeResult(
    val step: ProbeStep,
    val startTime: Long,
    val endTime: Long,
    val success: Boolean,
    val failureReason: FailureStage? = null,
    val networkProfileId: String? = null,
    val configFingerprint: String? = null,
    val engine: String? = null,
    val retryCount: Int = 0,
    /** Measured value for the step when it has one (latency of the request, for example). */
    val valueMs: Long? = null
) {
    val duration: Long get() = (endTime - startTime).coerceAtLeast(0)
}

/** Every step a probe run made, and what they add up to. */
data class HealthReport(
    val sessionId: String,
    val results: List<ProbeResult>,
    val verdict: ProbeVerdict,
    /** The first step that failed, or null when every step passed. */
    val failedStep: ProbeStep? = null,
    val failureStage: FailureStage? = null,
    /** The run was overtaken (disconnect, network change, newer run): its result is not current evidence. */
    val stale: Boolean = false
) {
    val passed: Boolean get() = failedStep == null && !stale && verdict != ProbeVerdict.SECURITY_REJECTED

    /** What was measured, in plain words, without interpretation. */
    fun observation(): String = results.joinToString("; ") { r ->
        val tries = if (r.retryCount > 0) " after ${r.retryCount + 1} tries" else ""
        if (r.success) "${r.step.name} passed in ${r.duration} ms$tries"
        else "${r.step.name} failed (${r.failureReason?.taxonomyName ?: "UNKNOWN"}) in ${r.duration} ms$tries"
    }.ifEmpty { "no step ran" }

    /**
     * What the measurements suggest. Always worded as a possibility: one phone's failures do not prove
     * filtering.
     */
    fun assessment(): String = when {
        stale -> "Result discarded: the session or network changed while it ran."
        verdict == ProbeVerdict.SECURITY_REJECTED -> "Refused by the security gate; not tried on the network."
        failedStep == null -> "Every step passed."
        else -> when (failureStage) {
            FailureStage.DNS_RESOLUTION_FAILED, FailureStage.DNS_RESPONSE_INVALID ->
                "Name resolution failed on this network: possible DNS filtering or an unreachable resolver."
            FailureStage.TCP_CONNECT_FAILED -> "The server's address did not accept a connection: possible address blocking or a dead server."
            FailureStage.TLS_HANDSHAKE_FAILED -> "TLS did not complete: possible SNI or fingerprint filtering, or endpoint degradation."
            FailureStage.CERTIFICATE_VALIDATION_FAILED -> "The certificate was refused: a wrong server, interception, or a misconfigured config."
            FailureStage.PROXY_HANDSHAKE_FAILED, FailureStage.PROXY_AUTH_FAILED ->
                "The connection opened but the proxy protocol failed: a wrong credential or a dead backend."
            FailureStage.HTTP_REQUEST_FAILED, FailureStage.HTTP_STATUS_INVALID ->
                "The tunnel exists but requests do not get through it: the server's exit or the path is impaired."
            FailureStage.DNS_TUNNEL_FAILED -> "Requests pass but names do not resolve through the tunnel."
            FailureStage.TIMEOUT -> "A step timed out: possible transport filtering or endpoint degradation."
            else -> "The step ${failedStep.name} failed; the cause is not known."
        }
    }
}
