package com.example.vpn.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLogAggregatorTest {
    @Test fun recoveryAfterMoreThanFiveHundredLinesIsRetained() {
        val emitted = mutableListOf<String>()
        val log = BridgeLogAggregator({ emitted.add(it) }, { 0L })
        repeat(1_000) { log.accept("refused [redacted]") }
        log.accept("connected")
        assertEquals(3, emitted.size)
        assertTrue(emitted[1].contains("999 additional"))
        assertEquals("connected", emitted.last())
    }

    @Test fun periodicSummaryAndFinalFlushAccountForEveryRepeat() {
        var now = 0L
        val emitted = mutableListOf<String>()
        val log = BridgeLogAggregator({ emitted.add(it) }, { now })
        log.accept("timeout")
        log.accept("timeout")
        now = 30_000
        log.accept("timeout")
        assertTrue(emitted.last().contains("2 additional"))
        log.accept("timeout")
        log.flush()
        assertTrue(emitted.last().contains("1 additional"))
        log.flush()
        assertEquals(3, emitted.size)
    }

    @Test fun differentEventsAreNeverSuppressed() {
        val emitted = mutableListOf<String>()
        val log = BridgeLogAggregator({ emitted.add(it) }, { 0L })
        log.accept("timeout")
        log.accept("connected")
        log.accept("timeout")
        assertEquals(listOf("timeout", "connected", "timeout"), emitted)
    }
}
