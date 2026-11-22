package com.example.xray

import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XhttpProfilesTest {
    @After fun reset() { XhttpProfiles.networkKey = null }

    @Test fun profileByNetwork() {
        assertEquals(XhttpProfiles.Profile.FAST, XhttpProfiles.forNetwork("wifi"))
        assertEquals(XhttpProfiles.Profile.FAST, XhttpProfiles.forNetwork("ethernet"))
        assertEquals(XhttpProfiles.Profile.CONSERVATIVE, XhttpProfiles.forNetwork("cell:43235"))
        assertEquals(XhttpProfiles.Profile.CONSERVATIVE, XhttpProfiles.forNetwork("cell:43211"))
        assertEquals(XhttpProfiles.Profile.CONSERVATIVE, XhttpProfiles.forNetwork("cell"))
        assertEquals(XhttpProfiles.Profile.CONSERVATIVE, XhttpProfiles.forNetwork(null))
        assertEquals(XhttpProfiles.Profile.CONSERVATIVE, XhttpProfiles.forNetwork("other"))
    }

    @Test fun fillsOnlyMissingKeysAndKeepsTheLinksXmuxWhole() {
        val link = JSONObject().put("xPaddingBytes", "1-2")
            .put("xmux", JSONObject().put("maxConnections", "2"))
        val out = XhttpProfiles.apply(link, XhttpProfiles.Profile.FAST)
        assertEquals("1-2", out.getString("xPaddingBytes"))
        assertEquals("2", out.getJSONObject("xmux").getString("maxConnections"))
        assertFalse(out.getJSONObject("xmux").has("maxConcurrency"))
        assertEquals("1000000", out.getString("scMaxEachPostBytes"))
        assertEquals("10-30", out.getString("scMinPostsIntervalMs"))
        assertFalse(link.has("scMaxEachPostBytes"))   // the input is not changed
    }

    @Test fun conservativeIsSmallerThanFast() {
        val c = XhttpProfiles.apply(null, XhttpProfiles.Profile.CONSERVATIVE)
        assertEquals("250000-500000", c.getString("scMaxEachPostBytes"))
        assertEquals("4-8", c.getJSONObject("xmux").getString("maxConcurrency"))
        assertEquals("40-80", c.getString("scMinPostsIntervalMs"))
    }

    private fun xhttp(extra: JSONObject? = null): VlessProfile {
        val p = VlessProfile(name = "x", address = "x.example.com", port = 443,
            uuid = "12345678-1234-1234-1234-123456789abc", transport = "xhttp", security = "tls",
            sni = "x.example.com", path = "/p")
        return if (extra != null) ProfileExtras.with(p, ProfileExtras.XHTTP_EXTRA, extra) else p
    }

    private fun xhttpSettings(p: VlessProfile): JSONObject {
        val root = JSONObject(XrayConfigBuilder.buildJson(p, AppSettings(dnsServer = "1.1.1.1")))
        val outs = root.getJSONArray("outbounds")
        val proxy = (0 until outs.length()).map { outs.getJSONObject(it) }.first { it.optString("tag") == "proxy" }
        return proxy.getJSONObject("streamSettings").getJSONObject("xhttpSettings")
    }

    @Test fun builderAppliesTheNetworkProfile() {
        XhttpProfiles.networkKey = "wifi"
        assertEquals("16-32", xhttpSettings(xhttp()).getJSONObject("extra").getJSONObject("xmux").getString("maxConcurrency"))
        XhttpProfiles.networkKey = "cell:43235"
        val extra = xhttpSettings(xhttp()).getJSONObject("extra")
        assertEquals("4-8", extra.getJSONObject("xmux").getString("maxConcurrency"))
        assertTrue(extra.has("xPaddingBytes"))
    }

    @Test fun builderKeepsTheLinksOwnValues() {
        XhttpProfiles.networkKey = "wifi"
        val extra = xhttpSettings(xhttp(JSONObject().put("scMaxEachPostBytes", 123456).put("xPaddingBytes", "100-1000")))
            .getJSONObject("extra")
        assertEquals(123456, extra.getInt("scMaxEachPostBytes"))
        assertEquals("100-1000", extra.getString("xPaddingBytes"))
        assertEquals("10-30", extra.getString("scMinPostsIntervalMs"))
    }
}
