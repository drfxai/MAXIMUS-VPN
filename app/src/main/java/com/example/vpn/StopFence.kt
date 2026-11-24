package com.example.vpn

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The user's Stop is final for every connect attempt that began before it.
 *
 * Build 171 report: Stop during Smart Connect took 28 s (DISCONNECTING 18:35:26Z → DISCONNECTED 18:35:54Z). The
 * cancelled attempt still held the connect lock while one of its blocking steps (socket I/O is not interrupted by
 * Thread.interrupt) ran out its own time limit, and the teardown waited for the lock behind it. Now:
 *
 *  - every connect job carries the [AttemptTicket] (the [ConnectionLifecycle] generation it was started in);
 *  - Stop [fence]s every older ticket: a stale attempt that is still unwinding can no longer change the visible state
 *    or the notification, nor hand over to another automatic connect;
 *  - the teardown waits at most [LOCK_GRACE_MS] for the connect lock ([acquireWithin]) and then tears down without it.
 *
 * Pure (no Android), so the rules are unit-tested.
 */
object StopFence {
    /** How long Stop waits for a cancelled attempt to give the connect lock back before tearing down anyway. */
    const val LOCK_GRACE_MS = 1_500L

    @Volatile private var fencedBelow = Long.MIN_VALUE

    /** Attempts started in a generation below [generation] are stale from now on (the user's Stop). */
    fun fence(generation: Long) {
        synchronized(this) { if (generation > fencedBelow) fencedBelow = generation }
    }

    /** True unless the calling thread runs a connect attempt that a Stop has fenced off. */
    fun admits(): Boolean = admits(AttemptTicket.current())

    fun admits(ticket: Long?): Boolean = ticket == null || ticket >= fencedBelow

    /**
     * Takes [mutex] when it frees up within [graceMs]; false when the holder (a cancelled attempt stuck in a blocking
     * call) keeps it longer. The caller then proceeds without it.
     */
    suspend fun acquireWithin(mutex: Mutex, graceMs: Long = LOCK_GRACE_MS): Boolean =
        withTimeoutOrNull(graceMs) { mutex.lock(); true } == true

    internal fun resetForTest() = synchronized(this) { fencedBelow = Long.MIN_VALUE }
}

/** The [ConnectionLifecycle] generation a connect job was started in, visible to every thread it runs on. */
class AttemptTicket(val ticket: Long) : ThreadContextElement<Long?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AttemptTicket> {
        private val local = ThreadLocal<Long?>()

        /** The ticket of the connect job running on this thread, or null outside one. */
        fun current(): Long? = local.get()
    }

    override fun updateThreadContext(context: CoroutineContext): Long? {
        val previous = local.get()
        local.set(ticket)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Long?) {
        local.set(oldState)
    }
}
