package com.example.vpn.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A real-request failure holds a path back only briefly, and a later pass clears it at once. */
class RecentFailWindowTest {
    private var now = 5_000_000L
    private val book = EvidenceBook { now }
    private fun record(ref: String, ok: Boolean) =
        book.recordPath(EvidenceSource.SMART_CONNECT, MeasurementType.REAL_REQUEST, ref, "REALITY", ok)

    @Test fun theHoldIsNinetySeconds() {
        assertEquals(90_000L, EvidenceBook.RECENT_FAIL_MS)
        book.observeNetwork("cell:43235")
        record("fr5", false)
        assertEquals(PathEligibility.RECENTLY_FAILED, book.eligibility("fr5"))
        assertEquals(now, book.recentFailureAt("fr5"))
        now += 91_000
        assertEquals(PathEligibility.UNTESTED, book.eligibility("fr5"))
        assertNull(book.recentFailureAt("fr5"))
    }

    @Test fun aLaterVerifiedPassClearsTheHold() {
        book.observeNetwork("cell:43235")
        record("de1", false)
        now += 10_000
        record("de1", true)
        assertNull(book.recentFailureAt("de1"))
        assertEquals(true, book.eligibility("de1").automatic)
    }
}
