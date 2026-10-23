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
