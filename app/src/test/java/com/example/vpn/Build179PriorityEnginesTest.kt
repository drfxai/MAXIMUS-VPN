package com.example.vpn

import com.example.vpn.connections.AetherCloudflareNotice
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.ChainRunner
import com.example.vpn.sidecar.EngineChain
import com.example.vpn.sidecar.LanternRelayHop
import com.example.vpn.sidecar.PsiphonRegionPlan
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.RadianceSidecar
import com.example.vpn.sidecar.ShirokhorshidSidecar
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Build 179: priority engines after the build 178 field report (Irancell). Psiphon's CDN-fronted meek step, Shir o
 * Khorshid CDN-fronted after a failed start, Aether IPv6-first with cf=0, and the two explicit chains over Lantern.
 */
class Build179PriorityEnginesTest {
    private val settings = JSONObject()
        .put("PropagationChannelId", "TEST-CHANNEL")
        .put("SponsorId", "TEST-SPONSOR")
        .put("RemoteServerListURLs", JSONArray().put(JSONObject().put("URL", "aHR0cHM6Ly9leGFtcGxlLmludmFsaWQvbGlzdA==")))
        .put("RemoteServerListSignaturePublicKey", "TEST-KEY")

    private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()

    // ---- Psiphon: CDN-fronted meek step ------------------------------------------------------------------------------

    @Test fun psiphonLeadsWithTheFrontedStepWhereDirectFailedRecently() {
        val steps = PsiphonRegionPlan.initial("", emptyList(), PsiphonRegionPlan.TOTAL_AUTO_MS, frontedFirst = true)
        assertTrue(steps.first().fronted)
        assertEquals("", steps.first().region)
        assertEquals(PsiphonRegionPlan.FRONTED_FIRST_MS, steps.first().budgetMs)
        assertTrue(steps.drop(1).none { it.fronted })
        assertEquals(PsiphonRegionPlan.TOTAL_AUTO_MS, steps.sumOf { it.budgetMs })
        // Without a recent failure nothing changes; a region the user fixed is never replaced.
        assertTrue(PsiphonRegionPlan.initial("", emptyList()).none { it.fronted })
        assertEquals(listOf("DE"), PsiphonRegionPlan.initial("DE", emptyList(), frontedFirst = true).map { it.region })
        // Too short a budget for both: the plain plan.
        assertTrue(PsiphonRegionPlan.initial("", emptyList(), PsiphonRegionPlan.KNOWN_BLOCKED_MS, frontedFirst = true).none { it.fronted })
    }

    @Test fun afterASilentAnyRegionTheFrontedStepComesFirstThenARegionOverTcp() {
        val steps = PsiphonRegionPlan.rotation(listOf("DE", "NL", "US"), emptySet(), emptyList(), 55_000)
        assertEquals(listOf(true, false), steps.map { it.fronted })
        assertEquals(listOf("", "DE"), steps.map { it.region })
        assertEquals(listOf(false, true), steps.map { it.tcpOnly })
        // A run that already led with the fronted step goes straight to the regions.
        val after = PsiphonRegionPlan.rotation(listOf("DE", "NL", "US"), emptySet(), emptyList(), 55_000, frontedTried = true)
        assertTrue(after.none { it.fronted })
        assertEquals(listOf("DE", "NL"), after.map { it.region })
    }

