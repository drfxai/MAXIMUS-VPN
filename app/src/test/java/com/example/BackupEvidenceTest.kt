package com.example

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.BackupEvidence
import com.example.vpn.connectivity.BackupReadiness
import com.example.vpn.connectivity.SmartFailoverPolicy
import com.example.vpn.connectivity.DiversitySelector
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Backup" means what the evidence says: a parsed, supported profile is only AVAILABLE. */
class BackupEvidenceTest {
    private val now = 10_000_000_000L
    private fun profile(id: String, latency: Long?, testedAgoMs: Long?, score: Double = 50.0) = VlessProfile(
        id = id, name = id, address = "203.0.113.${id.hashCode().and(0x7f) + 1}", port = 443,
        uuid = "00000000-0000-0000-0000-000000000001", security = "tls", sni = "$id.example",
        lastLatencyMs = latency, lastTestedTimestamp = testedAgoMs?.let { now - it }, overallScore = score
    )

    @Test fun readinessFollowsTheLastRealTest() {
        assertEquals(BackupReadiness.VERIFIED, BackupEvidence.readiness(profile("a", 900, 5 * 60_000L), now))
        assertEquals(BackupReadiness.PREVIOUSLY_VERIFIED, BackupEvidence.readiness(profile("b", 900, 3 * 3_600_000L), now))
        assertEquals(BackupReadiness.AVAILABLE, BackupEvidence.readiness(profile("c", null, null), now))
        assertEquals(BackupReadiness.RECENTLY_FAILED, BackupEvidence.readiness(profile("d", null, 60_000L), now))
    }

    @Test fun aRecentlyVerifiedBackupBeatsAnUntestedOneWithAHigherOldScore() {
        val verified = profile("verified", 1400, 10 * 60_000L, score = 20.0)
        val untested = profile("untested", null, null, score = 99.0)
        val failed = profile("failed", null, 2 * 60_000L, score = 99.0)
        val ranked = listOf(untested, failed, verified).sortedByDescending { BackupEvidence.rank(it, now) }
        assertEquals(listOf("verified", "untested", "failed"), ranked.map { it.id })
    }

    @Test fun thePlanPicksTheVerifiedBackupFirst() {
        val primary = profile("primary", 500, 60_000L)
        val verified = profile("verified", 1200, 5 * 60_000L, score = 10.0)
        val untested = profile("untested", null, null, score = 95.0)
        // Every profile on its own failure domain, so only the evidence decides the order.
        val traits = { p: VlessProfile -> DiversitySelector.traitsOf(p).copy(failureDomain = "dom:" + p.id) }
        val plan = SmartFailoverPolicy.plan(primary, listOf(untested, verified), { BackupEvidence.rank(it, now) }, traits)
        assertEquals("verified", plan.backupA?.profile?.id)
        assertEquals("verified 5 min ago", BackupEvidence.describe(verified, now))
        assertEquals("not tested", BackupEvidence.describe(untested, now))
    }
}
