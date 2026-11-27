package com.example.vpn.cleanip

import com.example.vpn.connections.BuiltinMethodMemory
import com.example.vpn.smartsni.SmartSniSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Build 181 Phase 5 (C): clean-IP scanner scoring, bounds, per-network memory, drop-and-rescan, refresh rate limit. */
class SmartIpScanTest {
    private val store = mutableMapOf<String, String>()
    private val settings = mutableMapOf<String, String>()

    @Before fun setUp() {
        store.clear(); settings.clear()
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        SmartSniSettings.read = { settings[it] }
        SmartSniSettings.write = { k, v -> if (v == null) settings.remove(k) else settings[k] = v }
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = { null }
        BuiltinMethodMemory.write = { _, _ -> }
        SmartSniSettings.read = { null }
        SmartSniSettings.write = { _, _ -> }
        SmartIpScan.warpProbe = { _, _, _ -> null }
        SmartIpScan.cdnProbe = { _, _, _ -> null }
    }

    private fun edge(ip: String, kind: SmartIpScan.Kind, vararg tries: Long?, data: Boolean = true, kbps: Long? = null) =
        SmartIpScan.Edge(ip, kind, tries.toList(), data, kbps)

    @Test fun scoreWeighsLatencyLossAndThroughput() {
        val fast = edge("1.1.1.1", SmartIpScan.Kind.CDN, 80, 90)
        val lossy = edge("1.1.2.1", SmartIpScan.Kind.CDN, 60, null)
        val slow = edge("1.1.3.1", SmartIpScan.Kind.CDN, 80, 90, kbps = 30)
        val noData = edge("1.1.4.1", SmartIpScan.Kind.CDN, 40, 40, data = false)
        val dead = edge("1.1.5.1", SmartIpScan.Kind.CDN, null, null)
        assertEquals(50, lossy.lossPercent)
        assertTrue(SmartIpScan.score(fast) < SmartIpScan.score(lossy))
        assertTrue(SmartIpScan.score(fast) < SmartIpScan.score(slow))
        assertTrue(SmartIpScan.score(fast) < SmartIpScan.score(noData))
        assertEquals(Double.MAX_VALUE, SmartIpScan.score(dead), 0.0)
        val ranked = SmartIpScan.rank(listOf(dead, noData, slow, lossy, fast))
        assertEquals("1.1.1.1", ranked.first().ip)
        assertTrue(ranked.none { it.ip == "1.1.5.1" })
    }

    @Test fun samplesStayInsideWarpRanges() {
        val s = SmartIpScan.sample(SmartIpScan.WARP_RANGES, 16, Random(7))
        assertEquals(16, s.size)
        assertTrue(s.all { ip -> SmartIpScan.WARP_RANGES.any { CdnRanges.contains(it, ip) } })
    }

    @Test fun scanIsBoundedInTimeConcurrencyAndSample() {
        val running = AtomicInteger(); val peak = AtomicInteger(); val calls = AtomicInteger()
        val probe: (String) -> Long? = { ip ->
            calls.incrementAndGet()
            val now = running.incrementAndGet(); peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
            running.decrementAndGet()
            if (ip.endsWith("7")) null else 100L + (ip.substringAfterLast('.').toLong() % 50)
        }
        SmartIpScan.warpProbe = { ip, _, _ -> probe(ip) }
        SmartIpScan.cdnProbe = { ip, _, _ -> probe(ip)?.let { it to true } }
        val lines = mutableListOf<String>()
        val started = System.currentTimeMillis()
        val r = SmartIpScan.scan("cell", "www.speedtest.net", budgetMs = 1_000, random = Random(1)) { lines += it }
        val took = System.currentTimeMillis() - started
        assertTrue("took $took ms", took < 2_500)
        assertTrue(peak.get() <= SmartIpScan.CONCURRENCY)
        assertTrue(calls.get() <= 2 * SmartIpScan.SAMPLE * SmartIpScan.TRIES)
        assertTrue(r.warp.size <= SmartIpScan.KEEP && r.cdn.size <= SmartIpScan.KEEP)
        assertTrue(r.count > 0)
        // counts and latency only in logs
        assertTrue(lines.single().contains("answered"))
        assertFalse(lines.single().contains("162.159."))
    }

    @Test fun memoryIsPerNetworkAndAFailedAddressIsDropped() {
        SmartIpScan.warpProbe = { _, _, _ -> 50L }
        SmartIpScan.cdnProbe = { _, _, _ -> null }
        SmartIpScan.scan("cell:43235", "x.cloudflare.com", kinds = setOf(SmartIpScan.Kind.WARP), budgetMs = 3_000, random = Random(2))
        val peers = SmartIpScan.warpPeers("cell:43235")
        assertTrue(peers.isNotEmpty() && peers.all { it.endsWith(":443") })
        assertTrue(SmartIpScan.warpPeers("wifi:home").isEmpty())
        assertFalse(SmartIpScan.needsScan("cell:43235", SmartIpScan.Kind.WARP))
        assertTrue(SmartIpScan.needsScan("wifi:home", SmartIpScan.Kind.WARP))
        peers.forEach { SmartIpScan.drop("cell:43235", it) }
        assertTrue(SmartIpScan.warpPeers("cell:43235").isEmpty())
        assertTrue(SmartIpScan.needsScan("cell:43235", SmartIpScan.Kind.WARP))
        // the setting off: no automatic scan
        SmartSniSettings.setFlag(SmartSniSettings.CLEAN_IP_SCAN, false)
        assertFalse(SmartIpScan.needsScan("cell:43235", SmartIpScan.Kind.WARP))
    }

    @Test fun backgroundRefreshIsRateLimited() {
        val now = 10_000_000L
        assertTrue(SmartIpScan.mayRefresh(true, true, 0, now))
        assertFalse(SmartIpScan.mayRefresh(true, true, now - 60_000, now))
        assertTrue(SmartIpScan.mayRefresh(true, true, now - SmartIpScan.REFRESH_MIN_MS, now))
        assertFalse(SmartIpScan.mayRefresh(false, true, 0, now))
        assertFalse(SmartIpScan.mayRefresh(true, false, 0, now))
    }
}
