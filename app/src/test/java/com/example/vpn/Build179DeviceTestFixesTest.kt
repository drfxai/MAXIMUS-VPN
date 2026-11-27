package com.example.vpn

import com.example.data.model.ConnectionRoute
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.ui.home.HomeSelection
import com.example.ui.home.SmartButtonState
import com.example.vpn.connections.AccessProbe
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.connections.BuiltinMethodMemory
import com.example.vpn.connections.CloudflareFilterMemory
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.connections.IrancellWarp
import com.example.vpn.connections.WarpDataPlaneHold
import com.example.vpn.diagnostics.EngineStages
import com.example.vpn.engine.registry.EngineLedger
import com.example.vpn.engine.registry.NetworkResults
import com.example.vpn.safety.MaximusVpnSupervisor
import com.example.vpn.sidecar.AetherSidecar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Build 179 device test on Irancell (files/engine-test-b179): the pure rules behind each fix. */
class Build179DeviceTestFixesTest {
    private val store = HashMap<String, String>()
    private lateinit var previousRead: (String) -> String?
    private lateinit var previousWrite: (String, String?) -> Unit
    private lateinit var previousNetwork: () -> String?

    @Before fun setUp() {
        previousRead = BuiltinMethodMemory.read
        previousWrite = BuiltinMethodMemory.write
        previousNetwork = BuiltinMethodMemory.network
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        BuiltinMethodMemory.network = { "wifi:other" }
        CloudflareFilterMemory.resetForTest()
        WarpDataPlaneHold.resetForTest()
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = previousRead
        BuiltinMethodMemory.write = previousWrite
        BuiltinMethodMemory.network = previousNetwork
        CloudflareFilterMemory.resetForTest()
        WarpDataPlaneHold.resetForTest()
    }

    // N1: the user's own pick whose first traffic check failed.
    @Test fun aManualPickRestartsItsEngineOnceThenFailsWithinItsDeadline() {
        fun decide(manual: Boolean = true, engine: Boolean = true, never: Boolean = true, retries: Int = 0,
                   elapsed: Long = 28_000, budget: Long = 95_000) =
            ManualCheckFailure.decide(manual, engine, never, retries, elapsed, budget)
        assertEquals(ManualCheckFailure.Decision.RETRY_SAME_ENGINE, decide())
        assertEquals(ManualCheckFailure.Decision.FAIL_NOW, decide(retries = 1))
        assertEquals(ManualCheckFailure.Decision.FAIL_NOW, decide(elapsed = 60_000))
        assertEquals(ManualCheckFailure.Decision.WATCH, decide(manual = false))
        assertEquals(ManualCheckFailure.Decision.WATCH, decide(engine = false))
        assertEquals(ManualCheckFailure.Decision.WATCH, decide(never = false))
    }

    @Test fun aRestartOfTheSameManualPickKeepsTheFirstAttemptsClock() {
        assertEquals(1_000L, ManualCheckFailure.anchoredSince(manual = true, sameProfile = true, anchorSince = 1_000L, now = 50_000L))
        assertEquals(50_000L, ManualCheckFailure.anchoredSince(manual = true, sameProfile = false, anchorSince = 1_000L, now = 50_000L))
        assertEquals(50_000L, ManualCheckFailure.anchoredSince(manual = false, sameProfile = true, anchorSince = 1_000L, now = 50_000L))
        assertEquals(50_000L, ManualCheckFailure.anchoredSince(manual = true, sameProfile = true, anchorSince = null, now = 50_000L))
        val message = ManualCheckFailure.message("Psiphon", "PROXY_HANDSHAKE_FAILED", 61, retried = true)
        assertTrue(message, message.contains("restart of the same engine did not help"))
        assertTrue(message, message.contains("no other connection was started"))
    }

    // N2
    @Test fun thePinnedLogNamesHowTheSessionWasStarted() {
        assertEquals("the power button", ConnectOrigin.pinnedBy(true))
        assertEquals("your own pick", ConnectOrigin.pinnedBy(false))
        assertEquals("the power button's config", ConnectOrigin.configOf(true))
        assertEquals("your own pick", ConnectOrigin.configOf(false))
    }

    // N3
    @Test fun theWarpHoldNeverRefusesTheUsersOwnPick() {
        assertFalse(ConnectionEngineRunner.holdRefuses(manual = true))
        assertTrue(ConnectionEngineRunner.holdRefuses(manual = false))
        assertTrue(ConnectionEngineRunner.manualPickDespiteHold(12 * 60_000L).contains("your own pick runs anyway"))
    }

