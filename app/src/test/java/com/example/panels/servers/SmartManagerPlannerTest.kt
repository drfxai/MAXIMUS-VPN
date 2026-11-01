package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartManagerPlannerTest {
    private val now = 1_800_000_000_000L
    private fun host(
        facts: ServerFacts? = ServerFacts(
            osId = "ubuntu", arch = "x86_64", packageManager = "apt-get",
            root = true, systemd = true, socketInspectionAvailable = true, ramMb = 2048, diskFreeMb = 20_000
        ),
        tools: List<InstalledTool> = emptyList(),
        checkedAt: Long = now - 2_000
    ): ManagedServer = ManagedServer(
        name = "Test VPS", host = "198.51.100.10", location = ServerLocation.ABROAD,
        checkedAt = checkedAt, facts = facts, tools = tools
    )

    @Test fun locallyRecordedInstallationIsNotTreatedAsFreshWhenInspectionExpires() {
        val saved = listOf(InstalledTool(ServerToolCatalog.XUI))
        val stale = SmartManagerPlanner.inspect(
            host(tools = saved, checkedAt = now - INSPECTION_TTL_MS - 1L), now)
        assertEquals(SmartManagerPlanner.Status.NEEDS_INSPECTION,
            stale.candidate(ServerToolCatalog.XUI)?.status)
        val current = SmartManagerPlanner.inspect(host(tools = saved), now)
        assertEquals(SmartManagerPlanner.Status.INSTALLED,
            current.candidate(ServerToolCatalog.XUI)?.status)
    }

    @Test fun refusesUnknownOrStaleHostState() {
        val absent = SmartManagerPlanner.inspect(host(facts = null), now)
        assertEquals(SmartManagerPlanner.Status.NEEDS_INSPECTION,
            absent.candidate(ServerToolCatalog.XUI)?.status)
        val stale = SmartManagerPlanner.inspect(host(checkedAt = now - 120_000), now)
        assertEquals(SmartManagerPlanner.Status.NEEDS_INSPECTION,
            stale.candidate(ServerToolCatalog.SOAR)?.status)
    }

    @Test fun refusesPlanningWhenPortInventoryCouldNotBeCollected() {
        val facts = requireNotNull(host().facts).copy(socketInspectionAvailable = false)
        val plan = SmartManagerPlanner.inspect(host(facts = facts), now)
        assertEquals(SmartManagerPlanner.Status.BLOCKED,
            plan.candidate(ServerToolCatalog.DNSTT)?.status)
        assertTrue(SmartManagerPlanner.bundleBlockers(
            plan, setOf(ServerToolCatalog.DNSTT)).isNotEmpty())
    }

    @Test fun refusesPort53EvenWhenNoManagedToolWasPreviouslyInstalled() {
        val facts = requireNotNull(host().facts).copy(udpPorts = setOf(53))
        val plan = SmartManagerPlanner.inspect(host(facts = facts), now)
        listOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN).forEach {
            assertEquals(SmartManagerPlanner.Status.BLOCKED, plan.candidate(it)?.status)
        }
    }

    @Test fun preventsUnverifiedDnsMultiplexingAndHiddifyColocation() {
        val plan = SmartManagerPlanner.inspect(host(), now)
        assertTrue(SmartManagerPlanner.bundleBlockers(
            plan, setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR)).isNotEmpty())
        assertTrue(SmartManagerPlanner.bundleBlockers(
            plan, setOf(ServerToolCatalog.HIDDIFY, ServerToolCatalog.XUI)).isNotEmpty())
        assertTrue(SmartManagerPlanner.bundleBlockers(
            plan, setOf(ServerToolCatalog.DNSTT)).isEmpty())
    }

    @Test fun privatePsiphonIsNeverAdvertisedAsAvailableWithoutRealTunnelTests() {
        val plan = SmartManagerPlanner.inspect(host(), now)
        assertEquals(SmartManagerPlanner.Status.UNVERIFIED,
            plan.candidate(SmartManagerPlanner.PRIVATE_PSIPHON)?.status)
        assertFalse(SmartManagerPlanner.bundleBlockers(
            plan, setOf(SmartManagerPlanner.PRIVATE_PSIPHON)).isEmpty())
    }

    @Test fun inventoryReportsRetainedPsiphonKeysSeparatelyFromUnit() {
        val facts = ServerFacts.parse("---MX-FACTS---\nPSIPHON_INSTALLED=0\nPSIPHON_DATA_RETAINED=1\n")
        assertFalse(facts.psiphonInstalled)
        assertTrue(facts.psiphonDataRetained)
        val c = SmartManagerPlanner.inspect(host(facts = facts), now)
            .candidate(SmartManagerPlanner.PRIVATE_PSIPHON)
        assertTrue(c?.reason.orEmpty().contains("purge"))
    }

    @Test fun existingExternalHiddifyBlocksXuiAndDoesNotReportOwnership() {
        val facts = requireNotNull(host().facts).copy(hiddifyInstalled = true)
        val plan = SmartManagerPlanner.inspect(host(facts = facts), now)
        assertEquals(SmartManagerPlanner.Status.INSTALLED, plan.candidate(ServerToolCatalog.HIDDIFY)?.status)
        assertEquals(SmartManagerPlanner.Status.BLOCKED, plan.candidate(ServerToolCatalog.XUI)?.status)
    }
}
