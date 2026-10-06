package com.example

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.ConnectionVerification
import com.example.data.model.VlessProfile
import com.example.vpn.diagnostics.FailureStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 and scenario E: CONNECTED only after real traffic; stale results never change a new session. */
class ConnectionVerificationTest {
    private val profile = VlessProfile(id = "p1", name = "One", address = "203.0.113.7", port = 443, uuid = "u")
    private val verifying = ConnectionState(status = ConnectionStatus.VERIFYING, activeProfile = profile, attemptId = "a1", sessionId = "s1")

    @Test fun aTunnelIsNotConnectedUntilTrafficPasses() {
        for (status in listOf(ConnectionStatus.VPN_INTERFACE_ESTABLISHED, ConnectionStatus.ENGINE_STARTED,
                ConnectionStatus.PROXY_CONNECTING, ConnectionStatus.VERIFYING, ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED)) {
            val s = ConnectionState(status = status)
            assertFalse(status.name, s.isConnected)
            assertTrue(status.name, s.isTunnelUp)
            assertFalse(status.name, s.statusLabel.contains("CONNECTED") && !s.statusLabel.contains("·"))
        }
    }

    @Test fun aSuccessfulCheckConnectsAndRecordsWhen() {
        val s = ConnectionVerification.afterCheck(verifying, "a1", true, 180, now = 1000)
        assertEquals(ConnectionStatus.CONNECTED, s.status)
        assertEquals(1000L, s.verifiedAt)
        assertEquals(180L, s.pingMs)
        assertTrue(s.isConnected)
    }

    @Test fun E_aTunnelThatCarriesNothingStaysUnverifiedWithItsStage() {
        val s = ConnectionVerification.afterCheck(verifying, "a1", false, null, now = 1000, stage = FailureStage.TLS_HANDSHAKE_FAILED)
        assertEquals(ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, s.status)
        assertFalse(s.isConnected)
        assertEquals(FailureStage.TLS_HANDSHAKE_FAILED, s.failureStage)
        assertNull(s.verifiedAt)
        assertTrue(s.errorMessage!!.contains("TLS_HANDSHAKE_FAILED"))
        // A later check that succeeds connects it.
        assertEquals(ConnectionStatus.CONNECTED, ConnectionVerification.afterCheck(s, "a1", true, 300, now = 2000).status)
    }

    @Test fun twoFailedChecksInARowDegradeAConnection() {
        val connected = ConnectionVerification.afterCheck(verifying, "a1", true, 180, now = 1000)
        val once = ConnectionVerification.afterCheck(connected, "a1", false, null, 2000, FailureStage.TIMEOUT)
        assertEquals(ConnectionStatus.CONNECTED, once.status)
        val twice = ConnectionVerification.afterCheck(once, "a1", false, null, 3000, FailureStage.TIMEOUT)
        assertEquals(ConnectionStatus.DEGRADED, twice.status)
        assertFalse(twice.isConnected)
        assertTrue(twice.isTunnelUp)
        assertEquals(ConnectionStatus.CONNECTED, ConnectionVerification.afterCheck(twice, "a1", true, 200, 4000).status)
    }

    @Test fun aStaleCheckFromAnotherAttemptOrAfterDisconnectChangesNothing() {
        assertSame(verifying, ConnectionVerification.afterCheck(verifying, "old", true, 100, 1000))
        assertSame(verifying, ConnectionVerification.afterCheck(verifying, null, true, 100, 1000))
        val disconnected = ConnectionState(status = ConnectionStatus.DISCONNECTED, attemptId = "a1")
        assertSame(disconnected, ConnectionVerification.afterCheck(disconnected, "a1", true, 100, 1000))
    }

    @Test fun aPassStopsCountingWhenTheAttemptNetworkProfileOrConnectionChanges() {
        val connected = ConnectionVerification.afterCheck(verifying, "a1", true, 180, now = 1000)
        val key = connected.passKey
        assertNotEquals(key, connected.copy(attemptId = "a2").passKey)
        assertNotEquals(key, connected.copy(networkGeneration = 1).passKey)
        assertNotEquals(key, connected.copy(activeProfile = profile.copy(id = "p2")).passKey)
        assertNotEquals(key, ConnectionState().passKey)
        assertEquals(key, connected.copy(pingMs = 999).passKey)
    }
}
