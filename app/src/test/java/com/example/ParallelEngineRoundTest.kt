package com.example

import com.example.vpn.smart.ParallelEngineRound
import com.example.vpn.smart.ParallelEngineRound.Entry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ParallelEngineRoundTest {
    @Test fun concurrencyIsBoundedByBatteryAndMeteredData() {
        assertEquals(3, ParallelEngineRound.concurrency(metered = false, lowBattery = false))
        assertEquals(3, ParallelEngineRound.concurrency(metered = null, lowBattery = false))
        assertEquals(2, ParallelEngineRound.concurrency(metered = true, lowBattery = false))
        assertEquals(0, ParallelEngineRound.concurrency(metered = false, lowBattery = true))
    }

    @Test fun picksTheFirstOfEachEngineTypeWithoutTorOrExcluded() {
        val ordered = listOf(Entry("m1", "masque", false), Entry("m2", "masque", false), Entry("t1", "tor", true),
            Entry("p1", "psiphon", false), Entry("p2", "psiphon", false), Entry("x1", "matrix-transport", false),
            Entry("c1", "chain:psiphon+masque", false))
        assertEquals(listOf("m1", "p1", "x1"), ParallelEngineRound.pick(ordered, emptySet(), 3).map { it.id })
        assertEquals(listOf("m2", "p1"), ParallelEngineRound.pick(ordered, setOf("m1", "x1", "c1"), 3).map { it.id })
        assertEquals(listOf("m1", "p1"), ParallelEngineRound.pick(ordered, emptySet(), 2).map { it.id })
        // One engine alone is not worth a parallel round; a limit under two disables it.
        assertTrue(ParallelEngineRound.pick(ordered.take(3), emptySet(), 3).isEmpty())
        assertTrue(ParallelEngineRound.pick(ordered, emptySet(), 0).isEmpty())
    }

    @Test fun firstPassingResultWinsAndTheOthersAreInterrupted() {
        val interrupted = AtomicInteger()
        val slow = {
            try { Thread.sleep(10_000); "late" } catch (e: InterruptedException) { interrupted.incrementAndGet(); throw e }
        }
        val started = System.nanoTime()
        val outcome = ParallelEngineRound.race<String>(listOf(
            "fail" to { "fail" }, "slow" to slow,
            "pass" to { Thread.sleep(150); "pass" }), passed = { it == "pass" }, timeoutMs = 5_000)
        assertEquals("pass", outcome.winner?.first)
        assertEquals("fail", outcome.finished["fail"])
        assertFalse(outcome.finished.containsKey("slow"))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4_000)
        Thread.sleep(200)
        assertEquals(1, interrupted.get())
    }

    @Test fun timeoutStopsEverythingAndReportsNoWinner() {
        val gate = CountDownLatch(1)
        val outcome = ParallelEngineRound.race<String>(listOf(
            "a" to { gate.await(); "a" }, "b" to { "b-failed" }), passed = { it == "a" }, timeoutMs = 200)
        assertNull(outcome.winner)
        assertEquals(setOf("b"), outcome.finished.keys)
    }

    @Test fun aThrowingTaskIsNotAResult() {
        val outcome = ParallelEngineRound.race<String>(listOf(
            "boom" to { error("x") as String }, "ok" to { "ok" }), passed = { it == "ok" }, timeoutMs = 2_000)
        assertEquals("ok", outcome.winner?.first)
        assertFalse(outcome.finished.containsKey("boom"))
    }
}
