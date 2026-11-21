package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.EngineUdp
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.SidecarChain
import com.example.vpn.sidecar.SidecarContext
import com.example.vpn.sidecar.SidecarLaunch
import com.example.vpn.sidecar.TorSidecar
import com.example.vpn.verify.Dialer
import com.example.vpn.verify.TrafficCheck
import com.example.vpn.verify.VideoPathProbe
import com.example.xray.XrayConfigBuilder
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Psiphon carried Telegram but not YouTube (build 165, Irancell). Psiphon's local proxy is SOCKS CONNECT only,
 * so QUIC must end inside Xray, app DNS must not depend on UDP or a resolver round trip through Psiphon, and
 * nothing may be split-tunneled around Psiphon.
 */
class PsiphonYoutubePathTest {
    private val ctx = SidecarContext(File("."), File("unused"), 12345, "u", "p", OperationalMode.DAILY)

    private fun xray(profile: com.example.data.model.VlessProfile, dns: String = "https://8.8.8.8/dns-query",
                     ipv6: Boolean = false): JSONObject {
        val forwarding = SidecarChain.xrayProfile(profile, ctx, SidecarLaunch(emptyList()))
        return JSONObject(XrayConfigBuilder.buildJson(forwarding, AppSettings(dnsServer = dns, ipv6Enabled = ipv6)))
    }

    private fun rules(config: JSONObject): List<JSONObject> {
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        return List(rules.length()) { rules.getJSONObject(it) }
    }

