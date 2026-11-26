package com.example.vpn

import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.connections.PsiphonChainMemory
import com.example.vpn.connections.PsiphonFallbackLadder
import com.example.vpn.sidecar.PsiphonRegionPlan
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.RadianceSidecar
import com.example.vpn.smart.PinnedRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-25: a manual Psiphon pick that failed ended on Psiphon over Lantern. Only Smart Connect may choose, change or
 * chain engines; a manual pick runs that one connection, may retry itself, and ends FAILED with a reason
 * ([ManualEnginePick], [ConnectDeadline]).
 */
class ManualEnginePickTest {
    private val all: (String) -> Boolean = { true }

    @Test fun aManualPickIsAnExactConnectThatIsNotSmart() {
        assertTrue(ManualEnginePick.manual(smart = false, exact = true))
        assertFalse(ManualEnginePick.manual(smart = true, exact = true))
        assertFalse(ManualEnginePick.manual(smart = true, exact = false))
        assertFalse(ManualEnginePick.manual(smart = false, exact = false))
        assertFalse(ManualEnginePick.mayLeaveEngine(manual = true))
        assertTrue(ManualEnginePick.mayLeaveEngine(manual = false))
    }

    // ---- manual pick never falls back --------------------------------------------------------------------------------

    @Test fun aManualPsiphonPickThatTimesOutNeverMovesToPsiphonOverLantern() {
        val decision = ConnectDeadline.decide(stopped = false, exitLocked = false, fallbacksSoFar = 0,
            tried = setOf(ConfigFreeMethod.PSIPHON.id), verifiedHere = ConfigFreeMethod.PSIPHON_RADIANCE.id, runnable = all,
            manualPick = true)
        assertEquals(ConnectDeadline.Decision.GiveUp(ConnectDeadline.GiveUpReason.MANUAL_PICK), decision)
    }

    @Test fun noManualConfigFreePickFallsBackToAnythingAtTheDeadline() {
        for (method in ConfigFreeMethod.entries) {
            for (verified in listOf(null, "saved-1", ConfigFreeMethod.TOR_SNOWFLAKE.id)) {
                val decision = ConnectDeadline.decide(false, false, 0, setOf(method.id), verified, all, manualPick = true)
                assertEquals("${method.id} / $verified",
                    ConnectDeadline.Decision.GiveUp(ConnectDeadline.GiveUpReason.MANUAL_PICK), decision)
            }
        }
    }

    @Test fun stopAndALockedExitStillComeFirstForAManualPick() {
        assertEquals(ConnectDeadline.Decision.Stopped,
            ConnectDeadline.decide(true, false, 0, emptySet(), null, all, manualPick = true))
        assertEquals(ConnectDeadline.Decision.GiveUp(ConnectDeadline.GiveUpReason.EXIT_LOCKED),
            ConnectDeadline.decide(false, true, 0, emptySet(), null, all, manualPick = true))
    }

    @Test fun aManualDirectPsiphonFailureNeverClimbsThePsiphonLadder() {
        val allowed = PsiphonChainMemory.mayUseChain(direct = true, smart = false, autoFailover = true, fallback = true,
            exact = true, exitLocked = false, force = false, chainAlreadyTried = false, chainAvailable = true)
        assertFalse(allowed)
        assertNull(PsiphonFallbackLadder.next(failedDirect = true, failedShirokhorshidFallback = false, allowed = allowed,
            shirokhorshidAvailable = true, shirokhorshidTried = false, chainAvailable = true, chainTried = false,
            chainVerifiedHere = true))
        assertNull(PsiphonFallbackLadder.next(failedDirect = false, failedShirokhorshidFallback = true, allowed = allowed,
            shirokhorshidAvailable = true, shirokhorshidTried = true, chainAvailable = true, chainTried = false,
            chainVerifiedHere = false))
    }

    @Test fun aManualAetherPickNeverHandsOverOrChainsThroughAnotherEngine() {
        assertFalse(ConnectionEngineRunner.mayRecoverAether(aether = true, smart = false, autoFailover = true, exact = true,
            exitLocked = false, force = false, alreadyEscalated = false, fallback = true))
        // WARP through Lantern starts Radiance in front of Aether; WARP through a saved server is another path.
        assertFalse(ManualEnginePick.lanternHop(manual = true, lanternAvailable = true))
        assertNull(ManualEnginePick.savedServerRelay(manual = true, exitNode = "saved-1"))
    }

    // ---- Smart Connect still falls back ---------------------------------------------------------------------------

    @Test fun smartConnectStillFallsBackAtTheDeadline() {
        val next = ConnectDeadline.decide(false, false, 0, setOf(ConfigFreeMethod.PSIPHON.id), null, all, manualPick = false)
        assertEquals(ConfigFreeMethod.PSIPHON_RADIANCE.id, (next as ConnectDeadline.Decision.Fallback).profileId)
        assertEquals(ConnectDeadline.Decision.Fallback("saved-1", "verified on this network before"),
            ConnectDeadline.decide(false, false, 0, setOf(ConfigFreeMethod.PSIPHON.id), "saved-1", all))
    }

