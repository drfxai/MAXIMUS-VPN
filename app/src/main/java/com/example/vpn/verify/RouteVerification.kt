package com.example.vpn.verify

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.diagnostics.FailureStage

/**
 * How strongly the current route is proven, from nothing to traffic both ways through the tunnel.
 * Derived from the connection state and its [TrafficEvidence], never set by hand, so the screen cannot
 * show a stronger proof than the checks produced.
 *
 * A handshake (TCP, TLS, QUIC) is never full connectivity; only a request carried by the engine counts.
 */
enum class RouteVerification(val title: String) {
    NOT_TESTED("Not tested"),
    /** The engine for this route is in this build; nothing ran yet. */
    ENGINE_AVAILABLE("Engine available"),
    ENGINE_STARTING("Engine starting"),
    /** The engine runs and the tunnel exists; no request has gone through it yet. */
    LOCAL_PROXY_READY("Engine ready, traffic not verified"),
    /** The server completed a handshake. Not connectivity: used for candidates, never for a live session. */
    HANDSHAKE_VERIFIED("Handshake only"),
    /** A real request went through the route and came back. */
    TUNNEL_TRAFFIC_VERIFIED("Traffic verified"),
    /** A download and an upload, each checked byte for byte, went through the route. */
    BIDIRECTIONAL_VERIFIED("Upload and download verified"),
    /** It was verified, but the latest checks through it failed. */
    DEGRADED("Degraded"),
    REVIVAL_IN_PROGRESS("Reviving configurations"),
    /** A derived copy of a dead configuration carried verified traffic. */
    REVIVED_AND_VERIFIED("Configuration revived, connection verified"),
    FAILED("Failed"),
    /**
     * Every available method was tried on this network and none carried traffic. It says only that no
     * route was observed with what was tested, never that none exists.
     */
    NO_EXTERNAL_ROUTE_OBSERVED("No external route observed");

    companion object {
        /** The verification the state supports; evidence from another attempt or network is ignored. */
        fun of(state: ConnectionState): RouteVerification = when (state.status) {
            ConnectionStatus.DISCONNECTED, ConnectionStatus.DISCONNECTING -> NOT_TESTED
            ConnectionStatus.PREPARING, ConnectionStatus.CONNECTING ->
                if (state.reviving) REVIVAL_IN_PROGRESS else NOT_TESTED
            ConnectionStatus.VPN_INTERFACE_ESTABLISHED, ConnectionStatus.PROXY_CONNECTING,
            ConnectionStatus.RECONNECTING, ConnectionStatus.SWITCHING -> ENGINE_STARTING
            ConnectionStatus.ENGINE_STARTED, ConnectionStatus.VERIFYING, ConnectionStatus.NETWORK_CHANGED,
            ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED -> LOCAL_PROXY_READY
            ConnectionStatus.CONNECTED -> when {
                state.revived -> REVIVED_AND_VERIFIED
                state.trafficEvidence?.takeIf { it.appliesTo(state) }?.bidirectional == true -> BIDIRECTIONAL_VERIFIED
                else -> TUNNEL_TRAFFIC_VERIFIED
            }
            ConnectionStatus.DEGRADED -> DEGRADED
            ConnectionStatus.FAILED -> if (state.recoveryExhausted) NO_EXTERNAL_ROUTE_OBSERVED else FAILED
        }
    }
}

/** Which way the check reached the internet; reported with the result, since they prove different hops. */
enum class ProbePath(val title: String) {
    /** Bound to Android's VPN network: the app's request went through the TUN and the engine. */
    TUN("through the VPN interface"),
    /**
     * Through the local port of the engine program the TUN forwards to. Used when the app is left out of
     * its own tunnel (every separate engine program), where Android refuses the app the VPN network. It
     * proves the engine carries traffic; the hop from the TUN to the engine is not observed by the app.
     */
    ENGINE_PROXY("through the engine's local port")
}

/** One direction of a transfer check. */
sealed class LegResult {
    /** Every byte arrived (download) or the far side echoed the end-of-payload marker (upload). */
    data class Passed(val bytes: Int, val elapsedMs: Long) : LegResult() {
        /** Measured rate in bytes per second, from this transfer only. */
        val bytesPerSecond: Long get() = bytes * 1000L / elapsedMs.coerceAtLeast(1)
    }
    /**
     * The transfer failed. [stalled] means part of it arrived and then nothing more: small requests pass
     * but bulk data stops, the pattern of a path MTU black hole or of throttling.
     */
    data class Failed(val stage: FailureStage, val bytes: Int, val stalled: Boolean, val reason: String) : LegResult()
    /** It could not be checked (no reference answered usefully); never counted as a pass or a failure. */
    data class NotTested(val reason: String) : LegResult()
}

/** The result of the upload and download check for one attempt on one network. */
data class TrafficEvidence(
    val attemptId: String?,
    val networkGeneration: Int,
    val path: ProbePath,
    val download: LegResult,
    val upload: LegResult,
    val checkedAt: Long
) {
    val bidirectional: Boolean get() = download is LegResult.Passed && upload is LegResult.Passed

    /** Small requests passed (the connection is verified) but a bulk transfer stopped part-way. */
    val bulkStalled: Boolean get() =
        (download as? LegResult.Failed)?.stalled == true || (upload as? LegResult.Failed)?.stalled == true

    fun appliesTo(state: ConnectionState): Boolean =
        attemptId != null && attemptId == state.attemptId && networkGeneration == state.networkGeneration

    /** One line for the log and the diagnostics report. */
    fun describe(): String = "download ${leg(download)}, upload ${leg(upload)} (${path.title})"

    private fun leg(r: LegResult): String = when (r) {
        is LegResult.Passed -> "verified (${r.bytes / 1024} KB, ${r.elapsedMs} ms, ${r.bytesPerSecond / 1024} KB/s)"
        is LegResult.Failed -> if (r.stalled) "stalled after ${r.bytes} bytes (${r.stage.name})" else "failed (${r.stage.name}: ${r.reason})"
        is LegResult.NotTested -> "not tested (${r.reason})"
    }
}
