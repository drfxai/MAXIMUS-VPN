package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the download check measured through each server steers the next Smart Connect on that network only. */
class RouteSpeedBookTest {
    private var stored: String? = null
    private var now = 1_000_000L
    private fun book() = RouteSpeedBook({ stored }, { stored = it }, { now })
    private val net = "cell:43235"

    @Test fun aStallWeighsMostAndIsRememberedAcrossARestart() {
        book().record(net, "a", null, stalled = true)
        assertEquals(RouteSpeedBook.STALL_PENALTY_MS, book().penaltyMs(net, "a"))
        assertEquals(0L, book().penaltyMs("wifi", "a"))
        now += RouteSpeedBook.STALL_MEMORY_MS
        assertEquals(RouteSpeedBook.SLOW_PENALTY_MS, book().penaltyMs(net, "a"))
        now += RouteSpeedBook.TTL_MS
        assertEquals(0L, book().penaltyMs(net, "a"))
    }

    @Test fun aSlowDownloadCostsInProportionAndAFastOneNothing() {
        val b = book()
        b.record(net, "fast", RouteSpeedBook.GOOD_BYTES_PER_SECOND * 2, stalled = false)
        b.record(net, "half", RouteSpeedBook.GOOD_BYTES_PER_SECOND / 2, stalled = false)
        assertEquals(0L, b.penaltyMs(net, "fast"))
        assertEquals(RouteSpeedBook.SLOW_PENALTY_MS / 2, b.penaltyMs(net, "half"))
        assertEquals(0L, b.penaltyMs(net, "never-measured"))
    }

    @Test fun theLatestResultReplacesTheEarlierOne() {
        val b = book()
        b.record(net, "a", null, stalled = true)
        b.record(net, "a", RouteSpeedBook.GOOD_BYTES_PER_SECOND, stalled = false)
        assertEquals(0L, b.penaltyMs(net, "a"))
        assertTrue(book().sample(net, "a")?.stalled == false)
    }

    @Test fun nothingMeasuredIsNotRecorded() {
        book().record(net, "a", null, stalled = false)
        assertNull(book().sample(net, "a"))
    }

    @Test fun clearForgetsEverything() {
        val b = book()
        b.record(net, "a", null, stalled = true)
        b.clear()
        assertNull(book().sample(net, "a"))
    }
}
