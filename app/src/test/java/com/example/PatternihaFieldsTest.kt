package com.example

import com.example.core.AppResult
import com.example.data.model.AppSettings
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vless.VlessParser
import com.example.vpn.engine.PatternihaFields
import com.example.vpn.engine.ProfileExtras
import com.example.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/** patterniha link fields that stock Xray v26.11.9 has (tut, esr, cp) reach its JSON; dialMode follows the fork's grammar. */
class PatternihaFieldsTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private val base = "vless://11111111-2222-3333-4444-555555555555@188.114.98.0:443?security=tls&sni=a.example.com&fp=chrome"

    private fun parse(uri: String): VlessProfile = (VlessParser.parse(uri) as AppResult.Success).data

    private fun stream(p: VlessProfile): JSONObject =
        JSONObject(XrayConfigBuilder.buildJson(p, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")

    @Test fun stockXrayGetsTutEsrAndCp() {
        val s = stream(parse("$base&type=ws&host=a.example.com&path=%2Fws&tut=15000&esr=1&cp=${enc("X25519MLKEM768, X25519")}#s"))
        assertEquals(15000, s.getJSONObject("sockopt").getInt("tcpUserTimeout"))
        assertTrue(s.getJSONObject("sockopt").has("mark"))
        val tls = s.getJSONObject("tlsSettings")
        assertTrue(tls.getBoolean("enableSessionResumption"))
        val curves = tls.getJSONArray("curvePreferences")
        assertEquals(2, curves.length())
        assertEquals("X25519MLKEM768", curves.getString(0))
        assertEquals("X25519", curves.getString(1))
    }

    @Test fun linksWithoutTheFieldsAreUnchanged() {
        val s = stream(parse("$base&type=ws&host=a.example.com&path=%2Fws#plain"))
        assertFalse(s.getJSONObject("sockopt").has("tcpUserTimeout"))
        assertFalse(s.getJSONObject("tlsSettings").has("enableSessionResumption"))
        assertFalse(s.getJSONObject("tlsSettings").has("curvePreferences"))
        assertFalse(s.getJSONObject("sockopt").has("dialMode"))
    }

    @Test fun tcpUserTimeoutOnlyWhereTheOutboundUsesTcp() {
        // As PattN does: not over mKCP, not XHTTP whose TLS offers h3 alone (QUIC), not over UDP protocols.
        val kcp = parse("$base&type=kcp&tut=4000#kcp")
        assertFalse(PatternihaFields.tcpUserTimeoutApplies(kcp))
        assertFalse(stream(kcp).getJSONObject("sockopt").has("tcpUserTimeout"))
        val h3 = parse("$base&type=xhttp&path=%2Fx&alpn=h3&tut=4000#h3")
        assertFalse(PatternihaFields.tcpUserTimeoutApplies(h3))
        assertNull(PatternihaFields.tcpUserTimeout(h3))
        val h2 = parse("$base&type=xhttp&path=%2Fx&alpn=${enc("h3,h2")}&tut=4000#h2")
        assertEquals(4000, PatternihaFields.tcpUserTimeout(h2))
        assertFalse(PatternihaFields.tcpUserTimeoutApplies(VlessProfile(name = "hy", address = "1.2.3.4", port = 443,
            uuid = "x", protocolType = ProtocolType.HYSTERIA2)))
    }

    @Test fun realityGetsNoTlsOnlyFields() {
        val p = parse("vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?type=tcp&security=reality" +
            "&sni=www.example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab&fp=chrome&tut=4000&esr=1&cp=X25519#r")
        val s = stream(p)
        assertEquals(4000, s.getJSONObject("sockopt").getInt("tcpUserTimeout"))
        assertFalse(s.getJSONObject("realitySettings").has("enableSessionResumption"))
        assertFalse(s.getJSONObject("realitySettings").has("curvePreferences"))
    }

    @Test fun dialModeFollowsTheForkGrammar() {
        for (ok in listOf("oob.0", "oob.0,32,65", "oob.0,_,12", "oob4.0,0;oob6.5,_,8", "oob6.255", "oob.+7")) {
            assertTrue(ok, PatternihaFields.validDialMode(ok))
        }
        for (bad in listOf("", "oob", "oob.", "oob.256", "oob.-1", "oob.a", "oob.0,", "oob.0;oob4.1", "oob4.1;oob4.2",
                "oob6.1;oob6.2", "oob.0;oob.1", "frag.1", "oob.0;", " oob.0", "oob.١")) {
            assertFalse(bad, PatternihaFields.validDialMode(bad))
        }
        assertNull(PatternihaFields.dialMode(parse("$base&dialMode=oob.999#bad")))
        assertEquals("oob.0", PatternihaFields.dialMode(parse("$base&dialMode=oob.0#ok")))
    }
}
