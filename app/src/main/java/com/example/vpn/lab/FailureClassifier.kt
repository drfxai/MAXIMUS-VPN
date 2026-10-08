package com.example.vpn.lab

import com.example.vpn.connectivity.HealthReport
import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.smart.NetworkCapabilityProfile

/**
 * Puts a measured failure into the LAB taxonomy. It only reads measurements (a probe report, the network's
 * capability profile, the address family that was tried); it produces an [Observation] of what was measured
 * and a rule-based [Assessment] worded as a possibility. AI assessments are separate and marked as such.
 */
object FailureClassifier {
    fun of(stage: FailureStage?): LabFailureCategory = when (stage) {
        null -> LabFailureCategory.UNKNOWN
        FailureStage.DNS_RESOLUTION_FAILED -> LabFailureCategory.DNS_RESOLUTION_FAILED
        FailureStage.DNS_RESPONSE_INVALID -> LabFailureCategory.DNS_RESPONSE_INVALID
        FailureStage.TCP_CONNECT_FAILED -> LabFailureCategory.TCP_CONNECT_FAILED
        FailureStage.TLS_HANDSHAKE_FAILED -> LabFailureCategory.TLS_HANDSHAKE_FAILED
        FailureStage.CERTIFICATE_VALIDATION_FAILED -> LabFailureCategory.CERTIFICATE_VALIDATION_FAILED
        FailureStage.PROXY_HANDSHAKE_FAILED, FailureStage.PROXY_AUTH_FAILED -> LabFailureCategory.PROTOCOL_HANDSHAKE_FAILED
        FailureStage.ENGINE_START_FAILED -> LabFailureCategory.ENGINE_START_FAILED
        FailureStage.TUN_ESTABLISH_FAILED -> LabFailureCategory.TUN_ESTABLISH_FAILED
        FailureStage.HTTP_REQUEST_FAILED, FailureStage.HTTP_STATUS_INVALID -> LabFailureCategory.HTTP_CONNECTIVITY_FAILED
        FailureStage.DNS_TUNNEL_FAILED -> LabFailureCategory.DNS_TUNNEL_FAILED
        FailureStage.NETWORK_CHANGED -> LabFailureCategory.NETWORK_CHANGED
        FailureStage.TIMEOUT -> LabFailureCategory.TIMEOUT
        FailureStage.SECURITY_REJECTED -> LabFailureCategory.SECURITY_REJECTED
        FailureStage.CANCELLED, FailureStage.UNKNOWN -> LabFailureCategory.UNKNOWN
    }

    /**
     * The category of a failed probe, refined by what else was measured: a TCP or timeout failure on an IPv6
     * endpoint while IPv6 measured unavailable is an IPv6 path failure; a UDP config on a network where UDP
     * or QUIC measured blocked is UDP/QUIC unavailability rather than a dead server.
     */
    fun classify(
        stage: FailureStage?,
        network: NetworkCapabilityProfile? = null,
        endpointFamily: String? = null,
        udpTransport: Boolean = false,
        usesQuic: Boolean = false
    ): LabFailureCategory {
        val base = of(stage)
        if (base !in PATH_LEVEL) return base
        if (udpTransport && network?.udpAvailable == false) return LabFailureCategory.UDP_UNAVAILABLE
        if (usesQuic && network?.quicAvailable == false) return LabFailureCategory.QUIC_UNAVAILABLE
        if (endpointFamily == "ipv6" && network?.ipv6Available == false) return LabFailureCategory.IPV6_PATH_FAILED
        if (endpointFamily == "ipv4" && network?.ipv4Available == false) return LabFailureCategory.IPV4_PATH_FAILED
        return base
    }

    private val PATH_LEVEL = setOf(LabFailureCategory.TCP_CONNECT_FAILED, LabFailureCategory.TIMEOUT, LabFailureCategory.TLS_HANDSHAKE_FAILED, LabFailureCategory.UNKNOWN)

    fun observe(report: HealthReport, now: Long, network: NetworkCapabilityProfile? = null, endpointFamily: String? = null): Observation =
        Observation(report.observation(), if (report.passed) null else classify(report.failureStage, network, endpointFamily), now)

