package com.example.panels.cloudflare

import com.example.panels.cloudflare.SmartDomains.Action
import com.example.panels.cloudflare.SmartDomains.Record
import com.example.panels.cloudflare.SmartDomains.Use
import com.example.panels.cloudflare.SmartDomains.Zone
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart subdomains: the right proxy setting per use, and nothing that belongs to something else is changed. */
class SmartDomainsTest {
    private val zones = listOf(Zone("z1", "example.com", "active"), Zone("z2", "shop.example.com", "pending"), Zone("z3", "other.net", "active"))

    @Test fun cdnNamesAreProxiedAndDirectOnesAreNot() {
        assertTrue(SmartDomains.desired("edge.example.com", "203.0.113.9", Use.CDN).proxied)
        assertEquals(false, SmartDomains.desired("r.example.com", "203.0.113.9", Use.DIRECT).proxied)
        assertEquals(false, SmartDomains.desired("ns.example.com", "203.0.113.9", Use.DNS_TUNNEL).proxied)
        assertEquals("AAAA", SmartDomains.desired("v6.example.com", "2001:db8::1", Use.DIRECT).type)
    }

    @Test fun aCdnNameNeedsACloudflarePort() {
        assertNull(SmartDomains.portProblem(Use.CDN, 443))
        assertNull(SmartDomains.portProblem(Use.CDN, 8443))
        assertNotNull(SmartDomains.portProblem(Use.CDN, 8444))
        assertNull(SmartDomains.portProblem(Use.DIRECT, 8444))
    }

    @Test fun labelsAreSingleSafeNames() {
        assertNull(SmartDomains.labelProblem("edge-1"))
        assertNotNull(SmartDomains.labelProblem("a.b"))
        assertNotNull(SmartDomains.labelProblem("*"))
        assertNotNull(SmartDomains.labelProblem("-x"))
        assertNotNull(SmartDomains.labelProblem(""))
        repeat(20) { assertNull(SmartDomains.labelProblem(SmartDomains.randomLabel("cdn"))) }
        assertTrue(SmartDomains.randomLabel("cdn").startsWith("cdn-"))
    }

    @Test fun theZoneIsTheLongestActiveSuffix() {
        assertEquals("z1", SmartDomains.zoneFor("edge.example.com", zones)?.id)
        // The pending sub-zone is longer but not active yet, so the active parent zone is used.
        assertEquals("z1", SmartDomains.zoneFor("a.shop.example.com", zones)?.id)
        assertEquals("z3", SmartDomains.zoneFor("https://x.other.net/path", zones)?.id)
        assertNull(SmartDomains.zoneFor("example.org", zones))
        assertNull("a look-alike is not a suffix", SmartDomains.zoneFor("badexample.com", zones))
    }

    @Test fun planningNeverTakesOverAName() {
        val want = SmartDomains.desired("edge.example.com", "203.0.113.9", Use.CDN)
        assertTrue(SmartDomains.plan(want, emptyList()) is Action.Create)
        assertTrue(SmartDomains.plan(want, listOf(want.copy(id = "r1"))) is Action.Unchanged)
        val flip = SmartDomains.plan(want, listOf(want.copy(id = "r1", proxied = false)))
        assertTrue(flip is Action.Update && flip.id == "r1")
        assertTrue(SmartDomains.plan(want, listOf(want.copy(id = "r1", content = "198.51.100.1"))) is Action.Conflict)
        assertTrue(SmartDomains.plan(want, listOf(Record("r2", "edge.example.com", "CNAME", "x.workers.dev", true))) is Action.Conflict)
        assertTrue(SmartDomains.plan(want, listOf(want.copy(id = "a"), want.copy(id = "b"))) is Action.Conflict)
        assertTrue("an AAAA next to it is not in the way",
            SmartDomains.plan(want, listOf(Record("r3", "edge.example.com", "AAAA", "2001:db8::1", true))) is Action.Create)
    }

    @Test fun ensureCreatesOnceAndThenLeavesItAlone() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"result":[]}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"result":{"id":"new"}}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"result":[{"id":"new","name":"edge.example.com","type":"A","content":"203.0.113.9","proxied":true}]}"""))
            val api = CloudflareDnsApi(base = server.url("/client/v4").toString().trimEnd('/'))
            val want = SmartDomains.desired("edge.example.com", "203.0.113.9", Use.CDN)
            assertTrue(api.ensure("tok", zones[0], want) is Action.Create)
            val lookup = server.takeRequest()
            assertEquals("Bearer tok", lookup.getHeader("Authorization"))
            assertTrue(lookup.path!!.contains("name=edge.example.com"))
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            val body = create.body.readUtf8()
            assertTrue(body.contains("\"proxied\":true") && body.contains("\"type\":\"A\""))
            assertTrue(api.ensure("tok", zones[0], want) is Action.Unchanged)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun cloudflareErrorsAreShownNotSwallowed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""))
            val api = CloudflareDnsApi(base = server.url("/client/v4").toString().trimEnd('/'))
            val e = runCatching { api.zones("tok") }.exceptionOrNull()
            assertEquals("Authentication error", e?.message)
        }
    }
}
