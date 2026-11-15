package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 150 (Irancell): strikes went 1/3, 2/3, then a lucky small request reset them; five minutes, no failover. */
class StrikeWindowTest {
    private var now = 0L
    private fun window() = StrikeWindow(clock = { now })

    @Test fun threeFailuresInsideTheWindowSwitchEvenWithPassesBetween() {
        val w = window()
        assertEquals(1, w.fail()); now += 3_000
        assertEquals(2, w.fail()); now += 3_000
        w.pass(); now += 12_000                       // one small request got through
        assertFalse(w.shouldSwitch())
        assertEquals("a pass never erases strikes inside the window", 3, w.fail())
        assertTrue(w.shouldSwitch())
    }

    @Test fun aPassEndsTheRunOfFailuresForTheRecheckCadence() {
        val w = window()
        w.fail(); w.fail()
        assertEquals(2, w.consecutive())
        w.pass()
        assertEquals(0, w.consecutive())
        assertEquals(2, w.count())
    }

    @Test fun strikesAgeOutOfTheThreeMinuteWindow() {
        val w = window()
        w.fail(); now += 61_000
        w.fail(); now += StrikeWindow.WINDOW_MS - 60_000   // the first strike is now older than the window
        assertEquals(2, w.fail())
        assertFalse(w.shouldSwitch())
    }

    @Test fun onlySustainedRealPassesClearTheWindow() {
        val w = window()
        w.fail(); w.fail()
        w.pass(); w.pass()
        assertEquals(2, w.count())
        w.pass()
        assertEquals(0, w.count())
        // A failure between passes restarts the run of passes.
        w.fail(); w.pass(); w.pass(); w.fail(); w.pass(); w.pass()
        assertEquals(2, w.count())
    }

    @Test fun passiveSkipsAreAllowedOnlyWithNoStrikeInTheWindow() {
        val w = window()
        assertTrue(w.maySkip())
        w.fail()
        w.pass()
        assertFalse("a strike inside the window forces real checks", w.maySkip())
        now += StrikeWindow.WINDOW_MS
        assertTrue(w.maySkip())
    }

    @Test fun resetStartsOver() {
        val w = window()
        w.fail(); w.fail(); w.fail()
        w.reset()
        assertEquals(0, w.count())
        assertEquals(0, w.consecutive())
        assertEquals("0/3 in 3 min", w.describe())
    }
}
