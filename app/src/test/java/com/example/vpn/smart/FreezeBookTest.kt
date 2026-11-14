package com.example.vpn.smart

import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-network last-good / frozen memory steers Smart Connect's test order on that network only. */
class FreezeBookTest {
    private var stored: String? = null
    private var now = 10_000_000L
    private fun book() = FreezeBook({ stored }, { stored = it }, { now })
    private val net = "cell:43235"

    private fun p(id: String) = VlessProfile(id = id, name = id, address = "$id.example", port = 443, uuid = "u")

    @Test fun lastGoodIsRememberedPerNetworkAcrossARestartUntilItsTtl() {
        book().recordGood(net, "a")
        now += 1
        book().recordGood(net, "b", rescue = "deep-fragment")
        assertEquals(listOf("b", "a"), book().lastGood(net))
        assertEquals("deep-fragment", book().good(net, "b")?.rescue)
        assertTrue(book().lastGood("wifi").isEmpty())
        now += FreezeBook.TTL_MS
        assertTrue(book().lastGood(net).isEmpty())
    }

    @Test fun aFreezeRemovesLastGoodAndCoolsDown() {
        val b = book()
        b.recordGood(net, "a")
        b.recordFrozen(net, "a")
        assertTrue(b.lastGood(net).isEmpty())
        assertEquals(setOf("a"), book().frozen(net))
        now += FreezeBook.FROZEN_COOLDOWN_MS
        assertTrue(book().frozen(net).isEmpty())
    }

    @Test fun passingAgainClearsTheFreeze() {
        val b = book()
        b.recordFrozen(net, "a")
        b.recordGood(net, "a")
        assertTrue(b.frozen(net).isEmpty())
        assertEquals(listOf("a"), b.lastGood(net))
        assertNull(b.good("wifi", "a"))
    }

    @Test fun orderPutsLastGoodFirstAndFrozenLast() {
        val ranked = listOf(p("x"), p("frozen"), p("y"), p("good"))
        val o = FreezeBook.order(ranked, lastGood = listOf("good"), frozen = setOf("frozen"))
        assertEquals(listOf("good", "x", "y", "frozen"), o.profiles.map { it.id })
        assertEquals(1, o.lastGoodFirst)
        assertEquals(1, o.frozenDeferred)
        assertEquals("smart · order · last-good first: 1, frozen deferred: 1", o.describe())
    }

    @Test fun lastGoodCutByTheRaceLimitIsStillTestedFirst() {
        val o = FreezeBook.order(listOf(p("x"), p("y")), lastGood = listOf("old", "missing"), frozen = emptySet(),
            eligibleLastGood = listOf(p("old")))
        assertEquals(listOf("old", "x", "y"), o.profiles.map { it.id })
        assertEquals(1, o.lastGoodFirst)
        assertEquals(0, o.frozenDeferred)
    }

    @Test fun aFrozenServerIsNeverCountedAsLastGood() {
        val o = FreezeBook.order(listOf(p("a"), p("b")), lastGood = listOf("a"), frozen = setOf("a"))
        assertEquals(listOf("b", "a"), o.profiles.map { it.id })
        assertEquals(0, o.lastGoodFirst)
        assertEquals(1, o.frozenDeferred)
    }

    @Test fun noMemoryKeepsTheRaceOrder() {
        val ranked = listOf(p("a"), p("b"), p("c"))
        val o = FreezeBook.order(ranked, emptyList(), emptySet())
        assertEquals(ranked, o.profiles)
        assertEquals("smart · order · last-good first: 0, frozen deferred: 0", o.describe())
    }

    @Test fun networkMemoryClearForgetsFreezes() {
        var freezes: String? = null
        val m = NetworkMemory({ null }, {}, loadFreezes = { freezes }, saveFreezes = { freezes = it })
        m.freezes.recordFrozen(net, "a")
        assertEquals(setOf("a"), NetworkMemory({ null }, {}, loadFreezes = { freezes }, saveFreezes = { freezes = it }).freezes.frozen(net))
        m.clear()
        assertTrue(NetworkMemory({ null }, {}, loadFreezes = { freezes }, saveFreezes = { freezes = it }).freezes.frozen(net).isEmpty())
    }
}
