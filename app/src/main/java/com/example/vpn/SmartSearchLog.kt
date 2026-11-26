package com.example.vpn

/**
 * What a connect reports about the network assessment and Smart Connect's plan.
 *
 * Build 177 device test: every manual pick (one engine, no search) logged "Network: Irancell … open (metered)", five
 * "<transport> not tried directly: Your pick: only this connection runs" lines, and twice "Assessment: filtering looks
 * heavy", while the reading flipped between open and heavy from one connect to the next. The assessment only steers
 * Smart Connect's search, so only Smart Connect reports it ([reports]), and the same reading on the same network is
 * reported once ([changed]). A manual pick gets one line saying the search is skipped ([manualLine]).
 */
object SmartSearchLog {
    private val last = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** True when this connect is Smart Connect's (not the user's own pick of one connection or a pinned config). */
    fun reports(exact: Boolean): Boolean = !exact

    /** True when [reading] differs from what was last reported on [network] (and remembers it). */
    fun changed(network: String, reading: String): Boolean {
        if (last.size > 32) last.clear()
        return last.put(network, reading) != reading
    }

    fun manualLine(name: String): String =
        "Your pick: only $name runs; Smart Connect's network assessment and search are skipped."

    internal fun resetForTest() = last.clear()
}
