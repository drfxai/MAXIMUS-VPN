package com.example.vpn.connections

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build 151 (Irancell, cf=0): the account API lane passed, the gateway scan found no serving tunnel within 19 s, and
 * the same ladder ran again 23 s later; the background wireguard-relay then started while the main tunnel's bulk test
 * was still running.
 */
class WarpDataPlaneHoldTest {
    private val store = HashMap<String, String>()
    private lateinit var previousRead: (String) -> String?
    private lateinit var previousWrite: (String, String?) -> Unit
    private val net = "cell:43235"
    private val scanFailure = "Aether gateway scan did not reach a serving tunnel within 19s; traffic is unverified."

    @Before fun setUp() {
        previousRead = BuiltinMethodMemory.read
        previousWrite = BuiltinMethodMemory.write
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        WarpDataPlaneHold.resetForTest()
        WarpRegistrationMemory.resetForTest()
        AetherLadderGuard.resetForTest()
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = previousRead
        BuiltinMethodMemory.write = previousWrite
        WarpDataPlaneHold.resetForTest()
        WarpRegistrationMemory.resetForTest()
        AetherLadderGuard.resetForTest()
    }

    @Test fun aGatewayScanFailureWithCloudflareFilteredStartsTheHold() {
        assertTrue(WarpDataPlaneHold.startsHold(scanFailure, cloudflareBlocked = true, relayed = false))
        assertFalse(WarpDataPlaneHold.startsHold(scanFailure, cloudflareBlocked = false, relayed = false))
        assertFalse(WarpDataPlaneHold.startsHold(scanFailure, cloudflareBlocked = true, relayed = true))
        assertFalse(WarpDataPlaneHold.startsHold("WARP API registration failed (account bootstrap or refresh timed out after 14s)",
            cloudflareBlocked = true, relayed = false))
    }

    @Test fun theHoldLastsThirtyMinutesOnThatNetworkAndSurvivesARestart() {
        WarpDataPlaneHold.record(net, now = 1_000)
        WarpDataPlaneHold.resetForTest()
        assertTrue(WarpDataPlaneHold.blocked(net, now = 1_000 + 29 * 60_000))
        assertFalse(WarpDataPlaneHold.blocked(net, now = 1_000 + WarpDataPlaneHold.HOLD_MS))
        assertFalse(WarpDataPlaneHold.blocked("wifi", now = 2_000))
        WarpDataPlaneHold.clear(net)
        assertFalse(WarpDataPlaneHold.blocked(net, now = 2_000))
    }

    @Test fun itIsSeparateFromTheRegistrationMemory() {
        WarpRegistrationMemory.record(net, now = 1_000)
        WarpDataPlaneHold.record(net, now = 2_000)
        WarpRegistrationMemory.clear(net)               // registration works: that memory is cleared, the hold stays
        assertFalse(WarpRegistrationMemory.blocked(net, now = 3_000))
        assertTrue(WarpDataPlaneHold.blocked(net, now = 3_000))
    }

    @Test fun theSameLadderDoesNotRunTwiceWithinSixtySeconds() {
        val t0 = 1_794_760_432_300L                      // 16:33:52 first ladder
        assertNull(AetherLadderGuard.begin(net, "ladder", now = t0))
        AetherLadderGuard.finish(net, "ladder", now = t0 + 20_049)   // it failed at 16:34:12
        // 16:34:35, 23 s after the failure: refused.
        assertEquals(23_000L, AetherLadderGuard.begin(net, "ladder", now = t0 + 43_049))
        // Another network or the relay kind is independent; a manual run is recorded but never refused.
        assertNull(AetherLadderGuard.begin("wifi", "ladder", now = t0 + 43_049))
        assertNull(AetherLadderGuard.begin(net, "relay", now = t0 + 43_049))
        assertNull(AetherLadderGuard.begin(net, "ladder", now = t0 + 43_049, enforce = false))
        // A minute after the last run it may run again.
        assertNull(AetherLadderGuard.begin(net, "ladder", now = t0 + 43_049 + AetherLadderGuard.MIN_GAP_MS))
    }

    @Test fun theBackgroundRelayIsSkippedDuringTheHoldAndWaitsForTheBulkTest() {
        assertEquals(AetherBackgroundRelay.Gate.SKIP_WARP_DATA, AetherBackgroundRelay.gate(
            firstRealPassAt = 1, now = 60_000, metered = true, strikes = 0, registrationBlocked = false, waitedMs = 0,
            warpDataHeld = true))
        // 16:36:21: real check passed + 20 s, but bulk.tun was still running: wait.
        assertEquals(AetherBackgroundRelay.Gate.WAIT, AetherBackgroundRelay.gate(
            firstRealPassAt = 1, now = 60_000, metered = true, strikes = 0, registrationBlocked = false, waitedMs = 30_000,
            bulkRunning = true))
        assertEquals(AetherBackgroundRelay.Gate.START, AetherBackgroundRelay.gate(
            firstRealPassAt = 1, now = 60_000, metered = true, strikes = 0, registrationBlocked = false, waitedMs = 30_000,
            bulkRunning = false))
        assertEquals(AetherBackgroundRelay.Gate.SKIP_TIMEOUT, AetherBackgroundRelay.gate(
            firstRealPassAt = 1, now = 60_000, metered = true, strikes = 0, registrationBlocked = false,
            waitedMs = AetherBackgroundRelay.MAX_GATE_WAIT_MS, bulkRunning = true))
    }
}
