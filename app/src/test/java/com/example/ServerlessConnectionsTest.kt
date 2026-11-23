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
            assertEquals(12345, inbound.getInt("port"))
            assertTrue(inbound.getJSONObject("sniffing").getBoolean("routeOnly"))
            assertEquals("password", inbound.getJSONObject("settings").getString("auth"))
            assertEquals("session-user", inbound.getJSONObject("settings").getJSONArray("accounts")
                .getJSONObject(0).getString("user"))
            val outbounds = result.getJSONArray("outbounds")
            val tagPrefix = if (variant == "oob") "tcp-oob" else "tcp-fragment-tls"
            val byTag = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
                .associateBy { it.getString("tag") }
            val masked = byTag.getValue(tagPrefix).getJSONObject("streamSettings")
            val firstTry = byTag.getValue("$tagPrefix-try").getJSONObject("streamSettings")
            assertEquals(tagPrefix, firstTry.getJSONObject("sockopt").getJSONObject("fallback")
                .getString("noResponseFallbackDialerProxy"))
            assertEquals("merge", firstTry.getJSONObject("finalmask").getJSONArray("tcp")
                .getJSONObject(0).getString("type"))
            val mask = masked.getJSONObject("finalmask").getJSONArray("tcp").getJSONObject(1)
            assertEquals("fragment", mask.getString("type"))
            when (variant) {
                "fragA" -> assertEquals("[\"6\",\"32\",\"1\"]",
                    mask.getJSONObject("settings").getJSONArray("lengths").toString())
                "fragB" -> assertEquals("[\"1\",\"37\",\"1\"]",
                    mask.getJSONObject("settings").getJSONArray("lengths").toString())
                "oob" -> assertEquals("oob.0,0", masked.getJSONObject("sockopt").getString("dialMode"))
            }
            assertFalse(result.toString().contains("fromMitM"))
            assertFalse(result.toString().contains("\"redirect\""))
            assertFalse(result.toString().contains("geosite:"))
            assertFalse(result.toString().contains("geoip:"))
            assertFalse(result.toString().contains("localhost"))
            val dns = result.getJSONObject("dns")
            assertEquals("1.1.1.1", dns.getJSONObject("hosts").getJSONArray("cloudflare-dns.com").getString(0))
            assertEquals("8.8.8.8", dns.getJSONObject("hosts").getJSONArray("dns.google").getString(0))
            val servers = dns.getJSONArray("servers")
            assertEquals("https+one://cloudflare-dns.com/dns-query", servers.getJSONObject(0).getString("address"))
            assertFalse(servers.getJSONObject(0).optBoolean("finalQuery"))
            assertEquals("https+one://dns.google/dns-query", servers.getJSONObject(1).getString("address"))
            assertTrue(servers.getJSONObject(1).getBoolean("finalQuery"))
            val rules = result.getJSONObject("routing").getJSONArray("rules")
            val routes = (0 until rules.length()).map { rules.getJSONObject(it) }
            assertTrue(routes.all { it.getString("outboundTag") in byTag })
            val doh = routes.single { it.optJSONArray("inboundTag")?.optString(0) == "no-filter-dns" }
            assertEquals("no-filter-dns-ip", doh.getJSONArray("inboundTag").getString(1))
            assertEquals(tagPrefix, doh.getString("outboundTag"))
            val retry = routes.single { it.getString("outboundTag") == "$tagPrefix-try" }
            assertEquals("tls", retry.getJSONArray("protocol").getString(0))
            System.getenv("MAXIMUS_SERVERLESS_FIXTURES")?.let { directory ->
                File(directory).mkdirs()
                File(directory, "$variant.json").writeText(result.toString())
            }
        }
    }
    @Test fun pinnedDirectOutboundsApplyTheSelectedExitWithoutBypassingDpiMasks() {
        for (variant in listOf("fragA", "fragB", "oob")) {
            val source = File("src/main/assets/serverless/Serverless-$variant.jsonc").readText()
            val exit = JSONObject().put("protocol", "socks").put("settings", JSONObject().put("servers",
                org.json.JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", 19080))))
            val root = ServerlessSidecar.config(source, 12345, "u", "p", "auto", exitOutbound = exit)
            val out = root.getJSONArray("outbounds")
            var linked = 0
            for (i in 0 until out.length()) {
                val outbound = out.getJSONObject(i)
                if (outbound.optString("protocol") !in setOf("direct", "freedom")) continue
                assertEquals("serverless-exit", outbound.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
                linked++
            }
            assertTrue("The pinned source uses direct outbounds", linked > 0)
            assertEquals("serverless-exit", out.getJSONObject(out.length() - 1).getString("tag"))
            assertFalse(root.toString().contains("noResponseFallbackDialerProxy"))
            assertTrue("DPI masks must stay on the original direct outbounds", root.toString().contains("finalmask"))
            System.getenv("MAXIMUS_SERVERLESS_FIXTURES")?.let { directory ->
                val exitFixtures = File(directory, "exit").apply { mkdirs() }
                File(exitFixtures, "$variant.json").writeText(root.toString())
            }
        }
    }
    @Test fun advancedDirectDialModeIsAppliedToThePinnedProtocolAlias() {
        val source = File("src/main/assets/serverless/Serverless-oob.jsonc").readText()
        val root = ServerlessSidecar.config(source, 12345, "u", "p", "auto",
            advanced = AetherSettings(exitDialMode = "oob.1", exitFinalMask = "{\"tcp\":[]}"))
        val out = root.getJSONArray("outbounds")
        for (i in 0 until out.length()) {
            val outbound = out.getJSONObject(i)
            if (outbound.optString("protocol") != "direct") continue
            assertEquals("oob.1", outbound.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialMode"))
            assertEquals(0, outbound.getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("tcp").length())
        }
    }

    @Test fun ipFamilyRemovesConflictingHappyEyeballs() {
        val source = File("src/main/assets/serverless/Serverless-fragA.jsonc").readText()
        val result = ServerlessSidecar.config(source, 12345, "u", "p", "ipv4")
        assertFalse(result.toString().contains("happyEyeballs"))
        assertTrue(result.toString().contains("ForceIPv4"))
        assertFalse(result.toString().contains("ForceIPv6v4"))
    }
    @Test fun dohBootstrapDoesNotNeedNetworkDnsAndRespectsSelectedFamily() {
        for (variant in listOf("fragA", "fragB", "oob")) for (family in listOf("auto", "ipv4", "ipv6")) {
            val source = File("src/main/assets/serverless/Serverless-$variant.jsonc").readText()
            val result = ServerlessSidecar.config(source, 12345, "u", "p", family)
            val dns = result.getJSONObject("dns")
            val servers = dns.getJSONArray("servers")
            for (i in 0 until servers.length()) {
                val server = servers.getJSONObject(i)
                val host = java.net.URI(server.getString("address")).host
                val ips = dns.getJSONObject("hosts").getJSONArray(host)
                assertTrue(ips.length() > 0)
                for (j in 0 until ips.length()) {
                    val ip = ips.getString(j)
                    assertTrue("Bootstrap must be a literal IP", ip.matches(Regex("[0-9a-fA-F:.]+")))
                    if (family == "ipv4") assertFalse(ip.contains(':'))
                    if (family == "ipv6") assertTrue(ip.contains(':'))
                }
            }
            assertFalse(result.toString().contains("challenges.cloudflare.com"))
        }
    }
    @Test fun v65ProfilesKeepOriginalDestinationsDotAndSendTheCloudflareDomainDirect() {
        assertEquals("9b62ddf9e8bc83432d4b0bcf8be4c963bc8cb300", ServerlessSidecar.CONFIG_REVISION)
        for (variant in listOf("fragA", "fragB", "oob")) {
            val source = File("src/main/assets/serverless/Serverless-$variant.jsonc").readText()
            assertTrue(source.contains("\"Serverless-v65-$variant\""))
            assertTrue(source.contains("\"min\": \"26.11.23\""))
            val result = ServerlessSidecar.config(source, 12345, "u", "p", "auto")
            val text = result.toString()
            // The desktop edge redirection (v65 moved it from 104.18.0.8 to 188.114.99.0) is replaced by original destinations.
            assertFalse(text.contains("188.114.99.0"))
            assertFalse(text.contains("104.18.0.8"))
            assertFalse(text.contains(".redirect"))
            val rules = result.getJSONObject("routing").getJSONArray("rules")
            val routes = (0 until rules.length()).map { rules.getJSONObject(it) }
            // DNS-over-TLS stays open for Android Private DNS; the geosite DoH/ads block has no entries left and is dropped.
            assertTrue(routes.none { it.optString("port") == "853" })
            assertTrue(routes.none { r -> r.optJSONArray("domain")?.let { d -> (0 until d.length()).any { d.getString(it).startsWith("geosite:") } } == true })
            val cloudflare = routes.single { r -> r.optString("network") == "tcp" &&
                r.optJSONArray("domain")?.let { d -> (0 until d.length()).any { d.getString(it) == "domain:cloudflare.com" } } == true }
            // v65 sends cloudflare.com straight out, as it does the domestic list.
            assertEquals("tcp-direct", cloudflare.getString("outboundTag"))
            // v65 asks the core for assurance-first interleaving; the auto family keeps it.
            assertTrue(text.contains("assuranceFirstInterleave"))
        }
    }
    @Test fun automaticSelectionNeverCrossesPrivacyModes() {
        val direct = ConnectionEngineRunner.candidates(ServerlessSidecar.profile())
        assertEquals(3, direct.size)
        assertTrue(direct.all(ServerlessSidecar::handles))
        val warp = ConnectionEngineRunner.candidates(AetherSidecar.profile())
        assertEquals(listOf("h2", "h3", "wireguard"), warp.map { ProfileExtras.read(it).getString("carrier") })
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
