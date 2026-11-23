package com.example.vpn.cleanip

import com.example.data.model.VlessProfile
import com.example.panels.CleanIpOptimizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Stage E and the Panels optimizer: which clean-edge scan a fronted config gets, and the stores that back it. */
class CleanEdgePlanTest {
    private fun cfg(transport: String, address: String = "edge.example.com", security: String = "tls") = VlessProfile(
        id = "p1", name = "Edge", address = address, port = 443, uuid = "11111111-1111-1111-1111-111111111111",
        transport = transport, security = security, sni = "edge.example.com", host = "edge.example.com", path = "/x"
    )

    @Test fun cloudflareWebSocketGetsTheWebSocketScanToppedUpByHandshakes() {
        val d = CleanEdgePlan.decide(cfg("ws"), Cdn.CLOUDFLARE, cloudflareProxied = false)
        assertTrue(d.runs)
        assertTrue(d.websocketScan)
        assertTrue(d.cdnScan)
        assertTrue(CleanEdgePlan.topUp(d, websocketFound = 1, remainingMs = 9_000))
        assertFalse(CleanEdgePlan.topUp(d, websocketFound = 3, remainingMs = 9_000))
        assertFalse(CleanEdgePlan.topUp(d, websocketFound = 0, remainingMs = 1_000))
    }

    @Test fun everyCdnTransportGetsTheHandshakeScan() {
        listOf("xhttp", "splithttp", "grpc", "h2", "httpupgrade").forEach { t ->
            val d = CleanEdgePlan.decide(cfg(t), Cdn.CLOUDFLARE, false)
            assertTrue(t, d.runs && d.cdnScan)
            assertEquals(t, t == "httpupgrade", d.websocketScan)
        }
        // A Fastly, CloudFront or Gcore front never takes the Cloudflare WebSocket scan, even for WebSocket.
        listOf(Cdn.FASTLY, Cdn.CLOUDFRONT, Cdn.GCORE).forEach { cdn ->
            val d = CleanEdgePlan.decide(cfg("ws"), cdn, cloudflareProxied = true)
            assertTrue("$cdn", d.runs)
            assertFalse("$cdn", d.websocketScan)
            assertEquals(cdn, d.cdn)
            assertTrue(CleanEdgePlan.topUp(d, websocketFound = 0, remainingMs = 15_000))
        }
    }

    @Test fun sniProxiedNetworkSkipsCloudflareOnly() {
        val d = CleanEdgePlan.decide(cfg("xhttp"), Cdn.CLOUDFLARE, cloudflareProxied = true)
        assertFalse(d.runs)
        assertEquals(CleanEdgePlan.SNI_PROXIED, d.skipped)
        assertFalse(CleanEdgePlan.topUp(d, 0, 15_000))
        // Unknown CDN is taken as Cloudflare; an unknown verdict does not skip.
        assertFalse(CleanEdgePlan.decide(cfg("ws"), null, true).runs)
        assertTrue(CleanEdgePlan.decide(cfg("ws"), null, null).runs)
    }

    @Test fun nonFrontedConfigsAreNotScanned() {
        listOf(cfg("tcp"), cfg("ws", security = "reality"), cfg("ws").copy(allowInsecure = true), cfg("ws").copy(rawConfig = "{}"))
            .forEach { p ->
                val d = CleanEdgePlan.decide(p, Cdn.CLOUDFLARE, false)
                assertEquals(CleanEdgePlan.NOT_FRONTED, d.skipped)
                assertNull(d.cdn)
            }
    }

    @Test fun candidatesKeepRememberedFirstAndDropJunk() {
        val c = CleanEdgePlan.candidates(listOf("104.16.1.1"), listOf("104.16.1.1", "104.17.2.2", "nope"),
            (1..20).map { "151.101.0.$it" })
        assertEquals("104.16.1.1", c[0])
        assertEquals("104.17.2.2", c[1])
        assertEquals(CleanEdgePlan.MAX_CANDIDATES, c.size)
        assertFalse(c.contains("nope"))
    }

    @Test fun finderTrustsTheCallersProxyRulingAndCdn() = runBlocking {
        var saved: String? = null
        val cache = CleanIpCache({ saved }, { saved = it })
        var checks = 0
        var resolves = 0
        val scanner = CdnEdgeScanner(handshake = { _, _, _, _ -> CdnEdgeScanner.Handshake(10, 20, true) },
            download = { _, _, _, _ -> null }, random = Random(3), concurrency = 1)
        val finder = CdnCleanIpFinder(cache, scanner,
            detect = { checks++; SniProxyDetector.Verdict(true, true, emptyList()) },
            resolve = { resolves++; emptyList() })
        val found = finder.find(cfg("grpc"), "cell:43211", "IR", speedTop = 0, checkProxy = false, knownCdn = Cdn.CLOUDFRONT)
        assertEquals(0, checks)
        assertEquals(0, resolves)
        assertEquals(Cdn.CLOUDFRONT, found.cdn)
        assertTrue(found.ips.isNotEmpty() && found.ips.all { CdnRanges.cdnOf(it) == Cdn.CLOUDFRONT })
    }

