package com.example.vpn.connectivity

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.lab.EchMeasurement
import com.example.vpn.lab.FullAnalysis
import com.example.vpn.lab.MtuIntelligence
import com.example.vpn.lab.NetworkState
import com.example.vpn.lab.PathStatus
import com.example.xray.ProbeTargets
import com.example.xray.RealDelayProbe
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A small censor simulation (section 32): each scenario decides which real requests pass, by server and by
 * probe target, and checks what the shared evidence concludes. Requests go through RealDelayProbe with a fake
 * libXray, and every result reaches the brain through the same observer the app installs. Simulated only:
 * nothing here was measured on a phone or a real network.
 */
class ScenarioSimulationTest {
    private val uuid = "8b0e2c4a-9f6d-4c1e-a7b3-2d5f8e1c0a9b"
    private fun ws(host: String) = VlessProfile(name = host, address = host, port = 443, uuid = uuid, transport = "ws", security = "tls",
        sni = host, host = host, path = "/ws", fingerprint = "chrome")
    private val cdn = ws("cdn.example.com")
    private val cdn2 = ws("cdn2.example.net")
    private val reality = VlessProfile(name = "reality", address = "203.0.113.10", port = 443, uuid = uuid, security = "reality",
        sni = "www.example.org", publicKey = "x".repeat(43), shortId = "ab", fingerprint = "chrome")
    private val hy2 = VlessProfile(name = "hy2", address = "198.51.100.20", port = 443, uuid = "pw", protocolType = ProtocolType.HYSTERIA2)
    private val wg = VlessProfile(name = "wg", address = "198.51.100.30", port = 51820, uuid = "", protocolType = ProtocolType.WIREGUARD)
    private val v6 = ws("cdn6.example.com").copy(address = "2001:db8::10")

    private val book get() = ConnectivityBrain.book
    private val originalInvoker = RealDelayProbe.invoker
    private val originalObserver = RealDelayProbe.observer
    private val originalKey = ConnectivityBrain.networkKey
    private var network = "sim"

    @Before fun install() {
        ProbeTargets.health.reset()
        ProbeTargets.active = ProbeTargets.BUILT_IN
        RealDelayProbe.observer = ConnectivityBrain::onProbe
        ConnectivityBrain.networkKey = { network }
    }

    @After fun restore() {
        RealDelayProbe.invoker = originalInvoker
        RealDelayProbe.observer = originalObserver
        ConnectivityBrain.networkKey = originalKey
        ProbeTargets.health.reset()
    }

    /** Starts a scenario on its own network session. */
    private fun on(name: String) { network = "sim-$name-${System.nanoTime()}"; ConnectivityBrain.refreshSession() }

    /** The censor: [passes] gets the generated Xray config and the probe URL of each request. */
    private fun censor(passes: (config: String, url: String) -> Boolean) {
        RealDelayProbe.invoker = { request ->
            val payload = JSONObject(request).getJSONObject("payload")
            val url = payload.getString("url")
            val configs = payload.getJSONArray("configs")
            val items = JSONArray((0 until configs.length()).map { i ->
                if (passes(configs.getJSONObject(i).getString("xrayJson"), url)) JSONObject().put("success", true).put("delay", 240)
                else JSONObject().put("success", false).put("error", "EOF")
            })
            JSONObject().put("success", true).put("data", JSONObject().put("results", items)).toString()
        }
    }

    private fun ref(p: VlessProfile) = PathRef.of(p)
    /** UDP configs are reported as the observer would see them (the simulation does not build their configs). */
    private fun udp(p: VlessProfile, ok: Boolean) =
        ConnectivityBrain.onProbe(p, if (ok) RealDelayProbe.Outcome.Delay(180) else RealDelayProbe.Outcome.Failed("timeout"))

    @Test fun dnsPoisoningIsKeptApartFromEgress() {
        on("dns")
        censor { _, _ -> true } // proxies reached by address still work
        book.recordNetwork(EvidenceSource.LAB, MeasurementType.DNS, "dns-poisoned", false)
        book.recordDns(DnsEvidenceKind.SYSTEM_DNS, DnsEvidenceStatus.POISONED)
        book.recordDns(DnsEvidenceKind.PRECONNECT_DOH_RESOLUTION, DnsEvidenceStatus.VERIFIED)
        assertTrue(RealDelayProbe.measure(reality, 5) is RealDelayProbe.Outcome.Delay)
        val a = book.assess()
        assertTrue(NetworkState.DNS_MANIPULATED in a.restrictions)
        assertNotEquals(NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY, a.primary)
        assertEquals(DnsEvidenceStatus.VERIFIED, book.dnsEvidence()[DnsEvidenceKind.PRECONNECT_DOH_RESOLUTION])
    }

    @Test fun sniFilteringIsNamedOnlyFromTheComparisonAndEchIsMeasured() {
        on("sni")
        // The censor cuts the plain name; an ECH copy hides it and passes.
        censor { config, _ -> config.contains("echConfigList") || !config.contains("cdn.example.com") }
        book.recordNetwork(EvidenceSource.LAB, MeasurementType.SNI_COMPARISON, "sni", false)
        val base = RealDelayProbe.measure(cdn, 5)
        assertTrue(base is RealDelayProbe.Outcome.Failed)
        val ech = EchMeasurement.variant(cdn)!!
        val r = EchMeasurement.judge(base, RealDelayProbe.measure(ech, 5), RealDelayProbe.measure(ech, 5))
        assertEquals(EchMeasurement.State.ECH_VERIFIED, r.state)
        assertTrue(NetworkState.SNI_INTERFERENCE_SUSPECTED in book.assess().restrictions)
        assertEquals("the saved config stays failed; the ECH copy is its own path",
            PathEligibility.RECENTLY_FAILED, book.eligibility(ref(cdn)))
        assertNotEquals(PathEligibility.RECENTLY_FAILED, book.eligibility(ref(ech)))
    }

