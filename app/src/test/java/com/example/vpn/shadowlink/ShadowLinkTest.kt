package com.example.vpn.shadowlink

import com.example.vpn.connectivity.NetworkFirewalls
import com.example.vpn.connectivity.TransportCapabilityEngine
import com.example.vpn.connectivity.TransportCapabilityEngine.Transport
import com.example.vpn.lab.NetworkState
import com.example.vpn.lab.NetworkStateClassifier
import com.example.vpn.lab.ServiceCheck
import com.example.vpn.smart.NetworkCapabilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowLinkTest {

    // --- captive portal classifier ---

    @Test fun empty204IsOpen() =
        assertEquals(CaptivePortalProbe.Portal.OPEN, CaptivePortalProbe.judge(204, null, 0))

    @Test fun a200WithAPageIsCaptive() =
        assertEquals(CaptivePortalProbe.Portal.CAPTIVE, CaptivePortalProbe.judge(200, null, 4096))

    @Test fun redirectIsCaptive() =
        assertEquals(CaptivePortalProbe.Portal.CAPTIVE, CaptivePortalProbe.judge(302, "http://login.hotel/", 0))

    @Test fun noAnswerIsClosed() =
        assertEquals(CaptivePortalProbe.Portal.CLOSED, CaptivePortalProbe.judge(null, null, null))

    @Test fun checkPrefersFirstDefinitiveVerdict() {
        val portal = CaptivePortalProbe.check { url ->
            if (url == CaptivePortalProbe.PROBE_URLS[0]) throw RuntimeException("down")
            CaptivePortalProbe.ProbeResponse(204, null, 0)
        }
        assertEquals(CaptivePortalProbe.Portal.OPEN, portal)
    }

    // --- egress fusion ---

    @Test fun captivePortalForcesBlockedRegardlessOfState() =
        assertEquals(Egress.BLOCKED,
            ShadowLink.egressOf(NetworkState.NORMAL, CaptivePortalProbe.Portal.CAPTIVE, true, false))

    @Test fun cleanNetworkIsOpen() =
        assertEquals(Egress.OPEN,
            ShadowLink.egressOf(NetworkState.NORMAL, CaptivePortalProbe.Portal.OPEN, true, false))

    @Test fun normalButSomethingBlockedIsFiltered() =
        assertEquals(Egress.FILTERED,
            ShadowLink.egressOf(NetworkState.NORMAL, CaptivePortalProbe.Portal.OPEN, true, true))

    @Test fun sniInterferenceIsFiltered() =
        assertEquals(Egress.FILTERED,
            ShadowLink.egressOf(NetworkState.SNI_INTERFERENCE_SUSPECTED, CaptivePortalProbe.Portal.OPEN, true, true))

    @Test fun ninWithDnsEgressIsTunnelOnly() =
        assertEquals(Egress.TUNNEL_ONLY,
            ShadowLink.egressOf(NetworkState.NIN_WITH_DNS_EGRESS, CaptivePortalProbe.Portal.OPEN, false, true))

    @Test fun noVerifiedEgressWithNothingWorkingIsBlocked() =
        assertEquals(Egress.BLOCKED,
            ShadowLink.egressOf(NetworkState.NO_VERIFIED_EGRESS, CaptivePortalProbe.Portal.OPEN, false, true))

    // --- transport recommendation ---

    private fun caps(udp: Boolean? = true, tcp: Boolean? = true, quic: Boolean? = true, udpImpaired: Boolean = false) =
        TransportCapabilityEngine.Capabilities(
            ipv4 = true, ipv6 = null, udp = udp, tcp = tcp, tls = true, http2 = true, http3 = quic,
            quic = quic, ech = null, dns = true, cloudflare = true, udpImpaired = udpImpaired, ipv4Impaired = false
        )

    @Test fun cleanNetworkPrefersUdpThenTls() {
        val hints = ShadowLink.recommendTransports(caps(), NetworkState.NORMAL, NetworkFirewalls.Firewall.OTHER)
        assertEquals(Transport.UDP, hints.first().transport)
        assertTrue(hints.any { it.transport == Transport.TCP_TLS })
    }

    @Test fun udpBlockedNetworkDoesNotRecommendUdp() {
        val hints = ShadowLink.recommendTransports(
            caps(udp = false, quic = false), NetworkState.UDP_BLOCKED_SUSPECTED, NetworkFirewalls.Firewall.OTHER)
        assertFalse(hints.any { it.transport == Transport.UDP })
        assertEquals(Transport.TCP_TLS, hints.first().transport)
    }

    @Test fun irancellTlsHintMentionsFragmentation() {
        val hints = ShadowLink.recommendTransports(caps(), NetworkState.FILTERED, NetworkFirewalls.Firewall.IRANCELL)
        val tls = hints.first { it.transport == Transport.TCP_TLS }
        assertTrue(tls.reason.contains("fragmentation"))
    }

    @Test fun tlsPathFailureAddsPlainTcpTunnelFallback() {
        val hints = ShadowLink.recommendTransports(
            caps(udp = false, quic = false), NetworkState.TLS_PATH_FAILURE, NetworkFirewalls.Firewall.OTHER)
        assertTrue(hints.any { it.transport == Transport.TCP_PLAIN })
    }

    // --- full synthesis ---

    @Test fun synthesizeProducesCoherentReport() {
        val profile = NetworkCapabilityProfile(
            transport = "cellular", ipv4Available = true, udpAvailable = true, tcpAvailable = true,
            tlsAvailable = true, quicAvailable = true, dnsManipulated = false,
            internationalOk = 3, internationalTried = 3
        )
        val reading = NetworkStateClassifier.classify(profile)
        val services = ServiceCheck.Report(
            results = listOf(
                ServiceCheck.Result(ServiceCheck.SERVICES[0], ServiceCheck.Verdict.WORKS, "ok"),
                ServiceCheck.Result(ServiceCheck.SERVICES[3], ServiceCheck.Verdict.FILTERED, "filter page")
            ),
            exit = ServiceCheck.Exit("1.2.3.4", "DE"), throughVpn = false, at = 0L
        )
        val report = ShadowLink.synthesize(
            networkKey = "cell:43235", networkLabel = "Irancell (cell:43235)", profile = profile,
            reading = reading, services = services, firewall = NetworkFirewalls.Firewall.IRANCELL,
            portal = CaptivePortalProbe.Portal.OPEN, priorWorkingKinds = listOf("reality"), now = 1_000L
        )
        assertEquals(false, report.dnsHonest == false) // dnsHonest should be true (not tampered)
        assertEquals(true, report.dnsHonest)
        assertTrue(report.workingServices.contains("Gemini"))
        assertTrue(report.blockedServices.contains("YouTube"))
        assertEquals("DE", report.exitCountry)
        assertEquals(listOf("reality"), report.priorWorkingKinds)
        assertTrue(report.recommendedTransports.isNotEmpty())
        assertTrue(report.summary.isNotBlank())
        // round-trips through JSON
        assertEquals("cell:43235", report.toJson().getString("key"))
    }
}
