package com.example.vpn.connections

import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import org.junit.Assert.*
import org.junit.Test

/**
 * Irancell diag 2026-11-14: WARP registration failed with a TLS handshake failure, the H3 + WireGuard race never ran
 * and H3 burned its whole 75 s window. Now: one MASQUE HTTP/2 attempt, 15 s cap, registration failure ends it.
 */
class IrancellWarpTest {
    private val irancellMethods = listOf("h3", "wireguard", "h2", ConnectionEngineRunner.H2_SPLIT)

    @Test fun onlyAutomaticForegroundAetherOnIrancell() {
        assertTrue(IrancellWarp.applies(AetherSidecar.ID, automatic = true, irancell = true, relayOnly = false, reverseRelay = false))
        assertFalse("other networks keep their ladder",
            IrancellWarp.applies(AetherSidecar.ID, automatic = true, irancell = false, relayOnly = false, reverseRelay = false))
        assertFalse("a carrier the user chose is theirs",
            IrancellWarp.applies(AetherSidecar.ID, automatic = false, irancell = true, relayOnly = false, reverseRelay = false))
        assertFalse("the background relay keeps its own plan",
            IrancellWarp.applies(AetherSidecar.ID, automatic = true, irancell = true, relayOnly = true, reverseRelay = false))
        assertFalse(IrancellWarp.applies(AetherSidecar.ID, automatic = true, irancell = true, relayOnly = false, reverseRelay = true))
        assertFalse(IrancellWarp.applies("serverless", automatic = true, irancell = true, relayOnly = false, reverseRelay = false))
    }

    @Test fun singleAttemptIsMasqueOverHttp2NeverWireGuardOrH3() {
        // The real Irancell order puts H3 and WireGuard first; the single attempt skips both.
        val order = ConnectionEngineRunner.order(irancellMethods, null, false, emptySet(), irancell = true)
        assertEquals(listOf("h3", "wireguard", "h2", ConnectionEngineRunner.H2_SPLIT), order)
        assertEquals("h2", IrancellWarp.pick(order, null))
        assertEquals(listOf("h3", "wireguard", ConnectionEngineRunner.H2_SPLIT), IrancellWarp.skipped(order, "h2"))
        // A remembered clean HTTP/2 gateway goes before any scan.
        assertEquals(ConnectionEngineRunner.CLEAN_H2,
            IrancellWarp.pick(listOf(ConnectionEngineRunner.CLEAN_H2) + order, null))
        // A MASQUE HTTP/2 method verified here before is kept first.
        assertEquals(ConnectionEngineRunner.H2_SPLIT,
            IrancellWarp.pick(listOf(ConnectionEngineRunner.CLEAN_H2) + order, ConnectionEngineRunner.H2_SPLIT))
        // H3, WireGuard and their follow-ups are never picked, even when remembered as verified.
        for (blocked in listOf("h3", "wireguard", ConnectionEngineRunner.WIREGUARD_MTU, ConnectionEngineRunner.WIREGUARD_NOIZE, "wiw"))
            assertEquals(blocked, "h2", IrancellWarp.pick(order + blocked, blocked))
        // Relays are the background relay's job.
        assertEquals("h2", IrancellWarp.pick(listOf(ConnectionEngineRunner.H2_RELAY) + order, ConnectionEngineRunner.H2_RELAY))
        assertNull(IrancellWarp.pick(listOf("h3", "wireguard"), null))
        assertNull(IrancellWarp.pick(emptyList(), "h2"))
    }

