package com.example

import com.example.data.model.ProtocolType
import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.share.ShareCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** War plan Phase 3: phone-to-phone QR sharing keeps every protocol's settings. */
class ShareCodeTest {
    private fun import(link: String) = UniversalImportEngine.importText(link).validProfiles.single()

    private val reality = "vless://8f3c2a91-6b4e-4d1f-9c2a-7e5b1d0a3c44@185.199.110.42:443?encryption=none&security=reality" +
        "&sni=www.speedtest.net&fp=chrome&pbk=Xk2v9Qm4TfYp7cRz1LwN8bHs3JdKe6UaV0gMq5iOyEo&sid=6ba85179e30d4fc2&type=tcp" +
        "&flow=xtls-rprx-vision#Frankfurt%20REALITY"
    private val wireGuard = "[Interface]\nPrivateKey = cGhvbmUtcHJpdmF0ZS1rZXktZm9yLXRlc3RpbmctMDE=\nAddress = 10.0.0.2/32\n" +
        "Jc = 4\nJmin = 40\nJmax = 70\n[Peer]\nPublicKey = c2VydmVyLXB1YmxpYy1rZXktZm9yLXRlc3RpbmctMDE=\nEndpoint = 203.0.113.5:51820\nAllowedIPs = 0.0.0.0/0\n"
    private val hy2 = "hysteria2://pass@203.0.113.9:8443?sni=h.example.org&obfs=salamander&obfs-password=x1&mport=20000-30000#HY2"

    @Test
    fun `every protocol survives the round trip`() {
        for (original in listOf(import(reality), import(wireGuard), import(hy2))) {
            val back = ShareCode.decode(ShareCode.encode(original))!!.profile
            assertEquals(original.copy(id = back.id, createdAt = back.createdAt, canonicalFingerprint = back.canonicalFingerprint,
                sourceFile = null, sourceSubscription = null, subscriptionUrl = null), back.copy(sourceFile = null, sourceSubscription = null, subscriptionUrl = null))
        }
        assertEquals(ProtocolType.WIREGUARD, ShareCode.decode(ShareCode.encode(import(wireGuard)))!!.profile.protocolType)
        assertTrue(ShareCode.decode(ShareCode.encode(import(wireGuard)))!!.profile.finalMask.contains("noise"))
    }

    @Test
    fun `codes are small enough to scan and carry their place in a set`() {
        val code = ShareCode.encode(import(reality), index = 2, total = 5)
        assertTrue(code, code.startsWith("mx1:2/5:"))
        assertTrue("${code.length} chars", code.length < 400)
        val part = ShareCode.decode(code)!!
        assertEquals(2, part.index)
        assertEquals(5, part.total)
        assertEquals("Frankfurt REALITY", part.profile.name)
    }

    @Test
    fun `damaged or foreign codes are refused`() {
        val code = ShareCode.encode(import(reality))
        assertNull(ShareCode.decode(code.dropLast(12)))
        assertNull(ShareCode.decode("mx1:6/5:" + code.removePrefix("mx1:")))
        assertNull(ShareCode.decode("mx1:!!!"))
        assertNull(ShareCode.decode(reality))
    }

    @Test
    fun `pasting a code imports it like a link`() {
        val code = ShareCode.encode(import(hy2), index = 1, total = 3)
        val imported = UniversalImportEngine.importText("$code\n$reality").validProfiles
        assertEquals(listOf(ProtocolType.HYSTERIA2, ProtocolType.VLESS), imported.map { it.protocolType })
    }
}
