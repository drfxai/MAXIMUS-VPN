package com.example.vpn

/**
 * The readiness window an engine announced for the attempt now running ("ready within 120s", Aether's "220s
 * readiness budget", Psiphon's region rotation). The connect deadline ([ConnectDeadline.effectiveBudget]) reads it so a
 * manual pick is never cut before the window its own stage text promised.
 *
 * Keyed by the connect attempt: a hint from an older attempt never stretches a newer one.
 */
object EngineBudgetHint {
    private data class Hint(val attemptId: String, val readyBy: Long)

    @Volatile private var hint: Hint? = null

    /** Supplies the current attempt id; set by the VPN service. */
    @Volatile var attemptSource: () -> String? = { null }

    /** The engine of the current attempt will be ready within [windowMs] from [now] (the longest announcement wins). */
    fun announce(windowMs: Long, now: Long = System.currentTimeMillis()) {
        val attempt = runCatching { attemptSource() }.getOrNull() ?: return
        set(attempt, now + windowMs)
    }

    /** The engine of the current attempt will be ready by [readyBy] (wall clock); e.g. Tor's bootstrap still advancing. */
    fun announceUntil(readyBy: Long) {
        val attempt = runCatching { attemptSource() }.getOrNull() ?: return
        set(attempt, readyBy)
    }

    fun set(attemptId: String, readyBy: Long) {
        val current = hint
        hint = if (current != null && current.attemptId == attemptId) current.copy(readyBy = maxOf(current.readyBy, readyBy))
            else Hint(attemptId, readyBy)
    }

    /** When the engine of [attemptId] said it would be ready by, or null. */
    fun readyBy(attemptId: String?): Long? = hint?.takeIf { attemptId != null && it.attemptId == attemptId }?.readyBy

    fun clear() { hint = null }
}
