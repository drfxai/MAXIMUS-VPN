package com.example.vpn.smart

import com.example.vpn.smart.PathGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathGateTest {
    @Test
    fun aPathThatJustFailedIsNotStartedAutomatically() {
        val d = PathGate.decide(verified = false, provenDead = true, engineAdopted = false, forced = false)
        assertEquals(Decision.NO_VERIFIED_PATH, d)
        assertFalse(PathGate.mayStart(d))
    }

    @Test
    fun connectAnywayIsTheUsersExplicitOverride() {
        val d = PathGate.decide(verified = false, provenDead = true, engineAdopted = false, forced = true)
        assertEquals(Decision.FORCED, d)
        assertTrue(PathGate.mayStart(d))
    }

    @Test
    fun aVerifiedAlternativeStarts() {
        assertEquals(Decision.VERIFIED, PathGate.decide(verified = true, provenDead = true, engineAdopted = false, forced = false))
    }

    @Test
    fun aRecoveryEngineIsChosenNotCountedAsVerified() {
        val d = PathGate.decide(verified = false, provenDead = true, engineAdopted = true, forced = false)
        assertEquals(Decision.ENGINE_UNVERIFIED, d)
        assertTrue(PathGate.mayStart(d))
    }

    @Test
    fun anUntestedPathIsNotProvenDead() {
        // The probe could not run (e.g. no test core): nothing proved the path dead.
        assertEquals(Decision.UNTESTED, PathGate.decide(verified = false, provenDead = false, engineAdopted = false, forced = false))
    }
}
