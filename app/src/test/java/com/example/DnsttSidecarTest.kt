package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.DnsttSidecar
import com.example.vpn.sidecar.DnsttSidecar.ResolverKind
import com.example.vpn.sidecar.SidecarContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DnsttSidecarTest {
    private val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun refused(link: String): String {
        try {
            DnsttSidecar.parse(link)
        } catch (e: IllegalArgumentException) {
            return e.message!!
        }
        fail("expected $link to be refused")
        error("unreachable")
    }

    @Test
    fun parsesADohLinkAndBuildsTheCommandLine() {
        val profile = DnsttSidecar.parse("dnstt://$key@t.example.com?doh=https://1.1.1.1/dns-query#My%20tunnel")
        val engine = DnsttSidecar()
        assertTrue(engine.handles(profile))
        assertNull(engine.problem(profile))
        assertEquals("My tunnel", profile.name)
        assertEquals("t.example.com", profile.address)

        val dir = File(System.getProperty("java.io.tmpdir"))
        val context = SidecarContext(dir, File(dir, "libdnstt.so"), 41000, "u", "p", OperationalMode.DAILY)
        val launch = engine.prepare(profile, AppSettings(), context)
        assertEquals(
            listOf(File(dir, "libdnstt.so").absolutePath, "-doh", "https://1.1.1.1/dns-query", "-pubkey", key, "-qps", "4", "t.example.com", "127.0.0.1:41000"),
            launch.command
        )
        assertFalse(launch.socksAuth)
        assertEquals("dns-tunnel", engine.id)
        assertEquals("dnstt", engine.binary)
    }

    @Test
    fun dotAndUdpResolversGetDefaultPortsAndIpv6Brackets() {
        val dot = DnsttSidecar.settings(DnsttSidecar.parse("dnstt://$key@t.example.com?dot=9.9.9.9"))
        assertEquals(ResolverKind.DOT, dot.kind)
        assertEquals("9.9.9.9:853", dot.resolver)
        val udp = DnsttSidecar.settings(DnsttSidecar.parse("dnstt://$key@t.example.com?udp=8.8.8.8:5353&utls=none"))
        assertEquals("8.8.8.8:5353", udp.resolver)
        assertEquals("none", udp.utls)
        assertEquals(
            listOf("x", "-udp", "8.8.8.8:5353", "-pubkey", key, "-utls", "none", "-qps", "4", "t.example.com", "127.0.0.1:1"),
            DnsttSidecar.command(udp, "x", 1)
        )
        val v6 = DnsttSidecar.settings(DnsttSidecar.parse("dnstt://$key@t.example.com?dot=[2606:4700:4700::1111]"))
        assertEquals("[2606:4700:4700::1111]:853", v6.resolver)
    }

    @Test
    fun missingResolverDefaultsToCloudflareDohAndKnownNamesBecomeAddresses() {
        assertEquals("https://1.1.1.1/dns-query", DnsttSidecar.settings(DnsttSidecar.parse("dnstt://$key@t.example.com")).resolver)
        val google = DnsttSidecar.settings(DnsttSidecar.parse("dnstt://$key@t.example.com?doh=https://dns.google/dns-query"))
        assertEquals("https://8.8.8.8/dns-query", google.resolver)
    }

    @Test
    fun refusesHostnameResolvers() {
        assertTrue(refused("dnstt://$key@t.example.com?doh=https://doh.example.net/dns-query").contains("IP address"))
        assertTrue(refused("dnstt://$key@t.example.com?dot=dns.example.net:853").contains("IP address"))
        assertTrue(refused("dnstt://$key@t.example.com?udp=resolver.example.net").contains("IP address"))
        assertTrue(refused("dnstt://$key@t.example.com?doh=http://1.1.1.1/dns-query").contains("https"))
    }

    @Test
    fun refusesBrokenLinks() {
        assertTrue(refused("dnstt://abcd@t.example.com").contains("64 hex"))
        assertTrue(refused("dnstt://$key@localhost").contains("domain"))
        assertTrue(refused("dnstt://t.example.com?doh=https://1.1.1.1/dns-query").contains("@"))
        assertTrue(refused("dnstt://$key@t.example.com?doh=https://1.1.1.1/dns-query&doh=https://1.1.1.1/dns-query").contains("twice"))
        assertTrue(refused("vless://x@y:1").contains("dnstt://"))
        assertNull(DnsttSidecar.parseOrNull("dnstt://$key@t.example.com?utls=a;b"))
    }

    @Test
    fun queryRateStaysUnderFivePerSecond() {
        val slow = DnsttSidecar.parse("dnstt://$key@t.example.com?udp=8.8.8.8&qps=2")
        assertEquals(2, DnsttSidecar.settings(slow).qps)
        assertTrue(DnsttSidecar.toLink(slow).contains("&qps=2"))
        assertEquals(listOf("-qps", "2"), DnsttSidecar.command(DnsttSidecar.settings(slow), "x", 1).let { it.subList(it.indexOf("-qps"), it.indexOf("-qps") + 2) })
        // A profile saved before the cap existed gets the default.
        val old = DnsttSidecar.parse("dnstt://$key@t.example.com")
        assertEquals(DnsttSidecar.DEFAULT_QPS, DnsttSidecar.settings(old).qps)
        assertFalse(DnsttSidecar.toLink(old).contains("qps"))
        for (bad in listOf("5", "6", "0", "-1", "2.5", "fast")) {
            assertTrue(refused("dnstt://$key@t.example.com?qps=$bad").contains("per second"))
        }
    }

    @Test
    fun linkRoundTripsAndBadStoredProfilesReportAProblem() {
        val profile = DnsttSidecar.profile(key.uppercase(), "T.Example.com.", "9.9.9.9", ResolverKind.DOT, "Tunnel #1")
        val link = DnsttSidecar.toLink(profile)
        val again = DnsttSidecar.parse(link)
        assertEquals(DnsttSidecar.settings(profile), DnsttSidecar.settings(again))
        assertEquals("Tunnel #1", again.name)
        assertEquals("t.example.com", DnsttSidecar.settings(profile).domain)

        val engine = DnsttSidecar()
        val broken = ProfileExtras.with(profile, DnsttSidecar.KEY_RESOLVER, "https://doh.example.net/dns-query")
        assertTrue(engine.handles(broken))
        assertNotNull(engine.problem(broken))
        assertFalse(engine.handles(VlessProfile(name = "v", address = "example.com", port = 443, uuid = "x")))
    }

    @Test
    fun severalResolversAreUsedInOrderWithOneRateLimitByDefault() {
        val link = "dnstt://$key@t.example.com?doh=https://1.1.1.1/dns-query&udp=8.8.8.8&dot=9.9.9.9&qps=3"
        val s = DnsttSidecar.settings(DnsttSidecar.parse(link))
        assertEquals(
            listOf(ResolverKind.DOH to "https://1.1.1.1/dns-query", ResolverKind.UDP to "8.8.8.8:53", ResolverKind.DOT to "9.9.9.9:853"),
            s.resolvers.map { it.kind to it.address }
        )
        assertFalse(s.perResolver)
        assertEquals(3, s.totalQps)
        assertEquals(
            listOf("x", "-doh", "https://1.1.1.1/dns-query", "-udp", "8.8.8.8:53", "-dot", "9.9.9.9:853", "-pubkey", key, "-qps", "3",
                "t.example.com", "127.0.0.1:1"),
            DnsttSidecar.command(s, "x", 1)
        )
        // Older builds read the first resolver from the single-resolver keys.
        val extras = ProfileExtras.read(DnsttSidecar.parse(link))
        assertEquals("doh", extras.getString(DnsttSidecar.KEY_RESOLVER_KIND))
        assertEquals("https://1.1.1.1/dns-query", extras.getString(DnsttSidecar.KEY_RESOLVER))
        val again = DnsttSidecar.parse(DnsttSidecar.toLink(DnsttSidecar.parse(link)))
        assertEquals(s, DnsttSidecar.settings(again))
    }

    @Test
    fun perResolverLimitIsOptInAndNeedsTwoResolvers() {
        val two = DnsttSidecar.settings(DnsttSidecar.parse("dnstt://$key@t.example.com?udp=8.8.8.8&udp=1.1.1.1&qps=2&perresolver=1"))
        assertTrue(two.perResolver)
        assertEquals(4, two.totalQps)
        assertTrue(DnsttSidecar.command(two, "x", 1).contains("-qps-per-resolver"))
        val one = DnsttSidecar.settings(DnsttSidecar.parse("dnstt://$key@t.example.com?udp=8.8.8.8&perresolver=1"))
        assertFalse(one.perResolver)
        assertFalse(DnsttSidecar.command(one, "x", 1).contains("-qps-per-resolver"))
        val many = (1..7).joinToString("&") { "udp=10.0.0.$it" }
        assertTrue(refused("dnstt://$key@t.example.com?$many").contains("up to"))
    }

    @Test
    fun backupDomainsGoToTheClientAndRoundTrip() {
        val link = "dnstt://$key@t.example.com?doh=https://1.1.1.1/dns-query&domains=t.example.net,T.Example.org."
        val s = DnsttSidecar.settings(DnsttSidecar.parse(link))
        assertEquals(listOf("t.example.net", "t.example.org"), s.backupDomains)
        val cmd = DnsttSidecar.command(s, "x", 1)
        assertEquals("t.example.net,t.example.org", cmd[cmd.indexOf("-domains") + 1])
        assertEquals("t.example.com", cmd[cmd.size - 2])
        assertEquals(s, DnsttSidecar.settings(DnsttSidecar.parse(DnsttSidecar.toLink(DnsttSidecar.parse(link)))))
        assertTrue(refused("dnstt://$key@t.example.com?domains=t.example.com").contains("repeats"))
        assertTrue(refused("dnstt://$key@t.example.com?domains=localhost").contains("not a valid"))
        assertTrue(refused("dnstt://$key@t.example.com?domains=a.example.net,b.example.net,c.example.net,d.example.net").contains("up to"))
    }

    @Test
    fun editingKeepsOtherExtrasAndOldProfilesStillWork() {
        val base = ProfileExtras.with(DnsttSidecar.parse("dnstt://$key@t.example.com?udp=8.8.8.8"), "note", "keep")
        val s = DnsttSidecar.validate(key, "t.example.com", listOf(ResolverKind.UDP to "8.8.8.8", ResolverKind.UDP to "1.1.1.1"), "", "2",
            listOf("t.example.net"), perResolver = true)
        val edited = DnsttSidecar.withSettings(base, s)
        assertEquals("keep", ProfileExtras.read(edited).optString("note"))
        assertEquals(s, DnsttSidecar.settings(edited))
        assertEquals(base.id, edited.id)
        // Going back to one resolver and no backups drops the extra keys.
        val single = DnsttSidecar.withSettings(edited, DnsttSidecar.validate(key, "t.example.com", ResolverKind.UDP, "8.8.8.8", ""))
        val extras = ProfileExtras.read(single)
        assertFalse(extras.has(DnsttSidecar.KEY_RESOLVERS))
        assertFalse(extras.has(DnsttSidecar.KEY_BACKUP_DOMAINS))
        assertFalse(extras.has(DnsttSidecar.KEY_PER_RESOLVER))
    }
}
