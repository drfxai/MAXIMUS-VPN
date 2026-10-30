package com.example.vpn.verify

import com.example.vpn.diagnostics.FailureStage
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.TimeUnit

/** Real transfers against a local server: byte-exact download, echoed upload, stalls, refused certificates. */
class TrafficCheckTest {
    private lateinit var server: MockWebServer
    private val direct = Dialer { host, port, timeoutMs -> Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) } }

    @Before fun start() { server = MockWebServer() }
    @After fun stop() { runCatching { server.shutdown() } }

    private fun target(path: String, bytes: Int = 0) = TrafficCheck.Target("127.0.0.1", server.port, path, bytes)

    private fun check(
        downloads: List<TrafficCheck.Target> = listOf(target("/bytes", 4096)),
        uploads: List<TrafficCheck.Target> = listOf(target("/echo")),
        timeoutMs: Int = 3_000
    ) = TrafficCheck(direct, tls = false, downloads = downloads, uploads = uploads, small = target("/204"),
        stepTimeoutMs = timeoutMs, random = Random(7))

    private fun echo() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/204" -> MockResponse().setResponseCode(204)
            "/bytes" -> MockResponse().setBody(Buffer().write(ByteArray(4096)))
            "/echo" -> MockResponse().setBody("{\"data\":\"" + request.body.readUtf8() + "\"}")
            "/chunked" -> MockResponse().setChunkedBody(Buffer().write(ByteArray(4096)), 1000)
            else -> MockResponse().setResponseCode(404)
        }
    }

    @Test fun slowRoutesGetMoreTimePerTransfer() {
        assertEquals(TrafficCheck.STEP_TIMEOUT_MS, TrafficCheck.stepTimeoutFor(null))
        assertEquals(TrafficCheck.STEP_TIMEOUT_MS, TrafficCheck.stepTimeoutFor(300))
        assertEquals(18_000, TrafficCheck.stepTimeoutFor(3_000))
        assertEquals(30_000, TrafficCheck.stepTimeoutFor(9_000))
    }

    @Test fun aFailedSmallReferenceDoesNotRejectAWorkingRoute() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val probe = TrafficCheck(direct, tls = false, small = target("/first"),
            smallFallbacks = listOf(target("/second")), stepTimeoutMs = 1_000)
        assertTrue(probe.smallRequest() >= 1)
        assertEquals(2, server.requestCount)
    }

    @Test fun redirectsDoNotCountAsVerifiedConnectivity() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://login.example"))
        server.start()
        assertTrue(runCatching { check().smallRequest() }.isFailure)
    }

    @Test fun fullLocalUploadWithoutReplyDoesNotProveAnMtuBlackHole() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val r = check(timeoutMs = 1_000).upload()
        assertTrue(r.toString(), r is LegResult.Failed && !r.stalled)
        assertEquals(TrafficCheck.UPLOAD_BYTES, (r as LegResult.Failed).bytes)
        assertTrue(r.reason.contains("remote receipt unconfirmed"))
    }

    @Test fun aWorkingRouteIsVerifiedBothWays() {
        server.dispatcher = echo()
        server.start()
        val evidence = check().both("a1", 0, ProbePath.TUN)
        assertTrue(evidence.describe(), evidence.bidirectional)
        assertEquals(4096, (evidence.download as LegResult.Passed).bytes)
        assertEquals(TrafficCheck.UPLOAD_BYTES, (evidence.upload as LegResult.Passed).bytes)
    }

    @Test fun aChunkedDownloadIsCountedExactly() {
        server.dispatcher = echo()
        server.start()
        val r = check(downloads = listOf(target("/chunked", 4096))).download()
        assertTrue(r.toString(), r is LegResult.Passed)
    }

    @Test fun aShortDownloadIsAFailureNotAPass() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1000))))
        server.start()
        val r = check().download()
        assertTrue(r.toString(), r is LegResult.Failed && !r.stalled)
    }

    @Test fun aDownloadThatStopsPartWayIsReportedAsStalled() {
        // The headers and the first bytes arrive, then nothing more before the deadline.
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))).throttleBody(1024, 5, TimeUnit.SECONDS))
        server.start()
        val r = check(timeoutMs = 1_500).download()
        assertTrue(r.toString(), r is LegResult.Failed && r.stalled && r.stage == FailureStage.TIMEOUT && r.bytes in 1..4095)
    }

    @Test fun anUploadWithoutTheEchoedMarkerIsNotAPass() {
        server.enqueue(MockResponse().setBody("{\"data\":\"truncated\"}"))
        server.start()
        val r = check().upload()
        assertTrue(r.toString(), r is LegResult.NotTested)
    }

    @Test fun aReferenceErrorIsNotTestedAndTheNextReferenceIsTried() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))))
        server.start()
        val r = check(downloads = listOf(target("/bytes", 4096), target("/bytes", 4096))).download()
        assertTrue(r.toString(), r is LegResult.Passed)
        assertEquals(2, server.requestCount)
    }

    @Test fun aSilentServerTimesOutWithinTheBound() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val started = System.currentTimeMillis()
        val r = check(timeoutMs = 1_000).download()
        assertTrue(r.toString(), r is LegResult.Failed && r.stage == FailureStage.TIMEOUT && !r.stalled)
        assertTrue(System.currentTimeMillis() - started < 4_000)
    }

    @Test fun theSmallRequestReturnsItsRoundTrip() {
        server.dispatcher = echo()
        server.start()
        assertTrue(check().smallRequest() >= 1)
    }

    @Test fun aCertificateForAnotherNameIsAFailureNeverAPass() {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("other.example").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))))
        server.start()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        val check = TrafficCheck(direct, tls = true, downloads = listOf(TrafficCheck.Target("localhost", server.port, "/bytes", 4096)),
            stepTimeoutMs = 3_000, sslFactory = { client.sslSocketFactory() },
            hostnameVerifier = okhttp3.internal.tls.OkHostnameVerifier)
        val r = check.download()
        assertTrue(r.toString(), r is LegResult.Failed && r.stage == FailureStage.CERTIFICATE_VALIDATION_FAILED)
    }

    @Test fun aValidCertificateForTheNamePasses() {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))))
        server.start()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        val check = TrafficCheck(direct, tls = true, downloads = listOf(TrafficCheck.Target("localhost", server.port, "/bytes", 4096)),
            stepTimeoutMs = 3_000, sslFactory = { client.sslSocketFactory() },
            hostnameVerifier = okhttp3.internal.tls.OkHostnameVerifier)
        assertTrue(check.download() is LegResult.Passed)
    }

    @Test fun anUntrustedCertificateIsRefused() {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))))
        server.start()
        // The client trusts a different authority: the interception case.
        val other = HeldCertificate.Builder().certificateAuthority(0).build()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(other.certificate).build()
        val check = TrafficCheck(direct, tls = true, downloads = listOf(TrafficCheck.Target("localhost", server.port, "/bytes", 4096)),
            stepTimeoutMs = 3_000, sslFactory = { client.sslSocketFactory() },
            hostnameVerifier = okhttp3.internal.tls.OkHostnameVerifier)
        val r = check.download()
        assertTrue(r.toString(), r is LegResult.Failed && r.stage == FailureStage.CERTIFICATE_VALIDATION_FAILED)
    }
}
