package com.example.panels.cloudflare

import com.example.panels.servers.DnsDelegation
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CloudflareDnsttProvisionTest {
    private fun ok(json: String) = MockResponse().setBody(json)
    private val zone = """{"success":true,"result":[{"id":"z1","name":"example.com","status":"active"}]}"""
    private val empty = """{"success":true,"result":[]}"""
    private fun created(id: String) = """{"success":true,"result":{"id":"$id"}}"""

    @Test fun createsOnlyDnsOnlyNameServerAndNs() {
        MockWebServer().use { server ->
            server.enqueue(ok(zone))
            server.enqueue(ok(empty))
            server.enqueue(ok(empty))
            server.enqueue(ok(created("a1")))
            server.enqueue(ok(created("ns1")))
            val api = CloudflareDnsApi(base = server.url("/client/v4").toString().trimEnd('/'))
            val count = api.provisionDnstt("limited-token", DnsDelegation.records("example.com", "t", "203.0.113.9"))
            assertEquals(2, count)
            assertEquals("GET", server.takeRequest().method)
            repeat(2) { assertEquals("GET", server.takeRequest().method) }
            val a = server.takeRequest()
            val ns = server.takeRequest()
            assertEquals("POST", a.method)
            assertEquals("POST", ns.method)
            assertEquals("Bearer limited-token", ns.getHeader("Authorization"))
            assertTrue(a.body.readUtf8().contains("123456789-does-not-exist").not())
            assertTrue(ns.body.readUtf8().contains("\"type\":\"NS\""))
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun failedNsCreationRollsBackOnlyOwnNewRecord() {
        MockWebServer().use { server ->
            server.enqueue(ok(zone))
            server.enqueue(ok(empty))
            server.enqueue(ok(empty))
            server.enqueue(ok(created("my-a-id")))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"errors":[{"message":"Denied"}]}"""))
            server.enqueue(ok("""{"success":true,"result":{}}"""))
            val api = CloudflareDnsApi(base = server.url("/client/v4").toString().trimEnd('/'))
            val err = runCatching {
                api.provisionDnstt("tok", DnsDelegation.records("example.com", "t", "203.0.113.10"))
            }.exceptionOrNull()
            assertNotNull(err)
            repeat(5) { server.takeRequest() }
            val undo = server.takeRequest()
            assertEquals("DELETE", undo.method)
            assertTrue(undo.path!!.endsWith("/dns_records/my-a-id"))
        }
    }

    @Test fun conflictPreflightNeverPostsPartialRecords() {
        MockWebServer().use { server ->
            server.enqueue(ok(zone))
            server.enqueue(ok("""{"success":true,"result":[{"id":"used","name":"ns.example.com","type":"A","content":"198.51.100.1","proxied":false}]}"""))
            server.enqueue(ok(empty))
            val api = CloudflareDnsApi(base = server.url("/client/v4").toString().trimEnd('/'))
            assertNotNull(runCatching {
                api.provisionDnstt("tok", DnsDelegation.records("example.com", "t", "203.0.113.10"))
            }.exceptionOrNull())
            assertEquals(3, server.requestCount)
        }
    }
}
