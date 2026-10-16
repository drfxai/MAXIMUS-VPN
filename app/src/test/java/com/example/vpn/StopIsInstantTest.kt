package com.example.vpn

import com.example.core.AppProcess
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.ui.home.SmartButtonState
import com.example.vpn.sidecar.EngineReaper
import com.example.vpn.sidecar.RunningEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Stop ends a connect attempt at once: blocking tests are left behind and starting engines are ended. */
class StopIsInstantTest {

    @Test fun cancellingReturnsAtOnceWhileTheBlockingCallFinishesOnItsOwn() = runBlocking {
        val finished = CountDownLatch(1)
        val job = async { Abandonable.run { Thread.sleep(1_500); finished.countDown(); 42 } }
        delay(100)
        val start = System.currentTimeMillis()
        job.cancel()
        runCatching { job.await() }
        assertTrue("cancel took too long", System.currentTimeMillis() - start < 300)
        // The left-behind call still completes in the background, and its result goes nowhere.
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertTrue(Abandonable.awaitIdle(1_000))
    }

    @Test fun anUncancelledCallReturnsItsResultAndItsError() = runBlocking {
        assertEquals(7, Abandonable.run { 7 })
        val error = runCatching { Abandonable.run { error("boom") } }.exceptionOrNull()
        assertEquals("boom", error?.message)
    }

    private class FakeEngine : RunningEngine {
        @Volatile var stopped = false
        override val isAlive: Boolean get() = !stopped
        override fun awaitReady(timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!stopped && System.currentTimeMillis() < deadline) Thread.sleep(10)
            return !stopped
        }
        override fun stop() { stopped = true }
    }

    @Test fun stopAllEndsAStartingEngineAndItsWaitReturns() {
        val engine = FakeEngine()
        val tracked = EngineReaper.track(engine)
        var ready: Boolean? = null
        val waiter = Thread { ready = tracked.awaitReady(60_000) }.apply { start() }
        Thread.sleep(50)
        val start = System.currentTimeMillis()
        EngineReaper.stopAll()
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertEquals(false, ready)
        assertTrue(System.currentTimeMillis() - start < 1_000)
        assertTrue(engine.stopped)
    }

    @Test fun aStoppedEngineIsNoLongerTracked() {
        val before = EngineReaper.count
        val tracked = EngineReaper.track(FakeEngine())
        assertEquals(before + 1, EngineReaper.count)
        tracked.stop()
        assertEquals(before, EngineReaper.count)
    }

    @Test fun torProcessIsRecognisedByName() {
        assertTrue(AppProcess.isTorProcessName("com.drfxai.maximusvpn:tor"))
        assertFalse(AppProcess.isTorProcessName("com.drfxai.maximusvpn"))
        assertFalse(AppProcess.isTorProcessName(null))
    }

    @Test fun smartButtonHasThreeStatesInThePowerButtonsMeaning() {
        fun of(s: ConnectionStatus) = SmartButtonState.of(ConnectionState(status = s))
        assertEquals(SmartButtonState.OFF, of(ConnectionStatus.DISCONNECTED))
        assertEquals(SmartButtonState.OFF, of(ConnectionStatus.FAILED))
        assertEquals(SmartButtonState.OFF, of(ConnectionStatus.DISCONNECTING))
        assertEquals(SmartButtonState.CONNECTING, of(ConnectionStatus.PREPARING))
        assertEquals(SmartButtonState.CONNECTING, of(ConnectionStatus.VERIFYING))
        assertEquals(SmartButtonState.ON, of(ConnectionStatus.CONNECTED))
        assertFalse(SmartButtonState.OFF.tapStops)
        assertTrue(SmartButtonState.CONNECTING.tapStops)
        assertTrue(SmartButtonState.ON.tapStops)
    }
}
