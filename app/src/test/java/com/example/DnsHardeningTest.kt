package com.example

import com.example.vpn.EndpointResolver
import com.example.vpn.connectivity.DnsOutcome
import com.example.vpn.connectivity.DnsResilienceEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class DnsHardeningTest {
    private fun opener(body: String): (URL) -> HttpURLConnection = { url ->
        object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        }
    }

    @Test fun poisonedSystemAnswerIsNeverReturnedWhenDohFails() {
        assertThrows(IllegalStateException::class.java) {
            EndpointResolver.resolve("example.com", { listOf(InetAddress.getByName("10.10.34.36")) },
                opener("""{"Status":2}"""), doh = listOf(EndpointResolver.DOH_ENDPOINTS.first()))
        }
    }

    @Test fun publicAnswerIsSelectedEvenWhenFirstSystemAddressIsBlocked() {
        val result = EndpointResolver.resolve("example.com", {
            listOf(InetAddress.getByName("10.10.34.36"), InetAddress.getByName("1.1.1.1"))
        }, { error("DoH must not be needed") })
        assertEquals("1.1.1.1", result.address)
    }

    @Test fun errorAndTruncatedJsonAnswersAreRejected() {
        for (flags in listOf("\"Status\":2", "\"Status\":0,\"TC\":true")) {
            assertNull(EndpointResolver.parseDohAnswer("""{$flags,"Answer":[{"type":1,"data":"1.1.1.1"}]}"""))
        }
        assertNull(EndpointResolver.parseDohAnswer("""{"Status":0,"Answer":[{"type":1,"data":"999.1.1.1"}]}"""))
    }

    @Test fun excludedResolversGetAnotherChanceAndRemainNetworkScoped() {
        var now = 100_000L
        val engine = DnsResilienceEngine({ null }, {}, { now })
        val available = listOf("a", "b", "c", "d")
        repeat(3) { engine.record("wifi", "a", "x", DnsOutcome.TIMEOUT, null) }
        assertFalse("a" in engine.profile("wifi", available).resolverOrder)
        now += DnsResilienceEngine.RETRY_AFTER_MS
        assertTrue("a" in engine.profile("wifi", available).resolverOrder)
        engine.record("wifi", "a", "x", DnsOutcome.TIMEOUT, null)
        assertFalse("a" in engine.profile("wifi", available).resolverOrder)
        assertEquals(available, engine.profile("cell", available).resolverOrder)
    }

    @Test fun oversizedDohResponseFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            EndpointResolver.resolve("example.com", { emptyList() }, opener("x".repeat(65536)),
                private = true, doh = listOf(EndpointResolver.DOH_ENDPOINTS.first()))
        }
    }
}
