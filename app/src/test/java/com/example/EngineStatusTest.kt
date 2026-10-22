package com.example

import com.example.vpn.engine.registry.EngineLedger
import com.example.vpn.engine.registry.EngineRegistry
import com.example.vpn.engine.registry.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V6 items 2, 43 and 83: one engine state for the LAB, diagnostics and the release check. */
class EngineStatusTest {
    private var now = 1_000_000L
    private val ledger = EngineLedger { now }
    private val tor = EngineRegistry.TOR

    private fun state(network: String = "wifi-a", bundled: Boolean? = true, problem: String? = null) =
        ledger.status(tor, network, bundled, problem).state

    @Test fun theBuildDecidesBeforeAnyEvidence() {
        assertEquals(EngineState.UNSUPPORTED, ledger.status(EngineRegistry.NAIVE, "wifi-a", null, null).state)
        assertEquals(EngineState.NOT_BUNDLED, state(bundled = false))
        assertEquals(EngineState.NOT_CONFIGURED, state(problem = "needs settings"))
        assertEquals(EngineState.AVAILABLE, state())
        // Evidence never lifts an engine this phone cannot run.
        ledger.verified(tor.id, "wifi-a")
        assertEquals(EngineState.NOT_BUNDLED, state(bundled = false))
    }

    @Test fun onlyFreshTrafficOnThisNetworkCountsAsVerified() {
        ledger.started(tor.id, "wifi-a")
        assertEquals(EngineState.STARTED, state())
        ledger.verified(tor.id, "wifi-a")
        assertEquals(EngineState.TRAFFIC_VERIFIED, state())
        // Another network learns nothing from it.
        assertEquals(EngineState.AVAILABLE, state(network = "cell-irancell"))
        // Old evidence is not a current state.
        now += EngineLedger.FRESH_MS + 1
        assertEquals(EngineState.AVAILABLE, state())
    }

    @Test fun failuresAreFailedOrDegraded() {
        ledger.failed(tor.id, "wifi-a", "did not start")
        assertEquals(EngineState.FAILED, state())
        ledger.verified(tor.id, "wifi-a")
        ledger.failed(tor.id, "wifi-a", "TLS_HANDSHAKE")
        val s = ledger.status(tor, "wifi-a", true, null)
        assertEquals(EngineState.DEGRADED, s.state)
        assertTrue(s.detail.contains("TLS_HANDSHAKE"))
        ledger.verified(tor.id, "wifi-a")
        assertEquals(EngineState.TRAFFIC_VERIFIED, state())
    }

    @Test fun recoverySkipsOnlyAnEngineThatJustFailedHere() {
        ledger.failed(tor.id, "wifi-a", "did not start")
        assertTrue(ledger.recentFailure(tor.id, "wifi-a")!!.contains("did not start"))
        assertFalse(ledger.verifiedHere(tor.id, "wifi-a"))
        // Another network, or a failure that aged out, gets a fresh try.
        assertNull(ledger.recentFailure(tor.id, "cell-irancell"))
        now += EngineLedger.FRESH_MS + 1
        assertNull(ledger.recentFailure(tor.id, "wifi-a"))
        // An engine that worked here is retried after a failure, and goes first while it works.
        ledger.verified(tor.id, "wifi-a")
        assertTrue(ledger.verifiedHere(tor.id, "wifi-a"))
        ledger.failed(tor.id, "wifi-a", "TLS_HANDSHAKE")
        assertNull(ledger.recentFailure(tor.id, "wifi-a"))
        assertFalse(ledger.verifiedHere(tor.id, "wifi-a"))
    }

    @Test fun everyRegistryEngineGetsAStateWithoutAPhone() {
        // No native library folder in a unit test: bundled engine programs read as missing, never as working.
        val all = EngineRegistry.statuses("wifi-a", EngineLedger { now })
        assertEquals(EngineRegistry.ENGINES.map { it.id }, all.map { it.engineId })
        assertTrue(all.none { it.state == EngineState.TRAFFIC_VERIFIED })
        assertEquals(EngineState.UNSUPPORTED, all.single { it.engineId == "naive" }.state)
        assertEquals(EngineState.NOT_BUNDLED, all.single { it.engineId == "masque" }.state)
    }

    /** The release gate (scripts/check-apk.py) asserts exactly the programs the registry declares. */
    @Test fun releaseGateListsEveryDeclaredEngineProgram() {
        val script = listOf(File("../scripts/check-apk.py"), File("scripts/check-apk.py")).first { it.isFile }.readText()
        val tuple = Regex("for engine in \\(([^)]*)\\)").find(script)!!.groupValues[1]
        val listed = Regex("\"([a-z0-9_-]+)\"").findAll(tuple).map { it.groupValues[1] }.toSet()
        assertEquals(EngineRegistry.requiredNativePrograms(), listed)
    }
}
