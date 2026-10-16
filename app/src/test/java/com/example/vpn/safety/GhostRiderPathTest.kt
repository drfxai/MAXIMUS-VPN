package com.example.vpn.safety

import com.example.data.model.OperationalMode
import com.example.vpn.sidecar.EngineChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ghost Rider's connect path prefers a Tor multi-hop but never fails closed into being offline: the
 * preference always ends in the hardened-servers floor, and the Psiphon → Tor chain it names is valid.
 */
class GhostRiderPathTest {
    @Test fun prefersPsiphonTorChainWhenBothAvailable() {
        val steps = GhostRiderPath.preference(setOf(GhostRiderPath.PSIPHON, GhostRiderPath.TOR, "mihomo"))
        assertEquals(
            listOf(
                GhostRiderPath.Step.CHAIN_PSIPHON_TOR,
                GhostRiderPath.Step.TOR_ONLY,
                GhostRiderPath.Step.HARDENED_SERVERS
            ),
            steps
        )
    }

    @Test fun fallsBackToTorAloneWithoutPsiphon() {
        val steps = GhostRiderPath.preference(setOf(GhostRiderPath.TOR))
        assertEquals(listOf(GhostRiderPath.Step.TOR_ONLY, GhostRiderPath.Step.HARDENED_SERVERS), steps)
    }

    @Test fun alwaysEndsInHardenedServersSoAConnectNeverDeadEnds() {
        // No Tor path possible: privacy degrades to a working connection rather than to nothing.
        val steps = GhostRiderPath.preference(setOf("mihomo"))
        assertEquals(listOf(GhostRiderPath.Step.HARDENED_SERVERS), steps)
        // Every preference, whatever the engines, ends in the floor.
        assertTrue(GhostRiderPath.preference(emptySet()).last() == GhostRiderPath.Step.HARDENED_SERVERS)
    }

    @Test fun theChainIsPsiphonEntryTorExitAndValid() {
        val chain = GhostRiderPath.chain()
        assertEquals(GhostRiderPath.PSIPHON, chain.hops.first().engineId)
        assertEquals(GhostRiderPath.TOR, chain.hops.last().engineId)
        // Valid per the chain planner: two distinct, chainable engines.
        assertNull(EngineChain.problem(chain))
    }

    @Test fun ghostRiderAlwaysFailsClosed() {
        // A server that cannot be resolved never releases the block in Ghost Rider, as in GOD MODE.
        assertTrue(
            !FailClosedPolicy.mayReleaseBlock(
                FailClosedPolicy.Failure.UNRESOLVABLE_SERVER, startedByUser = true, mode = OperationalMode.GHOST_RIDER
            )
        )
        // Even an invalid profile the user chose keeps the block (unlike DAILY).
        assertTrue(
            !FailClosedPolicy.mayReleaseBlock(
                FailClosedPolicy.Failure.INVALID_PROFILE, startedByUser = true, mode = OperationalMode.GHOST_RIDER
            )
        )
    }
}
