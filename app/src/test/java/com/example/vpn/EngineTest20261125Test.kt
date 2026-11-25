package com.example.vpn

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.diagnostics.EngineStages
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.RadianceSidecar
import com.example.vpn.sidecar.ServerlessSidecar
import com.example.vpn.sidecar.TorSidecar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-11-25 engine test (V1.0.4 build 175, Z Flip3, Irancell LTE): failing engines held the tunnel with zero internet
 * for minutes with no timeout, no error and no fallback, and the analytics screen showed no stage. These pin the
 * connect deadline ([ConnectDeadline]) and the stage timeline ([EngineStages]).
 */
class EngineTest20261125Test {
    @Before fun setUp() = EngineStages.resetForTest()
    @After fun tearDown() = EngineStages.resetForTest()

    // ---- connect deadline: budgets ---------------------------------------------------------------------------------

    @Test fun torGetsThreeMinutesChainsNinetySecondsServerlessFortyFiveOthersSixty() {
        assertEquals(ConnectDeadline.TOR_MS, ConnectDeadline.budgetMs(listOf(TorSidecar.ID)))
        assertEquals(ConnectDeadline.TOR_MS, ConnectDeadline.budgetMs(listOf("aether", TorSidecar.ID)))
        assertEquals(ConnectDeadline.CHAIN_MS, ConnectDeadline.budgetMs(listOf(PsiphonSidecar.ID, RadianceSidecar.id)))
        assertEquals(ConnectDeadline.SERVERLESS_MS, ConnectDeadline.budgetMs(listOf(ServerlessSidecar.ID)))
        assertEquals(ConnectDeadline.ENGINE_MS, ConnectDeadline.budgetMs(listOf(PsiphonSidecar.ID)))
        assertEquals(ConnectDeadline.ENGINE_MS, ConnectDeadline.budgetMs(emptyList()))
        assertTrue(ConnectDeadline.ENGINE_MS in 45_000L..60_000L)
    }

    @Test fun builtInProfilesMapToTheirEngines() {
        assertEquals(listOf(TorSidecar.ID), ConnectDeadline.engines(ConfigFreeMethod.TOR_SNOWFLAKE.profile()))
        assertEquals(listOf(PsiphonSidecar.ID, RadianceSidecar.id), ConnectDeadline.engines(ConfigFreeMethod.PSIPHON_RADIANCE.profile()))
        assertEquals(listOf(RadianceSidecar.id), ConnectDeadline.engines(ConfigFreeMethod.RADIANCE.profile()))
        assertEquals(ConnectDeadline.TOR_MS, ConnectDeadline.budgetMs(ConfigFreeMethod.AETHER_TOR.profile()))
        assertEquals(ConnectDeadline.CHAIN_MS, ConnectDeadline.budgetMs(ConfigFreeMethod.AETHER_PSIPHON.profile()))
        assertEquals(ConnectDeadline.ENGINE_MS, ConnectDeadline.budgetMs(ConfigFreeMethod.AETHER_H2.profile()))
    }

    @Test fun anEngineAttemptExpiresInAnyWaitingStatusASavedConfigOnlyWithoutTraffic() {
        val t0 = 1_000_000L
        assertFalse(ConnectDeadline.expired(ConnectionStatus.VERIFYING, true, t0, t0 + 59_999, 60_000))
        assertTrue(ConnectDeadline.expired(ConnectionStatus.VERIFYING, true, t0, t0 + 60_000, 60_000))
        assertTrue(ConnectDeadline.expired(ConnectionStatus.PREPARING, true, t0, t0 + 61_000, 60_000))
        assertTrue(ConnectDeadline.expired(ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, true, t0, t0 + 60_000, 60_000))
        // Verified or ended: never.
        assertFalse(ConnectDeadline.expired(ConnectionStatus.CONNECTED, true, t0, t0 + 600_000, 60_000))
        assertFalse(ConnectDeadline.expired(ConnectionStatus.DISCONNECTED, true, t0, t0 + 600_000, 60_000))
        assertFalse(ConnectDeadline.expired(ConnectionStatus.FAILED, true, t0, t0 + 600_000, 60_000))
        // A saved config's race is not cut; its tunnel without traffic is.
        assertFalse(ConnectDeadline.expired(ConnectionStatus.CONNECTING, false, t0, t0 + 600_000, 60_000))
        assertTrue(ConnectDeadline.expired(ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, false, t0, t0 + 60_000, 60_000))
    }

    // ---- connect deadline: what follows ----------------------------------------------------------------------------

    private val all: (String) -> Boolean = { true }

    @Test fun theUsersStopAlwaysWins() {
        assertEquals(ConnectDeadline.Decision.Stopped, ConnectDeadline.decide(stopped = true, exitLocked = false,
            fallbacksSoFar = 0, tried = emptySet(), verifiedHere = "x", runnable = all))
    }

    @Test fun aLockedExitStopsWithAReason() {
        assertEquals(ConnectDeadline.Decision.GiveUp(ConnectDeadline.GiveUpReason.EXIT_LOCKED), ConnectDeadline.decide(stopped = false,
            exitLocked = true, fallbacksSoFar = 0, tried = emptySet(), verifiedHere = null, runnable = all))
    }

