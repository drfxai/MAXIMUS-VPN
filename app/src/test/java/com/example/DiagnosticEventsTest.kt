package com.example

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.diagnostics.events.ConnectionEvents
import com.example.vpn.diagnostics.events.DiagEvent
import com.example.vpn.diagnostics.events.ErrorAggregator
import com.example.vpn.diagnostics.events.EventLog
import com.example.vpn.diagnostics.events.Severity
import com.example.vpn.diagnostics.events.TestRegistry
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** Phase 4: structured events, correlation ids, verifiable PASS, error groups and background tests. */
class DiagnosticEventsTest {
    private var now = 1_000L

    @Before fun setUp() {
        EventLog.resetForTest()
        EventLog.clock = { now }
        EventLog.context = { EventLog.Context(sessionId = "s1", attemptId = "a1", engine = "xray", network = "wifi:v4=1") }
    }

    @After fun tearDown() {
        EventLog.resetForTest()
        EventLog.context = { EventLog.Context() }
        EventLog.clock = System::currentTimeMillis
    }

    @Test fun eventsCarryTheCurrentSessionAttemptEngineAndNetwork() {
        val e = EventLog.event("probe", attributes = mapOf("k" to "v"))
        assertEquals("s1", e.sessionId)
        assertEquals("a1", e.attemptId)
        assertEquals("xray", e.engine)
        assertEquals("wifi:v4=1", e.network)
        // An event that names its own session keeps it.
        val own = EventLog.record(DiagEvent(timestamp = 1, severity = Severity.INFO, name = "x", sessionId = "s0"))
        assertEquals("s0", own.sessionId)
    }

    @Test fun theJsonRecordIsOtelShapedAndRoundTrips() {
        val e = EventLog.event("tunnel.check", Severity.WARN, mapOf("destination" to "www.gstatic.com"), profileRef = "p:0123456789",
            testId = "t-1", durationMs = 42, result = "FAIL", stage = FailureStage.TIMEOUT)
        val json = e.toJson()
        assertEquals(1_000L * 1_000_000, json.getLong("timeUnixNano"))
        assertEquals("WARN", json.getString("severityText"))
        assertEquals(13, json.getInt("severityNumber"))
        val attrs = json.getJSONObject("attributes")
        assertEquals("s1", attrs.getString("session.id"))
        assertEquals("TIMEOUT", attrs.getString("failure.stage"))
        assertEquals("OBSERVATION", attrs.getString("event.kind"))
        assertEquals(e, DiagEvent.fromJson(JSONObject(json.toString())))
    }

