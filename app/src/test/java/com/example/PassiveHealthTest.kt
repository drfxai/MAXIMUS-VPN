package com.example

import com.example.vpn.diagnostics.ConnectionMetrics
import com.example.vpn.smart.PassiveHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Passive evidence only extends a recently verified connection; it never creates one. */
class PassiveHealthTest {
    private var rx = 0L
    private var now = 1_000_000L
    private fun health() = PassiveHealth(rxBytes = { rx }, clock = { now })

    @Test fun neverSkipsWithoutARecentActiveSuccess() {
        val h = health()
        h.maySkipActiveCheck()
        rx += 10 * PassiveHealth.MIN_BYTES
        assertFalse("busy but never verified", h.maySkipActiveCheck())
    }

    @Test fun skipsWhileDataFlowsThroughARecentlyVerifiedTunnel() {
        val h = health()
        h.recordActiveSuccess()
        h.maySkipActiveCheck()
        rx += PassiveHealth.MIN_BYTES
        now += 12_000
        assertTrue(h.maySkipActiveCheck())
    }

    @Test fun anIdleTunnelIsCheckedActively() {
        val h = health()
        h.recordActiveSuccess()
        h.maySkipActiveCheck()
        rx += PassiveHealth.MIN_BYTES - 1
        assertFalse(h.maySkipActiveCheck())
    }

    @Test fun passiveTrustExpires() {
        val h = health()
        h.recordActiveSuccess()
        h.maySkipActiveCheck()
        rx += PassiveHealth.MIN_BYTES
        now += PassiveHealth.MAX_TRUST_MS
        assertFalse("an active check is due again", h.maySkipActiveCheck())
    }

    @Test fun anUnsupportedCounterAlwaysChecks() {
        val h = PassiveHealth(rxBytes = { -1L }, clock = { now })
        h.recordActiveSuccess()
        h.maySkipActiveCheck()
        assertFalse(h.maySkipActiveCheck())
    }

    @Test fun metricsKeepTheLastTenConnectTimes() {
        repeat(12) { ConnectionMetrics.recordConnectTime(1000L + it) }
        val times = ConnectionMetrics.connectTimes()
        assertEquals(10, times.size)
        assertEquals(1011L, times.last())
        assertTrue(ConnectionMetrics.summary().any { it.startsWith("Time to verified traffic") && it.contains("median") })
    }
}
