package com.example.vpn.smart

import com.example.vpn.smart.ContenderPick.Contender
import org.junit.Assert.assertEquals
import org.junit.Test

/** Smart Connect chooses among working paths the way the LAB's Full Analysis ranks them, not by the first answer. */
class ContenderPickTest {
    private fun pick(vararg c: Contender<String>) = ContenderPick.order(c.toList()).map { it.item }

    @Test fun aPathThatFailedARecheckGoesBehindOneThatPassedEveryRecheck() {
        // Fastest on the first request, then cut: the pattern on Irancell.
        val cut = Contender("cut", 0, listOf(200L, null, null))
        val steady = Contender("steady", 0, listOf(900L, 850L, 880L))
        assertEquals(listOf("steady", "cut"), pick(cut, steady))
    }

    @Test fun theMedianDecidesNotOneLuckyRequest() {
        val lucky = Contender("lucky", 0, listOf(150L, 1400L, 1300L))
        val even = Contender("even", 0, listOf(600L, 620L, 640L))
        assertEquals(listOf("even", "lucky"), pick(lucky, even))
    }

    @Test fun whatTheLabProvedHereWinsAmongStablePaths() {
        val lab = Contender("lab", 1, listOf(700L, 720L, 710L), preferred = true)
        val other = Contender("other", 0, listOf(400L, 410L, 420L))
        assertEquals(listOf("lab", "other"), pick(other, lab))
        // ...but not over a stable path when it failed its own recheck now.
        val labCut = lab.copy(samples = listOf(700L, null, 710L))
        assertEquals(listOf("other", "lab"), pick(other, labCut))
    }

    @Test fun aStallMeasuredHereOutweighsAFasterFirstRequest() {
        val stalledBefore = Contender("stalled", 0, listOf(300L, 300L, 300L), penaltyMs = RouteSpeedBook.STALL_PENALTY_MS)
        val fine = Contender("fine", 2, listOf(900L, 900L, 900L))
        assertEquals(listOf("fine", "stalled"), pick(stalledBefore, fine))
    }

    @Test fun theKindStepKeepsHttpsLookingPathsAheadWhenTimesAreClose() {
        val quic = Contender("quic", 3, listOf(500L, 500L, 500L))
        val reality = Contender("reality", 0, listOf(800L, 800L, 800L))
        assertEquals(listOf("reality", "quic"), pick(quic, reality))
    }

    @Test fun rechecksAreSpacedLikeTheLabsStableWindow() {
        assertEquals(ContenderPick.RECHECK_GAP_MS, ContenderPick.waitBeforeRecheck(lastAt = 1_000, now = 1_000))
        assertEquals(1_000L, ContenderPick.waitBeforeRecheck(lastAt = 1_000, now = 3_000))
        assertEquals(0L, ContenderPick.waitBeforeRecheck(lastAt = 1_000, now = 9_000))
        assertEquals(com.example.vpn.lab.FullAnalysis.STABLE_WINDOW_MS, ContenderPick.RECHECK_GAP_MS)
    }

    @Test fun median() {
        assertEquals(5L, ContenderPick.median(listOf(9L, 1L, 5L)))
        assertEquals(4L, ContenderPick.median(listOf(2L, 6L)))
    }
}