    @Test fun aTlsCutoffWithoutAComparisonIsOnlyATlsPathFailure() {
        on("tls")
        censor { _, _ -> false }
        RealDelayProbe.measure(listOf(cdn, reality), 5)
        val a = book.assess()
        assertTrue(NetworkState.TLS_PATH_FAILURE in a.restrictions)
        assertFalse(NetworkState.SNI_INTERFERENCE_SUSPECTED in a.restrictions)
        assertNotEquals("no recovery was attempted", NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY, a.primary)
    }

    @Test fun aUdpBlockKeepsUdpPathsOutOfFailover() {
        on("udp")
        censor { _, _ -> true }
        udp(hy2, false); udp(wg, false)
        RealDelayProbe.measure(cdn, 5)
        assertTrue(NetworkState.UDP_DEGRADED in book.assess().restrictions)
        assertEquals(listOf(cdn), book.eligibleInOrder(listOf(hy2, wg, cdn)) { ref(it) })
    }

    @Test fun aQuicBlackholeAloneIsNotCalledAUdpBlock() {
        on("quic")
        udp(hy2, false); udp(wg, true)
        assertFalse(NetworkState.UDP_DEGRADED in book.assess().restrictions)
        assertEquals(PathEligibility.RECENTLY_FAILED, book.eligibility(ref(hy2)))
        assertEquals(PathEligibility.FRESH_CANDIDATE, book.eligibility(ref(wg)))
    }

    @Test fun partialInternationalAccessIsVerifiedButDegraded() {
        on("partial")
        censor { _, url -> url.contains("apple") }
        val r = RealDelayProbe.measureTargets(cdn, 5)
        assertEquals(ProbeTargets.Verdict.EGRESS_VERIFIED_DEGRADED, r.verdict)
        assertEquals(PathEligibility.FRESH_CANDIDATE, book.eligibility(ref(cdn)))
    }

    @Test fun ipv4AndIpv6AreJudgedSeparately() {
        on("v6")
        censor { config, _ -> !config.contains("2001:db8::10") }
        RealDelayProbe.measure(listOf(cdn, v6), 5)
        assertEquals(PathEligibility.FRESH_CANDIDATE, book.eligibility(ref(cdn)))
        assertEquals(PathEligibility.RECENTLY_FAILED, book.eligibility(ref(v6)))
        assertEquals(EvidenceAddressFamily.IPV6, book.pathEvidence(ref(v6)).last().addressFamily)
    }

    @Test fun recursiveDnsEgressIsNeverInferred() {
        on("recursive")
        book.recordDns(DnsEvidenceKind.DIRECT_FOREIGN_DNS, DnsEvidenceStatus.WORKS)
        assertEquals(DnsEvidenceStatus.INFRASTRUCTURE_REQUIRED, book.dnsEvidence()[DnsEvidenceKind.RECURSIVE_FOREIGN_DNS_EGRESS])
    }

    @Test fun anMtuBlackholeIsFoundAndKeptForThatNetworkOnly() {
        on("mtu")
        val limit = 1400
        val search = MtuIntelligence.Search(1500, ipv6 = false)
        while (true) { val m = search.next() ?: break; search.record(m, m <= limit) }
        assertEquals(1400, search.best())
        val cache = MtuIntelligence.Cache()
        val key = MtuIntelligence.Key(network, "xray", "wireguard", "ipv4")
        cache.store(cache.propose(key, 1400, 0).stage().verify().commit())
        assertEquals(1400, cache.working(key, 1))
        assertEquals(null, cache.working(key.copy(network = "other"), 1))
    }

    @Test fun lossShowsAsDegradedNotVerified() {
        assertEquals(PathStatus.DEGRADED, FullAnalysis.verdict(listOf(true, false), 0))
        assertEquals(PathStatus.VERIFIED, FullAnalysis.verdict(listOf(true, true), FullAnalysis.STABLE_WINDOW_MS))
    }

    @Test fun oneProbeTargetsOutageDoesNotFailWorkingServers() {
        on("target")
        censor { _, url -> !url.contains("gstatic") }
        val o = RealDelayProbe.measure(listOf(cdn, cdn2), 5)
        assertTrue(o.all { it is RealDelayProbe.Outcome.Delay })
        assertEquals(PathEligibility.FRESH_CANDIDATE, book.eligibility(ref(cdn)))
    }

    @Test fun aNetworkSwitchClearsRecentFailures() {
        on("before")
        censor { _, _ -> false }
        RealDelayProbe.measure(cdn, 5)
        assertEquals(PathEligibility.RECENTLY_FAILED, book.eligibility(ref(cdn)))
        on("after")
        assertEquals(PathEligibility.UNTESTED, book.eligibility(ref(cdn)))
    }
}
