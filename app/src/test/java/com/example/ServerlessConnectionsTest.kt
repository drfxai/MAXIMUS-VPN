package com.example

import com.example.vpn.connections.AccessProbe
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.sidecar.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ServerlessConnectionsTest {
    @Test fun directProfilesPreserveDpiInstructionsAndAuthenticateLoopback() {
        for (variant in listOf("fragA", "fragB", "oob")) {
            val source = File("src/main/assets/serverless/Serverless-$variant.jsonc").readText()
            val result = ServerlessSidecar.config(source, 12345, "session-user", "session-secret", "auto")
            val inbound = result.getJSONArray("inbounds").getJSONObject(0)
            assertEquals("127.0.0.1", inbound.getString("listen"))
            assertEquals("password", inbound.getJSONObject("settings").getString("auth"))
            assertEquals(12345, inbound.getInt("port"))
            val original = JSONObject(source).getJSONArray("outbounds")
            assertEquals(original.toString(), result.getJSONArray("outbounds").toString())
            assertFalse(result.toString().contains("geosite:"))
            assertFalse(result.toString().contains("geoip:"))
            assertFalse(result.toString().contains("localhost"))
            System.getenv("MAXIMUS_SERVERLESS_FIXTURES")?.let { directory ->
                File(directory).mkdirs()
                File(directory, "$variant.json").writeText(result.toString())
            }
        }
    }
    @Test fun ipFamilyRemovesConflictingHappyEyeballs() {
        val source = File("src/main/assets/serverless/Serverless-fragA.jsonc").readText()
        val result = ServerlessSidecar.config(source, 12345, "u", "p", "ipv4")
        assertFalse(result.toString().contains("happyEyeballs"))
        assertTrue(result.toString().contains("ForceIPv4"))
        assertFalse(result.toString().contains("ForceIPv6v4"))
    }
    @Test fun automaticSelectionNeverCrossesPrivacyModes() {
        val direct = ConnectionEngineRunner.candidates(ServerlessSidecar.profile())
        assertEquals(3, direct.size)
        assertTrue(direct.all(ServerlessSidecar::handles))
        val warp = ConnectionEngineRunner.candidates(AetherSidecar.profile())
        assertEquals(2, warp.size)
        assertTrue(warp.all(AetherSidecar::handles))
        assertEquals(1, ConnectionEngineRunner.candidates(ServerlessSidecar.profile("oob")).size)
    }
    @Test fun chunkedDnsResponsesAreDecodedWithoutGuessing() {
        assertEquals("abcdef", AccessProbe.decodeChunks("3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n"))
        assertThrows(IllegalArgumentException::class.java) { AccessProbe.decodeChunks("ffff\r\nshort") }
    }
    @Test fun invalidSelectionsAndPortsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { ServerlessSidecar.profile("unknown") }
        assertThrows(IllegalArgumentException::class.java) { AetherSidecar.profile("insecure") }
        assertThrows(IllegalArgumentException::class.java) { ServerlessSidecar.config("{}", 0, "u", "p", "auto") }
    }
}
