package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 145 (Irancell): a server frozen at 16050 B must not come back through a stealth form or a comparison. */
class FrozenGuardTest {
    @Test fun aFrozenSelectedServerGetsNoStealthAlternateWhileOthersRemain() {
        assertFalse(FrozenGuard.stealthAllowed("fr5", setOf("fr5"), listOf("fr5", "de1", "nl2")))
        assertTrue("not frozen: stealth as before", FrozenGuard.stealthAllowed("de1", setOf("fr5"), listOf("fr5", "de1")))
    }

    @Test fun aFrozenSelectedServerIsTheLastResortWhenEveryOtherFrozeToo() {
        assertTrue(FrozenGuard.stealthAllowed("fr5", setOf("fr5", "de1"), listOf("fr5", "de1")))
        assertTrue(FrozenGuard.stealthAllowed("fr5", setOf("fr5"), emptyList()))
    }

    @Test fun preferUnfrozenDropsFrozenUnlessNothingElseIsLeft() {
        assertEquals(listOf("de1"), FrozenGuard.preferUnfrozen(listOf("fr5", "de1"), setOf("fr5")) { it })
        assertEquals(listOf("fr5"), FrozenGuard.preferUnfrozen(listOf("fr5"), setOf("fr5")) { it })
        assertEquals(listOf("a", "b"), FrozenGuard.preferUnfrozen(listOf("a", "b"), emptySet()) { it })
    }

    @Test fun aFrozenSeedDoesNotCompeteAndIsKeptOnlyAsLastResort() {
        val s = FrozenGuard.split(listOf("fr5"), listOf("de1", "fr9", "nl2"), setOf("fr5", "fr9"), { it }, { it })
        assertEquals(emptyList<String>(), s.seed)
        assertEquals(listOf("de1", "nl2"), s.candidates)
        assertEquals(listOf("fr5"), s.lastResortSeed)
        assertEquals(2, s.leftOut)
    }

    @Test fun whenOnlyFrozenServersExistTheyStillRace() {
        val s = FrozenGuard.split(listOf("fr5"), listOf("fr9"), setOf("fr5", "fr9"), { it }, { it })
        assertEquals(listOf("fr9"), s.candidates)
        assertEquals(listOf("fr5"), s.lastResortSeed)
    }

    @Test fun nothingFrozenChangesNothing() {
        val s = FrozenGuard.split(listOf("a"), listOf("b", "c"), emptySet(), { it }, { it })
        assertEquals(listOf("a"), s.seed)
        assertEquals(listOf("b", "c"), s.candidates)
        assertEquals(0, s.leftOut)
    }
}
