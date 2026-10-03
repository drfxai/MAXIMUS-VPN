package com.example

import com.example.vpn.diagnostics.DnsPathResult
import com.example.vpn.diagnostics.DnsProbePacket
import org.junit.Assert.*
import org.junit.Test

class DnsPathProbeTest {
    @Test fun onlyMatchingCompleteDnsAnswersCountAsPathEvidence() {
        val query = DnsProbePacket.query()
        val response = query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x83.toByte() }
        assertTrue(DnsProbePacket.matches(query, response))
        assertFalse(DnsProbePacket.matches(query, query))
        assertFalse(DnsProbePacket.matches(query, response.copyOf(11)))
        assertFalse(DnsProbePacket.matches(query, response.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }))
        assertFalse(DnsProbePacket.matches(query, response.copyOf().apply { this[2] = 0x83.toByte() }))
        assertFalse(DnsProbePacket.matches(query, response.copyOf().apply { this[3] = 2 }))
        assertFalse(DnsProbePacket.matches(query, response.copyOf().apply { this[13] = (this[13].toInt() xor 1).toByte() }))
    }

    @Test fun successfulLocalDnsNeverClaimsNoLeak() {
        assertNull(DnsPathResult(true, "Local response received").dnsLeakDetected)
        assertNull(DnsPathResult(false, "No response").dnsLeakDetected)
        assertFalse(DnsProbePacket.query().contentEquals(DnsProbePacket.query()))
    }
}
