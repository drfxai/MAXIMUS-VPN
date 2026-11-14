package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartManagerStateTest {
    private val base = ManagedServer(id = "vps", name = "VPS", host = "198.51.100.10",
        location = ServerLocation.ABROAD, hostKeySha256 = "SHA256:abc", password = "pw",
        checkedAt = 1_000L)
    private val facts = ServerFacts(osId = "ubuntu", socketInspectionAvailable = true)

    @Test fun inspectionDoesNotDropToolInstalledWhileItRan() {
        val snapshot = base
        val checked = snapshot.copy(facts = facts, checkedAt = 5_000L)
        val current = base.withTool(InstalledTool(ServerToolCatalog.XUI, profileId = "p1"))
        val merged = requireNotNull(SmartManagerState.mergeInspection(current, snapshot, checked))
        assertEquals(listOf(ServerToolCatalog.XUI), merged.tools.map { it.id })
        assertEquals(facts, merged.facts)
        assertEquals(5_000L, merged.checkedAt)
    }

    @Test fun inspectionNeverResurrectsRemovedOrRetargetedServer() {
        val checked = base.copy(facts = facts, checkedAt = 5_000L)
        assertNull(SmartManagerState.mergeInspection(null, base, checked))
        assertNull(SmartManagerState.mergeInspection(base.copy(host = "203.0.113.5"), base, checked))
        assertNull(SmartManagerState.mergeInspection(base.copy(hostKeySha256 = "SHA256:new"), base, checked))
    }

    @Test fun olderInspectionDoesNotOverwriteNewer() {
        val newer = base.copy(facts = facts, checkedAt = 9_000L)
        val late = base.copy(facts = ServerFacts(), checkedAt = 5_000L)
        assertEquals(newer, SmartManagerState.mergeInspection(newer, base, late))
    }

    private fun op(id: String, status: SmartOperation.Status, server: String = "vps") =
        SmartOperation(id, server, "dnstt", status, 1L, 1L, 1L)

    @Test fun blockingOperationStaysVisibleBeyondRecentWindow() {
        val all = listOf(op("old", SmartOperation.Status.UNCERTAIN)) +
            (1..12).map { op("c$it", SmartOperation.Status.COMPLETED) } +
            op("other", SmartOperation.Status.UNCERTAIN, server = "elsewhere")
        val shown = SmartManagerState.operationsFor(all, "vps")
        assertEquals(11, shown.size)
        assertEquals("c12", shown.first().id)
        assertTrue(shown.any { it.id == "old" })
        assertFalse(shown.any { it.id == "other" })
    }

    @Test fun dnsttNeedsBothUnitsActive() {
        val half = facts.copy(services = mapOf("maximus-dnstt" to "active", "maximus-socks" to "failed"))
        assertFalse(SmartManagerState.allUnitsActive(half, ServerToolCatalog.DNSTT))
        val both = facts.copy(services = mapOf("maximus-dnstt" to "active", "maximus-socks" to "active"))
        assertTrue(SmartManagerState.allUnitsActive(both, ServerToolCatalog.DNSTT))
        assertFalse(SmartManagerState.allUnitsActive(null, ServerToolCatalog.DNSTT))
    }

    @Test fun privatePsiphonHasLogsButSettingsToolsDoNot() {
        assertTrue(SmartManagerState.hasLogs(PrivatePsiphonServerScripts.TOOL_ID))
        assertTrue(SmartManagerState.hasLogs(ServerToolCatalog.HIDDIFY))
        assertFalse(SmartManagerState.hasLogs(ServerToolCatalog.SWAP))
        assertTrue(ToolScripts.logs(PrivatePsiphonServerScripts.TOOL_ID).toString().isNotEmpty())
    }

    @Test fun localResolverStubIsNotPublicPort53() {
        val ubuntu = facts.copy(udpPorts = setOf(53), tcpPorts = setOf(22, 53),
            publicUdpPorts = emptySet(), publicTcpPorts = setOf(22))
        assertEquals(false, SmartManagerState.publicPort53Occupied(ubuntu))
        assertEquals(true, SmartManagerState.publicPort53Occupied(ubuntu.copy(publicUdpPorts = setOf(53))))
        assertEquals(true, SmartManagerState.publicPort53Occupied(facts.copy(udpPorts = setOf(53))))
        assertNull(SmartManagerState.publicPort53Occupied(facts.copy(socketInspectionAvailable = false)))
        assertNull(SmartManagerState.publicPort53Occupied(null))
    }
}
