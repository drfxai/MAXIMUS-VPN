package com.example.vpn.lab

import com.example.vpn.connectivity.RecoveryProfile
import com.example.vpn.connectivity.RecoveryProfiles
import com.example.vpn.diagnostics.FailureStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Why a dead config failed, read only from what its error says plainly. */
class RevivalTriageTest {
    @Test fun plainCausesAreRecognised() {
        assertEquals(RevivalTriage.Cause.NAME_NOT_RESOLVED, RevivalTriage.cause("dial tcp: lookup x.example: no such host"))
        assertEquals(RevivalTriage.Cause.ADDRESS_UNREACHABLE, RevivalTriage.cause("dial tcp 203.0.113.9:443: connect: connection refused"))
        assertEquals(RevivalTriage.Cause.ADDRESS_UNREACHABLE, RevivalTriage.cause("connect: no route to host"))
        assertEquals(RevivalTriage.Cause.CONNECTION_CUT, RevivalTriage.cause("read tcp: connection reset by peer"))
        assertEquals(RevivalTriage.Cause.CONNECTION_CUT, RevivalTriage.cause("tls: handshake failure"))
        assertEquals(RevivalTriage.Cause.CERTIFICATE_REFUSED, RevivalTriage.cause("x509: certificate signed by unknown authority"))
    }

    @Test fun ambiguousErrorsLimitNothing() {
        for (t in listOf("timeout", "i/o timeout", "no response through the proxy", "", null)) {
            assertEquals(t, RevivalTriage.Cause.UNKNOWN, RevivalTriage.cause(t))
        }
        assertNull(RevivalTriage.stage(RevivalTriage.Cause.UNKNOWN))
        assertNull(RevivalTriage.stage(RevivalTriage.Cause.CONNECTION_CUT))
        assertNull("a refused certificate is never a reason to drop the check", RevivalTriage.stage(RevivalTriage.Cause.CERTIFICATE_REFUSED))
        assertEquals(FailureStage.DNS_RESOLUTION_FAILED, RevivalTriage.stage(RevivalTriage.Cause.NAME_NOT_RESOLVED))
    }

    @Test fun aCutHandshakeMovesTlsShapingRecipesUp() {
        val all = RecoveryProfiles.BUILT_IN
        val shaping = all.filter { p -> p.networkConditions.any { it in setOf(RecoveryProfile.Strategy.FRAGMENT, RecoveryProfile.Strategy.ECH) } }
        val address = all.filter { p -> p.networkConditions.any { it in setOf(RecoveryProfile.Strategy.ALT_ENDPOINT_V4, RecoveryProfile.Strategy.ALT_ENDPOINT_V6) } }
        assertTrue(shaping.isNotEmpty() && address.isNotEmpty())
        assertTrue(shaping.all { RevivalTriage.boost(RevivalTriage.Cause.CONNECTION_CUT, it) > 0 })
        assertTrue(address.all { RevivalTriage.boost(RevivalTriage.Cause.CONNECTION_CUT, it) == 0.0 })
        assertTrue(address.all { RevivalTriage.boost(RevivalTriage.Cause.CERTIFICATE_REFUSED, it) > 0 })
        assertTrue(all.all { RevivalTriage.boost(RevivalTriage.Cause.UNKNOWN, it) == 0.0 })
    }
}
