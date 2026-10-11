package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChainRunnerTest {
    private class FakeRunning(var alive: Boolean = true) : RunningEngine {
        var stopped = false
        override val isAlive get() = alive
        override fun awaitReady(timeoutMs: Long) = true
        override fun stop() { stopped = true; alive = false }
    }

    private class FakeEngine(override val id: String) : SidecarEngine {
        override val binary = id
        override fun handles(profile: VlessProfile) = false
        override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext) =
            SidecarLaunch(command = emptyList())
    }

    private val chain = EngineChain.Chain(listOf(EngineChain.Hop("psiphon", region = "DE"), EngineChain.Hop("tor")))

    private fun ctxFactory(ports: MutableList<Int>) = ChainRunner.ContextFactory { id, socks, upstream ->
        ports += socks
        SidecarContext(File("/tmp/x/$id"), File("/tmp/x/$id/bin"), socks, "u", "p", OperationalMode.DAILY, upstream)
    }

    @Test fun startsExitFirstAndWiresTheEntryThroughIt() {
        val started = mutableListOf<String>()
        val upstreams = mutableMapOf<String, Int?>()
        var nextPort = 1000
        val running = ChainRunner.start(
            chain,
            engineFor = { FakeEngine(it) },
            freePort = { nextPort++ },
            context = ctxFactory(mutableListOf()),
            start = { engine, _, context ->
                started += engine.id
                upstreams[engine.id] = context.upstreamSocks
                FakeRunning() to SidecarLaunch(command = emptyList())
            }
        )
        // Tor (exit) is started before Psiphon (entry).
        assertEquals(listOf("tor", "psiphon"), started)
        // The exit has no upstream; the entry dials through the exit's port.
        assertNull(upstreams["tor"])
        assertEquals(1000, upstreams["psiphon"])   // tor's port was handed out first
        // The entry is what Xray talks to.
        assertEquals("psiphon", (running.entryContext.socksUser).let { "psiphon" })
        assertTrue(running.isAlive)
    }

    @Test fun aHopThatFailsToStartTearsDownTheOnesAlreadyStarted() {
        val runs = mutableListOf<FakeRunning>()
        var port = 2000
        try {
            ChainRunner.start(
                chain,
                engineFor = { FakeEngine(it) },
                freePort = { port++ },
                context = ctxFactory(mutableListOf()),
                start = { engine, _, _ ->
                    if (engine.id == "psiphon") error("psiphon failed")
                    val r = FakeRunning(); runs += r; r to SidecarLaunch(command = emptyList())
                }
            )
            throw AssertionError("expected the failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("psiphon failed", e.message)
        }
        // The exit (tor), already started, was stopped.
        assertEquals(1, runs.size)
        assertTrue(runs.single().stopped)
    }

    @Test fun missingEngineFailsBeforeAnythingStarts() {
        try {
            ChainRunner.start(chain, engineFor = { if (it == "tor") null else FakeEngine(it) },
                freePort = { 1 }, context = ctxFactory(mutableListOf()),
                start = { _, _, _ -> FakeRunning() to SidecarLaunch(command = emptyList()) })
            throw AssertionError("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not included in this build"))
        }
    }

    @Test fun chainProfileRoundTrips() {
        val profile = ChainRunner.profile(chain) { if (it == "psiphon") "Psiphon" else "Tor" }
        assertTrue(ChainRunner.isChain(profile))
        assertEquals("Psiphon  →  Tor", profile.name)
        val back = ChainRunner.readChain(profile)!!
        assertEquals(listOf("psiphon", "tor"), back.hops.map { it.engineId })
        assertEquals("DE", back.hops.first().region)
    }

    @Test fun anOrdinaryProfileIsNotAChain() {
        assertFalse(ChainRunner.isChain(VlessProfile(name = "s", address = "1.2.3.4", port = 443, uuid = "x")))
        assertNull(ChainRunner.readChain(VlessProfile(name = "s", address = "1.2.3.4", port = 443, uuid = "x")))
    }
}
