package com.example

import com.example.vpn.EndpointResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class EndpointResolverTest {
    private val host = "maximus-bpb-abc.acme.workers.dev"

    private fun doh(body: String, seen: MutableList<String> = mutableListOf()): (URL) -> HttpURLConnection = { url ->
        seen += url.toString()
        object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        }
    }

    private val answer = """{"Status":0,"Answer":[{"name":"$host","type":5,"data":"x.example."},""" +
        """{"name":"x.example","type":1,"data":"104.21.30.40"}]}"""

    @Test
    fun aPublicSystemAnswerIsUsedAsIs() {
        val seen = mutableListOf<String>()
        val result = EndpointResolver.resolve(host, { listOf(InetAddress.getByName("172.67.1.2")) }, doh(answer, seen))
        assertEquals(EndpointResolver.Result("172.67.1.2", viaDoh = false), result)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aBlockPageAnswerIsReplacedByDnsOverHttps() {
        // Filtering networks answer blocked names with their block page, for example 10.10.34.36.
        val seen = mutableListOf<String>()
        val result = EndpointResolver.resolve(host, { listOf(InetAddress.getByName("10.10.34.36")) }, doh(answer, seen))
        assertEquals(EndpointResolver.Result("104.21.30.40", viaDoh = true), result)
        assertTrue(seen.first().startsWith("https://1.1.1.1/dns-query?name=$host"))
    }

    @Test
    fun aFailedSystemLookupFallsBackToDnsOverHttps() {
        val result = EndpointResolver.resolve(host, { throw java.net.UnknownHostException(it) }, doh(answer))
        assertEquals("104.21.30.40", result.address)
    }

    @Test
    fun privateAndUnspecifiedAddressesCountAsBlocked() {
        listOf("10.10.34.36", "192.168.1.1", "127.0.0.1", "0.0.0.0", "169.254.1.1", "fc00::1")
            .forEach { assertTrue(it, EndpointResolver.isBlockedAnswer(InetAddress.getByName(it))) }
        assertFalse(EndpointResolver.isBlockedAnswer(InetAddress.getByName("104.16.1.2")))
    }
}
