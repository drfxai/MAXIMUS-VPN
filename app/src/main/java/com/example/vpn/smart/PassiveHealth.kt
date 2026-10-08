package com.example.vpn.smart

/**
 * Passive evidence that the tunnel carries data, so a healthy, busy connection is not probed every
 * few seconds. With the native core the app's own sockets are the core's connections to the server,
 * so bytes the app receives are data coming back through the proxy.
 *
 * An active check may be skipped only when enough data arrived since the previous decision AND an
 * active check passed recently: passive evidence extends a verified state, it never creates one.
 */
class PassiveHealth(
    private val rxBytes: () -> Long = { android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val minBytes: Long = MIN_BYTES,
    private val maxTrustMs: Long = MAX_TRUST_MS
) {
    private var lastRx = -1L
    @Volatile private var lastActiveOkAt = 0L

    /** An active check through the tunnel succeeded. */
    fun recordActiveSuccess() { lastActiveOkAt = clock() }

    /** True when the next active check can be skipped. Call once per scheduled check. */
    @Synchronized
    fun maySkipActiveCheck(): Boolean {
        val rx = runCatching(rxBytes).getOrDefault(-1L)
        if (rx < 0) { lastRx = -1L; return false } // counter unsupported on this device
        val grew = lastRx >= 0 && rx - lastRx >= minBytes
        lastRx = rx
        return grew && clock() - lastActiveOkAt < maxTrustMs
    }

    companion object {
        /** Received bytes between two checks that count as "the tunnel is busy". */
        const val MIN_BYTES = 64L * 1024
        /** Longest time passive evidence alone keeps a connection marked healthy. */
        const val MAX_TRUST_MS = 45_000L
    }
}