    @Test fun secretsAreRedactedWhenRecorded() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val e = EventLog.event("log.error.vpn", Severity.ERROR, mapOf("message" to "vless://$uuid@203.0.113.9:443?security=tls failed"),
            error = IllegalStateException("token=$uuid"))
        assertFalse(e.toJson().toString().contains(uuid))
    }

    @Test fun profileRefsAreStableAndDoNotRevealTheId() {
        val ref = DiagEvent.profileRef("profile-123")!!
        assertEquals(ref, DiagEvent.profileRef("profile-123"))
        assertTrue(ref.matches(Regex("p:[0-9a-f]{10}")))
        assertFalse(ref.contains("123"))
        assertNull(DiagEvent.profileRef(""))
    }

    @Test fun aPassRecordsWhatMakesItCheckable() {
        val e = EventLog.pass("tunnel.verify", "www.gstatic.com", "p:abc", 180, "tun (VPN network)")
        assertEquals("PASS", e.result)
        assertEquals("a1", e.attemptId)
        assertEquals("xray", e.engine)
        assertEquals("wifi:v4=1", e.network)
        assertEquals(180L, e.durationMs)
        assertEquals("www.gstatic.com", e.attributes["destination"])
        assertEquals("tun (VPN network)", e.attributes["interface"])
        assertEquals("1000", e.attributes["measured.at"])
    }

    @Test fun theMemoryRingIsBoundedAndSaysWhenItDroppedEvents() {
        repeat(EventLog.MAX_MEMORY + 5) { EventLog.event("e$it") }
        assertEquals(EventLog.MAX_MEMORY, EventLog.snapshot().size)
        assertEquals("e5", EventLog.snapshot().first().name)
        assertTrue(EventLog.truncated)
    }

    @Test fun eventsArePersistedOffTheCallerAndRestoredAfterARestart() {
        val dir = Files.createTempDirectory("events").toFile()
        EventLog.init(dir)
        EventLog.event("before.restart")
        EventLog.flush()
        EventLog.resetForTest()
        EventLog.init(dir)
        EventLog.flush()
        assertEquals(listOf("before.restart"), EventLog.snapshot().map { it.name })
        dir.deleteRecursively()
    }

    @Test fun repeatedFailuresAreGroupedWithFirstLastProfilesNetworksAndSessions() {
        val events = (1..18).map { i ->
            DiagEvent(timestamp = i * 10L, severity = Severity.WARN, name = "proxy.request", sessionId = "s${i % 2}",
                profileRef = "p:${i % 3}", network = "cellular", result = "FAIL", failureStage = FailureStage.TIMEOUT)
        } + DiagEvent(timestamp = 5, severity = Severity.ERROR, name = "engine.terminated", result = "FAIL") +
            DiagEvent(timestamp = 6, severity = Severity.INFO, name = "test.completed", result = "PASS")
        val groups = ErrorAggregator.aggregate(events)
        assertEquals(2, groups.size)
        val g = groups.first()
        assertEquals("18 × proxy.request (TIMEOUT)", g.summary)
        assertEquals(10L, g.firstAt)
        assertEquals(180L, g.lastAt)
        assertEquals(setOf("p:0", "p:1", "p:2"), g.profiles)
        assertEquals(setOf("s0", "s1"), g.sessions)
        assertEquals(setOf("cellular"), g.networks)
    }

    @Test fun testsMoveThroughTheirStatesAndAreBounded() {
        val logged = ArrayList<DiagEvent>()
        val reg = TestRegistry(clock = { now }, log = { logged += it })
        val id = reg.queue("free.real_delay", sessionId = null)
        assertEquals(TestRegistry.State.QUEUED, reg.tests.value.single().state)
        assertTrue(reg.start(id))
        now += 50
        assertTrue(reg.finish(id, success = false, stage = FailureStage.TLS_HANDSHAKE_FAILED))
        val e = reg.tests.value.single()
        assertEquals(TestRegistry.State.FAILED, e.state)
        assertEquals(50L, e.durationMs)
        assertEquals(FailureStage.TLS_HANDSHAKE_FAILED, logged.last().failureStage)
        assertEquals(listOf("test.queued", "test.running", "test.failed"), logged.map { it.name })
        // Finished tests are kept up to a limit.
        repeat(TestRegistry.MAX_FINISHED + 10) { val t = reg.queue("x", null); reg.finish(t, true) }
        assertEquals(TestRegistry.MAX_FINISHED, reg.tests.value.count { it.finished })
    }

    @Test fun disconnectCancelsTheSessionsTestsAndTheirLateResultsAreRefused() {
        val reg = TestRegistry(clock = { now }, log = {})
        reg.beginSession("s1")
        val inSession = reg.queue("tunnel.recheck", "s1")
        val saved = reg.queue("free.real_delay", null)
        val job = Job()
        reg.attach(inSession, job)
        reg.start(inSession)
        reg.endSession()
        assertTrue(job.isCancelled)
        val cancelled = reg.tests.value.first { it.id == inSession }
        assertEquals(TestRegistry.State.CANCELLED, cancelled.state)
        assertEquals("disconnected", cancelled.cancelReason)
        assertFalse(reg.isLive(inSession))
        assertFalse("a late result must not land", reg.finish(inSession, true))
        // Tests of saved servers do not belong to a session and keep running.
        assertTrue(reg.isLive(saved))
    }

    @Test fun aNewSessionRefusesResultsOfTheOldOne() {
        val reg = TestRegistry(clock = { now }, log = {})
        reg.beginSession("s1")
        val old = reg.queue("tunnel.recheck", "s1")
        reg.beginSession("s2")
        assertFalse(reg.finish(old, true))
        assertEquals(TestRegistry.State.CANCELLED, reg.tests.value.single().state)
        assertEquals(1, reg.counts()[TestRegistry.State.CANCELLED])
    }

    @Test fun connectionChangesBecomeEventsWithSelectedAttemptedAndActiveProfilesApart() {
        val chosen = VlessProfile(id = "chosen", name = "A", address = "203.0.113.1", port = 443, uuid = "u")
        val other = chosen.copy(id = "other")
        val ref: (String?) -> String? = { it?.let { id -> "ref-$id" } }
        val start = ConnectionState(status = ConnectionStatus.CONNECTING, sessionId = "s1", attemptId = "a1",
            selectedProfileId = "chosen", attemptedProfileId = "other")
        val events = ConnectionEvents.between(ConnectionState(), start, now, ref)
        assertEquals(listOf("session.start", "attempt.start", "connection.state"), events.map { it.name })
        assertEquals("ref-chosen", events[0].attributes["profile.selected"])
        assertEquals("ref-other", events[0].attributes["profile.attempted"])

        val unverified = start.copy(status = ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, activeProfile = other,
            failureStage = FailureStage.TLS_HANDSHAKE_FAILED)
        val e = ConnectionEvents.between(start, unverified, now, ref).single()
        assertEquals(Severity.WARN, e.severity)
        assertEquals(DiagEvent.Kind.ASSESSMENT, e.kind)
        assertEquals(FailureStage.TLS_HANDSHAKE_FAILED, e.failureStage)
        assertEquals("ref-other", e.profileRef)
        assertNull(e.result)

        val connected = unverified.copy(status = ConnectionStatus.CONNECTED, verifiedAt = 2000, failureStage = null)
        val c = ConnectionEvents.between(unverified, connected, now, ref).single()
        assertEquals("PASS", c.result)
        assertEquals("2000", c.attributes["verified.at"])

        val ended = ConnectionEvents.between(connected, ConnectionState(status = ConnectionStatus.DISCONNECTED), now, ref)
        assertEquals(listOf("connection.state", "session.end"), ended.map { it.name })
        assertEquals("s1", ended[1].sessionId)
        assertNotNull(ended[1].durationMs)

        // Only traffic counters changing is not an event.
        assertTrue(ConnectionEvents.between(connected, connected.copy(uploadBytes = 99), now, ref).isEmpty())
    }
}
