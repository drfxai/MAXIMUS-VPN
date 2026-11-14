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
                state.trafficEvidence?.takeIf { it.appliesTo(state) && it.path == ProbePath.TUN }?.bidirectional == true -> BIDIRECTIONAL_VERIFIED
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

/** Bytes after which a "fixed-size freeze" stops a download (see [TrafficEvidence.fixedSizeFreeze]). */
const val FREEZE_MIN_BYTES = 12 * 1024
const val FREEZE_MAX_BYTES = 20 * 1024

/** The result of the upload and download check for one attempt on one network. */
data class TrafficEvidence(
    val attemptId: String?,
    val networkGeneration: Int,
    val path: ProbePath,
    val download: LegResult,
    val upload: LegResult,
    val checkedAt: Long,
    /** The graded upload's per-size detail (bytes over time, where it stalled); null when not measured that way. */
    val uploadProbe: UploadProbe? = null
) {
    val bidirectional: Boolean get() = download is LegResult.Passed && upload is LegResult.Passed

    /** Small requests passed (the connection is verified) but a bulk transfer stopped part-way. */
    val bulkStalled: Boolean get() =
        (download as? LegResult.Failed)?.stalled == true || (upload as? LegResult.Failed)?.stalled == true

    /**
     * The download stopped after roughly the first 16 KB and nothing more came: the signature of a
     * censor that lets a new TLS session to a flagged address carry its first ~16 KB and then freezes it.
     * Lowering the MTU does not change where it stops, so this is not treated as an MTU problem.
     */
    val fixedSizeFreeze: Boolean get() {
        val down = download as? LegResult.Failed
        if (down != null && down.stalled && down.bytes in FREEZE_MIN_BYTES..FREEZE_MAX_BYTES) return true
        // The same signature upstream: the graded upload stopped at ~16 KB on every reference it reached, and the
        // download also stopped inside the window (even when it ended as a timeout rather than a stall).
        return uploadFreeze && down != null && down.bytes in FREEZE_MIN_BYTES..FREEZE_MAX_BYTES
    }

    /**
     * The graded upload stalled inside the freeze window (16384 B, the first grade) on every reference that was
     * measured, with none confirming more: the per-connection ~16 KB cap seen upstream.
     */
    val uploadFreeze: Boolean get() {
        val measured = uploadProbe?.attempts.orEmpty().filter { a -> a.grades.any { it.outcome != UploadProbe.Outcome.NOT_TESTED } }
        return measured.isNotEmpty() && measured.all { a ->
            a.firstMiss?.let { it.outcome == UploadProbe.Outcome.STALLED && it.sentBytes in FREEZE_MIN_BYTES..FREEZE_MAX_BYTES } == true &&
                a.confirmedBytes < FREEZE_MIN_BYTES
        }
    }

    fun appliesTo(state: ConnectionState): Boolean =
        attemptId != null && attemptId == state.attemptId && networkGeneration == state.networkGeneration

    /**
     * Where a bulk transfer stopped: bytes the download received before it stalled, else bytes the graded
     * upload had written when its first unconfirmed size stalled; null when nothing stalled.
     */
    val stallBytes: Int? get() =
        (download as? LegResult.Failed)?.takeIf { it.stalled }?.bytes ?: uploadProbe?.stallBytes

    /** One line for the log and the diagnostics report. */
    fun describe(): String = "download ${leg(download)}, upload ${leg(upload, upload = true)} (${path.title})" +
        (uploadProbe?.let { "; ${it.describe()}" } ?: "")

    /**
     * The bulk test line of the diagnostics report: the server (host or address prefix, see [serverPrefix]), the
     * stall byte count when something stalled, then [describe].
     */
    fun diagLine(server: String?): String = buildString {
        append("server=").append(server?.takeIf { it.isNotBlank() } ?: "unknown")
        append(" stall=").append(stallBytes?.let { "${it}B" } ?: "none")
        append(" | ").append(describe())
    }

    private fun leg(r: LegResult, upload: Boolean = false): String = when (r) {
        is LegResult.Passed -> "verified (${r.bytes / 1024} KB, ${r.elapsedMs} ms, ${r.bytesPerSecond / 1024} KB/s)" +
            if (upload && uploadProbe?.partial == true) " partial" else ""
        is LegResult.Failed -> if (r.stalled && upload) "unconfirmed after ${r.bytes} bytes written locally (${r.stage.name}); remote receipt unknown"
            else if (r.stalled) "stalled after ${r.bytes} bytes (${r.stage.name})" else "failed (${r.stage.name}: ${r.reason})"
        is LegResult.NotTested -> "not tested (${r.reason})"
    }
}

/**
 * A server as the diagnostics report may show it: an IPv4 address cut to its /16 ("185.12.x.x", as the report's
 * own redaction does), an IPv6 address to its first two groups, a host name to its last two labels
 * ("*.example.com"). Never the full address.
 */
fun serverPrefix(address: String?): String? {
    val a = address?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(a)) return a.split('.').take(2).joinToString(".") + ".x.x"
    if (':' in a) return a.split(':').filter { it.isNotEmpty() }.take(2).joinToString(":") + ":x:x"
    val labels = a.split('.').filter { it.isNotEmpty() }
    return if (labels.size <= 2) labels.joinToString(".") else "*." + labels.takeLast(2).joinToString(".")
}

/**
 * True when a profile's traffic leaves through a Cloudflare Worker or Pages deployment (BPB and the like): its
 * address, Host header or SNI is a workers.dev / pages.dev name. Such an exit cannot reach Cloudflare-hosted
 * sites, so the upload/download check skips speed.cloudflare.com there. A Worker on a custom domain is not
 * recognisable here; the check then falls through to the non-Cloudflare references after one timeout.
 */
fun cloudflareWorkerExit(address: String?, host: String?, sni: String?): Boolean =
    listOf(address, host, sni).any { v ->
        v?.split(',')?.any { h -> h.trim().lowercase().trimEnd('.').let { it.endsWith(".workers.dev") || it.endsWith(".pages.dev") } } == true
    }
