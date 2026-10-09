package com.example.vpn.lab

import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import com.example.vpn.smart.NetworkCapabilityProfile
import com.example.vpn.smart.NetworkIdentity
import com.example.xray.RealDelayProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Autonomous LAB V5: replanning, verification, isolation semantics, identity and memory. These are unit-level
 * scenarios over the pure planner and rules; they are not the censor simulator and say nothing about a phone.
 */
class AdaptiveLabTest {
    private val open = NetworkCapabilityProfile(
        transport = "cellular", ipv4Available = true, udpAvailable = true, tcpAvailable = true, tlsAvailable = true,
        cloudflareReachable = true, dnsWorking = true, dnsManipulated = false, dohReachable = true,
        internationalOk = 3, internationalTried = 3, domesticReachable = true, sniFiltered = false, measuredAt = 5L
    )
    private val nin = open.copy(internationalOk = 0, tcpAvailable = false, tlsAvailable = false, cloudflareReachable = false)
    private val dead = NetworkCapabilityProfile(
        transport = "cellular", ipv4Available = true, udpAvailable = false, tcpAvailable = false, tlsAvailable = false, dnsWorking = false,
        dohReachable = false, dotReachable = false, internationalOk = 0, internationalTried = 3, domesticReachable = false, quicStatus = "QUIC_UNRESPONSIVE"
    )
    private val ordinary = ExperimentPlanner.Plan(ExperimentPlanner.Mode.ORDINARY, emptyList(), emptyMap(), 20, emptyList())
    private fun cand(id: String, f: PathFamily, ipv6: Boolean = false) = AdaptivePlanner.Candidate(id, id, f, ipv6)
    private fun test(d: AdaptivePlanner.Decision): AdaptivePlanner.Decision.Test = d as AdaptivePlanner.Decision.Test

    // ------------------------------------------------------------ fast recovery and deep optimization

    @Test fun firstWorkingPathIsShownAtOnceAndVerifiedLater() {
        val p = AdaptivePlanner(ordinary, open)
        p.add(listOf(cand("a", PathFamily.VLESS_TLS), cand("b", PathFamily.VLESS_REALITY), cand("c", PathFamily.WEBSOCKET)))
        assertEquals(AdaptivePlanner.Phase.FAST_RECOVERY, p.phase)
        assertEquals("a", test(p.decide(0)).candidate.id)
        p.record("a", listOf(true), 100, now = 1_000)
        assertEquals("a", p.firstWorking)
        assertEquals(AdaptivePlanner.Phase.DEEP_OPTIMIZATION, p.phase)
        assertEquals(PathStatus.CANDIDATE, p.track("a")!!.status)
        // A working saved config does not end the run: the next test looks for another family.
        val next = test(p.decide(1_500))
        assertFalse(next.recheck)
        assertNotEquals("a", next.candidate.id)
        p.record(next.candidate.id, listOf(false), null, now = 2_000)
        // After the stability window the winner is rechecked; two passes 3 s apart make it VERIFIED.
        val recheck = test(p.decide(4_000))
        assertTrue(recheck.recheck)
        assertEquals("a", recheck.candidate.id)
        p.record("a", listOf(true), 120, now = 4_000)
        assertEquals(PathStatus.VERIFIED, p.track("a")!!.status)
        assertEquals("a", p.best()!!.candidate.id)
    }

    @Test fun passThenFailedRecheckIsDegradedAndOnePassIsCandidate() {
        assertEquals(PathStatus.CANDIDATE, FullAnalysis.verdict(listOf(true), 0))
        assertEquals(PathStatus.VERIFIED, FullAnalysis.verdict(listOf(true, true), 3_000))
        assertEquals(PathStatus.CANDIDATE, FullAnalysis.verdict(listOf(true, true), 500))
        assertEquals(PathStatus.DEGRADED, FullAnalysis.verdict(listOf(true, false), 0))
        assertEquals(PathStatus.FAILED, FullAnalysis.verdict(listOf(false, false), 0))
        assertEquals(PathStatus.NOT_TESTED, FullAnalysis.verdict(emptyList(), 0))
        val p = AdaptivePlanner(ordinary, open)
        p.add(listOf(cand("a", PathFamily.VLESS_TLS)))
        p.record("a", listOf(true), 90, 1_000)
        // Only a recheck remains and it is not due yet: wait for the window, never call it verified early.
        assertEquals(AdaptivePlanner.Decision.Wait(4_000, "waiting for the stability window before a recheck"), p.decide(1_500))
        p.record("a", listOf(false), null, 4_000)
        assertEquals(PathStatus.DEGRADED, p.track("a")!!.status)
    }