    // N4
    @Test fun theLedgerKeepsEachConnectionsResultByItsPath() {
        var now = 1_000_000L
        val ledger = EngineLedger(clock = { now })
        ledger.pathVerified("psiphon→radiance", "cell:43235")
        now += 60_000
        ledger.pathFailed("aether/h3", "cell:43235", "TLS_HANDSHAKE_FAILED: no gateway")
        now += 60_000
        ledger.pathVerified("radiance", "cell:43235")
        ledger.pathVerified("radiance", "wifi:home")
        val here = ledger.pathResults("cell:43235")
        assertEquals(listOf("radiance", "aether/h3", "psiphon→radiance"), here.map { it.path })
        assertFalse(here[1].verified)
        assertEquals("TLS_HANDSHAKE_FAILED: no gateway", here[1].reason)
        assertNull(ledger.pathResult("psiphon", "cell:43235"))
        // A path entry is never read as an engine's own evidence.
        assertTrue(ledger.pathResults("wifi:home").single().verified)
        now += EngineLedger.HISTORY_MS
        assertTrue(ledger.pathResults("cell:43235").isEmpty())
    }

    @Test fun networkResultsNameChainsTheWayThePickerDoes() {
        assertEquals("Psiphon over Lantern", NetworkResults.title("psiphon→radiance"))
        assertEquals("Shir o Khorshid over Lantern", NetworkResults.title("shirokhorshid→radiance"))
        assertEquals("Aether over Lantern", NetworkResults.title("aether/h2→radiance"))
        assertEquals("Aether → Tor", NetworkResults.title("aether/h2→tor"))
        assertEquals("Aether → Psiphon", NetworkResults.title("aether/h2→psiphon"))
        assertEquals("MASQUE HTTP/3", NetworkResults.title("aether/h3"))
        assertEquals("Lantern / Radiance", NetworkResults.title("radiance"))
        val now = 10 * 60_000L
        val ok = EngineLedger.PathResult("radiance", true, now - 3 * 60_000L, null, 2, 0)
        val bad = EngineLedger.PathResult("aether/h3", false, now - 60_000L, "x", 0, 1)
        assertEquals("Lantern / Radiance · verified 3 min ago (2 of 2 verified)", NetworkResults.line(ok, now))
        assertEquals("Verified on this network in the last 24 h: Lantern / Radiance (3 min ago).",
            NetworkResults.labLine(listOf(bad, ok), now))
        assertNull(NetworkResults.labLine(listOf(bad), now))
        assertEquals("Failed here 1 min ago", NetworkResults.cardLine(bad, now, "Not tested yet"))
        assertEquals("Not tested yet", NetworkResults.cardLine(null, now, "Not tested yet"))
    }

    // N5 / N11
    @Test fun aCloudflareFilterReadingIsRememberedForSixHoursOnItsNetwork() {
        val net = "cell:43235"
        val t0 = 1_000_000L
        assertTrue(CloudflareFilterMemory.knownNow(net, freshFiltered = true, freshReachable = false, now = t0))
        // No fresh reading 5 h later (the 10 min freshness has long passed): still known.
        assertTrue(CloudflareFilterMemory.knownNow(net, freshFiltered = false, freshReachable = false, now = t0 + 5 * 3_600_000L))
        assertFalse(CloudflareFilterMemory.knownNow("wifi:home", freshFiltered = false, freshReachable = false, now = t0 + 60_000L))
        assertFalse(CloudflareFilterMemory.knownNow(net, false, false, now = t0 + CloudflareFilterMemory.MEMORY_MS))
        // A fresh reading that Cloudflare answers clears it.
        CloudflareFilterMemory.record(net, t0)
        assertFalse(CloudflareFilterMemory.knownNow(net, freshFiltered = false, freshReachable = true, now = t0 + 60_000L))
        assertNull(CloudflareFilterMemory.knownAt(net, t0 + 120_000L))
        // Survives a restart through the store.
        CloudflareFilterMemory.record(net, t0)
        CloudflareFilterMemory.resetForTest()
        assertEquals(t0, CloudflareFilterMemory.knownAt(net, t0 + 1_000L))
        assertTrue(CloudflareFilterMemory.known(false, false, rememberedAt = null, warpHeld = true))
        assertFalse(CloudflareFilterMemory.known(false, freshReachable = true, rememberedAt = t0, warpHeld = true))
    }

    // N6
    @Test fun aRouteThatExitsThroughTorIsTheTorNetworkBeforeTheTraceAnswers() {
        assertTrue(ConnectionRoute.exitsThroughTor("Tor"))
        assertTrue(ConnectionRoute.exitsThroughTor("Aether WARP · MASQUE HTTP/2 · Tor (chain)"))
        assertFalse(ConnectionRoute.exitsThroughTor("Psiphon"))
        assertEquals("Tor · Tor network", ConnectionRoute.withExitCountry("Tor", null))
        assertEquals("Psiphon · Unknown country", ConnectionRoute.withExitCountry("Psiphon", null))
    }

    // N7 / N14
    @Test fun chainPicksAreNamedAsChainsInTheLog() {
        assertEquals("aether/h2→radiance", EngineStages.engineOf(ConfigFreeMethod.AETHER_LANTERN.profile()))
        assertEquals("aether/h2→tor", EngineStages.engineOf(ConfigFreeMethod.AETHER_TOR.profile()))
        assertEquals("aether/h2→psiphon", EngineStages.engineOf(ConfigFreeMethod.AETHER_PSIPHON.profile()))
        assertEquals("aether/h2", EngineStages.engineOf(ConfigFreeMethod.AETHER_H2.profile()))
    }

