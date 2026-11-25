package com.example.vpn

import com.example.data.model.ConnectionRoute
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.OperationalMode
import com.example.ui.home.ActiveCardModel
import com.example.vpn.connections.BuiltinConnections
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.safety.FailClosedPolicy
import com.example.vpn.safety.PowerButtonPolicy
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.SidecarProcess
import com.example.vpn.smart.SmartConnectPlan
import com.example.vpn.verify.ProbePath
import com.example.vpn.verify.RouteVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1.0.4 build 176 on-device test (2026-11-25, Samsung Z Flip3, Irancell LTE): the bugs the logs showed, one test each.
 */
class Build176DeviceTestFixesTest {
    private val all: (String) -> Boolean = { true }

    // 1. Manual picks fell back: Psiphon → PoL → Tor, PoL → Tor, Shir o Khorshid → PoL, Aether → PoL (all [DEADLINE]).
    @Test fun noManualPickFromTheDeviceTestFallsBackAtItsDeadline() {
        listOf(ConfigFreeMethod.PSIPHON, ConfigFreeMethod.PSIPHON_RADIANCE, ConfigFreeMethod.SHIROKHORSHID,
            ConfigFreeMethod.AETHER_WARP).forEach { picked ->
            val decision = ConnectDeadline.decide(stopped = false, exitLocked = false, fallbacksSoFar = 0,
                tried = setOf(picked.id), verifiedHere = ConfigFreeMethod.RADIANCE.id, runnable = all, manualPick = true)
            assertEquals(picked.name, ConnectDeadline.Decision.GiveUp(ConnectDeadline.GiveUpReason.MANUAL_PICK), decision)
        }
        // Smart Connect still moves on.
        assertTrue(ConnectDeadline.decide(stopped = false, exitLocked = false, fallbacksSoFar = 0,
            tried = setOf(ConfigFreeMethod.PSIPHON.id), verifiedHere = null, runnable = all, manualPick = false)
            is ConnectDeadline.Decision.Fallback)
    }

    // 2. The power button with nothing selected started nothing.
    @Test fun thePowerButtonWithNoServerStartsSmartConnectOutsideTradeMode() {
        assertTrue(PowerButtonPolicy.configFreeWhenNoServer(OperationalMode.DAILY))
        assertTrue(PowerButtonPolicy.configFreeWhenNoServer(OperationalMode.GOD_MODE))
        assertFalse(PowerButtonPolicy.configFreeWhenNoServer(OperationalMode.TRADE))
        assertFalse(PowerButtonPolicy.configFreeWhenNoServer(OperationalMode.GHOST_RIDER))
        assertFalse(PowerButtonPolicy.configFreeWhenNoServer(OperationalMode.UNLOCK))
    }

    // 4. One source of truth: Shir o Khorshid said "ready within 120s" and was cut at 60 s; Aether's 220 s ladder at 60 s.
    @Test fun aManualPickWaitsOutTheWindowItsEngineAnnounced() {
        val since = 1_000_000L
        // Shir o Khorshid: 60 s base, 120 s readiness announced at start → 140 s.
        assertEquals(140_000L, ConnectDeadline.effectiveBudget(60_000L, since, since + 120_000L, manual = true))
        // Aether's Irancell ladder: 220 s announced 1 s in → 241 s.
        assertEquals(241_000L, ConnectDeadline.effectiveBudget(60_000L, since, since + 1_000L + 220_000L, manual = true))
        // Never shorter than the base, never stretched for Smart Connect, unchanged without an announcement.
        assertEquals(180_000L, ConnectDeadline.effectiveBudget(180_000L, since, since + 120_000L, manual = true))
        assertEquals(60_000L, ConnectDeadline.effectiveBudget(60_000L, since, since + 120_000L, manual = false))
        assertEquals(60_000L, ConnectDeadline.effectiveBudget(60_000L, since, null, manual = true))
    }

    @Test fun theStageTextShowsTheLimitThatApplies() {
        assertEquals(120_000L, ConnectDeadline.shownReadyMs(120_000L, manual = true, smartBudgetMs = 60_000L))
        assertEquals(60_000L, ConnectDeadline.shownReadyMs(120_000L, manual = false, smartBudgetMs = 60_000L))
        assertEquals(15_000L, ConnectDeadline.shownReadyMs(15_000L, manual = false, smartBudgetMs = 45_000L))
    }

    @Test fun aManualAetherPickGetsItsLadderWindowAndPsiphonItsRotation() {
        assertEquals(ConnectDeadline.AETHER_MANUAL_MS,
            ConnectDeadline.budgetFor(listOf(AetherSidecar.ID), manual = true, directPsiphon = false))
        assertEquals(ConnectDeadline.ENGINE_MS,
            ConnectDeadline.budgetFor(listOf(AetherSidecar.ID), manual = false, directPsiphon = false))
        assertEquals(ConnectDeadline.PSIPHON_MANUAL_MS,
            ConnectDeadline.budgetFor(listOf(com.example.vpn.sidecar.PsiphonSidecar.ID), manual = true, directPsiphon = true))
    }

    @Test fun anEngineAnnouncementOnlyCountsForItsOwnAttempt() {
        EngineBudgetHint.clear()
        EngineBudgetHint.set("a1", 5_000L)
        EngineBudgetHint.set("a1", 3_000L)
        assertEquals(5_000L, EngineBudgetHint.readyBy("a1"))
        assertNull(EngineBudgetHint.readyBy("a2"))
        EngineBudgetHint.set("a2", 1_000L)
        assertNull(EngineBudgetHint.readyBy("a1"))
        assertEquals(1_000L, EngineBudgetHint.readyBy("a2"))
        EngineBudgetHint.clear()
    }

