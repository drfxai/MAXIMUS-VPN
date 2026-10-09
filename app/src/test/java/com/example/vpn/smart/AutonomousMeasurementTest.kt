package com.example.vpn.smart

import com.example.vpn.smart.NetworkCapabilityDetector.QuicStatus
import com.example.vpn.smart.NetworkCapabilityDetector.TlsOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutonomousMeasurementTest {

    @Test fun quicIsJudgedFromSeveralEndpoints() {
        assertEquals(QuicStatus.QUIC_AVAILABLE, NetworkCapabilityDetector.quicStatus(listOf(true, true), udpWorked = true, tlsAbroadWorked = true))
        assertEquals(QuicStatus.QUIC_DEGRADED, NetworkCapabilityDetector.quicStatus(listOf(true, false), udpWorked = true, tlsAbroadWorked = true))
        // Two silent endpoints while UDP DNS and TLS abroad work: suspected, never "blocked" outright.
        assertEquals(QuicStatus.QUIC_BLOCKED_SUSPECTED, NetworkCapabilityDetector.quicStatus(listOf(false, false), udpWorked = true, tlsAbroadWorked = true))
        // One silent endpoint, or silence with UDP itself failing, is only unresponsive.
        assertEquals(QuicStatus.QUIC_UNRESPONSIVE, NetworkCapabilityDetector.quicStatus(listOf(false, null), udpWorked = true, tlsAbroadWorked = true))
        assertEquals(QuicStatus.QUIC_UNRESPONSIVE, NetworkCapabilityDetector.quicStatus(listOf(false, false), udpWorked = false, tlsAbroadWorked = true))
        assertEquals(QuicStatus.QUIC_NOT_MEASURED, NetworkCapabilityDetector.quicStatus(listOf(null, null), udpWorked = null, tlsAbroadWorked = false))
        assertNull(QuicStatus.QUIC_UNRESPONSIVE.available)
    }

    @Test fun sniInterferenceNeedsEveryComparisonOnSeveralAddresses() {
        val cut = TlsOutcome.COMPLETED to TlsOutcome.INTERFERED
        val passed = TlsOutcome.COMPLETED to TlsOutcome.SERVER_ANSWERED
        assertEquals(true, NetworkCapabilityDetector.sniEvidence(listOf(cut, cut)).suspected)
        // One differing pair is not enough.
        assertNull(NetworkCapabilityDetector.sniEvidence(listOf(cut)).suspected)
        assertNull(NetworkCapabilityDetector.sniEvidence(listOf(cut, passed)).suspected)
        assertEquals(false, NetworkCapabilityDetector.sniEvidence(listOf(passed, passed)).suspected)
        // A neutral name that failed makes the pair incomparable.
        assertNull(NetworkCapabilityDetector.sniEvidence(listOf(TlsOutcome.INTERFERED to TlsOutcome.INTERFERED)).suspected)
        assertEquals(NetworkCapabilityDetector.SniEvidence(2, 1), NetworkCapabilityDetector.sniEvidence(listOf(cut, passed)))
    }

    @Test fun recursiveEgressIsNeverInferred() {
        val p = NetworkCapabilityProfile(udpAvailable = true, dohReachable = true, dotReachable = true)
        assertNull(p.recursiveDnsEgress)
        val back = NetworkCapabilityProfile.fromJson(p.toJson())
        assertNull(back.recursiveDnsEgress)
        assertEquals(true, back.dotReachable)
    }
}