    @Test fun engineWinnersAreReverifiedInOneRun() {
        val first = EngineProbe.judge("tor", true, RealDelayProbe.Outcome.Delay(900))
        val stable = EngineProbe.combine(first, listOf(RealDelayProbe.Outcome.Delay(800) to 3_500L))
        assertEquals(ConnectionStage.STABILITY_VERIFIED, stable.stage)
        assertEquals(listOf(true, true), stable.outcomes)
        assertEquals(800L, stable.latencyMs)
        val flaky = EngineProbe.combine(first, listOf(RealDelayProbe.Outcome.Failed("timeout") to 3_500L))
        assertEquals(listOf(true, false), flaky.outcomes)
        assertEquals("timeout", flaky.failure)
        val p = AdaptivePlanner(ordinary, open)
        p.add(listOf(cand("t", PathFamily.TOR_OBFS4), cand("u", PathFamily.TOR_WEBTUNNEL)))
        p.record("t", stable.outcomes, stable.latencyMs, 10_000, spanMs = stable.spanMs)
        assertEquals(PathStatus.VERIFIED, p.track("t")!!.status)
        p.record("u", flaky.outcomes, flaky.latencyMs, 20_000, flaky.failure, flaky.spanMs)
        assertEquals(PathStatus.DEGRADED, p.track("u")!!.status)
    }

    // ------------------------------------------------------------ replanning

    @Test fun repeatedFailuresDeprioritizeTheirFailureDomain() {
        val p = AdaptivePlanner(ordinary, open)
        p.add(listOf(cand("t1", PathFamily.VLESS_TLS), cand("t2", PathFamily.WEBSOCKET), cand("t3", PathFamily.XHTTP), cand("ps", PathFamily.PSIPHON)))
        assertEquals("t1", test(p.decide(0)).candidate.id)
        p.record("t1", listOf(false), null, 1)
        assertEquals("t2", test(p.decide(2)).candidate.id)
        p.record("t2", listOf(false), null, 3)
        // Two TCP/TLS failures: the engine family now beats a third TCP/TLS config despite its cost.
        assertEquals("ps", test(p.decide(4)).candidate.id)
        assertTrue(p.events().any { "deprioritized" in it })
    }

    @Test fun udpSuccessRaisesUdpFamilies() {
        val p = AdaptivePlanner(ordinary, open)
        p.add(listOf(cand("hy", PathFamily.HYSTERIA2), cand("tls", PathFamily.VLESS_TLS), cand("wg", PathFamily.WIREGUARD)))
        p.record("hy", listOf(true), 70, 1)
        assertEquals("wg", test(p.decide(2)).candidate.id)
        assertTrue(p.events().any { "UDP" in it })
    }

    @Test fun ipv6GoesFirstWhenIpv4FailsAndIpv6Works() {
        val v6 = open.copy(internationalOk = 0, ipv6Available = true, ipv6TlsOk = true)
        val p = AdaptivePlanner(ordinary, v6)
        p.add(listOf(cand("v4", PathFamily.VLESS_TLS), cand("v6", PathFamily.VLESS_TLS, ipv6 = true)))
        assertEquals("v6", test(p.decide(0)).candidate.id)
        // And last when IPv6 is broken.
        val broken = AdaptivePlanner(ordinary, open.copy(ipv6Available = true, ipv6TlsOk = false))
        broken.add(listOf(cand("v6", PathFamily.VLESS_TLS, ipv6 = true), cand("v4", PathFamily.VLESS_TLS)))
        assertEquals("v4", test(broken.decide(0)).candidate.id)
    }

    @Test fun dnsTunnelSuccessStopsOrdinaryMutationsInEmergency() {
        val plan = ExperimentPlanner.plan(NetworkStateClassifier.classify(nin, 1L), nin, 15)
        assertEquals(ExperimentPlanner.Mode.EMERGENCY_RECOVERY, plan.mode)
        val p = AdaptivePlanner(plan, nin)
        p.add(listOf(cand("tls", PathFamily.VLESS_TLS), cand("dns", PathFamily.DNS_TUNNEL), cand("obfs", PathFamily.TOR_OBFS4)))
        // Recovery families go before ordinary ones, which keep a low prior.
        assertEquals("dns", test(p.decide(0)).candidate.id)
        p.record("dns", listOf(true, true), 1_500, 5_000, spanMs = 3_200)
        assertTrue(p.ordinaryMutationsStopped)
        assertEquals(PathStatus.VERIFIED, p.track("dns")!!.status)
        // Other recovery families are still compared before the low-prior ordinary config.
        assertEquals("obfs", test(p.decide(5_100)).candidate.id)
    }

