package com.example.vpn.connectivity

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.TransportCapabilityEngine.Admission
import com.example.vpn.connectivity.TransportCapabilityEngine.History
import com.example.vpn.safety.VpnRoutePolicy
import com.example.vpn.smart.NetworkCapabilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 6 test matrix: transport capability and IPv6 safety. */
class TransportCapabilityTest {
    private fun p(id: String, address: String = "cdn.example.org", proto: ProtocolType = ProtocolType.VLESS, security: String = "tls",
                  transport: String = "ws", alpn: String = "") =
        VlessProfile(id = id, name = id, address = address, port = 443, uuid = "u", protocolType = proto, security = security,
            transport = transport, sni = "cdn.example.org", alpn = alpn)

    private val hy2 = p("hy2", proto = ProtocolType.HYSTERIA2, transport = "udp")
    private val wsV4 = p("ws4", address = "104.16.1.1")
    private val wsV6 = p("ws6", address = "2606:4700::1")
    private val reality = p("re", security = "reality", transport = "tcp")
    private val xhttpH3 = p("h3", transport = "xhttp", alpn = "h3")

    private fun caps(udp: Boolean? = null, v4: Boolean? = true, v6: Boolean? = null, history: History = History()) =
        TransportCapabilityEngine.capabilities(NetworkCapabilityProfile(ipv4Available = v4, ipv6Available = v6, udpAvailable = udp), history)

    @Test fun udpAvailableKeepsUdpServersInPlace() {
        assertEquals(listOf("hy2", "ws4"), TransportCapabilityEngine.order(listOf(hy2, wsV4), caps(udp = true)).map { it.id })
    }

    @Test fun udpBlockedSkipsUdpServersInsteadOfHammeringThem() {
        assertEquals(listOf("ws4", "re"), TransportCapabilityEngine.order(listOf(hy2, wsV4, xhttpH3, reality), caps(udp = false)).map { it.id })
        assertEquals(Admission.SKIP, TransportCapabilityEngine.decide(hy2, caps(udp = false)).admission)
    }

    @Test fun udpImpairedByHistoryGoesAfterTcp() {
        val c = caps(udp = null, history = History(udpFailures = 3))
        assertTrue(c.udpImpaired)
        assertEquals(listOf("ws4", "hy2"), TransportCapabilityEngine.order(listOf(hy2, wsV4), c).map { it.id })
        assertFalse(caps(history = History(udpFailures = 3, udpSuccesses = 1)).udpImpaired)
    }

    @Test fun http3ConfigGetsAnH2FallbackOnlyWhereSafe() {
        val fb = TransportCapabilityEngine.h2Fallback(xhttpH3)!!
        assertEquals("h2,http/1.1", fb.alpn)
        assertEquals(TransportCapabilityEngine.Transport.TCP_TLS, TransportCapabilityEngine.transportOf(fb))
        assertNull(TransportCapabilityEngine.h2Fallback(wsV4))
        assertNull(TransportCapabilityEngine.h2Fallback(hy2))
        assertFalse(caps(udp = false).http3 ?: true)
    }

    @Test fun ipv4OnlyNetworkNeverForcesIpv6() {
        assertEquals(listOf("ws4"), TransportCapabilityEngine.order(listOf(wsV6, wsV4), caps(v4 = true, v6 = false)).map { it.id })
    }

    @Test fun ipv6OnlyNetworkSkipsIpv4Literals() {
        assertEquals(listOf("ws6", "dom"), TransportCapabilityEngine.order(listOf(wsV4, wsV6, p("dom")), caps(v4 = false, v6 = true)).map { it.id })
    }

    @Test fun dualStackWithFailingIpv4LetsIpv6Compete() {
        val c = caps(v4 = true, v6 = true, history = History(ipv4Failures = 3, ipv6Successes = 1))
        assertEquals(listOf("ws6", "ws4"), TransportCapabilityEngine.order(listOf(wsV4, wsV6), c).map { it.id })
        assertEquals(listOf("ws4", "ws6"), TransportCapabilityEngine.order(listOf(wsV4, wsV6), caps(v4 = true, v6 = true)).map { it.id })
    }

    @Test fun unmeasuredMeansPossible() {
        val none = TransportCapabilityEngine.capabilities(null)
        assertEquals(listOf("hy2", "ws6", "ws4"), TransportCapabilityEngine.order(listOf(hy2, wsV6, wsV4), none).map { it.id })
    }

    @Test fun everyTunCapturesIpv4AndIpv6SoIpv6CannotLeak() {
        assertTrue(VpnRoutePolicy.capturesEverything(VpnRoutePolicy.ROUTES))
        assertTrue(VpnRoutePolicy.ADDRESSES.any { it.address.contains(':') })
        assertFalse(VpnRoutePolicy.capturesEverything(VpnRoutePolicy.ROUTES.filter { !it.address.contains(':') }))
        // The only DNS server apps see is inside the tunnel's own private subnet.
        assertTrue(VpnRoutePolicy.DNS_SERVER.startsWith("172.19.0."))
        assertTrue(RecoverySecurityGate.isPrivateOrReserved(VpnRoutePolicy.DNS_SERVER))
    }
}