    // N8
    @Test fun aStopIsLoggedFromTheStateTheUserSawNotFromStopping() {
        assertEquals("CONNECTED", StopGuard.fromStatus("DISCONNECTING", "CONNECTED"))
        assertEquals("DISCONNECTING", StopGuard.fromStatus("DISCONNECTING", null))
        assertEquals("VERIFYING", StopGuard.fromStatus("VERIFYING", "CONNECTED"))
    }

    // N9
    @Test fun theSmartButtonSaysTheUsersPickIsRunningAndTheCardCanBeCleared() {
        assertEquals(SmartButtonState.MANUAL, SmartButtonState.of(ConnectionState(status = ConnectionStatus.CONNECTED)))
        assertEquals(SmartButtonState.MANUAL,
            SmartButtonState.of(ConnectionState(status = ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED)))
        assertEquals(SmartButtonState.ON, SmartButtonState.of(ConnectionState(status = ConnectionStatus.CONNECTED, smartSession = true)))
        assertEquals(SmartButtonState.OFF, SmartButtonState.of(ConnectionState(status = ConnectionStatus.DISCONNECTED)))
        val p = AetherSidecar.profile()
        val failed = ConnectionState(status = ConnectionStatus.FAILED, activeProfile = p, errorMessage = "x")
        val cleared = HomeSelection.cleared(failed)
        assertNull(cleared.activeProfile)
        assertEquals(ConnectionStatus.FAILED, cleared.status)
        assertEquals("x", cleared.errorMessage)
        val running = ConnectionState(status = ConnectionStatus.CONNECTED, activeProfile = p)
        assertEquals(running, HomeSelection.cleared(running))
    }

    // N12
    @Test fun theHttpsCheckGetsWhatIsLeftOfTheBudget() {
        assertNull(ConnectionEngineRunner.verifyWindowMs(null))
        assertEquals(ConnectionEngineRunner.VERIFY_MIN_MS, ConnectionEngineRunner.verifyWindowMs(2_000L))
        assertEquals(ConnectionEngineRunner.VERIFY_MIN_MS, ConnectionEngineRunner.verifyWindowMs(-5_000L))
        assertEquals(20_000L, ConnectionEngineRunner.verifyWindowMs(20_000L))
        val slow = ConnectionEngineRunner.boundedCheck(200L) { Thread.sleep(5_000); AccessProbe.Result(traffic = true) }
        assertFalse(slow.traffic)
        assertTrue(slow.failure.orEmpty(), slow.failure.orEmpty().contains("did not finish"))
        assertTrue(ConnectionEngineRunner.boundedCheck(2_000L) { AccessProbe.Result(traffic = true) }.traffic)
        assertTrue(ConnectionEngineRunner.boundedCheck(null) { AccessProbe.Result(traffic = true) }.traffic)
    }

    // N13
    @Test fun logTextsNoLongerBlameTheUserOrPromiseAFallback() {
        assertEquals("your Connect failed (Daily mode, Kill Switch off)",
            MaximusVpnSupervisor.describe(MaximusVpnSupervisor.Release.CONNECT_FAILED_BY_USER))
        val manual = ConnectionEngineRunner.budgetStopText(60_000L, listOf("wiw"), manual = true)
        assertFalse(manual, manual.contains("falling back to saved servers"))
        assertTrue(ConnectionEngineRunner.budgetStopText(60_000L, listOf("wiw"), manual = false).contains("falling back to saved servers"))
    }

    // Aether IPv6-first on Irancell
    @Test fun theUsersAetherPickOnIrancellTriesIpv6First() {
        val a = AetherSidecar.ID
        assertTrue(ConnectionEngineRunner.cfV6First(a, true, true, false, irancell = true, mci = false, relayOnly = false, manual = true))
        assertFalse(ConnectionEngineRunner.cfV6First(a, true, true, false, irancell = true, mci = false, relayOnly = false, manual = false))
        assertFalse(ConnectionEngineRunner.cfV6First(a, true, true, ipv4Only = true, irancell = true, mci = false, relayOnly = false, manual = true))
        assertFalse(ConnectionEngineRunner.cfV6First(a, true, true, false, irancell = false, mci = true, relayOnly = false, manual = true))
        val methods = listOf(ConnectionEngineRunner.H2_V6, ConnectionEngineRunner.H2_SPLIT, "h2")
        assertEquals(listOf(ConnectionEngineRunner.H2_V6, ConnectionEngineRunner.H2_SPLIT, "h2"),
            IrancellWarp.plan(methods, null, manual = true, v6First = true))
        assertEquals(listOf(ConnectionEngineRunner.H2_SPLIT, "h2"), IrancellWarp.plan(methods, null, manual = true))
        assertEquals(listOf(ConnectionEngineRunner.H2_SPLIT), IrancellWarp.plan(methods, null, manual = false, v6First = true))
    }
}
