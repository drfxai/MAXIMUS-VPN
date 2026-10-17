package com.example.panels.cloudflare

import com.example.panels.cloudflare.SmartDomains.Action
import com.example.panels.cloudflare.SmartDomains.Record
import com.example.panels.cloudflare.SmartDomains.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Smart domains screen's rows and outcome text, and the names kept on the phone. */
class SmartNamesTest {
    private val cdn = SmartNames.Saved("z1", "yourdomain.com", "cdn-k7m2qa.yourdomain.com", "A", "203.0.113.24", 443, Use.CDN, true)
    private val direct = SmartNames.Saved("z1", "yourdomain.com", "r-9xh4tw.yourdomain.com", "A", "203.0.113.24", 8443, Use.DIRECT, false)
    private val ns = SmartNames.Saved("z1", "yourdomain.com", "ns-p3vz8e.yourdomain.com", "A", "203.0.113.24", null, Use.DNS_TUNNEL, false)

    @Test fun rowsSayWhatEachNamePublishes() {
        assertEquals("Through Cloudflare · server address hidden · port 443", SmartNames.detail(cdn))
        assertEquals("Direct · publishes 203.0.113.24 · port 8443", SmartNames.detail(direct))
        assertEquals("DNS tunnel name server · publishes 203.0.113.24", SmartNames.detail(ns))
        assertEquals("Proxied", SmartNames.badge(true))
        assertEquals("DNS only", SmartNames.badge(false))
    }

    @Test fun hintListsCloudflarePortsForCdn() {
        assertEquals("Proxied through Cloudflare. Ports Cloudflare carries: 443, 2053, 2083, 2087, 2096, 8443.", SmartNames.hint(Use.CDN))
        assertTrue(SmartNames.hint(Use.DIRECT).startsWith("DNS only"))
    }

    @Test fun prefixesAndChips() {
        assertEquals(listOf("cdn", "r", "ns"), Use.values().map(SmartNames::prefix))
        assertEquals(listOf("Through Cloudflare", "Direct", "DNS tunnel"), Use.values().map(SmartNames::chip))
        assertEquals("Active", SmartNames.zoneStatus("active"))
        assertEquals("Pending", SmartNames.zoneStatus("pending"))
    }

    @Test fun onlyIpAddressesAreAccepted() {
        assertNull(SmartNames.addressProblem("203.0.113.24"))
        assertNull(SmartNames.addressProblem("2001:db8::1"))
        assertNotNull(SmartNames.addressProblem(""))
        assertNotNull(SmartNames.addressProblem("yourdomain.com"))
        assertNotNull(SmartNames.addressProblem("203.0.113"))
        assertNotNull(SmartNames.addressProblem("203.0.113.256"))
        assertNotNull(SmartNames.addressProblem("zz:yy::1"))
    }

    @Test fun portRulesFollowTheUse() {
        assertNull(SmartNames.portFieldProblem(Use.CDN, "443"))
        assertNotNull(SmartNames.portFieldProblem(Use.CDN, "8444"))
        assertNull(SmartNames.portFieldProblem(Use.DIRECT, "8444"))
        assertNotNull(SmartNames.portFieldProblem(Use.DIRECT, ""))
        assertNotNull(SmartNames.portFieldProblem(Use.DIRECT, "70000"))
        assertNull(SmartNames.portFieldProblem(Use.DNS_TUNNEL, ""))
    }

    @Test fun outcomesInPlainWords() {
        val r = Record(null, "cdn-a.yourdomain.com", "A", "203.0.113.24", true)
        assertEquals("Created cdn-a.yourdomain.com.", SmartNames.outcome(r.name, Action.Create(r)))
        assertEquals(
            "cdn-a.yourdomain.com already pointed at this server; proxy turned on.",
            SmartNames.outcome(r.name, Action.Update("id1", r, "proxy turned on"))
        )
        assertEquals("cdn-a.yourdomain.com is already set up. Nothing was changed.", SmartNames.outcome(r.name, Action.Unchanged(r)))
    }

    @Test fun conflictTextMatchesThePlan() {
        val want = SmartDomains.desired("edge.yourdomain.com", "203.0.113.24", Use.CDN)
        val action = SmartDomains.plan(want, listOf(Record("x", "edge.yourdomain.com", "A", "198.51.100.7", false)))
        assertTrue(action is Action.Conflict)
        assertEquals(
            "Already points at 198.51.100.7. Nothing was changed. Pick another name or change it in Cloudflare yourself.",
            SmartNames.conflict(want.name, (action as Action.Conflict).why)
        )
    }

    @Test fun namesRoundTripAndReplaceBySameRecord() {
        val list = listOf(cdn, direct, ns)
        assertEquals(list, SmartNames.fromJson(SmartNames.toJson(list)))
        assertEquals(emptyList<SmartNames.Saved>(), SmartNames.fromJson("not json"))
        val updated = SmartNames.upsert(list, cdn.copy(proxied = false))
        assertEquals(3, updated.size)
        assertEquals(false, updated.single { it.name == cdn.name }.proxied)
        assertEquals(4, SmartNames.upsert(list, cdn.copy(type = "AAAA", address = "2001:db8::1")).size)
    }
}
