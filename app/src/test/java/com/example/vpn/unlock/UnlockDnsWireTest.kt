package com.example.vpn.unlock

import com.example.data.model.OperationalMode
import com.example.vpn.packet.IPv4Header
import com.example.vpn.packet.PacketBuilder
import com.example.vpn.packet.UdpHeader
import com.example.vpn.safety.MaximusVpnSupervisor
import com.example.vpn.safety.OperatingModePolicy
import com.example.vpn.safety.UnlockDnsRoutePolicy
import com.example.vpn.safety.VpnRoutePolicy
import com.example.vpn.sidecar.ResolverReach
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DNS-only TUN's packets, its route policy, and how Unlock mode is wired into the modes. */
class UnlockDnsWireTest {
    private val dns = IPv4Header.parseIp(UnlockDnsRoutePolicy.DNS_SERVER)
    private val app = IPv4Header.parseIp("172.19.0.1")

    @Test fun aQuestionToTheTunDnsAddressIsTakenAndAnsweredWithMatchingAddresses() {
        val question = ResolverReach.query(0x1234, "gemini.google.com")
        val packet = PacketBuilder.buildUdpPacket(app, dns, 40_000, 53, question)
        val query = UnlockDnsWire.parseQuery(packet, packet.size, dns)
        assertNotNull(query)
        query!!
        assertArrayEquals(question, query.payload)
        assertEquals(40_000, query.clientPort)

        val response = question.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        val reply = UnlockDnsWire.reply(query, response)
        val ip = IPv4Header.parse(reply, 0, reply.size)!!
        assertEquals(UnlockDnsRoutePolicy.DNS_SERVER, ip.srcIpStr)
        assertEquals("172.19.0.1", ip.dstIpStr)
        val udp = UdpHeader.parse(reply, 20, reply.size)!!
        assertEquals(53, udp.srcPort)
        assertEquals(40_000, udp.dstPort)
        assertArrayEquals(response, reply.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength))
    }

    @Test fun otherPacketsNeverReachTheResolver() {
        val question = ResolverReach.query(1, "example.com")
        val otherPort = PacketBuilder.buildUdpPacket(app, dns, 40_000, 853, question)
        assertNull(UnlockDnsWire.parseQuery(otherPort, otherPort.size, dns))
        val otherHost = PacketBuilder.buildUdpPacket(app, IPv4Header.parseIp("1.1.1.1"), 40_000, 53, question)
        assertNull(UnlockDnsWire.parseQuery(otherHost, otherHost.size, dns))
        val tooShort = PacketBuilder.buildUdpPacket(app, dns, 40_000, 53, ByteArray(5))
        assertNull(UnlockDnsWire.parseQuery(tooShort, tooShort.size, dns))
        val fragment = PacketBuilder.buildUdpPacket(app, dns, 40_000, 53, question).also { it[6] = 0x20 } // more fragments
        assertNull(UnlockDnsWire.parseQuery(fragment, fragment.size, dns))
        assertNull(UnlockDnsWire.parseQuery(ByteArray(40), 40, dns))
    }

    @Test fun servfailEchoesTheQuestionOnly() {
        val question = ResolverReach.query(0xBEEF, "api.openai.com")
        val fail = UnlockDnsWire.servfail(question)!!
        assertEquals(question.size, fail.size)
        assertEquals(question[0], fail[0]); assertEquals(question[1], fail[1])
        assertTrue(fail[2].toInt() and 0x80 != 0)
        assertEquals(2, fail[3].toInt() and 0x0f)
        assertEquals(1, fail[5].toInt())
        assertTrue(UnlockDnsWire.truncated(byteArrayOf(0, 0, 0x02, 0)))
        assertFalse(UnlockDnsWire.truncated(question))
        assertNull(UnlockDnsWire.servfail(ByteArray(4)))
    }

    @Test fun theUnlockInterfaceRoutesOnlyItsDnsAddress() {
        assertTrue(UnlockDnsRoutePolicy.dnsOnly(UnlockDnsRoutePolicy.ROUTES))
        assertFalse(VpnRoutePolicy.capturesEverything(UnlockDnsRoutePolicy.ROUTES))
        assertFalse(UnlockDnsRoutePolicy.dnsOnly(VpnRoutePolicy.ROUTES))
        assertEquals(VpnRoutePolicy.DNS_SERVER, UnlockDnsRoutePolicy.DNS_SERVER)
        assertFalse(UnlockDnsRoutePolicy.ADDRESS.address.contains(':'))
        // Every other Maximus TUN still captures everything.
        assertTrue(VpnRoutePolicy.capturesEverything(VpnRoutePolicy.ROUTES))
        assertTrue(UnlockDnsRoutePolicy.MTU >= UnlockDnsWire.MAX_MESSAGE + 28)
    }

    @Test fun unlockSitsAfterDailyAndKeepsTheUsersSettings() {
        val modes = OperationalMode.entries
        assertEquals(OperationalMode.UNLOCK, modes[modes.indexOf(OperationalMode.DAILY) + 1])
        assertEquals("Unlock Mode", OperationalMode.UNLOCK.displayName)
        assertEquals(OperationalMode.UNLOCK, OperationalMode.valueOf("UNLOCK"))
        val policy = OperatingModePolicy.of(OperationalMode.UNLOCK)
        assertEquals(OperationalMode.UNLOCK, policy.mode)
        assertFalse(policy.survival)
        assertFalse(policy.lockExit)
        val settings = com.example.data.model.AppSettings(autoFailoverEnabled = false, ipv6Enabled = true)
        assertEquals(settings, policy.apply(settings))
    }

    @Test fun unlockModeReleasesTheTrafficBlock() {
        val supervisor = MaximusVpnSupervisor()
        supervisor.requestProtection()
        supervisor.release(MaximusVpnSupervisor.Release.UNLOCK_MODE)
        assertFalse(supervisor.protectionRequested)
        assertEquals(MaximusVpnSupervisor.Release.UNLOCK_MODE, supervisor.lastRelease)
    }
}
