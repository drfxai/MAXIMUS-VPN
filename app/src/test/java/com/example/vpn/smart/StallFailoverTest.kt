package com.example.vpn.smart

import com.example.vpn.smart.StallFailover.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bulk download that stalled outside the ~16 KB window: a runner-up, then a race without the stalled and
 * frozen servers, then the later Smart Connect stages once; never silent, never for a pinned config.
 */
class StallFailoverTest {
    private fun input(
        pinned: Boolean = false, switches: Int = 0, runnersUp: List<String> = emptyList(),
        frozen: Set<String> = emptySet(), candidates: List<String> = emptyList(), escalated: Boolean = false,
        max: Int = StallFailover.MAX_SWITCHES
    ) = StallFailover.Input("nl1", pinned, switches, runnersUp, frozen, candidates, escalated, max)

    @Test fun aRunnerUpThatDidNotFreezeHereComesFirst() {
        assertEquals(Decision.RunnerUp("de"),
            StallFailover.decide(input(runnersUp = listOf("nl1", "fr", "de"), frozen = setOf("fr"), candidates = listOf("nl1", "de", "uk"))))
    }

    @Test fun withNoRunnerUpTheSavedServersAreRacedWithoutTheStalledAndFrozenOnes() {
        val d = StallFailover.decide(input(frozen = setOf("fr"), candidates = listOf("nl1", "fr", "uk")))
        assertEquals(Decision.Race(setOf("nl1", "fr")), d)
        assertEquals("race", StallFailover.action(d))
    }

    @Test fun theFieldCaseWithOneXrayNodeEscalatesInsteadOfReturningSilently() {
        // Irancell: one verified node stalled at 3781 bytes, no runner-up, nothing else saved.
        val d = StallFailover.decide(input(candidates = listOf("nl1")))
        assertTrue(d is Decision.Escalate)
        assertEquals("escalate", StallFailover.action(d))
    }

    @Test fun theSwitchCapEscalatesOnceThenStays() {
        assertTrue(StallFailover.decide(input(switches = 2, runnersUp = listOf("de"), candidates = listOf("de"))) is Decision.Escalate)
        val after = StallFailover.decide(input(switches = 2, runnersUp = listOf("de"), candidates = listOf("de"), escalated = true))
        assertTrue(after is Decision.Stay)
        assertEquals("stay", StallFailover.action(after))
    }

    @Test fun everyServerStalledOrFrozenAfterTheLaterStagesRanStays() {
        assertTrue(StallFailover.decide(input(frozen = setOf("de"), candidates = listOf("nl1", "de"), escalated = true)) is Decision.Stay)
    }

    @Test fun aPinnedConfigIsNeverMoved() {
        val d = StallFailover.decide(input(pinned = true, runnersUp = listOf("de"), candidates = listOf("de")))
        assertTrue(d is Decision.Stay)
        assertTrue(StallFailover.reason(d).contains("pinned"))
    }

    @Test fun theUsersTryAnotherServerIsNotHeldBackByTheAutomaticCap() {
        assertEquals(Decision.RunnerUp("de"),
            StallFailover.decide(input(switches = 5, runnersUp = listOf("de"), candidates = listOf("de"), max = Int.MAX_VALUE)))
    }

    @Test fun aFreezeFailoverThatGaveUpEscalatesOnceAndNeverWhenPinned() {
        assertTrue(StallFailover.escalatesAfterFreeze(pinned = false, escalated = false))
        assertFalse(StallFailover.escalatesAfterFreeze(pinned = false, escalated = true))
        assertFalse(StallFailover.escalatesAfterFreeze(pinned = true, escalated = false))
    }

    @Test fun everyDecisionHasALogLine() {
        val name = { id: String -> id.uppercase() }
        assertTrue(StallFailover.describe(Decision.RunnerUp("de"), name).contains("DE"))
        assertTrue(StallFailover.describe(Decision.Race(setOf("nl1")), name).startsWith("failover · stall · SWITCH"))
        assertTrue(StallFailover.describe(Decision.Escalate("x"), name).contains("ESCALATE"))
        assertTrue(StallFailover.describe(Decision.Stay("y"), name).contains("STAY (y)"))
    }
}
