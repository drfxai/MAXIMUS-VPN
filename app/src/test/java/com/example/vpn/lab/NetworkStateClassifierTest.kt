package com.example.vpn.lab

import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.smart.NetworkCapabilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStateClassifierTest {
    private val open = NetworkCapabilityProfile(
        transport = "cellular", ipv4Available = true, udpAvailable = true, tcpAvailable = true, tlsAvailable = true,
        cloudflareReachable = true, dnsWorking = true, dnsManipulated = false, dohReachable = true,
        internationalOk = 2, internationalTried = 2, domesticReachable = true, sniFiltered = false
    )
    private fun state(p: NetworkCapabilityProfile?) = NetworkStateClassifier.classify(p, now = 1L)

    @Test fun nothingMeasuredIsUnknownNotFailed() {
        assertEquals(NetworkState.UNKNOWN, state(null).primary)
        assertEquals(NetworkState.UNKNOWN, state(NetworkCapabilityProfile()).primary)
        assertEquals(0.0, state(NetworkCapabilityProfile()).confidence, 0.0)
    }

    @Test fun openNetworkIsNormal() {
        val r = state(open)
        assertEquals(NetworkState.NORMAL, r.primary)
        assertTrue(r.restrictions.isEmpty())
        assertTrue(r.notTested.contains("QUIC"))
    }

    @Test fun tamperedDnsIsReportedEvenWhenTheRestWorks() {
        val r = state(open.copy(dnsWorking = false, dnsManipulated = true, cloudflareReachable = false))
        assertEquals(NetworkState.DNS_MANIPULATED, r.primary)
        // The CDN reference is reached by name; tampered DNS explains it, so no CDN claim.
        assertFalse(NetworkState.CDN_PATH_DEGRADED in r.restrictions)
    }

    @Test fun severalRestrictionsAreFiltered() {
        val r = state(open.copy(dnsManipulated = true, sniFiltered = true, udpAvailable = false))
        assertEquals(NetworkState.FILTERED, r.primary)
        assertEquals(setOf(NetworkState.DNS_MANIPULATED, NetworkState.SNI_INTERFERENCE_SUSPECTED, NetworkState.UDP_BLOCKED), r.restrictions)
        assertTrue(r.summary().startsWith("Filtered (DNS tampered, SNI interference suspected, UDP blocked)"))
    }

    @Test fun partialInternationalReach() {
        assertEquals(NetworkState.PARTIAL_INTERNATIONAL_CONNECTIVITY, state(open.copy(internationalOk = 1)).primary)
        // Multi-vantage quorum: one of four answering is partial reach, not isolation.
        assertEquals(NetworkState.PARTIAL_INTERNATIONAL_CONNECTIVITY, state(open.copy(internationalOk = 1, internationalTried = 4)).primary)
    }

    @Test fun quicStatesAreHonest() {
        assertEquals(NetworkState.QUIC_BLOCKED, state(open.copy(quicStatus = "QUIC_BLOCKED_SUSPECTED")).primary)
        assertEquals(NetworkState.QUIC_DEGRADED, state(open.copy(quicStatus = "QUIC_DEGRADED")).primary)
        // An unresponsive QUIC endpoint is not a restriction.
        assertEquals(NetworkState.NORMAL, state(open.copy(quicStatus = "QUIC_UNRESPONSIVE")).primary)
        assertFalse(state(open.copy(quicStatus = "QUIC_AVAILABLE")).notTested.contains("QUIC"))
    }

    @Test fun ipv6FailingAloneIsIpv6Degraded() {
        assertEquals(NetworkState.IPV6_DEGRADED, state(open.copy(ipv6Available = true, ipv6TlsOk = false)).primary)
        // IPv4 cut while IPv6 works.
        assertEquals(NetworkState.IPV4_DEGRADED, state(open.copy(internationalOk = 0, ipv6Available = true, ipv6TlsOk = true)).primary)
    }

    @Test fun tcpPassingWithTlsCutIsTlsInterference() {
        assertEquals(NetworkState.TLS_INTERFERED, state(open.copy(internationalOk = 0, tlsAvailable = false)).primary)
    }

    @Test fun nationalNetworkStatesNeedEvidence() {
        val nin = open.copy(internationalOk = 0, tcpAvailable = false, tlsAvailable = false, cloudflareReachable = false)
        // A direct UDP answer from a foreign resolver (and DoH) is not proof of DNS egress.
        assertEquals(NetworkState.DOMESTIC_ONLY_NO_VERIFIED_EGRESS, state(nin).primary)
        assertTrue(state(nin).notTested.contains("Recursive DNS egress (nonce)"))
        assertNotEquals(NetworkState.NIN_WITH_DNS_EGRESS, state(nin.copy(udpAvailable = true, dohReachable = true, dotReachable = true)).primary)
        // Only a measured recursive egress makes it a national network with DNS egress.
        assertEquals(NetworkState.NIN_WITH_DNS_EGRESS, state(nin.copy(recursiveDnsEgress = true)).primary)
        // Nothing answers abroad or at home, but DNS still does: filtering, never isolation.
        assertEquals(NetworkState.SEVERE_FILTERING, state(nin.copy(domesticReachable = false, udpAvailable = false, dohReachable = false)).primary)
        // No sign of life at all: still only "no verified egress"; isolation needs the recovery families to fail too.
        val dead = nin.copy(domesticReachable = false, udpAvailable = false, dohReachable = false, dnsWorking = false)
        assertEquals(NetworkState.NO_VERIFIED_EGRESS, state(dead).primary)
        assertNotEquals(NetworkState.TRUE_PHYSICAL_ISOLATION, state(dead).primary)
    }

    @Test fun carrierNameNeverChangesTheState() {
        assertEquals(state(open).primary, state(open.copy(carrierCode = "43235")).primary)
    }

    @Test fun trackerNeedsTwoConsistentReadingsToChange() {
        val t = NetworkStateTracker()
        val normal = state(open)
        val filtered = state(open.copy(dnsManipulated = true, sniFiltered = true))
        assertEquals(NetworkState.NORMAL, t.update("NS-1", normal).primary)
        assertEquals(NetworkState.NORMAL, t.update("NS-1", filtered).primary)
        assertEquals(NetworkState.FILTERED, t.update("NS-1", filtered).primary)
        // A new network session starts fresh.
        assertEquals(NetworkState.NORMAL, t.update("NS-2", normal).primary)
        // A confident reading changes it at once.
        assertEquals(NetworkState.NO_VERIFIED_EGRESS, t.update("NS-2", normal.copy(primary = NetworkState.NO_VERIFIED_EGRESS, confidence = 0.95)).primary)
    }

    @Test fun failuresAreRefinedByTheNetworkState() {
        assertEquals(LabFailureCategory.DNS_TAMPERED, FailureClassifier.classify(FailureStage.DNS_RESOLUTION_FAILED, open.copy(dnsManipulated = true)))
        assertEquals(LabFailureCategory.SNI_INTERFERENCE_SUSPECTED, FailureClassifier.classify(FailureStage.TLS_HANDSHAKE_FAILED, open.copy(sniFiltered = true)))
        assertEquals(LabFailureCategory.NO_INTERNATIONAL_EGRESS, FailureClassifier.classify(FailureStage.TIMEOUT, open.copy(internationalOk = 0)))
        assertEquals(LabFailureCategory.DNS_THROUGH_TUNNEL_FAILED, FailureClassifier.classify(FailureStage.DNS_TUNNEL_FAILED))
        // Nothing a config tweak can fix gets recovery candidates.
        assertFalse(FailureClassifier.allowsCandidates(LabFailureCategory.NO_INTERNATIONAL_EGRESS))
        assertFalse(FailureClassifier.allowsCandidates(LabFailureCategory.AUTHENTICATION_FAILED))
        assertTrue(FailureClassifier.allowsCandidates(LabFailureCategory.SNI_INTERFERENCE_SUSPECTED))
        LabFailureCategory.entries.forEach { FailureClassifier.assess(it) }
    }
}
