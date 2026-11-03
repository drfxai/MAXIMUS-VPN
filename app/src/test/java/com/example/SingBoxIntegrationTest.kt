package com.example

import com.example.data.model.*
import com.example.vpn.sidecar.*
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.connectivity.NetworkHandoffPolicy
import com.example.vless.VlessParser
import com.example.core.AppResult
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SingBoxIntegrationTest {
    private fun node() = VlessProfile(name = "test", address = "192.0.2.1", port = 443,
        uuid = "00000000-0000-0000-0000-000000000001", security = "tls", sni = "example.com")
    private fun context() = SidecarContext(File("."), File("singbox"), 19080, "user", "secret", OperationalMode.DAILY)

    @Test fun bindsAuthenticatedLoopbackAndKeepsTlsVerification() {
        val c = SingBoxSidecar.config(SingBoxSidecar.select(node()), context())
        val inbound = c.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals("secret", inbound.getJSONArray("users").getJSONObject(0).getString("password"))
        assertEquals("node", c.getJSONObject("route").getString("final"))
        val tls = c.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")
        assertFalse(tls.getBoolean("insecure"))
        assertEquals("example.com", tls.getString("server_name"))
    }

    @Test fun allNormalizedAdaptersProduceNativeValidationFixtures() {
        val output = System.getenv("MAXIMUS_SINGBOX_FIXTURES")?.let { File(it).apply { mkdirs() } }
        for ((protocol, type) in listOf(ProtocolType.VLESS to "vless", ProtocolType.VMESS to "vmess",
                ProtocolType.TROJAN to "trojan", ProtocolType.SHADOWSOCKS to "shadowsocks")) {
            val p = node().copy(protocolType = protocol,
                security = if (protocol == ProtocolType.SHADOWSOCKS) "none" else "tls",
                encryption = when (protocol) { ProtocolType.VMESS -> "auto"; ProtocolType.SHADOWSOCKS -> "aes-128-gcm"; else -> "none" })
            val config = SingBoxSidecar.config(p, context())
            assertEquals(type, config.getJSONArray("outbounds").getJSONObject(0).getString("type"))
            output?.let { File(it, "$type.json").writeText(config.toString()) }
        }
    }

    @Test fun lossyAndUnsafeEngineConversionsAreRefused() {
        for (p in listOf(node().copy(finalMask = "{}"), node().copy(allowInsecure = true),
            node().copy(pinnedPeerCertSha256 = "ab".repeat(32)), node().copy(echConfigList = "config"),
            node().copy(transport = "xhttp"), node().copy(fingerprint = "unsafe"),
            node().copy(encryption = "mlkem768x25519plus.native.0rtt.test"))) {
            assertNotNull(SingBoxSidecar.conversionProblem(p))
        }
    }

    @Test fun xrayForwardingProfileDoesNotRecursivelyStartSingBox() {
        val p = SingBoxSidecar.select(node())
        assertTrue(SingBoxSidecar.handles(p))
        assertFalse(SingBoxSidecar.handles(p.copy(profileType = ProfileType.XRAY_JSON)))
    }

    @Test fun realityAndWebsocketSettingsSurviveAdapter() {
        val p = node().copy(security = "reality", publicKey = "public", shortId = "abcd",
            fingerprint = "chrome", transport = "ws", host = "cdn.example.com", path = "/proxy")
        val out = SingBoxSidecar.config(p, context()).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("public", out.getJSONObject("tls").getJSONObject("reality").getString("public_key"))
        assertEquals("cdn.example.com", out.getJSONObject("transport").getJSONObject("headers").getString("Host"))
    }

    @Test fun advancedTlsAndCoreSurviveShareLinkRoundTrip() {
        val p = node().copy(cipherSuites = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", finalMask = "{\"tcp\":[]}",
            fingerprint = "unsafe", engineType = EngineType.SING_BOX)
        val parsed = (VlessParser.parse(VlessParser.toUri(p)) as AppResult.Success).data
        assertEquals(p.cipherSuites, parsed.cipherSuites)
        assertEquals(p.finalMask, parsed.finalMask)
        assertEquals(p.fingerprint, parsed.fingerprint)
        assertEquals(EngineType.SING_BOX, parsed.engineType)
    }

    @Test fun subscriptionDedupPreservesDpiVariantsAndCertificatePolicies() {
        val p = node()
        for (variant in listOf(p.copy(cipherSuites = "cipher"), p.copy(finalMask = "{}"),
            p.copy(fingerprint = "firefox"), p.copy(pinnedPeerCertSha256 = "ab".repeat(32)),
            p.copy(engineType = EngineType.SING_BOX)))
            assertNotEquals(p.effectiveFingerprint, variant.effectiveFingerprint)
        assertEquals(p.effectiveFingerprint, p.copy(name = "renamed").effectiveFingerprint)
    }

    @Test fun handoffIgnoresDuplicatesButRechecksLossRestoreAndAddressChanges() {
        val policy = NetworkHandoffPolicy()
        assertFalse(policy.observe("wifi:192.0.2.1"))
        assertFalse(policy.observe("wifi:192.0.2.1"))
        assertTrue(policy.observe("cell:198.51.100.1"))
        assertTrue(policy.observe(null))
        assertFalse(policy.observe(null))
        assertTrue(policy.observe("cell:198.51.100.1"))
        assertTrue(policy.observe("cell:198.51.100.2"))
    }
}
