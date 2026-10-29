package com.example

import com.example.vpn.sidecar.ResolverReach
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

class ResolverReachTest {
    @Test fun questionIsAPlainRecursiveARecordQuery() {
        val q = ResolverReach.query(0x1234, "t.example.com")
        assertEquals(0x12, q[0].toInt()); assertEquals(0x34, q[1].toInt())
        assertEquals(0x01, q[2].toInt()) // recursion desired
        val name = q.copyOfRange(12, q.size - 4)
        assertArrayEquals(byteArrayOf(1, 't'.code.toByte(), 7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0), name)
        assertArrayEquals(byteArrayOf(0, 1, 0, 1), q.copyOfRange(q.size - 4, q.size))
    }

    @Test fun resolverAddressesParseAsTheSidecarWritesThem() {
        assertEquals(53, ResolverReach.address("1.1.1.1:53").port)
        assertEquals(InetAddress.getByName("2606:4700::1111"), ResolverReach.address("[2606:4700::1111]:53").address)
    }

    @Test fun anAnswerWithTheSameIdCountsAndReportsItsCode() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val thread = Thread {
                val buf = ByteArray(512)
                val p = DatagramPacket(buf, buf.size)
                server.receive(p)
                // A stray reply with another id first, then the real one (NXDOMAIN).
                val stray = buf.copyOf(p.length).also { it[0] = (it[0] + 1).toByte(); it[2] = 0x81.toByte() }
                server.send(DatagramPacket(stray, stray.size, p.socketAddress))
                val reply = buf.copyOf(p.length).also { it[2] = 0x81.toByte(); it[3] = 0x83.toByte() }
                server.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }.apply { start() }
            val outcome = ResolverReach.check("127.0.0.1:${server.localPort}", "t.example.com", timeoutMs = 2_000)
            thread.join()
            assertTrue(outcome is ResolverReach.Outcome.Answered)
            assertEquals(3, (outcome as ResolverReach.Outcome.Answered).rcode)
        }
    }

    @Test fun noReplyIsSilentAndALocalRefusalIsBlocked() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            assertEquals(ResolverReach.Outcome.Silent, ResolverReach.check("127.0.0.1:${server.localPort}", "t.example.com", timeoutMs = 300))
        }
        // The engine's "UPLOAD_MTU value=0" case: the phone gives no network to send on.
        val blocked = ResolverReach.check("1.1.1.1:53", "t.example.com", open = { throw SocketException("Network is unreachable") })
        assertEquals(ResolverReach.Outcome.Blocked("Network is unreachable"), blocked)
        assertTrue(ResolverReach.describe("1.1.1.1:53", blocked).contains("could not be reached from this phone"))
    }
}
