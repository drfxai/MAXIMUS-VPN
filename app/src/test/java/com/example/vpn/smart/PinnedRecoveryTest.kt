package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 173 report (Irancell cell:43235, SM-F711B, Android 15), 01:29:59–01:35:15 +0330: Psiphon started with the power
 * button stayed "up" while every real check failed with SOCKS reply 1, one failure window (three strikes) every ~27 s for
 * more than five minutes; the watchdog only logged "Staying on it" and never recovered.
 */
class PinnedRecoveryTest {
    private var now = 0L

    @Test fun theReportsDeadPinnedTunnelIsRestartedAfterTwoWindowsWithBackoffAndNeverLeftAlone() {
        val r = PinnedRecovery { now }
        val restartsAt = mutableListOf<Long>()
        // 5 min 16 s of dead windows, one every 27 s, as in the report.
        while (now <= 316_000L) {
            when (val d = r.onDeadWindow()) {
                is PinnedRecovery.Decision.Restart -> restartsAt += now
                is PinnedRecovery.Decision.Wait -> Unit
            }
            now += 27_000L
        }
        // First restart on the second window (~27 s after the first), never on the first one.
        assertEquals(27_000L, restartsAt.first())
        // Several restarts within the 5 minutes, each further apart than the last (backoff), none closer than 30 s.
        assertTrue("restarts at $restartsAt", restartsAt.size in 3..6)
        restartsAt.zipWithNext { a, b -> b - a }.let { gaps ->
            assertTrue("gaps $gaps", gaps.all { it >= PinnedRecovery.FIRST_BACKOFF_MS })
            assertTrue("gaps $gaps", gaps.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test fun aPassingCheckResetsWindowsAndBackoff() {
        val r = PinnedRecovery { now }
        assertTrue(r.onDeadWindow() is PinnedRecovery.Decision.Wait)
        assertTrue(r.onDeadWindow() is PinnedRecovery.Decision.Restart)
        assertEquals(1, r.restartsSoFar())
        now += 1_000
        r.onDeadWindow()
        val held = r.onDeadWindow()
        assertTrue("backoff holds the second restart", held is PinnedRecovery.Decision.Wait)
        assertNotNull((held as PinnedRecovery.Decision.Wait).nextRestartInMs)
        r.onPass()
        assertEquals(0, r.restartsSoFar())
        assertTrue(r.onDeadWindow() is PinnedRecovery.Decision.Wait)
        assertTrue("after a pass, two windows restart at once again", r.onDeadWindow() is PinnedRecovery.Decision.Restart)
    }

    @Test fun backoffDoublesFromThirtySecondsUpToFiveMinutes() {
        assertEquals(0L, PinnedRecovery.backoffMs(0))
        assertEquals(30_000L, PinnedRecovery.backoffMs(1))
        assertEquals(60_000L, PinnedRecovery.backoffMs(2))
        assertEquals(120_000L, PinnedRecovery.backoffMs(3))
        assertEquals(240_000L, PinnedRecovery.backoffMs(4))
        assertEquals(300_000L, PinnedRecovery.backoffMs(5))
        assertEquals(300_000L, PinnedRecovery.backoffMs(500))
    }

    @Test fun theRepeatedWarnLinesAreRateLimitedAndSayHowManyWereHeldBack() {
        val t = WarnThrottle(everyMs = 60_000L, burst = 3) { now }
        var logged = 0
        var lastNote = ""
        // The report: a failed-check WARN about every 9 s for 5 min 16 s (~35 lines).
        var lines = 0
        now = 0
        while (now <= 316_000L) {
            lines++
            t.admit()?.let { logged++; lastNote = it }
            now += 9_000L
        }
        assertTrue("$logged of $lines lines logged", logged <= 3 + 316_000 / 60_000 + 1)
        assertTrue(lastNote, lastNote.contains("held back"))
        t.reset()
        assertEquals("", t.admit())
        assertNull(WarnThrottle(everyMs = 60_000L, burst = 0) { now }.let { w -> w.admit(); w.admit() })
    }
}
