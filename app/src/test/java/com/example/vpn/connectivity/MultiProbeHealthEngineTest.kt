package com.example.vpn.connectivity

import com.example.vpn.connectivity.MultiProbeHealthEngine.Companion.step
import com.example.vpn.diagnostics.FailureStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 1 test matrix: multi-probe health. */
class MultiProbeHealthEngineTest {
    private val subject = MultiProbeHealthEngine.Subject("s1", "fp1", "xray", "wifi:v4=1")

    private fun TestScope.engine(budget: ProbeBudget = ProbeBudget(retryBackoffMs = 100, maxBackoffMs = 400)) =
        MultiProbeHealthEngine(budget) { testScheduler.currentTime }

    private fun ok(s: ProbeStep, ms: Long = 10) = step(s) { delay(ms); ms }
    private fun fail(s: ProbeStep, e: Exception) = step(s) { throw e }

    @Test fun icmpBlockedButTcpWorkingIsHealthy() = runTest {
        // There is no ICMP step at all: a server that drops ping is judged by the steps that carry traffic.
        val r = engine().run(subject, listOf(ok(ProbeStep.TCP_CONNECT), ok(ProbeStep.TLS_HANDSHAKE), ok(ProbeStep.PROTOCOL_HANDSHAKE)))
        assertTrue(r.passed)
        assertEquals(ProbeVerdict.PROTOCOL_OK, r.verdict)
        assertTrue(ProbeStep.entries.none { it.name.contains("ICMP") || it.name.contains("PING") })
    }

    @Test fun tcpWorkingTlsFailingNamesTls() = runTest {
        val r = engine().run(subject, listOf(ok(ProbeStep.TCP_CONNECT), fail(ProbeStep.TLS_HANDSHAKE, javax.net.ssl.SSLException("reset during handshake"))))
        assertFalse(r.passed)
        assertEquals(ProbeStep.TLS_HANDSHAKE, r.failedStep)
        assertEquals(FailureStage.TLS_HANDSHAKE_FAILED, r.failureStage)
        assertEquals(ProbeVerdict.BLOCKED, r.verdict)
        // TLS failures are retried within the budget.
        assertEquals(2, r.results.last().retryCount)
    }

    @Test fun refusedCertificateIsNotRetriedAndIsInvalid() = runTest {
        var calls = 0
        val cert = step(ProbeStep.TLS_HANDSHAKE) { calls++; throw javax.net.ssl.SSLHandshakeException("x").apply { initCause(java.security.cert.CertificateException("bad")) } }
        val r = engine().run(subject, listOf(ok(ProbeStep.TCP_CONNECT), cert))
        assertEquals(1, calls)
        assertEquals(FailureStage.CERTIFICATE_VALIDATION_FAILED, r.failureStage)
        assertEquals(ProbeVerdict.INVALID, r.verdict)
    }

    @Test fun tlsWorkingProtocolHandshakeFailing() = runTest {
        val r = engine().run(subject, listOf(ok(ProbeStep.TLS_HANDSHAKE), fail(ProbeStep.PROTOCOL_HANDSHAKE, IllegalStateException("bad response"))))
        assertEquals(ProbeStep.PROTOCOL_HANDSHAKE, r.failedStep)
        assertEquals(FailureStage.PROXY_HANDSHAKE_FAILED, r.failureStage)
        assertEquals("PROTOCOL_HANDSHAKE_FAILED", r.failureStage!!.taxonomyName)
    }

    @Test fun tunnelUpButHttpFailingIsDegraded() = runTest {
        val r = engine().run(subject, listOf(ok(ProbeStep.ENGINE_START), ok(ProbeStep.TUN_ESTABLISH),
            fail(ProbeStep.HTTP_THROUGH_TUNNEL, java.io.IOException("unexpected end of stream"))))
        assertEquals(ProbeStep.HTTP_THROUGH_TUNNEL, r.failedStep)
        assertEquals(ProbeVerdict.DEGRADED, r.verdict)
        assertEquals("HTTP_CONNECTIVITY_FAILED", r.failureStage!!.taxonomyName)
    }

    @Test fun httpWorkingDnsThroughTunnelFailing() = runTest {
        val r = engine().run(subject, listOf(ok(ProbeStep.TUN_ESTABLISH), ok(ProbeStep.HTTP_THROUGH_TUNNEL),
            fail(ProbeStep.DNS_THROUGH_TUNNEL, java.net.UnknownHostException("example.org"))))
        assertEquals(FailureStage.DNS_TUNNEL_FAILED, r.failureStage)
        assertEquals(ProbeVerdict.DEGRADED, r.verdict)
    }

