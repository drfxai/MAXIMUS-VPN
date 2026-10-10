package com.example.vpn.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineChainTest {
    private fun chain(vararg ids: String) = EngineChain.Chain(ids.map { EngineChain.Hop(it) })

    @Test fun twoDistinctChainableEnginesAreValid() {
        assertNull(EngineChain.problem(chain("psiphon", "tor")))
        assertNull(EngineChain.problem(chain("tor", "psiphon")))
    }

    @Test fun oneHopIsTooFew() = assertTrue(EngineChain.problem(chain("psiphon"))!!.contains("at least"))

    @Test fun threeHopsAreTooMany() =
        assertTrue(EngineChain.problem(chain("psiphon", "tor", "psiphon"))!!.contains("at most"))

    @Test fun theSameEngineTwiceIsRejected() =
        assertTrue(EngineChain.problem(chain("tor", "tor"))!!.contains("once"))

    @Test fun anUnchainableEngineIsRejected() =
        assertTrue(EngineChain.problem(chain("psiphon", "mihomo"))!!.contains("cannot be part"))

    @Test fun planStartsAtTheExitAndWiresEachHopToTheNext() {
        val steps = EngineChain.plan(chain("psiphon", "tor"))
        // Start order is exit (tor, index 1) first, then entry (psiphon, index 0).
        assertEquals(listOf(1, 0), steps.map { it.index })
        val exit = steps.first { it.index == 1 }
        val entry = steps.first { it.index == 0 }
        assertNull(exit.upstreamHop)          // the exit dials the internet directly
        assertEquals(1, entry.upstreamHop)     // the entry dials through the exit
    }

    @Test fun describeNamesTheHopsInOrder() =
        assertEquals("Psiphon  →  Tor", EngineChain.describe(chain("psiphon", "tor")) {
            if (it == "psiphon") "Psiphon" else "Tor"
        })

    @Test fun hopProfileCarriesTheHopsKnobs() {
        val psiphon = EngineChain.hopProfile(EngineChain.Hop("psiphon", region = "DE"))
        assertEquals("DE", PsiphonSidecar.region(psiphon))
        val tor = EngineChain.hopProfile(EngineChain.Hop("tor", bridges = "obfs4 1.2.3.4:443 CERT cert=x iat-mode=0"))
        assertTrue(TorSidecar.bridges(tor).isNotEmpty())
    }
}
