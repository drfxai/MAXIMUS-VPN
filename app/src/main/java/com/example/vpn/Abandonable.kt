package com.example.vpn

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a blocking call (a real-request probe, a server race, a clean-address scan) so that cancelling
 * the caller returns at once instead of waiting out the call's own time limit. The call keeps running
 * on its own thread until its limit and its result is dropped; these calls only test, they hold no
 * connection state, so leaving one behind is safe. This is what lets Stop end a connect attempt within
 * a second instead of after the current round of tests.
 */
object Abandonable {
    private val inFlight = AtomicInteger(0)
    private val threadIds = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "maximus-test-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    })

    /** Blocking calls still running, including ones a cancelled caller left behind. */
    val running: Int get() = inFlight.get()

    suspend fun <T> run(block: () -> T): T = suspendCancellableCoroutine { cont ->
        inFlight.incrementAndGet()
        pool.execute {
            try {
                // Resuming a cancelled continuation is ignored, so a left-behind result goes nowhere.
                cont.resumeWith(runCatching(block))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /** Waits up to [maxMs] for left-behind calls to finish; true when none is running. */
    fun awaitIdle(maxMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + maxMs
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return inFlight.get() == 0
    }
}
