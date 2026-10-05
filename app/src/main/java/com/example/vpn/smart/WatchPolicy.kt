package com.example.vpn.smart

/**
 * When the failover watchdog checks a running connection. A healthy connection is checked every
 * 12 s; after a failed check it is re-checked every 3 s, so a dead connection is given up after
 * about 18 s instead of 36 s, while three failures in a row are still needed (no flapping on one
 * lost request).
 */
object WatchPolicy {
    const val HEALTHY_INTERVAL_MS = 12_000L
    const val RECHECK_INTERVAL_MS = 3_000L
    const val FAILURES_TO_SWITCH = 3

    fun nextCheckDelayMs(consecutiveFailures: Int): Long =
        if (consecutiveFailures > 0) RECHECK_INTERVAL_MS else HEALTHY_INTERVAL_MS
}
