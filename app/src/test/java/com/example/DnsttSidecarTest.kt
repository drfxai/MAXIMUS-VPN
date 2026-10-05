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
            listOf(File(dir, "libdnstt.so").absolutePath, "-doh", "https://1.1.1.1/dns-query", "-pubkey", key, "t.example.com", "127.0.0.1:41000"),
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
            listOf("x", "-udp", "8.8.8.8:5353", "-pubkey", key, "-utls", "none", "t.example.com", "127.0.0.1:1"),
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
        assertTrue(refused("dnstt://$key@t.example.com?doh=https://1.1.1.1/dns-query&udp=1.1.1.1").contains("only one"))
        assertTrue(refused("vless://x@y:1").contains("dnstt://"))
        assertNull(DnsttSidecar.parseOrNull("dnstt://$key@t.example.com?utls=a;b"))
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
}
