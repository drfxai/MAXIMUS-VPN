package com.example.vpn.safety

import com.example.data.model.OperationalMode
import org.junit.Assert.*
import org.junit.Test

class DnsKillSwitchRegressionTest {
    @Test fun validDohLiteralsAndKnownBootstrapAliases() {
        assertTrue(DnsResolvers.isUsable("https://dns.google/dns-query"))
        assertEquals("https://8.8.8.8/dns-query", DnsResolvers.literal("https://dns.google/dns-query"))
        assertTrue(DnsResolvers.isUsable("1.1.1.1"))
        assertTrue(DnsResolvers.isUsable("https://[2606:4700:4700::1111]/dns-query"))
    }

    @Test fun rejectAmbiguousOrMalformedIpv4Ipv6AndUntrustedHostname() {
        listOf(
            "", "example.com", "https://resolver.example/dns-query",
            "https://user@dns.google/dns-query",
            "https://dns.google/dns-query#fragment",
            "https://1.1.1.1:65536/dns-query",
            "https://1.1.1.1.evil.invalid/dns-query",
            "001.1.1.1", "1.2.3.999", "1.2.3", "1.2.3.-1",
            "fe80::1%eth0", "2001:db8:::1", "abcd", "http://1.1.1.1/dns-query",
            "1.1.1.1:53"
        ).forEach { resolver ->
            assertFalse("Unsafe resolver admitted: $resolver", DnsResolvers.isUsable(resolver))
        }
    }

    @Test fun explicitKillSwitchCannotBeRelaxedByDailyModeOnInvalidProfile() {
        assertTrue(FailClosedPolicy.mayReleaseBlock(
            FailClosedPolicy.Failure.INVALID_PROFILE, true, OperationalMode.DAILY))
        assertFalse(FailClosedPolicy.mayReleaseBlock(
            FailClosedPolicy.Failure.INVALID_PROFILE, true, OperationalMode.DAILY, true))
        assertFalse(FailClosedPolicy.mayReleaseBlock(
            FailClosedPolicy.Failure.UNRESOLVABLE_SERVER, true, OperationalMode.DAILY))
        assertFalse(FailClosedPolicy.mayReleaseBlock(
            FailClosedPolicy.Failure.INVALID_PROFILE, false, OperationalMode.DAILY))
        assertFalse(FailClosedPolicy.mayReleaseBlock(
            FailClosedPolicy.Failure.INVALID_PROFILE, true, OperationalMode.GOD_MODE))
    }
}
