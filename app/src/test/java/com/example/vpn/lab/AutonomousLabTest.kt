package com.example.vpn.lab

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.smart.NetworkCapabilityProfile
import com.example.xray.RealDelayProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AutonomousLabTest {
    private val open = NetworkCapabilityProfile(
        transport = "cellular", ipv4Available = true, udpAvailable = true, tcpAvailable = true, tlsAvailable = true,
        cloudflareReachable = true, dnsWorking = true, dnsManipulated = false, dohReachable = true,
        internationalOk = 3, internationalTried = 3, domesticReachable = true, sniFiltered = false, measuredAt = 5L
    )
    private val nin = open.copy(internationalOk = 0, tcpAvailable = false, tlsAvailable = false, cloudflareReachable = false)
    private fun reading(p: NetworkCapabilityProfile) = NetworkStateClassifier.classify(p, now = 1L)
    private fun profile(id: String, transport: String = "tcp", security: String = "tls", type: ProtocolType = ProtocolType.VLESS, fp: String = id) =
        VlessProfile(id = id, canonicalFingerprint = fp, name = "cfg-$id", address = "203.0.113.1", port = 443, uuid = "u", transport = transport,
            security = security, protocolType = type)

    // ------------------------------------------------------------ planner

    @Test fun nationalNetworkPlansDiverseRecoveryNeverStop() {
        val dnsEgress = ExperimentPlanner.plan(reading(nin.copy(recursiveDnsEgress = true)), nin, 15)
        assertEquals(ExperimentPlanner.Mode.EMERGENCY_RECOVERY, dnsEgress.mode)
        assertEquals(PathFamily.DNS_TUNNEL, dnsEgress.prefer.first())
        assertTrue(dnsEgress.budget > 0)
        assertFalse(PathFamily.DNS_TUNNEL in dnsEgress.skip)
        // Diversity: Psiphon, every Tor bridge kind, Mihomo and WireGuard-style UDP are all in the plan.
        assertTrue(dnsEgress.prefer.containsAll(listOf(PathFamily.PSIPHON, PathFamily.TOR_WEBTUNNEL, PathFamily.TOR_OBFS4, PathFamily.TOR_SNOWFLAKE, PathFamily.MIHOMO, PathFamily.AMNEZIAWG)))
        // Domestic only: ordinary configs are not skipped, only tried last with a low prior.
        val domestic = ExperimentPlanner.plan(reading(nin), nin, 15)
        assertEquals(ExperimentPlanner.Mode.EMERGENCY_RECOVERY, domestic.mode)
        assertFalse(PathFamily.VLESS_REALITY in domestic.skip)
        assertTrue(PathFamily.VLESS_REALITY in domestic.lowPrior)
        // Nothing answers abroad or at home: still recovery, never a stop with a zero budget.
        val none = ExperimentPlanner.plan(reading(nin.copy(domesticReachable = false)), nin, 15)
        assertEquals(ExperimentPlanner.Mode.EMERGENCY_RECOVERY, none.mode)
        assertEquals(15, none.budget)
        // DNS and UDP dead: Psiphon and Tor go before the DNS tunnel; UDP families are left out.
        val dead = nin.copy(dnsWorking = false, udpAvailable = false, domesticReachable = false)
        val order = ExperimentPlanner.emergencyOrder(dead)
        assertTrue(order.indexOf(PathFamily.PSIPHON) < order.indexOf(PathFamily.DNS_TUNNEL))
        assertFalse(PathFamily.AMNEZIAWG in order)
        // QUIC answering keeps UDP families in play even when direct UDP DNS failed.
        assertTrue(PathFamily.AMNEZIAWG in ExperimentPlanner.emergencyOrder(dead.copy(quicStatus = "QUIC_AVAILABLE")))
    }

    @Test fun udpBlockedSkipsUdpFamiliesAndPrefersTcp() {
        val p = ExperimentPlanner.plan(reading(open.copy(udpAvailable = false)), open, 15)
        assertEquals(ExperimentPlanner.Mode.ORDINARY, p.mode)
        assertTrue(PathFamily.HYSTERIA2 in p.skip && PathFamily.WIREGUARD in p.skip && PathFamily.TUIC in p.skip)
        assertEquals(PathFamily.VLESS_REALITY, p.prefer.first())
        // Engines go last.
        assertEquals(listOf(PathFamily.PSIPHON) + PathFamily.TOR_FAMILIES, p.prefer.takeLast(5))
        // QUIC answering means UDP is not dead: UDP families are not skipped.
        val quic = open.copy(udpAvailable = false, quicStatus = "QUIC_AVAILABLE")
        assertFalse(PathFamily.HYSTERIA2 in ExperimentPlanner.plan(reading(quic), quic, 15).skip)
    }

    @Test fun budgetIsLargerForUserRunsAndSmallerOnBatteryOrMetered() {
        assertEquals(15, ExperimentPlanner.budget(ExperimentPlanner.Budget(true, 80, false, false)))
        assertEquals(4, ExperimentPlanner.budget(ExperimentPlanner.Budget(false, 80, false, false)))
        assertEquals(10, ExperimentPlanner.budget(ExperimentPlanner.Budget(true, 80, false, true)))
        assertEquals(4, ExperimentPlanner.budget(ExperimentPlanner.Budget(true, 10, false, false)))
        assertEquals(15, ExperimentPlanner.budget(ExperimentPlanner.Budget(true, 10, true, false)))
    }

    // ------------------------------------------------------------ selection and ranking

    @Test fun selectionTakesOnePerFamilyFirstAndHonoursSkips() {
        val profiles = listOf(
            profile("t1") to PathFamily.VLESS_TLS, profile("t2") to PathFamily.VLESS_TLS, profile("t3") to PathFamily.VLESS_TLS,
            profile("r1", security = "reality") to PathFamily.VLESS_REALITY,
            profile("h1", type = ProtocolType.HYSTERIA2) to PathFamily.HYSTERIA2
        )
        val plan = ExperimentPlanner.Plan(ExperimentPlanner.Mode.ORDINARY, listOf(PathFamily.VLESS_REALITY), mapOf(PathFamily.HYSTERIA2 to "udp"), 3, emptyList())
        val picks = FullAnalysis.select(profiles, plan).map { it.profile.id }
        assertEquals(listOf("r1", "t1", "t2"), picks)
        // Memory only reorders: a config proven here goes first inside its family.
        val proven = FullAnalysis.select(profiles, plan, provenHere = setOf("t3")).map { it.profile.id }
        assertEquals(listOf("r1", "t3", "t1"), proven)
        assertTrue(FullAnalysis.select(profiles, plan.copy(budget = 0)).isEmpty())
    }

    @Test fun rankingPutsRealTrafficThenStabilityThenLatency() {
        fun m(id: String, stage: ConnectionStage, passes: Int, latency: Long?, family: PathFamily = PathFamily.VLESS_TLS) =
            RankedMethod(id, id, family, PathStatus.CANDIDATE, stage, latency, null, "", passes, passes)
        val ranked = FullAnalysis.rank(listOf(
            m("failed", ConnectionStage.NOT_TESTED, 0, null),
            m("fast-once", ConnectionStage.APPLICATION_REQUEST_PASSED, 1, 50),
            m("slow-twice", ConnectionStage.STABILITY_VERIFIED, 2, 400),
            m("fast-twice", ConnectionStage.STABILITY_VERIFIED, 2, 100)
        ), emptyList()).map { it.profileId }
        assertEquals(listOf("fast-twice", "slow-twice", "fast-once", "failed"), ranked)
        assertNull(FullAnalysis.confidence(0, 0))
        assertEquals(0.0, FullAnalysis.confidence(0, 2)!!, 0.0)
        assertTrue(FullAnalysis.confidence(2, 2)!! < 1.0)
    }

    @Test fun livePathsSayNotTestedInsteadOfGuessing() {
        val rows = FullAnalysis.networkPaths(open, 10L).associateBy { it.key }
        assertEquals(PathStatus.AVAILABLE, rows.getValue("dns-plain").status)
        assertEquals(PathStatus.NOT_TESTED, rows.getValue("dns-egress").status)
        assertEquals(PathStatus.NOT_TESTED, rows.getValue("dns-dot").status)
        assertEquals(PathStatus.NOT_TESTED, rows.getValue("quic").status)
        assertEquals(PathStatus.BLOCKED, FullAnalysis.networkPaths(open.copy(dnsManipulated = true), 10L).first { it.key == "dns-plain" }.status)
        assertEquals(PathStatus.UNSUPPORTED, FullAnalysis.networkPaths(open.copy(ipv6Available = false), 10L).first { it.key == "ipv6" }.status)
        assertTrue(FullAnalysis.untested(open, reading(open)).any { it.startsWith("Recursive DNS egress") })
        assertTrue(FullAnalysis.untested(open, reading(open)).any { it.startsWith("External DNS leak") })
    }

    @Test fun familyRowsComeFromTestsOnly() {
        val plan = ExperimentPlanner.Plan(ExperimentPlanner.Mode.ORDINARY, emptyList(), mapOf(PathFamily.HYSTERIA2 to "UDP blocked"), 5, emptyList())
        val results = listOf(
            RankedMethod("a", "a", PathFamily.VLESS_TLS, PathStatus.VERIFIED, ConnectionStage.STABILITY_VERIFIED, 90, 0.9, "", 2, 2),
            RankedMethod("b", "b", PathFamily.WEBSOCKET, PathStatus.FAILED, ConnectionStage.NOT_TESTED, null, 0.0, "TLS cut", 1, 0)
        )
        val rows = FullAnalysis.familyPaths(setOf(PathFamily.VLESS_TLS, PathFamily.WEBSOCKET, PathFamily.HYSTERIA2, PathFamily.GRPC), results, plan, emptyMap(), 1L)
            .associateBy { it.key.removePrefix("family-") }
        assertEquals(PathStatus.VERIFIED, rows.getValue("VLESS_TLS").status)
        assertEquals(PathStatus.FAILED, rows.getValue("WEBSOCKET").status)
        // A skip is a planning choice, never shown as a measured block.
        assertEquals(PathStatus.NOT_TESTED, rows.getValue("HYSTERIA2").status)
        assertEquals(PathStatus.NOT_TESTED, rows.getValue("GRPC").status)
    }

    @Test fun reportRoundTrips() {
        val r = AnalysisReport("NS-1", "cell:1", "Mobile data", 1, 2, "Normal", 0.8, "ordinary", listOf("why"), listOf("UDP blocked"),
            FullAnalysis.networkPaths(open, 3), listOf(RankedMethod("a", "a", PathFamily.VLESS_TLS, PathStatus.CANDIDATE, ConnectionStage.APPLICATION_REQUEST_PASSED, 90, 0.9, "ok", 1, 1)),
            listOf("MTU"), "Use a.")
        assertEquals(r, AnalysisReport.fromJson(JSONObject(r.toJson().toString())))
        assertEquals("a", r.best?.profileId)
    }

    @Test fun freshnessAges() {
        assertEquals(Freshness.FRESH, Freshness.of(0, 3_600_000))
        assertEquals(Freshness.AGING, Freshness.of(0, 10 * 3_600_000L))
        assertEquals(Freshness.STALE, Freshness.of(0, 48 * 3_600_000L))
        assertEquals(Freshness.EXPIRED, Freshness.of(0, 100 * 3_600_000L))
    }

    @Test fun familiesComeFromFieldsNotNames() {
        assertEquals(PathFamily.VLESS_REALITY, PathFamily.of(profile("x", security = "reality")))
        assertEquals(PathFamily.XHTTP, PathFamily.of(profile("x", transport = "xhttp")))
        assertEquals(PathFamily.HYSTERIA2, PathFamily.of(profile("x", type = ProtocolType.HYSTERIA2)))
        assertEquals(PathFamily.DNS_TUNNEL, PathFamily.of(profile("x"), "dns-tunnel"))
        assertEquals(PathFamily.MIHOMO, PathFamily.of(profile("x"), "mihomo"))
        assertFalse(ConnectionStage.LOCAL_PROXY_READY.carriesTraffic)
        assertTrue(ConnectionStage.APPLICATION_REQUEST_PASSED.carriesTraffic)
    }

    // ------------------------------------------------------------ DNS tunnel lifecycle

    @Test fun onlyApplicationTrafficCountsAsConnected() {
        val ready = EngineProbe.judge("dns-tunnel", ready = true, outcome = RealDelayProbe.Outcome.Failed("timeout"))
        assertEquals(ConnectionStage.LOCAL_PROXY_READY, ready.stage)
        assertEquals(EngineProbe.DnsTunnelStage.LOCAL_PROXY_READY, EngineProbe.dnsTunnelStage(ready))
        assertFalse(ready.stage.carriesTraffic)
        val passed = EngineProbe.judge("dns-tunnel", ready = true, outcome = RealDelayProbe.Outcome.Delay(900))
        assertEquals(EngineProbe.DnsTunnelStage.APPLICATION_TRAFFIC_VERIFIED, EngineProbe.dnsTunnelStage(passed))
        assertEquals(900L, passed.latencyMs)
        assertTrue(EngineProbe.judge("tor", ready = true, outcome = RealDelayProbe.Outcome.NotRun("VPN running")).notTested)
        assertEquals(ConnectionStage.NOT_TESTED, EngineProbe.judge("tor", ready = false, outcome = null).stage)
        assertEquals(EngineProbe.DnsTunnelStage.ENGINE_UNAVAILABLE, EngineProbe.dnsTunnelStage(null))
        // Outcomes follow the stage.
        assertEquals(listOf(true), passed.outcomes)
        assertEquals(listOf(false), ready.outcomes)
        assertTrue(EngineProbe.judge("tor", ready = true, outcome = RealDelayProbe.Outcome.NotRun("VPN running")).outcomes.isEmpty())
    }

    // ------------------------------------------------------------ resolver intelligence

    private fun answer(rcode: Int, vararg ips: String) = DnsWire.Answer(1, rcode, false, ips.size, ips.map { InetAddress.getByName(it) }, 64)

    @Test fun dnsWireRoundTrip() {
        val q = DnsWire.query(0x1234, "www.google.com", DnsWire.TYPE_A, ednsSize = 1232)
        // A query is not a response.
        assertNull(DnsWire.parse(q, q.size))
        // Turn it into a response with one A record.
        val resp = q.copyOf(q.size - 11).also {
            it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1; it[11] = 0
        } + byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 142.toByte(), 250.toByte(), 185.toByte(), 4)
        val a = DnsWire.parse(resp, resp.size)
        assertNotNull(a)
        a!!
        assertEquals(0x1234, a.id)
        assertEquals(DnsWire.RCODE_NOERROR, a.rcode)
        assertEquals(listOf(InetAddress.getByName("142.250.185.4")), a.addresses)
        assertNull(DnsWire.parse(ByteArray(4), 4))
    }

    @Test fun resolverJudgementSpotsBlockPagesAndHijacks() {
        assertEquals(false to false, ResolverIntelligence.judge(answer(0, "142.250.185.4"), answer(DnsWire.RCODE_NXDOMAIN)))
        assertEquals(true to null, ResolverIntelligence.judge(answer(0, "10.10.34.35"), null))
        assertEquals(false to true, ResolverIntelligence.judge(answer(0, "142.250.185.4"), answer(0, "93.184.216.34")))
        assertEquals(null to null, ResolverIntelligence.judge(null, null))
    }

    @Test fun resolversRankHonestFirstAndStayOnTheirNetwork() {
        fun r(label: String, udp: Boolean, hijack: Boolean, rtt: Long) = ResolverIntelligence.Result(label, "x", "public", "net-a", udp, false, rtt, false, hijack, null, 0, 10)
        val ranked = ResolverIntelligence.rank(listOf(r("hijacker", true, true, 5), r("slow", true, false, 300), r("fast", true, false, 40), r("dead", false, false, 1)))
        assertEquals(listOf("fast", "slow"), ranked.take(2).map { it.label })
        assertFalse(ranked.first { it.label == "hijacker" }.usable)
        val store = LabStore(load = { null }, save = {}, clock = { 5L })
        store.saveResolvers("net-a", ranked)
        assertEquals(4, store.resolversFor("net-a", now = 5).size)
        assertTrue(store.resolversFor("net-b", now = 5).isEmpty())
        // Expired results are never returned.
        assertTrue(store.resolversFor("net-a", now = 11).isEmpty())
        val c = ResolverIntelligence.candidates(listOf(InetAddress.getByName("192.168.1.1"), InetAddress.getByName("1.1.1.1")))
        assertEquals("192.168.1.1", c.first().address)
        assertEquals(c.size, c.distinctBy { it.address }.size)
        assertEquals(ranked.first(), ResolverIntelligence.Result.fromJson(ranked.first().toJson()))
    }

    @Test fun storeKeepsOneReportPerNetwork() {
        var saved: String? = null
        val store = LabStore(load = { saved }, save = { saved = it })
        fun rep(key: String, t: Long) = AnalysisReport("NS", key, key, t, t, "Normal", 0.5, "m", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "")
        store.saveReport(rep("a", 1)); store.saveReport(rep("a", 2)); store.saveReport(rep("b", 3))
        assertEquals(listOf("b", "a"), store.reports().map { it.contextKey })
        assertEquals(2L, store.reportFor("a")?.finishedAt)
        assertEquals(2, LabStore(load = { saved }, save = {}).reports().size)
    }
}
