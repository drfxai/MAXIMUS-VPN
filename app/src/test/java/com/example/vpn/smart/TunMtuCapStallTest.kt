package com.example.vpn.smart

import com.example.vpn.smart.TunMtuTrial.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 145: every stall was 16050 B (cap class), yet the network stayed on MTU 1280 "testing whether MTU is the cause". */
class TunMtuCapStallTest {
    private var stored: String? = null
    private val now = 1_000L
    private fun trial() = TunMtuTrial({ stored }, { stored = it }, { now })
    private val net = "cell:43235"

    @Test fun onlyAnMtuClassStallStartsTheTrial() {
        assertTrue(StallFailover.mtuTrialFor(900))
        assertFalse(StallFailover.mtuTrialFor(16_050))
        val t = trial()
        assertNull(t.onBulkCheck(net, 1400, 1400, stalled = true, passed = false, mayStart = StallFailover.mtuTrialFor(16_050)))
        assertEquals(1400, t.mtuFor(net, 1400))
    }

    @Test fun aCapStallClearsAStaleLowMtuTrial() {
        val t = trial()
        assertEquals(State.TRYING_LOW, t.onBulkCheck(net, 1400, 1400, stalled = true, passed = false, mayStart = true))
        assertEquals(TunMtuTrial.LOW_MTU, t.mtuFor(net, 1400))
        val cap = StallFailover.capStall(stalled = true, froze = true, bytes = 16_050)
        assertTrue(cap)
        assertNull(t.onBulkCheck(net, TunMtuTrial.LOW_MTU, 1400, stalled = false, passed = false, capStall = cap))
        assertTrue(t.lastClearedByCap)
        assertNull(t.state(net))
        assertEquals("configured MTU comes back", 1400, trial().mtuFor(net, 1400))
        // Cleared, not marked as tested: a real MTU-class stall may still start a trial later.
        assertEquals(State.TRYING_LOW, trial().onBulkCheck(net, 1400, 1400, stalled = true, passed = false, mayStart = true))
    }

    @Test fun aCapStallWithNoTrialChangesNothing() {
        val t = trial()
        assertNull(t.onBulkCheck(net, 1400, 1400, stalled = true, passed = false, capStall = true))
        assertFalse(t.lastClearedByCap)
        assertEquals(1400, t.mtuFor(net, 1400))
    }

    @Test fun capStallNeedsAStallAboveTheMtuWindow() {
        assertFalse(StallFailover.capStall(stalled = true, froze = false, bytes = 1200))
        assertFalse(StallFailover.capStall(stalled = false, froze = false, bytes = 16_050))
        assertFalse(StallFailover.capStall(stalled = true, froze = false, bytes = null))
        assertTrue(StallFailover.capStall(stalled = false, froze = true, bytes = 16_384))
    }
}