    @Test fun theFrontedStepLimitsPsiphonToItsFrontedProtocolsUnpinnedAndWithoutTactics() {
        val config = PsiphonSidecar.buildConfig(JSONObject(settings.toString()), "DE", 40123, File("/tmp/b179-psiphon"), fronted = true)
        assertEquals(PsiphonSidecar.FRONTED_PROTOCOLS, strings(config.optJSONArray("LimitTunnelProtocols")))
        assertFalse(config.has("EgressRegion"))
        assertTrue(config.optBoolean("DisableTactics"))
        val tcp = PsiphonSidecar.buildConfig(JSONObject(settings.toString()), "", 40123, File("/tmp/b179-psiphon"),
            tcpOnly = true, fronted = true)
        assertEquals(listOf("FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH"), strings(tcp.optJSONArray("LimitTunnelProtocols")))
        // The ordinary start keeps its region.
        assertEquals("DE", PsiphonSidecar.buildConfig(JSONObject(settings.toString()), "DE", 40123, File("/tmp/b179-psiphon"))
            .optString("EgressRegion"))
        val profile = PsiphonSidecar.frontedOnly(PsiphonSidecar.profile(""))
        assertTrue(PsiphonSidecar.isFrontedOnly(profile))
        assertFalse(PsiphonSidecar.isFrontedOnly(PsiphonSidecar.profile("")))
    }

    // ---- Shir o Khorshid: CDN-fronted only after a failed start -------------------------------------------------------

    @Test fun shirOKhorshidSwitchesToCdnFrontedOnlyAfterAFailedStart() {
        assertFalse(ShirokhorshidSidecar.nextFronted(lastFailed = null, lastFronted = false))
        assertFalse(ShirokhorshidSidecar.nextFronted(lastFailed = null, lastFronted = true))
        assertTrue(ShirokhorshidSidecar.nextFronted(lastFailed = true, lastFronted = false))
        assertFalse(ShirokhorshidSidecar.nextFronted(lastFailed = true, lastFronted = true))
        assertTrue(ShirokhorshidSidecar.nextFronted(lastFailed = false, lastFronted = true))
        assertFalse(ShirokhorshidSidecar.nextFronted(lastFailed = false, lastFronted = false))
        val base = PsiphonSidecar.buildConfig(JSONObject(settings.toString()), "NL", 40123, File("/tmp/b179-sok"))
        val out = ShirokhorshidSidecar.applyOptions(base, ShirokhorshidSidecar.Options(), fronted = true)
        assertEquals(ShirokhorshidSidecar.CDN_PROTOCOLS + PsiphonSidecar.FRONTED_PROTOCOLS,
            strings(out.optJSONArray("LimitTunnelProtocols")))
        assertTrue(out.optBoolean("DisableTactics"))
        assertFalse(out.has("EgressRegion"))
    }

    // ---- Aether: IPv6 first with cf=0 ---------------------------------------------------------------------------------

    @Test fun aetherTriesWarpOverIpv6FirstWhenCloudflareIsFiltered() {
        assertTrue(ConnectionEngineRunner.cfV6First(AetherSidecar.ID, automatic = true, cloudflareBlocked = true,
            ipv4Only = false, irancell = false, mci = false, relayOnly = false))
        assertFalse(ConnectionEngineRunner.cfV6First(AetherSidecar.ID, true, true, ipv4Only = true, irancell = false, mci = false, relayOnly = false))
        assertFalse(ConnectionEngineRunner.cfV6First(AetherSidecar.ID, true, cloudflareBlocked = false, ipv4Only = false, irancell = false, mci = false, relayOnly = false))
        assertFalse(ConnectionEngineRunner.cfV6First(AetherSidecar.ID, true, true, false, irancell = true, mci = false, relayOnly = false))
        assertFalse(ConnectionEngineRunner.cfV6First(AetherSidecar.ID, true, true, false, irancell = false, mci = true, relayOnly = false))
        assertFalse(ConnectionEngineRunner.cfV6First("psiphon", true, true, false, irancell = false, mci = false, relayOnly = false))
        val methods = listOf("h2", "h3", "wireguard", ConnectionEngineRunner.H2_V6, ConnectionEngineRunner.WIREGUARD_V6)
        val ordered = ConnectionEngineRunner.order(methods, null, true, emptySet(), cfV6 = true)
        assertEquals(setOf(ConnectionEngineRunner.WIREGUARD_V6, ConnectionEngineRunner.H2_V6), ordered.take(2).toSet())
        // Without cf=0 the IPv6 variants keep no special place.
        val plain = ConnectionEngineRunner.order(methods, null, true, emptySet())
        assertFalse(plain.take(2).toSet() == setOf(ConnectionEngineRunner.WIREGUARD_V6, ConnectionEngineRunner.H2_V6))
    }

