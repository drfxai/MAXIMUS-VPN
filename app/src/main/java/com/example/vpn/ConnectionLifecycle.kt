package com.example.vpn

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-flight rules for connect, disconnect, reconnect and failover.
 *
 *  - Every request that comes from outside the running connection (the user's Connect or Disconnect, a
 *    reconnect, Always-on) opens a new generation. Automatic work (a failover switch, an auto-reconnect after
 *    the network returns) carries the generation it was started in and is dropped when a newer one exists, so a
 *    failover that fires while the user disconnects never brings the tunnel back.
 *  - One teardown at a time: a second disconnect while one runs joins it instead of stopping the engine and
 *    closing the interface again.
 *  - One failover switch at a time: the watchdog and the tunnel-error path cannot both start a switch.
 *
 * Pure (no Android), so the rules are unit-tested.
 */
class ConnectionLifecycle(
    /**
     * The generation counter. The VPN service passes the process-wide [PROCESS] counter: [StopFence] is process-wide,
     * so tickets must keep counting up across service instances. Build 175 report: after a Disconnect the service
     * stopped itself; the next Connect (3-5 s later, same process) created a new service whose own counter started
     * again at 1, below the fence the Stop had set, so every state change of that connect was dropped. Psiphon's
     * region run read the fence as a cancel and ended silently (18 min "Preparing"), and verified Lantern / Psiphon
     * over Lantern sessions never became CONNECTED (no step 10, no transfer checks).
     */
    private val generation: AtomicLong = AtomicLong(0)
) {
    private val switching = AtomicBoolean(false)
    private val teardownLock = Any()
    private var teardown: CompletableDeferred<Unit>? = null

    companion object {
        /** One counter for the whole process, shared by every service instance (see [generation]). */
        val PROCESS = AtomicLong(0)
    }

    /** A new request from outside; anything automatic started before it is now stale. */
    fun newIntent(): Long = generation.incrementAndGet()

    fun current(): Long = generation.get()

    fun isCurrent(ticket: Long): Boolean = generation.get() == ticket

    /** True for the one caller allowed to start a failover switch; false while another switch is pending. */
    fun tryBeginSwitch(): Boolean = switching.compareAndSet(false, true)

    fun endSwitch() = switching.set(false)

    val switchPending: Boolean get() = switching.get()

    /**
     * Runs [block] as the only teardown in flight. A caller arriving while one runs waits for it and returns
     * false without running [block] again; the caller that ran it gets true.
     */
    suspend fun singleTeardown(block: suspend () -> Unit): Boolean {
        val (mine, deferred) = synchronized(teardownLock) {
            val running = teardown
            if (running != null && !running.isCompleted) false to running
            else CompletableDeferred<Unit>().also { teardown = it }.let { true to it }
        }
        if (!mine) {
            deferred.await()
            return false
        }
        try {
            block()
        } finally {
            deferred.complete(Unit)
        }
        return true
    }
}