    @Test fun aiHintOnlyReordersAndIsValidated() {
        val offered = setOf(PathFamily.TOR_OBFS4, PathFamily.PSIPHON, PathFamily.DNS_TUNNEL)
        assertEquals(listOf(PathFamily.PSIPHON, PathFamily.TOR_OBFS4),
            AiCheckpoint.validate(listOf("psiphon", "rm -rf /", "VLESS_REALITY", "tor obfs4", "PSIPHON"), offered))
        assertTrue(AiCheckpoint.validate(null, offered).isEmpty())
        val p = AdaptivePlanner(ordinary, open)
        p.add(listOf(cand("a", PathFamily.VLESS_TLS), cand("b", PathFamily.WEBSOCKET)))
        p.hint(listOf(PathFamily.WEBSOCKET))
        assertEquals("b", test(p.decide(0)).candidate.id)
        // A hint never makes anything verified.
        assertTrue(p.tracks().none { it.status == PathStatus.VERIFIED })
    }

    // ------------------------------------------------------------ stop criteria

    @Test fun stopsWhenThreeIndependentPathsAreVerifiedAndOneIsClearlyBest() {
        fun run(latencies: List<Long>): AdaptivePlanner {
            val p = AdaptivePlanner(ordinary, open)
            val fams = listOf(PathFamily.VLESS_TLS, PathFamily.VLESS_REALITY, PathFamily.WEBSOCKET)
            p.add(fams.mapIndexed { i, f -> cand("c$i", f) } + cand("extra", PathFamily.GRPC))
            fams.indices.forEach { i -> p.record("c$i", listOf(true), latencies[i], i.toLong()) }
            fams.indices.forEach { i -> p.record("c$i", listOf(true), latencies[i], 5_000L + i) }
            return p
        }
        val clear = run(listOf(100, 300, 400))
        val stop = clear.decide(6_000)
        assertTrue(stop is AdaptivePlanner.Decision.Stop)
        assertTrue((stop as AdaptivePlanner.Decision.Stop).why.startsWith("three independent"))
        // Close results: keep comparing.
        val close = run(listOf(100, 120, 130))
        assertEquals("extra", test(close.decide(6_000)).candidate.id)
    }

    @Test fun stopsWhenTheBudgetIsSpentOrNothingIsLeft() {
        val p = AdaptivePlanner(ordinary.copy(budget = 2), open)
        p.add(listOf(cand("a", PathFamily.VLESS_TLS), cand("b", PathFamily.WEBSOCKET), cand("c", PathFamily.GRPC)))
        p.record("a", listOf(false), null, 1)
        p.record("b", listOf(false), null, 2)
        assertTrue((p.decide(3) as AdaptivePlanner.Decision.Stop).why.contains("budget"))
        val q = AdaptivePlanner(ordinary, open)
        q.add(listOf(cand("a", PathFamily.VLESS_TLS)))
        q.record("a", listOf(false), null, 1)
        assertEquals(AdaptivePlanner.Decision.Stop("every candidate was tested"), q.decide(2))
        assertEquals(AdaptivePlanner.Phase.DONE, q.phase)
    }

    // ------------------------------------------------------------ isolation semantics

    @Test fun trueIsolationNeedsEveryAvailableFamilyToFail() {
        assertEquals(NetworkState.NO_VERIFIED_EGRESS, NetworkStateClassifier.classify(dead, 1L).primary)
        val p = AdaptivePlanner(ordinary, dead)
        p.add(listOf(cand("tls", PathFamily.VLESS_TLS), cand("tor", PathFamily.TOR_SNOWFLAKE)))
        val available = setOf(PathFamily.VLESS_TLS, PathFamily.TOR_SNOWFLAKE)
        p.record("tls", listOf(false), null, 1)
        // A recovery family was never tested: no conclusion.
        assertNull(EmergencyRecovery.conclude(dead, p.tracks(), available))
        assertTrue(EmergencyRecovery.whyNot(dead, p.tracks(), available)!!.contains("Tor Snowflake"))
        p.record("tor", listOf(false), null, 2)
        assertEquals(NetworkState.TRUE_PHYSICAL_ISOLATION, EmergencyRecovery.conclude(dead, p.tracks(), available))
        // Any sign of life on the network, or no engine family among the tests: never isolation.
        assertNull(EmergencyRecovery.conclude(dead.copy(dnsWorking = true), p.tracks(), available))
        assertNull(EmergencyRecovery.conclude(dead.copy(quicStatus = "QUIC_DEGRADED"), p.tracks(), available))
        val xrayOnly = AdaptivePlanner(ordinary, dead).apply { add(listOf(cand("tls", PathFamily.VLESS_TLS))); record("tls", listOf(false), null, 1) }
        assertNull(EmergencyRecovery.conclude(dead, xrayOnly.tracks(), setOf(PathFamily.VLESS_TLS)))
    }