    // 5. "Time from connect to verified traffic: 80942 ms" after a fallback the user waited ~172 s for.
    @Test fun timeToConnectRunsFromTheTapAcrossFallbacks() {
        val tap = 10_000L
        val attempt = tap + 91_000L
        val verified = attempt + 81_000L
        assertEquals(172_000L, ConnectTiming.elapsed(tap, attempt, verified))
        assertEquals(81_000L, ConnectTiming.elapsed(0L, attempt, verified))
        assertTrue(ConnectTiming.describe(172_000L, 81_000L).contains("this attempt: 81000 ms"))
        assertEquals("Time from connect to verified traffic: 14027 ms.", ConnectTiming.describe(14_027L, 14_027L))
    }

    // 6. Engine picks were logged as "Power button: only the config selected in Nodes".
    @Test fun aManualEnginePickIsNotCalledThePowerButton() {
        fun input(enginePick: Boolean) = SmartConnectPlan.Input(godMode = false, lockExit = true, exact = true,
            enginePick = enginePick, force = false, mayMove = false, reading = null)
        assertTrue(SmartConnectPlan.skipReason(input(true)).startsWith("Your pick"))
        assertTrue(SmartConnectPlan.skipReason(input(false)).startsWith("Power button"))
        assertTrue(BuiltinConnections.isBuiltin(ConfigFreeMethod.PSIPHON.id))
        assertTrue(BuiltinConnections.isBuiltin(BuiltinConnections.AETHER))
        assertFalse(BuiltinConnections.isBuiltin("a-saved-server"))
        assertFalse(BuiltinConnections.isBuiltin(null))
    }

    // 7. "chain hop failed · Engine process exited before local readiness (exit 141 …)" when the deadline stopped it.
    @Test fun anEngineTheAppStoppedIsNotReportedAsACrash() {
        assertEquals(SidecarProcess.STOPPED_BEFORE_READY, SidecarProcess.exitedBeforeReady(141, null, stopping = true))
        assertEquals(SidecarProcess.STOPPED_BEFORE_READY, SidecarProcess.exitedBeforeReady(143, null, stopping = true))
        assertTrue(SidecarProcess.stoppedByApp(SidecarProcess.STOPPED_BEFORE_READY))
        val real = SidecarProcess.exitedBeforeReady(141, null, stopping = false)
        assertTrue(real, real.contains("exit 141 SIGPIPE"))
        assertFalse(SidecarProcess.stoppedByApp(real))
        assertFalse(SidecarProcess.exitedBeforeReady(2, "configuration rejected", stopping = true) == SidecarProcess.STOPPED_BEFORE_READY)
    }

    // 8. "Android VPN unconfirmed" after the log had "device traffic verified".
    @Test fun deviceTrafficSeenConfirmsTheAndroidVpn() {
        val base = ConnectionState(status = ConnectionStatus.CONNECTED, attemptId = "a", lastVerifiedPath = ProbePath.ENGINE_PROXY)
        assertEquals(RouteVerification.ENGINE_PROXY_VERIFIED, base.verification)
        assertEquals(RouteVerification.DEVICE_TRAFFIC_VERIFIED, base.copy(deviceTrafficSeen = true).verification)
        assertEquals("VPN CONNECTED", base.copy(deviceTrafficSeen = true).statusLabel)
    }

    @Test fun aPartialFilteredSiteProbeBelongsToItsAttempt() {
        val s = ConnectionState(status = ConnectionStatus.CONNECTED, attemptId = "a", filteredPartialAttempt = "a")
        assertTrue(s.filteredSitesPartial)
        assertFalse(s.copy(attemptId = "b").filteredSitesPartial)
    }

    // 10. "Psiphon · Spain" for Psiphon over Lantern; "Tor · Unknown country" for loc=T1.
    @Test fun torExitsAndChainsAreNamedForWhatTheyAre() {
        assertEquals("Tor · Tor network", ConnectionRoute.withExitCountry("Tor", "T1"))
        assertEquals("Tor network", ActiveCardModel.countryName("T1"))
        assertEquals("Germany", ActiveCardModel.countryName("DE"))
        assertEquals("x · Unknown country", ConnectionRoute.withExitCountry("x", "ZZ9"))
        assertEquals("Psiphon over Lantern / Radiance", ConnectionRoute.chainLabel(listOf("Psiphon", "Lantern / Radiance")))
        val tor = ActiveCardModel.of(ConnectionState(status = ConnectionStatus.CONNECTED, attemptId = "a",
            activeProfile = ConfigFreeMethod.TOR_SNOWFLAKE.profile(), exitCountryCode = "T1"), null)
        assertEquals("Tor network", tor?.title)
    }

    // 11. "other apps stay blocked" after a failed Connect.
    @Test fun aFailedConnectOfTheUsersReleasesTheBlockOnlyWithoutAKillSwitchInDailyMode() {
        assertTrue(FailClosedPolicy.mayReleaseAfterFailedConnect(startedByUser = true, mode = OperationalMode.DAILY, killSwitchEnabled = false))
        assertFalse(FailClosedPolicy.mayReleaseAfterFailedConnect(startedByUser = true, mode = OperationalMode.DAILY, killSwitchEnabled = true))
        assertFalse(FailClosedPolicy.mayReleaseAfterFailedConnect(startedByUser = true, mode = OperationalMode.GOD_MODE, killSwitchEnabled = false))
        assertFalse(FailClosedPolicy.mayReleaseAfterFailedConnect(startedByUser = true, mode = OperationalMode.TRADE, killSwitchEnabled = false))
        assertFalse(FailClosedPolicy.mayReleaseAfterFailedConnect(startedByUser = false, mode = OperationalMode.DAILY, killSwitchEnabled = false))
    }
}
