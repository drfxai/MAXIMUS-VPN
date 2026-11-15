package com.example

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import com.example.mihomo.MihomoParser
import com.example.vpn.connectivity.TlsResilienceEngine
import com.example.vpn.sidecar.SingBoxSidecar
import com.example.vpn.stealth.StealthPathFinder
import com.example.vpn.stealth.StealthVariants
import com.example.xray.RealDelayProbe
import com.example.xray.RealityFingerprint
import com.example.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Xray REALITY servers v26.9.8+ reject a ClientHello without an X25519MLKEM768 key share
 * (XTLS/Xray-core#7259): edge, ios, qq, random, android and 360 fail; chrome, firefox, safari, randomized work.
 */
class RealityPostQuantumFingerprintTest {
    private val reality = VlessProfile(
        id = "pq1", name = "PQ REALITY", address = "203.0.113.20", port = 443,
        uuid = "11111111-2222-3333-4444-555555555555", transport = "tcp", security = "reality",
        sni = "www.example.com", publicKey = "pbk", shortId = "ab", flow = "xtls-rprx-vision", fingerprint = "edge"
    )

    @Before fun reset() {
        StealthPathFinder.memory.clear()
        RealityFingerprint.resetLogForTests()
    }

    private fun realityFingerprintOf(p: VlessProfile): String? =
        JSONObject(XrayConfigBuilder.buildJson(p, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("realitySettings").optString("fingerprint").takeIf { it.isNotEmpty() }

    @Test fun fingerprintsWithoutAPostQuantumShareAreSentAsAPostQuantumBrowser() {
        for (fp in listOf("edge", "ios", "qq", "random", "android", "360", "unsafe", "helloedge_106", "hellochrome_120_pq"))
            assertTrue("$fp must not be PQ-capable", !RealityFingerprint.isPqCapable(fp))
        assertEquals("chrome", RealityFingerprint.forReality("edge"))
        assertEquals("safari", RealityFingerprint.forReality("ios"))
        assertEquals("chrome", RealityFingerprint.forReality("android"))
        assertEquals("chrome", RealityFingerprint.forReality("360"))
        assertEquals("chrome", RealityFingerprint.forReality("qq"))
        assertEquals("chrome", RealityFingerprint.forReality("random"))
        assertEquals("firefox", RealityFingerprint.forReality("hellofirefox_120"))
        for (fp in listOf("chrome", "firefox", "safari", "randomized", "hellochrome_155", ""))
            assertEquals(fp, RealityFingerprint.forReality(fp))
    }

    @Test fun realityOutboundGetsAPostQuantumFingerprintAndTheSavedChoiceIsLogged() {
        val logs = mutableListOf<String>()
        RealityFingerprint.forRealityLogged("Edge", reality.name) { logs += it }
        RealityFingerprint.forRealityLogged("Edge", reality.name) { logs += it }
        assertEquals("logged once per profile and fingerprint", 1, logs.size)
        assertTrue(logs.single().contains("'Edge'") && logs.single().contains("'chrome'"))

        assertEquals("chrome", realityFingerprintOf(reality))
        assertEquals("safari", realityFingerprintOf(reality.copy(fingerprint = "ios")))
        assertEquals("firefox", realityFingerprintOf(reality.copy(fingerprint = "firefox")))
        assertEquals("chrome", realityFingerprintOf(reality.copy(fingerprint = "unsafe")))
        // Blank stays blank: Xray's default is HelloChrome_Auto (Chrome 155 in v26.11.9).
        assertNull(realityFingerprintOf(reality.copy(fingerprint = "")))
        // The saved profile itself is not rewritten.
        assertEquals("edge", reality.fingerprint)
    }

    @Test fun plainTlsKeepsTheFingerprintTheUserChose() {
        val tls = reality.copy(security = "tls", flow = "", publicKey = "", shortId = "")
        val out = JSONObject(XrayConfigBuilder.buildJson(tls, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("edge", out.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("fingerprint"))
    }

    @Test fun importedXrayJsonRealityOutboundsAreSwitchedToo() {
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"203.0.113.20","port":443,
            "users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"www.example.com",
            "fingerprint":"qq","publicKey":"pbk","shortId":"ab"}}}]}"""
        val p = VlessProfile(name = "json", profileType = ProfileType.XRAY_JSON, rawConfig = raw)
        val reality = JSONObject(XrayConfigBuilder.buildJson(p, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("chrome", reality.getString("fingerprint"))
    }

    @Test fun stealthRotationUsesOnlyPostQuantumFingerprintsInOrder() {
        // edge goes out as chrome, so the rotation is firefox, then safari.
        assertEquals(listOf("firefox", "safari"), StealthVariants.fingerprintRotation(reality))
        assertEquals(listOf("chrome", "safari"), StealthVariants.fingerprintRotation(reality.copy(fingerprint = "firefox")))
        assertEquals(listOf("chrome", "firefox"), StealthVariants.fingerprintRotation(reality.copy(fingerprint = "safari")))
        assertEquals(listOf("firefox", "safari"), StealthVariants.fingerprintRotation(reality.copy(fingerprint = "")))
        val variants = StealthVariants.of(reality)
        val fps = variants.map { it.profile.fingerprint }.toSet()
        assertTrue("every alternate is PQ-capable: $fps", fps.all { it == "edge" || it in RealityFingerprint.PQ_ROTATION })
        assertNotNull(variants.firstOrNull { it.key == StealthVariants.fingerprintKey("firefox") })
        assertNotNull(variants.firstOrNull { it.key == StealthVariants.fingerprintKey("safari") })
        assertEquals("firefox", StealthVariants.fingerprintOf(StealthVariants.fingerprintKey("firefox")))
        assertNull(StealthVariants.fingerprintOf(StealthVariants.FRAGMENT))
        // Go TLS on plain TLS keeps its cipher suites: no fingerprint alternates.
        assertTrue(StealthVariants.fingerprintRotation(reality.copy(security = "tls", fingerprint = "unsafe")).isEmpty())
    }

    @Test fun theWinningFingerprintIsRememberedForTheNetworkAndTriedFirstByOtherRealityProfiles() {
        val memory = HashMap<String, String>()
        // Simulated network: only Safari's hello gets through.
        val probe: (List<VlessProfile>, Int) -> List<RealDelayProbe.Outcome> = { list, _ ->
            list.map { if (it.fingerprint == "safari" && it.finalMask.isBlank()) RealDelayProbe.Outcome.Delay(150) else RealDelayProbe.Outcome.Failed("real certificate") }
        }
        val win = StealthPathFinder(probe, log = {}, memory = memory).choose(reality, { it }, emptyList())
        assertEquals("safari", win.profile.fingerprint)
        assertEquals(StealthVariants.fingerprintKey("safari"), memory[reality.id])
        assertEquals("safari", memory[RealityFingerprint.NETWORK_WINNER_KEY])

        // Another REALITY server on the same network starts with Safari: one request.
        val other = reality.copy(id = "pq2", address = "203.0.113.21", fingerprint = "chrome")
        var calls = 0
        val again = StealthPathFinder({ l, t -> calls++; probe(l, t) }, log = {}, memory = memory).choose(other, { it }, emptyList())
        assertEquals("safari", again.profile.fingerprint)
        assertEquals(1, calls)

        // A REALITY profile that already sends Safari is used as saved.
        val safari = reality.copy(id = "pq3", fingerprint = "safari")
        assertEquals("as saved", StealthPathFinder(probe, log = {}, memory = memory).firstPath(safari, safari).label)
        // Plain TLS profiles do not inherit the REALITY winner.
        val tls = reality.copy(id = "t1", security = "tls", flow = "", publicKey = "", shortId = "", fingerprint = "chrome")
        assertEquals("as saved", StealthPathFinder(probe, log = {}, memory = memory).firstPath(tls, tls).label)
    }

    @Test fun tlsCheckNotesTheSwapAndKnowsChrome155() {
        assertTrue("hellochrome_155" in TlsResilienceEngine.XRAY_FINGERPRINTS)
        val a = TlsResilienceEngine.assess(reality, TlsResilienceEngine.Engine.XRAY)
        assertTrue(a.accepted)
        assertTrue(a.notes.toString(), a.notes.any { it.contains("post-quantum") && it.contains("'chrome'") })
        assertFalse(TlsResilienceEngine.assess(reality.copy(fingerprint = "chrome"), TlsResilienceEngine.Engine.XRAY)
            .notes.any { it.contains("post-quantum") })
    }

    @Test fun realityIsNeverMovedToSingBoxAutomatically() {
        val p = reality.copy(fingerprint = "chrome")
        assertNull("the conversion itself is lossless", SingBoxSidecar.conversionProblem(p))
        assertEquals(SingBoxSidecar.REALITY_PQ_PROBLEM, SingBoxSidecar.autoSelectProblem(p))
        assertNull(SingBoxSidecar.autoSelectProblem(p.copy(security = "tls", flow = "", publicKey = "", shortId = "")))
    }

    @Test fun mihomoRealityAsksForThePostQuantumShare() {
        val yaml = MihomoParser.buildMihomoYaml(reality)
        assertTrue(yaml, yaml.contains("client-fingerprint: \"chrome\""))
        assertTrue(yaml, yaml.contains("support-x25519mlkem768: true"))
    }

    @Test fun lanProxyInboundIsOffByDefault() {
        val settings = AppSettings()
        assertFalse(settings.hotspotShareEnabled)
        assertFalse(XrayConfigBuilder.hotspotShareActive(settings))
        val inbounds = JSONObject(XrayConfigBuilder.buildJson(reality, settings)).getJSONArray("inbounds")
        for (i in 0 until inbounds.length())
            assertFalse(inbounds.getJSONObject(i).toString(), inbounds.getJSONObject(i).optString("listen") == "0.0.0.0")
        // Even switched on, it is never opened without credentials.
        assertFalse(XrayConfigBuilder.hotspotShareActive(settings.copy(hotspotShareEnabled = true)))
    }
}
