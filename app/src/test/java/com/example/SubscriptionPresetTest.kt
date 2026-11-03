package com.example

import com.example.data.model.*
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.subscription.SubscriptionPreset
import org.junit.Assert.*
import org.junit.Test

class SubscriptionPresetTest {
    private fun node() = VlessProfile(name = "test", address = "192.0.2.1", port = 443,
        uuid = "00000000-0000-0000-0000-000000000001", security = "tls", sni = "example.com", countryCode = "DE")

    @Test fun presetIsReversibleAndKeepsTlsAndManualCoreChoices() {
        val p = node().copy(cipherSuites = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")
        val preset = SubscriptionPreset(EngineType.SING_BOX)
        val changed = preset.apply(p)
        assertEquals(EngineType.SING_BOX, changed.engineType)
        assertEquals(p.cipherSuites, changed.cipherSuites)
        assertEquals(p.sni, changed.sni)
        assertEquals(EngineType.XRAY, SubscriptionPreset().apply(changed).engineType)
        val manual = ProfileExtras.with(p, "coreLocked", true)
        assertEquals(EngineType.XRAY, preset.apply(manual).engineType)
        assertEquals(EngineType.XRAY, preset.apply(p.copy(finalMask = "{}" )).engineType)
    }

    @Test fun countryExclusionDoesNotDeleteTheNodeAndCanBeRemoved() {
        val p = node()
        val excluded = SubscriptionPreset(excludedCountries = setOf("DE")).apply(p)
        assertFalse(SubscriptionPreset.autoSelectable(excluded))
        assertEquals(p.id, excluded.id)
        assertEquals(p.uuid, excluded.uuid)
        assertTrue(SubscriptionPreset.autoSelectable(SubscriptionPreset().apply(excluded)))
        assertTrue(SubscriptionPreset.autoSelectable(SubscriptionPreset(excludedCountries = setOf("DE")).apply(p.copy(countryCode = null))))
    }
}
