package com.example

import com.example.data.model.VlessProfile
import com.example.vpn.experimental.FrontedTunnel
import com.example.vpn.experimental.ReachabilityScanner
import org.junit.Assert.*
import org.junit.Test

class FrontedTunnelTest {
    private val base = VlessProfile(name = "origin", address = "origin.example", port = 443, uuid = "credential",
        transport = "ws", security = "tls", sni = "origin.example", host = "origin.example", path = "/tunnel")

    @Test fun frontingPreservesAuthenticationAndSeparatesTlsAndHttpIdentity() {
        val p = FrontedTunnel.build(base, "104.16.1.1", "FRONT.example.", "origin.example")
        assertEquals("104.16.1.1", p.address)
        assertEquals("front.example", p.sni)
        assertEquals(base.host, p.host)
        assertEquals(base.uuid, p.uuid)
        assertEquals(base.path, p.path)
        assertNotEquals(base.id, p.id)
        assertFalse(p.allowInsecure)
        assertFalse(p.desyncEnabled)
        assertNull(p.lastTestedTimestamp)
        assertEquals("origin.example", base.address)
    }

    @Test fun incompatibleProfilesAndInsecureModesAreRefused() {
        for (p in listOf(base.copy(transport = "tcp"), base.copy(security = "reality"),
            base.copy(allowInsecure = true), base.copy(verifyPeerCertByName = "other.example"))) {
            assertThrows(IllegalArgumentException::class.java) { FrontedTunnel.build(p, "1.1.1.1", "front.example", "origin.example") }
        }
    }

    @Test fun scanTargetsAreBoundedDeduplicatedAndNeverResolveHostNames() {
        assertEquals(1, ReachabilityScanner.targets("1.1.1.1:443\n1.1.1.1:443").size)
        for (raw in listOf("example.com", "127.0.0.1", "10.0.0.1", "1.1.1.1:0", "999.1.1.1",
            (1..33).joinToString("\n") { "1.1.1.1" })) {
            assertThrows(IllegalArgumentException::class.java) { ReachabilityScanner.targets(raw) }
        }
        assertEquals(853, ReachabilityScanner.targets("[2606:4700:4700::1111]:853").single().port)
    }
}
