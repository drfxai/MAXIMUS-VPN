package com.example.vpn.smart

import org.junit.Assert.*
import org.junit.Test

class RecoveryAttemptPolicyTest {
    @Test fun progressingTorIsNotCutOffByTheLast78SecondsOfTheSearch() {
        assertEquals(420_000L, RecoveryAttemptPolicy.window("tor", 78_000))
        assertEquals(78_000L, RecoveryAttemptPolicy.window("masque", 78_000))
        assertEquals(90_000L, RecoveryAttemptPolicy.window("matrix-transport", 300_000))
    }
    @Test fun registrationFailureSkipsOtherCarriersOfTheSameClientOnly() {
        val p = RecoveryAttemptPolicy()
        p.record("matrix-transport", "registration failed before the local proxy became ready")
        assertTrue(p.skip(listOf("matrix-transport")))
        assertTrue(p.skip(listOf("psiphon", "matrix-transport")))
        assertFalse(p.skip(listOf("masque")))
        assertFalse(p.skip(listOf("tor")))
        assertFalse(RecoveryAttemptPolicy().skip(listOf("matrix-transport")))
    }
    @Test fun aCarrierTimeoutDoesNotProveRegistrationFailure() {
        val p = RecoveryAttemptPolicy()
        p.record("matrix-transport", "traffic verification timed out")
        assertFalse(p.skip(listOf("matrix-transport")))
    }
}
