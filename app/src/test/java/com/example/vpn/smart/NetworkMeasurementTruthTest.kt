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
