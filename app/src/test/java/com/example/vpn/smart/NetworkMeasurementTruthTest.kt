package com.example.vpn.smart

import com.example.vpn.smart.NetworkCapabilityDetector.DnsOutcome
import com.example.vpn.smart.NetworkCapabilityDetector.TlsOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class NetworkMeasurementTruthTest {
    private fun ip(vararg b: Int) = InetAddress.getByAddress(b.map { it.toByte() }.toByteArray())

    @Test fun blockPageAnswerIsTamperedNotWorking() {
        assertEquals(DnsOutcome.TAMPERED, NetworkCapabilityDetector.classifyDns(listOf(ip(10, 10, 34, 35))))
        assertEquals(DnsOutcome.ANSWER, NetworkCapabilityDetector.classifyDns(listOf(ip(142, 250, 185, 4))))
        assertEquals(DnsOutcome.ANSWER, NetworkCapabilityDetector.classifyDns(listOf(ip(10, 10, 34, 35), ip(142, 250, 185, 4))))
        assertEquals(DnsOutcome.FAILED, NetworkCapabilityDetector.classifyDns(emptyList()))
    }

    @Test fun certificateComplaintMeansTheServerAnswered() {
        assertEquals(TlsOutcome.SERVER_ANSWERED, NetworkCapabilityDetector.tlsFailure(SSLHandshakeException("x").apply { initCause(CertificateException("name mismatch")) }))
        assertEquals(TlsOutcome.INTERFERED, NetworkCapabilityDetector.tlsFailure(java.net.SocketException("Connection reset")))
        assertEquals(TlsOutcome.INTERFERED, NetworkCapabilityDetector.tlsFailure(java.net.SocketTimeoutException("Read timed out")))
    }

    @Test fun sniFilteringNeedsAPassingNeutralName() {
        assertEquals(true, NetworkCapabilityDetector.sniFiltered(TlsOutcome.COMPLETED, TlsOutcome.INTERFERED))
        assertEquals(false, NetworkCapabilityDetector.sniFiltered(TlsOutcome.COMPLETED, TlsOutcome.SERVER_ANSWERED))
        assertNull(NetworkCapabilityDetector.sniFiltered(TlsOutcome.INTERFERED, TlsOutcome.INTERFERED))
        assertNull(NetworkCapabilityDetector.sniFiltered(TlsOutcome.COMPLETED, TlsOutcome.NOT_RUN))
    }

    @Test fun newMeasurementsSurviveStorage() {
        val p = NetworkCapabilityProfile(dnsManipulated = true, dohReachable = false, internationalOk = 1, internationalTried = 2, domesticReachable = true, sniFiltered = true)
        val back = NetworkCapabilityProfile.fromJson(p.toJson())
        assertEquals(p, back)
        assertEquals(true, back.internationalReachable)
    }
}

class QuicProbeTest {
    private val scid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val dcid = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)

    @org.junit.Test fun probeIsAPaddedLongHeaderWithAReservedVersion() {
        val p = NetworkCapabilityDetector.quicVersionProbe(dcid, scid)
        org.junit.Assert.assertEquals(1200, p.size)
        org.junit.Assert.assertEquals(0xC0.toByte(), p[0])
        org.junit.Assert.assertArrayEquals(byteArrayOf(0x1a, 0x2a, 0x3a, 0x4a), p.copyOfRange(1, 5))
        org.junit.Assert.assertEquals(8.toByte(), p[5])
    }

    @org.junit.Test fun onlyAVersionNegotiationEchoingOurIdCounts() {
        val vn = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 8) + scid + byteArrayOf(8) + dcid + byteArrayOf(0, 0, 0, 1)
        org.junit.Assert.assertTrue(NetworkCapabilityDetector.isVersionNegotiation(vn, vn.size, scid))
        val wrongId = vn.copyOf().also { it[6] = 42 }
        org.junit.Assert.assertFalse(NetworkCapabilityDetector.isVersionNegotiation(wrongId, wrongId.size, scid))
        val notVn = vn.copyOf().also { it[4] = 1 }
        org.junit.Assert.assertFalse(NetworkCapabilityDetector.isVersionNegotiation(notVn, notVn.size, scid))
        org.junit.Assert.assertFalse(NetworkCapabilityDetector.isVersionNegotiation(vn, 5, scid))
    }
}
