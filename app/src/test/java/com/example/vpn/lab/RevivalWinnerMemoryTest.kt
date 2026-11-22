package com.example.vpn.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What revived a config on a network is tried first there for about a day, and on no other network. */
class RevivalWinnerMemoryTest {
    private var stored: String? = null
    private var now = 5_000_000L
    private fun memory() = RevivalWinnerMemory({ stored }, { stored = it }, { now })
    private val net = "cell:43235"

    @Test fun theWinnerIsRememberedPerNetworkAcrossARestart() {
        memory().record(net, "fp-a", "ech-cloudflare-1")
        assertEquals("ech-cloudflare-1", memory().recipeFor(net, "fp-a"))
        assertNull(memory().recipeFor("wifi:home", "fp-a"))
        assertNull(memory().recipeFor(net, "fp-b"))
    }

    @Test fun itExpiresAfterADay() {
        val m = memory()
        m.record(net, "fp-a", "bpb-fragment-v1@v1")
        now += RevivalWinnerMemory.TTL_MS - 1
        assertEquals("bpb-fragment-v1@v1", m.recipeFor(net, "fp-a"))
        now += 1
        assertNull(m.recipeFor(net, "fp-a"))
        assertEquals(0.0, m.boost(net, "fp-a", "bpb-fragment-v1"), 0.0)
    }

    @Test fun theConfigsOwnWinLeadsThenAWinOnAnotherConfigHere() {
        val m = memory()
        m.record(net, "fp-a", "bpb-fragment-v1@v1")
        m.record(net, "fp-b", "ech-cloudflare-1")
        assertEquals(RevivalWinnerMemory.OWN_BOOST, m.boost(net, "fp-a", "bpb-fragment-v1@v1"), 0.0)
        assertEquals(RevivalWinnerMemory.OWN_BOOST, m.boost(net, "fp-a", "bpb-fragment-v1"), 0.0)
        assertEquals(RevivalWinnerMemory.NETWORK_BOOST, m.boost(net, "fp-a", "ech-cloudflare-1"), 0.0)
        assertEquals(0.0, m.boost(net, "fp-a", "alpn-http11"), 0.0)
        assertEquals(0.0, m.boost("wifi:home", "fp-a", "bpb-fragment-v1"), 0.0)
    }

    @Test fun aNewerWinReplacesTheOlderOneForTheSameConfig() {
        val m = memory()
        m.record(net, "fp-a", "bpb-fragment-v1@v1")
        m.record(net, "fp-a", "ech-cloudflare-1")
        assertEquals("ech-cloudflare-1", memory().recipeFor(net, "fp-a"))
        assertEquals(1, memory().wins(net).size)
    }

    @Test fun configsThatWonHereLeadTheNewestFirstAndTheRestKeepTheirOrder() {
        val m = memory()
        m.record(net, "c", "r1")
        now += 1
        m.record(net, "d", "r2")
        assertEquals(listOf("d", "c", "a", "b", "e"), m.winnersFirst(net, listOf("a", "b", "c", "d", "e")) { it })
        assertEquals(listOf("a", "b", "c"), m.winnersFirst("wifi:home", listOf("a", "b", "c")) { it })
    }

    @Test fun blankInputIsIgnored() {
        val m = memory()
        m.record(null, "fp", "r")
        m.record(net, "", "r")
        m.record(net, "fp", "")
        assertEquals(0, m.wins(net).size)
        assertNull(stored)
    }
}