    @Test fun networkChangeDuringProbeMakesTheResultStale() = runTest {
        var generation = 1
        val r = engine().run(subject, listOf(
            step(ProbeStep.TCP_CONNECT) { generation = 2; 5L },
            ok(ProbeStep.TLS_HANDSHAKE)
        ), freshness = { generation == 1 })
        assertTrue(r.stale)
        assertFalse(r.passed)
        assertEquals(1, r.results.size)
        assertTrue(r.assessment().contains("discarded"))
    }

    @Test fun staleResultIsNeverAPass() = runTest {
        val r = engine().run(subject, listOf(ok(ProbeStep.HTTP_THROUGH_TUNNEL)), freshness = { false })
        assertTrue(r.stale)
        assertFalse(r.passed)
        assertTrue(r.results.isEmpty())
    }

    @Test fun aSlowStepTimesOutWithinTheBudget() = runTest {
        val budget = ProbeBudget(perStepTimeoutMs = 1_000, perConfigTimeoutMs = 3_000, maxRetries = 5, retryBackoffMs = 100, maxBackoffMs = 400)
        val r = engine(budget).run(subject, listOf(step(ProbeStep.TCP_CONNECT) { delay(60_000); 1L }))
        assertEquals(FailureStage.TIMEOUT, r.failureStage)
        assertEquals(ProbeVerdict.TIMEOUT, r.verdict)
        // The whole config stays within its time limit even with retries left.
        assertTrue(testScheduler.currentTime <= 3_000)
    }

    @Test fun aFlakyStepPassesOnRetryAndSaysSo() = runTest {
        var n = 0
        val r = engine().run(subject, listOf(step(ProbeStep.HTTP_THROUGH_TUNNEL) { if (++n < 2) throw java.io.IOException("reset") else 42L }))
        assertTrue(r.passed)
        assertEquals(1, r.results.single().retryCount)
        assertEquals(42L, r.results.single().valueMs)
        assertTrue(r.observation().contains("after 2 tries"))
    }

    @Test fun securityRejectionStopsBeforeAnyNetworkStep() = runTest {
        var touched = false
        val r = engine().run(subject, listOf(step(ProbeStep.DNS_RESOLUTION) { touched = true; 1L }), securityProblem = { "no encryption" })
        assertFalse(touched)
        assertEquals(ProbeVerdict.SECURITY_REJECTED, r.verdict)
        assertFalse(r.passed)
    }

    @Test fun stabilityNeedsMostSamplesToPass() = runTest {
        var i = 0
        val flaky = MultiProbeHealthEngine.stability(samples = 4, intervalMs = 100, minSuccesses = 3) { if (i++ % 2 == 0) 100L else throw java.io.IOException("x") }
        val r = engine(ProbeBudget(maxRetries = 0)).run(subject, listOf(flaky))
        assertEquals(ProbeStep.STABILITY, r.failedStep)
        var j = 0
        val steady = MultiProbeHealthEngine.stability(samples = 3, intervalMs = 100, minSuccesses = 3) { 200L + 10 * j++ }
        val s = engine().run(subject, listOf(steady))
        assertTrue(s.passed)
        assertEquals(210L, s.results.single().valueMs)
    }

    @Test fun resultsCarryTheSubjectButNoAddress() = runTest {
        val r = engine().run(subject, listOf(ok(ProbeStep.TCP_CONNECT, 30)))
        val p = r.results.single()
        assertEquals("fp1", p.configFingerprint)
        assertEquals("wifi:v4=1", p.networkProfileId)
        assertEquals(30L, p.duration)
        assertNull(p.failureReason)
    }

    @Test fun budgetBacksOffExponentiallyAndCaps() {
        val b = ProbeBudget(retryBackoffMs = 500, maxBackoffMs = 3_000)
        assertEquals(listOf(0L, 500L, 1_000L, 2_000L, 3_000L, 3_000L), (0..5).map { b.backoffMs(it) })
    }

    @Test fun budgetShrinksOnMeteredAndLowBattery() {
        val b = ProbeBudget(maxConcurrentConfigProbes = 4)
        assertEquals(4, b.configConcurrency(metered = false, batteryPercent = 80, charging = false))
        assertEquals(1, b.configConcurrency(metered = true, batteryPercent = 80, charging = false))
        assertEquals(1, b.configConcurrency(metered = false, batteryPercent = 10, charging = false))
        assertEquals(4, b.configConcurrency(metered = false, batteryPercent = 10, charging = true))
    }

    @Test fun failureTaxonomyRecognisesCertificateTexts() {
        assertEquals(FailureStage.CERTIFICATE_VALIDATION_FAILED, FailureStage.fromText("x509: certificate signed by unknown authority"))
        assertEquals(FailureStage.TLS_HANDSHAKE_FAILED, FailureStage.fromText("remote error: tls: handshake failure"))
        assertEquals(FailureStage.TLS_HANDSHAKE_FAILED, FailureStage.of(javax.net.ssl.SSLHandshakeException("bad cert")))
    }
}
