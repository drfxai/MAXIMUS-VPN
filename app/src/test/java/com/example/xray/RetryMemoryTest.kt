package com.example.xray

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 150: each Smart Connect round paid both targets' timeouts (~12 s per five servers) while nothing passed. */
class RetryMemoryTest {
    private var now = 0L
    private val memory = RealDelayProbe.RetryMemory(clock = { now })

    @Test fun aRetryThatRescuedNobodyIsSkippedForAMinute() {
        memory.record(retried = 5, rescued = 0)
        assertTrue(memory.skipRetry())
        now += RealDelayProbe.RetryMemory.HOLD_MS
        assertFalse(memory.skipRetry())
    }

    @Test fun oneRetriedCandidateIsNotEnoughEvidence() {
        memory.record(retried = 1, rescued = 0)
        assertFalse(memory.skipRetry())
    }

    @Test fun aRescueEndsTheHoldAtOnce() {
        memory.record(retried = 5, rescued = 0)
        memory.record(retried = 3, rescued = 1)
        assertFalse(memory.skipRetry())
    }

    @Test fun theProbeLeavesItOffByDefault() {
        assertFalse(RealDelayProbe.skipUselessRetry)
    }
}
