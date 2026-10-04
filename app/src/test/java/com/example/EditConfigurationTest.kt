package com.example

import com.example.core.AppResult
import com.example.data.model.AppSettings
import com.example.data.model.RoutingMode
import com.example.data.model.VlessProfile
import com.example.vless.VlessParser
import com.example.vpn.CertificateFingerprint
import com.example.vpn.engine.RuntimeCapabilities
import com.example.xray.XrayConfigBuilder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fields the Edit Configuration screen writes must reach the Xray outbound and share links. */
class EditConfigurationTest {
    private val pin = "a".repeat(64)

    private val tlsNode = VlessProfile(
        name = "edited",
        address = "203.0.113.10",
        port = 443,
        uuid = "00000000-0000-4000-8000-000000000000",
        transport = "ws",
        security = "tls",
        sni = "edge.example.com",
        host = "edge.example.com",
        path = "/ws",
        fingerprint = "unsafe"
    )

    private fun outbound(profile: VlessProfile): JSONObject {
        val json = JSONObject(XrayConfigBuilder.buildJson(profile, AppSettings(routingMode = RoutingMode.GLOBAL, dnsServer = "1.1.1.1")))
        return json.getJSONArray("outbounds").getJSONObject(0)
    }

    @Test
    fun editedTlsFieldsReachTheXrayOutbound() {
        val ob = outbound(
            tlsNode.copy(
                allowInsecure = true,
                pinnedPeerCertSha256 = pin,
                verifyPeerCertByName = "edge.example.com",
                echConfigList = "cloudflare-ech.com+https://1.1.1.1/dns-query",
                echSockopt = """{"domainStrategy":"UseIPv4"}""",
                targetStrategy = "UseIPv4"
            )
        )
        val tls = ob.getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertEquals(pin, tls.getString("pinnedPeerCertSha256"))
        assertEquals("edge.example.com", tls.getString("verifyPeerCertByName"))
        assertEquals("cloudflare-ech.com+https://1.1.1.1/dns-query", tls.getString("echConfigList"))
        assertEquals("UseIPv4", tls.getJSONObject("echSockopt").getString("domainStrategy"))
        // Xray-core refuses to start when allowInsecure is present; the pin replaces it.
        assertFalse(tls.has("allowInsecure"))
        assertEquals("UseIPv4", ob.getString("targetStrategy"))
    }

    @Test
    fun defaultsAddNothingNew() {
        val ob = outbound(tlsNode)
        val tls = ob.getJSONObject("streamSettings").getJSONObject("tlsSettings")
        for (key in listOf("pinnedPeerCertSha256", "verifyPeerCertByName", "echConfigList", "echSockopt", "allowInsecure")) {
            assertFalse(key, tls.has(key))
        }
        assertFalse(ob.has("targetStrategy"))
        assertFalse(outbound(tlsNode.copy(targetStrategy = "AsIs")).has("targetStrategy"))
    }

    @Test
    fun invalidEditsAreRefusedBeforeConnecting() {
        assertNull(RuntimeCapabilities.unsupportedReason(tlsNode))
        assertNotNull(RuntimeCapabilities.unsupportedReason(tlsNode.copy(allowInsecure = true)))
        assertNull(RuntimeCapabilities.unsupportedReason(tlsNode.copy(allowInsecure = true, pinnedPeerCertSha256 = pin)))
        assertNotNull(RuntimeCapabilities.unsupportedReason(tlsNode.copy(pinnedPeerCertSha256 = "abc")))
        assertNull(RuntimeCapabilities.unsupportedReason(tlsNode.copy(pinnedPeerCertSha256 = "AB:".repeat(31) + "AB, $pin")))
        assertNotNull(RuntimeCapabilities.unsupportedReason(tlsNode.copy(echSockopt = "{not json")))
        assertNotNull(RuntimeCapabilities.unsupportedReason(tlsNode.copy(targetStrategy = "Sometimes")))
        assertNull(RuntimeCapabilities.unsupportedReason(tlsNode.copy(targetStrategy = "forceipv6v4")))
    }

    @Test
    fun shareLinkKeepsPinVerifyNameAndEch() {
        val edited = tlsNode.copy(pinnedPeerCertSha256 = pin, verifyPeerCertByName = "a.example.com,b.example.com", echConfigList = "AEX+DQBB")
        val parsed = (VlessParser.parse(VlessParser.toUri(edited)) as AppResult.Success).data
        assertEquals(pin, parsed.pinnedPeerCertSha256)
        assertEquals("a.example.com,b.example.com", parsed.verifyPeerCertByName)
        assertEquals("AEX+DQBB", parsed.echConfigList)
    }

    @Test
    fun fetchesTheLeafCertificateSha256() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("unused"))
        server.start()
        try {
            val fingerprint = CertificateFingerprint.fetch("127.0.0.1", server.port, sni = "localhost")
            assertEquals(CertificateFingerprint.sha256Hex(held.certificate), fingerprint)
            assertTrue(RuntimeCapabilities.isValidCertPin(fingerprint))
            // The handshake is aborted by the client, so no request ever reaches the server.
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
