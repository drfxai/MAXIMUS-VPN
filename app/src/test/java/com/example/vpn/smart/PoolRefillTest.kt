package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Test

/** Build 145: "Skipped 28 servers that failed here moments ago" must never leave 0 servers to test. */
class PoolRefillTest {
    @Test fun enoughFreshServersSkipTheRecentFailures() {
        val failed = mapOf("a" to 10L)
        val r = PoolRefill.refill(listOf("a", "b", "c", "d", "e"), { failed[it] }, { false })
        assertEquals(listOf("b", "c", "d", "e"), r.candidates)
        assertEquals(1, r.skipped)
        assertEquals(0, r.readmitted)
    }

    @Test fun theOldestSkippedComeBackSoThePoolIsNotEmpty() {
        val ids = (1..30).map { "s$it" }
        // 28 failed moments ago (s1 oldest), 2 fresh.
        val failed = ids.take(28).withIndex().associate { (i, id) -> id to (100L + i) }
        val r = PoolRefill.refill(ids, { failed[it] }, { false })
        assertEquals(PoolRefill.MIN_CANDIDATES, r.candidates.size)
        assertEquals(listOf("s1", "s2", "s29", "s30"), r.candidates)
        assertEquals(2, r.readmitted)
    }

    @Test fun unfrozenServersAreReadmittedBeforeFrozenOnes() {
        val failed = mapOf("fr5" to 1L, "de1" to 50L, "nl2" to 60L, "at3" to 70L)
        val r = PoolRefill.refill(listOf("fr5", "de1", "nl2", "at3"), { failed[it] }, { it == "fr5" })
        assertEquals(listOf("de1", "nl2", "at3", "fr5"), r.candidates.sortedBy { listOf("de1", "nl2", "at3", "fr5").indexOf(it) })
        val r3 = PoolRefill.refill(listOf("fr5", "de1", "nl2", "at3"), { failed[it] }, { it == "fr5" }, min = 3)
        assertEquals(listOf("de1", "nl2", "at3"), r3.candidates)
    }

    @Test fun describeSaysWhatCameBack() {
        val r = PoolRefill.refill(listOf("a", "b"), { 1L }, { false })
        assertEquals("Skipped 0 servers that failed here moments ago; 2 of the oldest-skipped retested so the pool is not empty.",
            PoolRefill.describe(r))
    }
}
