package com.example.vpn.connectivity

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.ConnectionVerification
import com.example.vpn.diagnostics.FailureStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 2: CONNECTED only on current evidence; network changes and switches clear old PASS results. */
class ConnectionStateCorrectnessTest {
    private val connected = ConnectionState(status = ConnectionStatus.CONNECTED, attemptId = "a1", verifiedAt = 100, pingMs = 80)

    @Test fun aTunnelWithoutTrafficIsNeverConnected() {
        listOf(ConnectionStatus.VPN_INTERFACE_ESTABLISHED, ConnectionStatus.ENGINE_STARTED, ConnectionStatus.VERIFYING,
            ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, ConnectionStatus.NETWORK_CHANGED, ConnectionStatus.SWITCHING).forEach {
            val s = ConnectionState(status = it, attemptId = "a1")
            assertFalse(it.name, s.isConnected)
            assertTrue(it.name, s.isTunnelUp)
            assertFalse(it.name, s.statusLabel == "VPN CONNECTED")
        }
    }

    @Test fun networkChangeClearsTheLivePassUntilRechecked() {
        val changed = ConnectionVerification.onNetworkChanged(connected)
        assertEquals(ConnectionStatus.NETWORK_CHANGED, changed.status)
        assertEquals(1, changed.networkGeneration)
        assertNull(changed.verifiedAt)
        assertNull(changed.pingMs)
        assertTrue(changed.isTunnelUp)
        assertFalse(changed.passKey == connected.passKey)
        val rechecked = ConnectionVerification.afterCheck(changed, "a1", true, 120, now = 500)
        assertEquals(ConnectionStatus.CONNECTED, rechecked.status)
        assertEquals(500L, rechecked.verifiedAt)
    }

    @Test fun aFailedRecheckAfterNetworkChangeIsUnverifiedNotConnected() {
        val changed = ConnectionVerification.onNetworkChanged(connected)
        val s = ConnectionVerification.afterCheck(changed, "a1", false, null, now = 500, stage = FailureStage.TIMEOUT)
        assertEquals(ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, s.status)
        assertEquals(FailureStage.TIMEOUT, s.failureStage)
    }

    @Test fun networkChangeWhileStartingOnlyBumpsTheGeneration() {
        val starting = ConnectionState(status = ConnectionStatus.VERIFYING, attemptId = "a1", networkGeneration = 3)
        val s = ConnectionVerification.onNetworkChanged(starting)
        assertEquals(ConnectionStatus.VERIFYING, s.status)
        assertEquals(4, s.networkGeneration)
        val off = ConnectionVerification.onNetworkChanged(ConnectionState())
        assertEquals(ConnectionStatus.DISCONNECTED, off.status)
    }

    @Test fun switchingClearsThePassAndIgnoresLateChecks() {
        val switching = ConnectionVerification.onSwitching(connected)
        assertEquals(ConnectionStatus.SWITCHING, switching.status)
        assertNull(switching.verifiedAt)
        assertTrue(switching.isBusy)
        // A check that finishes during the switch does not turn it back into CONNECTED.
        assertEquals(switching, ConnectionVerification.afterCheck(switching, "a1", true, 50, now = 900))
    }

    @Test fun aResultForAnOlderAttemptIsIgnored() {
        val newer = connected.copy(attemptId = "a2", status = ConnectionStatus.VERIFYING, verifiedAt = null)
        assertEquals(newer, ConnectionVerification.afterCheck(newer, "a1", true, 50, now = 900))
    }

    @Test fun disconnectedStateIsNeverTurnedConnectedByALateCheck() {
        val off = ConnectionState(status = ConnectionStatus.DISCONNECTED, attemptId = "a1")
        assertEquals(off, ConnectionVerification.afterCheck(off, "a1", true, 50, now = 900))
        assertEquals(off, ConnectionVerification.onSwitching(off))
    }

    @Test fun twoFailedChecksDegradeAConnectedTunnel() {
        val one = ConnectionVerification.afterCheck(connected, "a1", false, null, 200, FailureStage.HTTP_REQUEST_FAILED)
        assertEquals(ConnectionStatus.CONNECTED, one.status)
        val two = ConnectionVerification.afterCheck(one, "a1", false, null, 300, FailureStage.HTTP_REQUEST_FAILED)
        assertEquals(ConnectionStatus.DEGRADED, two.status)
    }
}
