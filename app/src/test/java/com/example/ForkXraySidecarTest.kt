package com.example

import com.example.core.AppResult
import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vless.VlessParser
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.ForkXraySidecar
import com.example.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/** Links with patterniha-fork-only fields run on the fork sidecar; everything else is unchanged. */
class ForkXraySidecarTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private val base = "vless://11111111-2222-3333-4444-555555555555@188.114.98.0:443?type=ws&security=tls" +
        "&sni=a.example.com&host=a.example.com&path=%2Fws&fp=unsafe"
    private val fragment = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["0","104","1"],"delays":["0"]}}]}"""
    private val merge = """{"tcp":[{"type":"fragment","settings":{"packets":"1-1","lengths":["43"]}},""" +
        """{"type":"merge","settings":{"times":100,"delayMs":30,"maxBytes":16384}}]}"""
    private val family = """{"tcp6":[{"type":"merge","settings":{"times":100}}]}"""

    private fun parse(uri: String): VlessProfile = (VlessParser.parse(uri) as AppResult.Success).data

    @Test fun plainAndStockMaskLinksStayOnStockXray() {
        val plain = parse("$base#plain")
        assertTrue(ForkXraySidecar.forkFields(plain).isEmpty())
        assertFalse(ForkXraySidecar.applies(plain))
        val fragmented = parse("$base&fm=${enc(fragment)}#frag")
        assertTrue(ForkXraySidecar.forkFields(fragmented).isEmpty())
        assertFalse(ForkXraySidecar.applies(fragmented))
        assertEquals(fragmented, ForkXraySidecar.stockProfile(fragmented))
    }

    @Test fun detectsEveryForkOnlyField() {
        val p = parse("$base&fm=${enc(merge)}&dialMode=oob.0&tut=4000&esr=1&cp=${enc("X25519,CurveP256")}#all")
        assertEquals(listOf("fm", "dialMode"), ForkXraySidecar.forkFields(p))
        assertTrue(ForkXraySidecar.applies(p))
        assertEquals(listOf("dialMode"), ForkXraySidecar.forkFields(parse("$base&dialMode=oob6.0#d")))
        assertEquals(listOf("fm"), ForkXraySidecar.forkFields(parse("$base&fm=${enc(family)}#f")))
        assertTrue(ForkXraySidecar.forkOnlyMask("""{"udp4":[]}"""))
        assertTrue(ForkXraySidecar.forkOnlyMask("""{"tcp":[{"type":"proxy-protocol","settings":{"version":2}}]}"""))
        assertFalse(ForkXraySidecar.forkOnlyMask("not json"))
    }

    @Test fun stockXrayFieldsDoNotNeedTheFork() {
        // tcpUserTimeout, enableSessionResumption and curvePreferences are in stock Xray v26.11.9 as well.
        val p = parse("$base&tut=4000&esr=1&cp=X25519#stock")
        assertTrue(ForkXraySidecar.forkFields(p).isEmpty())
        assertFalse(ForkXraySidecar.applies(p))
        assertEquals(p, ForkXraySidecar.stockProfile(p))
    }

    @Test fun invalidDialModeStaysOnStockXray() {
        // The fork fails every dial on a dialMode it cannot parse, so such a link is not sent to it.
        for (mode in listOf("oob.", "oob.256", "split.1", "oob.0;oob4.1", "oob4.1;oob4.2")) {
            val p = parse("$base&dialMode=${enc(mode)}#bad")
            assertTrue(mode, ForkXraySidecar.forkFields(p).isEmpty())
            assertFalse(mode, ForkXraySidecar.applies(p))
        }
        val ok = parse("$base&dialMode=${enc("oob4.0,_,12;oob6.5")}#ok")
        assertEquals(listOf("dialMode"), ForkXraySidecar.forkFields(ok))
        assertEquals("oob4.0,_,12;oob6.5", ForkXraySidecar.outbound(ok, AppSettings())
            .getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialMode"))
    }

    @Test fun mapsFieldsToForkJsonKeys() {
        val p = parse("$base&fm=${enc(merge)}&dialMode=oob.0&tut=4000&esr=1&cp=${enc("X25519MLKEM768,X25519")}#all")
        val outbound = ForkXraySidecar.outbound(p, AppSettings())
        assertEquals(ForkXraySidecar.TAG, outbound.getString("tag"))
        val stream = outbound.getJSONObject("streamSettings")
        val sock = stream.getJSONObject("sockopt")
        assertEquals("oob.0", sock.getString("dialMode"))
        assertEquals(4000, sock.getInt("tcpUserTimeout"))
        val tls = stream.getJSONObject("tlsSettings")
        assertTrue(tls.getBoolean("enableSessionResumption"))
        val curves = tls.getJSONArray("curvePreferences")
        assertEquals(2, curves.length())
        assertEquals("X25519MLKEM768", curves.getString(0))
        assertEquals("X25519", curves.getString(1))
        val masks = stream.getJSONObject("finalmask").getJSONArray("tcp")
        assertEquals("merge", masks.getJSONObject(1).getString("type"))
    }

    @Test fun forkConfigIsOneAuthenticatedLoopbackInlet() {
        val p = parse("$base&dialMode=oob.0#d")
        val root = ForkXraySidecar.config(ForkXraySidecar.outbound(p, AppSettings()), 10808, "u", "p", AppSettings().dnsServer)
        val inlet = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", inlet.getString("listen"))
        assertEquals(10808, inlet.getInt("port"))
        assertEquals("password", inlet.getJSONObject("settings").getString("auth"))
        assertEquals(1, root.getJSONArray("outbounds").length())
        assertEquals(ForkXraySidecar.TAG, root.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
        assertFalse(root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
            .getJSONObject("sockopt").has("mark"))
    }

    @Test fun realityGetsSockoptOnly() {
        val p = parse("vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?type=tcp&security=reality" +
            "&sni=www.example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab&fp=chrome&dialMode=oob.0" +
            "&tut=4000&esr=1&cp=X25519#r")
        assertTrue(ForkXraySidecar.applies(p))
        val stream = ForkXraySidecar.outbound(p, AppSettings()).getJSONObject("streamSettings")
        assertEquals(4000, stream.getJSONObject("sockopt").getInt("tcpUserTimeout"))
        assertEquals("oob.0", stream.getJSONObject("sockopt").getString("dialMode"))
        assertFalse(stream.has("tlsSettings"))
        assertFalse(stream.getJSONObject("realitySettings").has("enableSessionResumption"))
    }

    @Test fun stockFallbackDropsForkFieldsOnly() {
        val p = parse("$base&fm=${enc(merge)}&dialMode=oob.0&tut=4000&esr=1&cp=X25519#all")
        val stock = ForkXraySidecar.stockProfile(p)
        assertTrue(ForkXraySidecar.forkFields(stock).isEmpty())
        assertFalse(ForkXraySidecar.applies(stock))
        val masks = JSONObject(stock.finalMask).getJSONArray("tcp")
        assertEquals(1, masks.length())
        assertEquals("fragment", masks.getJSONObject(0).getString("type"))
        val extras = ProfileExtras.read(stock)
        assertFalse(extras.has(ProfileExtras.PATT_DIAL_MODE))
        // Stock Xray has these, so the fallback keeps them and the builder still writes them.
        assertEquals(4000, extras.getInt(ProfileExtras.PATT_TCP_USER_TIMEOUT))
        assertTrue(extras.getBoolean(ProfileExtras.PATT_SESSION_RESUMPTION))
        assertEquals("X25519", extras.getString(ProfileExtras.PATT_CURVE_PREFERENCES))
        val stream = JSONObject(XrayConfigBuilder.buildJson(stock, AppSettings()))
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals(4000, stream.getJSONObject("sockopt").getInt("tcpUserTimeout"))
        assertFalse(stream.getJSONObject("sockopt").has("dialMode"))
        assertTrue(stream.getJSONObject("tlsSettings").getBoolean("enableSessionResumption"))
        assertTrue(ProfileExtras.usesPatternihaKeys(stock))
        assertEquals(p.address, stock.address)
        assertEquals(p.uuid, stock.uuid)
        assertEquals("", ForkXraySidecar.stockMask(family))
    }

    @Test fun stockFallbackUsesTheFamilyListOfTheServer() {
        // On the fork tcp4/tcp6 replace tcp for a remote of that family; stock Xray gets that list as tcp.
        val split = """{"tcp4":[{"type":"fragment","settings":{"packets":"tlshello","length":"10-20"}},""" +
            """{"type":"merge","settings":{"times":100}}],"tcp6":[{"type":"borrow","settings":{}}]}"""
        val v4 = JSONObject(ForkXraySidecar.stockMask(split, ipv6 = false))
        assertEquals(1, v4.getJSONArray("tcp").length())
        assertEquals("fragment", v4.getJSONArray("tcp").getJSONObject(0).getString("type"))
        assertFalse(v4.has("tcp4"))
        assertFalse(v4.has("tcp6"))
        assertEquals("", ForkXraySidecar.stockMask(split, ipv6 = true))
        val v6Server = parse("vless://11111111-2222-3333-4444-555555555555@[2a06:98c1:3120::7]:443?type=ws&security=tls" +
            "&sni=a.example.com&host=a.example.com&path=%2Fws&fm=${enc(split)}#v6")
        assertEquals("", ForkXraySidecar.stockProfile(v6Server).finalMask)
        val v4Server = parse("$base&fm=${enc(split)}#v4")
        assertEquals("fragment", JSONObject(ForkXraySidecar.stockProfile(v4Server).finalMask)
            .getJSONArray("tcp").getJSONObject(0).getString("type"))
    }

    @Test fun echOutboundCarriesTheEchLookupOnTheFork() {
        val ech = """{"protocol":"freedom","tag":"ech-dns","streamSettings":{"sockopt":{"mark":255}}}"""
        val p = parse("$base&dialMode=oob.0&ech=${enc("cloudflare-ech.com+https://1.1.1.1/dns-query")}" +
            "&echOutbound=${enc(ech)}#ech")
        val echOutbound = ForkXraySidecar.echOutbound(p)!!
        assertEquals(ForkXraySidecar.ECH_TAG, echOutbound.getString("tag"))
        val outbound = ForkXraySidecar.outbound(p, AppSettings())
        assertEquals(ForkXraySidecar.ECH_TAG, outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings")
            .getJSONObject("echSockopt").getString("dialerProxy"))
        val root = ForkXraySidecar.config(outbound, 10808, "u", "p", AppSettings().dnsServer, echOutbound)
        val outbounds = root.getJSONArray("outbounds")
        assertEquals(2, outbounds.length())
        assertEquals("freedom", outbounds.getJSONObject(1).getString("protocol"))
        assertFalse(outbounds.getJSONObject(1).getJSONObject("streamSettings").getJSONObject("sockopt").has("mark"))
        // Without an ECH config to look up there is nothing for it to carry.
        assertEquals(null, ForkXraySidecar.echOutbound(parse("$base&dialMode=oob.0&echOutbound=${enc(ech)}#noech")))
    }
}
