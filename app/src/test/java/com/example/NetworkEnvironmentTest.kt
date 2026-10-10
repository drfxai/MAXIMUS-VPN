package com.example

import com.example.vpn.smart.NetworkEnvironment
import com.example.vpn.smart.NetworkEnvironment.Level
import com.example.vpn.smart.NetworkEnvironment.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1.0.1 step 6: how hostile a network looks, from what the phone and earlier connects saw. */
class NetworkEnvironmentTest {
    private fun classify(
        validated: Boolean = true, dns: Boolean = false, failed: Set<String> = emptySet(), working: List<String> = emptyList()
    ) = NetworkEnvironment.classify("cell:43235", validated, metered = true, roaming = false, dnsPoisoned = dns,
        failedKinds = failed, workingKinds = working)

    @Test fun anOrdinaryNetworkIsOpen() {
        val r = classify(working = listOf("REALITY"))
        assertEquals(Level.OPEN, r.level)
        assertEquals(setOf(Signal.METERED), r.signals)
        assertFalse(NetworkEnvironment.suggestsGodMode(r))
    }

    @Test fun oneFilterIsFilteredAndTwoAreHeavy() {
        assertEquals(Level.FILTERED, classify(dns = true, working = listOf("REALITY")).level)
        val heavy = classify(dns = true, failed = setOf("QUIC"), working = listOf("REALITY", "QUIC"))
        assertEquals(Level.HEAVY, heavy.level)
        assertTrue(Signal.UDP_FILTERED in heavy.signals)
        assertTrue(NetworkEnvironment.suggestsGodMode(heavy))
    }

    @Test fun whenEverythingThatWorkedNowFailsThereIsNoVerifiedEgress() {
        val r = classify(validated = false, failed = setOf("REALITY", "CDN"), working = listOf("REALITY", "CDN"))
        assertEquals(Level.NO_VERIFIED_EGRESS, r.level)
        assertTrue(r.describe().startsWith("Irancell (cell:43235): no_verified_egress"))
    }

    @Test fun aTlsFailureIsNeverCalledSniWithoutAControlledComparison() {
        val r = classify(failed = setOf("REALITY", "TLS"), working = listOf("CDN"))
        assertTrue(Signal.TLS_PATH_FAILURE in r.signals)
        assertFalse(Signal.SNI_INTERFERENCE_SUSPECTED in r.signals)
        val compared = NetworkEnvironment.classify("cell:43235", true, metered = true, roaming = false, dnsPoisoned = false,
            failedKinds = setOf("TLS"), workingKinds = listOf("CDN"), sniComparisonCut = true)
        assertTrue(Signal.SNI_INTERFERENCE_SUSPECTED in compared.signals)
    }
}
