package com.example

import com.example.vpn.hub.ConfigHealthScorer
import com.example.vpn.hub.ConfigSyncManager
import com.example.vpn.hub.ConfigValidationPipeline
import com.example.vpn.hub.FreeConfigProvider
import com.example.vpn.hub.HealthState
import com.example.vpn.hub.PerformanceState
import com.example.vpn.hub.ProviderRegistry
import com.example.vpn.hub.SecurityState
import com.example.xray.RealDelayProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1.0.1 step 12: free configs are untrusted until a real request carries traffic through them. */
class FreeConfigHubTest {
    private val provider = FreeConfigProvider("p", "Public list", listOf("https://example.com/sub"), FreeConfigProvider.Kind.PUBLIC)
    private val reality = "vless://11111111-1111-1111-1111-111111111111@203.0.113.7:443?security=reality&sni=www.example.com&pbk=abc&sid=ab&fp=chrome&type=tcp&flow=xtls-rprx-vision#R"
    private val plain = "vless://22222222-2222-2222-2222-222222222222@203.0.113.8:80?security=none&type=ws&path=%2F#Plain"
    private val insecure = "trojan://pw@203.0.113.9:443?security=tls&sni=t.example&allowInsecure=1#T"
    private val privateAddr = "vless://33333333-3333-3333-3333-333333333333@10.10.34.36:443?security=reality&sni=www.example.com&pbk=abc&sid=ab&type=tcp#Priv"
    private val tuic = "tuic://44444444-4444-4444-4444-444444444444:pw@203.0.113.10:443?sni=t.example#Tuic"

    @Test fun onlyEncryptedVerifiableConfigsPassAndTheRestAreQuarantinedWithAReason() {
        val r = ConfigValidationPipeline.process(listOf(reality, reality, plain, insecure, privateAddr, tuic, "garbage").joinToString("\n"), provider)
        // The importer drops allowInsecure, so the Trojan node is kept with certificate checks on.
        assertEquals(listOf("203.0.113.7", "203.0.113.9"), r.accepted.map { it.profile.address })
        assertFalse(r.accepted.any { it.profile.allowInsecure })
        assertTrue(r.accepted.all { it.security == SecurityState.UNVERIFIED && it.profile.id.startsWith("hub-p-") })
        val reasons = r.quarantined.associate { it.profile.address to it.security }
        assertEquals(mapOf("203.0.113.8" to SecurityState.INSECURE, "10.10.34.36" to SecurityState.INSECURE), reasons)
        assertFalse(r.accepted.any { it.approved })
        val trojan = r.accepted[1].profile
        assertEquals("Does not check the server's certificate",
            ConfigValidationPipeline.securityProblem(trojan.copy(allowInsecure = true)))
    }

    @Test fun aDuplicateFromAnotherProviderIsDropped() {
        val first = ConfigValidationPipeline.process(reality, provider)
        val known = first.accepted.map { it.profile.effectiveFingerprint }.toSet()
        assertTrue(ConfigValidationPipeline.process(reality, provider.copy(id = "q"), known).accepted.isEmpty())
    }

    @Test fun onlyNodesThatCarriedTrafficAreApproved() {
        val registry = ProviderRegistry(officialUrls = emptyList(), extra = listOf(provider,
            FreeConfigProvider("down", "Down", listOf("https://down.example/sub"), FreeConfigProvider.Kind.PUBLIC)))
        val twoNodes = reality + "\n" + reality.replace("203.0.113.7", "203.0.113.70")
        val sync = ConfigSyncManager(registry, fetch = { if (it.id == "down") error("timeout") else twoNodes },
            probe = { list, _ -> list.map { if (it.address == "203.0.113.7") RealDelayProbe.Outcome.Delay(250) else RealDelayProbe.Outcome.Failed("reset") } }
        ).sync()
        assertEquals(mapOf("down" to "timeout"), sync.failedProviders)
        val approved = sync.approved.single()
        assertEquals(SecurityState.VERIFIED, approved.security)
        assertEquals(PerformanceState.FAST, approved.performance)
        assertEquals(HealthState.OFFLINE, sync.nodes.single { it.profile.address == "203.0.113.70" }.health)
    }

    @Test fun theOfficialSourceIsListedOnlyOnceItExists() {
        assertTrue(ProviderRegistry(officialUrls = emptyList()).providers.isEmpty())
        assertEquals(FreeConfigProvider.Kind.OFFICIAL, ProviderRegistry(officialUrls = listOf("https://x.example/sub")).providers.single().kind)
        assertEquals(HealthState.DEGRADED, ConfigHealthScorer.health(1, 3))
        assertEquals(PerformanceState.SLOW, ConfigHealthScorer.performance(2000))
    }
}
