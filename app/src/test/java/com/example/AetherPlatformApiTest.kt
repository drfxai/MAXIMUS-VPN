package com.example

import com.example.vpn.sidecar.AetherPlatformApi
import com.example.vpn.verify.Socks5
import com.example.vpn.verify.TrafficCheck
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AetherPlatformApiTest {
    @Test fun registrationPayloadAndAuthorizationArePreservedAndAdapterTokenIsNotForwarded() {
        val count = AtomicInteger()
        AetherPlatformApi(Socks5.Endpoint("127.0.0.1", 1), exchange = { host, path, method, headers, body ->
            count.incrementAndGet()
            assertEquals("api.cloudflareclient.com", host); assertEquals("/v0a4005/reg", path)
            assertEquals("POST", method); assertEquals("Bearer test-only", headers["authorization"])
            assertEquals("application/json", headers["content-type"])
            assertFalse(headers.containsKey("x-maximus-api-token"))
            assertEquals("{\"key\":\"test-only\"}", body!!.toString(Charsets.UTF_8))
            TrafficCheck.Response(429, 2, ByteArrayOutputStream().apply { write("{}".toByteArray()) }, mapOf("retry-after" to "9"))
        }).use { api ->
            val response = request(api, api.token, "api.cloudflareclient.com")
            assertTrue(response.startsWith("HTTP/1.1 429")); assertTrue(response.contains("Retry-After: 9"))
            assertEquals(1, count.get())
        }
    }
    @Test fun wrongTokenAndArbitraryHostsNeverReachTheNetwork() {
        val count = AtomicInteger()
        AetherPlatformApi(Socks5.Endpoint("127.0.0.1", 1), exchange = { _, _, _, _, _ ->
            count.incrementAndGet(); error("Forbidden adapter request reached the network")
        }).use { api ->
            assertTrue(request(api, "wrong-token", "api.cloudflareclient.com").startsWith("HTTP/1.1 502"))
            assertTrue(request(api, api.token, "example.com").startsWith("HTTP/1.1 502"))
            assertEquals(0, count.get())
        }
    }
    private fun request(api: AetherPlatformApi, token: String, host: String): String {
        val port = api.address.substringAfter(':').toInt()
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val body = "{\"key\":\"test-only\"}"
            socket.getOutputStream().write(("POST /v0a4005/reg HTTP/1.1\r\nHost: $host\r\nX-Maximus-Api-Token: $token\r\n" +
                "Authorization: Bearer test-only\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
}
