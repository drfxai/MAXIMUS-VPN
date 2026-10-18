package com.example.vpn.sidecar

import org.junit.Assert.*
import org.junit.Test

class WebTunnelEndpointsTest {
    private val bridge = "webtunnel [2001:db8::1]:443 ABCDEF url=https://bridge.example:8443/private-path ver=0.0.3"

    @Test fun pinsPublicDestinationWithoutChangingTlsHostOrSecretPath() {
        val pinned = WebTunnelEndpoints.pin(bridge) { host ->
            assertEquals("bridge.example", host)
            "93.184.216.34"
        }
        assertEquals("$bridge addr=93.184.216.34:8443", pinned)
    }

    @Test fun refusesThePrivateBlockPageFromThePhoneReport() {
        assertThrows(IllegalArgumentException::class.java) {
            WebTunnelEndpoints.pin(bridge) { "10.10.34.36" }
        }
    }

    @Test fun supportsIpv6WithoutChangingTheUrl() {
        assertEquals("$bridge addr=[2606:4700::1111]:8443",
            WebTunnelEndpoints.pin(bridge) { "2606:4700::1111" })
    }

    @Test fun leavesUserPinnedDestinationsAndOtherTransportsAlone() {
        val pinned = "$bridge addr=93.184.216.34:8443"
        assertEquals(pinned, WebTunnelEndpoints.pin(pinned) { error("must not resolve a user override") })
        assertEquals("snowflake bridge", WebTunnelEndpoints.pin("snowflake bridge") { error("must not resolve Snowflake") })
    }

    @Test fun rejectsAResolverReturningAnotherHostname() {
        assertThrows(IllegalArgumentException::class.java) { WebTunnelEndpoints.pin(bridge) { "block.example" } }
    }
}
