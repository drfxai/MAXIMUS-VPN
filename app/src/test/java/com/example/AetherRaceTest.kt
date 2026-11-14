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

    @Test fun wireGuardHandshakeWithoutDataLetsH3KeepRunningAndWin() {
        val h3Aborted = CountDownLatch(1)
        val outcome = race().run(listOf(
            "h3" to { lane: FirstVerifiedRace.Lane ->
                lane.onAbort { h3Aborted.countDown() }
                Thread.sleep(600)
                Leg("h3", verified = true)
            },
            "wireguard" to { _: FirstVerifiedRace.Lane -> Leg("wireguard", verified = false, handshake = true) }
        ), deadlineMs = 10_000, verified = { it.verified }, decisive = { it.handshake })
        assertEquals("H3 still running when WireGuard had no data keeps its chance", "h3", outcome.winner)
        assertEquals("wireguard", outcome.decidedBy)
        assertEquals(setOf("wireguard"), outcome.losers.keys)
        assertTrue(outcome.unfinished.isEmpty())
        assertFalse(outcome.timedOut)
        assertFalse("the winner is never aborted", h3Aborted.await(300, TimeUnit.MILLISECONDS))
        assertFalse(outcome.value!!.stopped)
    }

    @Test fun wireGuardHandshakeWithoutDataWaitsForH3ToFail() {
        val outcome = race().run(listOf(
            "h3" to { _: FirstVerifiedRace.Lane -> Thread.sleep(400); Leg("h3", verified = false) },
            "wireguard" to { _: FirstVerifiedRace.Lane -> Leg("wireguard", verified = false, handshake = true) }
        ), deadlineMs = 10_000, verified = { it.verified }, decisive = { it.handshake })
        assertNull(outcome.winner)
        assertEquals("wireguard", outcome.decidedBy)
        assertEquals("H3 finished (failed) before the race ended", setOf("wireguard", "h3"), outcome.losers.keys)
        assertTrue(outcome.unfinished.isEmpty())
        assertFalse(outcome.timedOut)
    }

    @Test fun wireGuardHandshakeWithoutDataWaitsForH3UntilTheDeadline() {
        val h3Aborted = CountDownLatch(1)
        val started = System.currentTimeMillis()
        val outcome = race().run(listOf(
            "h3" to { lane: FirstVerifiedRace.Lane ->
                lane.onAbort { h3Aborted.countDown() }
                try { Thread.sleep(10_000) } catch (e: InterruptedException) {}
                Leg("h3", verified = false)
            },
            "wireguard" to { _: FirstVerifiedRace.Lane -> Leg("wireguard", verified = false, handshake = true) }
        ), deadlineMs = 500, verified = { it.verified }, decisive = { it.handshake })
        assertTrue("the race did not end at the WireGuard result", System.currentTimeMillis() - started >= 450)
        assertNull(outcome.winner)
        assertEquals("wireguard", outcome.decidedBy)
        assertTrue(outcome.timedOut)
        assertEquals(listOf("h3"), outcome.unfinished)
        assertTrue("H3 is stopped at the deadline", h3Aborted.await(2, TimeUnit.SECONDS))
    }

    @Test fun claimReleasesTheWinnerWhenCancelledAfterTheRace() {
        val r = race()
        val outcome = r.run(listOf(
            "h3" to { _: FirstVerifiedRace.Lane -> Leg("h3", verified = true) }
        ), deadlineMs = 10_000, verified = { it.verified })
        assertEquals("h3", outcome.winner)
        try {
            r.claim(outcome) { throw kotlinx.coroutines.CancellationException("Connection cancelled") }
            fail("the cancellation is rethrown")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("Connection cancelled", e.message)
        }
        assertTrue("the winner's engine is stopped", outcome.value!!.stopped)
        assertEquals(listOf("h3"), released.toList())
    }

    @Test fun claimKeepsTheWinnerRunningWhenNotCancelled() {
        val r = race()
        val outcome = r.claim(r.run(listOf(
            "h3" to { _: FirstVerifiedRace.Lane -> Leg("h3", verified = true) }
        ), deadlineMs = 10_000, verified = { it.verified })) {}
        assertEquals("h3", outcome.winner)
        assertFalse(outcome.value!!.stopped)
        // Without a winner a cancellation stops nothing and is still rethrown.
        val none = r.run(listOf("h3" to { _: FirstVerifiedRace.Lane -> Leg("h3", verified = false) }),
            deadlineMs = 10_000, verified = { it.verified })
        try { r.claim(none) { throw InterruptedException() }; fail() } catch (e: InterruptedException) {}
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
        assertFalse("server frozen on this network", AetherBackgroundRelay.shouldStart("cell:43235", now, "cell:43235", true, true, now,
            connectedFrozen = true))
        assertFalse("one relay switch per user Connect", AetherBackgroundRelay.shouldStart("cell:43235", now, "cell:43235", true, true, now,
            alreadySwitched = true))
        assertEquals(listOf(ConnectionEngineRunner.WIREGUARD_RELAY, ConnectionEngineRunner.H2_RELAY), AetherBackgroundRelay.ORDER)
    }

    @Test fun backgroundRelayIsCappedOnMeteredLinks() {
        assertTrue(AetherBackgroundRelay.metered(measuredMetered = false, activeMetered = false, networkKey = "cell:43235"))
        assertTrue(AetherBackgroundRelay.metered(measuredMetered = false, activeMetered = false, networkKey = "cell"))
        assertTrue(AetherBackgroundRelay.metered(measuredMetered = true, activeMetered = false, networkKey = "wifi"))
        assertTrue(AetherBackgroundRelay.metered(measuredMetered = false, activeMetered = true, networkKey = "wifi"))
        assertFalse(AetherBackgroundRelay.metered(measuredMetered = false, activeMetered = false, networkKey = "wifi"))
        assertFalse(AetherBackgroundRelay.metered(measuredMetered = false, activeMetered = false, networkKey = null))
        assertEquals(AetherBackgroundRelay.METERED_BUDGET_MS, AetherBackgroundRelay.budgetMs(metered = true))
        assertNull(AetherBackgroundRelay.budgetMs(metered = false))
        assertTrue("well inside the 120 s metered Aether budget",
            AetherBackgroundRelay.METERED_BUDGET_MS < ConnectionEngineRunner.HEAVY_LADDER_BUDGET_MS)
    }

    @Test fun backgroundRelaySwitchesOnlyAfterVerifiedDataWithoutBreakingTheConnection() {
        assertTrue(AetherBackgroundRelay.shouldSwitch(relayVerified = true, stillConnected = true, sameNetwork = true))
        assertFalse(AetherBackgroundRelay.shouldSwitch(relayVerified = false, stillConnected = true, sameNetwork = true))
        assertFalse(AetherBackgroundRelay.shouldSwitch(relayVerified = true, stillConnected = false, sameNetwork = true))
        assertFalse(AetherBackgroundRelay.shouldSwitch(relayVerified = true, stillConnected = true, sameNetwork = false))
        assertFalse("exit froze here meanwhile", AetherBackgroundRelay.shouldSwitch(relayVerified = true, stillConnected = true,
            sameNetwork = true, connectedFrozen = true))
        assertFalse("already switched once this Connect", AetherBackgroundRelay.shouldSwitch(relayVerified = true, stillConnected = true,
            sameNetwork = true, alreadySwitched = true))
    }
}
