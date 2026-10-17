package com.example.panels.cloudflare

import com.example.panels.cloudflare.SmartDomains.Use
import com.example.panels.cloudflare.SmartDomains.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Smart domains fills in after the token works. */
class SmartFillTest {
    private fun server(protocol: String, transport: String = "tcp", security: String = "none", port: Int = 443,
                       address: String = "203.0.113.24", country: String? = "US") =
        SmartFill.Server("My server", protocol, transport, security, address, port, country)

    @Test fun cdnForWsXhttpGrpcAndHttpUpgradeOverTlsOnCloudflarePorts() {
        for (t in listOf("ws", "xhttp", "grpc", "httpupgrade", "splithttp")) {
            assertEquals(t, Use.CDN, SmartFill.useFor(server("VLESS", t, "tls")))
        }
        assertEquals(Use.CDN, SmartFill.useFor(server("VMess", "ws", "tls", port = 8443)))
        assertEquals(Use.CDN, SmartFill.useFor(server("Trojan", "grpc", "tls", port = 2053)))
    }

    @Test fun directForEverythingCloudflareCannotCarry() {
        assertEquals(Use.DIRECT, SmartFill.useFor(server("VLESS", "tcp", "reality")))
        assertEquals(Use.DIRECT, SmartFill.useFor(server("VLESS", "xhttp", "reality")))
        assertEquals(Use.DIRECT, SmartFill.useFor(server("Hysteria2", security = "tls", port = 40419)))
        assertEquals(Use.DIRECT, SmartFill.useFor(server("TUIC", security = "tls")))
        assertEquals(Use.DIRECT, SmartFill.useFor(server("WireGuard", port = 51820)))
        assertEquals(Use.DIRECT, SmartFill.useFor(server("SSH", port = 22)))
        assertEquals(Use.DIRECT, SmartFill.useFor(server("VLESS", "tcp", "tls")))
        // CDN-shaped but not over TLS, or on a port Cloudflare's proxy does not carry for TLS.
        assertEquals(Use.DIRECT, SmartFill.useFor(server("VLESS", "ws", "none", port = 80)))
        assertEquals(Use.DIRECT, SmartFill.useFor(server("VLESS", "ws", "tls", port = 8444)))
    }

    @Test fun reasonSaysWhyInPlainWords() {
        val hy = server("Hysteria2", security = "tls", port = 40419)
        val r = SmartFill.reason(hy, SmartFill.useFor(hy))
        assertTrue(r, r.contains("Hysteria2 can't go through Cloudflare's proxy, so the name is DNS only"))
        assertTrue(r.endsWith("Existing records are never overwritten."))
        val odd = server("VLESS", "ws", "tls", port = 8444)
        assertTrue(SmartFill.reason(odd, Use.DIRECT).contains("port 8444 is not one Cloudflare's proxy carries"))
        val ws = server("VLESS", "ws", "tls")
        assertTrue(SmartFill.reason(ws, Use.CDN).contains("VLESS · WebSocket over TLS on port 443"))
        assertTrue(SmartFill.reason(ws, Use.CDN).contains("proxied (orange cloud)"))
    }

    @Test fun titleNamesProtocolTransportAndCountry() {
        assertEquals("Hysteria2 · US", SmartFill.title(server("Hysteria2")))
        assertEquals("VLESS · WebSocket · DE", SmartFill.title(server("VLESS", "ws", "tls", country = "de")))
        assertEquals("VLESS REALITY", SmartFill.title(server("VLESS", "tcp", "reality", country = null)))
        assertEquals("VLESS · gRPC", SmartFill.title(server("VLESS", "grpc", "tls", country = "??")))
    }

    @Test fun fillUsesTheServerAndARandomPrefixedLabel() {
        val f = SmartFill.fill(server("Hysteria2", security = "tls", port = 40419))
        assertNotNull(f)
        f!!
        assertEquals(Use.DIRECT, f.use)
        assertEquals("A", f.recordType)
        assertTrue(f.label, Regex("^r-[a-z2-9]{6}$").matches(f.label))
        assertNull(SmartDomains.labelProblem(f.label))
        assertEquals("A → 203.0.113.24", SmartFill.recordLine(f))
        assertEquals("DNS record (the config keeps its port 40419)", SmartFill.recordCaption(f))
        val cdn = SmartFill.fill(server("VLESS", "ws", "tls"))!!
        assertEquals(Use.CDN, cdn.use)
        assertTrue(cdn.label.startsWith("cdn-"))
        val v6 = SmartFill.fill(server("TUIC", address = "[2001:db8::1]"))!!
        assertEquals("AAAA", v6.recordType)
        assertEquals("2001:db8::1", v6.server.address)
    }

    @Test fun noFillWithoutAServerOrWithAHostName() {
        assertNull(SmartFill.fill(null))
        assertNull(SmartFill.fill(server("VLESS", "ws", "tls", address = "edge.example.com")))
        assertNull(SmartFill.fill(server("VLESS", address = "")))
        assertNull(SmartFill.fill(server("VLESS", port = 0)))
    }

    @Test fun zoneIsTheOnlyActiveOneElseThePickedOne() {
        val a = Zone("a", "example.com", "active")
        val b = Zone("b", "example.net", "active")
        val p = Zone("p", "pending.org", "pending")
        assertEquals(a, SmartFill.zone(listOf(p, a), savedId = "p"))
        assertEquals(b, SmartFill.zone(listOf(a, b), savedId = "b"))
        assertEquals(a, SmartFill.zone(listOf(p, a, b), savedId = ""))
        assertEquals(p, SmartFill.zone(listOf(p), savedId = ""))
        assertNull(SmartFill.zone(emptyList(), savedId = ""))
    }
}
