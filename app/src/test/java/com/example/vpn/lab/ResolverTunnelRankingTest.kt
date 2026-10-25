package com.example.vpn.lab

import org.junit.Assert.*
import org.junit.Test

class ResolverTunnelRankingTest {
    private fun resolver(
        address: String,
        network: String = "wifi-a",
        ping: Long = 20,
        txt: Boolean? = true,
        udp: Boolean? = true,
        tcp: Boolean? = true,
        manipulated: Boolean? = false,
        measuredAt: Long = 100_000L,
        expiresAt: Long = 120_000L
    ) = ResolverIntelligence.Result(
        label = address, address = address, origin = "manual", networkKey = network,
        udp = udp, tcp = tcp, rttMs = ping, blockPage = manipulated, nxdomainHijack = false,
        ednsSize = if (txt == true) 1232 else null, measuredAt = measuredAt,
        expiresAt = expiresAt, txt = txt
    )

    @Test fun goodTunnelEvidenceBeatsLowLatencyAOnlyResolver() {
        val aOnly = resolver("1.1.1.1", ping = 5, txt = false)
        val tunnel = resolver("8.8.8.8", ping = 110)
        assertEquals("8.8.8.8", ResolverIntelligence.rankForDnsTunnel(listOf(aOnly, tunnel), "wifi-a", 110_000).first().address)
    }

    @Test fun falsePositiveAndManipulatedAnswersNeverBeatUsablePath() {
        val poisoned = resolver("1.1.1.1", ping = 1, manipulated = true)
        val usable = resolver("8.8.8.8", ping = 300, udp = false, tcp = true)
        val sorted = ResolverIntelligence.rankForDnsTunnel(listOf(poisoned, usable), "wifi-a", 110_000)
        assertEquals("8.8.8.8", sorted.first().address)
        assertFalse(sorted.last().usable)
    }

    @Test fun staleAndOtherNetworkEvidenceExcluded() {
        val valid = resolver("1.1.1.1")
        val other = resolver("8.8.8.8", network = "mobile-b")
        val expired = resolver("9.9.9.9", expiresAt = 105_000)
        val future = resolver("4.4.4.4", measuredAt = 115_000)
        assertEquals(listOf("1.1.1.1"), ResolverIntelligence.rankForDnsTunnel(
            listOf(other, expired, valid, future), "wifi-a", 110_000
        ).map { it.address })
    }
}
