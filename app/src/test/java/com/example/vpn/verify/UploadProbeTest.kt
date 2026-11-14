package com.example.vpn.verify

import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.verify.UploadProbe.Grade
import com.example.vpn.verify.UploadProbe.HostAttempt
import com.example.vpn.verify.UploadProbe.Outcome
import com.example.vpn.verify.UploadProbe.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The graded upload's verdict, stall count and report lines, and the server label of the bulk test line. */
class UploadProbeTest {
    private fun ok(size: Int) = Grade(size, Outcome.OK, size, 500, listOf(Sample(500, size)))
    private fun stalled(size: Int, at: Int) =
        Grade(size, Outcome.STALLED, at, 20_000, listOf(Sample(400, 16_384), Sample(9_800, at)), FailureStage.TIMEOUT)

    @Test fun only16KbConfirmedIsAPartialFailureWithItsStallCount() {
        val p = UploadProbe(listOf(HostAttempt("speed.cloudflare.com", listOf(ok(16_384), stalled(65_536, 32_768)))))
        val leg = p.toLeg()
        assertTrue(leg.toString(), leg is LegResult.Failed && !leg.stalled && leg.bytes == 32_768)
        assertTrue((leg as LegResult.Failed).reason, leg.reason.contains("16 KB ok, 64 KB stalled after 32768 B"))
        assertTrue(p.partial)
        assertEquals(32_768, p.stallBytes)
        assertTrue(p.describe(), p.describe().contains("[0.4s:16K 9.8s:32K]"))
    }

    @Test fun theAttemptThatGotFurthestIsReported() {
        val p = UploadProbe(listOf(
            HostAttempt("speed.cloudflare.com", listOf(stalled(16_384, 8_192))),
            HostAttempt("httpbingo.org", listOf(ok(16_384), ok(65_536), stalled(262_144, 98_304)))
        ))
        assertEquals("httpbingo.org", p.best!!.host)
        assertEquals(98_304, p.stallBytes)
        assertTrue(p.toLeg() is LegResult.Passed)
        assertTrue(p.describe(), p.describe().contains("speed.cloudflare.com: 16 KB stalled after 8192 B") &&
            p.describe().contains(" | httpbingo.org: "))
    }

    @Test fun nothingAnsweringIsNotTestedNeverAFailure() {
        val p = UploadProbe(listOf(HostAttempt("httpbin.org", listOf(Grade(16_384, Outcome.NOT_TESTED, 16_384, 50)))),
            skipped = listOf("speed.cloudflare.com"))
        val leg = p.toLeg(listOf("httpbin.org answered HTTP 503"))
        assertTrue(leg.toString(), leg is LegResult.NotTested && leg.reason.contains("skipped speed.cloudflare.com"))
        assertNull(p.stallBytes)
        assertFalse(p.partial)
    }

    @Test fun everyGradeConfirmedIsAFullPass() {
        val p = UploadProbe(listOf(HostAttempt("speed.cloudflare.com", TrafficCheck.UPLOAD_GRADES.map(::ok))))
        val leg = p.toLeg()
        assertTrue(leg is LegResult.Passed && leg.bytes == TrafficCheck.UPLOAD_BYTES)
        assertTrue(p.describe().contains("all grades ok"))
        assertFalse(p.partial)
    }

    @Test fun theBulkTestLineCarriesServerPrefixAndStallBytes() {
        val p = UploadProbe(listOf(HostAttempt("httpbingo.org", listOf(ok(16_384), stalled(65_536, 40_960)))))
        val e = TrafficEvidence("a", 1, ProbePath.TUN, LegResult.Passed(65_536, 900), p.toLeg(), 0L, p)
        val line = e.diagLine(serverPrefix("185.143.233.120"))
        assertTrue(line, line.startsWith("server=185.143.x.x stall=40960B | download verified"))
        assertTrue(line, line.contains("upload grades (partial: 16 KB ok, stalled at 40960 B)"))
        val stalledDownload = TrafficEvidence("a", 1, ProbePath.TUN,
            LegResult.Failed(FailureStage.TIMEOUT, 16_050, stalled = true, reason = "x"), LegResult.NotTested("y"), 0L)
        assertEquals("server=unknown stall=16050B", stalledDownload.diagLine(null).substringBefore(" |"))
        val clean = TrafficEvidence("a", 1, ProbePath.TUN, LegResult.Passed(65_536, 900), LegResult.Passed(65_536, 900), 0L)
        assertTrue(clean.diagLine("*.example.com").startsWith("server=*.example.com stall=none | "))
    }

    @Test fun serverPrefixNeverShowsTheWholeAddress() {
        assertEquals("185.143.x.x", serverPrefix("185.143.233.120"))
        assertEquals("2a01:4f8:x:x", serverPrefix("[2a01:4f8:c17:1::2]"))
        assertEquals("*.example.com", serverPrefix("de1.vpn.Example.com"))
        assertEquals("example.com", serverPrefix("example.com"))
        assertNull(serverPrefix(" "))
        assertNull(serverPrefix(null))
    }

    @Test fun workerExitsAreRecognisedByAddressHostOrSni() {
        assertTrue(cloudflareWorkerExit("104.16.1.1", "bpb.user.workers.dev", ""))
        assertTrue(cloudflareWorkerExit("panel.pages.dev", "", ""))
        assertTrue(cloudflareWorkerExit("1.2.3.4", "", "a.example.com, x.workers.dev."))
        assertFalse(cloudflareWorkerExit("104.16.1.1", "cdn.example.com", "cdn.example.com"))
        assertFalse(cloudflareWorkerExit(null, null, null))
    }
}
