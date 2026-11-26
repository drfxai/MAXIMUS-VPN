package com.example.vpn

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.diagnostics.events.ConnectionEvents
import com.example.vpn.diagnostics.report.DiagnosticReportBuilder
import com.example.vpn.diagnostics.report.ReportEnvironment
import com.example.vpn.diagnostics.report.RoutePaths
import com.example.vpn.engine.registry.EngineLedger
import com.example.vpn.engine.registry.EngineRegistry
import com.example.vpn.engine.registry.EngineState
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.unlock.UnlockConnectRetry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Build 178 field report (Irancell LTE, 2026-11-26 18:32-19:51 UTC): fixes 1-6. */
class Build178FieldReportTest {
    @After fun reset() = StopGuard.resetForTest()

    // 1 + 2: every Stop says who asked; a second tap right after Connect is part of that tap.

    @Test fun b1_secondTapWithinTwoSecondsOfConnectIsNotAStop() {
        val tap = 1_000_000L
        // The report's self-teardowns came 0.94 s, 1.0 s, 1.45 s and 1.67 s after the Connect tap.
        listOf(940L, 1_000L, 1_450L, 1_670L).forEach { after ->
            assertTrue(StopGuard.swallow(StopOrigin.POWER_BUTTON, tap, tap + after))
            assertTrue(StopGuard.swallow(StopOrigin.CONFIG_FREE_BUTTON, tap, tap + after))
        }
        // A deliberate Stop later, or one from the notification, the Smart Connect sheet or a panic, always goes through.
        assertFalse(StopGuard.swallow(StopOrigin.POWER_BUTTON, tap, tap + StopGuard.DOUBLE_TAP_MS))
        assertFalse(StopGuard.swallow(StopOrigin.NOTIFICATION, tap, tap + 500))
        assertFalse(StopGuard.swallow(StopOrigin.SMART_SHEET, tap, tap + 500))
        assertFalse(StopGuard.swallow(StopOrigin.GHOST_PANIC, tap, tap + 500))
        // No Connect tap seen: nothing is held back.
        assertFalse(StopGuard.swallow(StopOrigin.POWER_BUTTON, 0L, tap))
    }

    @Test fun b1_b2_stopReasonNamesTheOriginStateAndTime() {
        val reason = StopGuard.reason(StopOrigin.POWER_BUTTON, "VPN_INTERFACE_ESTABLISHED", 11_100)
        assertEquals("Stopped by the power button while vpn interface established (11 s after the Connect tap)", reason)
        assertTrue(StopGuard.reason(StopOrigin.UNKNOWN, "PREPARING", 940).contains("0.9 s after the Connect tap"))
        assertEquals(StopOrigin.NOTIFICATION, StopOrigin.of("notification"))
        assertEquals(StopOrigin.UNKNOWN, StopOrigin.of(null))
        assertEquals(StopOrigin.UNKNOWN, StopOrigin.of("something-else"))
        assertEquals(StopOrigin.entries.size, StopOrigin.entries.map { it.key }.toSet().size)
    }

    @Test fun b1_b2_disconnectingAndSessionEndCarryTheReason() {
        val running = ConnectionState(status = ConnectionStatus.PREPARING, sessionId = "s1", attemptId = "a1")
        val stopping = running.copy(status = ConnectionStatus.DISCONNECTING, stopReason = "Stopped by the tile while preparing")
        val e = ConnectionEvents.between(running, stopping, 1L) { it }
        assertEquals("Stopped by the tile while preparing", e.single { it.name == "connection.state" }.attributes["reason"])
        val ended = ConnectionEvents.between(stopping, ConnectionState(status = ConnectionStatus.DISCONNECTED,
            stopReason = stopping.stopReason), 2L) { it }
        assertEquals(stopping.stopReason, ended.single { it.name == "session.end" }.attributes["reason"])
    }

    // 3: Psiphon's dial notices are always on, and the failure says what was dialed.

