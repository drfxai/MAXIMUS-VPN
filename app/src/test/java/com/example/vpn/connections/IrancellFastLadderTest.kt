package com.example.vpn.connections

import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.BuiltinExitNode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Build 140 on Irancell: ~7 minutes of full WARP scans after WireGuard showed the data block; no prewarm line. */
class IrancellFastLadderTest {
    private val store = mutableMapOf<String, String>()

    @Before fun setUp() {
        store.clear()
        CleanH2PeerMemory.read = { store[it] }
        CleanH2PeerMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        AetherPrewarm.resetForTest()
    }

    @After fun tearDown() {
        CleanH2PeerMemory.read = { null }
        CleanH2PeerMemory.write = { _, _ -> }
        AetherPrewarm.resetForTest()
    }

    @Test fun handshakeWithoutDataOnIrancellOrFilteredLinkTakesTheFastPath() {
        fun fast(engine: String, automatic: Boolean, carrier: String, irancell: Boolean, filtered: Boolean, reverse: Boolean) =
            ConnectionEngineRunner.warpDataBlockedFastPath(engine, automatic, carrier, irancell, filtered, reverse)
        assertTrue(fast(AetherSidecar.ID, true, "wireguard", true, false, false))
        assertTrue(fast(AetherSidecar.ID, true, "wireguard", false, true, false))
        // Not on an unfiltered link, not for the user's own carrier, not for MASQUE, not for a reverse relay.
        assertFalse(fast(AetherSidecar.ID, true, "wireguard", false, false, false))
        assertFalse(fast(AetherSidecar.ID, false, "wireguard", true, true, false))
        assertFalse(fast(AetherSidecar.ID, true, "h3", true, true, false))
        assertFalse(fast(AetherSidecar.ID, true, "wireguard", true, true, true))
        assertFalse(fast("serverless", true, "wireguard", true, true, false))
    }

    @Test fun meteredFilteredLinksCapTheWholeLadder() {
        assertEquals(ConnectionEngineRunner.HEAVY_LADDER_BUDGET_MS,
            ConnectionEngineRunner.ladderBudgetMs(AetherSidecar.ID, true, metered = true, irancell = true, cloudflareFiltered = false))
        assertEquals(ConnectionEngineRunner.HEAVY_LADDER_BUDGET_MS,
            ConnectionEngineRunner.ladderBudgetMs(AetherSidecar.ID, true, metered = true, irancell = false, cloudflareFiltered = true))
        assertNull(ConnectionEngineRunner.ladderBudgetMs(AetherSidecar.ID, true, metered = false, irancell = true, cloudflareFiltered = true))
        assertNull(ConnectionEngineRunner.ladderBudgetMs(AetherSidecar.ID, true, metered = true, irancell = false, cloudflareFiltered = false))
        assertNull(ConnectionEngineRunner.ladderBudgetMs(AetherSidecar.ID, false, metered = true, irancell = true, cloudflareFiltered = true))
        assertTrue(ConnectionEngineRunner.HEAVY_LADDER_BUDGET_MS <= 120_000L)
    }

    @Test fun linkHintsCarryMetered() {
        val p = com.example.vpn.smart.NetworkCapabilityProfile(transport = "cellular", ipv4Available = true, ipv6Available = false,
            udpAvailable = true, cloudflareReachable = false, meteredNetwork = true, measuredAt = 1)
        assertTrue(ConnectionEngineRunner.linkHints(p).metered)
        assertFalse(ConnectionEngineRunner.linkHints(p.copy(meteredNetwork = null)).metered)
    }

    @Test fun startFailureReasonsAreFixedStrings() {
        val stage = "engine process could not start"
        assertEquals(BuiltinExitNode.RUNTIME_MISSING, ConnectionEngineRunner.startFailureReason(AetherSidecar.ID,
            BuiltinExitNode.ExitNodeFailure(BuiltinExitNode.RUNTIME_MISSING), stage))
        assertEquals(stage, ConnectionEngineRunner.startFailureReason(AetherSidecar.ID, IllegalStateException("secret 1.2.3.4"), stage))
        // An exit failure whose text is not one of the fixed reasons is never shown as it is.
        assertEquals(stage, ConnectionEngineRunner.startFailureReason(AetherSidecar.ID,
            BuiltinExitNode.ExitNodeFailure("host secret.example"), stage))
    }

    @Test fun prewarmExplainsWhyItDidNothingOncePerGap() {
        val logs = mutableListOf<String>()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        fun run(now: Long, vpn: Boolean = false) = AetherPrewarm.run("cell:43235", "screen-on", vpnActive = vpn, powerSave = false,
            metered = true, now = now, connect = { 40L }, log = { logs += it }, event = { r, a -> events += r to a })
        assertNull(run(1_000))
        assertEquals(listOf("aether/prewarm · cell:43235 · screen-on · skipped: no clean H2 gateway remembered on this network yet (nothing sent) · metered link"), logs)
        assertEquals("SKIP", events.single().first)
        assertEquals("nothing_remembered", events.single().second["skip"])
        // Repeated screen-ons stay quiet until the gap passes; a VPN that is up is never logged.
        assertNull(run(2_000)); assertNull(run(3_000, vpn = true))
        assertEquals(1, logs.size)
        assertNull(run(1_000 + AetherPrewarm.METERED_GAP_MS))
        assertEquals(2, logs.size)
        // Once a gateway is remembered the prewarm runs and reports it as an event too.
        CleanH2PeerMemory.remember("cell:43235", "162.159.198.1:443", now = 5_000)
        assertNotNull(run(10_000))
        assertEquals("PASS", events.last().first)
        assertEquals("1", events.last().second["answered"])
    }
}
