package com.example.vpn.cleanip

import com.example.data.model.VlessProfile
import com.example.vpn.cleanip.CdnEdgeScanner.Edge
import com.example.vpn.cleanip.CdnEdgeScanner.Handshake
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Clean CDN edges: sampling across bundled ranges, ranking by clean handshakes, and the per-network path. */
class CdnEdgeScannerTest {
    private fun cfg(transport: String = "xhttp", address: String = "edge.example.com", sni: String = "edge.example.com") = VlessProfile(
        id = "p1", name = "p1", address = address, port = 443, uuid = "11111111-1111-1111-1111-111111111111",
        transport = transport, security = "tls", sni = sni, host = sni, path = "/x"
    )

    private fun ok(tcp: Long, tls: Long) = Handshake(tcp, tls, true)

    @Test fun rangesParseAndClassifyAddresses() {
        Cdn.entries.forEach { cdn -> cdn.ranges.forEach { assertTrue("$cdn $it", CdnRanges.parse(it) != null) } }
        assertEquals(Cdn.CLOUDFLARE, CdnRanges.cdnOf("188.114.98.0"))
        assertEquals(Cdn.CLOUDFLARE, CdnRanges.cdnOf("104.18.0.8"))
        assertEquals(Cdn.FASTLY, CdnRanges.cdnOf("151.101.1.1"))
        assertEquals(Cdn.CLOUDFRONT, CdnRanges.cdnOf("13.32.5.9"))
        assertEquals(Cdn.GCORE, CdnRanges.cdnOf("92.223.124.10"))
        assertNull(CdnRanges.cdnOf("8.8.8.8"))
        assertNull(CdnRanges.ipv4("256.1.1.1"))
        assertNull(CdnRanges.ipv4("example.com"))
        assertTrue(CdnRanges.contains("104.16.0.0/13", "104.23.255.254"))
        assertFalse(CdnRanges.contains("104.16.0.0/13", "104.24.0.1"))
    }

    @Test fun samplingSpreadsOverRangesAndStaysInside() {
        val s = CdnRanges.sample(Cdn.CLOUDFLARE, 30, Random(7))
        assertEquals(30, s.size)
        assertEquals(s.size, s.distinct().size)
        s.forEach { assertEquals(Cdn.CLOUDFLARE, CdnRanges.cdnOf(it)) }
        // One per range first: 15 Cloudflare ranges are all touched by 30 samples.
        assertEquals(Cdn.CLOUDFLARE.ranges.size, s.mapNotNull { CdnRanges.rangeOf(Cdn.CLOUDFLARE, it) }.distinct().size)
        // Gcore edges are single addresses: a sample never invents one.
        val g = CdnRanges.sample(Cdn.GCORE, 500, Random(1))
        assertEquals(Cdn.GCORE.ranges.size, g.size)
        g.forEach { assertTrue(it, "$it/32" in Cdn.GCORE.ranges) }
        assertTrue(CdnRanges.sample(Cdn.FASTLY, 0).isEmpty())
    }

    @Test fun rankingPrefersCleanFastAndReliableEdges() {
        val fast = Edge("1.1.1.1", Cdn.CLOUDFLARE, listOf(ok(20, 40), ok(22, 41)))
        val slow = Edge("1.1.1.2", Cdn.CLOUDFLARE, listOf(ok(80, 160), ok(81, 170)))
        val flaky = Edge("1.1.1.3", Cdn.CLOUDFLARE, listOf(ok(15, 30), Handshake(15, null)))
        val dead = Edge("1.1.1.4", Cdn.CLOUDFLARE, listOf(Handshake(null, null)))
        val intercepted = Edge("1.1.1.5", Cdn.CLOUDFLARE, listOf(Handshake(5, null, false)))
        val ranked = CdnEdgeScanner.rank(listOf(dead, slow, intercepted, flaky, fast))
        assertEquals(listOf("1.1.1.1", "1.1.1.2", "1.1.1.3"), ranked.map { it.ip })
        assertEquals(63L, fast.medianMs)
        assertTrue(intercepted.certMismatch)
    }

    @Test fun throughputCountsOnceMeasured() {
        val quickButThin = Edge("1.1.1.1", Cdn.CLOUDFLARE, listOf(ok(20, 40)), downloadKbps = 30)
        val slowerButWide = Edge("1.1.1.2", Cdn.CLOUDFLARE, listOf(ok(50, 90)), downloadKbps = 2_000)
        val failedDownload = Edge("1.1.1.3", Cdn.CLOUDFLARE, listOf(ok(10, 20)), downloadKbps = 0)
        assertEquals(listOf("1.1.1.2", "1.1.1.3", "1.1.1.1"), CdnEdgeScanner.rank(listOf(quickButThin, slowerButWide, failedDownload)).map { it.ip })
    }

