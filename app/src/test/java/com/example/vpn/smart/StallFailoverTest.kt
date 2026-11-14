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

    @Test fun aStallWithinTheFirstSegmentIsAnMtuBlackHoleLaterOnesAreACap() {
        assertEquals(StallFailover.StallClass.MTU, StallFailover.classify(0))
        assertEquals(StallFailover.StallClass.MTU, StallFailover.classify(1400))
        assertEquals(StallFailover.StallClass.MTU, StallFailover.classify(StallFailover.MTU_STALL_MAX_BYTES))
        assertEquals(StallFailover.StallClass.CAP, StallFailover.classify(1501))
        assertEquals(StallFailover.StallClass.CAP, StallFailover.classify(16050))
        assertEquals(null, StallFailover.classify(null))
        assertTrue(StallFailover.mtuTrialFor(1200))
        assertFalse(StallFailover.mtuTrialFor(16050))
        assertFalse(StallFailover.mtuTrialFor(null))
    }

    private val networks = mapOf(
        "nl1" to HostPrefix.keys("1.2.3.4"), "nl2" to HostPrefix.keys("1.2.3.77"),
        "named" to HostPrefix.keys("nl.example.com", "1.2.3.9"), "de" to HostPrefix.keys("5.6.7.8"),
        "cdn" to HostPrefix.keys("cdn.example.com"))

    @Test fun aCapStallSkipsServersOnTheStalledServersNetwork() {
        val i = input(runnersUp = listOf("nl2", "named", "de"), candidates = listOf("nl1", "nl2", "named", "de", "cdn"))
            .copy(stallBytes = 30_000, stalledKeys = HostPrefix.keys("1.2.3.4"), networks = networks)
        assertEquals(Decision.RunnerUp("de"), StallFailover.decide(i))
        val race = StallFailover.decide(i.copy(runnersUp = listOf("nl2")))
        assertEquals(Decision.Race(setOf("nl1", "nl2", "named")), race)
    }

    @Test fun anMtuStallKeepsTheSameNetworkServers() {
        val i = input(runnersUp = listOf("nl2", "de"), candidates = listOf("nl1", "nl2", "de"))
            .copy(stallBytes = 1200, stalledKeys = HostPrefix.keys("1.2.3.4"), networks = networks)
        assertEquals(Decision.RunnerUp("nl2"), StallFailover.decide(i))
        // Unknown bytes (the user's "Try another server") keep the old behaviour too.
        assertEquals(Decision.RunnerUp("nl2"), StallFailover.decide(i.copy(stallBytes = null)))
    }

    @Test fun aCapStallWithOnlySameNetworkServersLeftEscalates() {
        val i = input(runnersUp = listOf("nl2"), candidates = listOf("nl1", "nl2", "named"))
            .copy(stallBytes = 16050, stalledKeys = HostPrefix.keys("1.2.3.4"), networks = networks)
        val d = StallFailover.decide(i)
        assertTrue(d is Decision.Escalate)
        assertTrue((d as Decision.Escalate).reason.contains("network"))
    }

    @Test fun theStallLineNamesBytesClassAndPrefix() {
        assertEquals("failover · stall · bytes=16050 class=cap prefix=1.2.3.0/24 (same-network servers left out: 2) · MTU trial skipped",
            StallFailover.stallLine(16050, HostPrefix.keys("1.2.3.4"), 2))
        assertEquals("failover · stall · bytes=1200 class=mtu prefix=unresolved",
            StallFailover.stallLine(1200, HostPrefix.keys("nl.example.com")))
    }

    @Test fun sameNetworkMatchesAnyKeyAndNeverTheStalledServerItself() {
        assertEquals(setOf("nl2", "named"), StallFailover.sameNetwork("nl1", HostPrefix.keys("1.2.3.4"), networks))
        assertEquals(setOf("nl1", "nl2"), StallFailover.sameNetwork("named", HostPrefix.keys("nl.example.com", "1.2.3.9"), networks))
        assertTrue(StallFailover.sameNetwork("nl1", emptySet(), networks).isEmpty())
    }
}
