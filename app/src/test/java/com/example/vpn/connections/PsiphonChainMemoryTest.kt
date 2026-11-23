package com.example.vpn.connections

import com.example.vpn.sidecar.PsiphonSidecar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PsiphonChainMemoryTest {
    @Before fun reset() = PsiphonChainMemory.resetForTest()
    @After fun after() = PsiphonChainMemory.resetForTest()

    @Test fun directFailureMakesTheChainGoFirstOnThatNetworkForSixHours() {
        val net = "cell:43235"
        assertFalse(PsiphonChainMemory.preferChain(net, now = 0))
        PsiphonChainMemory.recordDirectFailure(net, now = 1_000)
        assertTrue(PsiphonChainMemory.preferChain(net, now = 1_000 + 60_000))
        assertFalse("another network is unaffected", PsiphonChainMemory.preferChain("wifi:abcdef123456", now = 2_000))
        assertTrue(PsiphonChainMemory.preferChain(net, now = 1_000 + PsiphonChainMemory.HOLD_MS - 1))
        assertFalse(PsiphonChainMemory.preferChain(net, now = 1_000 + PsiphonChainMemory.HOLD_MS))
        PsiphonChainMemory.recordDirectFailure(net, now = 5_000)
        PsiphonChainMemory.clear(net)
        assertNull(PsiphonChainMemory.failedAt(net, now = 6_000))
        assertEquals(6 * 60 * 60_000L, PsiphonChainMemory.HOLD_MS)
    }

    @Test fun onlyPublicDirectPsiphonCountsAsDirect() {
        assertTrue(PsiphonChainMemory.isDirect(PsiphonSidecar.profile()))
        assertTrue(PsiphonChainMemory.isDirect(PsiphonSidecar.profile(tcpOnly = true)))
        assertFalse(PsiphonChainMemory.isDirect(PsiphonChainMemory.chainProfile()))
        assertFalse(PsiphonChainMemory.isDirect(PsiphonSidecar.privateProfile("123e4567-e89b-12d3-a456-426614174000", "vps", "203.0.113.1")))
        assertEquals("config-free-psiphon-radiance", PsiphonChainMemory.chainProfile().id)
    }

    @Test fun chainHandOverRespectsPinnedForcedAndOnceBoundaries() {
        fun may(exact: Boolean = false, exitLocked: Boolean = false, force: Boolean = false, tried: Boolean = false,
                available: Boolean = true, smart: Boolean = false, failover: Boolean = false, fallback: Boolean = true,
                direct: Boolean = true) = PsiphonChainMemory.mayUseChain(direct, smart, failover, fallback, exact,
            exitLocked, force, tried, available)
        assertTrue(may())
        assertTrue(may(fallback = false, smart = true))
        assertTrue(may(fallback = false, failover = true))
        assertFalse(may(fallback = false))
        assertFalse(may(exact = true))
        assertFalse(may(exitLocked = true))
        assertFalse(may(force = true))
        assertFalse(may(tried = true))
        assertFalse(may(available = false))
        assertFalse(may(direct = false))
    }

    @Test fun aVerifiedChainOverLanternGoesFirstOnItsNetworkUntilDirectCarriesTrafficAgain() {
        // Build 173: direct Psiphon silent for 240 s, Psiphon over Lantern verified in 18 s on cell:43235.
        val net = "cell:43235"
        PsiphonChainMemory.recordChainVerified(net, now = 10_000)
        assertTrue(PsiphonChainMemory.preferChain(net, now = 20_000))
        assertTrue(PsiphonChainMemory.describe(net, now = 70_000).contains("Psiphon over Lantern"))
        assertFalse(PsiphonChainMemory.preferChain("wifi:abcdef123456", now = 20_000))
        assertFalse(PsiphonChainMemory.preferChain(net, now = 10_000 + PsiphonChainMemory.HOLD_MS))
        PsiphonChainMemory.recordChainVerified(net, now = 30_000)
        PsiphonChainMemory.recordDirectFailure(net, now = 30_000)
        PsiphonChainMemory.clear(net)
        assertNull(PsiphonChainMemory.chainVerifiedAt(net, now = 40_000))
        assertFalse(PsiphonChainMemory.preferChain(net, now = 40_000))
    }

    @Test fun onlyTheUsersOwnDirectPickIsOfferedTheChainWithOneTap() {
        assertTrue(PsiphonChainMemory.suggestChain(direct = true, exact = true, chainAvailable = true, chainAlreadyTried = false))
        assertFalse("automatic sessions hand over instead",
            PsiphonChainMemory.suggestChain(direct = true, exact = false, chainAvailable = true, chainAlreadyTried = false))
        assertFalse(PsiphonChainMemory.suggestChain(direct = false, exact = true, chainAvailable = true, chainAlreadyTried = false))
        assertFalse(PsiphonChainMemory.suggestChain(direct = true, exact = true, chainAvailable = false, chainAlreadyTried = false))
        assertFalse(PsiphonChainMemory.suggestChain(direct = true, exact = true, chainAvailable = true, chainAlreadyTried = true))
    }
}
