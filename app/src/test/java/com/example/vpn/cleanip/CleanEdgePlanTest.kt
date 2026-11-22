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
}
