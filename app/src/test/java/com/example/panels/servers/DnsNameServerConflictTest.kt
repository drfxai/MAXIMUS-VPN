package com.example.panels.servers

import com.example.panels.cloudflare.CloudflareDnsApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Several DNS tunnels on one domain must not all be delegated to whichever server ns.<domain> names. */
class DnsNameServerConflictTest {
    @Test fun sharedNameServerIsTheDefault() {
        val (a, ns) = DnsDelegation.records("example.com", "tun", "192.0.2.10")
        assertEquals("ns.example.com", a.name)
        assertEquals("ns.example.com", ns.value)
    }

    @Test fun ownNameServerIsNamedAfterTheTunnelForEveryDomain() {
        val r = DnsDelegation.records("example.com", "tun", "192.0.2.10", listOf("example.net"), ownNameServer = true)
        assertEquals(listOf("ns-tun.example.com", "tun.example.com", "ns-tun.example.net", "tun.example.net"), r.map { it.name })
        assertEquals("ns-tun.example.com", r[1].value)
        assertEquals("192.0.2.10", r[0].value)
        assertEquals("ns-tun.example.net", r[3].value)
    }

    @Test fun nameServerPointingAtAnotherServerExplainsThatTheTunnelCannotWork() {
        val records = DnsDelegation.records("example.com", "tun", "192.0.2.10")
        val checks = DnsDelegation.judge(records,
            """{"Status":0,"Answer":[{"type":1,"data":"198.51.100.7"}]}""",
            """{"Status":0,"Answer":[{"type":2,"data":"ns.example.com."}]}""")
        assertEquals(DnsDelegation.Status.WRONG, checks[0].status)
        assertTrue(checks[0].detail.contains("198.51.100.7"))
        assertTrue(checks[0].detail.contains("never sees its queries"))
        assertEquals(DnsDelegation.Status.OK, checks[1].status)
    }

    @Test fun cloudflareReportsATakenNameServerWithoutWriting() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"result":[{"id":"z1","name":"example.com","status":"active"}]}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"result":[{"id":"old","name":"ns.example.com","type":"A","content":"198.51.100.7","proxied":false}]}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"result":[]}"""))
            val api = CloudflareDnsApi(base = server.url("/client/v4").toString().trimEnd('/'))
            val err = runCatching { api.provisionDnstt("tok", DnsDelegation.records("example.com", "tun", "192.0.2.10")) }.exceptionOrNull()
            assertTrue(err is CloudflareDnsApi.NameServerTaken)
            err as CloudflareDnsApi.NameServerTaken
            assertEquals("ns.example.com", err.name)
            assertEquals("198.51.100.7", err.address)
            repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test fun cottenAdvertisesTheDelegatedNameServer() {
        assertTrue(DnsServerScripts.cotten("tun.example.com", "amd64").text.contains("ZONE_NS = [\"ns.example.com\"]"))
        assertTrue(DnsServerScripts.cotten("tun.example.com", "amd64", "ns-tun.example.com").text.contains("ZONE_NS = [\"ns-tun.example.com\"]"))
        try { DnsServerScripts.cotten("tun.example.com", "amd64", "ns;id"); fail() } catch (_: IllegalArgumentException) {}
    }
}
