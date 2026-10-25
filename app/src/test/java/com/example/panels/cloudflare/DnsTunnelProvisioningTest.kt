package com.example.panels.cloudflare

import com.example.panels.servers.DnsDelegation
import org.junit.Assert.*
import org.junit.Test

class DnsTunnelProvisioningTest {
    private val zones = listOf(
        SmartDomains.Zone("z1", "example.com", "active"),
        SmartDomains.Zone("z2", "example.net", "active")
    )

    @Test fun createsDnsOnlyNameServersAndDelegationsAcrossTwoZones() {
        val required = DnsDelegation.records("example.com", "t", "203.0.113.20", listOf("example.net"))
        val targets = DnsTunnelProvisioning.targets(required, zones)
        assertEquals(4, targets.size)
        assertEquals(listOf("z1", "z1", "z2", "z2"), targets.map { it.zone.id })
        assertEquals(listOf("A", "NS", "A", "NS"), targets.map { it.record.type })
        assertTrue(targets.all { !it.record.proxied })
        assertEquals("ns.example.net", targets.last().record.content)
        assertTrue(targets.all { DnsTunnelProvisioning.decide(it.record, emptyList()) is SmartDomains.Action.Create })
    }

    @Test fun refusesUnknownInactiveZonesAndInvalidServerAddress() {
        val required = DnsDelegation.records("example.com", "t", "203.0.113.20", listOf("example.net"))
        assertThrows(IllegalStateException::class.java) {
            DnsTunnelProvisioning.targets(required, zones.take(1))
        }
        assertThrows(IllegalStateException::class.java) {
            DnsTunnelProvisioning.targets(required, zones.map { it.copy(status = "pending") })
        }
        assertThrows(IllegalStateException::class.java) {
            DnsTunnelProvisioning.targets(DnsDelegation.records("example.com", "t", "not-an-ip"), zones)
        }
    }

    @Test fun neverChangesExistingRecordsOrCoexistsWithInvalidNsTypes() {
        val ns = SmartDomains.Record(null, "t.example.com", "NS", "ns.example.com", false)
        val conflicting = SmartDomains.Record("some-id", "t.example.com", "A", "203.0.113.20", false)
        assertTrue(DnsTunnelProvisioning.decide(ns, listOf(conflicting)) is SmartDomains.Action.Conflict)
        val proxiedNameServer = SmartDomains.Record("a1", "ns.example.com", "A", "203.0.113.20", true)
        val desiredNameServer = proxiedNameServer.copy(id = null, proxied = false)
        assertTrue(DnsTunnelProvisioning.decide(desiredNameServer, listOf(proxiedNameServer)) is SmartDomains.Action.Conflict)
        assertTrue(DnsTunnelProvisioning.decide(ns, listOf(ns.copy(id = "existing"))) is SmartDomains.Action.Unchanged)
    }

    @Test fun aAndAaaaCanShareNameserverLabelButNotNsDelegation() {
        val a = SmartDomains.Record(null, "ns.example.com", "A", "203.0.113.20", false)
        val aaaa = SmartDomains.Record("v6", "ns.example.com", "AAAA", "2001:db8::20", false)
        assertTrue(DnsTunnelProvisioning.decide(a, listOf(aaaa)) is SmartDomains.Action.Create)
        assertTrue(DnsTunnelProvisioning.decide(a, listOf(aaaa, a.copy(id = "v4"))) is SmartDomains.Action.Unchanged)
    }
}