    @Test fun smartConnectStillClimbsThePsiphonLadderAndRecoversAether() {
        val allowed = PsiphonChainMemory.mayUseChain(direct = true, smart = true, autoFailover = false, fallback = false,
            exact = false, exitLocked = false, force = false, chainAlreadyTried = false, chainAvailable = true)
        assertTrue(allowed)
        assertEquals(PsiphonFallbackLadder.Step.SHIROKHORSHID, PsiphonFallbackLadder.next(failedDirect = true,
            failedShirokhorshidFallback = false, allowed = allowed, shirokhorshidAvailable = true, shirokhorshidTried = false,
            chainAvailable = true, chainTried = false, chainVerifiedHere = false))
        assertTrue(ConnectionEngineRunner.mayRecoverAether(aether = true, smart = true, autoFailover = false, exact = false,
            exitLocked = false, force = false, alreadyEscalated = false))
        assertTrue(ManualEnginePick.lanternHop(manual = false, lanternAvailable = true))
        assertFalse(ManualEnginePick.lanternHop(manual = false, lanternAvailable = false))
        assertEquals("saved-1", ManualEnginePick.savedServerRelay(manual = false, exitNode = "saved-1"))
    }

    // ---- the same engine may retry itself -----------------------------------------------------------------------------

    @Test fun aManualDirectPsiphonPickGetsTheTimeItsOwnRegionRotationNeeds() {
        val manual = ConnectDeadline.budgetFor(listOf(PsiphonSidecar.ID), manual = true, directPsiphon = true)
        assertEquals(ConnectDeadline.PSIPHON_MANUAL_MS, manual)
        assertTrue(manual > PsiphonRegionPlan.totalBudget("", 120_000L, manual = true, knownBlocked = false))
        // Smart Connect and other engines keep their budgets; a chain stays a chain.
        assertEquals(ConnectDeadline.ENGINE_MS,
            ConnectDeadline.budgetFor(listOf(PsiphonSidecar.ID), manual = false, directPsiphon = true))
        assertEquals(ConnectDeadline.ENGINE_MS,
            ConnectDeadline.budgetFor(listOf(RadianceSidecar.id), manual = true, directPsiphon = false))
        assertEquals(ConnectDeadline.CHAIN_MS,
            ConnectDeadline.budgetFor(listOf(PsiphonSidecar.ID, RadianceSidecar.id), manual = true, directPsiphon = false))
    }

    @Test fun aManualPsiphonPickStillRotatesItsOwnRegions() {
        val steps = PsiphonRegionPlan.rotation(available = listOf("DE", "NL", "US"), tried = emptySet(),
            remembered = emptyList(), leftMs = PsiphonRegionPlan.TOTAL_AUTO_MS)
        assertTrue(steps.toString(), steps.isNotEmpty())
        // Its own regions, and its own CDN-fronted meek protocols (same engine): never another engine.
        assertTrue(steps.all { it.region.isNotEmpty() || it.fronted })
        assertTrue(steps.any { it.region.isNotEmpty() })
    }

    @Test fun aPinnedSessionRestartsTheSameConfigAfterItStopsCarryingTraffic() {
        val recovery = PinnedRecovery(clock = { 0L })
        repeat(PinnedRecovery.WINDOWS_BEFORE_RESTART - 1) {
            assertTrue(recovery.onDeadWindow() is PinnedRecovery.Decision.Wait)
        }
        assertEquals(PinnedRecovery.Decision.Restart(1), recovery.onDeadWindow())
    }

    // ---- wiring: where the rules are applied ------------------------------------------------------------------------

    private fun source(path: String): String =
        listOf("src/main/java/com/example/$path", "app/src/main/java/com/example/$path").map { java.io.File(it) }
            .first { it.isFile }.readText()

    @Test fun theServiceAppliesTheManualRuleAtEveryHandOver() {
        val service = source("vpn/RayVpnService.kt")
        assertTrue(service.contains("ManualEnginePick.manual(smart = smart, exact = intent?.getBooleanExtra(EXTRA_EXACT, false) == true)"))
        assertTrue(service.contains("manualPick = manualPick)"))
        assertTrue(service.contains("ConnectDeadline.budgetMs(it, manual = exactSession)"))
        assertTrue(service.contains("ManualEnginePick.savedServerRelay(manual = exactSession"))
        assertTrue(service.contains("ManualEnginePick.lanternHop(manual = exactSession"))
        assertTrue(service.contains("ConnectDeadline.GiveUpReason.MANUAL_PICK -> com.example.R.string.connect_deadline_reason_manual"))
    }

    @Test fun everyManualEnginePickIsSentAsExact() {
        val home = source("ui/home/HomeScreen.kt")
        assertTrue(home.contains("BuiltinConnections.resolve(context, method.id) ?: method.profile(), exact = true)"))
        val page = source("ui/lab/BuiltinConnectionScreen.kt")
        assertTrue(page.contains("vpn.startOrRequestPermission(context, model.profile(), exact = true)"))
    }
}
