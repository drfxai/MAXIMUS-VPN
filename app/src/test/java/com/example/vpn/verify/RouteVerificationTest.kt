package com.example.vpn.verify

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.ConnectionVerification
import com.example.vpn.diagnostics.FailureStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verification shown never exceeds the checks behind it, and stale evidence never counts. */
class RouteVerificationTest {
    private val passed = LegResult.Passed(65_536, 900)
    private fun evidence(attempt: String? = "a1", generation: Int = 0, down: LegResult = passed, up: LegResult = passed) =
        TrafficEvidence(attempt, generation, ProbePath.TUN, down, up, checkedAt = 1)
    private val connected = ConnectionState(status = ConnectionStatus.CONNECTED, attemptId = "a1")

    @Test fun nothingBeforeTrafficIsCalledVerified() {
        for (status in ConnectionStatus.values().filter { it != ConnectionStatus.CONNECTED }) {
            val v = RouteVerification.of(ConnectionState(status = status, attemptId = "a1", trafficEvidence = evidence()))
            assertFalse("$status -> $v", v in setOf(RouteVerification.TUNNEL_TRAFFIC_VERIFIED, RouteVerification.BIDIRECTIONAL_VERIFIED,
                RouteVerification.REVIVED_AND_VERIFIED))
        }
    }

    @Test fun aSmallRequestIsTrafficVerifiedOnly() {
        assertEquals(RouteVerification.TUNNEL_TRAFFIC_VERIFIED, connected.verification)
    }

    @Test fun bothDirectionsPassingIsBidirectional() {
        assertEquals(RouteVerification.BIDIRECTIONAL_VERIFIED, connected.copy(trafficEvidence = evidence()).verification)
    }

    @Test fun oneDirectionNotTestedStaysAtTrafficVerified() {
        val s = connected.copy(trafficEvidence = evidence(up = LegResult.NotTested("no reference answered")))
        assertEquals(RouteVerification.TUNNEL_TRAFFIC_VERIFIED, s.verification)
    }

    @Test fun evidenceFromAnotherAttemptOrNetworkDoesNotCount() {
        assertEquals(RouteVerification.TUNNEL_TRAFFIC_VERIFIED, connected.copy(trafficEvidence = evidence(attempt = "old")).verification)
        assertEquals(RouteVerification.TUNNEL_TRAFFIC_VERIFIED, connected.copy(trafficEvidence = evidence(generation = 3)).verification)
        // A network change bumps the generation: the old upload/download proof stops counting at once.
        val changed = ConnectionVerification.onNetworkChanged(connected.copy(trafficEvidence = evidence()))
        val again = ConnectionVerification.afterCheck(changed, "a1", true, 120, now = 5)
        assertEquals(RouteVerification.TUNNEL_TRAFFIC_VERIFIED, again.verification)
    }

    @Test fun aStalledBulkTransferIsVisible() {
        val e = evidence(down = LegResult.Failed(FailureStage.TIMEOUT, 12_000, stalled = true, reason = "deadline"))
        assertTrue(e.bulkStalled)
        assertFalse(e.bidirectional)
        assertTrue(e.describe().contains("stalled after 12000 bytes"))
    }

    @Test fun revivalIsShownWhileRunningAndAfterItsCopyCarriesTraffic() {
        assertEquals(RouteVerification.REVIVAL_IN_PROGRESS, ConnectionState(status = ConnectionStatus.PREPARING, reviving = true).verification)
        assertEquals(RouteVerification.REVIVED_AND_VERIFIED, connected.copy(revived = true).verification)
        // A revived copy that has not carried traffic yet is not "revived and verified".
        assertEquals(RouteVerification.LOCAL_PROXY_READY, ConnectionState(status = ConnectionStatus.VERIFYING, revived = true).verification)
    }

    @Test fun exhaustedRecoveryIsNoRouteObservedOtherwiseFailed() {
        assertEquals(RouteVerification.NO_EXTERNAL_ROUTE_OBSERVED, ConnectionState(status = ConnectionStatus.FAILED, recoveryExhausted = true).verification)
        assertEquals(RouteVerification.FAILED, ConnectionState(status = ConnectionStatus.FAILED).verification)
        assertEquals(RouteVerification.DEGRADED, ConnectionState(status = ConnectionStatus.DEGRADED).verification)
    }

    @Test fun measuredRatesComeFromTheTransferOnly() {
        assertEquals(65_536L * 1000 / 900, passed.bytesPerSecond)
    }
}
