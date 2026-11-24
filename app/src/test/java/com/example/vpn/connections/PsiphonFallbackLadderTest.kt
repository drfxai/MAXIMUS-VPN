package com.example.vpn.connections

import com.example.vpn.connections.PsiphonFallbackLadder.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PsiphonFallbackLadderTest {
    private fun next(
        failedDirect: Boolean = true, failedSok: Boolean = false, allowed: Boolean = true,
        sokAvailable: Boolean = true, sokTried: Boolean = false, chainAvailable: Boolean = true,
        chainTried: Boolean = false, chainVerified: Boolean = false
    ) = PsiphonFallbackLadder.next(failedDirect, failedSok, allowed, sokAvailable, sokTried, chainAvailable, chainTried, chainVerified)

    @Test fun failedDirectPsiphonTriesShirOKhorshidFirst() = assertEquals(Step.SHIROKHORSHID, next())

    @Test fun chainVerifiedHereGoesStraightToTheChain() = assertEquals(Step.CHAIN, next(chainVerified = true))

    @Test fun noShirOKhorshidEngineFallsBackToTheChain() = assertEquals(Step.CHAIN, next(sokAvailable = false))

    @Test fun shirOKhorshidAlreadyTriedFallsBackToTheChain() = assertEquals(Step.CHAIN, next(sokTried = true))

    @Test fun failedShirOKhorshidFallbackHandsOverToTheChain() =
        assertEquals(Step.CHAIN, next(failedDirect = false, failedSok = true))

    @Test fun shirOKhorshidTheUserPickedNeverHandsOver() = assertNull(next(failedDirect = false, failedSok = false))

    @Test fun pinnedLockedOrForcedSessionsNeverHandOver() {
        assertNull(next(allowed = false))
        assertNull(next(failedDirect = false, failedSok = true, allowed = false))
    }

    @Test fun nothingLeftEndsTheLadder() {
        assertNull(next(sokAvailable = false, chainTried = true))
        assertNull(next(failedDirect = false, failedSok = true, chainAvailable = false))
    }
}