    @Test fun quicSuspectedIsNeverShownAsBlocked() {
        val rows = FullAnalysis.networkPaths(open.copy(quicStatus = "QUIC_BLOCKED_SUSPECTED"), 1L).associateBy { it.key }
        assertEquals(PathStatus.BLOCKED_SUSPECTED, rows.getValue("quic").status)
        assertEquals(PathStatus.UNRESPONSIVE, FullAnalysis.networkPaths(open.copy(quicStatus = "QUIC_UNRESPONSIVE"), 1L).first { it.key == "quic" }.status)
        // One failed DoH/DoT check is a suspicion, not a proven block.
        assertEquals(PathStatus.BLOCKED_SUSPECTED, FullAnalysis.networkPaths(open.copy(dohReachable = false), 1L).first { it.key == "dns-doh" }.status)
    }

    // ------------------------------------------------------------ live rows

    @Test fun liveRowsMoveFromQueuedToTestingToResult() {
        val p = AdaptivePlanner(ordinary, open)
        p.add(listOf(cand("a", PathFamily.VLESS_TLS), cand("b", PathFamily.WEBSOCKET)))
        fun rows(current: String?, finished: Boolean = false) =
            FullAnalysis.liveFamilyRows(p.tracks(), current, ordinary, emptyMap(), "NS-1", 10L, finished, p.stopReason).associateBy { it.title }
        assertEquals(PathStatus.QUEUED, rows(null).getValue("VLESS TLS").status)
        assertEquals(PathStatus.TESTING, rows("a").getValue("VLESS TLS").status)
        p.record("a", listOf(true), 80, 1)
        val after = rows(null)
        assertEquals(PathStatus.CANDIDATE, after.getValue("VLESS TLS").status)
        assertEquals(80L, after.getValue("VLESS TLS").latencyMs)
        assertEquals("NS-1", after.getValue("VLESS TLS").sessionId)
        assertEquals(PathStatus.QUEUED, after.getValue("WebSocket").status)
        assertEquals(PathStatus.NOT_TESTED, rows(null, finished = true).getValue("WebSocket").status)
        assertEquals(PathStatus.NOT_REQUIRED,
            FullAnalysis.liveFamilyRows(p.tracks(), null, ordinary, emptyMap(), "NS-1", 10L, true, "enough", setOf(PathFamily.WEBSOCKET))
                .first { it.title == "WebSocket" }.status)
    }

    // ------------------------------------------------------------ network identity and memory

    @Test fun wifiNetworksAreToldApartWithoutTheirName() {
        val home = NetworkIdentity.Inputs("192.168.1.1", listOf("192.168.1.1"), null, "192.168.1.0/24", false, 1500)
        val cafe = NetworkIdentity.Inputs("10.0.0.1", listOf("10.0.0.1", "8.8.8.8"), "cafe.lan", "10.0.0.0/24", true, 1500)
        val salt = "a".repeat(32)
        val a = NetworkIdentity.key("wifi", home, salt)
        assertEquals(a, NetworkIdentity.key("wifi", home.copy(dnsServers = listOf("192.168.1.1")), salt))
        assertNotEquals(a, NetworkIdentity.key("wifi", cafe, salt))
        // Another install (salt) gives another fingerprint for the same network.
        assertNotEquals(a, NetworkIdentity.key("wifi", home, "b".repeat(32)))
        assertTrue(a.startsWith("wifi:") && a.length == "wifi:".length + NetworkIdentity.LENGTH)
        assertFalse(a.contains("192.168"))
        // Nothing to fingerprint: the plain kind, never a merged fake identity.
        assertEquals("wifi", NetworkIdentity.key("wifi", NetworkIdentity.Inputs(null, emptyList(), null, null, false, null), salt))
        assertEquals("192.168.1.0/24", NetworkIdentity.ipv4Network(byteArrayOf(192.toByte(), 168.toByte(), 1, 23), 24))
        assertEquals("Wi-Fi · ${a.substringAfter(':').take(4)}", NetworkIdentity.label(a, "Wi-Fi"))
        assertEquals("Irancell", NetworkIdentity.label("cell:43235", "Irancell"))
    }

