package com.example.b178

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.OperationalMode
import com.example.ui.browser.GodBrowserBack
import com.example.ui.home.ConfigFreeMethodText
import com.example.ui.home.HomeFailure
import com.example.ui.language.UiText
import com.example.vpn.ConnectDeadline
import com.example.vpn.SmartSearchLog
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.diagnostics.EngineStages
import com.example.vpn.diagnostics.ExitTrace
import com.example.vpn.diagnostics.LiveTunnelProbe
import com.example.vpn.godmode.GodModeLogOnce
import com.example.vpn.safety.FailClosedPolicy
import com.example.vpn.safety.FailClosedPolicy.AfterFailure
import com.example.vpn.sidecar.PsiphonRegionPlan
import com.example.vpn.sidecar.TorSidecar
import com.example.vpn.sidecar.TorStartDeadline
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build 177 device test (V1.0.4, Z Flip3, Irancell LTE, 2026-11-26): the pure rules behind the fixes for B1–B3, B5–B9,
 * B11, B16, B17, B19–B21. Each test names its bug.
 */
class Build177FixesTest {
    @Before fun setUp() { EngineStages.resetForTest(); SmartSearchLog.resetForTest() }
    @After fun tearDown() { EngineStages.resetForTest(); SmartSearchLog.resetForTest() }

    // ---- B3: exit trace ----------------------------------------------------------------------------------------------

    @Test fun b3_exitIsReadUntilKnownThenRightAfterConnectThenEveryTenthCheck() {
        assertTrue(ExitTrace.due(null, 0, 0))
        assertTrue(ExitTrace.due(null, 2, 0))
        assertFalse(ExitTrace.due(null, ExitTrace.FIRST_TRIES, 0))
        assertTrue(ExitTrace.due("163.123.193.23", 0, 2))
        assertTrue(ExitTrace.due("163.123.193.23", 0, 5))
        assertFalse(ExitTrace.due("163.123.193.23", 0, 3))
        assertTrue(ExitTrace.due("163.123.193.23", 0, 9))
        assertTrue(ExitTrace.due("163.123.193.23", 0, 19))
        assertFalse(ExitTrace.due("163.123.193.23", 0, 10))
    }

    @Test fun b3_countryAndAddressAlwaysComeFromTheSameTrace() {
        // Build 177: "Netherlands" stayed on the card while every trace said DE.
        assertEquals("DE" to "163.123.193.23",
            ExitTrace.next("NL", "163.123.193.9", LiveTunnelProbe.Exit("DE", "163.123.193.23")))
        assertEquals("NL" to "1.1.1.1", ExitTrace.next("NL", "1.1.1.1", null))
        assertEquals("NL" to "1.1.1.1", ExitTrace.next("NL", "1.1.1.1", LiveTunnelProbe.Exit(null, null)))
        // A read without a country never leaves an older read's country beside a new address.
        assertEquals(null to "2.2.2.2", ExitTrace.next("NL", "1.1.1.1", LiveTunnelProbe.Exit(null, "2.2.2.2")))
        val line = ExitTrace.changed("NL", "1.1.1.1", LiveTunnelProbe.Exit("DE", "2.2.2.2"))
        assertNotNull(line); assertTrue(line!!.contains("NL → DE"))
        assertNull(ExitTrace.changed("DE", "1.1.1.1", LiveTunnelProbe.Exit("DE", "2.2.2.2")))
        assertNull(ExitTrace.changed(null, null, LiveTunnelProbe.Exit("DE", "2.2.2.2")))
    }

    // ---- B7 / B2 / B20: Home failure headline ----------------------------------------------------------------------

    private fun failed(message: String?) = ConnectionState(status = ConnectionStatus.FAILED, errorMessage = message)

