package com.example.vpn.verify

import java.net.SocketException

/**
 * How the Android TUN (device) path of a session can be checked.
 *
 * A separate engine program (Aether, Psiphon, Tor, a chain, Serverless, Radiance…) runs under this app's UID and
 * cannot protect its own sockets, so the VPN builder leaves the whole app out of its own VPN
 * (`SplitTunnelStore.apply(excludeSelf = true)` → `addDisallowedApplication(own package)`). Android then refuses
 * every socket of this UID on the VPN network: `Network.bindSocket` fails with EPERM, so a check that dials through
 * [RouteDialers.vpn] fails within milliseconds with a SocketException and 0 bytes on every host (build 165). That is
 * a property of the check, not evidence that the TUN is broken; the device path is then judged by the traffic the
 * TUN actually forwards for the other apps ([TunForwardMeter], `tun.forwarded`).
 */
object TunCheckPlan {
    enum class Mode {
        /** The app is inside its own VPN (in-process Xray / Kotlin tunnel): dial through the VPN network. */
        BIND_PROBE,
        /** The app is excluded from its own VPN: a bind-based check cannot run; use TUN-side counters. */
        SELF_EXCLUDED
    }

    /** Result recorded for bulk.tun when the app is left out of its own VPN. Not a failure. */
    const val NOT_TESTABLE_SELF_EXCLUDED = "not_testable_self_excluded"
    /** Result recorded when Android refused to bind this app's socket to the VPN network. Not a failure. */
    const val NOT_TESTABLE_BIND_REFUSED = "not_testable_bind_refused"
    /** What the report says instead of a TUN failure for a self-excluded session. */
    const val SELF_EXCLUDED_LINE = "TUN check not applicable (app excluded from its own VPN)"

    fun mode(appOutsideVpn: Boolean): Mode = if (appOutsideVpn) Mode.SELF_EXCLUDED else Mode.BIND_PROBE

    /** The registry line for a self-excluded bulk.tun. */
    fun selfExcludedResult(engine: String?): String =
        "$NOT_TESTABLE_SELF_EXCLUDED: $SELF_EXCLUDED_LINE; ${engine ?: "the engine"} runs outside the TUN, " +
            "so the device path is judged by tun.forwarded"

    fun bindRefusedResult(reason: String): String = "$NOT_TESTABLE_BIND_REFUSED: $reason"

    private val ERRNO = Regex("\\b(EPERM|EACCES)\\b|Operation not permitted|Permission denied")

    /**
     * True when [t], or one of its causes, is Android refusing to bind this app's socket to the VPN network:
     * `SocketException("Binding socket to network N failed: EPERM (Operation not permitted)")` from
     * `Network.bindSocket`, or the ErrnoException under it.
     */
    fun isBindRefusal(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth < 8) {
            if (e is VpnBindRefusedException) return true
            val message = e.message.orEmpty()
            if (message.contains("Binding socket to network", ignoreCase = true)) return true
            val errnoLike = e is SocketException || e.javaClass.simpleName == "ErrnoException"
            if (errnoLike && ERRNO.containsMatchIn(message)) return true
            e = e.cause
            depth++
        }
        return false
    }

    /**
     * Opens and closes one socket on the VPN network before a bind-based check. Returns the refusal reason
     * when Android does not let this app use the VPN network, null when the socket could be bound (or the
     * failure was something else, which the check itself then reports).
     */
    fun bindPreflight(createBound: () -> java.io.Closeable): String? = try {
        createBound().close()
        null
    } catch (e: Exception) {
        if (isBindRefusal(e)) reasonOf(e) else null
    }

    fun reasonOf(e: Throwable): String {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth < 8) {
            val m = c.message.orEmpty()
            if (m.contains("Binding socket to network", true) || ERRNO.containsMatchIn(m))
                return "Android refused to bind the app's socket to the VPN network (${m.take(120)})"
            c = c.cause
            depth++
        }
        return "Android refused to bind the app's socket to the VPN network (${e.javaClass.simpleName})"
    }
}

/** This app's socket may not use the VPN network (it is excluded from its own VPN). Never a route failure. */
class VpnBindRefusedException(reason: String, cause: Throwable? = null) : SocketException("$MARKER: $reason") {
    init { if (cause != null) initCause(cause) }

    companion object {
        const val MARKER = "VPN bind refused"
    }
}
