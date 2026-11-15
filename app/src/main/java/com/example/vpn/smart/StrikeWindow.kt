package com.example.vpn.smart

/**
 * The failover watchdog's strikes: failed real traffic checks (and reported tunnel errors) counted inside a sliding
 * [windowMs], not only in a row.
 *
 * Build 150 on Irancell (cell:43235): the running server answered one small request now and then while bulk
 * transfers were dead (download timeout, upload stalled at 16384 B). The old counter went 1/3, 2/3, then a lucky
 * small request reset it to 0 and the next failure started again at 1/3: five minutes without a failover. Here a
 * strike stays until it ages out of the window, so three failures within three minutes switch even with passes
 * in between.
 *
 * - [fail] adds a strike (a real check that failed, or a tunnel error).
 * - [pass] is a real check that carried traffic: it ends the current run of failures ([consecutive], which sets the
 *   re-check cadence) but never erases strikes inside the window. Only [SUSTAINED_PASSES] real passes in a row with
 *   no failure between them clear the window: a connection that recovered for good does not carry old strikes.
 * - A skipped check (passive evidence: traffic was flowing) is neither: it is not a real check, so it never resets
 *   anything ([maySkip] says when a skip is allowed at all).
 *
 * Thread-safe. Pure apart from [clock].
 */
class StrikeWindow(
    private val clock: () -> Long = System::currentTimeMillis,
    val windowMs: Long = WINDOW_MS,
    val toSwitch: Int = WatchPolicy.FAILURES_TO_SWITCH
) {
    private val strikes = ArrayDeque<Long>()
    private var passesInARow = 0
    private var consecutive = 0

    /** A failed real check or tunnel error; returns the strikes now inside the window. */
    @Synchronized
    fun fail(): Int {
        val now = clock()
        prune(now)
        strikes.addLast(now)
        passesInARow = 0
        consecutive++
        return strikes.size
    }

    /** A real check that carried traffic. */
    @Synchronized
    fun pass() {
        prune(clock())
        consecutive = 0
        passesInARow++
        if (passesInARow >= SUSTAINED_PASSES) strikes.clear()
    }

    /** Strikes inside the window now. */
    @Synchronized
    fun count(): Int { prune(clock()); return strikes.size }

    /** Failures since the last real pass (the re-check cadence follows this). */
    @Synchronized
    fun consecutive(): Int = consecutive

    /** Enough strikes inside the window to leave this connection. */
    @Synchronized
    fun shouldSwitch(): Boolean = count() >= toSwitch

    /** Passive evidence may stand in for a real check only while there is no strike inside the window. */
    @Synchronized
    fun maySkip(): Boolean = count() == 0

    /** A switch was made or monitoring stopped: start over. */
    @Synchronized
    fun reset() { strikes.clear(); passesInARow = 0; consecutive = 0 }

    /** "2/3 in 3 min" for the log line. */
    fun describe(n: Int = count()): String = "$n/$toSwitch in ${windowMs / 60_000} min"

    private fun prune(now: Long) {
        while (strikes.isNotEmpty() && now - strikes.first() !in 0 until windowMs) strikes.removeFirst()
    }

    companion object {
        /** Three failures inside this window leave the connection, with or without passes between them. */
        const val WINDOW_MS = 3 * 60_000L
        /** Real passes in a row (about half a minute at the healthy cadence) that clear the window. */
        const val SUSTAINED_PASSES = 3
    }
}
