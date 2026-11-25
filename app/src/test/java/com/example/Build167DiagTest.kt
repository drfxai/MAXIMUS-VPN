package com.example

import com.example.data.model.AppSettings
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import com.example.vpn.ConnectedNotification
import com.example.vpn.connections.AetherStartupStall
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.EngineUdp
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.RadianceSidecar
import com.example.vpn.sidecar.SidecarChain
import com.example.vpn.sidecar.SidecarContext
import com.example.vpn.sidecar.SidecarLaunch
import com.example.vpn.sidecar.SidecarProcess
import com.example.vpn.smart.StallFailover
import com.example.vpn.verify.Dialer
import com.example.vpn.verify.LegResult
import com.example.vpn.verify.ProbePath
import com.example.vpn.verify.TrafficCheck
import com.example.vpn.verify.TrafficEvidence
import com.example.vpn.verify.VideoPathProbe
import com.example.xray.XrayConfigBuilder
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Build 167 (Irancell cf=0, then an unmeasured non-metered network): Aether carried everything but YouTube, the
 * googlevideo probe failed while YouTube played, Aether waited 180 s + H3 on a network with nothing remembered, and a
 * pinned Radiance session whose bulk check stalled offered no way out.
 */
class Build167DiagTest {
    private val ctx = SidecarContext(File("."), File("unused"), 12345, "u", "p", OperationalMode.DAILY)

    private fun aether(carrier: String, extra: (JSONObject) -> Unit = {}): VlessProfile {
        val base = AetherSidecar.profile()
        return base.copy(extraSettings = JSONObject(base.extraSettings).put("carrier", carrier).also(extra).toString())
    }

    private fun xray(profile: VlessProfile, dns: String = "8.8.8.8"): JSONObject {
        val forwarding = SidecarChain.xrayProfile(profile, ctx, SidecarLaunch(emptyList()))
        return JSONObject(XrayConfigBuilder.buildJson(forwarding, AppSettings(dnsServer = dns, ipv6Enabled = false)))
    }

