package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartBundlePlannerTest {
    private val time = 1_800_000_000_000L
    private val server = ManagedServer(
        name = "VPS", host = "198.51.100.20", location = ServerLocation.ABROAD,
        checkedAt = time, facts = ServerFacts(
            osId = "ubuntu", arch = "x86_64", packageManager = "apt-get",
            root = true, systemd = true, socketInspectionAvailable = true,
            ramMb = 2048, diskFreeMb = 20_000
        )
    )

    private fun inspect(s: ManagedServer = server) = SmartManagerPlanner.inspect(s, time)

    private fun review(
        inspection: SmartManagerPlanner.Inspection,
        selected: Set<String>,
        domain: String,
        tunnelLabel: String
    ) = SmartBundlePlanner.review(inspection, selected, domain, tunnelLabel, now = time)


    @Test fun eligiblePanelAndSingleDnsTunnelRunInDeterministicOrder() {
        val p = review(inspect(), setOf(ServerToolCatalog.XUI, ServerToolCatalog.DNSTT),
            "example.com", "t")
        assertTrue(p.blockers.toString(), p.eligible)
        assertEquals(listOf(ServerToolCatalog.DNSTT, ServerToolCatalog.XUI), p.order)
    }

    @Test fun rejectsMultipleDnsTunAndHiddifyAndMissingDomain() {
        val badDns = review(inspect(),
            setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR), "example.com", "t")
        assertFalse(badDns.eligible)
        assertTrue(badDns.blockers.isNotEmpty())
        val hiddify = review(inspect(),
            setOf(ServerToolCatalog.XUI, ServerToolCatalog.HIDDIFY), "example.com", "t")
        assertFalse(hiddify.eligible)
        val blank = review(inspect(),
            setOf(ServerToolCatalog.XUI, ServerToolCatalog.COTTEN), "", "t")
        assertFalse(blank.eligible)
        assertTrue(blank.blockers.any { it.contains("domain") })
    }

    @Test fun neverRunsWithStaleOrOccupiedPortInventory() {
        val stale = review(inspect(server.copy(checkedAt = time - INSPECTION_TTL_MS - 1)),
            setOf(ServerToolCatalog.XUI, ServerToolCatalog.DNSTT), "example.com", "t")
        assertFalse(stale.eligible)
        val occupied = review(
            inspect(server.copy(facts = server.facts?.copy(udpPorts = setOf(53)))),
            setOf(ServerToolCatalog.XUI, ServerToolCatalog.DNSTT), "example.com", "t"
        )
        assertFalse(occupied.eligible)
        assertTrue(occupied.blockers.isNotEmpty())
    }
}
