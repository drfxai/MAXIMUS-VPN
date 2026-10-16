package com.example.vpn.smart

import com.example.data.model.ServerCategory
import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Optimal Node agent's shortlist: order, the favorite bonus, and that dead nodes drop out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartConnectRankTest {
    private fun node(
        id: String,
        score: Double,
        stability: Double = 100.0,
        latency: Long? = 100L,
        loss: Double = 0.0,
        favorite: Boolean = false,
        category: ServerCategory = ServerCategory.BALANCED
    ) = VlessProfile(
        id = id, name = "N$id", address = "203.0.113.${id.filter { it.isDigit() }.ifEmpty { "1" }}", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", security = "reality", sni = "www.example.com",
        publicKey = "pbk$id", shortId = "ab", flow = "xtls-rprx-vision", fingerprint = "chrome",
        overallScore = score, stability = stability, lastLatencyMs = latency, packetLoss = loss,
        isFavorite = favorite, category = category
    )

    @Test fun ranksBestFirstAndCapsAtTheRequestedCount() {
        val pool = listOf(node("1", 40.0), node("2", 90.0), node("3", 70.0), node("4", 55.0))
        val top = SmartConnect.rankTopNodes(pool, count = 3)
        assertEquals(listOf("2", "3", "4"), top.map { it.profile.id })
    }

    @Test fun aFavoriteLeadsEvenWithALowerScore() {
        val pool = listOf(node("1", 95.0), node("2", 30.0, favorite = true))
        val top = SmartConnect.rankTopNodes(pool, count = 2)
        assertEquals("2", top.first().profile.id)
    }

    @Test fun offlineAndHighLossNodesDropOutWhenHealthyOnesExist() {
        val pool = listOf(
            node("ok", 50.0),
            node("off", 99.0, category = ServerCategory.OFFLINE),
            node("lossy", 99.0, loss = 55.0)
        )
        val top = SmartConnect.rankTopNodes(pool, count = 3)
        assertEquals(listOf("ok"), top.map { it.profile.id })
    }

    @Test fun selectBestNodeIsTheTopOfTheRanking() {
        val pool = listOf(node("1", 40.0), node("2", 90.0))
        assertEquals(SmartConnect.rankTopNodes(pool, count = 1).firstOrNull()?.profile?.id,
            SmartConnect.selectBestNode(pool)?.profile?.id)
        assertEquals("2", SmartConnect.selectBestNode(pool)?.profile?.id)
    }

    @Test fun emptyInEmptyOut() {
        assertTrue(SmartConnect.rankTopNodes(emptyList()).isEmpty())
    }

    @Test fun theCardLeadsWithTheServerInUseAndPutsServersThatFailedHereLast() {
        val e = com.example.vpn.connectivity.PathEligibility
        // Phone report 2026-10-16: DE (best rating) failed here, US carried the traffic, yet DE led the card.
        val de = node("1", 100.0); val ru = node("2", 60.0); val fi = node("3", 50.0); val us = node("4", 10.0)
        val evidence = mapOf("1" to e.RECENTLY_FAILED, "2" to e.UNTESTED, "3" to e.FRESH_VERIFIED, "4" to e.FRESH_VERIFIED)
        val card = SmartConnect.rankForCard(listOf(de, ru, fi, us), active = us, evidenceOf = { evidence[it.id] })
        assertEquals(listOf("4", "3", "2"), card.map { it.profile.id })
        assertTrue(card[0].inUse)
        // Not connected: the best server by this network's evidence leads, never one that just failed here.
        val idle = SmartConnect.rankForCard(listOf(de, ru, fi, us), evidenceOf = { evidence[it.id] }, count = 4)
        assertEquals(listOf("3", "4", "2", "1"), idle.map { it.profile.id })
        assertTrue(idle.none { it.inUse })
    }
}
