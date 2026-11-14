package com.example.vpn.verify

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.ConnectionVerification
import com.example.vpn.diagnostics.FailureStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Connected, but large downloads are stalling": only for this attempt's evidence, and never while moving. */
class BulkLimitedStateTest {
    private val passed = LegResult.Passed(bytes = 256 * 1024, elapsedMs = 800)
    private fun stalled(bytes: Int) = LegResult.Failed(FailureStage.HTTP_REQUEST_FAILED, bytes, stalled = true, reason = "timeout")
    private fun evidence(down: LegResult, attempt: String = "a1", generation: Int = 0) =
        TrafficEvidence(attempt, generation, ProbePath.TUN, down, passed, checkedAt = 1)
    private val connected = ConnectionState(status = ConnectionStatus.CONNECTED, attemptId = "a1")

    @Test fun aStallOutsideTheFreezeWindowIsLimited() {
        // The field case: verified, then the bulk download stopped at 3781 bytes.
        assertTrue(connected.copy(trafficEvidence = evidence(stalled(3781))).bulkLimited)
    }

    @Test fun theSixteenKilobyteFreezeIsLimited() {
        assertTrue(ConnectionVerification.bulkLimited(connected.copy(trafficEvidence = evidence(stalled(16 * 1024)))))
    }

    @Test fun aPassingTransferOrNoCheckYetIsNotLimited() {
        assertFalse(connected.copy(trafficEvidence = evidence(passed)).bulkLimited)
        assertFalse(connected.bulkLimited)
    }

    @Test fun evidenceFromAnotherAttemptOrNetworkDoesNotCount() {
        assertFalse(connected.copy(trafficEvidence = evidence(stalled(3781), attempt = "old")).bulkLimited)
        assertFalse(connected.copy(trafficEvidence = evidence(stalled(3781), generation = 2)).bulkLimited)
    }

    @Test fun aSwitchInProgressIsNotShownAsLimited() {
        val limited = connected.copy(trafficEvidence = evidence(stalled(3781)))
        assertFalse(ConnectionVerification.onSwitching(limited).bulkLimited)
        for (status in ConnectionStatus.values().filter { it != ConnectionStatus.CONNECTED }) {
            assertFalse(status.name, limited.copy(status = status).bulkLimited)
        }
    }
}
