package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPortPlannerTest {
    private val time = 1_800_000_000_000L
    private val facts = ServerFacts(
        osId = "ubuntu", systemd = true, socketInspectionAvailable = true,
        tcpPorts = setOf(22, 443), udpPorts = setOf(53)
    )

    @Test fun refusesUnknownOrStaleInventory() {
        val listener = SmartPortPlanner.Binding("dnstt", SmartPortPlanner.Protocol.UDP, 53)
        assertFalse(SmartPortPlanner.propose(null, time, listOf(listener), time).eligible)
        assertFalse(SmartPortPlanner.propose(facts, time - 120_000, listOf(listener), time).eligible)
        assertFalse(SmartPortPlanner.propose(facts.copy(socketInspectionAvailable = false), time,
            listOf(listener), time).eligible)
    }

    @Test fun blocksOccupiedPortsAndPreservesSsh() {
        assertFalse(SmartPortPlanner.propose(facts, time,
            listOf(SmartPortPlanner.Binding("psiphon", SmartPortPlanner.Protocol.TCP, 443)), time).eligible)
        assertFalse(SmartPortPlanner.propose(facts.copy(tcpPorts = emptySet()), time,
            listOf(SmartPortPlanner.Binding("other", SmartPortPlanner.Protocol.TCP, 22)), time).eligible)
    }

    @Test fun differentProtocolSamePortIsNotAlwaysConflict() {
        val candidates = listOf(
            SmartPortPlanner.Binding("a", SmartPortPlanner.Protocol.TCP, 4444),
            SmartPortPlanner.Binding("b", SmartPortPlanner.Protocol.UDP, 4444))
        assertTrue(SmartPortPlanner.propose(facts, time, candidates, time).eligible)
        assertFalse(SmartPortPlanner.overlaps(candidates[0], candidates[1]))
    }

    @Test fun wildcardAndSameInterfaceCollideButDistinctAddressesMayCoexistInDraft() {
        val a = SmartPortPlanner.Binding("a", SmartPortPlanner.Protocol.TCP, 5353, "192.0.2.1")
        val b = SmartPortPlanner.Binding("b", SmartPortPlanner.Protocol.TCP, 5353, "192.0.2.2")
        val wildcard = SmartPortPlanner.Binding("c", SmartPortPlanner.Protocol.TCP, 5353)
        assertFalse(SmartPortPlanner.overlaps(a, b))
        assertTrue(SmartPortPlanner.overlaps(a, wildcard))
        assertFalse(SmartPortPlanner.propose(facts.copy(tcpPorts = emptySet()), time, listOf(a, wildcard), time).eligible)
    }

    @Test fun dnsEngineBundleCannotSharePublicUdp53() {
        val requests = SmartPortPlanner.knownPublicBindings(ServerToolCatalog.SOAR) +
            SmartPortPlanner.knownPublicBindings(ServerToolCatalog.DNSTT)
        assertEquals(2, requests.size)
        assertFalse(SmartPortPlanner.propose(facts.copy(udpPorts = emptySet()), time, requests, time).eligible)
    }
}
