package com.example

import com.example.core.AppResult
import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vless.VlessParser
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.ForkXraySidecar
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
        val p = parse("$base&fm=${enc(merge)}&dialMode=oob.0&tut=4000&esr=1&cp=${enc("X25519,P256")}#all")
        assertEquals(listOf("fm", "dialMode", "tut", "esr", "cp"), ForkXraySidecar.forkFields(p))
        assertTrue(ForkXraySidecar.applies(p))
        assertEquals(listOf("dialMode"), ForkXraySidecar.forkFields(parse("$base&dialMode=oob6.0#d")))
        assertEquals(listOf("fm"), ForkXraySidecar.forkFields(parse("$base&fm=${enc(family)}#f")))
        assertTrue(ForkXraySidecar.forkOnlyMask("""{"udp4":[]}"""))
        assertFalse(ForkXraySidecar.forkOnlyMask("not json"))
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
            "&sni=www.example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab&fp=chrome&tut=4000&esr=1&cp=X25519#r")
        assertTrue(ForkXraySidecar.applies(p))
        val stream = ForkXraySidecar.outbound(p, AppSettings()).getJSONObject("streamSettings")
        assertEquals(4000, stream.getJSONObject("sockopt").getInt("tcpUserTimeout"))
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
        for (key in listOf(ProfileExtras.PATT_DIAL_MODE, ProfileExtras.PATT_TCP_USER_TIMEOUT,
                ProfileExtras.PATT_SESSION_RESUMPTION, ProfileExtras.PATT_CURVE_PREFERENCES)) assertFalse(key, extras.has(key))
        assertTrue(ProfileExtras.usesPatternihaKeys(stock))
        assertEquals(p.address, stock.address)
        assertEquals(p.uuid, stock.uuid)
        assertEquals("", ForkXraySidecar.stockMask(family))
    }
}
