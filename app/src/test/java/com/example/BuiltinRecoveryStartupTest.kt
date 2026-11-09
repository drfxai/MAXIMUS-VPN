package com.example

import com.example.data.model.*
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.lab.EngineProbe
import com.example.vpn.sidecar.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BuiltinRecoveryStartupTest {
    @Test fun smartRecoveryTriesEveryDirectVariantAndKeepsTheRequestedMode() {
        val methods = mutableListOf<String>()
        val modes = mutableListOf<OperationalMode>()
        val engine = object : SidecarEngine {
            override val id = ServerlessSidecar.ID
            override val binary = id
            override fun handles(profile: VlessProfile) = true
            override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
                methods += ProfileExtras.read(profile).getString("variant")
                modes += settings.operationalMode
                error("test-only preparation failure")
            }
        }
        val dir = Files.createTempDirectory("builtin-recovery").toFile()
        try {
            val result = EngineProbe.runIsolated(engine, ServerlessSidecar.profile(), File("unused"), dir,
                1, 1, 0, OperationalMode.GOD_MODE, false)
            assertEquals(listOf("fragA", "fragB", "oob"), methods)
            assertTrue(modes.all { it == OperationalMode.GOD_MODE })
            assertFalse(result.stage.carriesTraffic)
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellingSmartRecoveryInterruptsTheWaitAndStopsTheOwnedEngine() = runBlocking {
        val waiting = CountDownLatch(1)
        val stopped = AtomicBoolean()
        val engine = object : SidecarEngine {
            override val id = ServerlessSidecar.ID
            override val binary = id
            override fun handles(profile: VlessProfile) = true
            override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext) =
                SidecarLaunch(listOf("unused"), readyTimeoutMs = 180_000)
            override fun startPrepared(profile: VlessProfile, settings: AppSettings, launch: SidecarLaunch, context: SidecarContext) =
                object : RunningEngine {
                    override val isAlive get() = !stopped.get()
                    override fun awaitReady(timeoutMs: Long): Boolean { waiting.countDown(); Thread.sleep(timeoutMs); return false }
                    override fun stop() { stopped.set(true) }
                }
        }
        val dir = Files.createTempDirectory("builtin-cancel").toFile()
        try {
            val job = launch(Dispatchers.IO) {
                runInterruptible {
                    EngineProbe.runIsolated(engine, ServerlessSidecar.profile(), File("unused"), dir,
                        1, 1, 0, OperationalMode.DAILY, false)
                }
            }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue("The cancelled recovery must stop its engine", stopped.get())
        } finally { dir.deleteRecursively() }
    }
}
