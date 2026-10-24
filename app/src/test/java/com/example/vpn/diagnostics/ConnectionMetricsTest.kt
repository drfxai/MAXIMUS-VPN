package com.example.vpn.diagnostics
import org.junit.Assert.*
import org.junit.Test
class ConnectionMetricsTest {
    @Test fun evenSampleUsesBothMiddleValues() {
        assertEquals(363577L, ConnectionMetrics.medianMillis(listOf(500958, 226197)))
        assertEquals(3L, ConnectionMetrics.medianMillis(listOf(1, 3, 9)))
        assertNull(ConnectionMetrics.medianMillis(emptyList()))
        assertEquals(Long.MAX_VALUE, ConnectionMetrics.medianMillis(listOf(Long.MAX_VALUE, Long.MAX_VALUE)))
    }
}