    @Test fun cacheClearForgetsEdgesAndVerdicts() {
        var saved: String? = null
        val cache = CleanIpCache({ saved }, { saved = it })
        cache.put("cell:43211", Cdn.FASTLY, "edge.example.com", listOf("151.101.1.1"))
        cache.record("cell:43211", SniProxyDetector.Verdict(true, true, listOf("why")))
        assertEquals(true, cache.sniProxied("cell:43211"))
        cache.clear()
        assertTrue(cache.best("cell:43211", Cdn.FASTLY, "edge.example.com").isEmpty())
        assertNull(cache.sniProxied("cell:43211"))
        // Persisted: a fresh instance over the saved JSON sees nothing either.
        val reloaded = CleanIpCache({ saved }, { saved = it })
        assertTrue(reloaded.best("cell:43211", Cdn.FASTLY, "edge.example.com").isEmpty())
        assertNull(reloaded.sniProxied("cell:43211"))
    }

    @Test fun cdnEdgeCopiesKeepTlsIdentityAndAreNamedForTheirCdn() {
        val xhttp = cfg("xhttp")
        val edge = CleanIpOptimizer.verifiedCdnEdge("151.101.1.1", Cdn.FASTLY, 120, xhttp.name)
        assertTrue(edge.originVerified)
        val copy = CleanIpOptimizer.variant(xhttp, edge)
        assertEquals("151.101.1.1", copy.address)
        assertEquals("edge.example.com", copy.sni)
        assertEquals("edge.example.com", copy.host)
        assertEquals("xhttp", copy.transport)
        assertTrue(copy.name.startsWith(CleanIpOptimizer.CDN_COPY_PREFIX))
        assertTrue(CleanIpOptimizer.isCleanIpCopy(copy))
        assertEquals("Edge", CleanIpOptimizer.baseName(copy.name).substringBefore(" ("))
        val cf = CleanIpOptimizer.variant(cfg("ws"), CleanIpOptimizer.Result("104.16.1.1", 100, 0, 3))
        assertTrue(cf.name.startsWith("⚡ [CF-Optimized] "))
        assertNotNull(runCatching { CleanIpOptimizer.variant(cfg("tcp"), edge) }.exceptionOrNull())
    }

    @Test fun cloudflareBlockDefersOnlyCloudflareFronts() {
        // cf=0: the plan defers clean IP; a config known to sit on another CDN keeps its early place.
        listOf(Cdn.FASTLY, Cdn.CLOUDFRONT, Cdn.GCORE).forEach { cdn ->
            assertEquals("$cdn", CleanEdgePlan.Timing.EARLY, CleanEdgePlan.timing(early = false, deferred = true, front = cdn))
        }
        assertEquals(CleanEdgePlan.Timing.DEFERRED, CleanEdgePlan.timing(early = false, deferred = true, front = Cdn.CLOUDFLARE))
        // Unknown front is taken as Cloudflare.
        assertEquals(CleanEdgePlan.Timing.DEFERRED, CleanEdgePlan.timing(early = false, deferred = true, front = null))
        // Not cf=0: unchanged; not allowed at all: nothing, whatever the front.
        assertEquals(CleanEdgePlan.Timing.EARLY, CleanEdgePlan.timing(early = true, deferred = false, front = null))
        assertEquals(CleanEdgePlan.Timing.NONE, CleanEdgePlan.timing(early = false, deferred = false, front = Cdn.FASTLY))
    }

    @Test fun candidatesSpreadOverSubnetsAfterTheRememberedOnes() {
        val c = CleanEdgePlan.candidates(
            remembered = listOf("104.16.9.9", "104.16.9.8"),
            websocket = listOf("104.17.1.1", "104.17.1.2", "104.17.1.3"),
            handshake = listOf("104.17.1.4", "104.18.2.1", "104.16.9.9", "104.19.3.1", "104.18.2.2")
        )
        // Remembered stay first and in order, even in one /24; the rest lead with one address per /24, best first.
        assertEquals(listOf("104.16.9.9", "104.16.9.8", "104.17.1.1", "104.18.2.1", "104.19.3.1"), c.take(5))
        assertEquals(listOf("104.17.1.2", "104.18.2.2", "104.17.1.3", "104.17.1.4"), c.drop(5))
        // The first race batch of four now spans three /24s instead of one.
        assertEquals(3, c.drop(2).take(4).mapNotNull { CdnRanges.prefix24(it) }.distinct().size)
    }

