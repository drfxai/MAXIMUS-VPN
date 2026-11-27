package com.example.vpn.smart

import com.example.vpn.ConnectDeadline
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 181 3.6: Smart Connect tiers and demotions. */
class SmartTiersTest {
    private val normal = ConnectDeadline.CANDIDATES

    @Test fun unfilteredNetworkKeepsTheOldOrder() {
        assertEquals(SmartTiers.Tier.NORMAL, SmartTiers.tier(SmartTiers.Signals()))
        assertEquals(normal, SmartTiers.candidates(SmartTiers.Signals(), normal))
    }

    @Test fun heavyLeadsWithLanternThenLanternChainsThenShortWarpThenSnowflake() {
        val s = SmartTiers.Signals(cloudflareFiltered = true, udpFiltered = true, irancell = true)
        assertEquals(SmartTiers.Tier.HEAVY, SmartTiers.tier(s))
        val c = SmartTiers.candidates(s, normal)
        // Build 181 Phase 6: the user's own ECH server (only runnable when one has ECH) leads, then Lantern.
        assertEquals(listOf(ConfigFreeMethod.PERSONAL_ECH.id, ConfigFreeMethod.RADIANCE.id), c.take(2))
        assertTrue(c.indexOf(ConfigFreeMethod.PSIPHON_RADIANCE.id) < c.indexOf(ConfigFreeMethod.AETHER_H2.id))
        assertTrue(c.indexOf(ConfigFreeMethod.AETHER_LANTERN.id) < c.indexOf(ConfigFreeMethod.AETHER_H2.id))
        assertTrue(c.indexOf(ConfigFreeMethod.AETHER_H2.id) < c.indexOf(ConfigFreeMethod.TOR_SNOWFLAKE.id))
        // direct Psiphon is demoted on Irancell; Serverless (ISP exit) is never a Smart fallback
        assertEquals(ConfigFreeMethod.PSIPHON.id, c.last())
        assertFalse(ConfigFreeMethod.SERVERLESS.id in c)
    }

    @Test fun whitelistLeadsWithSnowflakeThenConduits() {
        val s = SmartTiers.Signals(internationalReachable = false, domesticReachable = true)
        assertEquals(SmartTiers.Tier.WHITELIST, SmartTiers.tier(s))
        val c = SmartTiers.candidates(s, normal)
        assertEquals(listOf(ConfigFreeMethod.TOR_SNOWFLAKE.id, ConfigFreeMethod.SHIROKHORSHID.id, ConfigFreeMethod.RADIANCE.id,
            ConfigFreeMethod.PERSONAL_ECH.id), c.take(4))
        assertFalse(ConfigFreeMethod.PERSONAL_ECH.id in SmartTiers.candidates(SmartTiers.Signals(), normal))
        assertTrue(SmartTiers.allowIranianConduits(SmartTiers.Tier.WHITELIST))
        assertFalse(SmartTiers.allowIranianConduits(SmartTiers.Tier.HEAVY))
    }

    @Test fun nearShutdownHasAClearSlowStatus() {
        val s = SmartTiers.Signals(internationalReachable = false, domesticReachable = false)
        assertEquals(SmartTiers.Tier.NEAR_SHUTDOWN, SmartTiers.tier(s))
        assertTrue(SmartTiers.nearShutdownStatus().contains("5 min"))
        assertTrue(SmartTiers.allowIranianConduits(SmartTiers.Tier.NEAR_SHUTDOWN))
    }

    @Test fun demotionsMoveToTheEndAndKeepEverything() {
        val ids = listOf(ConfigFreeMethod.AETHER_WG.id, ConfigFreeMethod.AETHER_H3.id, ConfigFreeMethod.RADIANCE.id,
            ConfigFreeMethod.AETHER_GOOL.id, ConfigFreeMethod.AETHER_MIM.id, ConfigFreeMethod.AETHER_TOR.id)
        val cf = SmartTiers.demote(ids, SmartTiers.Signals(cloudflareFiltered = true))
        assertEquals(listOf(ConfigFreeMethod.AETHER_H3.id, ConfigFreeMethod.RADIANCE.id), cf.take(2))
        assertEquals(ids.toSet(), cf.toSet())
        val udp = SmartTiers.demote(ids, SmartTiers.Signals(udpFiltered = true))
        assertEquals(ConfigFreeMethod.AETHER_H3.id, udp.last())
    }

    @Test fun signalsComeFromTheMeasurement() {
        val m = NetworkCapabilityProfile(udpAvailable = false, internationalOk = 0, internationalTried = 3, domesticReachable = true)
        val s = SmartTiers.signals(m, null, cloudflareFiltered = false)
        assertTrue(s.udpFiltered)
        assertEquals(SmartTiers.Tier.WHITELIST, SmartTiers.tier(s))
    }
}
