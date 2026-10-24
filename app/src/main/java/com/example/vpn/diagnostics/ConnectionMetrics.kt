package com.example.vpn.diagnostics

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counters since the app started, printed in the diagnostics report so changes in connection
 * behaviour can be compared on the same phone and network (time to connect, switches, background
 * checks). Nothing here leaves the phone unless the user copies the report.
 */
object ConnectionMetrics {
    val connectAttempts = AtomicInteger()
    val connectVerified = AtomicInteger()
    val failoverSwitches = AtomicInteger()
    val activeTunnelChecks = AtomicInteger()
    val passiveSkips = AtomicInteger()
    val notTestedWhileConnected = AtomicInteger()
    val labSessions = AtomicInteger()
    private val connectTimes = ArrayDeque<Long>()

    /** Time from the connect request to the first request verified through the tunnel. */
    @Synchronized
    fun recordConnectTime(ms: Long) {
        if (ms < 0) return
        connectVerified.incrementAndGet()
        connectTimes.addLast(ms)
        while (connectTimes.size > KEEP) connectTimes.removeFirst()
    }

    @Synchronized
    fun connectTimes(): List<Long> = connectTimes.toList()

    @Synchronized
    fun summary(): List<String> {
        val times = connectTimes.toList()
        val median = medianMillis(times)
        return listOf(
            "Connect attempts: ${connectAttempts.get()}, verified: ${connectVerified.get()}",
            "Time to verified traffic (last ${times.size}): ${if (times.isEmpty()) "none yet" else times.joinToString(", ") { "${it}ms" }}" +
                (median?.let { "; median ${it}ms" } ?: ""),
            "Automatic switches: ${failoverSwitches.get()}",
            "Background tunnel checks: ${activeTunnelChecks.get()} sent, ${passiveSkips.get()} skipped (traffic was flowing)",
            "Server tests not run while connected: ${notTestedWhileConnected.get()}",
            "LAB network sessions: ${labSessions.get()}"
        )
    }

    internal fun medianMillis(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        if (sorted.size % 2 == 1) return sorted[mid]
        val low = sorted[mid - 1]; val high = sorted[mid]
        return low + (high - low) / 2
    }

    private const val KEEP = 10
}
