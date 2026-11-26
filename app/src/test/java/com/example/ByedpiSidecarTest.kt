package com.example

import com.example.data.model.*
import com.example.vpn.sidecar.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ByedpiSidecarTest {
    private val original = VlessProfile(name = "test", address = "104.16.1.1", port = 443, uuid = "test-id",
        security = "tls", sni = "front.example", host = "origin.example", transport = "ws", path = "/proxy")
    private val context = SidecarContext(File("."), File("/unused/libbyedpi.so"), 19080,
        "test-user", "test-password-123456789", AppSettings().operationalMode)

    @Test fun nativeLaunchRestrictsDestinationAndUsesActualFakePackets() {
        val profile = ByedpiSidecar.enable(original, "decoy.example", 8)
        val launch = ByedpiSidecar.prepare(profile, AppSettings(), context)
        assertTrue(launch.command.containsAll(listOf("--fake", "-1", "--ttl", "8", "--fake-sni", "decoy.example", "--no-udp", "--no-domain")))
        assertEquals(original.address, launch.environment["MAXIMUS_PEER_IP"])
        assertEquals("443", launch.environment["MAXIMUS_PEER_PORT"])
        assertNotEquals(original.effectiveFingerprint, profile.effectiveFingerprint)
        assertNotEquals(profile.effectiveFingerprint, ByedpiSidecar.enable(original, "decoy.example", 4).effectiveFingerprint)
    }

    @Test fun md5SignatureIsAnExplicitValidatedFlag() {
        val plain = ByedpiSidecar.enable(original, "auth.vercel.com", 8)
        assertFalse(ByedpiSidecar.prepare(plain, AppSettings(), context).command.contains("--md5sig"))
        val signed = ByedpiSidecar.enable(original, "auth.vercel.com", 32, md5sig = true)
        val command = ByedpiSidecar.prepare(signed, AppSettings(), context).command
        assertTrue(command.containsAll(listOf("--fake", "-1", "--ttl", "32", "--md5sig", "--fake-sni", "auth.vercel.com")))
        assertTrue(command.indexOf("--md5sig") > command.indexOf("--fake"))
        assertNotEquals(ByedpiSidecar.enable(original, "auth.vercel.com", 32).effectiveFingerprint, signed.effectiveFingerprint)
        // Without MD5 the fingerprint is unchanged from earlier builds' formula.
        val legacy = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${original.effectiveFingerprint}|byedpi|auth.vercel.com|8".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(legacy, plain.effectiveFingerprint)
        // A non-boolean flag is rejected; wrapping removes it from the runtime profile.
        val bad = signed.copy(extraSettings = JSONObject(signed.extraSettings).put(ByedpiSidecar.KEY_MD5SIG, "yes; rm").toString())
        assertNotNull(ByedpiSidecar.problem(bad))
        assertFalse(JSONObject(JSONObject(ByedpiSidecar.wrap(signed, context).rawConfig).toString()).toString().contains("md5"))
    }

    @Test fun wrapperPreservesRealTlsCredentialsAndAddsOnlyDialer() {
        val profile = ByedpiSidecar.enable(original, "decoy.example", 8)
        val wrapped = ByedpiSidecar.wrap(profile, context)
        val out = JSONObject(wrapped.rawConfig).getJSONArray("outbounds")
        assertEquals(2, out.length())
        val real = out.getJSONObject(0)
        assertEquals("vless", real.getString("protocol"))
        assertEquals("test-id", real.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0).getString("id"))
        val stream = real.getJSONObject("streamSettings")
        assertEquals("front.example", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertFalse(stream.getJSONObject("tlsSettings").optBoolean("allowInsecure"))
        assertEquals("fake-sni-dialer", stream.getJSONObject("sockopt").getString("dialerProxy"))
        val health = ByedpiSidecar.addHealthInbound(JSONObject(), wrapped).getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", health.getString("listen"))
        assertEquals("password", health.getJSONObject("settings").getString("auth"))
    }

    @Test fun incompatibleOrUnsafeProfilesAreRejected() {
        for (profile in listOf(original.copy(allowInsecure = true), original.copy(address = "127.0.0.1"),
            original.copy(security = "reality"), original.copy(finalMask = "{}"))) {
            assertThrows(IllegalArgumentException::class.java) { ByedpiSidecar.enable(profile, "decoy.example", 8) }
        }
        assertThrows(IllegalArgumentException::class.java) { ByedpiSidecar.enable(original, "x;id", 8) }
        assertThrows(IllegalArgumentException::class.java) { ByedpiSidecar.enable(original, "decoy.example", 64) }
    }
}