    @Test fun b3_diagnosticNoticesAreOnEvenWhenTheBuildSettingsTurnThemOff() {
        val dir = Files.createTempDirectory("psiphon-b178").toFile()
        try {
            val settings = JSONObject().put("PropagationChannelId", "A").put("SponsorId", "B")
                .put("EmitDiagnosticNotices", false)
            assertTrue(PsiphonSidecar.buildConfig(settings, "", 40123, dir).getBoolean("EmitDiagnosticNotices"))
            assertTrue(PsiphonSidecar.buildConfig(settings, "DE", 40123, dir, upstreamSocks = 40124)
                .getBoolean("EmitDiagnosticNotices"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun b3_failureSaysHowManyServersWereDialedOverWhichProtocols() {
        val progress = PsiphonSidecar.StartupProgress()
        progress.observe("""{"noticeType":"AvailableEgressRegions","data":{"regions":["DE","NL","US"]}}""")
        val listed = progress.failureAfter(25_000)
        assertTrue(listed, listed.contains("(3 regions) but no server was dialed yet"))
        repeat(3) { progress.observe("""{"noticeType":"ConnectingServer","data":{"protocol":"OSSH"}}""") }
        progress.observe("""{"noticeType":"ConnectingServer","data":{"protocol":"TLS-OSSH"}}""")
        val dialed = progress.failureAfter(25_000)
        assertTrue(dialed, dialed.contains("server dialing did not establish a tunnel: dialed 4 server(s)"))
        assertTrue(dialed, dialed.contains("OSSH×3") && dialed.contains("TLS-OSSH×1"))
        assertTrue(dialed, dialed.contains("none answered on this network"))
    }

    // 4: a manual Aether pick with cf=0 known gets one short check; it still runs only Aether.

    @Test fun b4_manualAetherWithCloudflareFilteredGetsOneShortCheck() {
        assertEquals(ConnectionEngineRunner.CF_FILTERED_MANUAL_BUDGET_MS,
            ConnectionEngineRunner.cfFilteredManualBudgetMs(AetherSidecar.ID, manual = true, cloudflareBlocked = true, relayOnly = false))
        assertNull(ConnectionEngineRunner.cfFilteredManualBudgetMs(AetherSidecar.ID, manual = true, cloudflareBlocked = false, relayOnly = false))
        assertNull(ConnectionEngineRunner.cfFilteredManualBudgetMs(AetherSidecar.ID, manual = false, cloudflareBlocked = true, relayOnly = false))
        assertNull(ConnectionEngineRunner.cfFilteredManualBudgetMs(AetherSidecar.ID, manual = true, cloudflareBlocked = true, relayOnly = true))
        assertNull(ConnectionEngineRunner.cfFilteredManualBudgetMs("psiphon", manual = true, cloudflareBlocked = true, relayOnly = false))
        // The tighter budget wins; neither alone is kept as it was.
        assertEquals(60_000L, ConnectionEngineRunner.effectiveLadderBudgetMs(120_000L, 60_000L))
        assertEquals(60_000L, ConnectionEngineRunner.effectiveLadderBudgetMs(null, 60_000L))
        assertEquals(120_000L, ConnectionEngineRunner.effectiveLadderBudgetMs(120_000L, null))
        assertNull(ConnectionEngineRunner.effectiveLadderBudgetMs(null, null))
        assertTrue(ConnectionEngineRunner.cfFilteredManualNotice(60_000).contains("up to 60s"))
        // A manual pick never leaves its engine.
        assertFalse(ManualEnginePick.mayLeaveEngine(true))
    }

    // 5: Unlock checks its servers again before it fails.

    @Test fun b5_unlockConnectChecksThreeTimesBeforeFailing() {
        assertEquals(0L, UnlockConnectRetry.delayBeforeMs(1))
        assertEquals(2_000L, UnlockConnectRetry.delayBeforeMs(2))
        assertEquals(4_000L, UnlockConnectRetry.delayBeforeMs(3))
        assertTrue(UnlockConnectRetry.again(1))
        assertTrue(UnlockConnectRetry.again(2))
        assertFalse(UnlockConnectRetry.again(UnlockConnectRetry.ATTEMPTS))
        // The report's successful retry came 16 s after the failure: the internal retries fit well inside that.
        assertTrue(UnlockConnectRetry.totalDelayMs <= 6_000L)
    }

    // 6: the engine list says what was tried on this network, also after the app was closed.

    @Test fun b6_engineHistorySurvivesARestartAndIsShownButNeverCountsAsLive() {
        var now = 10_000_000L
        var saved: String? = null
        val store = EngineLedger.Store(load = { saved }, save = { saved = it })
        val first = EngineLedger { now }.apply { this.store = store }
        val aether = EngineRegistry.ENGINES.single { it.id == AetherSidecar.ID }
        first.failed(aether.id, "cell:abc123", "TLS_HANDSHAKE_FAILED: gateway scan found no endpoint")
        now += 1_000
        // The same failure reported again by the connection's FAILED state is one attempt, not two.
        first.failed(aether.id, "cell:abc123", "TLS_HANDSHAKE_FAILED: gateway scan found no endpoint")
        now += 60_000
        first.failed(aether.id, "cell:abc123", "TIMEOUT: no verified traffic")

        // The app is closed and opened again 20 minutes later.
        now += 20 * 60_000
        val second = EngineLedger { now }.apply { this.store = store }
        val s = second.status(aether, "cell:abc123", null, null)
        assertEquals(EngineState.FAILED, s.state)
        assertTrue(s.detail, s.detail.contains("last attempt failed 20 min ago"))
        assertTrue(s.detail, s.detail.contains("2 attempt(s) on this network in 24 h: 2 failed"))
        // History is never live evidence: no retry skip, no verified state.
        assertNull(second.recentFailure(aether.id, "cell:abc123"))
        assertFalse(second.verifiedHere(aether.id, "cell:abc123"))
        // Another network learns nothing, and a day later it is gone.
        assertEquals("nothing tested on this network yet", second.status(aether, "wifi:def456", null, null).detail)
        now += EngineLedger.HISTORY_MS
        val third = EngineLedger { now }.apply { this.store = store }
        assertEquals(EngineState.AVAILABLE, third.status(aether, "cell:abc123", null, null).state)
    }

    @Test fun b6_reportNamesTheLastAttemptsEngineAndWhyPathsWereNotTested() {
        val failed = ConnectionState(status = ConnectionStatus.FAILED, sessionId = "s1", attemptId = "a1",
            failureStage = com.example.vpn.diagnostics.FailureStage.TLS_HANDSHAKE_FAILED)
        val inputs = DiagnosticReportBuilder.Inputs(generatedAt = 1L,
            env = ReportEnvironment("1.0.4", 179, "abc", "now", "15", 35, "phone", "Xray"),
            connection = failed, networkProfile = null, events = emptyList(), eventLogTruncated = false,
            tests = emptyList(), runtime = JSONObject(), profileRef = null, lastEngine = "aether/h2")
        assertEquals("aether/h2 (last attempt, failed)", inputs.engineLabel)
        assertEquals("none", inputs.copy(lastEngine = null).engineLabel)
        val paths = RoutePaths.of(failed, emptyList(), null)
        assertEquals(RoutePaths.State.NOT_TESTED, paths.engine.state)
        assertTrue(paths.engine.detail, paths.engine.detail.contains("failed before traffic could be checked (TLS_HANDSHAKE_FAILED)"))
        val stopped = RoutePaths.of(ConnectionState(status = ConnectionStatus.DISCONNECTED, stopReason = "Stopped by the tile"),
            emptyList(), null)
        assertTrue(stopped.engine.detail, stopped.engine.detail.contains("Stopped by the tile"))
    }
}