    @Test fun eachNetworkGetsItsOwnSessionAndHistory() {
        val t = NetworkSessionTracker(clock = { 1L })
        val keys = listOf("cell:43235", "cell:43211", "wifi:aaaaaaaaaaaa", "wifi:bbbbbbbbbbbb")
        val sessions = keys.map { t.observe(it, "IPv4", it).also { (_, isNew) -> assertTrue(isNew) }.first }
        assertEquals(4, sessions.map { it.sessionId }.distinct().size)
        assertEquals("wifi", sessions[2].vpnKey)
        assertEquals("cell:43235", sessions[0].vpnKey)
        // Evidence from Wi-Fi A never moves the plan on Wi-Fi B.
        val ev = LabEvidence(sessions[2].contextKey, PathFamily.VLESS_REALITY, "fp", true, ConnectionStage.APPLICATION_REQUEST_PASSED, 90, 2, 2,
            "AVAILABLE", "passed", 0, LabEvidence.TTL_MS, 7)
        assertEquals(mapOf(PathFamily.VLESS_REALITY to 1.0), LabEvidence.priors(listOf(ev), sessions[2].contextKey, 1_000, 7))
        assertTrue(LabEvidence.priors(listOf(ev), sessions[3].contextKey, 1_000, 7).isEmpty())
    }

    @Test fun evidenceFreshnessDecidesWhetherHistoryCounts() {
        val ev = LabEvidence("n", PathFamily.TOR_OBFS4, "fp", false, ConnectionStage.LOCAL_PROXY_READY, null, 0, 2, null, "passed", 0, LabEvidence.TTL_MS, 7)
        assertEquals(LabEvidence.Freshness.FRESH, ev.freshness(1_000, 7))
        assertEquals(LabEvidence.Freshness.AGING, ev.freshness(LabEvidence.FRESH_MS + 1, 7))
        assertEquals(LabEvidence.Freshness.STALE, ev.freshness(LabEvidence.AGING_MS + 1, 7))
        assertEquals(LabEvidence.Freshness.EXPIRED, ev.freshness(LabEvidence.TTL_MS, 7))
        // A different app build makes fresh evidence stale.
        assertEquals(LabEvidence.Freshness.STALE, ev.freshness(1_000, 8))
        assertEquals(-0.5, LabEvidence.priors(listOf(ev), "n", LabEvidence.FRESH_MS + 1, 7).getValue(PathFamily.TOR_OBFS4), 0.0)
        assertTrue(LabEvidence.priors(listOf(ev), "n", LabEvidence.AGING_MS + 1, 7).isEmpty())
        assertEquals(ev, LabEvidence.fromJson(JSONObject(ev.toJson().toString())))
        // The store keeps it across restarts and drops expired records.
        var saved: String? = null
        val store = LabStore(load = { saved }, save = { saved = it }, clock = { 10L })
        store.addEvidence(listOf(ev))
        assertEquals(listOf(ev), LabStore(load = { saved }, save = {}).evidenceFor("n"))
    }

    @Test fun aiBriefNeverCarriesTheNetworkFingerprint() {
        val snap = LabSnapshot(network = NetworkContext("NS-1", "wifi:abcdef123456", "IPv4", 0, "Wi-Fi · abcd"))
        val brief = LabBrief.of(snap, now = 1L)
        assertTrue(brief.contains("Wi-Fi"))
        assertFalse(brief.contains("abcdef"))
        assertFalse(brief.contains("abcd"))
    }

    // ------------------------------------------------------------ families, resolvers, capabilities

    @Test fun engineFamiliesComeFromBridgesAndProxyType() {
        val p = VlessProfile(id = "x", name = "x", address = "a", port = 1, uuid = "", profileType = ProfileType.VLESS)
        assertEquals(PathFamily.TOR_WEBTUNNEL, PathFamily.of(p, "tor", "webtunnel"))
        assertEquals(PathFamily.TOR_OBFS4, PathFamily.of(p, "tor", "obfs4"))
        assertEquals(PathFamily.TOR_SNOWFLAKE, PathFamily.of(p, "tor", null))
        assertEquals(PathFamily.TOR, PathFamily.of(p, "tor", "meek_lite"))
        assertEquals(PathFamily.AMNEZIAWG, PathFamily.of(p, "mihomo", "wireguard+awg"))
        assertEquals(PathFamily.WIREGUARD, PathFamily.of(p, "mihomo", "wireguard"))
        assertEquals(PathFamily.MIHOMO, PathFamily.of(p, "mihomo", "vless"))
        assertTrue(PathFamily.AMNEZIAWG.udp && PathFamily.AMNEZIAWG.engine)
    }