    @Test fun b7_homeShowsTheFirstSentenceOfTheReasonOnlyForAFailure() {
        val msg = "Psiphon brought no tunnel up in 4 start(s) (regions: NL, any, DE, FR) within 94s. Pick another connection."
        assertEquals("Psiphon brought no tunnel up in 4 start(s) (regions: NL, any, DE, FR) within 94s.", HomeFailure.reason(failed(msg)))
        assertNull(HomeFailure.reason(ConnectionState(status = ConnectionStatus.DISCONNECTED, errorMessage = msg)))
        assertNull(HomeFailure.reason(failed(null)))
        assertNull(HomeFailure.reason(failed("   ")))
        val long = "x".repeat(400)
        val shown = HomeFailure.short(long)!!
        assertEquals(HomeFailure.MAX_REASON, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test fun b20_homeAlwaysSaysWhetherTrafficIsBlocked() {
        val released = "The traffic block is released: apps use the internet directly, without the VPN, until you connect again."
        val killSwitch = "Kill Switch is on, so traffic stays blocked: apps have no internet until you connect again. Turning the Kill Switch off releases the block."
        val notices = listOf(released, killSwitch)
        val torFail = "Tor / Snowflake carried no traffic in 180 s: you picked this connection yourself. "
        assertEquals("Tor / Snowflake carried no traffic in 180 s: you picked this connection yourself.\n$released",
            HomeFailure.reason(failed(torFail + released), notices))
        // The Kill Switch sentence has two sentences of its own; it is kept whole.
        assertEquals("Tor / Snowflake carried no traffic in 180 s: you picked this connection yourself.\n$killSwitch",
            HomeFailure.reason(failed(torFail + killSwitch), notices))
        assertEquals(released, HomeFailure.reason(failed(released), notices))
    }

    @Test fun b2_b20_theFailureMessageCarriesExactlyOneBlockSentence() {
        val kept = "Traffic stays blocked: apps have no internet until you connect again."
        val released = "The traffic block is released: apps use the internet directly."
        val known = listOf(kept, released)
        assertEquals("Engine failed. $released", FailClosedPolicy.withNotice("Engine failed", released, known))
        assertEquals("Engine failed. $released", FailClosedPolicy.withNotice("Engine failed.", released, known))
        // A later release replaces the kept sentence instead of adding a second, contradicting one.
        assertEquals("Engine failed. $released", FailClosedPolicy.withNotice("Engine failed. $kept", released, known))
        assertEquals("Engine failed. $kept", FailClosedPolicy.withNotice("Engine failed. $kept", kept, known))
        assertEquals("Engine failed.", FailClosedPolicy.withNotice("Engine failed. $kept", "", known))
        assertEquals(released, FailClosedPolicy.withNotice(null, released, known))
    }

    // ---- B2 / B20 / B21: what a failed Connect leaves behind --------------------------------------------------------

    @Test fun b2_keepsBlockAfterFailedConnectOnlyWhereTheBlockIsNotReleased() {
        assertFalse(FailClosedPolicy.keepsBlockAfterFailedConnect(true, startedByUser = true, mode = OperationalMode.DAILY, killSwitchEnabled = false))
        assertTrue(FailClosedPolicy.keepsBlockAfterFailedConnect(true, startedByUser = true, mode = OperationalMode.DAILY, killSwitchEnabled = true))
        assertTrue(FailClosedPolicy.keepsBlockAfterFailedConnect(true, startedByUser = true, mode = OperationalMode.GOD_MODE, killSwitchEnabled = false))
        assertTrue(FailClosedPolicy.keepsBlockAfterFailedConnect(true, startedByUser = true, mode = OperationalMode.TRADE, killSwitchEnabled = false))
        assertTrue(FailClosedPolicy.keepsBlockAfterFailedConnect(true, startedByUser = false, mode = OperationalMode.DAILY, killSwitchEnabled = false))
        assertFalse(FailClosedPolicy.keepsBlockAfterFailedConnect(false, startedByUser = false, mode = OperationalMode.GOD_MODE, killSwitchEnabled = true))
    }

    @Test fun b20_theFailureTextNamesTheKillSwitchOnlyWhenItIsWhatKeepsTheBlock() {
        assertEquals(AfterFailure.RELEASED, FailClosedPolicy.afterFailedConnect(true, true, OperationalMode.DAILY, false))
        assertEquals(AfterFailure.KILL_SWITCH, FailClosedPolicy.afterFailedConnect(true, true, OperationalMode.DAILY, true))
        assertEquals(AfterFailure.MODE_OR_AUTOMATIC, FailClosedPolicy.afterFailedConnect(true, true, OperationalMode.GOD_MODE, true))
        assertEquals(AfterFailure.MODE_OR_AUTOMATIC, FailClosedPolicy.afterFailedConnect(true, true, OperationalMode.GHOST_RIDER, false))
        assertEquals(AfterFailure.MODE_OR_AUTOMATIC, FailClosedPolicy.afterFailedConnect(true, false, OperationalMode.DAILY, true))
        assertEquals(AfterFailure.NO_BLOCK, FailClosedPolicy.afterFailedConnect(false, true, OperationalMode.DAILY, true))
    }

    @Test fun b21_turningTheKillSwitchOffReleasesAFailedConnectsBlockInDailyModeOnly() {
        assertTrue(FailClosedPolicy.releaseOnKillSwitchOff(true, true, OperationalMode.DAILY, killSwitchWas = true, killSwitchNow = false))
        // GOD / Trade / Ghost Rider and automatic (Always-on) sessions keep the block.
        for (mode in listOf(OperationalMode.GOD_MODE, OperationalMode.TRADE, OperationalMode.GHOST_RIDER)) {
            assertFalse(mode.name, FailClosedPolicy.releaseOnKillSwitchOff(true, true, mode, killSwitchWas = true, killSwitchNow = false))
        }
        assertFalse(FailClosedPolicy.releaseOnKillSwitchOff(true, false, OperationalMode.DAILY, killSwitchWas = true, killSwitchNow = false))
        // Nothing held, or no on → off change: nothing to release.
        assertFalse(FailClosedPolicy.releaseOnKillSwitchOff(false, true, OperationalMode.DAILY, killSwitchWas = true, killSwitchNow = false))
        assertFalse(FailClosedPolicy.releaseOnKillSwitchOff(true, true, OperationalMode.DAILY, killSwitchWas = false, killSwitchNow = false))
        assertFalse(FailClosedPolicy.releaseOnKillSwitchOff(true, true, OperationalMode.DAILY, killSwitchWas = true, killSwitchNow = true))
    }

    // ---- B6: network assessment is Smart Connect's ------------------------------------------------------------------

    @Test fun b6_onlySmartConnectReportsTheAssessmentAndOnlyWhenItChanged() {
        assertTrue(SmartSearchLog.reports(exact = false))
        assertFalse(SmartSearchLog.reports(exact = true))
        assertTrue(SmartSearchLog.changed("cell:43235", "open (metered)"))
        assertFalse(SmartSearchLog.changed("cell:43235", "open (metered)"))
        assertTrue(SmartSearchLog.changed("cell:43235", "heavy"))
        assertTrue(SmartSearchLog.changed("wifi:home", "open (metered)"))
        val line = SmartSearchLog.manualLine("Tor / Snowflake")
        assertTrue(line.contains("only Tor / Snowflake runs"))
    }

    // ---- B1 / B8 / B10: stage texts ---------------------------------------------------------------------------------

    @Test fun b1_aManualTorPickSaysTheDeadlineThatReallyApplies() {
        val text = ConnectDeadline.startingText(TorSidecar.ID, readyMs = 120_000, manual = true, smartBudgetMs = 60_000, manualBudgetMs = 180_000)
        assertTrue(text, text.startsWith("ready within 180s"))
        assertTrue(text, text.contains("at most ${(TorStartDeadline.CAP_MS + ConnectDeadline.VERIFY_MARGIN_MS) / 1000}s"))
        assertEquals(TorStartDeadline.CAP_MS + ConnectDeadline.VERIFY_MARGIN_MS, ConnectDeadline.TOR_MANUAL_CAP_MS)
    }

    @Test fun b1_otherEnginesKeepTheirOwnOrSmartConnectsLimit() {
        assertEquals("ready within 95s", ConnectDeadline.startingText("psiphon", 95_000, manual = true, smartBudgetMs = 60_000, manualBudgetMs = 95_000))
        assertEquals("ready within 60s (Smart Connect's limit; the engine allows 95s)",
            ConnectDeadline.startingText("psiphon", 95_000, manual = false, smartBudgetMs = 60_000, manualBudgetMs = 95_000))
        // Smart Connect's Tor keeps Smart Connect's limit (the bootstrap extension is the manual pick's).
        assertEquals("ready within 60s (Smart Connect's limit; the engine allows 120s)",
            ConnectDeadline.startingText(TorSidecar.ID, 120_000, manual = false, smartBudgetMs = 60_000, manualBudgetMs = 180_000))
    }

    @Test fun b8_b10_carrierTestsAreAStageOfTheirOwn() {
        assertEquals("testing carriers (gateway scan) · ready within 93s", ConnectDeadline.carrierTestText("aether", 93_000))
        assertEquals("testing carriers · ready within 45s", ConnectDeadline.carrierTestText("serverless", 45_000))
        assertEquals("carrier chosen by its test after 33s", ConnectDeadline.carrierChosenText(33_600))
    }

    @Test fun b1_torDeadlineMovesOnlyWhileBootstrapAdvances() {
        val d = TorStartDeadline(startedAt = 0, baseMs = 120_000)
        assertTrue(d.saw("Tor 14%: Handshaking with a relay", 60_000))
        assertEquals(14, d.percent)
        assertFalse(d.saw("Tor 14%: Handshaking with a relay", 70_000))
        assertTrue(d.saw("Tor 30%: Loading networkstatus consensus", 150_000))
        assertEquals(minOf(150_000 + TorStartDeadline.STALL_MS, TorStartDeadline.CAP_MS), d.readyBy)
    }

    // ---- B11: Psiphon region window ---------------------------------------------------------------------------------

    @Test fun b11_anyRegionSaysWhenItMovesOn() {
        val any = PsiphonRegionPlan.Step("", 70_000, "no region fixed")
        assertEquals("up to 70s; next region after 40s if no server is dialed", PsiphonRegionPlan.stepWindowText(any, ""))
        // The user's own region is not cut early.
        assertEquals("up to 70s", PsiphonRegionPlan.stepWindowText(any, "DE"))
        assertEquals("up to 25s", PsiphonRegionPlan.stepWindowText(PsiphonRegionPlan.Step("NL", 25_000, "remembered"), ""))
        assertEquals("up to 30s", PsiphonRegionPlan.stepWindowText(PsiphonRegionPlan.Step("", 30_000, "last"), ""))
    }

    // ---- B9: stale engine tag on "preparing" ------------------------------------------------------------------------

    @Test fun b9_aNewConnectNamesOnlyTheEngineItStarts() {
        val shir = ConfigFreeMethod.SHIROKHORSHID.profile()
        val aether = ConfigFreeMethod.AETHER_WARP.profile()
        val failed = ConnectionState(status = ConnectionStatus.FAILED, activeProfile = shir, errorMessage = "timed out")
        EngineStages.onStateChange(failed, ConnectionState(status = ConnectionStatus.PREPARING, activeProfile = aether), now = 1)
        val entry = EngineStages.entries.value.last()
        assertEquals("preparing", entry.stage)
        assertTrue(entry.engine, entry.engine.startsWith("aether"))
        EngineStages.onStateChange(failed, ConnectionState(status = ConnectionStatus.PREPARING, activeProfile = null), now = 2)
        assertEquals("none", EngineStages.entries.value.last().engine)
        // Later stages still fall back to the previous profile while the new state has none yet.
        val prep = ConnectionState(status = ConnectionStatus.PREPARING, activeProfile = aether)
        EngineStages.onStateChange(prep, ConnectionState(status = ConnectionStatus.CONNECTING), now = 3)
        assertTrue(EngineStages.entries.value.last().engine.startsWith("aether"))
    }

    // ---- B17: Hardened Browser back ---------------------------------------------------------------------------------

    @Test fun b17_backGoesThroughPageHistoryThenLeavesTheScreen() {
        var history = 2
        var left = 0
        val back = GodBrowserBack(canGoBack = { history > 0 }, goBack = { history-- }, leave = { left++ })
        back.press(); back.press()
        assertEquals(0, history); assertEquals(0, left)
        back.press()
        assertEquals(1, left)
        // A focused WebView's Back key is consumed on both halves and acts once, on key up.
        assertTrue(back.onKey(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.ACTION_DOWN))
        assertEquals(1, left)
        assertTrue(back.onKey(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.ACTION_UP))
        assertEquals(2, left)
        assertFalse(back.onKey(android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.ACTION_UP))
        assertEquals(2, left)
    }

    // ---- B16: GOD mode logs -----------------------------------------------------------------------------------------

    @Test fun b16_unavailableBridgesAreNotedOnceAndAReTapChangesNothing() {
        val flag = AtomicBoolean(false)
        assertTrue(GodModeLogOnce.first(flag))
        assertFalse(GodModeLogOnce.first(flag))
        assertFalse(GodModeLogOnce.changes(OperationalMode.GOD_MODE, OperationalMode.GOD_MODE))
        assertTrue(GodModeLogOnce.changes(OperationalMode.DAILY, OperationalMode.GOD_MODE))
    }

    // ---- B19 / B14: every label has its own resource ----------------------------------------------------------------

    @Test fun b19_everyPickerMethodModeAndStatusHasAResource() {
        val methods = ConfigFreeMethod.entries.map { ConfigFreeMethodText.description(it) }
        assertEquals(methods.size, methods.toSet().size)
        assertEquals(OperationalMode.entries.size, OperationalMode.entries.map { UiText.modeTitle(it) }.toSet().size)
        assertEquals(OperationalMode.entries.size, OperationalMode.entries.map { UiText.modeSubtitle(it) }.toSet().size)
        val labels = ConnectionStatus.entries.associateWith { UiText.status(ConnectionState(status = it)) }
        // Statuses that share an English label share a resource, and no two different labels share one.
        ConnectionStatus.entries.forEach { a -> ConnectionStatus.entries.forEach { b ->
            val sameLabel = ConnectionState(status = a).statusLabel == ConnectionState(status = b).statusLabel
            assertEquals("$a/$b", sameLabel, labels.getValue(a) == labels.getValue(b))
        } }
    }

    @Test fun b14_lanternIsDescribedAsBuiltIn() {
        assertFalse(ConfigFreeMethod.RADIANCE.description.contains("when the engine is installed"))
        assertTrue(ConfigFreeMethod.RADIANCE.description.contains("built into the app"))
    }
}