    private fun firstRule(config: JSONObject): JSONObject = config.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)

    // ---- 1. Aether + YouTube -------------------------------------------------------------------------------

    @Test fun aetherCarriesUdpButEndsQuicLocallyOnEveryCarrier() {
        for (carrier in listOf("auto", "h2", "h3", "wireguard", "gool", "mim")) {
            val profile = aether(carrier)
            assertEquals(carrier, EngineUdp.Capability.CARRIES_UDP, EngineUdp.of(profile))
            assertTrue(carrier, EngineUdp.quicEndedLocally(profile))
            val rule = firstRule(xray(profile))
            assertEquals(carrier, EngineUdp.QUIC_BLOCK_TAG, rule.getString("outboundTag"))
            assertEquals("udp", rule.getString("network"))
            assertEquals("443", rule.getString("port"))
        }
    }

    @Test fun aetherKeepsFakeDnsAndAsksItsFallbackResolverOverTcpOnlyOnHttp2Carriers() {
        val h2 = xray(aether("h2")).getJSONObject("dns").getJSONArray("servers")
        assertEquals("fakedns", h2.getString(0))
        assertEquals("tcp://8.8.8.8:53", h2.getJSONObject(1).getString("address"))
        val h3 = xray(aether("h3")).getJSONObject("dns").getJSONArray("servers")
        assertEquals("fakedns", h3.getString(0))
        assertEquals("8.8.8.8", h3.getJSONObject(1).getString("address"))
        assertTrue(EngineUdp.resolverOverTcp(aether("gool")))
        assertFalse(EngineUdp.resolverOverTcp(aether("wireguard")))
    }

    @Test fun aetherWhoseTorOrPsiphonHopOwnsThePortIsConnectOnly() {
        val chained = aether("h2") { it.put("psiphon", "chain") }
        assertEquals(EngineUdp.Capability.TCP_ONLY, EngineUdp.of(chained))
        assertTrue(EngineUdp.describe(chained).contains("SOCKS CONNECT only"))
        val torOnly = aether("h2") { it.put("tor", "only") }
        assertEquals(EngineUdp.Capability.TCP_ONLY, EngineUdp.of(torOnly))
    }

    @Test fun aetherSessionsGetTheYoutubeProbeAndSayWhatUdpAndDnsDo() {
        assertTrue(EngineUdp.videoProbed(AetherSidecar.ID))
        assertTrue(EngineUdp.videoProbed(PsiphonSidecar.ID))
        assertFalse(EngineUdp.videoProbed(RadianceSidecar.id))
        assertTrue(EngineUdp.describe(aether("h2")).contains("QUIC/UDP 443 ended locally"))
        assertTrue(EngineUdp.dnsPath(aether("h2")).contains("through WARP"))
        assertTrue(EngineUdp.dnsPath(PsiphonSidecar.profile()).contains("psiphon on its server"))
    }

    @Test fun radianceNowEndsQuicLocallyAndAnswersAppNamesWithFakeDns() {
        // 2026-11-25 engine test: Lantern alone gave the phone no DNS (curl exit 6) while Psiphon over Lantern worked.
        val config = xray(RadianceSidecar.profile())
        assertEquals(EngineUdp.QUIC_BLOCK_TAG, firstRule(config).optString("outboundTag"))
        assertTrue(EngineUdp.quicEndedLocally(RadianceSidecar.profile()))
        assertTrue(config.has("fakedns"))
    }

    // ---- 2. googlevideo probe ------------------------------------------------------------------------------

    private val direct = Dialer { host, port, timeoutMs -> Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) } }

    @Test fun aLargeOrEndlessVideoAnswerPassesOnItsFirstBytes() {
        val server = MockWebServer()
        val big = Buffer().write(ByteArray(2 * 1024 * 1024) { 'v'.code.toByte() })
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/generate_204" -> MockResponse().setResponseCode(204)
                // Chunked and far over the old 640 KB cap: the build 167 "Response too long".
                "/report_mapping" -> MockResponse().setChunkedBody(big.clone(), 16 * 1024)
                "/ranged" -> MockResponse().setResponseCode(206).setBody(Buffer().write(ByteArray(32 * 1024)))
                "/empty" -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            fun t(path: String) = TrafficCheck.Target("127.0.0.1", server.port, path)
            val ok = VideoPathProbe(direct, 5_000, tls = false,
                targets = listOf("youtube" to t("/generate_204"), "googlevideo" to t("/report_mapping"))).run("u", "d")
            assertTrue(ok.describe(), ok.passed)
            assertEquals("reachable", ok.verdict)
            // Every leg asked for the first bytes only.
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("bytes=0-${VideoPathProbe.MAX_BYTES - 1}", first.getHeader("Range"))

            val ranged = VideoPathProbe(direct, 5_000, tls = false, targets = listOf("googlevideo" to t("/ranged"))).run("u", "d")
            assertTrue(ranged.describe(), ranged.passed)

            val empty = VideoPathProbe(direct, 5_000, tls = false, targets = listOf("googlevideo" to t("/empty"))).run("u", "d")
            assertFalse(empty.passed)
            assertEquals("HTTP 200 with no data", empty.legs.single().error)
        } finally {
            server.shutdown()
        }
    }

    @Test fun aSilentServerFailsAtTheLegDeadlineNotLater() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val started = System.currentTimeMillis()
            val r = VideoPathProbe(direct, 1_000, tls = false,
                targets = listOf("googlevideo" to TrafficCheck.Target("127.0.0.1", server.port, "/x"))).run("u", "d")
            assertFalse(r.passed)
            assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 5_000)
        } finally {
            server.shutdown()
        }
    }

    @Test fun videoVerdicts() {
        assertNull(VideoPathProbe.verdict(204, 0))
        assertNull(VideoPathProbe.verdict(200, 1))
        assertNull(VideoPathProbe.verdict(206, 32 * 1024))
        assertEquals("HTTP 200 with no data", VideoPathProbe.verdict(200, 0))
        assertEquals("HTTP 403", VideoPathProbe.verdict(403, 10))
        assertTrue(VideoPathProbe.LEG_TIMEOUT_MS >= 10_000)
    }

    // ---- 3. Aether startup on a network with nothing remembered ---------------------------------------------

    private fun applies(carrier: String = "h2", method: String = carrier, irancell: Boolean = false, filtered: Boolean = false,
                        remembered: Boolean = false, verified: Boolean = false, relay: Boolean = false, automatic: Boolean = true) =
        AetherStartupStall.applies(AetherSidecar.ID, automatic, carrier, method, irancell, filtered, remembered, verified, relay)

    @Test fun theStallCapAppliesOnlyWhenNothingIsKnownAboutTheNetwork() {
        assertTrue(applies())
        assertTrue(applies(carrier = "h3"))
        assertTrue(applies(method = ConnectionEngineRunner.H2_SPLIT))
        assertFalse("remembered gateway", applies(remembered = true))
        assertFalse("clean-h2 method", applies(method = ConnectionEngineRunner.CLEAN_H2))
        assertFalse("a method verified here", applies(verified = true))
        assertFalse("the Irancell ladder keeps its budget", applies(irancell = true))
        assertFalse("filtered links keep their fixed budgets", applies(filtered = true))
        assertFalse("relays", applies(relay = true))
        assertFalse("a carrier the user chose", applies(automatic = false))
        assertFalse("WireGuard", applies(carrier = "wireguard"))
        assertFalse(AetherStartupStall.applies("psiphon", true, "h2", "h2", false, false, false, false, false))
    }

    @Test fun progressKeepsTheBudgetAndATransportStallEndsIt() {
        val t0 = 1_000_000L
        // Registering / scanning: progress, the normal budget holds however long.
        assertFalse(AetherStartupStall.shouldGiveUp(t0 + 170_000, t0, "scan", null))
        assertFalse(AetherStartupStall.shouldGiveUp(t0 + 100_000, t0, "account", null))
        // Gateway picked, not serving: the build 167 case is cut at 45 s after the first transport stage.
        val transportAt = t0 + 11_000
        assertFalse(AetherStartupStall.shouldGiveUp(transportAt + 44_000, t0, "transport", transportAt))
        assertTrue(AetherStartupStall.shouldGiveUp(transportAt + AetherStartupStall.TRANSPORT_STALL_MS, t0, "transport", transportAt))
        // A later rescan does not reset the clock: the first transport time is what counts.
        assertTrue(AetherStartupStall.shouldGiveUp(transportAt + 60_000, t0, "scan", transportAt))
        // No stage line at all.
        assertFalse(AetherStartupStall.shouldGiveUp(t0 + 30_000, t0, null, null))
        assertTrue(AetherStartupStall.shouldGiveUp(t0 + 45_000, t0, null, null))
        assertTrue(AetherStartupStall.reason("transport").contains("did not start serving within 45s"))
        assertTrue(AetherStartupStall.TRANSPORT_STALL_MS in 30_000L..45_000L)
    }

    /** Aether's stdout as a stream the test writes to. */
    private class FakeAether : Process() {
        private val output = PipedInputStream()
        private val writer = PipedOutputStream(output)
        private val ended = CountDownLatch(1)
        override fun getInputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun exitValue(): Int { if (ended.count > 0) throw IllegalThreadStateException(); return 0 }
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun destroy() { writer.close(); ended.countDown() }
        fun line(value: String) { writer.write("$value\n".toByteArray()); writer.flush() }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(what, condition())
    }

    @Test fun theEngineReportsWhenItFirstPickedAGateway() {
        val child = FakeAether()
        val process = SidecarProcess(AetherSidecar.ID, child, 1, AetherSidecar.readyLine(1), logOutput = false)
        try {
            assertNull(process.startupStage)
            assertNull(process.transportStageSinceMs)
            child.line("MAXIMUS_AETHER_STAGE:account")
            waitFor("account stage") { process.startupStage == "account" }
            assertNull(process.transportStageSinceMs)
            child.line("MAXIMUS_AETHER_STAGE:transport")
            waitFor("transport stage") { process.transportStageSinceMs != null }
            val first = process.transportStageSinceMs!!
            Thread.sleep(20)
            child.line("MAXIMUS_AETHER_STAGE:scan")
            child.line("MAXIMUS_AETHER_STAGE:transport")
            waitFor("second transport") { process.startupStage == "transport" && process.transportGeneration >= 4 }
            assertEquals(first, process.transportStageSinceMs)
            // The runner's gate gives up at once on a stall that is already old.
            assertFalse(process.awaitReadyUnless(5_000) {
                AetherStartupStall.shouldGiveUp(first + AetherStartupStall.TRANSPORT_STALL_MS, first, process.startupStage, first)
            })
        } finally { process.stop() }
    }

    // ---- 4. Pinned sessions whose bulk check stalls ---------------------------------------------------------

    @Test fun aPinnedSessionStaysAutomaticallyButMovesWhenTheUserAsks() {
        val auto = StallFailover.decide(StallFailover.Input("rad", pinned = true, switchesSoFar = 0, candidates = listOf("rad", "nl")))
        assertTrue(auto is StallFailover.Decision.Stay)
        val asked = StallFailover.decide(StallFailover.Input("rad", pinned = true, switchesSoFar = 0, candidates = listOf("rad", "nl"),
            maxSwitches = Int.MAX_VALUE, userAsked = true))
        assertEquals(StallFailover.Decision.Race(setOf("rad")), asked)
        assertTrue(StallFailover.unpinsSession(asked, userAsked = true, exactSession = true))
        // Nothing saved to race (an engine like Radiance alone): the later Smart Connect stages.
        val alone = StallFailover.decide(StallFailover.Input("rad", pinned = true, switchesSoFar = 0, candidates = emptyList(),
            maxSwitches = Int.MAX_VALUE, userAsked = true))
        assertTrue(alone is StallFailover.Decision.Escalate)
    }

    @Test fun tradeModeKeepsItsExitEvenOnAPress() {
        val d = StallFailover.decide(StallFailover.Input("nl", pinned = true, switchesSoFar = 0, candidates = listOf("nl", "de"),
            userAsked = true, lockedExit = true))
        assertTrue(d is StallFailover.Decision.Stay)
        assertTrue(StallFailover.reason(d).contains("Trade mode"))
        assertFalse(StallFailover.unpinsSession(d, userAsked = true, exactSession = true))
        assertFalse(StallFailover.unpinsSession(StallFailover.Decision.Race(emptySet()), userAsked = false, exactSession = true))
    }

    @Test fun aPinnedSessionWhoseEngineCheckStalledShowsTheLimitedState() {
        // The Radiance case: ENGINE_PROXY download stalled at 45498 B, the session was not Smart Connect.
        val stalled = LegResult.Failed(FailureStage.HTTP_REQUEST_FAILED, 45_498, stalled = true, reason = "timeout")
        val passed = LegResult.Passed(bytes = 16 * 1024, elapsedMs = 800)
        val state = ConnectionState(status = ConnectionStatus.CONNECTED, attemptId = "a1", smartSession = false,
            trafficEvidence = TrafficEvidence("a1", 0, ProbePath.ENGINE_PROXY, stalled, passed, checkedAt = 1))
        assertTrue(state.bulkLimited)
        assertTrue(ConnectedNotification.contentLine(state.bulkLimited, "↓ 1 KB/s ↑ 0 B/s").startsWith(ConnectedNotification.LIMITED_LINE))
        assertEquals("speeds", ConnectedNotification.contentLine(false, "speeds"))
    }
}