    // ---- The explicit picks -------------------------------------------------------------------------------------------

    @Test fun shirOKhorshidOverLanternIsAnExplicitRunnableChain() {
        val profile = ConfigFreeMethod.SHIROKHORSHID_RADIANCE.profile()
        val chain = ChainRunner.readChain(profile)
        assertNotNull(chain)
        assertEquals(listOf(ShirokhorshidSidecar.ID, RadianceSidecar.id), chain!!.hops.map { it.engineId })
        assertNull(EngineChain.problem(chain))
        assertEquals(ShirokhorshidSidecar.ID,
            ProfileExtras.read(EngineChain.hopProfile(chain.hops.first())).optString(PsiphonSidecar.KEY_ENGINE))
        assertEquals(ConfigFreeMethod.SHIROKHORSHID_RADIANCE.id, profile.id)
        // A chain hop dials through Lantern's SOCKS port: TCP protocols only, the CDN ones kept.
        val hop = ShirokhorshidSidecar.applyOptions(PsiphonSidecar.buildConfig(JSONObject(settings.toString()), "", 40123,
            File("/tmp/b179-sok-chain"), upstreamSocks = 40124), ShirokhorshidSidecar.Options(), chained = true)
        val protocols = strings(hop.optJSONArray("LimitTunnelProtocols"))
        assertTrue(protocols.isNotEmpty())
        assertTrue("FRONTED-MEEK-CDN-OSSH" in protocols)
        assertTrue(protocols.none { it.contains("QUIC") })
        // Its deadline waits out the hop's CDN scan.
        assertEquals(ConnectDeadline.SHIROKHORSHID_CHAIN_MS,
            ConnectDeadline.budgetFor(listOf(ShirokhorshidSidecar.ID, RadianceSidecar.id), manual = true, directPsiphon = false))
    }

    @Test fun aetherOverLanternIsAnExplicitPickAndThePlainPicksStayAlone() {
        val e = ProfileExtras.read(ConfigFreeMethod.AETHER_LANTERN.profile())
        assertTrue(LanternRelayHop.requested(e))
        assertEquals("h2", e.optString("carrier"))
        assertEquals("off", AetherSettings.read(e).psiphon)
        assertEquals("off", AetherSettings.read(e).tor)
        assertEquals(ConfigFreeMethod.AETHER_LANTERN.id, ConfigFreeMethod.AETHER_LANTERN.profile().id)
        assertFalse(AetherCloudflareNotice.appliesTo(ConfigFreeMethod.AETHER_LANTERN))
        // Rule: a plain manual engine pick never goes through another engine.
        assertFalse(LanternRelayHop.requested(ProfileExtras.read(ConfigFreeMethod.AETHER_WARP.profile())))
        assertFalse(LanternRelayHop.requested(ProfileExtras.read(ConfigFreeMethod.AETHER_H2.profile())))
        assertFalse(ChainRunner.isChain(ConfigFreeMethod.SHIROKHORSHID.profile()))
        assertFalse(ChainRunner.isChain(ConfigFreeMethod.PSIPHON.profile()))
        assertFalse(ManualEnginePick.lanternHop(manual = true, lanternAvailable = true))
        // Both are listed, each with its own description.
        assertTrue(ConfigFreeMethod.SHIROKHORSHID_RADIANCE in ConfigFreeMethod.entries)
        assertTrue(ConfigFreeMethod.AETHER_LANTERN in ConfigFreeMethod.entries)
        assertNull(ConfigFreeMethod.SHIROKHORSHID_RADIANCE.aetherTransport)
        assertNotNull(ConfigFreeMethod.AETHER_LANTERN.aetherTransport)
    }
}
