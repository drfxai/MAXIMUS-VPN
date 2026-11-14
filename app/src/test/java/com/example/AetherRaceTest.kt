package com.example

import com.example.vpn.connections.AetherBackgroundRelay
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.connections.FirstVerifiedRace
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AetherRaceTest {
    /** A raced attempt: [verified] stands for HTTPS data passing; [stopped] records that its engine was stopped. */
    private class Leg(val label: String, val verified: Boolean, val handshake: Boolean = false) {
        @Volatile var stopped = false
    }

    private val released = Collections.synchronizedList(mutableListOf<String>())
    private fun race() = FirstVerifiedRace<Leg> { it.stopped = true; released += it.label }

    @Test fun firstVerifiedResultWinsAndTheLoserIsAborted() {
        val loserAborted = CountDownLatch(1)
        val outcome = race().run(listOf(
            "h3" to { lane: FirstVerifiedRace.Lane ->
                lane.onAbort { loserAborted.countDown() }
                Thread.sleep(5_000)
                Leg("h3", verified = true)
            },
            "wireguard" to { _: FirstVerifiedRace.Lane -> Thread.sleep(50); Leg("wireguard", verified = true) }
        ), deadlineMs = 10_000, verified = { it.verified })
        assertEquals("wireguard", outcome.winner)
        assertEquals("wireguard", outcome.value?.label)
        assertFalse("the winner keeps running", outcome.value!!.stopped)
        assertEquals(listOf("h3"), outcome.unfinished)
        assertTrue("the loser is stopped at once", loserAborted.await(2, TimeUnit.SECONDS))
        assertFalse(outcome.timedOut)
    }

    @Test fun unverifiedResultNeverWinsEvenWhenFirst() {
        val outcome = race().run(listOf(
            "wireguard" to { _: FirstVerifiedRace.Lane -> Leg("wireguard", verified = false, handshake = true) },
            "h3" to { _: FirstVerifiedRace.Lane -> Thread.sleep(200); Leg("h3", verified = true) }
        ), deadlineMs = 10_000, verified = { it.verified })
        assertEquals("h3", outcome.winner)
        assertEquals(setOf("wireguard"), outcome.losers.keys)
        assertTrue(outcome.unfinished.isEmpty())
    }

    @Test fun noWinnerWhenNeitherCarriesData() {
        val outcome = race().run(listOf(
            "h3" to { _: FirstVerifiedRace.Lane -> Leg("h3", verified = false) },
            "wireguard" to { _: FirstVerifiedRace.Lane -> Leg("wireguard", verified = false) }
        ), deadlineMs = 10_000, verified = { it.verified })
        assertNull(outcome.winner)
        assertNull(outcome.value)
        assertEquals(setOf("h3", "wireguard"), outcome.losers.keys)
        assertFalse(outcome.timedOut)
    }

    @Test fun oneDeadlineEndsTheRaceAndAbortsEveryone() {
        val aborted = CountDownLatch(2)
        val started = System.currentTimeMillis()
        val outcome = race().run(listOf("h3", "wireguard").map { label ->
            label to { lane: FirstVerifiedRace.Lane ->
                lane.onAbort { aborted.countDown() }
                try { Thread.sleep(10_000) } catch (e: InterruptedException) {}
                Leg(label, verified = true)
            }
        }, deadlineMs = 300, verified = { it.verified })
        assertTrue(System.currentTimeMillis() - started < 3_000)
        assertNull(outcome.winner)
        assertTrue(outcome.timedOut)
        assertEquals(listOf("h3", "wireguard"), outcome.unfinished)
        assertTrue(aborted.await(2, TimeUnit.SECONDS))
    }

    @Test fun aLateVerifiedResultIsReleasedNotLeaked() {
        val outcome = race().run(listOf(
            "h3" to { _: FirstVerifiedRace.Lane ->
                // Ignores the abort and still comes back verified after the race closed.
                val end = System.currentTimeMillis() + 400
                while (System.currentTimeMillis() < end) { try { Thread.sleep(50) } catch (e: InterruptedException) {} }
                Leg("h3", verified = true)
            },
            "wireguard" to { _: FirstVerifiedRace.Lane -> Leg("wireguard", verified = true) }
        ), deadlineMs = 10_000, verified = { it.verified })
        assertEquals("wireguard", outcome.winner)
        val end = System.currentTimeMillis() + 3_000
        while ("h3" !in released && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("a result that arrives after the race closed is stopped", "h3" in released)
        assertFalse("wireguard" in released)
    }

    @Test fun wireGuardHandshakeWithoutDataEndsTheRaceAtOnce() {
        val started = System.currentTimeMillis()
        val outcome = race().run(listOf(
            "h3" to { _: FirstVerifiedRace.Lane -> try { Thread.sleep(10_000) } catch (e: InterruptedException) {}; Leg("h3", verified = false) },
            "wireguard" to { _: FirstVerifiedRace.Lane -> Leg("wireguard", verified = false, handshake = true) }
        ), deadlineMs = 10_000, verified = { it.verified }, decisive = { it.handshake })
        assertTrue(System.currentTimeMillis() - started < 3_000)
        assertNull(outcome.winner)
        assertEquals("wireguard", outcome.decidedBy)
        assertEquals(listOf("h3"), outcome.unfinished)
    }

    @Test fun raceOnlyForAutomaticIrancellWithStoredIdentities() {
        fun eligible(engine: String = AetherSidecar.ID, automatic: Boolean = true, irancell: Boolean = true, next: String = "h3",
                     queued: List<String> = listOf("wireguard", "h2"), recheck: Boolean = false, relayOnly: Boolean = false,
                     reverse: Boolean = false, stored: Boolean = true, raced: Boolean = false) =
            ConnectionEngineRunner.raceEligible(engine, automatic, irancell, next, queued, recheck, relayOnly, reverse, stored, raced)
        assertTrue(eligible())
        assertFalse(eligible(engine = "serverless"))
        assertFalse(eligible(automatic = false))
        assertFalse(eligible(irancell = false))
        assertFalse(eligible(next = "wireguard", queued = listOf("h3")))
        assertFalse(eligible(queued = listOf("h2")))
        assertFalse(eligible(recheck = true))
        assertFalse(eligible(relayOnly = true))
        assertFalse(eligible(reverse = true))
        assertFalse("two engines never register identities at once", eligible(stored = false))
        assertFalse(eligible(raced = true))
    }

    @Test fun blockedWarpWithoutSavedServerDefersTheRelay() {
        val auto = AetherSettings()
        assertTrue(ConnectionEngineRunner.deferRelayToSavedServer(AetherSidecar.ID, true, auto, null, warpDataBlocked = true))
        assertTrue(ConnectionEngineRunner.deferRelayToSavedServer(AetherSidecar.ID, true, auto, "", warpDataBlocked = true))
        // A verified saved server exists: the relay runs now instead.
        assertFalse(ConnectionEngineRunner.deferRelayToSavedServer(AetherSidecar.ID, true, auto, "server-1", warpDataBlocked = true))
        assertFalse(ConnectionEngineRunner.deferRelayToSavedServer(AetherSidecar.ID, true, auto, null, warpDataBlocked = false))
        assertFalse(ConnectionEngineRunner.deferRelayToSavedServer(AetherSidecar.ID, false, auto, null, warpDataBlocked = true))
        assertFalse(ConnectionEngineRunner.deferRelayToSavedServer(AetherSidecar.ID, true, AetherSettings(tor = "chain"), null, true))
        assertFalse(ConnectionEngineRunner.deferRelayToSavedServer(AetherSidecar.ID, true, AetherSettings(psiphon = "only"), null, true))
    }

    @Test fun backgroundRelayStartsOnlyOnTheSameNetworkThroughAnEligibleVerifiedServer() {
        val now = 1_000_000L
        assertTrue(AetherBackgroundRelay.shouldStart("cell:43235", now - 1_000, "cell:43235", true, true, now))
        assertFalse(AetherBackgroundRelay.shouldStart(null, now, "cell:43235", true, true, now))
        assertFalse(AetherBackgroundRelay.shouldStart("cell:43235", now, "wifi:home", true, true, now))
        assertFalse("server Aether cannot use as an exit", AetherBackgroundRelay.shouldStart("cell:43235", now, "cell:43235", false, true, now))
        assertFalse("not verified yet", AetherBackgroundRelay.shouldStart("cell:43235", now, "cell:43235", true, false, now))
        assertFalse("stale", AetherBackgroundRelay.shouldStart("cell:43235", now - AetherBackgroundRelay.MAX_AGE_MS - 1, "cell:43235", true, true, now))
        assertEquals(listOf(ConnectionEngineRunner.WIREGUARD_RELAY, ConnectionEngineRunner.H2_RELAY), AetherBackgroundRelay.ORDER)
    }

    @Test fun backgroundRelaySwitchesOnlyAfterVerifiedDataWithoutBreakingTheConnection() {
        assertTrue(AetherBackgroundRelay.shouldSwitch(relayVerified = true, stillConnected = true, sameNetwork = true))
        assertFalse(AetherBackgroundRelay.shouldSwitch(relayVerified = false, stillConnected = true, sameNetwork = true))
        assertFalse(AetherBackgroundRelay.shouldSwitch(relayVerified = true, stillConnected = false, sameNetwork = true))
        assertFalse(AetherBackgroundRelay.shouldSwitch(relayVerified = true, stillConnected = true, sameNetwork = false))
    }
}
