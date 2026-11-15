package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 151: the shield's bulk test was cancelled when the session ended and no verdict was recorded. */
class UplinkShieldBookInconclusiveTest {
    private var stored: String? = null
    private var now = 1_000_000L
    private fun book() = UplinkShieldBook({ stored }, { stored = it }, { now })
    private val net = "cell:43235"

    @Test fun oneInconclusiveRunIsNotHelpAndTheShieldMayStillBeTried() {
        assertEquals(1, book().recordInconclusive(net))
        assertFalse(book().active(net))
        assertTrue(book().mayTry(net))
        assertEquals("inconclusive", book().describe(net))
    }

    @Test fun twoInconclusiveRunsSkipTheShieldFor12Hours() {
        book().recordInconclusive(net)
        now += 60_000
        assertEquals(2, book().recordInconclusive(net))       // a fresh book: the count survived a restart
        assertFalse(book().mayTry(net))
        now += UplinkShieldBook.NO_HELP_COOLDOWN_MS
        assertTrue(book().mayTry(net))
    }

    @Test fun oneFailureSkipsItAtOnce() {
        book().record(net, helped = false)
        assertFalse(book().mayTry(net))
    }

    @Test fun anInconclusiveRunNeverUndoesAShieldThatHelped() {
        book().record(net, helped = true)
        assertEquals(0, book().recordInconclusive(net))
        assertTrue(book().active(net))
    }

    @Test fun aRealVerdictReplacesTheInconclusiveCount() {
        book().recordInconclusive(net)
        book().record(net, helped = true)
        assertTrue(book().active(net))
        book().record(net, helped = false)
        assertEquals(0, book().recordInconclusive(net))      // the failure's 12 h hold is kept
        assertFalse(book().mayTry(net))
    }

    @Test fun otherNetworksAreUntouched() {
        book().recordInconclusive(net); book().recordInconclusive(net)
        assertTrue(book().mayTry("wifi"))
    }
}
