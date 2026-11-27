package com.example.vpn.sidecar

import com.example.vpn.ConnectDeadline
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.engine.ProfileExtras
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Build 181 3.4: Serverless Direct with Xray's sockopt.fallback to Lantern for sites that do not answer. */
class ServerlessLanternFallbackTest {
    private fun outbounds(root: JSONObject) = root.getJSONArray("outbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun tcpPathsFallBackToLanternAndKeepTheirOwnFirstFallback() {
        for (variant in listOf("fragA", "fragB", "oob")) {
            val source = File("src/main/assets/serverless/Serverless-$variant.jsonc").readText()
            val root = ServerlessSidecar.withLanternFallback(
                ServerlessSidecar.config(source, 12345, "u", "p", "auto"), 40999, "lu", "lp")
            val all = outbounds(root)
            val lantern = all.single { it.getString("tag") == ServerlessSidecar.LANTERN_TAG }
            assertEquals("socks", lantern.getString("protocol"))
            assertEquals("127.0.0.1", lantern.getJSONObject("settings").getString("address"))
            assertEquals(40999, lantern.getJSONObject("settings").getInt("port"))
            for (o in all.filter { it.optString("tag").startsWith("tcp-") && it.optString("protocol") in setOf("freedom", "direct") }) {
                val fb = o.getJSONObject("streamSettings").getJSONObject("sockopt").getJSONObject("fallback")
                assertEquals("$variant ${o.getString("tag")}", ServerlessSidecar.LANTERN_TAG, fb.getString("failedDialFallbackDialerProxy"))
                assertEquals(ServerlessSidecar.LANTERN_TAG, fb.getString("noIPFallbackDialerProxy"))
                assertTrue(fb.getString("noResponseFallbackDialerProxy").isNotBlank())
            }
            // UDP and DNS are not re-dialled through Lantern
            all.filter { it.optString("tag").startsWith("udp-") }.forEach {
                assertFalse(it.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.has("fallback") == true)
            }
        }
    }

    @Test fun upstreamIsParsedStrictly() {
        assertEquals(Triple(4321, "abc_D-1", "xyz"), ServerlessSidecar.parseUpstream("socks5://abc_D-1:xyz@127.0.0.1:4321"))
        assertNull(ServerlessSidecar.parseUpstream("socks5://a:b@10.0.0.1:4321"))
        assertNull(ServerlessSidecar.parseUpstream("socks5://a:b@127.0.0.1:70000"))
    }

    @Test fun itIsALabelledPickNeverASmartFallback() {
        val p = ConfigFreeMethod.SERVERLESS_LANTERN.profile()
        assertTrue(ServerlessSidecar.handles(p))
        assertTrue(ProfileExtras.read(p).optBoolean(ServerlessSidecar.KEY_LANTERN_FALLBACK))
        assertFalse(ProfileExtras.read(ConfigFreeMethod.SERVERLESS.profile()).optBoolean(ServerlessSidecar.KEY_LANTERN_FALLBACK))
        assertTrue(ConnectDeadline.ispExit(ConfigFreeMethod.SERVERLESS_LANTERN.id))
    }
}