    @Test fun resolverBurstMeasuresLossJitterAndRateLimiting() {
        val clean = ResolverIntelligence.burstOf("r", listOf(40L, 50L, 40L, 50L, 40L, 50L, 40L, 50L), 1L)
        assertEquals(0, clean.lossPercent)
        assertEquals(10L, clean.jitterMs)
        assertFalse(clean.rateLimited)
        val limited = ResolverIntelligence.burstOf("r", listOf(40L, 40L, 40L, 40L, 40L, null, null, null, null, null), 1L)
        assertEquals(50, limited.lossPercent)
        assertTrue(limited.rateLimited)
        assertEquals(PathStatus.DEGRADED, FullAnalysis.burstPath(limited).status)
        assertEquals(PathStatus.FAILED, FullAnalysis.burstPath(ResolverIntelligence.burstOf("r", listOf(null, null), 1L)).status)
        fun r(txt: Boolean?, edns: Int?, hijack: Boolean = false, udp: Boolean = true) =
            ResolverIntelligence.Result("r", "x", "public", "n", udp, false, 10, false, hijack, edns, 0, 10, aaaa = true, txt = txt)
        assertEquals(ResolverIntelligence.DnsStatus.DNS_TUNNEL_CANDIDATE, r(true, 1232).status)
        assertEquals(ResolverIntelligence.DnsStatus.DNS_HEALTHY, r(false, 1232).status)
        assertEquals(ResolverIntelligence.DnsStatus.DNS_MANIPULATED, r(true, 1232, hijack = true).status)
        assertEquals(ResolverIntelligence.DnsStatus.DNS_FAILED, r(null, null, udp = false).status)
        // Probes never claim recursion or a working tunnel.
        assertTrue(listOf(r(true, 1232), r(false, null)).none { it.status == ResolverIntelligence.DnsStatus.DNS_TUNNEL_VERIFIED || it.status == ResolverIntelligence.DnsStatus.RECURSION_VERIFIED })
        assertEquals(r(true, 1232), ResolverIntelligence.Result.fromJson(r(true, 1232).toJson()))
    }

    @Test fun capabilityMatrixNeverShowsUnprovenFeaturesAsWorking() {
        assertTrue(CapabilityMatrix.ROWS.all { row -> CapabilityMatrix.Layer.entries.all { row.values.containsKey(it) } && row.evidence.isNotBlank() })
        assertTrue(CapabilityMatrix.ROWS.none { it.provenWorking })
        val awg = CapabilityMatrix.byId("amneziawg")!!
        assertEquals(CapabilityMatrix.Value.YES, awg.at(CapabilityMatrix.Layer.RUNTIME_PRESENT))
        assertEquals(CapabilityMatrix.Value.NO, awg.at(CapabilityMatrix.Layer.RUNTIME_TRAFFIC_VERIFIED))
        assertEquals(CapabilityMatrix.Value.UNKNOWN, CapabilityMatrix.byId("ech")!!.at(CapabilityMatrix.Layer.RUNTIME_TRAFFIC_VERIFIED))
        assertFalse(CoreCapabilityRegistry.byId("amneziawg")!!.usable)
    }

    @Test fun reportKeepsFirstWorkingPathAndPlanChanges() {
        val r = AnalysisReport("NS-1", "wifi:abc|IPv4", "Wi-Fi · abc", 1, 2, "Severe filtering", 0.6, "Emergency recovery", listOf("why"), emptyList(),
            emptyList(), emptyList(), listOf("MTU"), "Use a.", firstWorking = "a", events = listOf("A UDP method passed: UDP families raised."), stopReason = "every candidate was tested")
        assertEquals(r, AnalysisReport.fromJson(JSONObject(r.toJson().toString())))
        // Reports saved before V5 still load.
        val old = JSONObject(r.toJson().toString()).apply { remove("fw"); remove("ev"); remove("sr") }
        assertNull(AnalysisReport.fromJson(old)!!.firstWorking)
    }
}