    @Test fun thePathVerifiedHereGoesFirstThenTheChainOverLantern() {
        val aether = ConfigFreeMethod.AETHER_WARP.id
        assertEquals(ConnectDeadline.Decision.Fallback("saved-1", "verified on this network before"),
            ConnectDeadline.decide(false, false, 0, setOf(aether), "saved-1", all))
        val next = ConnectDeadline.decide(false, false, 0, setOf(aether), null, all)
        assertEquals(ConfigFreeMethod.PSIPHON_RADIANCE.id, (next as ConnectDeadline.Decision.Fallback).profileId)
        // The chain was the pick: Tor next.
        val afterChain = ConnectDeadline.decide(false, false, 0, setOf(ConfigFreeMethod.PSIPHON_RADIANCE.id), null, all)
        assertEquals(ConfigFreeMethod.TOR_SNOWFLAKE.id, (afterChain as ConnectDeadline.Decision.Fallback).profileId)
    }

    @Test fun serverlessDirectIsNeverAFallback() {
        val tried = ConnectDeadline.CANDIDATES.toSet()
        // Remembered as verified here (what Smart Connect picked in the test), still not taken.
        assertEquals(ConnectDeadline.Decision.GiveUp(ConnectDeadline.GiveUpReason.NONE_LEFT),
            ConnectDeadline.decide(false, false, 0, tried, ConfigFreeMethod.SERVERLESS.id, all))
        assertFalse(ConfigFreeMethod.SERVERLESS.id in ConnectDeadline.CANDIDATES)
        assertTrue(ConnectDeadline.ispExit(com.example.vpn.connections.BuiltinConnections.DIRECT))
    }

    @Test fun candidatesThisBuildCannotRunAreSkippedAndFallbacksAreCapped() {
        val noChain: (String) -> Boolean = { it != ConfigFreeMethod.PSIPHON_RADIANCE.id }
        val next = ConnectDeadline.decide(false, false, 0, emptySet(), null, noChain)
        assertEquals(ConfigFreeMethod.TOR_SNOWFLAKE.id, (next as ConnectDeadline.Decision.Fallback).profileId)
        assertEquals(ConnectDeadline.Decision.GiveUp(ConnectDeadline.GiveUpReason.FALLBACKS_USED),
            ConnectDeadline.decide(false, false, ConnectDeadline.MAX_FALLBACKS, emptySet(), null, all))
    }

    // ---- stage timeline --------------------------------------------------------------------------------------------

    @Test fun everyStatusChangeBecomesAStageAndErrorsAreMarked() {
        val psiphon = ConfigFreeMethod.PSIPHON.profile()
        EngineStages.begin(EngineStages.engineOf(psiphon), now = 1)
        val a = ConnectionState(status = ConnectionStatus.PREPARING, activeProfile = psiphon)
        val b = a.copy(status = ConnectionStatus.VERIFYING)
        val c = b.copy(status = ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, errorMessage = "no request went through")
        EngineStages.onStateChange(a, b, now = 2)
        EngineStages.onStateChange(b, c, now = 3)
        // A speed update changes nothing.
        EngineStages.onStateChange(c, c.copy(downloadSpeedBps = 10), now = 4)
        val entries = EngineStages.entries.value
        assertEquals(listOf("connect requested", "verifying traffic", "tunnel up, no traffic"), entries.map { it.stage })
        assertEquals("psiphon", entries.last().engine)
        assertTrue(entries.last().error)
        assertEquals("no request went through", entries.last().detail)
    }

    @Test fun stagesNameTheEngineNeverTheServerAndAreRedactedAndCapped() {
        assertEquals("psiphon→radiance", EngineStages.engineOf(ConfigFreeMethod.PSIPHON_RADIANCE.profile()))
        assertEquals("aether/h2", EngineStages.engineOf(ConfigFreeMethod.AETHER_H2.profile()))
        assertEquals("xray", EngineStages.engineOf(com.example.data.model.VlessProfile(name = "My server", address = "203.0.113.9",
            port = 443, uuid = "11111111-1111-1111-1111-111111111111")))
        val lines = mutableListOf<String>()
        EngineStages.sink = { lines += it.line() }
        repeat(EngineStages.MAX_ENTRIES + 5) { EngineStages.record("tor", "step $it", now = it.toLong()) }
        assertEquals(EngineStages.MAX_ENTRIES, EngineStages.entries.value.size)
        assertEquals("step ${EngineStages.MAX_ENTRIES + 4}", EngineStages.entries.value.last().stage)
        assertEquals(EngineStages.MAX_ENTRIES + 5, lines.size)
        // The same stage twice in a row is kept once.
        EngineStages.record("tor", "bootstrap", "50%")
        EngineStages.record("tor", "bootstrap", "50%")
        assertEquals(1, EngineStages.entries.value.count { it.stage == "bootstrap" })
        EngineStages.record("tor", "long", "x".repeat(1_000))
        assertTrue(EngineStages.entries.value.last().detail!!.length <= EngineStages.MAX_DETAIL)
    }

    // ---- logcat mirror ---------------------------------------------------------------------------------------------

    @Test fun logcatLinesAreOneLineCappedAndNeverThrowOnTheJvm() {
        val line = com.example.xray.Logcat.format("VPN", "a\nb" + "x".repeat(2_000))
        assertFalse(line.contains('\n'))
        assertEquals(com.example.xray.Logcat.MAX_LINE, line.length)
        com.example.xray.Logcat.write(com.example.xray.LogLevel.ERROR, "VPN", "android.util.Log is a stub here")
        com.example.xray.XrayLogManager.i("VPN", "still logs without logcat")
    }
}
