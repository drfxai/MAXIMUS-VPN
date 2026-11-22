package com.example

import com.example.core.AppResult
import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vless.VlessParser
import com.example.vpn.engine.ProfileExtras
import com.example.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/** patterniha (PattN / Free-Configs) share-link fields survive import, storage and export. */
class PatternihaLinkFieldsTest {
    private val mask = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"10-20"}}]}"""
    private val ciphers = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256"
    private val echOutbound = """{"protocol":"freedom","tag":"ech-dns"}"""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun link(withEch: Boolean = true) =
        "vless://11111111-2222-3333-4444-555555555555@[2a06:98c1:3120::7]:443?type=ws&security=tls" +
            "&sni=a.example.com&host=a.example.com&path=%2Fws&fp=unsafe" +
            "&cs=${enc(ciphers)}&fm=${enc(mask)}&dialMode=oob.0&tut=15000&esr=1" +
            "&cp=${enc("X25519,P256")}" +
            (if (withEch) "&ech=${enc("cloudflare-ech.com+https://1.1.1.1/dns-query")}" else "") +
            "&echOutbound=${enc(echOutbound)}#patt"

    private fun parse(uri: String): VlessProfile =
        (VlessParser.parse(uri) as AppResult.Success).data

    @Test fun parsesAllPatternihaFields() {
        val p = parse(link())
        assertEquals(ciphers, p.cipherSuites)
        assertEquals(JSONObject(mask).toString(), JSONObject(p.finalMask).toString())
        assertEquals("cloudflare-ech.com+https://1.1.1.1/dns-query", p.echConfigList)
        val extras = ProfileExtras.read(p)
        assertEquals("oob.0", extras.getString(ProfileExtras.PATT_DIAL_MODE))
        assertEquals(15000, extras.getInt(ProfileExtras.PATT_TCP_USER_TIMEOUT))
        assertTrue(extras.getBoolean(ProfileExtras.PATT_SESSION_RESUMPTION))
        assertEquals("X25519,P256", extras.getString(ProfileExtras.PATT_CURVE_PREFERENCES))
        assertEquals("ech-dns", extras.getJSONObject(ProfileExtras.PATT_ECH_OUTBOUND).getString("tag"))
        assertTrue(ProfileExtras.usesPatternihaKeys(p))
    }

    @Test fun roundTripsThroughExport() {
        val first = parse(link())
        val exported = VlessParser.toUri(first)
        assertTrue(exported, exported.contains("fm="))
        assertTrue(exported, exported.contains("cs="))
        assertFalse(exported, exported.contains("finalmask="))
        for (key in listOf("dialMode=", "tut=15000", "esr=1", "cp=", "ech=", "echOutbound=")) {
            assertTrue("$key missing from $exported", exported.contains(key))
        }
        val second = parse(exported)
        assertEquals(first.cipherSuites, second.cipherSuites)
        assertEquals(first.finalMask, second.finalMask)
        assertEquals(first.echConfigList, second.echConfigList)
        assertEquals(ProfileExtras.pattQuery(first), ProfileExtras.pattQuery(second))
    }

    @Test fun invalidValuesAreDropped() {
        val p = parse(
            "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?security=tls&sni=a.example.com" +
                "&tut=-5&esr=0&echOutbound=notjson#x"
        )
        val extras = ProfileExtras.read(p)
        assertFalse(extras.has(ProfileExtras.PATT_TCP_USER_TIMEOUT))
        assertFalse(extras.has(ProfileExtras.PATT_SESSION_RESUMPTION))
        assertFalse(extras.has(ProfileExtras.PATT_ECH_OUTBOUND))
        assertFalse(ProfileExtras.usesPatternihaKeys(p))
    }

    @Test fun plainLinksKeepLongKeys() {
        val p = parse(
            "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?security=tls&sni=a.example.com" +
                "&cipherSuites=${enc(ciphers)}&finalmask=${enc(mask)}#x"
        )
        val exported = VlessParser.toUri(p)
        assertTrue(exported, exported.contains("cipherSuites="))
        assertTrue(exported, exported.contains("finalmask="))
        assertFalse(exported, exported.contains("&fm="))
    }

    @Test fun mappedFieldsReachXrayJson() {
        val outbound = JSONObject(XrayConfigBuilder.buildJson(parse(link()), AppSettings()))
            .getJSONArray("outbounds").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals(ciphers, tls.getString("cipherSuites"))
        assertEquals("cloudflare-ech.com+https://1.1.1.1/dns-query", tls.getString("echConfigList"))
        // Xray drops TLS fragments when ECH is on (FragmentPolicy), so check the mask on a link without ech.
        val plainStream = JSONObject(XrayConfigBuilder.buildJson(parse(link(withEch = false)), AppSettings()))
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("fragment", plainStream.getJSONObject("finalmask").getJSONArray("tcp").getJSONObject(0).getString("type"))
    }
}
