package com.example.vpn.verify

import com.example.vpn.diagnostics.FailureStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 145: the upload stalled at exactly 16384 B on every reference while the download stopped at ~16 KB. */
class UploadFreezeSignatureTest {
    private fun stalledAt16k(host: String) = UploadProbe.HostAttempt(host, listOf(
        UploadProbe.Grade(16_384, UploadProbe.Outcome.STALLED, 16_384, 8_000),
        UploadProbe.Grade(65_536, UploadProbe.Outcome.NOT_TESTED, 0, 0)))
    private val upFrozen = UploadProbe(listOf(stalledAt16k("speed.cloudflare.com"), stalledAt16k("httpbingo.org")))
    private fun ev(down: LegResult, probe: UploadProbe?) = TrafficEvidence("a", 0, ProbePath.TUN, down,
        probe?.toLeg() ?: LegResult.Passed(65_536, 500), checkedAt = 1, uploadProbe = probe)

    @Test fun uploadStallAtSixteenKilobytesOnEveryTargetIsFreezeEvidence() {
        assertTrue(ev(LegResult.Failed(FailureStage.TIMEOUT, 16_050, stalled = true, reason = "t"), upFrozen).uploadFreeze)
        // The download ended as a timeout inside the window rather than a stall: the upload completes the signature.
        val e = ev(LegResult.Failed(FailureStage.TIMEOUT, 16_050, stalled = false, reason = "t"), upFrozen)
        assertTrue(e.fixedSizeFreeze)
        assertTrue(capClass(e.stallBytes))
    }

    @Test fun oneTargetPassingIsNotTheSignature() {
        val mixed = UploadProbe(listOf(stalledAt16k("speed.cloudflare.com"), UploadProbe.HostAttempt("httpbingo.org", listOf(
            UploadProbe.Grade(16_384, UploadProbe.Outcome.OK, 16_384, 300),
            UploadProbe.Grade(65_536, UploadProbe.Outcome.OK, 65_536, 900)))))
        val e = ev(LegResult.Failed(FailureStage.TIMEOUT, 16_050, stalled = false, reason = "t"), mixed)
        assertFalse(e.uploadFreeze)
        assertFalse(e.fixedSizeFreeze)
    }

    @Test fun uploadAloneWithADownloadOutsideTheWindowIsNotAFreeze() {
        assertFalse(ev(LegResult.Failed(FailureStage.TIMEOUT, 48_000, stalled = false, reason = "t"), upFrozen).fixedSizeFreeze)
        assertFalse(ev(LegResult.Passed(262_144, 900), upFrozen).fixedSizeFreeze)
    }

    @Test fun theDownloadOnlySignatureStillCounts() {
        assertTrue(ev(LegResult.Failed(FailureStage.TIMEOUT, 16_050, stalled = true, reason = "t"), null).fixedSizeFreeze)
    }

    @Test fun build150UploadStalledAt16kAndADownloadThatCarriedNothingIsAFreeze() {
        val e = ev(LegResult.Failed(FailureStage.TIMEOUT, 0, stalled = false, reason = "TIMEOUT"), upFrozen)
        assertTrue(e.uploadFreezeDeadDownload)
        assertTrue(e.fixedSizeFreeze)
        assertTrue(capClass(e.stallBytes))
    }

    @Test fun aReferenceThatNeverConnectedDoesNotHideTheSignature() {
        val unreachable = UploadProbe.HostAttempt("httpbingo.org", listOf(
            UploadProbe.Grade(16_384, UploadProbe.Outcome.FAILED, 0, 4_000),
            UploadProbe.Grade(65_536, UploadProbe.Outcome.NOT_TESTED, 0, 0)))
        val probe = UploadProbe(listOf(stalledAt16k("speed.cloudflare.com"), unreachable))
        assertTrue(ev(LegResult.Failed(FailureStage.TIMEOUT, 0, stalled = false, reason = "TIMEOUT"), probe).fixedSizeFreeze)
    }

    @Test fun aDeadDownloadWithoutTheUploadStallIsNotAFreeze() {
        val e = ev(LegResult.Failed(FailureStage.TIMEOUT, 0, stalled = false, reason = "TIMEOUT"), null)
        assertFalse(e.uploadFreezeDeadDownload)
        assertFalse(e.fixedSizeFreeze)
        // An upload stall past the window with a dead download is not the 16 KB cap either.
        val late = UploadProbe(listOf(UploadProbe.HostAttempt("speed.cloudflare.com", listOf(
            UploadProbe.Grade(16_384, UploadProbe.Outcome.OK, 16_384, 300),
            UploadProbe.Grade(65_536, UploadProbe.Outcome.STALLED, 40_000, 8_000)))))
        assertFalse(ev(LegResult.Failed(FailureStage.TIMEOUT, 0, stalled = false, reason = "TIMEOUT"), late).fixedSizeFreeze)
    }

    private fun capClass(bytes: Int?) =
        com.example.vpn.smart.StallFailover.classify(bytes) == com.example.vpn.smart.StallFailover.StallClass.CAP
}
