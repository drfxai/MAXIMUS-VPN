package com.example.vpn.connectivity

import com.example.vpn.EndpointResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Stage 10: DNS resilience from real queries, and only locally validated endpoints for recovery. */
class DnsAndEndpointTest {
    private var now = 1_000_000L
    private var saved: String? = null
    private fun dns() = DnsResilienceEngine({ saved }, { saved = it }, { now })
    private val resolvers = listOf("a", "b", "c", "d")

    @Test fun resolversThatAnswerGoFirstAndOnesThatNeverDidAreLeftOut() {
        val e = dns()
        repeat(3) { e.record("wifi", "a", "x.example", DnsOutcome.TIMEOUT, null) }
        e.record("wifi", "c", "x.example", DnsOutcome.ANSWER, 300)
        e.record("wifi", "d", "x.example", DnsOutcome.ANSWER, 80)
        val p = e.profile("wifi", resolvers)
        assertEquals(listOf("d", "c", "b"), p.resolverOrder)
        // Another network has its own measurements.
        assertEquals(resolvers, e.profile("cell:43211", resolvers).resolverOrder)
    }

    @Test fun atLeastThreeResolversAreAlwaysAsked() {
        val e = dns()
        resolvers.forEach { r -> repeat(3) { e.record("wifi", r, "x", DnsOutcome.ERROR, null) } }
        assertEquals(3, e.profile("wifi", resolvers).resolverOrder.size)
    }

    @Test fun aNameTheNetworkKeepsBlockingSkipsTheGraceWaitButOthersDoNot() {
        val e = dns()
        repeat(2) { e.record("wifi", DnsResilienceEngine.SYSTEM, "bpb.workers.dev", DnsOutcome.BLOCKED_ANSWER, 20) }
        e.record("wifi", DnsResilienceEngine.SYSTEM, "ok.example", DnsOutcome.BLOCKED_ANSWER, 20)
        e.record("wifi", DnsResilienceEngine.SYSTEM, "ok.example", DnsOutcome.ANSWER, 20)
        val p = e.profile("wifi", resolvers)
        assertTrue(p.skipSystemFor("BPB.workers.dev"))
        assertFalse(p.skipSystemFor("ok.example"))
        assertFalse(e.profile("cell", resolvers).skipSystemFor("bpb.workers.dev"))
        // Measurements persist and expire.
        assertTrue(dns().profile("wifi", resolvers).skipSystemFor("bpb.workers.dev"))
        now += DnsResilienceEngine.TTL_MS + 1
        assertFalse(dns().profile("wifi", resolvers).skipSystemFor("bpb.workers.dev"))
    }

    private val host = "bpb.example.workers.dev"
    private val answer = """{"Status":0,"Answer":[{"name":"$host","type":1,"data":"104.21.30.40"}]}"""
    private val open: (URL) -> HttpURLConnection = { url ->
        object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream(): InputStream = ByteArrayInputStream(answer.toByteArray())
        }
    }

    @Test fun everyRealQueryIsMeasuredAndATamperedNameGoesStraightToDoh() {
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<Pair<String, DnsOutcome>>())
        val r = EndpointResolver.resolve(host, { listOf(InetAddress.getByName("10.10.34.36")) }, open,
            onOutcome = { res, o, _ -> outcomes += res to o })
        assertEquals("104.21.30.40", r.address)
        assertTrue(outcomes.contains(DnsResilienceEngine.SYSTEM to DnsOutcome.BLOCKED_ANSWER))
        // Known tampered: the slow system answer is not waited for.
        val started = System.nanoTime()
        val fast = EndpointResolver.resolve(host, { Thread.sleep(3_000); listOf(InetAddress.getByName("10.10.34.36")) }, open,
            doh = listOf(EndpointResolver.DOH_ENDPOINTS.first()), skipSystem = true)
        assertEquals("104.21.30.40", fast.address)
        assertTrue((System.nanoTime() - started) / 1_000_000 < EndpointResolver.SYSTEM_GRACE_MS)
    }

    @Test fun godModeStillNeverAsksTheNetworksDns() {
        var asked = false
        EndpointResolver.resolve(host, { asked = true; emptyList() }, open, private = true, skipSystem = false)
        assertFalse(asked)
    }

    private fun scores() = EndpointScoringEngine({ saved }, { saved = it }, { now })
    private fun m(ip: String, ok: Boolean, ms: Long? = 100, network: String = "wifi") = EndpointScoringEngine.Measurement(ip, network, now, ok, ms)

    @Test fun onlyRecentlyPassingPublicEndpointsAreValidated() {
        val s = scores()
        s.record(listOf(m("104.16.1.1", true, 200), m("104.16.1.2", true, 90), m("104.16.1.3", false, null),
            m("10.0.0.1", true), m("not-an-ip", true), m("104.16.1.4", true, 50, network = "cell")))
        s.record(listOf(m("104.16.1.2", false, null), m("104.16.1.2", false, null)))
        assertEquals(listOf("104.16.1.1"), s.validated("wifi"))
        assertEquals(listOf("104.16.1.4"), scores().validated("cell"))
        now += EndpointScoringEngine.VALID_FOR_MS + 1
        assertTrue(s.validated("wifi").isEmpty())
    }

    @Test fun validatedEndpointsBecomeRecoveryAlternativesBehindTheGate() {
        var ledger: String? = null
        val engine = BpbRecoveryEngine(ledger = RecoveryLedger({ ledger }, { ledger = it }), clock = { RecoveryProfiles.REVIEWED_AT + 1 },
            maxCandidates = 20)
        val parent = com.example.data.model.VlessProfile(id = "p", name = "p", address = "bpb.example.workers.dev", port = 443,
            uuid = "11111111-1111-1111-1111-111111111111", security = "tls", transport = "ws", sni = "bpb.example.workers.dev",
            host = "bpb.example.workers.dev", fingerprint = "chrome", canonicalFingerprint = "fp")
        val s = scores()
        s.record(listOf(m("104.16.1.1", true)))
        val c = engine.generate(parent, com.example.vpn.diagnostics.FailureStage.TCP_CONNECT_FAILED, "wifi", s.validated("wifi"))
        assertEquals(listOf("104.16.1.1"), c.mapNotNull { it.endpoint })
        assertTrue(c.all { it.security.passed })
    }
}
