package com.example.vpn.unlock

/**
 * Unlock's Connect checks its servers again before it fails. Build 178 field report (Irancell LTE): Connect failed
 * 0.27 s after the tap with "No Unlock DNS server answered correctly" while the phone was still settling on a network
 * change, and the user's own retry 16 s later connected at once. A Connect now makes up to [ATTEMPTS] checks, waiting
 * [delayBeforeMs] before each repeat, and only then fails (saying how many checks ran). Pure.
 */
object UnlockConnectRetry {
    const val ATTEMPTS = 3
    private val DELAYS_MS = listOf(2_000L, 4_000L)

    /** The pause before check number [attempt] (1-based); 0 for the first. */
    fun delayBeforeMs(attempt: Int): Long = if (attempt <= 1) 0L else DELAYS_MS[minOf(attempt - 2, DELAYS_MS.lastIndex)]

    /** Whether another check follows a failed check number [attempt]. */
    fun again(attempt: Int): Boolean = attempt < ATTEMPTS

    /** The most a Connect spends waiting between checks (the checks' own time not included). */
    val totalDelayMs: Long get() = (2..ATTEMPTS).sumOf { delayBeforeMs(it) }
}
