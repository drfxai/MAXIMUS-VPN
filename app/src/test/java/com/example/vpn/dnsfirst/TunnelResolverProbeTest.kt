package com.example.vpn.dnsfirst

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelResolverProbeTest {
    private val domain = "t.example.com"
    private val fast = TunnelResolverProbe.Timeouts(300, 300, 300)

    private fun probe(t: TunnelResolverProbe.Transport, kind: String = "udp") =
        TunnelResolverProbe.probe("198.51.100.1", domain, t, Random(42), fast, kind)

    @Test
    fun honestRecursingResolverPassesEverything() {
        val caps = probe(FakeResolver.Behaviour())
        assertTrue(caps.reach); assertTrue(caps.longName); assertTrue(caps.txt)
        assertTrue(caps.nullType); assertTrue(caps.cname); assertTrue(caps.tcp); assertTrue(caps.zShape)
        assertEquals(1232, caps.ednsPayload)
        assertEquals(TunnelResolverProbe.BURST, caps.burstAnswered)
        assertEquals(4, caps.safeQps)
        assertTrue(caps.suits(EngineKind.DNSTT)); assertTrue(caps.suits(EngineKind.SLIPSTREAM))
        assertTrue(caps.suits(EngineKind.COTTEN, overTcp = true))
        assertTrue(caps.score > 60)
    }

    @Test
    fun refusingResolverIsNotReached() {
        val caps = probe(FakeResolver.Behaviour(answer = { FakeResolver.reply(it, DnsWire.RCODE_REFUSED) }))
        assertFalse(caps.reach)
        assertEquals(DnsWire.RCODE_REFUSED, caps.rcode)
        assertFalse(caps.usable)
        assertEquals(0, caps.score)
    }

    @Test
    fun silentResolverReachableOnlyOverTcpIsReported() {
        val t = object : TunnelResolverProbe.Transport {
            override fun exchange(query: ByteArray, timeoutMs: Int, tcp: Boolean) = if (tcp) FakeResolver.reply(query) else null
            override fun burst(queries: List<ByteArray>, timeoutMs: Int) = emptyList<ByteArray>()
        }
        val caps = probe(t)
        assertFalse(caps.reach)
        assertTrue(caps.tcp)
    }

    @Test
    fun resolverThatDropsTxtSuitsOnlyCotten() {
        val caps = probe(FakeResolver.Behaviour(answer = { q -> if (FakeResolver.qtype(q) == DnsWire.TYPE_TXT) null else FakeResolver.reply(q) }))
        assertTrue(caps.reach)
        assertFalse(caps.txt)
        assertFalse("long-name probe is a TXT question", caps.longName)
        assertFalse(caps.suits(EngineKind.DNSTT))
    }

    @Test
    fun resolverThatDropsLongNamesIsNotUsable() {
        val caps = probe(FakeResolver.Behaviour(answer = { q -> if (FakeResolver.nameLength(q) > 100) null else FakeResolver.reply(q, txt = true) }))
        assertTrue(caps.reach); assertTrue(caps.txt); assertFalse(caps.longName)
        assertFalse(caps.usable)
        assertFalse(caps.suits(EngineKind.COTTEN))
    }

    @Test
    fun rateLimitedResolverGetsALowerRate() {
        assertEquals(2, probe(FakeResolver.Behaviour(burstLimit = 7)).safeQps)
        assertEquals(1, probe(FakeResolver.Behaviour(burstLimit = 3)).safeQps)
        val limited = probe(FakeResolver.Behaviour(burstLimit = 3))
        assertTrue(limited.score < probe(FakeResolver.Behaviour()).score)
    }

    @Test
    fun zBitRejectionIsRecordedWithoutAffectingUse() {
        val caps = probe(FakeResolver.Behaviour(answer = { q ->
            if (FakeResolver.zSet(q)) FakeResolver.reply(q, DnsWire.RCODE_FORMERR) else FakeResolver.reply(q, txt = FakeResolver.qtype(q) == DnsWire.TYPE_TXT)
        }))
        assertFalse(caps.zShape)
        assertTrue(caps.suits(EngineKind.DNSTT))
    }

    @Test
    fun dohProbeDoesNotAskForTcp() {
        val posted = mutableListOf<ByteArray>()
        val doh = TunnelResolverProbe.Doh("https://doh.example/dns-query") { _, body, _ -> posted += body; FakeResolver.reply(body, txt = true) }
        val caps = TunnelResolverProbe.probe("https://doh.example/dns-query", domain, doh, Random(1), fast, kind = "doh")
        assertEquals("doh", caps.transport)
        assertTrue(caps.suits(EngineKind.DNSTT))
        assertFalse(caps.tcp)
        assertTrue(posted.size > TunnelResolverProbe.BURST)
    }

    @Test
    fun scanRanksBestFirstAndDropsThrowingProbes() {
        val byIp = mapOf(
            "198.51.100.1" to FakeResolver.Behaviour(burstLimit = 3),
            "198.51.100.2" to FakeResolver.Behaviour(),
            "198.51.100.3" to FakeResolver.Behaviour(answer = { FakeResolver.reply(it, DnsWire.RCODE_REFUSED) })
        )
        val ranked = TunnelResolverProbe.scan(byIp.keys.toList() + "198.51.100.9", domain, parallelism = 2) { ip ->
            TunnelResolverProbe.probe(ip, domain, byIp[ip] ?: error("down"), Random(7), fast)
        }
        assertEquals(listOf("198.51.100.2", "198.51.100.1", "198.51.100.3"), ranked.map { it.resolver })
    }

    @Test
    fun udpTransportTalksToARealSocketAndIgnoresStrangers() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Thread {
                val buf = ByteArray(2048)
                runCatching {
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        server.receive(p)
                        val q = buf.copyOf(p.length)
                        val r = FakeResolver.reply(q, txt = true)
                        server.send(DatagramPacket(r, r.size, p.socketAddress))
                    }
                }
            }.apply { isDaemon = true; start() }
            val udp = TunnelResolverProbe.Udp("127.0.0.1", server.localPort)
            val q = DnsWire.query(77, "a.$domain", DnsWire.TYPE_TXT)
            val reply = udp.exchange(q, 1_000)
            assertNotNull(reply)
            assertEquals(DnsWire.RCODE_NXDOMAIN, DnsWire.parse(q, reply!!)!!.rcode)
            val burst = List(5) { DnsWire.query(100 + it, "b$it.$domain", DnsWire.TYPE_TXT) }
            assertEquals(5, udp.burst(burst, 1_000).size)
            server.close(); worker.join(1_000)
        }
        // Nothing listens on a closed port: silence, never an exception.
        val dead = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertNull(TunnelResolverProbe.Udp("127.0.0.1", dead).exchange(DnsWire.query(1, "c.$domain", DnsWire.TYPE_A), 300))
    }
}
