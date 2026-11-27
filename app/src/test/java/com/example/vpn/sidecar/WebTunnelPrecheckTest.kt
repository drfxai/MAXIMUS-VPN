package com.example.vpn.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Build 181 3.5: WebTunnel bridges that answer TLS go first; none is dropped. */
class WebTunnelPrecheckTest {
    private val a = "webtunnel [2001:db8::1]:443 0123456789ABCDEF0123456789ABCDEF01234567 url=https://a.example/p ver=0.0.7 addr=192.0.2.10:443"
    private val b = "webtunnel [2001:db8::2]:443 1123456789ABCDEF0123456789ABCDEF01234567 url=https://b.example/p ver=0.0.7 addr=[2001:db8::99]:8443"
    private val c = "webtunnel [2001:db8::3]:443 2123456789ABCDEF0123456789ABCDEF01234567 url=https://c.example/p ver=0.0.7 addr=192.0.2.12:443"

    @Test fun targetsComeFromTheUrlAndThePinnedAddress() {
        assertEquals(Triple("a.example", "192.0.2.10", 443), WebTunnelEndpoints.target(a))
        assertEquals(Triple("b.example", "2001:db8::99", 8443), WebTunnelEndpoints.target(b))
        assertNull(WebTunnelEndpoints.target("webtunnel x y url=https://n.example/"))
    }

    @Test fun answeringBridgesGoFirstAndNoneIsDropped() {
        val (ordered, answered) = WebTunnelEndpoints.precheck(listOf(a, b, c), timeoutMs = 2_000) { host, _, _, _ -> host != "a.example" }
        assertEquals(2, answered)
        assertEquals(setOf(b, c), ordered.take(2).toSet())
        assertEquals(a, ordered.last())
    }

    @Test fun nothingAnswersKeepsTheOrderAndOtherTransportsAreUntouched() {
        val obfs4 = "obfs4 192.0.2.1:443 0123456789ABCDEF0123456789ABCDEF01234567 cert=x iat-mode=0"
        val (ordered, answered) = WebTunnelEndpoints.precheck(listOf(obfs4, a, c), timeoutMs = 1_000) { _, _, _, _ -> false }
        assertEquals(0, answered)
        assertEquals(listOf(obfs4, a, c), ordered)
        assertEquals(listOf(obfs4) to 0, WebTunnelEndpoints.precheck(listOf(obfs4)))
    }
}
