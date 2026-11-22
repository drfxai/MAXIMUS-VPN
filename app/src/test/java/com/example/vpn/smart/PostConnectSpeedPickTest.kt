package com.example.vpn.smart

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Post-connect speed pick: switch only to an alternate more than 10% faster; never on metered unless allowed. */
class PostConnectSpeedPickTest {
    private val pick = PostConnectSpeedPick

    @Test fun anAlternateMustBeMoreThanTenPercentFaster() {
        assertNull("exactly 10% faster is not enough", pick.decide(1_000_000, mapOf("b" to 1_100_000)))
        assertNull("5% faster stays", pick.decide(1_000_000, mapOf("b" to 1_050_000)))
        assertEquals("b", pick.decide(1_000_000, mapOf("b" to 1_100_001)))
        assertEquals("b", pick.decide(1_000_000, mapOf("b" to 2_000_000)))
        assertNull("slower stays", pick.decide(1_000_000, mapOf("b" to 900_000)))
        assertTrue(pick.fasterEnough(100, 111))
        assertFalse(pick.fasterEnough(100, 110))
    }

    @Test fun theFastestMeasuredAlternateWinsAndAnUnmeasuredOneNever() {
        assertEquals("c", pick.decide(1_000_000, mapOf("b" to 1_500_000, "c" to 3_000_000)))
        assertEquals("b", pick.decide(1_000_000, linkedMapOf("c" to null, "b" to 1_500_000)))
        assertNull(pick.decide(1_000_000, mapOf("b" to null, "c" to null)))
        assertNull("no comparison without a rate for the server in use", pick.decide(null, mapOf("b" to 9_000_000)))
        assertNull(pick.decide(0, mapOf("b" to 9_000_000)))
        assertNull(pick.decide(1_000_000, emptyMap()))
    }

    @Test fun skippedOnMeteredNetworksUnlessTheUserAllowsIt() {
        assertTrue(pick.applies(smartPick = true, tunPath = true, metered = false, allowMetered = false, alternates = 2, alreadyRan = false))
        assertFalse(pick.applies(smartPick = true, tunPath = true, metered = true, allowMetered = false, alternates = 2, alreadyRan = false))
        assertTrue(pick.applies(smartPick = true, tunPath = true, metered = true, allowMetered = true, alternates = 2, alreadyRan = false))
        assertFalse("no other verified server", pick.applies(true, true, false, false, alternates = 0, alreadyRan = false))
        assertFalse("once per Connect", pick.applies(true, true, false, false, alternates = 2, alreadyRan = true))
        assertFalse("not a Smart pick", pick.applies(false, true, false, false, 2, false))
        assertFalse("app outside its VPN", pick.applies(true, false, false, false, 2, false))
    }

    @Test fun atMostTwoAlternatesNeverTheServerInUseOrAFrozenOne() {
        assertEquals(listOf("b", "d"), pick.pickAlternates(listOf("a", "b", "c", "d", "e"), current = "a", exclude = setOf("c")))
        assertEquals(listOf("b"), pick.pickAlternates(listOf("b", "b"), current = "a"))
        assertEquals(emptyList<String>(), pick.pickAlternates(listOf("a"), current = "a"))
    }

    @Test fun probesGetTheirOwnInboundOutboundAndAFirstRoutingRule() {
        val config = JSONObject()
            .put("inbounds", JSONArray())
            .put("outbounds", JSONArray().put(JSONObject().put("tag", "proxy").put("protocol", "vless")))
            .put("routing", JSONObject().put("rules", JSONArray().put(JSONObject().put("type", "field").put("outboundTag", "direct"))))
        val out = JSONObject().put("protocol", "trojan").put("tag", "whatever")
        pick.addProbes(config, listOf(PostConnectSpeedPick.Probe("b", "speed-pick-0", 40001, "u", "p", out)))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals("password", inbound.getJSONObject("settings").getString("auth"))
        assertEquals("speed-pick-0", config.getJSONArray("outbounds").getJSONObject(1).getString("tag"))
        assertEquals("proxy", config.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        assertEquals("speed-pick-0", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("speed-pick-0", rules.getJSONObject(0).getJSONArray("inboundTag").getString(0))
        assertEquals("direct", rules.getJSONObject(1).getString("outboundTag"))
    }

    @Test fun anOutboundThatNeedsAnotherOutboundIsNotUsed() {
        fun full(o: JSONObject) = JSONObject().put("outbounds", JSONArray().put(o))
        val plain = pick.standaloneOutbound(full(JSONObject().put("protocol", "vless").put("tag", "proxy")), "speed-pick-1")
        assertEquals("speed-pick-1", plain!!.getString("tag"))
        assertNull(pick.standaloneOutbound(full(JSONObject().put("protocol", "vless")
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", "frag")))), "t"))
        assertNull(pick.standaloneOutbound(full(JSONObject().put("protocol", "freedom")), "t"))
        assertNull(pick.standaloneOutbound(JSONObject(), "t"))
    }

    @Test fun stagedProbesAreTakenOnce() {
        pick.clear()
        val p = PostConnectSpeedPick.Probe("b", "speed-pick-0", 1, "u", "p", JSONObject().put("protocol", "vless"))
        pick.stage(listOf(p))
        assertEquals(listOf(p), pick.consume())
        assertEquals(emptyList<PostConnectSpeedPick.Probe>(), pick.consume())
        pick.markActive(listOf(p))
        assertEquals(listOf(p), pick.active)
        pick.clear()
        assertEquals(emptyList<PostConnectSpeedPick.Probe>(), pick.active)
    }
}
