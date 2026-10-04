package com.example

import com.example.core.AppResult
import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vless.VlessParser
import com.example.vpn.engine.ConfigurationAdapter
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.engine.ProtocolLinks
import com.example.vpn.engine.RuntimeCapabilities
import com.example.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hysteria2, WireGuard, XHTTP, HTTPUpgrade and REALITY options on the bundled Xray core. */
class AntiCensorshipProtocolsTest {
    private val pin = "83562c74abf462d97c868964b45b1eff4997f95feca862d7d7f1d8c14e3bb31e"
    private val pinWithColons = pin.uppercase().chunked(2).joinToString(":")

    private fun proxyOutbound(profile: VlessProfile): JSONObject =
        JSONObject(XrayConfigBuilder.buildJson(profile, AppSettings())).getJSONArray("outbounds").getJSONObject(0)

    @Test fun hysteria2LinkKeepsObfsPinAndBandwidth() {
        val p = ProtocolLinks.parseHysteria2(
            "hysteria2://pa+ss%401@hy.example.com:8443/?sni=cdn.example.com&obfs=salamander" +
                "&obfs-password=obfs-secret&insecure=1&pinSHA256=$pinWithColons&upmbps=50&downmbps=100#My%20HY2"
        )
        assertEquals(ProtocolType.HYSTERIA2, p.protocolType)
        assertEquals(EngineType.XRAY, p.engineType)
        assertEquals("pa+ss@1", p.uuid)
        assertEquals("hy.example.com", p.address)
        assertEquals(8443, p.port)
        assertEquals("cdn.example.com", p.sni)
        assertEquals("My HY2", p.name)
        assertEquals(pin, p.pinnedPeerCertSha256)
        assertTrue(p.allowInsecure)
        assertNull(RuntimeCapabilities.unsupportedReason(p))

        val mask = JSONObject(p.finalMask)
        val salamander = mask.getJSONArray("udp").getJSONObject(0)
        assertEquals("salamander", salamander.getString("type"))
        assertEquals("obfs-secret", salamander.getJSONObject("settings").getString("password"))
        assertEquals("brutal", mask.getJSONObject("quicParams").getString("congestion"))
        assertEquals("50 mbps", mask.getJSONObject("quicParams").getString("brutalUp"))
        assertEquals("100 mbps", mask.getJSONObject("quicParams").getString("brutalDown"))
    }

    @Test fun hysteria2BuildsAnXrayHysteriaOutbound() {
        val p = ProtocolLinks.parseHysteria2("hy2://secret@1.2.3.4:443?sni=hy.example.com&pinSHA256=$pin&obfs=salamander&obfs-password=x")
        val out = proxyOutbound(p)
        assertEquals("hysteria", out.getString("protocol"))
        assertEquals(2, out.getJSONObject("settings").getInt("version"))
        assertEquals("1.2.3.4", out.getJSONObject("settings").getString("address"))
        val stream = out.getJSONObject("streamSettings")
        assertEquals("hysteria", stream.getString("network"))
        assertEquals("secret", stream.getJSONObject("hysteriaSettings").getString("auth"))
        assertEquals("tls", stream.getString("security"))
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals("hy.example.com", tls.getString("serverName"))
        assertEquals("h3", tls.getJSONArray("alpn").getString(0))
        assertEquals(pin, tls.getString("pinnedPeerCertSha256"))
        assertFalse("uTLS fingerprints do not apply to QUIC", tls.has("fingerprint"))
        assertEquals("salamander", stream.getJSONObject("finalmask").getJSONArray("udp").getJSONObject(0).getString("type"))
    }

    @Test fun hysteria2PortListTurnsOnHopping() {
        val p = ProtocolLinks.parseHysteria2("hysteria2://pw@hy.example.com:443,20000-30000/?hopInterval=10&obfs=salamander&obfs-password=o")
        assertEquals(443, p.port)
        val udp = JSONObject(p.finalMask).getJSONArray("udp")
        // Xray requires udphop to be the last (outermost) UDP mask.
        val hop = udp.getJSONObject(udp.length() - 1)
        assertEquals("udphop", hop.getString("type"))
        assertEquals("443,20000-30000", hop.getJSONObject("settings").getString("remotePorts"))
        assertEquals("10", hop.getJSONObject("settings").getString("interval"))

        val mport = ProtocolLinks.parseHysteria2("hysteria2://pw@hy.example.com:443?mport=40000-45000&hopInterval=2")
        val hop2 = JSONObject(mport.finalMask).getJSONArray("udp").getJSONObject(0).getJSONObject("settings")
        assertEquals("40000-45000", hop2.getString("remotePorts"))
        assertEquals("Xray refuses intervals under five seconds", "5", hop2.getString("interval"))
    }

