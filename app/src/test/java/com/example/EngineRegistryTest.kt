package com.example

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.EngineSelectionPolicy
import com.example.vpn.engine.ProtocolLinks
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.engine.registry.CapabilityState
import com.example.vpn.engine.registry.EngineCircuitBreaker
import com.example.vpn.engine.registry.EngineRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1.0.1 step 5: the engine registry, the capability matrix and the per-engine circuit breaker. */
class EngineRegistryTest {
    @Test fun everyRuntimeHasABundledPinnedDescriptor() {
        for (runtime in EngineSelectionPolicy.Runtime.values()) {
            val d = EngineRegistry.descriptorFor(runtime)
            assertTrue(d.bundled)
            assertNotNull(d.version)
        }
        assertFalse(EngineRegistry.MIHOMO.bundled)
    }

    @Test fun theMatrixMatchesWhatTheEnginesAccept() {
        val reality = VlessProfile(id = "r", name = "r", address = "203.0.113.1", port = 443,
            uuid = "11111111-1111-1111-1111-111111111111", security = "reality", sni = "a.example",
            publicKey = "pbk", shortId = "ab", flow = "xtls-rprx-vision", fingerprint = "chrome")
        assertNull(RuntimeCapabilities.unsupportedReason(reality))
        assertNull(RuntimeCapabilities.unsupportedReason(ProtocolLinks.parseHysteria2("hysteria2://pw@198.51.100.1:443?sni=h.example#h")))
        assertNotNull(RuntimeCapabilities.unsupportedReason(reality.copy(protocolType = ProtocolType.TUIC)))
        assertEquals(CapabilityState.NONE, EngineRegistry.CAPABILITIES["TUIC"])
        assertEquals(CapabilityState.NONE, EngineRegistry.CAPABILITIES["Psiphon"])
        // Only what passed real traffic in the simulator counts as working.
        assertEquals(listOf("VLESS REALITY Vision", "VLESS WebSocket TLS (CDN)", "VLESS Encryption", "Hysteria2", "WireGuard"),
            EngineRegistry.verified())
    }

    @Test fun anEngineThatKeepsFailingIsSkippedForAWhile() {
        val breaker = EngineCircuitBreaker(threshold = 3, cooldownMs = 1_000)
        repeat(2) { breaker.recordFailure("xray", now = 0) }
        assertTrue(breaker.allows("xray", now = 0))
        breaker.recordFailure("xray", now = 0)
        assertFalse(breaker.allows("xray", now = 999))
        assertTrue(breaker.allows("xray", now = 1_000))
        breaker.recordSuccess("xray")
        assertTrue(breaker.allows("xray", now = 1_001))
        assertTrue(breaker.allows("kotlin-tunnel", now = 0))
    }
}
