package com.example

import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AetherWireGuardMtuTest {
    @Test fun mtuIsValidatedAndPersisted() {
        assertNull(AetherSettings(mtu = "").problem())
        for (ok in listOf("1180", "1200", "1220", "1280", "1420")) assertNull(ok, AetherSettings(mtu = ok).problem())
        for (bad in listOf("1179", "1421", "abc", "12 00", "-1200", "99999", "1200;")) assertNotNull(bad, AetherSettings(mtu = bad).problem())
        val settings = AetherSettings(mtu = "1220")
        assertEquals(settings, AetherSettings.read(settings.write(JSONObject())))
        assertEquals("", AetherSettings.read(JSONObject()).mtu)
    }

    @Test fun mtuReachesOnlyWireGuardCarriersThroughTheEnvironment() {
        val settings = AetherSettings(mtu = "1200")
        assertEquals(mapOf(AetherSettings.MTU_ENV to "1200"), settings.environment("wireguard"))
        assertEquals(mapOf(AetherSettings.MTU_ENV to "1200"), settings.environment("wiw"))
        for (carrier in listOf("h2", "h3", "gool", "mim")) assertTrue(settings.environment(carrier).isEmpty())
        assertTrue(AetherSettings().environment("wireguard").isEmpty())
        // Never a command-line flag the pinned engine does not know.
        assertFalse(settings.arguments("wireguard", true).any { it.contains("mtu", ignoreCase = true) })
    }

    @Test fun reducedMtuRetryOnlyFollowsAutomaticWireGuardWithAutomaticMtu() {
        fun follow(engine: String = AetherSidecar.ID, automatic: Boolean = true, carrier: String = "wireguard",
                   mtu: String = "", queued: Boolean = false, reverse: Boolean = false) =
            ConnectionEngineRunner.reducedMtuWireGuardFollowUp(engine, automatic, carrier, mtu, queued, reverse)
        assertTrue(follow())
        assertFalse(follow(engine = "serverless"))
        assertFalse(follow(automatic = false))
        assertFalse(follow(carrier = "h3"))
        assertFalse(follow(carrier = "wiw"))
        assertFalse(follow(mtu = "1280"))
        assertFalse(follow(queued = true))
        assertFalse(follow(reverse = true))
    }

    @Test fun reducedMtuRetryKeepsTheEndpointAndUsesTheRetryValue() {
        val base = AetherSidecar.profile("auto", "ipv4")
        val noize = ConnectionEngineRunner.obfuscatedWireGuard(base)
        val retry = ConnectionEngineRunner.reducedMtuWireGuard(noize)
        val extras = ProfileExtras.read(retry)
        assertEquals("wireguard", extras.optString("carrier"))
        assertEquals(AetherSettings.MTU_RETRY.toString(), extras.optString("mtu"))
        assertTrue(AetherSettings.MTU_RETRY in 1180..1220)
        assertTrue(extras.optBoolean("mtuFollowUp"))
        assertFalse(extras.has("noizeFollowUp"))
        assertFalse("quick reconnect to the endpoint that handshook", extras.optBoolean("rescan"))
        assertNull(AetherSidecar.problem(retry))
        assertEquals(mapOf(AetherSettings.MTU_ENV to "1200"), AetherSettings.read(extras).environment("wireguard"))
    }
}
