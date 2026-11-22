package com.example.vpn.dns

import com.example.vpn.sidecar.CottenDnsSidecar
import com.example.vpn.sidecar.DnsttSidecar
import com.example.vpn.sidecar.ResolverReach
import com.example.vpn.sidecar.SlipstreamSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hijack detection, ranking, the per-network pool and how DNS-tunnel profiles use it. */
class ResolverScannerTest {
    private fun r(ip: String, replies: Int = 3, ms: Long? = 60, tcp: Long? = null, dot: Long? = null,
                  hijacked: Boolean = false, intercepted: Boolean = false) =
        ResolverScanner.Result(ip, "test", replies, 3, if (replies > 0) ms else null, tcp, dot, hijacked, intercepted)

    @Test fun iranianPoisonAndBogonAnswersAreHijacks() {
        assertTrue(ResolverScanner.hijacked(listOf("1.1.1.1"), listOf("10.10.34.35")))
        assertTrue(ResolverScanner.hijacked(null, listOf("10.10.34.36")))
        assertTrue(ResolverScanner.hijacked(null, listOf("127.0.0.1")))
        assertTrue(ResolverScanner.hijacked(null, listOf("192.168.1.1")))
        // The control name answered with somebody else's address: rewritten.
        assertTrue(ResolverScanner.hijacked(listOf("93.184.216.34"), null))
        assertFalse(ResolverScanner.hijacked(listOf("1.0.0.1", "1.1.1.1"), listOf("142.250.74.110")))
        // No answer at all is silence, not a hijack.
        assertFalse(ResolverScanner.hijacked(null, null))
        assertFalse(ResolverScanner.hijacked(emptyList(), emptyList()))
    }

    @Test fun scoreRewardsSpeedSuccessAndTransportsAndCapsDishonesty() {
        val fast = r("1.1.1.1", ms = 30, tcp = 40, dot = 50)
        assertEquals(100, fast.score)
        assertTrue(r("a", ms = 60).score > r("b", ms = 600).score)
        assertTrue(r("a", ms = 60, tcp = 70).score > r("a", ms = 60).score)
        assertTrue(r("lossy", replies = 1, ms = 30).score <= 60)
        assertTrue(r("x", ms = 30, tcp = 30, dot = 30, hijacked = true).score <= 10)
        assertTrue(r("x", ms = 30, tcp = 30, intercepted = true).score <= 30)
        assertEquals(0, r("dead", replies = 0, ms = null).score)
    }

    @Test fun rankDropsDishonestAndSilentResolversAndOrdersBestFirst() {
        val ranked = ResolverScanner.rank(listOf(
            r("9.9.9.9", ms = 400),
            r("10.0.0.1", ms = 20, hijacked = true),
            r("8.8.8.8", ms = 80),
            r("4.4.4.4", replies = 0, ms = null),
            r("5.5.5.5", ms = 20, intercepted = true),
            r("6.6.6.6", replies = 0, ms = null, tcp = 300)
        ))
        assertEquals(listOf("8.8.8.8", "9.9.9.9", "6.6.6.6"), ranked.map { it.ip })
    }

    @Test fun scanUsesTheProbeWithBoundedParallelismAndRanks() {
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        var peak = 0
        val candidates = (1..12).map { ResolverScanner.Candidate("1.2.3.$it", "test") }
        val ranked = ResolverScanner.scan(candidates, parallelism = 3) { c ->
            val now = inFlight.incrementAndGet()
            synchronized(this) { peak = maxOf(peak, now) }
            Thread.sleep(20)
            inFlight.decrementAndGet()
            val n = c.ip.substringAfterLast('.').toInt()
            r(c.ip, ms = 10L * n, hijacked = n == 1)
        }
        assertTrue(peak <= 3)
        assertEquals(11, ranked.size)
        assertEquals("1.2.3.2", ranked.first().ip)
    }

    @Test fun candidatesPutConfiguredFirstDedupeAndSkipBogons() {
        val c = ResolverScanner.candidates(listOf("8.8.8.8", "10.10.34.34", "5.6.7.8"), listOf("5.6.7.8", "2.2.2.2", "not-an-ip"))
        assertEquals("8.8.8.8", c[0].ip)
        assertTrue(c[0].dot)
        assertEquals("5.6.7.8", c[1].ip)
        assertTrue(c.none { it.ip == "10.10.34.34" || it.ip == "not-an-ip" })
        assertEquals(c.size, c.map { it.ip }.distinct().size)
        assertTrue(c.any { it.ip == "2.2.2.2" })
        assertTrue(ResolverScanner.candidates(emptyList(), (1..100).map { "2.3.4.$it" }).size <= ResolverScanner.MAX_CANDIDATES)
    }

