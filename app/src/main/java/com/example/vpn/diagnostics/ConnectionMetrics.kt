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
        val median = times.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
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

    private const val KEEP = 10
}
