package com.example.panels.servers

import org.junit.Assert.*
import org.junit.Test

class PublicDnsListenerPolicyTest {
    private val time = 1_800_000_000_000L
    private val dns = SmartPortPlanner.knownPublicBindings(ServerToolCatalog.DNSTT)
    private fun facts(
        public: Set<Int>?,
        all: Set<Int> = setOf(53)
    ): ServerFacts = ServerFacts(
        arch = "x86_64", root = true, systemd = true,
        socketInspectionAvailable = true,
        tcpPorts = emptySet(), udpPorts = all,
        publicUdpPorts = public,
        publicTcpPorts = emptySet()
    )

    @Test fun localSystemdResolvedDoesNotBlockPublicDns53() {
        val result = SmartPortPlanner.propose(facts(emptySet()), time, dns, now = time)
        assertTrue(result.conflicts.toString(), result.eligible)
    }

    @Test fun actualPublicDnsListenerStillBlocks() {
        val result = SmartPortPlanner.propose(facts(setOf(53)), time, dns, now = time)
        assertFalse(result.eligible)
        assertTrue(result.conflicts.any { "UDP 53" in it })
    }

    @Test fun legacyInventoryWithNoInterfaceEvidenceStillFailsClosed() {
        val result = SmartPortPlanner.propose(facts(null), time, dns, now = time)
        assertFalse(result.eligible)
    }

    @Test fun parserKeepsPublicAndLoopbackInventoriesSeparate() {
        val parsed = ServerFacts.parse("""
            ${ServerFacts.BEGIN}
            SOCKETS=1
            TCP=22,53,
            UDP=53,
            TCP_PUBLIC=22,
            UDP_PUBLIC=
        """.trimIndent())
        assertEquals(setOf(22, 53), parsed.tcpPorts)
        assertEquals(setOf(53), parsed.udpPorts)
        assertEquals(setOf(22), parsed.publicTcpPorts)
        assertEquals(emptySet<Int>(), parsed.publicUdpPorts)
    }
}
