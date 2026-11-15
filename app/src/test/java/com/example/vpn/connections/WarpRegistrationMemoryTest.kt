package com.example.vpn.connections

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Build 150 (Irancell): WARP registration timed out three times in two minutes on the same network. */
class WarpRegistrationMemoryTest {
    private val store = HashMap<String, String>()
    private lateinit var previousRead: (String) -> String?
    private lateinit var previousWrite: (String, String?) -> Unit

    @Before fun setUp() {
        previousRead = BuiltinMethodMemory.read
        previousWrite = BuiltinMethodMemory.write
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        WarpRegistrationMemory.resetForTest()
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = previousRead
        BuiltinMethodMemory.write = previousWrite
        WarpRegistrationMemory.resetForTest()
    }

    @Test fun aFailureHoldsForThirtyMinutesOnThatNetworkOnly() {
        WarpRegistrationMemory.record("cell:43235", now = 1_000)
        assertTrue(WarpRegistrationMemory.blocked("cell:43235", now = 1_000 + 29 * 60_000))
        assertFalse(WarpRegistrationMemory.blocked("cell:43235", now = 1_000 + WarpRegistrationMemory.HOLD_MS))
        assertFalse(WarpRegistrationMemory.blocked("wifi", now = 2_000))
        assertFalse(WarpRegistrationMemory.blocked(null, now = 2_000))
    }

    @Test fun itSurvivesARestartThroughThePersistedStore() {
        WarpRegistrationMemory.record("cell:43235", now = 5_000)
        WarpRegistrationMemory.resetForTest()          // the process restarted: only the store is left
        assertEquals(5_000L, WarpRegistrationMemory.failedAt("cell:43235", now = 6_000))
    }

    @Test fun aWorkingCarrierClearsIt() {
        WarpRegistrationMemory.record("cell:43235", now = 5_000)
        WarpRegistrationMemory.clear("cell:43235")
        assertFalse(WarpRegistrationMemory.blocked("cell:43235", now = 6_000))
        assertTrue(store.isEmpty())
    }

    @Test fun theRelayGateSkipsWhileRegistrationIsHeld() {
        assertEquals(AetherBackgroundRelay.Gate.SKIP_REGISTRATION, AetherBackgroundRelay.gate(
            firstRealPassAt = 1, now = 60_000, metered = true, strikes = 0, registrationBlocked = true, waitedMs = 0))
    }
}