    /** The rule-based reading of a category. Confidence is modest: one phone's failure never proves filtering. */
    fun assess(category: LabFailureCategory?): Assessment = when (category) {
        null -> Assessment("Every measured step passed.", Assessment.Source.DETERMINISTIC_RULE, 0.9)
        LabFailureCategory.DNS_RESOLUTION_FAILED, LabFailureCategory.DNS_RESPONSE_INVALID ->
            Assessment("Names did not resolve properly on this network: possible DNS filtering or an unreachable resolver.", Assessment.Source.DETERMINISTIC_RULE, 0.6)
        LabFailureCategory.TCP_CONNECT_FAILED -> Assessment("The server address refused or dropped connections: possible address blocking or a dead server.", Assessment.Source.DETERMINISTIC_RULE, 0.5)
        LabFailureCategory.TLS_HANDSHAKE_FAILED -> Assessment("TLS did not complete: possible SNI or fingerprint filtering. Fragment, fingerprint and ECH candidates may help.", Assessment.Source.DETERMINISTIC_RULE, 0.5)
        LabFailureCategory.CERTIFICATE_VALIDATION_FAILED -> Assessment("The certificate was refused. This is never worked around; check the config or the server.", Assessment.Source.DETERMINISTIC_RULE, 0.8)
        LabFailureCategory.PROTOCOL_HANDSHAKE_FAILED -> Assessment("The proxy protocol failed after the connection opened: a wrong credential or a dead backend.", Assessment.Source.DETERMINISTIC_RULE, 0.6)
        LabFailureCategory.HTTP_CONNECTIVITY_FAILED -> Assessment("The tunnel came up but requests did not get through it.", Assessment.Source.DETERMINISTIC_RULE, 0.5)
        LabFailureCategory.DNS_TUNNEL_FAILED -> Assessment("Requests pass but names do not resolve through the tunnel.", Assessment.Source.DETERMINISTIC_RULE, 0.6)
        LabFailureCategory.IPV4_PATH_FAILED -> Assessment("IPv4 paths fail on this network; IPv6 endpoints may work.", Assessment.Source.DETERMINISTIC_RULE, 0.6)
        LabFailureCategory.IPV6_PATH_FAILED -> Assessment("This network has no working IPv6; IPv6 endpoints are skipped.", Assessment.Source.DETERMINISTIC_RULE, 0.8)
        LabFailureCategory.UDP_UNAVAILABLE -> Assessment("UDP is blocked on this network; UDP-based configs cannot work here.", Assessment.Source.DETERMINISTIC_RULE, 0.8)
        LabFailureCategory.QUIC_UNAVAILABLE -> Assessment("QUIC is blocked on this network; HTTP/2 forms may work.", Assessment.Source.DETERMINISTIC_RULE, 0.7)
        LabFailureCategory.TIMEOUT -> Assessment("A step timed out: possible transport filtering or a slow server.", Assessment.Source.DETERMINISTIC_RULE, 0.4)
        LabFailureCategory.NETWORK_CHANGED -> Assessment("The network changed during the test; the result was discarded.", Assessment.Source.DETERMINISTIC_RULE, 0.9)
        LabFailureCategory.SECURITY_REJECTED -> Assessment("Refused by the security gate; not tried on the network.", Assessment.Source.DETERMINISTIC_RULE, 1.0)
        LabFailureCategory.ENGINE_START_FAILED, LabFailureCategory.TUN_ESTABLISH_FAILED ->
            Assessment("The phone could not start the tunnel; this is local, not the network.", Assessment.Source.DETERMINISTIC_RULE, 0.7)
        LabFailureCategory.UNKNOWN -> Assessment("The cause is not known from these measurements.", Assessment.Source.DETERMINISTIC_RULE, 0.2)
    }

    /** Whether recovery candidates are allowed after this failure: never for certificate, credential or security refusals. */
    fun allowsCandidates(category: LabFailureCategory?): Boolean = category !in setOf(
        LabFailureCategory.CERTIFICATE_VALIDATION_FAILED, LabFailureCategory.PROTOCOL_HANDSHAKE_FAILED, LabFailureCategory.SECURITY_REJECTED,
        LabFailureCategory.NETWORK_CHANGED, LabFailureCategory.ENGINE_START_FAILED, LabFailureCategory.TUN_ESTABLISH_FAILED,
        LabFailureCategory.UDP_UNAVAILABLE, LabFailureCategory.IPV6_PATH_FAILED
    )
}
