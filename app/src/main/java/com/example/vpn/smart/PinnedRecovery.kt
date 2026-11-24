package com.example.vpn.smart

/**
 * A pinned session (the power button without fallback) whose tunnel stopped carrying traffic.
 *
 * Build 173 report (Irancell cell:43235, Samsung SM-F711B, Android 15), 01:29:59–01:35:15 +0330: Psiphon started with
 * the power button was up, but every real traffic check failed with "SOCKS reply 1" (the engine could not reach the
 * target), three strikes every ~27 s for more than five minutes. The watchdog only logged "Staying on it: it was
 * connected with the power button" each time and never recovered; the user had to stop and start by hand.
 *
 * A pinned config is still never switched for another one silently. After [WINDOWS_BEFORE_RESTART] failure windows in a
 * row (a window is the watchdog's three strikes) the SAME config is restarted instead (Psiphon: a fresh tunnel, in
 * another region when it can, see [com.example.vpn.sidecar.PsiphonRegionPlan.initial]), with a backoff between
 * restarts ([backoffMs]) so a dead network is not hammered. A real check that passes resets everything.
 *
 * Kept across FailoverManager instances: each restart creates a new manager. Thread-safe; the clock is injected.
 */
class PinnedRecovery(private val clock: () -> Long = System::currentTimeMillis) {
    sealed class Decision {
        /** Keep the tunnel (fail-closed) and wait: [windows] dead windows so far, [nextRestartInMs] when known. */
        data class Wait(val windows: Int, val nextRestartInMs: Long?) : Decision()
        /** Restart the same config now; [attempt] counts restarts since the tunnel last carried traffic. */
        data class Restart(val attempt: Int) : Decision()
    }

    private var windows = 0
    private var restarts = 0
    private var lastRestartAt = 0L

    /** One more failure window (three strikes) on the pinned config. */
    @Synchronized
    fun onDeadWindow(): Decision {
        windows++
        if (windows < WINDOWS_BEFORE_RESTART) return Decision.Wait(windows, null)
        val now = clock()
        if (restarts > 0) {
            val wait = backoffMs(restarts) - (now - lastRestartAt)
            if (wait > 0) return Decision.Wait(windows, wait)
        }
        restarts++
        lastRestartAt = now
        windows = 0
        return Decision.Restart(restarts)
    }

    /** A real check through the tunnel passed: it carries traffic again. */
    @Synchronized
    fun onPass() {
        windows = 0
        restarts = 0
        lastRestartAt = 0L
    }

    /** A new session the user started. */
    @Synchronized
    fun reset() = onPass()

    @Synchronized
    fun restartsSoFar(): Int = restarts

    companion object {
        /** Two windows: about 55 s of a tunnel carrying nothing (the report: one window every ~27 s). */
        const val WINDOWS_BEFORE_RESTART = 2
        const val FIRST_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L

        /** Wait after restart number [done] before the next one: 30 s, 60 s, 120 s, 240 s, then 5 min. Pure. */
        fun backoffMs(done: Int): Long {
            if (done <= 0) return 0L
            val shift = minOf(done - 1, 10)
            return minOf(FIRST_BACKOFF_MS shl shift, MAX_BACKOFF_MS)
        }
    }
}

/**
 * Rate limit for a WARN line that repeats while nothing changes (the report: the same "failed a real traffic check" and
 * "Staying on it" lines every few seconds for minutes). The first [burst] lines pass, then one per [everyMs] carrying
 * how many were held back. [reset] when the condition clears. Thread-safe; the clock is injected.
 */
class WarnThrottle(
    private val everyMs: Long = 60_000L,
    private val burst: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var passed = 0
    private var suppressed = 0
    private var lastAt = 0L

    /** null: hold this line back. Otherwise log it, followed by the returned suffix (empty, or the held-back count). */
    @Synchronized
    fun admit(): String? {
        val now = clock()
        if (passed < burst || now - lastAt >= everyMs) {
            passed++
            lastAt = now
            val note = if (suppressed > 0) " ($suppressed similar lines held back)" else ""
            suppressed = 0
            return note
        }
        suppressed++
        return null
    }

    @Synchronized
    fun reset() {
        passed = 0
        suppressed = 0
        lastAt = 0L
    }
}
