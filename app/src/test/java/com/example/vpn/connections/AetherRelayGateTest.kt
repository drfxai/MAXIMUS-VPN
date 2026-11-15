package com.example.vpn.connections

import com.example.vpn.connections.AetherBackgroundRelay.Gate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 150: the background relay started 6 s after connect on a metered link while the main tunnel was failing. */
class AetherRelayGateTest {
    private fun gate(firstPass: Long, now: Long, metered: Boolean = true, strikes: Int = 0, waited: Long = 0) =
        AetherBackgroundRelay.gate(firstPass, now, metered, strikes, registrationBlocked = false, waitedMs = waited)

    @Test fun noRealCheckAfterConnectMeansWait() {
        assertEquals(Gate.WAIT, gate(firstPass = 0, now = 6_000))
    }

    @Test fun aRealCheckPlusTheGracePeriodStartsIt() {
        assertEquals(Gate.WAIT, gate(firstPass = 10_000, now = 10_000 + AetherBackgroundRelay.GRACE_AFTER_REAL_CHECK_MS - 1))
        assertEquals(Gate.START, gate(firstPass = 10_000, now = 10_000 + AetherBackgroundRelay.GRACE_AFTER_REAL_CHECK_MS))
    }

    @Test fun onAMeteredLinkFailoverStrikesHoldItBack() {
        assertEquals(Gate.WAIT, gate(firstPass = 1, now = 60_000, strikes = 1))
        assertEquals("unmetered: strikes do not hold it", Gate.START, gate(firstPass = 1, now = 60_000, metered = false, strikes = 1))
        assertTrue(AetherBackgroundRelay.mustStop(metered = true, strikes = 1))
        assertFalse(AetherBackgroundRelay.mustStop(metered = true, strikes = 0))
        assertFalse(AetherBackgroundRelay.mustStop(metered = false, strikes = 2))
    }

    @Test fun waitingTooLongGivesUp() {
        assertEquals(Gate.SKIP_TIMEOUT, gate(firstPass = 0, now = 1, waited = AetherBackgroundRelay.MAX_GATE_WAIT_MS))
    }
}