    @Test fun scanIsBoundedRanksAndDropsInterceptedEdges() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val seenSni = mutableSetOf<String>()
        val scanner = CdnEdgeScanner(handshake = { ip, _, sni, _ ->
            synchronized(seenSni) { seenSni += sni }
            peak.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(5)
            active.decrementAndGet()
            val last = ip.substringAfterLast('.').toInt()
            when {
                last % 3 == 0 -> Handshake(10, null, false)
                last % 3 == 1 -> Handshake(null, null)
                else -> ok(10L + last % 50, 20L + last % 50)
            }
        }, download = { _, _, _, _ -> null }, random = Random(3), concurrency = 4)
        val edges = scanner.scan(Cdn.FASTLY, "edge.example.com", samples = 40, enough = 100)
        assertTrue("peak ${peak.get()}", peak.get() <= 4)
        assertEquals(setOf("edge.example.com"), seenSni)
        assertTrue(edges.isNotEmpty())
        assertTrue(edges.all { it.ip.substringAfterLast('.').toInt() % 3 == 2 })
        assertEquals(edges.sortedBy { it.score }.map { it.ip }, edges.map { it.ip })
    }

    @Test fun scanTestsPreferredFirstAndMeasuresSpeedOnlyOnTheBest() = runBlocking {
        val downloads = mutableListOf<String>()
        val scanner = CdnEdgeScanner(handshake = { ip, _, _, _ -> if (ip == "104.18.0.8") ok(5, 10) else ok(50, 100) },
            download = { ip, host, _, _ -> synchronized(downloads) { downloads += "$ip@$host" }; 1_000L }, random = Random(5), concurrency = 1)
        val edges = scanner.scan(Cdn.CLOUDFLARE, "a.example.com", preferred = listOf("104.18.0.8", "not-an-ip"), samples = 6, enough = 100, speedTop = 2)
        assertEquals("104.18.0.8", edges.first().ip)
        assertEquals(2, downloads.size)
        assertTrue(downloads.all { it.endsWith("@speed.cloudflare.com") })
    }

    @Test fun configsAndTheirCdn() {
        assertTrue(CdnEdgeScanner.fronted(cfg("ws")))
        assertTrue(CdnEdgeScanner.fronted(cfg("grpc")))
        assertTrue(CdnEdgeScanner.fronted(cfg("xhttp")))
        assertFalse(CdnEdgeScanner.fronted(cfg("tcp")))
        assertFalse(CdnEdgeScanner.fronted(cfg().copy(security = "reality")))
        assertFalse(CdnEdgeScanner.fronted(cfg().copy(allowInsecure = true)))
        assertEquals(Cdn.FASTLY, CdnEdgeScanner.cdnFor(cfg(address = "151.101.65.1")))
        assertEquals(Cdn.CLOUDFRONT, CdnEdgeScanner.cdnFor(cfg()) { listOf("10.0.0.1", "13.224.1.2") })
        assertNull(CdnEdgeScanner.cdnFor(cfg()) { throw java.net.UnknownHostException() })
        assertEquals("edge.example.com", CdnEdgeScanner.tlsName(cfg(sni = "")))
    }

    @Test fun finderCachesPerNetworkAndSkipsCloudflareOnAProxiedNetwork() = runBlocking {
        var saved: String? = null
        var now = 1_000_000L
        val cache = CleanIpCache({ saved }, { saved = it }, clock = { now })
        var scans = 0
        var checks = 0
        val scanner = CdnEdgeScanner(handshake = { _, _, _, _ -> scans++; ok(10, 20) }, download = { _, _, _, _ -> null }, random = Random(9), concurrency = 1)
        var proxied = false
        val finder = CdnCleanIpFinder(cache, scanner, detect = { checks++; SniProxyDetector.Verdict(proxied, true, emptyList()) })

        val fastly = cfg(address = "151.101.65.1")
        val first = finder.find(fastly, "cell:43235", "IR")
        assertEquals(Cdn.FASTLY, first.cdn)
        assertTrue(first.ips.isNotEmpty())
        assertTrue(first.ips.all { CdnRanges.cdnOf(it) == Cdn.FASTLY })
        assertEquals(0, checks)
        val before = scans
        val again = finder.find(fastly, "cell:43235", "IR")
        assertTrue(again.fromCache)
        assertEquals(before, scans)
        assertTrue(finder.find(fastly, "wifi:home", "IR").ips.isNotEmpty())

        proxied = true
        val cf = finder.find(cfg(address = "188.114.98.0"), "cell:43211", "IR")
        assertTrue(cf.ips.isEmpty())
        assertTrue(cf.skipped!!.contains("proxies"))
        finder.find(cfg(address = "188.114.98.0", sni = "b.example.com"), "cell:43211", "IR")
        assertEquals("verdict remembered per network", 1, checks)

        // Expired edges are scanned again.
        now += CleanIpCache.DEFAULT_TTL_MS + 1
        val s = scans
        assertFalse(finder.find(fastly, "cell:43235", "IR").fromCache)
        assertTrue(scans > s)
        assertTrue(finder.find(cfg("tcp"), "cell:43235", "IR").skipped != null)
    }
}
