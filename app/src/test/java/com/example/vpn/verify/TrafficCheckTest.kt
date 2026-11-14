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
        assertEquals(TrafficCheck.SLOW_STEP_FLOOR_MS, TrafficCheck.stepTimeoutFor(3_000))
        assertEquals(TrafficCheck.SLOW_STEP_FLOOR_MS, TrafficCheck.stepTimeoutFor(1_920))
        assertEquals(24_000, TrafficCheck.stepTimeoutFor(4_000))
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
        // The first grade (16 KB) went out and was never confirmed: the graded check stops there.
        assertEquals(TrafficCheck.UPLOAD_GRADES.first(), (r as LegResult.Failed).bytes)
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

    private fun graded(
        uploads: List<TrafficCheck.Target>, workerExit: Boolean = false, timeoutMs: Int = 3_000
    ) = TrafficCheck(direct, tls = false, uploads = uploads, small = target("/204"), stepTimeoutMs = timeoutMs,
        random = Random(7), cloudflareWorkerExit = workerExit)

    @Test fun everyUploadGradeIsTriedAndAllPassingIsAFullPass() {
        server.dispatcher = echo()
        server.start()
        val probe = graded(listOf(target("/echo")))
        val r = probe.upload()
        assertTrue(r.toString(), r is LegResult.Passed && r.bytes == TrafficCheck.UPLOAD_BYTES)
        val attempt = probe.lastUploadProbe!!.attempts.single()
        assertEquals(TrafficCheck.UPLOAD_GRADES, attempt.grades.map { it.size })
        assertTrue(attempt.grades.all { it.outcome == UploadProbe.Outcome.OK })
        assertEquals(3, server.requestCount)
        // Bytes written over time: one sample per 16 KB, ending at the full size.
        assertEquals(TrafficCheck.UPLOAD_BYTES, attempt.grades.last().timeline.last().bytes)
        assertEquals(TrafficCheck.UPLOAD_BYTES / TrafficCheck.TIMELINE_STEP, attempt.grades.last().timeline.size)
    }

    @Test fun a256KbStallAfter64KbIsPartialNotASingleFail() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.bodySize > 100_000) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else MockResponse().setBody("{\"data\":\"" + request.body.readUtf8() + "\"}")
        }
        server.start()
        val probe = graded(listOf(target("/echo")), timeoutMs = 1_000)
        val r = probe.upload()
        val p = probe.lastUploadProbe!!
        // 64 KB confirmed is more than the old single 24 KB upload: the leg passes, marked partial.
        assertTrue(r.toString(), r is LegResult.Passed && r.bytes == 65_536)
        assertTrue(p.partial)
        assertEquals(65_536, p.confirmedBytes)
        val miss = p.best!!.firstMiss!!
        assertEquals(262_144, miss.size)
        assertEquals(UploadProbe.Outcome.STALLED, miss.outcome)
        assertEquals(miss.sentBytes, p.stallBytes)
        assertTrue(p.describe(), p.describe().contains("partial: 64 KB ok") && p.describe().contains("256 KB stalled after"))
        val evidence = TrafficEvidence("a", 0, ProbePath.TUN, LegResult.Passed(4096, 10), r, 0L, p)
        assertTrue(evidence.describe(), evidence.describe().contains("verified (64 KB") && evidence.describe().contains("partial"))
        assertEquals(p.stallBytes, evidence.stallBytes)
    }

    @Test fun aCloudflareReferenceIsSkippedBehindAWorkerExit() {
        server.dispatcher = echo()
        server.start()
        val cf = TrafficCheck.Target("127.0.0.1", server.port, "/cf", echoes = false, cloudflare = true)
        val probe = graded(listOf(cf, target("/echo")), workerExit = true)
        assertTrue(probe.upload() is LegResult.Passed)
        val paths = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)!!.path }
        assertTrue(paths.toString(), paths.none { it!!.startsWith("/cf") })
        assertEquals(listOf("127.0.0.1"), probe.lastUploadProbe!!.skipped)
    }

    @Test fun aNonEchoingReferenceIsAskedForEachGradeSizeAndConfirmsBy2xx() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/__up?bytes=")) MockResponse().setResponseCode(200) else MockResponse().setResponseCode(404)
        }
        server.start()
        val up = TrafficCheck.Target("127.0.0.1", server.port, "/__up", echoes = false, cloudflare = true)
        val probe = graded(listOf(up))
        val r = probe.upload()
        assertTrue(r.toString(), r is LegResult.Passed && r.bytes == TrafficCheck.UPLOAD_BYTES)
        val paths = List(3) { server.takeRequest(1, TimeUnit.SECONDS)!!.path }
        assertEquals(TrafficCheck.UPLOAD_GRADES.map { "/__up?bytes=$it" }, paths)
    }

    @Test fun aReferenceThatRefusesTheUploadFallsBackToTheNext() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/first" -> MockResponse().setResponseCode(503)
                else -> MockResponse().setBody("{\"data\":\"" + request.body.readUtf8() + "\"}")
            }
        }
        server.start()
        val probe = graded(listOf(target("/first"), target("/second")))
        assertTrue(probe.upload() is LegResult.Passed)
        val p = probe.lastUploadProbe!!
        assertEquals(2, p.attempts.size)
        assertEquals(UploadProbe.Outcome.NOT_TESTED, p.attempts.first().grades.single().outcome)
        assertEquals(4, server.requestCount)
    }

    @Test fun theDefaultUploadReferencesLeadWithCloudflareThenNonCloudflareEchoes() {
        assertEquals(listOf("speed.cloudflare.com", "httpbingo.org", "httpbin.org"), TrafficCheck.UPLOADS.map { it.host })
        assertTrue(TrafficCheck.UPLOADS.first().cloudflare && !TrafficCheck.UPLOADS.first().echoes)
        assertEquals("/__up", TrafficCheck.UPLOADS.first().path)
        assertTrue(TrafficCheck.UPLOADS.drop(1).none { it.cloudflare })
        assertEquals(listOf(16_384, 65_536, 262_144), TrafficCheck.UPLOAD_GRADES)
    }

    @Test fun aChunkedDownloadIsCountedExactly() {
        server.dispatcher = echo()
        server.start()
        val r = check(downloads = listOf(target("/chunked", 4096))).download()
        assertTrue(r.toString(), r is LegResult.Passed)
    }

    @Test fun chunkedUtf8BodiesAreReadByByteLength() {
        val text = "اتصال برقرار شد"
        server.enqueue(MockResponse().setChunkedBody(text, 3))
        server.start()
        val response = check().exchange(target("/dns"), "GET", null, keepBody = true)
        assertEquals(text, response.body?.toString("UTF-8"))
        assertEquals(text.toByteArray(Charsets.UTF_8).size, response.bodyBytes)
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

    @Test fun accountJsonHeadersAndResponseStatusSurviveAuthenticatedTls() {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "9").setBody("{}"))
        server.start()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        val probe = TrafficCheck(direct, stepTimeoutMs = 3000, sslFactory = { client.sslSocketFactory() },
            hostnameVerifier = okhttp3.internal.tls.OkHostnameVerifier)
        val response = probe.exchange(TrafficCheck.Target("localhost", server.port, "/v0a4005/reg"), "POST",
            "{}".toByteArray(), true, mapOf("Authorization" to "Bearer test-only", "Content-Type" to "application/json", "CF-Client-Version" to "test-only"))
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("Bearer test-only", request.getHeader("Authorization"))
        assertEquals("{}", request.body.readUtf8())
        assertEquals(429, response.status); assertEquals("9", response.headers["retry-after"])
        assertEquals(1, server.requestCount)
    }

    @Test fun aCertificateForAnotherNameIsAFailureNeverAPass() {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("other.example").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))))
        server.start()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        var tlsVerified = false
        val check = TrafficCheck(direct, tls = true, downloads = listOf(TrafficCheck.Target("localhost", server.port, "/bytes", 4096)),
            stepTimeoutMs = 3_000, sslFactory = { client.sslSocketFactory() },
            hostnameVerifier = okhttp3.internal.tls.OkHostnameVerifier, onTlsVerified = { tlsVerified = true })
        val r = check.download()
        assertTrue(!tlsVerified)
        assertTrue(r.toString(), r is LegResult.Failed && r.stage == FailureStage.CERTIFICATE_VALIDATION_FAILED)
    }

    @Test fun aValidCertificateForTheNamePasses() {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))))
        server.start()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        var tlsVerified = false
        val check = TrafficCheck(direct, tls = true, downloads = listOf(TrafficCheck.Target("localhost", server.port, "/bytes", 4096)),
            stepTimeoutMs = 3_000, sslFactory = { client.sslSocketFactory() },
            hostnameVerifier = okhttp3.internal.tls.OkHostnameVerifier, onTlsVerified = { tlsVerified = true })
        assertTrue(check.download() is LegResult.Passed)
        assertTrue(tlsVerified)
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
