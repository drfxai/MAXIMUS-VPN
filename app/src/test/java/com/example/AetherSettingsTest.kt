package com.example

import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.ServerlessSidecar
import com.example.vpn.engine.ProfileExtras
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AetherSettingsTest {
    @Test fun advancedOptionsSurviveProfileAndAutomaticCandidates() {
        val settings = AetherSettings(scan = "thorough", noize = "gfw",
            endpoint = "162.159.192.1:443", apiAddress = "1.1.1.1",
            apiHost = "api.devices.cloudflare.com", fragmentSize = "8-16",
            fragmentDelay = "2-10", ech = true)
        val profile = AetherSidecar.profile(advanced = settings)
        for (candidate in com.example.vpn.connections.ConnectionEngineRunner.candidates(profile)) {
            assertEquals(settings, AetherSettings.read(ProfileExtras.read(candidate)))
        }
        val args = settings.arguments("h2", true)
        assertEquals("162.159.192.1:443", args[args.indexOf("--h2-peer") + 1])
        assertEquals("api.devices.cloudflare.com", args[args.indexOf("--api-sni") + 1])
        assertEquals("auto", args[args.indexOf("--ech") + 1])
        assertFalse(args.contains("--noize"))
        assertTrue(settings.arguments("wireguard", true).contains("--noize"))
        assertTrue(settings.arguments("wiw", true).contains("--wiw-outer"))
        assertTrue(settings.arguments("mim", true).contains("--mim-outer"))
        assertFalse(settings.arguments("h3", false).contains("--fragment-size"))
    }

    @Test fun everyFingerprintAndAdvancedExitSettingSurvivesPersistence() {
        val commands = JSONObject()
        for (name in com.example.vpn.sidecar.AetherTlsProfiles.names) {
            val settings = AetherSettings(fingerprint = name, apiSni = "api.devices.cloudflare.com",
                exitFinalMask = "{\"tcp\":[]}", exitDialMode = "oob.1", alias = "My WARP")
            assertEquals(settings, AetherSettings.read(settings.write(JSONObject())))
            val args = settings.arguments("h2", true)
            assertTrue(args.contains("--tls-ciphers"))
            assertTrue(args.contains("--tls-sigalgs"))
            commands.put(name, org.json.JSONArray(listOf("--masque", "--h2") + args))
        }
        System.getenv("MAXIMUS_SERVERLESS_FIXTURES")?.let {
            File(File(it).parentFile, "aether-options.json").writeText(commands.toString())
        }
        assertNotNull(AetherSettings(apiSni = "api.devices.cloudflare.com", apiHost = "api.cloudflareclient.com").problem())
        assertNotNull(AetherSettings(tor = "reverse", psiphon = "reverse").problem())
        assertNotNull(AetherSettings(exitFinalMask = "[]").problem())
        assertNotNull(AetherSettings(tor = "reverse").problem("wireguard"))
    }

    @Test fun invalidNativeValuesFailBeforeLaunchingOrRegistering() {
        for (bad in listOf("1.1.1.1", "1.1.1.1:0", "1.1.1.1:65536", "host.example:443",
            "2606:4700::1:443", "[2606:4700::1]bad:443", "1.1.1.1:443 --tls-verify", "1.1.1.1:+443")) {
            assertNotNull(bad, AetherSettings(endpoint = bad).problem())
        }
        assertNull(AetherSettings(endpoint = "[2606:4700::1]:443").problem())
        assertNotNull(AetherSettings(apiAddress = "example.com").problem())
        assertNotNull(AetherSettings(apiHost = "example.com").problem())
        assertNotNull(AetherSettings(fragmentSize = "32-8").problem())
        assertNotNull(AetherSettings(fragmentDelay = "1001").problem())
        assertNotNull(AetherSettings(scan = "unknown").problem())
        assertNotNull(AetherSettings(noize = "unknown").problem())
        assertEquals(AetherSettings(), AetherSettings.read(JSONObject()))
    }

    @Test fun googlePreferenceKeepsAuthenticatedFallbackAndRoutingTags() {
        for (variant in listOf("fragA", "fragB", "oob")) for (family in AetherSidecar.families) {
            val source = File("src/main/assets/serverless/Serverless-$variant.jsonc").readText()
            val normal = ServerlessSidecar.config(source, 12345, "u", "p", family)
            val google = ServerlessSidecar.config(source, 12345, "u", "p", family, "google")
            val servers = google.getJSONObject("dns").getJSONArray("servers")
            assertEquals("https+one://dns.google/dns-query", servers.getJSONObject(0).getString("address"))
            assertFalse(servers.getJSONObject(0).optBoolean("finalQuery"))
            assertEquals("https+one://cloudflare-dns.com/dns-query", servers.getJSONObject(1).getString("address"))
            assertTrue(servers.getJSONObject(1).getBoolean("finalQuery"))
            assertEquals(normal.getJSONArray("outbounds").toString(), google.getJSONArray("outbounds").toString())
            assertEquals(normal.getJSONObject("routing").toString(), google.getJSONObject("routing").toString())
        }
    }
}
