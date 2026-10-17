package com.example.vpn.smart

import com.example.vpn.smart.TunMtuTrial.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tunnel MTU is lowered only to test a bulk stall, and only kept when the test shows it helped. */
class TunMtuTrialTest {
    private var stored: String? = null
    private var now = 1_000L
    private fun trial() = TunMtuTrial({ stored }, { stored = it }, { now })
    private val net = "cell:43235"

    @Test fun noStallMeansTheConfiguredMtu() {
        val t = trial()
        assertNull(t.onBulkCheck(net, 1400, 1400, stalled = false, passed = true))
        assertEquals(1400, t.mtuFor(net, 1400))
    }

    @Test fun aStallTestsTheLowMtuNextTimeAndKeepsItWhenItHelps() {
        assertEquals(State.TRYING_LOW, trial().onBulkCheck(net, 1400, 1400, stalled = true, passed = false))
        val next = trial() // survives a restart
        assertEquals(TunMtuTrial.LOW_MTU, next.mtuFor(net, 1400))
        assertEquals(1400, next.mtuFor("wifi:aaaa", 1400))
        assertEquals(State.LOW_HELPED, next.onBulkCheck(net, TunMtuTrial.LOW_MTU, 1400, stalled = false, passed = true))
        assertEquals(TunMtuTrial.LOW_MTU, trial().mtuFor(net, 1400))
    }

    @Test fun whenTheLowMtuStallsTooTheConfiguredOneComesBackAndIsNotRetested() {
        val t = trial()
        t.onBulkCheck(net, 1400, 1400, stalled = true, passed = false)
        assertEquals(State.LOW_DID_NOT_HELP, t.onBulkCheck(net, TunMtuTrial.LOW_MTU, 1400, stalled = true, passed = false))
        assertEquals(1400, t.mtuFor(net, 1400))
        assertNull("no second trial while the result holds", t.onBulkCheck(net, 1400, 1400, stalled = true, passed = false))
    }

    @Test fun anInconclusiveCheckChangesNothing() {
        val t = trial()
        t.onBulkCheck(net, 1400, 1400, stalled = true, passed = false)
        // A failed small request (neither stalled nor passed) says nothing about MTU.
        assertNull(t.onBulkCheck(net, TunMtuTrial.LOW_MTU, 1400, stalled = false, passed = false))
        assertEquals(State.TRYING_LOW, t.state(net))
    }

    @Test fun resultsExpireAndClearForgetsThem() {
        val t = trial()
        t.onBulkCheck(net, 1400, 1400, stalled = true, passed = false)
        now += TunMtuTrial.TTL_MS
        assertEquals(1400, t.mtuFor(net, 1400))
        now = 1_000L
        t.clear()
        assertEquals(1400, trial().mtuFor(net, 1400))
    }

    @Test fun aConfiguredMtuAtTheFloorIsNeverTested() {
        assertNull(trial().onBulkCheck(net, 1280, 1280, stalled = true, passed = false))
    }

    @Test fun clearingNetworkMemoryAlsoForgetsTheMtuTest() {
        val m = NetworkMemory({ null }, {}, loadMtu = { stored }, saveMtu = { stored = it })
        m.mtu.onBulkCheck(net, 1400, 1400, stalled = true, passed = false)
        m.clear()
        assertEquals(1400, trial().mtuFor(net, 1400))
    }
}
