package com.example.vpn

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.diagnostics.EngineStages
import com.example.vpn.engine.registry.NetworkResults
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.ShirokhorshidFrontedRetry
import com.example.vpn.sidecar.SidecarProcess
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 181: the fixes for the Build 180 device test on Irancell (A2–A7; A1 is in LanternRelayFallbackTest). */
class Build180DeviceTestFixesTest {
    @After fun tearDown() = EngineStages.resetForTest()

    // ---- A2: no IPv6 lane without IPv6, and the pick keeps its 60 s ------------------------------------------------

    @Test fun a2_aPhoneWithoutGlobalIpv6IsIpv4OnlyForAether() {
        assertTrue(ConnectionEngineRunner.noIpv6(measuredIpv4Only = false, linkGlobalIpv6 = false))
        assertTrue(ConnectionEngineRunner.noIpv6(measuredIpv4Only = true, linkGlobalIpv6 = null))
        assertFalse(ConnectionEngineRunner.noIpv6(measuredIpv4Only = false, linkGlobalIpv6 = null))
        assertFalse(ConnectionEngineRunner.noIpv6(measuredIpv4Only = false, linkGlobalIpv6 = true))
        // With no IPv6 the IPv6-first lane is never planned (cfV6First reads ipv4Only).
        assertFalse(ConnectionEngineRunner.cfV6First(com.example.vpn.sidecar.AetherSidecar.ID, automatic = true,
            cloudflareBlocked = true, ipv4Only = ConnectionEngineRunner.noIpv6(false, false), irancell = true, mci = false,
            relayOnly = false, manual = true))
    }

    @Test fun a2_theIpv6LaneOfTheUsersPickSharesItsSixtySeconds() {
        assertEquals(ConnectionEngineRunner.MANUAL_V6_SHARE_MS, ConnectionEngineRunner.v6AttemptMs(manual = true))
        assertEquals(ConnectionEngineRunner.IRANCELL_V6_MS, ConnectionEngineRunner.v6AttemptMs(manual = false))
        assertTrue(ConnectionEngineRunner.MANUAL_V6_SHARE_MS < ConnectionEngineRunner.CF_FILTERED_MANUAL_BUDGET_MS)
    }

    // ---- A3: Shir o Khorshid switches sooner and keeps the first phase's dials -------------------------------------

    @Test fun a3_theSwitchNoLongerWaitsTwentyFiveSecondsAfterTheConnect() {
        val window = 120_000L
        // Build 180: server connected at ~28 s; the switch fired at 53 s. Now at 36 s (8 s after the connect).
        assertFalse(ShirokhorshidFrontedRetry.giveUp(3, 28_000L, 0L, 35_000L, window))
        assertTrue(ShirokhorshidFrontedRetry.giveUp(3, 28_000L, 0L, 36_000L, window))
        val text = ShirokhorshidFrontedRetry.firstPhaseText(36_000L, 28_000L,
            PsiphonSidecar.StartupDials(tcp = 5, connected = 1, byProtocol = sortedMapOf("OSSH" to 5)))
        assertTrue(text, text.startsWith("first phase (all protocols, 36s; server connected at 28s)"))
        assertTrue(text, text.contains("OSSH×5"))
        assertTrue(ShirokhorshidFrontedRetry.firstPhaseText(30_000L, null, null).endsWith("no dial counts"))
    }

    // ---- A5: no stale engine on the Connect tap; Smart Connect is not "aether/auto" --------------------------------

    @Test fun a5_theConnectTapCopyOfTheLastStateIsNotLogged() {
        EngineStages.resetForTest()
        val tor = ConfigFreeMethod.AETHER_WARP.profile()
        val failed = ConnectionState(status = ConnectionStatus.FAILED, activeProfile = tor)
        val tap = failed.copy(status = ConnectionStatus.PREPARING)
        assertTrue(EngineStages.staleConnectTap(failed, tap))
        EngineStages.onStateChange(failed, tap, now = 1)
        assertTrue(EngineStages.entries.value.isEmpty())
        // A new profile on "preparing" is still named.
        val other = ConfigFreeMethod.SHIROKHORSHID.profile()
        assertFalse(EngineStages.staleConnectTap(failed, ConnectionState(status = ConnectionStatus.PREPARING, activeProfile = other)))
        assertEquals(EngineStages.SMART, EngineStages.connectLabel(smart = true, profile = tor))
        assertTrue(EngineStages.connectLabel(smart = false, profile = tor).startsWith("aether"))
    }

    // ---- A6: the LAB tile counts the connections verified here ------------------------------------------------------

    @Test fun a6_theLabTileCountsConnectionsVerifiedHereWhenNoProfileIs() {
        assertEquals("5 connections", NetworkResults.verifiedMetric(profiles = 0, connectionsHere = 5))
        assertEquals("1 connection", NetworkResults.verifiedMetric(0, 1))
        assertEquals("2 profiles", NetworkResults.verifiedMetric(2, 5))
        assertEquals("1 profile", NetworkResults.verifiedMetric(1, 0))
        assertEquals("0 profiles", NetworkResults.verifiedMetric(0, 0))
    }

    // ---- A7: Aether's gateway scan says how many it tried and where they failed -----------------------------------

    @Test fun a7_aetherScanLinesBecomeCountsWithoutAddresses() {
        assertEquals("trying 42 gateway(s) over v4, SNI www.cloudflare.com",
            SidecarProcess.aetherScanLine("MAXIMUS_AETHER_SCAN:start candidates=42 family=v4 sni=www.cloudflare.com"))
        assertEquals("result none; failed probes: TCP 0, TLS/pin 40, HTTP/2 0, timeout 2, other 0",
            SidecarProcess.aetherScanLine("MAXIMUS_AETHER_SCAN:result=none failed_tcp=0 failed_tls=40 failed_h2=0 failed_timeout=2 failed_other=0"))
        // Anything outside the fixed fields is dropped; an unknown line is not a scan line.
        assertEquals("trying ? gateway(s) over v4, SNI ?",
            SidecarProcess.aetherScanLine("MAXIMUS_AETHER_SCAN:start family=v4 peer=162.159.198.1:443"))
        assertNull(SidecarProcess.aetherScanLine("MAXIMUS_AETHER_STAGE:scan"))
        assertNull(SidecarProcess.aetherScanLine("MAXIMUS_AETHER_SCAN:start peer=1.2.3.4"))
    }
}
