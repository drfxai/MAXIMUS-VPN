package com.example.vpn.sidecar

import com.example.data.model.OperationalMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EngineHandoffTest {
    private class FakeEngine : RunningEngine {
        var stopped = 0
        override val isAlive: Boolean get() = stopped == 0
        override fun awaitReady(timeoutMs: Long) = isAlive
        override fun stop() { stopped++ }
    }

    private fun parked(engine: RunningEngine, dir: File, at: Long, mode: OperationalMode = OperationalMode.GOD_MODE) = EngineHandoff.Parked(
        "p1", "tor", mode, engine, SidecarContext(dir, File("tor"), 18901, "u", "p", mode), SidecarLaunch(listOf("tor")), at
    )

    @After fun tearDown() = EngineHandoff.clear()

    @Test fun theConnectTakesOverTheTestedEngineAndItsFolderGoesWhenItStops() {
        val dir = Files.createTempDirectory("handoff").toFile()
        val engine = FakeEngine()
        EngineHandoff.park(parked(engine, dir, at = 1_000))
        assertTrue(EngineHandoff.holds(dir))
        val kept = EngineHandoff.take("p1", "tor", OperationalMode.GOD_MODE, now = 5_000)
        assertNotNull(kept)
        assertEquals(0, engine.stopped)
        assertTrue(dir.exists())
        kept!!.engine.stop()
        assertEquals(1, engine.stopped)
        assertFalse(dir.exists())
        assertNull(EngineHandoff.take("p1", "tor", OperationalMode.GOD_MODE, now = 5_000))
    }

    @Test fun anotherConfigModeOrAnOldEngineIsStoppedNotReused() {
        for ((id, mode, now) in listOf(Triple("p2", OperationalMode.GOD_MODE, 2_000L), Triple("p1", OperationalMode.DAILY, 2_000L),
                Triple("p1", OperationalMode.GOD_MODE, 1_000L + EngineHandoff.MAX_AGE_MS + 1))) {
            val dir = Files.createTempDirectory("handoff").toFile()
            val engine = FakeEngine()
            EngineHandoff.park(parked(engine, dir, at = 1_000))
            assertNull(EngineHandoff.take(id, "tor", mode, now = now))
            assertEquals(1, engine.stopped)
            assertFalse(dir.exists())
        }
    }

    @Test fun parkingASecondEngineStopsTheFirst() {
        val first = FakeEngine()
        EngineHandoff.park(parked(first, Files.createTempDirectory("a").toFile(), at = 1_000))
        EngineHandoff.park(parked(FakeEngine(), Files.createTempDirectory("b").toFile(), at = 2_000))
        assertEquals(1, first.stopped)
    }
}