    private fun outbound(config: JSONObject, tag: String): JSONObject? {
        val outbounds = config.getJSONArray("outbounds")
        return (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.firstOrNull { it.optString("tag") == tag }
    }

    @Test fun psiphonEndsQuicLocallyBeforeAnyOtherRule() {
        val config = xray(PsiphonSidecar.profile())
        val first = rules(config).first()
        assertEquals("udp", first.getString("network"))
        assertEquals("443", first.getString("port"))
        assertEquals(EngineUdp.QUIC_BLOCK_TAG, first.getString("outboundTag"))
        assertEquals("blackhole", outbound(config, EngineUdp.QUIC_BLOCK_TAG)!!.getString("protocol"))
        // DNS still reaches Xray's DNS outbound; the proxy stays the first outbound.
        assertTrue(rules(config).any { it.optString("port") == "53" && it.optString("outboundTag") == "private-dns-out" })
        assertEquals("socks", config.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
        assertEquals(1, rules(config).count { it.optString("outboundTag") == EngineUdp.QUIC_BLOCK_TAG })
    }

    @Test fun psiphonAnswersAppDnsWithFakeDnsSoPsiphonResolvesTheName() {
        val config = xray(PsiphonSidecar.profile())
        assertEquals("fakedns", config.getJSONObject("dns").getJSONArray("servers").getString(0))
        assertEquals("AsIs", config.getJSONObject("routing").getString("domainStrategy"))
        assertEquals("198.18.0.0/15", config.getJSONArray("fakedns").getJSONObject(0).getString("ipPool"))
    }

    @Test fun aPlainUdpResolverIsAskedOverTcpThroughATcpOnlyEngine() {
        val config = xray(PsiphonSidecar.profile(), dns = "8.8.8.8")
        val servers = config.getJSONObject("dns").getJSONArray("servers")
        assertEquals("tcp://8.8.8.8:53", servers.getJSONObject(1).getString("address"))
        val tor = xray(TorSidecar.profile(), dns = "1.1.1.1")
        assertEquals("tcp://1.1.1.1:53", tor.getJSONObject("dns").getJSONArray("servers").getString(0))
    }

    @Test fun torAlsoEndsQuicButKeepsItsResolver() {
        val config = xray(TorSidecar.profile())
        assertEquals(EngineUdp.QUIC_BLOCK_TAG, rules(config).first().getString("outboundTag"))
        assertFalse(config.has("fakedns"))
        assertEquals("https://8.8.8.8/dns-query", config.getJSONObject("dns").getJSONArray("servers").getString(0))
    }

    @Test fun enginesWithoutAKnownUdpLimitAreUnchanged() {
        // Build 167 moved Aether out of this group (QUIC ended locally; see Build167DiagTest).
        val config = xray(com.example.vpn.sidecar.RadianceSidecar.profile())
        assertTrue(rules(config).none { it.optString("outboundTag") == EngineUdp.QUIC_BLOCK_TAG })
        assertEquals(null, outbound(config, EngineUdp.QUIC_BLOCK_TAG))
        assertEquals(EngineUdp.Capability.UNKNOWN, EngineUdp.of(com.example.vpn.sidecar.RadianceSidecar.profile()))
        assertEquals(EngineUdp.Capability.CARRIES_UDP, EngineUdp.of(AetherSidecar.profile()))
        assertEquals(EngineUdp.Capability.TCP_ONLY, EngineUdp.of(PsiphonSidecar.profile()))
        assertEquals(EngineUdp.Capability.TCP_ONLY, EngineUdp.of(TorSidecar.profile()))
        assertTrue(EngineUdp.remoteDns(PsiphonSidecar.profile(region = "DE", tcpOnly = true)))
        assertFalse(EngineUdp.remoteDns(TorSidecar.profile()))
    }

    @Test fun quicBlockIsIdempotent() {
        val root = JSONObject().put("outbounds", JSONArray().put(JSONObject().put("tag", "proxy").put("protocol", "socks")))
            .put("routing", JSONObject().put("rules", JSONArray().put(JSONObject().put("port", "53").put("outboundTag", "dns"))))
        EngineUdp.applyQuicBlock(root)
        EngineUdp.applyQuicBlock(root)
        assertEquals(2, root.getJSONObject("routing").getJSONArray("rules").length())
        assertEquals(2, root.getJSONArray("outbounds").length())
    }

    @Test fun psiphonNeverSplitTunnelsAroundItself() {
        val settings = JSONObject()
            .put("PropagationChannelId", "C").put("SponsorId", "S")
            .put("SplitTunnelOwnRegion", true)
            .put("SplitTunnelRegions", JSONArray().put("IR"))
        val config = PsiphonSidecar.buildConfig(settings, "", 5000, File("/data/x"))
        assertFalse(config.getBoolean("SplitTunnelOwnRegion"))
        assertFalse(config.has("SplitTunnelRegions"))
    }

    @Test fun videoProbeReportsEachLegAndTheUdpPath() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/generate_204" -> MockResponse().setResponseCode(204)
                "/report_mapping" -> MockResponse().setBody("mapping")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val direct = Dialer { host, port, timeoutMs -> Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) } }
            fun t(path: String) = TrafficCheck.Target("127.0.0.1", server.port, path)
            val udp = EngineUdp.describe(PsiphonSidecar.ID)
            val ok = VideoPathProbe(direct, 3_000, tls = false,
                targets = listOf("youtube" to t("/generate_204"), "googlevideo" to t("/report_mapping"))).run(udp, "remote")
            assertTrue(ok.describe(), ok.passed)
            assertEquals("reachable", ok.verdict)
            val attrs = ok.attributes(PsiphonSidecar.ID)
            assertEquals("PASS", attrs["youtube.result"])
            assertEquals("PASS", attrs["googlevideo.result"])
            assertTrue(attrs.getValue("udp").contains("SOCKS CONNECT only"))

            val partial = VideoPathProbe(direct, 3_000, tls = false,
                targets = listOf("youtube" to t("/generate_204"), "googlevideo" to t("/missing"))).run(udp, "remote")
            assertFalse(partial.passed)
            assertEquals("partial", partial.verdict)
            assertEquals("FAIL", partial.attributes(PsiphonSidecar.ID)["googlevideo.result"])
        } finally {
            server.shutdown()
        }
        val dead = VideoPathProbe(Dialer { _, _, _ -> throw java.net.ConnectException("refused") }, 1_000, tls = false)
            .run("none", "remote")
        assertEquals("unreachable", dead.verdict)
        assertTrue(dead.legs.all { it.error!!.contains("refused") })
    }
}
