package com.example.vpn.cleanip

import com.example.vpn.cleanip.CdnEdgeScanner.Handshake
import com.example.vpn.cleanip.SniProxyDetector.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The Cloudflare SNI-proxy detector: strong signs alone, medium ones in pairs, and nothing on a normal network. */
class SniProxyDetectorTest {
    /** One edge in each of the first [n] Cloudflare ranges. */
    private fun edges(n: Int, h: (Int) -> Handshake) =
        Cdn.CLOUDFLARE.ranges.take(n).mapIndexed { i, r -> CdnRanges.hostIn(r, Random(i))!! to h(i) }

    private val decoysFail = listOf("151.101.1.1" to Handshake(30, null, false), "13.32.1.1" to Handshake(40, null, false))

    @Test fun normalNetworkIsNotProxied() {
        // Anycast: similar TCP times, TLS about one more round trip, valid certificates, Cloudflare sees Iran.
        val v = SniProxyDetector.judge(Evidence(edges(6) { Handshake(40L + it, 45L + it, true) }, decoysFail, "IR", "IR"))
        assertFalse(v.reasons.toString(), v.proxied)
        assertTrue(v.conclusive)
    }

    @Test fun decoyThatCompletesCloudflareTlsFlagsTheNetwork() {
        val v = SniProxyDetector.judge(Evidence(edges(2) { Handshake(40, 45, true) },
            listOf("151.101.1.1" to Handshake(5, 120, true)), null, "IR"))
        assertTrue(v.proxied)
        assertTrue(v.reasons.single().contains("151.101.1.1"))
    }

    @Test fun traceFromAnotherCountryFlagsTheNetwork() {
        val v = SniProxyDetector.judge(Evidence(edges(3) { Handshake(40, 45, true) }, decoysFail, "AZ", "ir"))
        assertTrue(v.proxied)
        assertFalse(SniProxyDetector.judge(Evidence(edges(3) { Handshake(40, 45, true) }, decoysFail, "AZ", null)).proxied)
        assertFalse(SniProxyDetector.judge(Evidence(edges(3) { Handshake(40, 45, true) }, decoysFail, "XX", "IR")).proxied)
    }

    @Test fun oneMediumSignIsNotEnoughTwoAre() {
        // Uniform TCP across ranges with a long TLS: TCP ended near the phone.
        val uniform = edges(5) { Handshake(8, 140, true) }
        assertFalse(SniProxyDetector.judge(Evidence(uniform, decoysFail)).proxied)
        // Most edges answer with a certificate that does not match the name.
        val mismatched = edges(4) { Handshake(8, null, false) }
        assertFalse(SniProxyDetector.judge(Evidence(mismatched, decoysFail)).proxied)
        val wrongCert = (10 until 16).map { "104.16.0.$it" to Handshake(8, null, false) }
        val both = uniform + wrongCert
        val v = SniProxyDetector.judge(Evidence(both, decoysFail))
        assertTrue(v.reasons.toString(), v.proxied)
        assertEquals(2, v.reasons.size)
    }

    @Test fun tooFewRangesDoNotCountAsUniform() {
        val v = SniProxyDetector.judge(Evidence(edges(3) { Handshake(8, 140, true) }, decoysFail))
        assertTrue(v.reasons.isEmpty())
    }

    @Test fun nothingAnsweringIsInconclusiveAndNotRemembered() {
        val v = SniProxyDetector.judge(Evidence(edges(6) { Handshake(null, null) }, listOf("151.101.1.1" to Handshake(null, null))))
        assertFalse(v.proxied)
        assertFalse(v.conclusive)
        var saved: String? = null
        val cache = CleanIpCache({ saved }, { saved = it }, clock = { 0L })
        cache.record("cell:43235", v)
        assertNull(cache.sniProxied("cell:43235"))
    }

    @Test fun traceBodyParsing() {
        assertEquals("AZ", SniProxyDetector.parseTraceLoc("fl=1\nh=speed.cloudflare.com\nip=31.171.100.104\nloc=AZ\ntls=TLSv1.3\n"))
        assertEquals("IR", SniProxyDetector.parseTraceLoc("5b\r\nip=5.1.1.1\r\nloc=IR\r\n0\r\n"))
        assertNull(SniProxyDetector.parseTraceLoc("no trace here"))
    }

    @Test fun probeUsesTheCloudflareNameOnEdgesAndDecoys() {
        val calls = mutableListOf<Pair<String, String>>()
        val e = SniProxyDetector.probe("IR", handshake = { ip, _, sni, _ -> calls += ip to sni; Handshake(10, 20, true) },
            trace = { "loc=IR\n" }, random = Random(2))
        assertEquals(6, e.edges.size)
        assertEquals(3, e.decoys.size)
        assertTrue(e.edges.all { CdnRanges.cdnOf(it.first) == Cdn.CLOUDFLARE })
        assertTrue(e.decoys.none { CdnRanges.cdnOf(it.first) == Cdn.CLOUDFLARE })
        assertTrue(calls.all { it.second == SniProxyDetector.PROBE_NAME })
        assertEquals("IR", e.traceLoc)
    }

    @Test fun cacheRemembersVerdictsAndEdgesWithTtl() {
        var saved: String? = null
        var now = 0L
        val cache = CleanIpCache({ saved }, { saved = it }, clock = { now })
        cache.record("cell:43235", SniProxyDetector.Verdict(true, true, listOf("why")))
        cache.put("cell:43235", Cdn.CLOUDFLARE, "A.example.com", listOf("188.114.98.1", "bad", "188.114.98.1"))
        // A fresh instance reads what the first one saved.
        val reread = CleanIpCache({ saved }, { saved = it }, clock = { now })
        assertEquals(true, reread.sniProxied("cell:43235"))
        assertEquals(listOf("why"), reread.proxyReasons("cell:43235"))
        assertEquals(listOf("188.114.98.1"), reread.best("cell:43235", Cdn.CLOUDFLARE, "a.example.com"))
        assertTrue(reread.best("wifi:x", Cdn.CLOUDFLARE, "a.example.com").isEmpty())
        now = CleanIpCache.DEFAULT_TTL_MS
        assertNull(reread.sniProxied("cell:43235"))
        assertTrue(reread.best("cell:43235", Cdn.CLOUDFLARE, "a.example.com").isEmpty())
    }
}
