package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.xray.RealDelayProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smart Connect pressed by the user: several working paths are gathered and rechecked a few seconds apart,
 * as the LAB's Full Analysis does, instead of taking the first that answers (which on Irancell is often
 * cut seconds later, or slow).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerRaceCompareTest {
    private fun node(id: String) = VlessProfile(
        id = id, name = "N$id", address = "203.0.113.${id.filter { it.isDigit() }.ifEmpty { "9" }}", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", security = "reality", sni = "s$id.example.com",
        publicKey = "pbk$id", shortId = "ab", flow = "xtls-rprx-vision", fingerprint = "chrome"
    )

    /** Request time per test of each server as saved; null carries nothing. Disguised forms carry nothing. */
    private fun probeFrom(script: Map<String, List<Long?>>, calls: MutableMap<String, Int> = mutableMapOf()) =
        { list: List<VlessProfile>, _: Int ->
            list.map { p ->
                if (p.finalMask.contains("tlshello")) return@map RealDelayProbe.Outcome.Failed("reset")
                val n = calls.merge(p.id, 1, Int::plus)!! - 1
                val ms = script[p.id]?.getOrNull(n)
                if (ms == null) RealDelayProbe.Outcome.Failed("reset") else RealDelayProbe.Outcome.Delay(ms)
            }
        }

    private fun race(script: Map<String, List<Long?>>, calls: MutableMap<String, Int> = mutableMapOf()) =
        ServerRace(probe = probeFrom(script, calls), log = {})

    @Test fun theFirstToAnswerLosesWhenItIsCutOnTheRecheck() {
        val script = mapOf(
            "1" to listOf(150L, null, null),     // fastest once, then cut
            "3" to listOf(700L, 650L, 680L),
            "4" to listOf(500L, 520L, 510L)
        )
        val sleeps = mutableListOf<Long>()
        val win = race(script).run((1..6).map { node("$it") }, { it }, timeoutSec = 6, maxBatches = 3,
            contenders = 3, sleep = { sleeps += it })!!
        assertEquals("4", win.owner.id)
        assertEquals(listOf("3", "1"), win.runnersUp.map { it.owner.id })
        assertEquals("two rechecks, spaced", ContenderPick.RECHECKS, sleeps.size)
    }

    @Test fun theSelectedServerCompetesAndKeepsItsPlaceWhenItIsBest() {
        val selected = node("s")
        val script = mapOf("s" to listOf(300L, 310L), "2" to listOf(900L, 950L, 920L), "5" to listOf(800L, 820L, 810L))
        val win = race(script).run(listOf(node("2"), node("5"), node("6")), { it }, timeoutSec = 6, maxBatches = 2, contenders = 3,
            seed = listOf(ServerRace.Winner(selected, selected, 280L)), sleep = {})!!
        assertEquals("s", win.owner.id)
        assertEquals(2, win.runnersUp.size)
    }

    @Test fun aSlowSelectedServerIsReplaced() {
        val selected = node("s")
        val script = mapOf("s" to listOf(2_500L, 2_600L), "2" to listOf(600L, 620L, 610L))
        val win = race(script).run(listOf(node("2"), node("3")), { it }, timeoutSec = 6, maxBatches = 2, contenders = 3,
            seed = listOf(ServerRace.Winner(selected, selected, 2_400L)), sleep = {})!!
        assertEquals("2", win.owner.id)
        assertEquals(listOf("s"), win.runnersUp.map { it.owner.id })
    }

    @Test fun whatTheLabProvedHereIsRacedFirstAndPreferred() {
        val pool = (1..12).map { node("$it") }
        val ranked = ServerRace.rank(pool, preferred = setOf("11"), limit = ServerRace.FULL_LIMIT)
        assertEquals("11", ranked.first().id)
        val script = mapOf("1" to listOf(400L, 400L, 400L), "2" to listOf(450L, 450L, 450L), "11" to listOf(900L, 900L, 900L))
        val win = race(script).run(ranked, { it }, timeoutSec = 6, maxBatches = 3, contenders = 3, preferred = setOf("11"), sleep = {})!!
        assertEquals("11", win.owner.id)
    }

    @Test fun aStallMeasuredHereRanksAServerLower() {
        val pool = listOf(node("1"), node("2"))
        val ranked = ServerRace.rank(pool, penaltyMs = { if (it.id == "1") RouteSpeedBook.STALL_PENALTY_MS else 0L })
        assertEquals(listOf("2", "1"), ranked.map { it.id })
    }

    @Test fun oneContenderKeepsTheFastFirstAnswerRace() {
        val calls = mutableMapOf<String, Int>()
        val win = race(mapOf("1" to listOf(150L)), calls).run((1..6).map { node("$it") }, { it }, timeoutSec = 6, maxBatches = 3)!!
        assertEquals("1", win.owner.id)
        assertTrue(win.runnersUp.isEmpty())
        assertEquals("no recheck after a failure", 1, calls["1"])
    }
}
