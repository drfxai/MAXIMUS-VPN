package com.example

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
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
import org.json.JSONObject

/** V1.0.1 step 5: the engine registry, the capability matrix and the per-engine circuit breaker. */
class EngineRegistryTest {
    @Test fun directPsiphonLetsTheCoreChooseTransport() {
        assertFalse(JSONObject(ConfigFreeMethod.PSIPHON.profile().extraSettings).optBoolean("tcpOnly"))
    }

    @Test fun aetherPresetsKeepTheirTransportAndRelayDirection() {
        val presets = mapOf(
            ConfigFreeMethod.AETHER_H2 to "h2",
            ConfigFreeMethod.AETHER_H3 to "h3",
            ConfigFreeMethod.AETHER_WG to "wireguard",
            ConfigFreeMethod.AETHER_GOOL to "gool",
            ConfigFreeMethod.AETHER_GOOL_CLASSIC to "wiw",
            ConfigFreeMethod.AETHER_MIM to "mim",
            ConfigFreeMethod.AETHER_PSIPHON to "h2",
            ConfigFreeMethod.AETHER_TOR to "h2"
        )
        for ((preset, carrier) in presets) {
            val profile = preset.profile()
            val extras = JSONObject(profile.extraSettings)
            assertEquals(preset.id, profile.id)
            assertEquals(carrier, extras.getString("carrier"))
            assertEquals(if (preset == ConfigFreeMethod.AETHER_PSIPHON) "chain" else "off", extras.getString("psiphon"))
            assertEquals(if (preset == ConfigFreeMethod.AETHER_TOR) "chain" else "off", extras.getString("tor"))
        }
    }

    @Test fun everyRuntimeHasABundledPinnedDescriptor() {
        for (runtime in EngineSelectionPolicy.Runtime.values()) {
            val d = EngineRegistry.descriptorFor(runtime)
            assertTrue(d.bundled)
            assertNotNull(d.version)
        }
        // Engines the plan still calls for are listed, with a license, and map to no runtime.
        val planned = EngineRegistry.ENGINES.filterNot { it.bundled }
        assertTrue(planned.all { it.runtime == null && it.version == null && it.license.isNotBlank() })
        // Mihomo is a separate program carried behind Xray, so it is bundled without being a runtime.
        assertTrue(EngineRegistry.MIHOMO.bundled)
        assertNull(EngineRegistry.MIHOMO.runtime)
        assertEquals("GPL-3.0", EngineRegistry.MIHOMO.license)
    }

    @Test fun radianceIsPinnedAndRequiredInTheReleaseApk() {
        val descriptor = EngineRegistry.RADIANCE
        val sidecar = com.example.vpn.sidecar.RadianceSidecar
        assertTrue(descriptor.bundled)
        assertNull(descriptor.runtime)
        assertEquals(sidecar.REVISION, descriptor.version)
        assertTrue(com.example.vpn.sidecar.Sidecars.ENGINES.contains(sidecar))
        assertTrue(EngineRegistry.requiredNativePrograms().contains(sidecar.binary))
    }

    @Test fun theMatrixMatchesWhatTheEnginesAccept() {
        val reality = VlessProfile(id = "r", name = "r", address = "203.0.113.1", port = 443,
            uuid = "11111111-1111-1111-1111-111111111111", security = "reality", sni = "a.example",
            publicKey = "pbk", shortId = "ab", flow = "xtls-rprx-vision", fingerprint = "chrome")
        assertNull(RuntimeCapabilities.unsupportedReason(reality))
        assertNull(RuntimeCapabilities.unsupportedReason(ProtocolLinks.parseHysteria2("hysteria2://pw@198.51.100.1:443?sni=h.example#h")))
        assertNotNull(RuntimeCapabilities.unsupportedReason(reality.copy(protocolType = ProtocolType.TUIC)))
        assertEquals(CapabilityState.NONE, EngineRegistry.CAPABILITIES["NaiveProxy"])
        // Only what passed real traffic in the simulator or the engine lab counts as working.
        assertEquals(listOf("VLESS REALITY Vision", "VLESS WebSocket TLS (CDN)", "VLESS Encryption", "Hysteria2", "WireGuard",
            "TUIC (Mihomo)"), EngineRegistry.verified())
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
