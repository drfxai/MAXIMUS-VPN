package com.example.vpn

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.boot.QuickConnect
import com.example.vpn.sidecar.EngineReaper
import com.example.vpn.sidecar.RunningEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Build 171 report: Stop during Smart Connect took 28 s (session 08151fb3, DISCONNECTING 18:35:26Z → DISCONNECTED
 * 18:35:54Z) because the teardown waited for the connect lock that a cancelled attempt held inside a blocking step.
 * These tests model the service's rules with the real [StopFence], [AttemptTicket], [ConnectionLifecycle] and
 * [EngineReaper]: Stop finishes within the lock grace, the stale attempt cannot write state or start another attempt,
 * and the engine it was starting is ended.
 */
class StopFenceTest {
    @Before fun setUp() = StopFence.resetForTest()
    @After fun tearDown() = StopFence.resetForTest()

    /** The service's state holder, guarded like RayVpnService.updateState. */
    private class State {
        val value = AtomicReference(ConnectionStatus.DISCONNECTED)
        val dropped = AtomicInteger(0)
        fun update(s: ConnectionStatus) {
            if (!StopFence.admits()) { dropped.incrementAndGet(); return }
            value.set(s)
        }
    }

    private class StartingEngine : RunningEngine {
        @Volatile var stopped = false
        override val isAlive: Boolean get() = !stopped
        override fun awaitReady(timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!stopped && System.currentTimeMillis() < deadline) Thread.sleep(10)
            return !stopped
        }
        override fun stop() { stopped = true }
    }

    @Test fun aTicketBelowTheFenceIsRefusedAndNewerOnesAndUntaggedCodeAreAdmitted() = runBlocking {
        assertTrue(StopFence.admits())
        withContext(AttemptTicket(4)) { assertTrue(StopFence.admits()) }
        StopFence.fence(5)
        withContext(AttemptTicket(4)) { assertFalse(StopFence.admits()) }
        withContext(AttemptTicket(5)) { assertTrue(StopFence.admits()) }
        withContext(AttemptTicket(6)) { assertTrue(StopFence.admits()) }
        // The teardown itself and the UI run outside any attempt.
        assertTrue(StopFence.admits())
        assertNull(AttemptTicket.current())
        // The ticket follows the attempt onto IO threads, and only there.
        withContext(AttemptTicket(4) + Dispatchers.IO) { assertEquals(4L, AttemptTicket.current()) }
        // A fence never moves back.
        StopFence.fence(2)
        assertFalse(StopFence.admits(4))
    }

    @Test fun stopFinishesWithinTheGraceWhileTheCancelledAttemptIsStuckAndNothingRestarts() = runBlocking {
        val lifecycle = ConnectionLifecycle()
        val mutex = Mutex()
        val state = State()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stuckInBlockingStep = CountDownLatch(1)
        val releaseBlockingStep = CountDownLatch(1)
        val attemptsStarted = AtomicInteger(0)
        val engine = EngineReaper.track(StartingEngine())

        // Smart Connect: an attempt holds the lock, starts an engine and then sits in a step that ignores cancellation
        // (socket I/O is not interrupted). When it returns it would fail the step and start the next attempt.
        val ticket = lifecycle.newIntent()
        val connectJob = scope.launch(AttemptTicket(ticket)) {
            mutex.withLock {
                attemptsStarted.incrementAndGet()
                state.update(ConnectionStatus.PREPARING)
                stuckInBlockingStep.countDown()
                releaseBlockingStep.await(20, TimeUnit.SECONDS)
                // What the old code did right after the blocking step: FAILED, then the next ladder step.
                state.update(ConnectionStatus.FAILED)
                if (isActive) {
                    attemptsStarted.incrementAndGet()
                    state.update(ConnectionStatus.PREPARING)
                }
            }
        }
        assertTrue(stuckInBlockingStep.await(5, TimeUnit.SECONDS))
        assertEquals(ConnectionStatus.PREPARING, state.value.get())

        // The user's Stop, as ACTION_DISCONNECT runs it.
        val began = System.nanoTime()
        StopFence.fence(lifecycle.newIntent())
        connectJob.cancel()
        EngineReaper.stopAll()
        state.update(ConnectionStatus.DISCONNECTING)
        val tornDown = lifecycle.singleTeardown {
            val locked = StopFence.acquireWithin(mutex, 300)
            try {
                assertFalse("the stuck attempt still holds the lock", locked)
                state.update(ConnectionStatus.DISCONNECTED)
            } finally {
                if (locked) mutex.unlock()
            }
        }
        val tookMs = (System.nanoTime() - began) / 1_000_000
        assertTrue(tornDown)
        assertTrue("stop took $tookMs ms", tookMs < 2_000)
        assertEquals(ConnectionStatus.DISCONNECTED, state.value.get())

        // The engine the attempt was starting was ended at once.
        val waited = System.currentTimeMillis()
        assertFalse(engine.awaitReady(5_000))
        assertTrue(System.currentTimeMillis() - waited < 1_000)

        // The blocking step finally returns: its FAILED is dropped and no new attempt starts.
        releaseBlockingStep.countDown()
        connectJob.join()
        assertEquals(ConnectionStatus.DISCONNECTED, state.value.get())
        assertTrue(state.dropped.get() >= 1)
        assertEquals(1, attemptsStarted.get())
        assertFalse(mutex.isLocked)
        scope.cancel()
    }

    @Test fun theNextConnectAfterStopOwnsTheStateAgain() = runBlocking {
        val lifecycle = ConnectionLifecycle()
        val state = State()
        StopFence.fence(lifecycle.newIntent())
        val writes = CopyOnWriteArrayList<ConnectionStatus>()
        val next = lifecycle.newIntent()
        withContext(AttemptTicket(next) + Dispatchers.IO) {
            state.update(ConnectionStatus.PREPARING)
            writes += state.value.get()
        }
        assertEquals(listOf(ConnectionStatus.PREPARING), writes)
        assertEquals(0, state.dropped.get())
    }

    @Test fun aFreeLockIsTakenAtOnce() = runBlocking {
        val mutex = Mutex()
        assertTrue(StopFence.acquireWithin(mutex, 100))
        assertTrue(mutex.isLocked)
        mutex.unlock()
    }

    @Test fun tileAndWidgetTapsStopAConnectThatIsStillSearching() {
        fun of(s: ConnectionStatus) = QuickConnect.tapStops(ConnectionState(status = s))
        assertTrue(of(ConnectionStatus.PREPARING))
        assertTrue(of(ConnectionStatus.CONNECTING))
        assertTrue(of(ConnectionStatus.VPN_INTERFACE_ESTABLISHED))
        assertTrue(of(ConnectionStatus.VERIFYING))
        assertTrue(of(ConnectionStatus.CONNECTED))
        assertTrue(of(ConnectionStatus.RECONNECTING))
        // A teardown already under way is left alone; nothing running, nothing to stop.
        assertFalse(of(ConnectionStatus.DISCONNECTING))
        assertFalse(of(ConnectionStatus.DISCONNECTED))
        assertFalse(of(ConnectionStatus.FAILED))
    }

    @Test fun theReaperStillHandsBackTheEngineItWraps() {
        val engine = StartingEngine()
        val tracked = EngineReaper.track(engine)
        try {
            assertTrue(EngineReaper.underlying(tracked) === engine)
            assertTrue(EngineReaper.underlying(engine) === engine)
        } finally {
            tracked.stop()
        }
    }
}
