package com.example.vpn.connectivity

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 7: one Top-30 list on the phone that never piles onto one CDN, network, source or kind. */
class DiversitySelectorTest {
    private fun p(id: String, address: String, security: String = "tls", transport: String = "ws", sni: String = address,
                  source: String = "s1", proto: ProtocolType = ProtocolType.VLESS) =
        VlessProfile(id = id, name = id, address = address, port = 443, uuid = "u", protocolType = proto, security = security,
            transport = transport, sni = sni, sourceSubscription = source)

    private fun pick(items: List<VlessProfile>, score: Map<String, Double>, limit: Int = DiversitySelector.MAX) =
        DiversitySelector.select(items, { score.getValue(it.id) }, DiversitySelector::traitsOf, { it.id }, limit)

    @Test fun failureDomainsFollowTheListBuilder() {
        assertEquals("cdn:cloudflare", DiversitySelector.traitsOf(p("a", "104.16.5.5")).failureDomain)
        assertEquals("cdn:cloudflare", DiversitySelector.traitsOf(p("a", "x.y.workers.dev")).failureDomain)
        assertEquals("cdn:cloudfront", DiversitySelector.traitsOf(p("a", "d1.cloudfront.net")).failureDomain)
        assertEquals("net:5.6.0.0/16", DiversitySelector.traitsOf(p("a", "5.6.7.8", security = "reality", transport = "tcp")).failureDomain)
        assertEquals("net:2a01:4f8::/32", DiversitySelector.traitsOf(p("a", "[2a01:4f8::1]", security = "reality", transport = "tcp")).failureDomain)
        assertEquals("dom:example.org", DiversitySelector.traitsOf(p("a", "a.b.example.org")).failureDomain)
        assertTrue(DiversitySelector.traitsOf(p("a", "5.6.7.8", sni = "front.example.com")).cdn)
    }

    @Test fun neverMoreThanThirtyAndBestFirst() {
        val items = (1..80).map { p("n$it", "${10 + it}.1.1.1", security = "reality", transport = "tcp", source = "s${it % 9}") }
        val score = items.associate { it.id to it.id.drop(1).toDouble() }
        val out = pick(items, score)
        assertEquals(30, out.size)
        assertEquals(out.sortedByDescending { score.getValue(it.id) }, out)
        assertEquals(30, pick(items, score, limit = 500).size)
    }

    @Test fun oneCloudflareRangeCannotFillTheList() {
        val cf = (1..20).map { p("cf$it", "104.16.0.$it") }
        val direct = (1..10).map { p("d$it", "${20 + it}.2.3.4", security = "reality", transport = "tcp") }
        val score = cf.associate { it.id to 100.0 } + direct.associate { it.id to 10.0 }
        val out = pick(cf + direct, score, limit = 10)
        assertEquals(3, out.count { it.id.startsWith("cf") })
        assertEquals(3, DiversitySelector.largestDomain(out, DiversitySelector::traitsOf))
    }

    @Test fun capsRelaxRatherThanLeaveTheListShort() {
        val same = (1..8).map { p("s$it", "104.16.0.$it") }
        val out = pick(same, same.associate { it.id to 1.0 }, limit = 8)
        assertEquals(8, out.size)
    }

    @Test fun bothAddressFamiliesAreSeeded() {
        val v4 = (1..10).map { p("v4-$it", "${30 + it}.0.0.1", security = "reality", transport = "tcp") }
        val v6 = p("v6", "[2001:470::1]", security = "reality", transport = "tcp")
        val score = v4.associate { it.id to 50.0 } + mapOf("v6" to 1.0)
        assertTrue(pick(v4 + v6, score, limit = 5).any { it.id == "v6" })
    }

    @Test fun deterministic() {
        val items = (1..40).map { p("x$it", "104.${16 + it % 8}.0.$it", source = "s${it % 3}") }
        val score = items.associate { it.id to (it.id.hashCode() % 7).toDouble() }
        assertEquals(pick(items, score).map { it.id }, pick(items.reversed(), score).map { it.id })
    }
}
