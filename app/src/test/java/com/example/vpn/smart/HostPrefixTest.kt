package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The /24 (IPv4) or /48 (IPv6) stand-in for "same network" when no ASN data is at hand. */
class HostPrefixTest {
    @Test fun ipv4IsKeyedOnItsSlash24() {
        assertEquals("1.2.3.0/24", HostPrefix.of("1.2.3.4"))
        assertEquals("1.2.3.0/24", HostPrefix.of(" 1.2.3.250 "))
        assertNull(HostPrefix.of("1.2.3.256"))
        assertNull(HostPrefix.of("example.com"))
    }

    @Test fun ipv6IsKeyedOnItsSlash48() {
        assertEquals("2a01:4f8:c17::/48", HostPrefix.of("[2a01:4f8:c17:1234::1]"))
        assertEquals("2a01:4f8:c17::/48", HostPrefix.of("2a01:04f8:0c17:ffff:0:0:0:2"))
        assertEquals("0:0:0::/48", HostPrefix.of("::1"))
        assertNull(HostPrefix.of("2a01::4f8::1"))
    }

    @Test fun aNameKeysOnItselfAndOnWhereItResolved() {
        assertEquals(setOf("host:a.example.com", "5.6.7.0/24"), HostPrefix.keys("A.example.com", "5.6.7.8"))
        assertEquals(setOf("host:a.example.com"), HostPrefix.keys("a.example.com"))
        assertEquals(setOf("5.6.7.0/24"), HostPrefix.keys("5.6.7.9", "9.9.9.9"))
        assertTrue(HostPrefix.keys("").isEmpty())
    }

    @Test fun theLogLabelNeverShowsTheHostName() {
        assertEquals("5.6.7.0/24", HostPrefix.label(HostPrefix.keys("a.example.com", "5.6.7.8")))
        assertEquals("unresolved", HostPrefix.label(HostPrefix.keys("a.example.com")))
    }
}
