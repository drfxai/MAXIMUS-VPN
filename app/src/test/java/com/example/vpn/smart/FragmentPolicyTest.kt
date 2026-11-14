package com.example.vpn.smart

import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** REALITY: at most a "1-3" fragment, never tlshello or tiny pieces. ECH: no fragment at all. */
class FragmentPolicyTest {
    private val tlshello = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"100-200","delay":"10-20"}}]}"""
    private fun settings(mask: String) = JSONObject(mask).getJSONArray("tcp").getJSONObject(0).getJSONObject("settings")

    @Test fun realityNeverRunsTlshelloOrTheDeepRescue() {
        for (mask in listOf(tlshello, FreezeFailover.DEEP_FRAGMENT_MASK,
                com.example.vpn.connectivity.IranHelloSplit.DEEP_MASK, com.example.vpn.connectivity.IranHelloSplit.MASK)) {
            val out = FragmentPolicy.apply(mask, "reality", "")
            val tcp = JSONObject(out).getJSONArray("tcp")
            assertEquals("one fragment entry at most: $mask", 1, tcp.length())
            assertEquals("1-3", settings(out).getString("packets"))
            assertEquals("100-200", settings(out).getString("length"))
            assertEquals("10-20", settings(out).getString("delay"))
            assertFalse(settings(out).has("maxSplit"))
            assertTrue(FragmentPolicy.realitySafe(settings(out)))
        }
    }

    @Test fun aRealityOneToThreeFragmentWithSensiblePiecesIsKeptAsIs() {
        val ok = """{"tcp":[{"type":"fragment","settings":{"packets":"1-3","length":"80-150","delay":"5-10"}}]}"""
        assertEquals(ok, FragmentPolicy.apply(ok, "REALITY", ""))
        val tiny = """{"tcp":[{"type":"fragment","settings":{"packets":"1-3","length":"5","delay":"5"}}]}"""
        assertEquals("1-3", settings(FragmentPolicy.apply(tiny, "reality", "")).getString("packets"))
        assertEquals("100-200", settings(FragmentPolicy.apply(tiny, "reality", "")).getString("length"))
    }

    @Test fun echDropsEveryFragmentButKeepsOtherMasks() {
        assertEquals("", FragmentPolicy.apply(tlshello, "tls", "cloudflare-ech.com+udp://1.1.1.1"))
        val mixed = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello"}},{"type":"noise"}],"udp":[{"type":"salamander"}]}"""
        val out = JSONObject(FragmentPolicy.apply(mixed, "tls", "cloudflare-ech.com+udp://1.1.1.1"))
        assertEquals(1, out.getJSONArray("tcp").length())
        assertEquals("noise", out.getJSONArray("tcp").getJSONObject(0).getString("type"))
        assertEquals(1, out.getJSONArray("udp").length())
        assertFalse(FragmentPolicy.hasFragment(out.toString()))
    }

    @Test fun plainTlsWithoutEchAndNonJsonAreUnchanged() {
        assertEquals(tlshello, FragmentPolicy.apply(tlshello, "tls", ""))
        assertEquals(FreezeFailover.DEEP_FRAGMENT_MASK, FragmentPolicy.apply(FreezeFailover.DEEP_FRAGMENT_MASK, "tls", ""))
        assertEquals("not json", FragmentPolicy.apply("not json", "reality", ""))
        assertEquals("", FragmentPolicy.apply("", "reality", ""))
    }

    @Test fun theXrayConfigBuilderAppliesThePolicy() {
        val reality = VlessProfile(name = "R", address = "1.2.3.4", port = 443, uuid = "8b0e2c4a-9f6d-4c1e-a7b3-2d5f8e1c0a9b",
            transport = "tcp", security = "reality", sni = "www.example.com", publicKey = "pbk", fingerprint = "chrome",
            finalMask = FreezeFailover.DEEP_FRAGMENT_MASK)
        val proxy = JSONObject(XrayConfigBuilder.buildJson(reality, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
        val s = proxy.getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("tcp").getJSONObject(0).getJSONObject("settings")
        assertEquals("1-3", s.getString("packets"))

        val ech = reality.copy(security = "tls", publicKey = "", echConfigList = "cloudflare-ech.com+udp://1.1.1.1", finalMask = tlshello)
        val echProxy = JSONObject(XrayConfigBuilder.buildJson(ech, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
        assertFalse(echProxy.getJSONObject("streamSettings").has("finalmask"))
    }
}
