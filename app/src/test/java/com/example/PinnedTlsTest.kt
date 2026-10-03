package com.example

import com.example.panels.PinnedTls
import com.example.panels.XuiApiClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PinnedTlsTest {

    private fun httpsServer(cert: HeldCertificate): MockWebServer {
        val server = MockWebServer()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server.useHttps(handshake.sslSocketFactory(), false)
        server.start()
        return server
    }

    private fun selfSigned() = HeldCertificate.Builder().commonName("Maximus Panel").build()

    private fun fingerprintOf(cert: HeldCertificate): String =
        PinnedTls.sha256Hex(cert.certificate.encoded)

    @Test
    fun trustsOnlyTheCertificateWhoseFingerprintWasRecorded() {
        val cert = selfSigned()
        val server = httpsServer(cert)
        try {
            server.enqueue(MockResponse().setBody("ok"))
            val client = PinnedTls.client(OkHttpClient(), "SHA256:" + fingerprintOf(cert))
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                assertEquals("ok", it.body?.string())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsAnotherSelfSignedCertificate() {
        val server = httpsServer(selfSigned())
        try {
            server.enqueue(MockResponse().setBody("secret"))
            val client = PinnedTls.client(OkHttpClient(), fingerprintOf(selfSigned()))
            try {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
                fail("a certificate with a different fingerprint must be rejected")
            } catch (e: javax.net.ssl.SSLException) {
                assertTrue(e.toString(), e.toString().contains("pinned", ignoreCase = true))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun apiClientUsesThePinAndReportsAMismatchClearly() {
        val cert = selfSigned()
        val server = httpsServer(cert)
        try {
            val body = """{"success":true,"obj":[{"port":443}]}"""
            server.enqueue(MockResponse().setBody(body))
            val good = XuiApiClient(server.url("/base/panel/").toString(), apiToken = "t", pinnedCertSha256 = fingerprintOf(cert))
            assertEquals(setOf(443), good.usedPorts())

            val bad = XuiApiClient(server.url("/base/panel/").toString(), apiToken = "t", pinnedCertSha256 = fingerprintOf(selfSigned()))
            try {
                bad.usedPorts()
                fail("pin mismatch must surface")
            } catch (e: com.example.panels.XuiApiException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("certificate"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aPanelStillOnPlainHttpIsReportedAsSuch() {
        // Like Go's net/http, answer a TLS ClientHello with a plain-text 400.
        val server = java.net.ServerSocket(0)
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                while (true) {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(512))
                        socket.getOutputStream().write(
                            "HTTP/1.0 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray()
                        )
                        socket.getOutputStream().flush()
                    }
                }
            }
        }
        try {
            val api = XuiApiClient(
                "https://127.0.0.1:${server.localPort}/base/panel/",
                apiToken = "t",
                pinnedCertSha256 = "ab".repeat(32)
            )
            try {
                api.usedPorts()
                fail("must fail")
            } catch (e: com.example.panels.XuiApiException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("plain HTTP"))
            }
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun normalisesFingerprintNotations() {
        assertEquals("ab".repeat(32), PinnedTls.normalize("SHA256:" + "AB".repeat(32)))
        assertEquals("ab".repeat(32), PinnedTls.normalize((1..32).joinToString(":") { "AB" }))
    }
}