    @Test fun realIrancellCandidatesYieldFragmentedHttp2() {
        val auto = ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto"))
        val variants = ConnectionEngineRunner.operatorVariants(auto.map(ConnectionEngineRunner::irancellH3), irancell = true, mci = false)
        val h2 = variants.first { ProfileExtras.read(it).let { e -> e.optString("carrier") == "h2" && !e.optBoolean(AetherSidecar.SNI_SPLIT) } }
        val chosen = IrancellWarp.withNativeApi(h2)
        val e = ProfileExtras.read(chosen)
        assertEquals("h2", e.optString("carrier"))
        assertEquals("Aether's own API route, so its name/edge fallback and API fragment apply", "native", e.optString("apiRoute"))
        val args = AetherSettings.read(e).arguments("h2", e.optBoolean("fragment", true))
        assertTrue("--fragment also splits the WARP API ClientHello in Aether 2.4.0", "--fragment" in args)
    }

    @Test fun aRouteTheUserChoseIsKept() {
        val oob = AetherSidecar.profile("h2", advanced = AetherSettings(apiRoute = "oob"))
        assertEquals("oob", ProfileExtras.read(IrancellWarp.withNativeApi(oob)).optString("apiRoute"))
        val auto = AetherSidecar.profile("h2")
        assertEquals("native", ProfileExtras.read(IrancellWarp.withNativeApi(auto)).optString("apiRoute"))
    }

    @Test fun readinessWaitFitsTheFifteenSecondCap() {
        assertEquals(15_000L, IrancellWarp.BUDGET_MS)
        // The 75 s filtered window (what H3 burned) is cut to the cap.
        assertEquals(15_000L, IrancellWarp.waitMs(75_000L, startedAt = 1_000L, now = 1_000L))
        // Time spent preparing the engine counts against the cap.
        assertEquals(11_000L, IrancellWarp.waitMs(75_000L, startedAt = 1_000L, now = 5_000L))
        // Never longer than the engine's own window.
        assertEquals(8_000L, IrancellWarp.waitMs(8_000L, startedAt = 0L, now = 0L))
        // Budget spent: still a short wait so the engine can report why it failed.
        assertEquals(IrancellWarp.MIN_WAIT_MS, IrancellWarp.waitMs(75_000L, startedAt = 0L, now = 40_000L))
        // Prepare + wait ends at the cap whenever the floor is not needed.
        for (spent in 0L..14_000L step 500L)
            assertEquals(IrancellWarp.BUDGET_MS, spent + IrancellWarp.waitMs(180_000L, 0L, spent))
    }

    @Test fun registrationFailureEndsTheWaitAfterTheGrace() {
        val failure = "WARP API registration failed (TLS handshake failed)"
        val watch = IrancellWarp.RegistrationWatch(graceMs = 2_000L)
        assertFalse(watch.shouldStop(null, 0L))
        assertFalse("a scan timeout is not a registration failure",
            watch.shouldStop("Aether gateway scan did not reach a serving tunnel within 15s; traffic is unverified.", 100L))
        assertFalse("Aether may still answer on its fallback API route", watch.shouldStop(failure, 1_000L))
        assertFalse(watch.shouldStop(failure, 2_999L))
        assertTrue(watch.shouldStop(failure, 3_000L))
    }

    @Test fun aLaterStartupStageClearsTheRegistrationFailure() {
        val failure = "WARP API registration failed (TLS handshake failed)"
        val watch = IrancellWarp.RegistrationWatch(graceMs = 2_000L)
        assertFalse(watch.shouldStop(failure, 0L))
        // Fallback route answered: the identity stage cleared the failure, the grace starts over if it comes back.
        assertFalse(watch.shouldStop(null, 1_500L))
        assertFalse(watch.shouldStop(failure, 2_500L))
        assertFalse(watch.shouldStop(failure, 4_000L))
        assertTrue(watch.shouldStop(failure, 4_500L))
    }

    @Test fun registrationFailFastIsWellInsideTheCap() {
        assertTrue(IrancellWarp.REGISTRATION_GRACE_MS < IrancellWarp.BUDGET_MS)
        assertTrue("the old Irancell burn was 75 s", IrancellWarp.BUDGET_MS < ConnectionEngineRunner.FILTERED_BUDGET_MS.getValue("h3"))
    }
}
