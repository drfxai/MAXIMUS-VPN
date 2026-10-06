package com.example

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.diagnostics.events.DiagEvent
import com.example.vpn.diagnostics.events.Severity
import com.example.vpn.diagnostics.events.TestRegistry
import com.example.vpn.diagnostics.report.DiagnosticReportBuilder
import com.example.vpn.diagnostics.report.ReportEnvironment
import com.example.vpn.diagnostics.report.ReportRedaction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5: the short and the JSON report, their limits and the export-time redaction. */
class DiagnosticReportTest {
    private val env = ReportEnvironment("1.0.0", 26, "2bdf976abcde", "2026-10-06T12:00Z", "14", 34, "Google Pixel 8", "Xray v26.9.9")
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    private fun inputs(events: List<DiagEvent>, truncated: Boolean = false, tests: List<TestRegistry.Entry> = emptyList()) =
        DiagnosticReportBuilder.Inputs(
            generatedAt = 1_760_000_000_000,
            env = env,
            connection = ConnectionState(status = ConnectionStatus.CONNECTED, sessionId = "s1", attemptId = "a2",
                activeEngineName = "xray", verifiedAt = 1_760_000_000_000),
            networkProfile = "wifi:v4=1,v6=0,udp=?,tls=1,cf=1,dns=1",
            networkObservations = listOf("IPv4: yes", "QUIC: not measured"),
            events = events,
            eventLogTruncated = truncated,
            tests = tests,
            runtime = JSONObject().put("exits", JSONArray().put(JSONObject().put("reason", "ANR").put("timestamp", 1))),
            profileRef = "p:0123456789"
        )

    private fun fail(i: Int) = DiagEvent(timestamp = 1_760_000_000_000L + i, severity = Severity.WARN, name = "proxy.request",
        sessionId = "s1", profileRef = "p:1", result = "FAIL", failureStage = FailureStage.TIMEOUT)

    @Test fun theJsonReportCarriesEverySection() {
        val pass = DiagEvent(timestamp = 1_760_000_000_500, severity = Severity.INFO, name = "test.pass", attemptId = "a2", result = "PASS",
            durationMs = 120, attributes = mapOf("test.type" to "tunnel.verify", "destination" to "www.gstatic.com"))
        val test = TestRegistry.Entry("t-1", "free.real_delay", null, "p:1", TestRegistry.State.CANCELLED, 1, cancelReason = "stopped")
        val json = DiagnosticReportBuilder.json(inputs((1..18).map(::fail) + pass, tests = listOf(test)))
        assertEquals(DiagnosticReportBuilder.SCHEMA_VERSION, json.getInt("schemaVersion"))
        assertFalse(json.getBoolean("truncated"))
        assertEquals("1.0.0", json.getJSONObject("app").getString("version"))
        assertEquals(26, json.getJSONObject("app").getInt("versionCode"))
        assertEquals("2bdf976abcde", json.getJSONObject("app").getString("buildCommit"))
        assertEquals("14", json.getJSONObject("device").getString("android"))
        assertEquals("Xray v26.9.9", json.getJSONObject("engine").getString("version"))
        assertEquals("a2", json.getJSONObject("session").getString("attemptId"))
        assertEquals("CONNECTED", json.getJSONObject("session").getString("status"))
        assertEquals("wifi:v4=1,v6=0,udp=?,tls=1,cf=1,dns=1", json.getJSONObject("network").getString("profile"))
        assertEquals("18 × proxy.request (TIMEOUT)", json.getJSONArray("errors").getJSONObject(0).getString("summary"))
        assertEquals("CANCELLED", json.getJSONArray("tests").getJSONObject(0).getString("state"))
        assertEquals("ANR", json.getJSONObject("runtime").getJSONArray("exits").getJSONObject(0).getString("reason"))
        assertEquals(19, json.getJSONArray("events").length())
    }

    @Test fun theSummaryIsShortAndNamesTheMainFailureAndTheLastPass() {
        val pass = DiagEvent(timestamp = 1_760_000_000_500, severity = Severity.INFO, name = "test.pass", attemptId = "a2", result = "PASS",
            durationMs = 120, attributes = mapOf("test.type" to "tunnel.verify", "destination" to "www.gstatic.com"))
        val s = DiagnosticReportBuilder.summary(inputs((1..18).map(::fail) + pass))
        assertTrue(s.lines().size <= 25)
        assertTrue(s.contains("18 × proxy.request (TIMEOUT)"))
        assertTrue(s.contains("tunnel.verify to www.gstatic.com in 120 ms (attempt a2)"))
        assertTrue(s.contains("1 × ANR"))
        assertTrue(s.contains("VPN CONNECTED"))
    }

    @Test fun limitsDropOldEventsAndSaySo() {
        val many = (1..DiagnosticReportBuilder.MAX_EVENTS + 50).map(::fail)
        val json = DiagnosticReportBuilder.json(inputs(many))
        assertTrue(json.getBoolean("truncated"))
        assertEquals(DiagnosticReportBuilder.MAX_EVENTS, json.getJSONArray("events").length())
        assertTrue(DiagnosticReportBuilder.json(inputs(listOf(fail(1)), truncated = true)).getBoolean("truncated"))
        assertTrue(DiagnosticReportBuilder.summary(inputs(many)).contains("truncated"))
    }

    @Test fun theSecondPassRemovesWhatTheFirstMightMiss() {
        val text = "bot 1234567890:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsawABCD key AIzaSyA1234567890abcdefghijklmnopqrstu " +
            "user a.person@example.com phone +989121234567 server 203.0.113.77 v6 2001:db8:85a3:0:0:8a2e:370:7334 " +
            "sub https://host.example/sub/$uuid token ${"Q".repeat(48)} at 12:30:45 via 1.1.1.1 Xray v26.9.9"
        val out = ReportRedaction.secondPass(text)
        for (secret in listOf("AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw", "AIzaSyA1234567890", "a.person@example.com", "+989121234567",
                "203.0.113.77", "8a2e:370:7334", uuid, "Q".repeat(48))) {
            assertFalse(secret, out.contains(secret))
        }
        assertTrue(out.contains("203.0.x.x"))
        assertTrue(out.contains("12:30:45"))
        assertTrue(out.contains("1.1.1.1"))
        assertTrue(out.contains("v26.9.9"))
    }

    @Test fun theJsonReportIsRedactedValueByValueAndStaysValid() {
        val leaky = DiagEvent(timestamp = 1, severity = Severity.ERROR, name = "log.error.vpn", result = "FAIL",
            attributes = mapOf("message" to "auth:\"secretvalue\" to 198.51.100.4 vless://$uuid@198.51.100.4:443"))
        val json = DiagnosticReportBuilder.json(inputs(listOf(leaky)))
        val text = json.toString()
        assertFalse(text.contains(uuid))
        assertFalse(text.contains("198.51.100.4"))
        assertFalse(text.contains("secretvalue"))
        JSONObject(text) // still valid JSON
    }
}
