package com.example.vpn.smart

import com.example.vpn.stealth.recordslice.UplinkShield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uplink Shield MSS ladder: 1360 → 1220 → 1140, the working step remembered per network. */
class UplinkShieldLadderTest {
    private var stored: String? = null
    private var now = 5_000_000L
    private fun book() = UplinkShieldBook({ stored }, { stored = it }, { now })
    private val net = "cell:43235"

    @Test fun ladderSteps() {
        assertEquals(listOf(1360, 1220, 1140), UplinkShield.LADDER)
        assertEquals(1220, UplinkShield.nextStep(1360))
        assertEquals(1140, UplinkShield.nextStep(1220))
        assertNull(UplinkShield.nextStep(1140))
    }

    @Test fun aFreshNetworkStartsAtTheFirstStep() {
        assertEquals(1360, book().nextMss(net))
        assertTrue(book().mayTry(net))
        assertNull(book().activeMss(net))
    }

    @Test fun eachNoHelpStepsDownUntilTheLadderEnds() {
        book().record(net, helped = false, mss = 1360)
        assertTrue(book().mayTry(net))
        assertEquals(1220, book().nextMss(net))
        book().record(net, helped = false, mss = 1220)
        assertTrue(book().mayTry(net))
        assertEquals(1140, book().nextMss(net))
        book().record(net, helped = false, mss = 1140)
        assertFalse(book().mayTry(net))
        now += UplinkShieldBook.NO_HELP_COOLDOWN_MS
        assertTrue(book().mayTry(net))
        assertEquals(1360, book().nextMss(net))     // after the cooldown the ladder starts again
    }

    @Test fun theWorkingStepIsRememberedPerNetworkAcrossRestarts() {
        book().record(net, helped = false, mss = 1360)
        book().record(net, helped = true, mss = 1220)
        assertEquals(1220, book().activeMss(net))   // a fresh book reads it back from storage
        assertEquals(1220, book().nextMss(net))
        assertTrue(book().active(net))
        assertNull(book().activeMss("wifi"))
        now += UplinkShieldBook.TTL_MS
        assertNull(book().activeMss(net))
    }

    @Test fun anInconclusiveRunAtASmallerStepIsCountedAndRetriedAtThatStep() {
        book().record(net, helped = false, mss = 1360)
        assertEquals(1, book().recordInconclusive(net, 1220))
        assertEquals(1220, book().nextMss(net))
        assertTrue(book().mayTry(net))
        assertEquals(2, book().recordInconclusive(net, 1220))
        assertFalse(book().mayTry(net))
    }

    @Test fun oldEntriesWithoutAnMssReadAsTheFirstStep() {
        stored = """{"schema":1,"networks":{"$net":{"h":true,"at":$now}}}"""
        assertEquals(1360, book().activeMss(net))
    }

    @Test fun freezeFailoverUsesTheGivenStep() {
        val p = com.example.data.model.VlessProfile(name = "s", address = "1.2.3.4", port = 443,
            uuid = "12345678-1234-1234-1234-123456789abc", transport = "tcp", security = "tls", sni = "a.example.com")
        val v = UplinkShield.enable(p, 1140)
        assertEquals(1140, UplinkShield.mss(v))
        assertEquals(p.id, v.id)
    }
}