    @Test fun diverseKeepsEveryAddressAndRankWithinASubnet() {
        val ips = listOf("1.1.1.1", "1.1.1.2", "2.2.2.1", "1.1.1.3", "3.3.3.1", "2.2.2.2", "1.1.1.1")
        val d = CdnRanges.diverse(ips)
        assertEquals(listOf("1.1.1.1", "2.2.2.1", "3.3.3.1", "1.1.1.2", "2.2.2.2", "1.1.1.3"), d)
        assertEquals("1.1.1", CdnRanges.prefix24("1.1.1.200"))
        assertNull(CdnRanges.prefix24("example.com"))
        assertTrue(CdnRanges.diverse(emptyList()).isEmpty())
    }

    @Test fun cdnComesFromTheTlsNameWhenTheAddressIsNotOnAList() {
        val lookups = mutableListOf<String>()
        // A literal address on no list (a user-picked edge, an unlisted PoP): the SNI's own answer names the CDN.
        val literal = cfg("xhttp", address = "45.12.1.1").copy(sni = "front.example.com", host = "origin.example.com")
        assertEquals(Cdn.FASTLY, CdnEdgeScanner.cdnFor(literal) { n ->
            lookups += n; if (n == "front.example.com") listOf("151.101.1.1") else emptyList()
        })
        assertEquals(listOf("front.example.com"), lookups)
        // A poisoned answer for the address (10.10.34.x): the Host header is the last evidence.
        lookups.clear()
        val poisoned = cfg("ws").copy(sni = "edge.example.com", host = "cdn.example.net")
        assertEquals(Cdn.CLOUDFRONT, CdnEdgeScanner.cdnFor(poisoned) { n ->
            lookups += n; if (n == "cdn.example.net") listOf("13.224.1.2") else listOf("10.10.34.36")
        })
        // The address and the SNI are the same name: looked up once, then the Host.
        assertEquals(listOf("edge.example.com", "cdn.example.net"), lookups)
        // An address on a list wins without any further lookup.
        lookups.clear()
        assertEquals(Cdn.GCORE, CdnEdgeScanner.cdnFor(literal.copy(address = "92.223.124.10")) { lookups += it; listOf("151.101.1.1") })
        assertTrue(lookups.isEmpty())
        // Nothing on a list anywhere: unknown, which the finder takes as Cloudflare.
        assertNull(CdnEdgeScanner.cdnFor(literal) { listOf("8.8.8.8") })
        val finder = CdnCleanIpFinder(CleanIpCache({ null }, { }), resolve = { listOf("8.8.8.8") })
        assertNull(finder.frontOf(literal))
        assertEquals(Cdn.CLOUDFLARE, finder.cdnOf(literal))
    }

    @Test fun finderCachesEdgesSpreadOverSubnets() = runBlocking {
        var saved: String? = null
        val cache = CleanIpCache({ saved }, { saved = it })
        // Three remembered edges in one /24 are the fastest; ranked as is they would fill the head of the list.
        val sameBlock = listOf("151.101.1.1", "151.101.1.2", "151.101.1.3")
        val scanner = CdnEdgeScanner(handshake = { ip, _, _, _ ->
            if (ip in sameBlock) CdnEdgeScanner.Handshake(5, 5, true) else CdnEdgeScanner.Handshake(50, 50, true)
        }, download = { _, _, _, _ -> null }, random = Random(11), concurrency = 1)
        val finder = CdnCleanIpFinder(cache, scanner, detect = { SniProxyDetector.Verdict(false, true, emptyList()) })
        val found = finder.find(cfg("grpc", address = "151.101.65.1"), "cell:43235", "IR", preferred = sameBlock, speedTop = 0)
        assertEquals("151.101.1.1", found.ips.first())
        assertTrue(found.ips[1] !in sameBlock)
        val prefixes = found.ips.map { CdnRanges.prefix24(it) }
        // Leaders first: no /24 repeats before every /24 found has had its turn.
        val firstRepeat = prefixes.withIndex().firstOrNull { (i, p) -> p in prefixes.take(i) }?.index ?: prefixes.size
        assertEquals(prefixes.distinct().size, firstRepeat)
        assertEquals(found.ips, cache.best("cell:43235", Cdn.FASTLY, "edge.example.com"))
    }
}
