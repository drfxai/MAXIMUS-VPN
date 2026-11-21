package com.example.vpn.diagnostics.report

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.diagnostics.events.TestRegistry
import com.example.vpn.verify.LegResult
import com.example.vpn.verify.ProbePath
import com.example.vpn.verify.TrafficEvidence
import com.example.vpn.verify.TunCheckPlan
import com.example.vpn.verify.TunForwardMeter
import com.example.vpn.verify.TunForwarded
import com.example.vpn.verify.TunPathStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report shows the engine path and the device (TUN) path separately; a self-excluded TUN is never "failed". */
class RoutePathsTest {
    private val env = ReportEnvironment("1.0.4", 166, "abc", "t", "14", 34, "Pixel", "Xray")
    private val enginePass = TrafficEvidence("a1", 0, ProbePath.ENGINE_PROXY,
        LegResult.Passed(65536, 900), LegResult.Passed(262144, 2000), checkedAt = 1)
    private val state = ConnectionState(status = ConnectionStatus.CONNECTED, sessionId = "s1", attemptId = "a1",
        activeEngineName = "xray", trafficEvidence = enginePass)
    private val notTestable = TestRegistry.Entry("t-1", "bulk.tun", "s1", "p:1", TestRegistry.State.NOT_TESTABLE, 1,
        startedAt = 1, endedAt = 2, result = TunCheckPlan.selfExcludedResult("aether"))

    private fun inputs(tests: List<TestRegistry.Entry>, tun: TunPathStatus.View?) = DiagnosticReportBuilder.Inputs(
        generatedAt = 1_760_000_000_000, env = env, connection = state, networkProfile = null, events = emptyList(),
        eventLogTruncated = false, tests = tests, runtime = JSONObject(), profileRef = "p:1", tunPath = tun)

    private fun window(down: Long, up: Long) = TunForwardMeter.Snapshot(at = 20_000, startedAt = 0, downBytes = down,
        upBytes = up, flows = 3, activeFlows = 1, dataFlows = 2, engineUnreachable = 0, resets = 0)

    @Test fun aSelfExcludedSessionSaysTheTunCheckIsNotApplicableInsteadOfFailed() {
        val tun = TunPathStatus.View(selfExcluded = true, cumulative = null, state = TunForwarded.State.UNAVAILABLE)
        val paths = RoutePaths.of(state, listOf(notTestable), tun)
        assertEquals(RoutePaths.State.VERIFIED, paths.engine.state)
        assertEquals(RoutePaths.State.NOT_TESTABLE, paths.device.state)
        assertTrue(paths.selfExcluded)

        val summary = DiagnosticReportBuilder.summary(inputs(listOf(notTestable), tun))
        assertTrue(summary, summary.contains("Engine path: verified"))
        assertTrue(summary, summary.contains("Device (TUN) path: not testable"))
        assertTrue(summary, summary.contains(TunCheckPlan.SELF_EXCLUDED_LINE))
        assertTrue(summary, summary.contains("No failures recorded."))
        assertFalse(summary, summary.contains("NOT VERIFIED"))

        val json = DiagnosticReportBuilder.json(inputs(listOf(notTestable), tun))
        assertEquals("NOT_TESTABLE", json.getJSONObject("paths").getJSONObject("device").getString("state"))
        assertEquals("VERIFIED", json.getJSONObject("paths").getJSONObject("engine").getString("state"))
        assertEquals(0, json.getJSONArray("errors").length())
    }

    @Test fun otherAppsTrafficThroughTheTunVerifiesTheDevicePath() {
        val w = window(down = 512 * 1024, up = 40 * 1024)
        val tun = TunPathStatus.View(selfExcluded = true, cumulative = w, state = TunForwarded.verdict(w))
        val paths = RoutePaths.of(state, listOf(notTestable), tun)
        assertEquals(RoutePaths.State.VERIFIED, paths.device.state)
        val json = DiagnosticReportBuilder.json(inputs(listOf(notTestable), tun))
        val forwarded = json.getJSONObject("tunForwarded")
        assertEquals("VERIFIED", forwarded.getString("state"))
        assertEquals(512L * 1024, forwarded.getLong("rxBytes"))
        assertEquals(3, forwarded.getInt("flows"))

        // Too little traffic yet: still not testable (waiting), never failed.
        val idle = window(down = 1024, up = 900)
        val waiting = RoutePaths.of(state, listOf(notTestable), TunPathStatus.View(true, idle, TunForwarded.verdict(idle)))
        assertEquals(RoutePaths.State.NOT_TESTABLE, waiting.device.state)
        assertTrue(waiting.device.detail.contains(TunForwarded.State.WAITING.word))
    }

    @Test fun anInProcessEngineKeepsTheBindBasedTunVerdict() {
        val tunPass = enginePass.copy(path = ProbePath.TUN)
        val inside = state.copy(trafficEvidence = tunPass)
        val paths = RoutePaths.of(inside, emptyList(), TunPathStatus.View(false, null, TunForwarded.State.UNAVAILABLE))
        assertFalse(paths.selfExcluded)
        assertEquals(RoutePaths.State.VERIFIED, paths.device.state)
        val tunFail = tunPass.copy(download = LegResult.Failed(com.example.vpn.diagnostics.FailureStage.TIMEOUT, 0, false, "x"))
        assertEquals(RoutePaths.State.FAILED, RoutePaths.of(inside.copy(trafficEvidence = tunFail), emptyList(), null).device.state)
    }
}
