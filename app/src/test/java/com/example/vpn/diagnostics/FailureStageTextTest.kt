package com.example.vpn.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The phone report showed six subscription 503s counted as TUN_ESTABLISH_FAILED. */
class FailureStageTextTest {
    @Test fun aSubscription503IsNotATunFailure() {
        val old = "Sync failed for 'MAXIMUS': HTTP 503: subscription service unavailable; this does not establish a tunnel failure"
        assertNotEquals(FailureStage.TUN_ESTABLISH_FAILED, FailureStage.fromText(old))
        assertEquals(FailureStage.HTTP_STATUS_INVALID, FailureStage.fromText("Sync failed for 'MAXIMUS': HTTP 503: subscription service unavailable"))
    }

    @Test fun realTunFailuresStillMatch() {
        assertEquals(FailureStage.TUN_ESTABLISH_FAILED, FailureStage.fromText("Could not establish the VPN interface"))
        assertEquals(FailureStage.TUN_ESTABLISH_FAILED, FailureStage.fromText("failed to establish tun device"))
    }
}