    @Test fun selfSignedHysteria2NeedsItsPin() {
        val p = ProtocolLinks.parseHysteria2("hysteria2://pw@hy.example.com:443?insecure=1")
        assertTrue(RuntimeCapabilities.unsupportedReason(p)!!.contains("pinSHA256"))
        assertNull(RuntimeCapabilities.unsupportedReason(p.copy(pinnedPeerCertSha256 = pin)))
    }

    @Test fun quickAddAndSubscriptionsParseHysteria2Alike() {
        val link = "hy2://pw@hy.example.com:443?obfs=salamander&obfs-password=o#n"
        val quick = ConfigurationAdapter.parseHysteria2Uri(link)!!
        assertEquals("hysteria", quick.transport)
        assertEquals(ProtocolLinks.parseHysteria2(link).finalMask, quick.finalMask)
    }

    @Test fun wireGuardLinkBuildsAWireGuardOutbound() {
        val p = ProtocolLinks.parseWireGuard(
            "wireguard://qGWpQaNHNECGTm6agH57Pv%2FxC9qaOw1RwZrKpsuLbm8%3D@162.159.192.1:2408" +
                "?publickey=bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=&address=172.16.0.2/32,2606:4700:110::2/128" +
                "&reserved=1,2,3&mtu=1280#WARP"
        )
        assertEquals(ProtocolType.WIREGUARD, p.protocolType)
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", p.publicKey)
        assertNull(RuntimeCapabilities.unsupportedReason(p))

        val out = proxyOutbound(p)
        assertEquals("wireguard", out.getString("protocol"))
        val s = out.getJSONObject("settings")
        assertEquals("qGWpQaNHNECGTm6agH57Pv/xC9qaOw1RwZrKpsuLbm8=", s.getString("secretKey"))
        assertEquals(2, s.getJSONArray("address").length())
        assertEquals("162.159.192.1:2408", s.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        assertEquals(3, s.getJSONArray("reserved").getInt(2))
        assertTrue(s.getBoolean("noKernelTun"))
        assertEquals(listOf(0, 0, 0), XrayConfigBuilder.wireGuardReserved("AAAA")!!.let { a -> (0 until a.length()).map { a.getInt(it) } })
    }

    @Test fun xhttpModeExtraAndRealityPqvSurviveImportAndExport() {
        val link = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@x.example.com:443?type=xhttp&security=reality" +
            "&sni=www.example.com&pbk=Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=6ba85179&fp=chrome" +
            "&path=%2Fx&mode=stream-one&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D&pqv=PQVKEY#x"
        val p = (VlessParser.parse(link) as AppResult.Success).data
        val extras = ProfileExtras.read(p)
        assertEquals("stream-one", extras.getString(ProfileExtras.XHTTP_MODE))
        assertEquals("100-1000", extras.getJSONObject(ProfileExtras.XHTTP_EXTRA).getString("xPaddingBytes"))
        assertEquals("PQVKEY", extras.getString(ProfileExtras.MLDSA65_VERIFY))

        val stream = proxyOutbound(p).getJSONObject("streamSettings")
        assertEquals("stream-one", stream.getJSONObject("xhttpSettings").getString("mode"))
        assertEquals("100-1000", stream.getJSONObject("xhttpSettings").getJSONObject("extra").getString("xPaddingBytes"))
        assertEquals("PQVKEY", stream.getJSONObject("realitySettings").getString("mldsa65Verify"))

        val again = (VlessParser.parse(VlessParser.toUri(p)) as AppResult.Success).data
        assertEquals(extras.toString(), ProfileExtras.read(again).toString())
    }

    @Test fun httpUpgradeIsSupported() {
        val p = (VlessParser.parse(
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@cdn.example.com:443?type=httpupgrade&security=tls&sni=cdn.example.com&host=cdn.example.com&path=%2Fhu#h"
        ) as AppResult.Success).data
        assertNull(RuntimeCapabilities.unsupportedReason(p))
        val hu = proxyOutbound(p).getJSONObject("streamSettings").getJSONObject("httpupgradeSettings")
        assertEquals("/hu", hu.getString("path"))
        assertEquals("cdn.example.com", hu.getString("host"))
    }

    @Test fun privateDnsAcceptsTheNewOutbounds() {
        val settings = AppSettings(dnsServer = "1.1.1.1")
        val hy2 = ProtocolLinks.parseHysteria2("hy2://pw@1.2.3.4:443")
        assertNotNull(JSONObject(XrayConfigBuilder.buildJson(hy2, settings)).optJSONObject("dns"))
    }
}
