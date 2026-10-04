package com.example

import com.example.data.model.VlessProfile
import com.example.vpn.protectTcpSocket
import com.example.vpn.smart.FailoverManager
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class SocketProtectionRegressionTest {
    private val active = VlessProfile(name = "active", address = "45.63.91.162", port = 38706,
        uuid = "00000000-0000-0000-0000-000000000001")

    @Test fun protectionReceivesBoundUnconnectedSocket() {
        Socket().use { socket ->
            assertTrue(protectTcpSocket(socket) { candidate ->
                assertSame(socket, candidate)
                assertTrue(candidate.isBound)
                assertFalse(candidate.isConnected)
                true
            })
            assertTrue(socket.isBound)
        }
    }

    @Test fun rejectionStaysARejection() {
        Socket().use { socket ->
            assertFalse(protectTcpSocket(socket) { false })
        }
    }

    @Test fun failoverExcludesDuplicateImportsAndSameEndpoint() {
        val choices = listOf(
            active,
            active.copy(id = "imported", name = "same config"),
            active.copy(id = "different-credentials", uuid = "00000000-0000-0000-0000-000000000002"),
            active.copy(id = "real-backup", address = "example.net")
        )
        assertEquals(listOf("real-backup"), FailoverManager.eligibleFallbacks(choices, active).map { it.id })
    }

    @Test fun failoverPrefersAKindOfConnectionThatHasNotFailed() {
        val reality = active.copy(id = "reality", address = "r.example.com", security = "reality",
            publicKey = "Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw", sni = "www.example.com")
        val cdn = active.copy(id = "cdn", address = "c.example.com", transport = "ws", security = "tls", sni = "c.example.com")
        val hy2 = active.copy(id = "hy2", address = "h.example.com", protocolType = com.example.data.model.ProtocolType.HYSTERIA2,
            transport = "hysteria", security = "tls", uuid = "pw")
        assertEquals("REALITY", FailoverManager.protocolFamily(reality))
        assertEquals("CDN", FailoverManager.protocolFamily(cdn))
        assertEquals("QUIC", FailoverManager.protocolFamily(hy2))
        val all = listOf(reality, cdn, hy2)
        assertEquals(listOf("cdn", "hy2"), FailoverManager.preferUntriedFamilies(all, setOf("REALITY")).map { it.id })
        assertEquals("every kind failed: keep all", all, FailoverManager.preferUntriedFamilies(all, setOf("REALITY", "CDN", "QUIC")))
    }

    @Test fun hysteria2OnTheSameHostAndPortIsARealFallback() {
        val hy2 = active.copy(id = "hy2", protocolType = com.example.data.model.ProtocolType.HYSTERIA2,
            transport = "hysteria", security = "tls", uuid = "pw")
        assertEquals(listOf("hy2"), FailoverManager.eligibleFallbacks(listOf(active, hy2), active).map { it.id })
    }
}
