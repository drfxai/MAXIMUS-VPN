package com.example

import com.example.data.model.VlessProfile
import com.example.vpn.hub.FreeListSwap
import com.example.vpn.hub.LastKnownGoodPool
import com.example.vpn.hub.RetainedFreeConfigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2: three last-known-good free configs and an atomic list swap. */
class LastKnownGoodTest {
    private fun p(n: Int, id: String = "id$n") = VlessProfile(
        id = id, name = "Free $n", address = "203.0.113.$n", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.example", canonicalFingerprint = "fp$n"
    )

    private fun pool(): LastKnownGoodPool {
        var saved: String? = null
        return LastKnownGoodPool({ saved }, { saved = it })
    }

    @Test fun thePoolHoldsTheActiveConfigAndTheTwoBestRecentBackups() {
        val pool = pool()
        pool.recordVerified(p(1), 200, now = 1, activeFingerprint = "fp1")
        pool.recordVerified(p(2), 210, now = 2, activeFingerprint = "fp2")
        pool.recordVerified(p(3), 220, now = 3, activeFingerprint = "fp3")
        assertEquals(setOf("id1", "id2", "id3"), pool.protectedIds())
        // A fourth verified config pushes out the one verified longest ago, but never the active one.
        pool.recordVerified(p(1), 190, now = 4, activeFingerprint = "fp1")
        pool.recordVerified(p(4), 230, now = 5, activeFingerprint = "fp1")
        assertEquals(setOf("id1", "id3", "id4"), pool.protectedIds())
        assertEquals(LastKnownGoodPool.SIZE, pool.entries().size)
    }

    @Test fun thePoolSurvivesARestart() {
        var saved: String? = null
        LastKnownGoodPool({ saved }, { saved = it }).recordVerified(p(1), 200, now = 1)
        assertEquals(setOf("id1"), LastKnownGoodPool({ saved }, { saved = it }).protectedIds())
    }

    @Test fun deadEntriesLeaveButNotTheOneInUse() {
        val pool = pool()
        pool.recordVerified(p(1), 200, now = 1)
        pool.recordVerified(p(2), 200, now = 2)
        pool.dropDead({ true }, activeFingerprint = "fp2")
        assertEquals(setOf("id2"), pool.protectedIds())
    }

    @Test fun aRefreshNeverDeletesProtectedOrInUseConfigs() {
        val saved = (1..50).map { p(it) }
        val fresh = (51..100).map { p(it) }
        val plan = FreeListSwap.plan(saved, fresh, protectedIds = setOf("id1", "id2", "id3"), activeId = "id4")
        assertEquals(setOf("id1", "id2", "id3", "id4"), plan.retained.map { it.id }.toSet())
        assertTrue(plan.delete.none { it.id in setOf("id1", "id2", "id3", "id4") })
        // At most 50 result: four retained configs take places first.
        assertEquals(46, plan.insert.size)
        assertEquals(50, plan.resultingCount)
    }

    @Test fun configsInBothListsKeepTheirSavedRowsAndResults() {
        val saved = listOf(p(1), p(2).copy(lastLatencyMs = 180))
        val fresh = listOf(p(2, id = "new2"), p(3))
        val plan = FreeListSwap.plan(saved, fresh, emptySet(), null)
        assertEquals(listOf("id2"), plan.kept.map { it.id })
        assertEquals(listOf("id3"), plan.insert.map { it.id })
        assertEquals(listOf("id1"), plan.delete.map { it.id })
        assertEquals(180L, plan.kept.single().lastLatencyMs)
    }

    @Test fun anEmptyNewListRemovesOnlyUnprotectedConfigs() {
        val saved = (1..5).map { p(it) }
        val plan = FreeListSwap.plan(saved, emptyList(), setOf("id1"), activeId = "id2")
        assertEquals(setOf("id1", "id2"), plan.retained.map { it.id }.toSet())
        assertEquals(3, plan.delete.size)
    }

    @Test fun theResultIsCappedAtFifty() {
        val plan = FreeListSwap.plan(emptyList(), (1..80).map { p(it) }, emptySet(), null)
        assertEquals(50, plan.insert.size)
        assertEquals((1..50).map { "id$it" }, plan.insert.map { it.id })
    }

    @Test fun manyFavouritesNeverPushTheListPastFifty() {
        val saved = (1..60).map { p(it).copy(isFavorite = true) }
        val fresh = (61..100).map { p(it) }
        val plan = FreeListSwap.plan(saved, fresh, protectedIds = setOf("id60"), activeId = "id59")
        assertEquals(50, plan.resultingCount)
        // The one in use and the protected one always stay; favourites fill what room is left, new ones none.
        assertTrue(plan.retained.map { it.id }.containsAll(listOf("id59", "id60")))
        assertTrue(plan.insert.isEmpty())
        assertEquals(10, plan.delete.size)
        assertTrue(plan.delete.none { it.id == "id59" || it.id == "id60" })
    }

    @Test fun keptConfigsBeyondTheLimitMakeWayInTheNewListsOrder() {
        val saved = (1..50).map { p(it) }
        val fresh = (50 downTo 1).map { p(it) } + (51..60).map { p(it) }
        val plan = FreeListSwap.plan(saved, fresh, protectedIds = setOf("id1"), activeId = null)
        assertEquals(50, plan.resultingCount)
        assertTrue(plan.insert.isEmpty())
        assertTrue(plan.delete.isEmpty())
        assertEquals("id1", plan.kept.first().id)
        assertEquals("id50", plan.kept[1].id)
    }

    @Test fun retainedConfigsAreRememberedUntilReleased() {
        var ids = emptySet<String>()
        val store = RetainedFreeConfigs({ ids }, { ids = it })
        store.update(retained = listOf("a", "b"), gone = emptyList())
        store.update(retained = emptyList(), gone = listOf("a"))
        assertEquals(setOf("b"), store.ids())
    }
}
