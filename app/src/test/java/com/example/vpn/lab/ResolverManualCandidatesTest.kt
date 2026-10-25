package com.example.vpn.lab

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ResolverManualCandidatesTest {
    @Test fun manualIpv4AndIpv6WithPortsArePreservedWithoutRangeDiscovery() {
        val network = listOf(InetAddress.getByName("192.0.2.53"))
        val c = ResolverIntelligence.candidates(network, listOf("1.1.1.1:5353", "[2001:4860:4860::8888]:5354", "1.1.1.1:5353", "invalid-name.example"), max = 10)
        assertEquals("192.0.2.53", c.first().address)
        assertEquals(53, c.first().port)
        assertEquals(1, c.count { it.address == "1.1.1.1" && it.port == 5353 })
        assertEquals(1, c.count { it.port == 5354 && it.address.contains(':') })
        assertFalse(c.any { it.address == "invalid-name.example" })
        assertTrue(c.size <= 10)
    }

    @Test fun legacyPersistedResultsStillUseStandardDnsPort() {
        val now = 500_000L
        val before = ResolverIntelligence.Result("x", "1.1.1.1", "manual", "wifi",
            true, true, 15L, false, false, 1232, now, now + 100_000)
        assertEquals(53, before.port)
        val saved = before.copy(port = 5353)
        assertEquals(5353, ResolverIntelligence.Result.fromJson(saved.toJson())!!.port)
        assertEquals("wifi", ResolverIntelligence.Result.fromJson(before.toJson())!!.networkKey)
    }
}
