package com.example

import com.example.panels.CleanIpOptimizer
import com.example.data.model.VlessProfile
import org.junit.Assert.*
import org.junit.Test

class EdgeOptimizerTest {
    private val profile = VlessProfile(name = "worker", address = "example.workers.dev", port = 443,
        uuid = "123", transport = "ws", security = "tls", sni = "example.workers.dev", host = "example.workers.dev", path = "/vl/path")
    @Test fun eligibilityProtectsUnrelatedConfigurations() {
        assertTrue(CleanIpOptimizer.eligible(profile))
        assertFalse(CleanIpOptimizer.eligible(profile.copy(security = "reality")))
        assertFalse(CleanIpOptimizer.eligible(profile.copy(transport = "tcp")))
        assertFalse(CleanIpOptimizer.eligible(profile.copy(sni = "")))
        assertFalse(CleanIpOptimizer.eligible(profile.copy(rawConfig = "{}")))
    }
    @Test fun variantsPreserveAuthenticationAndTlsIdentity() {
        val result = CleanIpOptimizer.Result("104.16.1.1", 100, 5, 3)
        val changed = CleanIpOptimizer.variant(profile, result)
        assertEquals(profile.sni, changed.sni)
        assertEquals(profile.host, changed.host)
        assertEquals(profile.uuid, changed.uuid)
        assertEquals(profile.path, changed.path)
        assertNotEquals(profile.id, changed.id)
        assertEquals("104.16.1.1", changed.address)
        assertEquals("example.workers.dev", profile.address)
    }
    @Test fun rankingRejectsFailuresPenalizesInstabilityAndCapsAt15() {
        val candidates = (1..30).map { CleanIpOptimizer.Result("104.16.1.$it", it * 10L, 0, 3) }
        val ranked = CleanIpOptimizer.ranked(candidates + CleanIpOptimizer.Result("104.16.2.1", 1, 0, 1))
        assertEquals(15, ranked.size)
        assertEquals("104.16.1.1", ranked.first().ip)
        assertTrue(CleanIpOptimizer.Result("a", 10, 500, 2).score > ranked.last().score)
    }
    @Test fun cidrMembershipIsExact() {
        assertTrue(CleanIpOptimizer.inCidr("104.16.0.1", "104.16.0.0/13"))
        assertFalse(CleanIpOptimizer.inCidr("104.24.0.1", "104.16.0.0/13"))
        assertFalse(CleanIpOptimizer.inCidr("127.0.0.1", "104.16.0.0/13"))
    }

    @Test fun customSubnetParsingSupportsCidrAndIps() {
        val parsed = CleanIpOptimizer.parseCustomSubnet("104.16.12.0/24, 162.159.130.5", 20)
        assertTrue(parsed.isNotEmpty())
        assertTrue(parsed.any { it.first.startsWith("104.16.12.") })
        assertTrue(parsed.any { it.first == "162.159.130.5" })
    }

    @Test fun compatibilityChecksRejectRealityAndDirectEndpoints() {
        assertTrue(CleanIpOptimizer.isCloudflareCompatible(profile))
        assertFalse(CleanIpOptimizer.isCloudflareCompatible(profile.copy(security = "reality")))
        assertFalse(CleanIpOptimizer.isCloudflareCompatible(profile.copy(transport = "tcp")))
        assertFalse(CleanIpOptimizer.isCloudflareCompatible(profile.copy(address = "1.2.3.4", sni = "", host = "")))
    }

    @Test fun downloadSpeedImprovesScoreRank() {
        val fast = CleanIpOptimizer.Result("104.16.1.1", 100, 5, 3, downloadSpeedKbps = 5000)
        val slow = CleanIpOptimizer.Result("104.16.1.2", 100, 5, 3, downloadSpeedKbps = 100)
        assertTrue(fast.score < slow.score)
    }
}
