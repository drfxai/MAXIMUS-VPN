package com.example.vpn.connectivity

import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.hub.ConnectivityMeasurement
import com.example.vpn.hub.FreeConfigLifecycle
import com.example.vpn.hub.FreeConfigLifecycleRules
import com.example.vpn.hub.GlobalStatus
import com.example.vpn.hub.LocalEvidence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 3: deterministic scoring and candidate states. */
class ConnectionScoreTest {
    private val now = 10_000_000L
    private val good = ConnectionScore.Inputs(
        lifecycle = FreeConfigLifecycle.LOCAL_NETWORK_VERIFIED, globalStatus = GlobalStatus.GLOBAL_VERIFIED,
        protocolOk = true, tunnelOk = true, internetOk = true, dnsTunnelOk = true,
        recentSuccesses = 5, recentAttempts = 5, historicalSuccesses = 18, historicalAttempts = 20,
        latencyMs = 250, jitterMs = 15, packetLossPercent = 0.0, reconnectsPerHour = 0.0, stableSessionMs = 40 * 60_000,
        networkMatch = true, verifiedSessions = 3, securityMode = "reality", lastEvidenceAt = now
    )

    @Test fun aStable250msServerBeatsAnUnstable70msOne() {
        val stable = ConnectionScore.of(good, now)
        val fastFlaky = ConnectionScore.of(good.copy(latencyMs = 70, jitterMs = 60, packetLossPercent = 8.0, reconnectsPerHour = 4.0,
            recentSuccesses = 3, recentAttempts = 5, stableSessionMs = 2 * 60_000), now)
        assertTrue("${stable.score} vs ${fastFlaky.score}", stable.score > fastFlaky.score)
    }

    @Test fun latencyNeverDominates() {
        val slow = ConnectionScore.of(good.copy(latencyMs = 2_500), now)
        val fast = ConnectionScore.of(good.copy(latencyMs = 20), now)
        assertTrue(fast.score - slow.score <= ConnectionScore.WEIGHTS.getValue("latency") + 0.01)
    }

    @Test fun securityRejectionOverridesEverything() {
        val r = ConnectionScore.of(good.copy(securityProblem = "Does not check the server's certificate", boundedAdjustment = 5.0), now)
        assertFalse(r.eligible)
        assertEquals(0.0, r.score, 0.0)
        assertFalse(ConnectionScore.of(good.copy(lifecycle = FreeConfigLifecycle.SECURITY_REJECTED), now).eligible)
    }

    @Test fun deadQuarantinedAndGloballyFailedAreNotEligible() {
        listOf(good.copy(lifecycle = FreeConfigLifecycle.DEAD), good.copy(lifecycle = FreeConfigLifecycle.QUARANTINED),
            good.copy(globalStatus = GlobalStatus.GLOBAL_FAILED)).forEach { assertFalse(ConnectionScore.of(it, now).eligible) }
    }

    @Test fun oldEvidenceDriftsToNeutral() {
        val fresh = ConnectionScore.of(good, now)
        val old = ConnectionScore.of(good.copy(lastEvidenceAt = now - 4 * ConnectionScore.FRESH_HALF_LIFE_MS), now)
        assertTrue(fresh.score > old.score)
        assertEquals(0.5, ConnectionScore.freshness(ConnectionScore.FRESH_HALF_LIFE_MS), 1e-9)
    }

    @Test fun adjustmentIsBoundedAndNeverRescues() {
        val base = ConnectionScore.of(good.copy(lastEvidenceAt = null), now).score
        val boosted = ConnectionScore.of(good.copy(lastEvidenceAt = null, boundedAdjustment = 1_000.0), now).score
        assertEquals(ConnectionScore.MAX_ADJUSTMENT, boosted - base, 0.01)
        assertFalse(ConnectionScore.of(good.copy(lifecycle = FreeConfigLifecycle.DEAD, boundedAdjustment = 1_000.0), now).eligible)
    }

    @Test fun rankingIsDeterministicAndPutsIneligibleLast() {
        val items = listOf("b" to good, "a" to good, "z" to good.copy(securityProblem = "x"), "c" to good.copy(latencyMs = 1_500))
        val ranked = ConnectionScore.rank(items, { ConnectionScore.of(it.second, now) }, { it.first }).map { it.first }
        assertEquals(listOf("a", "b", "c", "z"), ranked)
    }

    @Test fun everyScoreExplainsItself() {
        val r = ConnectionScore.of(good, now)
        assertTrue(ConnectionScore.WEIGHTS.keys.all { it in r.reasons })
        assertEquals(100.0, ConnectionScore.WEIGHTS.values.sum(), 0.0)
    }

    @Test fun localStatesUseHonestNamesAndOldStoredNamesStillLoad() {
        val old = LocalEvidence.fromJson(JSONObject().put("f", "fp").put("l", "IRAN_VERIFIED"))
        assertEquals(FreeConfigLifecycle.LOCAL_NETWORK_VERIFIED, old.lifecycle)
        assertEquals(FreeConfigLifecycle.LOCAL_PROBATION, FreeConfigLifecycle.parse("IRAN_PROBATION"))
        assertTrue(FreeConfigLifecycle.entries.none { it.name.contains("IRAN") || it.label.contains("works in Iran", ignoreCase = true) })
    }

    @Test fun securityRejectedIsStickyAgainstSuccesses() {
        var e = LocalEvidence("fp", lifecycle = FreeConfigLifecycle.SECURITY_REJECTED)
        repeat(5) { e = FreeConfigLifecycleRules.apply(e, ConnectivityMeasurement(it.toLong(), ConnectivityMeasurement.Kind.CONNECTION, true)) }
        assertEquals(FreeConfigLifecycle.SECURITY_REJECTED, e.lifecycle)
        assertFalse(e.lifecycle.usable)
    }

    @Test fun oneSuccessIsProbationNotVerified() {
        val e = FreeConfigLifecycleRules.apply(LocalEvidence("fp"), ConnectivityMeasurement(1, ConnectivityMeasurement.Kind.TEST, true))
        assertEquals(FreeConfigLifecycle.LOCAL_PROBATION, e.lifecycle)
        val failed = FreeConfigLifecycleRules.apply(e, ConnectivityMeasurement(2, ConnectivityMeasurement.Kind.TEST, false, failureStage = FailureStage.TIMEOUT))
        assertEquals(FailureStage.TIMEOUT, failed.lastFailureStage)
    }
}
