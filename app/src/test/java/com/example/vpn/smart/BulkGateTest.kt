package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkGateTest {
    @Test fun aFullOrLongEnoughReadPasses() {
        assertEquals(BulkGate.Verdict.PASS, BulkGate.verdict(BulkGate.Read.Done(BulkGate.DOWNLOAD_BYTES, 900)))
        assertEquals(BulkGate.Verdict.PASS, BulkGate.verdict(BulkGate.Read.Cut(40_000, idleMs = 100, timedOut = true, elapsedMs = 5_500)))
    }

    @Test fun aReadThatStopsInTheFreezeWindowAndGoesQuietIsFrozen() {
        assertEquals(BulkGate.Verdict.FROZEN, BulkGate.verdict(BulkGate.Read.Cut(16_050, idleMs = 4_800, timedOut = true, elapsedMs = 5_500)))
        assertEquals(BulkGate.Verdict.FROZEN, BulkGate.verdict(BulkGate.Read.Cut(16_384, idleMs = 2_000, timedOut = true, elapsedMs = 5_500)))
    }

    @Test fun aSlowReadStillMovingOrAnUnreachedReferenceSaysNothing() {
        // 16 KB arrived but bytes were still coming at the deadline: slow, not frozen.
        assertEquals(BulkGate.Verdict.INCONCLUSIVE, BulkGate.verdict(BulkGate.Read.Cut(16_050, idleMs = 300, timedOut = true, elapsedMs = 5_500)))
        // Outside the window.
        assertEquals(BulkGate.Verdict.INCONCLUSIVE, BulkGate.verdict(BulkGate.Read.Cut(4_000, idleMs = 5_000, timedOut = true, elapsedMs = 5_500)))
        // Closed by the server, not a freeze.
        assertEquals(BulkGate.Verdict.INCONCLUSIVE, BulkGate.verdict(BulkGate.Read.Cut(16_050, idleMs = 4_000, timedOut = false, elapsedMs = 4_100)))
        assertEquals(BulkGate.Verdict.INCONCLUSIVE, BulkGate.verdict(BulkGate.Read.NotTested("HTTP 503")))
        assertEquals(BulkGate.Verdict.INCONCLUSIVE, BulkGate.verdict(BulkGate.Read.Done(1_000, 300)))
    }

    @Test fun itRunsOnlyForSmartPicksOnMeteredOrFrozenNetworks() {
        fun applies(smart: Boolean = true, tun: Boolean = true, metered: Boolean = false, history: Boolean = false,
                    good: Boolean = false, gated: Int = 0) = BulkGate.applies(smart, tun, metered, history, good, gated)
        assertFalse(applies())
        assertTrue(applies(metered = true))
        assertTrue(applies(history = true))
        assertFalse(applies(metered = true, smart = false))
        assertFalse(applies(metered = true, tun = false))
        assertFalse(applies(metered = true, good = true))
        assertTrue(applies(metered = true, gated = BulkGate.MAX_CANDIDATES - 1))
        assertFalse(applies(metered = true, gated = BulkGate.MAX_CANDIDATES))
    }

    @Test fun markersCountGatedCandidatesAndBoundTheExtraTime() {
        val exclude = setOf("x", BulkGate.marker(1), BulkGate.marker(2))
        assertEquals(2, BulkGate.gatedSoFar(exclude))
        assertEquals(0, BulkGate.gatedSoFar(setOf("__aether-last__")))
        assertTrue(BulkGate.BUDGET_MS <= 6_000L)
        assertTrue(BulkGate.STEP_MS < BulkGate.BUDGET_MS)
        assertTrue(BulkGate.DOWNLOAD_BYTES >= BulkGate.MIN_BYTES && BulkGate.MIN_BYTES >= 32 * 1024)
    }
}
