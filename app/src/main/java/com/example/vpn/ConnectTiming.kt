package com.example.vpn

/**
 * "Time from connect to verified traffic". Build 176 logged 80.9 s after Psiphon over Lantern fell back to Tor, while
 * the user had waited about 172 s: the clock restarted with every attempt. It now runs from the user's Connect tap to
 * the first verified traffic of that Connect; failovers and reconnects later in the session time only themselves.
 * Pure, so it is unit-tested.
 */
object ConnectTiming {
    /**
     * Milliseconds to report at [now]. [tapAt]: the user's Connect tap not yet followed by verified traffic (0 when
     * there is none); [attemptStartedAt]: when this attempt began.
     */
    fun elapsed(tapAt: Long, attemptStartedAt: Long, now: Long): Long =
        now - (if (tapAt in 1..attemptStartedAt) tapAt else attemptStartedAt)

    /** The log line, naming the attempt's own share when earlier attempts of the same Connect came first. */
    fun describe(totalMs: Long, attemptMs: Long): String =
        if (totalMs > attemptMs + 1_000) "Time from connect to verified traffic: $totalMs ms (this attempt: $attemptMs ms; " +
            "earlier attempts of this Connect took the rest)."
        else "Time from connect to verified traffic: $totalMs ms."
}
