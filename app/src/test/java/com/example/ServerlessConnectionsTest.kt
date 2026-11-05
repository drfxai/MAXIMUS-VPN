package com.example

import com.example.vpn.connections.ConnectionEngineRunner
import com.example.data.model.*
import com.example.vpn.engine.ProfileExtras
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
            val dns = result.getJSONObject("dns")
            assertEquals("challenges.cloudflare.com", dns.getJSONObject("hosts").getString("cloudflare-dns.com"))
            val servers = dns.getJSONArray("servers")
            assertEquals("https+one://cloudflare-dns.com/dns-query", servers.getJSONObject(0).getString("address"))
            assertFalse(servers.getJSONObject(0).optBoolean("finalQuery"))
            assertEquals("https+one://1.1.1.1/dns-query", servers.getJSONObject(1).getString("address"))
            val rules = result.getJSONObject("routing").getJSONArray("rules")
            assertTrue((0 until rules.length()).any { index ->
                val tags = rules.getJSONObject(index).optJSONArray("inboundTag")
                tags?.optString(0) == "no-filter-dns" && tags.optString(1) == "no-filter-dns-ip"
            })
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
    @Test fun preparationFailuresTryRemainingDirectVariantsButCancellationStopsImmediately() {
        val attempted = mutableListOf<String>()
        var cancel = false
        val engine = object : SidecarEngine {
            override val id = ServerlessSidecar.ID
            override val binary = id
            override fun handles(profile: VlessProfile) = ServerlessSidecar.handles(profile)
            override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
                attempted += ProfileExtras.read(profile).getString("variant")
                if (cancel) throw kotlinx.coroutines.CancellationException("Test cancellation")
                error("Test preparation failure")
            }
        }
        val context = SidecarContext(File("."), File("unused"), 12345, "u", "p", OperationalMode.DAILY)
        val failure = assertThrows(ConnectionEngineRunner.NoVerifiedPath::class.java) {
            ConnectionEngineRunner.start(engine, ServerlessSidecar.profile(), AppSettings(), context)
        }
        assertEquals(listOf("fragA", "fragB", "oob"), attempted)
        assertTrue(failure.message.orEmpty().contains("configuration preparation failed"))
        attempted.clear()
        cancel = true
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            ConnectionEngineRunner.start(engine, ServerlessSidecar.profile(), AppSettings(), context)
        }
        assertEquals(listOf("fragA"), attempted)
    }
    @Test fun invalidSelectionsAndPortsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { ServerlessSidecar.profile("unknown") }
        assertThrows(IllegalArgumentException::class.java) { AetherSidecar.profile("insecure") }
        assertThrows(IllegalArgumentException::class.java) { ServerlessSidecar.config("{}", 0, "u", "p", "auto") }
    }
}