    @Test fun aRecordsParsesCompressedAnswersAndRejectsMismatches() {
        val q = ResolverReach.query(0x1234, "one.one.one.one")
        val answer = { ip: List<Int> ->
            byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + ip.map { it.toByte() }.toByteArray()
        }
        val cname = byteArrayOf(0xc0.toByte(), 0x0c, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0.toByte(), 0x0c)
        val header = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 3, 0, 0, 0, 0)
        val resp = header + q.copyOfRange(12, q.size) + cname + answer(listOf(1, 1, 1, 1)) + answer(listOf(1, 0, 0, 1))
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), ResolverScanner.aRecords(q, resp, resp.size))
        val otherId = resp.copyOf().also { it[1] = 0x35 }
        assertNull(ResolverScanner.aRecords(q, otherId, otherId.size))
        val nxdomain = resp.copyOf().also { it[3] = 0x83.toByte() }
        assertNull(ResolverScanner.aRecords(q, nxdomain, nxdomain.size))
    }

    @Test fun poolKeepsTopHonestResolversPerNetworkUntilItsTtl() {
        var stored: String? = null
        var now = 1_000_000L
        fun pool() = VerifiedResolverPool({ stored }, { stored = it }, { now })
        pool().record("cell:43235", ResolverScanner.rank(listOf(r("8.8.8.8", ms = 90), r("1.1.1.1", ms = 30, dot = 40),
            r("6.6.6.6", hijacked = true))))
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), pool().verified("cell:43235").map { it.ip })
        assertTrue(pool().verified("cell:43235").first().dot)
        assertTrue(pool().verified("wifi").isEmpty())
        assertFalse(pool().needsScan("cell:43235"))
        assertTrue(pool().needsScan("wifi"))
        now += VerifiedResolverPool.TTL_MS / 2
        assertTrue(pool().needsScan("cell:43235"))
        assertEquals(2, pool().verified("cell:43235").size)
        now += VerifiedResolverPool.TTL_MS / 2
        assertTrue(pool().verified("cell:43235").isEmpty())
    }

    @Test fun configBasedPathOnlyReorders() {
        val ordered = VerifiedResolverPool.preferVerified(
            listOf("9.9.9.9:53", "185.51.200.2:53", "8.8.8.8:53"), listOf("8.8.8.8", "185.51.200.2", "1.1.1.1"),
            { VerifiedResolverPool.hostOf(it) })
        assertEquals(listOf("8.8.8.8:53", "185.51.200.2:53", "9.9.9.9:53"), ordered)
        assertEquals("2001:db8::1", VerifiedResolverPool.hostOf("[2001:db8::1]:53"))
        assertEquals("1.2.3.4", VerifiedResolverPool.hostOf("tls://1.2.3.4:853"))
    }

    private val pool = listOf(
        VerifiedResolverPool.Entry("8.8.8.8", 90, 40, udp = true, tcp = true, dot = true),
        VerifiedResolverPool.Entry("178.22.122.100", 80, 20, udp = true, tcp = false, dot = false)
    )

    @Test fun cottenDnsProfileIsReorderedNeverExtended() {
        val p = CottenDnsSidecar.profile(CottenDnsSidecar.Settings("t.example.com", "test-only-cotten-key-000000000000",
            listOf("1.1.1.1", "8.8.8.8:53")))
        val applied = DnsTunnelResolverOrder.apply(CottenDnsSidecar.ID, p, pool)
        assertTrue(applied.changed)
        assertFalse(applied.configFree)
        assertEquals(listOf("8.8.8.8:53", "1.1.1.1:53"), CottenDnsSidecar.settings(applied.profile).resolvers)
        assertFalse(DnsTunnelResolverOrder.apply(CottenDnsSidecar.ID, p, emptyList()).changed)
    }

    @Test fun slipstreamOnItsDefaultResolverIsConfigFreeAndGetsVerifiedResolvers() {
        val free = SlipstreamSidecar.profile("t.example.com")
        val applied = DnsTunnelResolverOrder.apply(SlipstreamSidecar.ID, free, pool)
        assertTrue(applied.configFree)
        assertEquals(listOf("8.8.8.8", "178.22.122.100", SlipstreamSidecar.DEFAULT_RESOLVER),
            SlipstreamSidecar.settings(applied.profile).resolvers)

        val configured = SlipstreamSidecar.profile("t.example.com", listOf("9.9.9.9", "178.22.122.100"))
        val kept = DnsTunnelResolverOrder.apply(SlipstreamSidecar.ID, configured, pool)
        assertFalse(kept.configFree)
        assertEquals(listOf("178.22.122.100", "9.9.9.9"), SlipstreamSidecar.settings(kept.profile).resolvers)
    }

    @Test fun dnsttOnItsDefaultDohGetsOnlyDotVerifiedResolvers() {
        val key = "a".repeat(64)
        val free = DnsttSidecar.profile(key, "t.example.com")
        val applied = DnsTunnelResolverOrder.apply(DnsttSidecar.ID, free, pool)
        assertTrue(applied.configFree)
        val rs = DnsttSidecar.settings(applied.profile).resolvers
        assertEquals(DnsttSidecar.ResolverKind.DOT, rs.first().kind)
        assertEquals("8.8.8.8", VerifiedResolverPool.hostOf(rs.first().address))
        assertEquals(DnsttSidecar.DEFAULT_DOH, rs.last().address)
        assertEquals(2, rs.size)
    }
}
