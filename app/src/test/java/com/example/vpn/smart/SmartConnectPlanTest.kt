package com.example.vpn.smart

import com.example.vpn.lab.NetworkState
import com.example.vpn.lab.NetworkStateReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which stages a connect runs, and the per-stage counts the screen shows. */
class SmartConnectPlanTest {
    private fun input(god: Boolean = false, lockExit: Boolean = false, force: Boolean = false, mayMove: Boolean = true,
                      reading: NetworkStateReading? = null) =
        SmartConnectPlan.Input(godMode = god, lockExit = lockExit, force = force, mayMove = mayMove, reading = reading)

    private fun reading(primary: NetworkState, vararg extra: NetworkState) =
        NetworkStateReading(primary, extra.toSet(), 0.7, listOf("measured"), emptyList(), 1_000)

    @Test fun dailyRunsEveryStageButStartsEnginesOnlyThroughRecovery() {
        val plan = SmartConnectPlan.of(input())
        SearchStage.values().forEach { assertTrue(it.name, plan.allows(it)) }
        assertEquals(listOf(EmergencyTransport.CLEAN_IP, EmergencyTransport.WARP), plan.transports)
    }

    @Test fun godModeKeepsTheEmergencyOrder() {
        val plan = SmartConnectPlan.of(input(god = true))
        assertEquals(EmergencyTransport.values().toList(), plan.transports)
    }

    @Test fun measuredUdpBlockSkipsWarpAndMasqueButNotTheRest() {
        val plan = SmartConnectPlan.of(input(god = true, reading = reading(NetworkState.FILTERED, NetworkState.UDP_BLOCKED_SUSPECTED)))
        assertEquals(listOf(EmergencyTransport.CLEAN_IP, EmergencyTransport.PSIPHON, EmergencyTransport.TOR), plan.transports)
        assertTrue(plan.transportsSkipped.getValue(EmergencyTransport.WARP).contains("UDP"))
    }

    @Test fun measuredQuicBlockSkipsOnlyMasque() {
        val plan = SmartConnectPlan.of(input(god = true, reading = reading(NetworkState.QUIC_BLOCKED_SUSPECTED)))
        assertFalse(plan.allows(EmergencyTransport.MASQUE))
        assertTrue(plan.allows(EmergencyTransport.WARP))
    }

    @Test fun aSevereNetworkStillTriesEverything() {
        // "No verified egress" says nothing answered yet; it never rules recovery out.
        val plan = SmartConnectPlan.of(input(god = true, reading = reading(NetworkState.NO_VERIFIED_EGRESS)))
        assertEquals(EmergencyTransport.values().toList(), plan.transports)
        SearchStage.values().forEach { assertTrue(plan.allows(it)) }
    }

    @Test fun tradeModeOnlyTestsTheSelectedServer() {
        val plan = SmartConnectPlan.of(input(god = true, lockExit = true))
        assertEquals(listOf(SearchStage.SELECTED), SearchStage.values().filter { plan.allows(it) })
        assertTrue(plan.transports.isEmpty())
    }

    @Test fun thePowerButtonOnlyConnectsTheSelectedConfigEvenInGodMode() {
        val plan = SmartConnectPlan.of(SmartConnectPlan.Input(godMode = true, lockExit = true, exact = true,
            force = false, mayMove = true, reading = null))
        assertEquals(listOf(SearchStage.SELECTED), SearchStage.values().filter { plan.allows(it) })
        assertTrue(plan.transports.isEmpty())
        assertTrue(plan.skipped.getValue(SearchStage.EMERGENCY).startsWith("Power button"))
    }

    @Test fun withoutFailoverTheSessionStaysOnTheChosenServer() {
        val plan = SmartConnectPlan.of(input(mayMove = false))
        assertFalse(plan.allows(SearchStage.OTHER_SERVERS))
        assertFalse(plan.allows(SearchStage.RECOVERY))
        assertFalse(plan.allows(SearchStage.REVIVAL))
        assertTrue(plan.allows(SearchStage.SAME_SERVER))
    }

    @Test fun connectAnywaySkipsRememberedRoutesAndLongRecovery() {
        val plan = SmartConnectPlan.of(input(force = true))
        assertFalse(plan.allows(SearchStage.REMEMBERED_ROUTE))
        assertFalse(plan.allows(SearchStage.RECOVERY))
        assertTrue(plan.allows(SearchStage.SELECTED))
    }

    @Test fun aReadingCountsOnlyWhenFreshAndFromTheSameKindOfNetwork() {
        val cell = NetworkCapabilityProfile(transport = "cellular", carrierCode = "43235", udpAvailable = false, measuredAt = 1_000)
        assertNotNull(SmartConnectPlan.freshReading(cell, "cell:43235", now = 2_000))
        assertNotNull(SmartConnectPlan.freshReading(cell, "cell", now = 2_000))
        assertNull(SmartConnectPlan.freshReading(cell, "cell:43211", now = 2_000))
        assertNull(SmartConnectPlan.freshReading(cell, "wifi", now = 2_000))
        assertNull(SmartConnectPlan.freshReading(cell, "cell:43235", now = 1_000 + SmartConnectPlan.READING_MAX_AGE_MS))
        assertNull(SmartConnectPlan.freshReading(null, "cell:43235", now = 2_000))
    }

    @Test fun countsLandInTheRunningStage() {
        val published = mutableListOf<SearchProgress>()
        val t = SearchTracker("a1", SmartConnectPlan.of(input())) { published += it }
        t.skip(SearchStage.REMEMBERED_ROUTE, "No route remembered for this network")
        t.start(SearchStage.SELECTED)
        t.count(carried = false)
        t.start(SearchStage.SAME_SERVER) // the selected path carried nothing
        repeat(4) { t.count(carried = false) }
        t.finishRunning(verified = false)
        t.start(SearchStage.OTHER_SERVERS)
        repeat(5) { t.count(carried = false) }
        t.count(carried = true)
        t.finish(SearchStage.OTHER_SERVERS, verified = true)
        t.end()
        val p = t.progress()
        assertEquals(StageReport.Outcome.SKIPPED, p.of(SearchStage.REMEMBERED_ROUTE).outcome)
        assertEquals(StageReport.Outcome.FAILED, p.of(SearchStage.SELECTED).outcome)
        assertEquals(1, p.of(SearchStage.SELECTED).tested)
        assertEquals(4, p.of(SearchStage.SAME_SERVER).failed)
        assertEquals(StageReport.Outcome.VERIFIED, p.of(SearchStage.OTHER_SERVERS).outcome)
        assertEquals(6 to 1, p.of(SearchStage.OTHER_SERVERS).tested to p.of(SearchStage.OTHER_SERVERS).verified)
        assertEquals(StageReport.Outcome.NOT_REACHED, p.of(SearchStage.REVIVAL).outcome)
        assertEquals(11, p.tested)
        assertEquals(1, p.verified)
        assertEquals(10, p.failed)
        assertEquals(p, published.last())
        assertTrue(p.describe().startsWith("Stage D"))
    }

    @Test fun nothingIsCountedAgainstAStageThePlanLeftOut() {
        val t = SearchTracker("a1", SmartConnectPlan.of(input(lockExit = true)))
        t.start(SearchStage.OTHER_SERVERS)
        t.count(carried = true)
        assertEquals(StageReport.Outcome.SKIPPED, t.progress().of(SearchStage.OTHER_SERVERS).outcome)
        assertEquals(0, t.progress().tested)
        assertNull(t.progress().current)
    }

    @Test fun anEngineChosenUnmeasuredIsNeitherVerifiedNorFailed() {
        val t = SearchTracker("a1", SmartConnectPlan.of(input(god = true)))
        t.start(SearchStage.EMERGENCY)
        t.count(carried = false) // WARP
        t.chose(SearchStage.EMERGENCY, "Tor chosen; checked once it runs")
        t.end()
        val e = t.progress().of(SearchStage.EMERGENCY)
        assertEquals(StageReport.Outcome.CHOSEN, e.outcome)
        assertEquals(0, e.verified)
    }

    @Test fun resultsMeasuredElsewhereAddUp() {
        val t = SearchTracker("a1", SmartConnectPlan.of(input()))
        t.start(SearchStage.RECOVERY)
        t.add(SearchStage.RECOVERY, tested = 7, verified = 0)
        t.finish(SearchStage.RECOVERY, verified = false, note = "every family failed")
        val r = t.progress().of(SearchStage.RECOVERY)
        assertEquals(7, r.failed)
        assertEquals("every family failed", r.note)
    }
    @Test fun anotherWifiCannotInheritARecentUdpOrQuicFailure() {
        val measured = NetworkCapabilityProfile(transport = "wifi", measuredAt = 1000L,
            networkIdentity = "wifi:aaa", udpAvailable = false, tlsAvailable = true)
        assertNotNull(SmartConnectPlan.freshReading(measured, "wifi:aaa", 1001L))
        assertNull(SmartConnectPlan.freshReading(measured, "wifi:bbb", 1001L))
        assertEquals(measured, NetworkCapabilityProfile.fromJson(measured.toJson()))
    }

    // ------------------------------------------------------------ Cloudflare blocked (cf=0)

    private val irancellCf0 = NetworkCapabilityProfile(transport = "cellular", carrierCode = "43235", udpAvailable = true,
        cloudflareReachable = false, measuredAt = 1_000)

    @Test fun cloudflareBlockedMovesCleanIpWarpAndMasqueToTheEndWithoutDroppingThem() {
        val plan = SmartConnectPlan.of(SmartConnectPlan.Input(godMode = true, lockExit = false, force = false, mayMove = true,
            reading = null, cloudflareBlocked = true))
        assertTrue(plan.cloudflareLast)
        assertEquals(listOf(EmergencyTransport.PSIPHON, EmergencyTransport.TOR, EmergencyTransport.CLEAN_IP,
            EmergencyTransport.WARP, EmergencyTransport.MASQUE), plan.transports)
        listOf(EmergencyTransport.CLEAN_IP, EmergencyTransport.WARP, EmergencyTransport.MASQUE).forEach {
            assertTrue(it.name, plan.allows(it))
            assertTrue(it.name, plan.deferred(it))
            assertFalse(it.name, plan.early(it))
        }
        assertTrue(plan.early(EmergencyTransport.PSIPHON))
        assertFalse(plan.deferred(EmergencyTransport.TOR))
    }

    @Test fun cloudflareBlockedInDailyModeDefersCleanIpAndWarp() {
        val plan = SmartConnectPlan.of(SmartConnectPlan.Input(godMode = false, lockExit = false, force = false, mayMove = true,
            reading = null, cloudflareBlocked = true))
        assertEquals(listOf(EmergencyTransport.CLEAN_IP, EmergencyTransport.WARP), plan.transports)
        assertTrue(plan.deferred(EmergencyTransport.CLEAN_IP) && plan.deferred(EmergencyTransport.WARP))
        assertFalse(plan.early(EmergencyTransport.CLEAN_IP) || plan.early(EmergencyTransport.WARP))
        // A transport the plan left out is neither early nor deferred.
        assertFalse(plan.deferred(EmergencyTransport.MASQUE) || plan.early(EmergencyTransport.MASQUE))
    }

    @Test fun aMeasuredOrUnmeasuredCloudflareKeepsTheUsualOrder() {
        listOf(true, false).forEach { god ->
            val plan = SmartConnectPlan.of(input(god = god))
            assertFalse(plan.cloudflareLast)
            EmergencyTransport.values().forEach {
                assertEquals(it.name, plan.allows(it), plan.early(it))
                assertFalse(it.name, plan.deferred(it))
            }
        }
        // A UDP block still skips WARP and MASQUE on a cf=0 network; only what is allowed is moved.
        val udp = SmartConnectPlan.of(SmartConnectPlan.Input(godMode = true, lockExit = false, force = false, mayMove = true,
            reading = reading(NetworkState.FILTERED, NetworkState.UDP_BLOCKED_SUSPECTED), cloudflareBlocked = true))
        assertEquals(listOf(EmergencyTransport.PSIPHON, EmergencyTransport.TOR, EmergencyTransport.CLEAN_IP), udp.transports)
    }

    @Test fun onlyCloudflareBasedTransportsAreMarked() {
        assertEquals(setOf(EmergencyTransport.CLEAN_IP, EmergencyTransport.WARP, EmergencyTransport.MASQUE),
            EmergencyTransport.values().filter { it.cloudflare }.toSet())
    }

    @Test fun cloudflareCountsAsBlockedOnlyWhenMeasuredBlockedFreshOnThisNetwork() {
        assertTrue(SmartConnectPlan.cloudflareBlocked(irancellCf0, "cell:43235", now = 2_000))
        // cf=1 or not measured: never blocked.
        assertFalse(SmartConnectPlan.cloudflareBlocked(irancellCf0.copy(cloudflareReachable = true), "cell:43235", now = 2_000))
        assertFalse(SmartConnectPlan.cloudflareBlocked(irancellCf0.copy(cloudflareReachable = null), "cell:43235", now = 2_000))
        assertFalse(SmartConnectPlan.cloudflareBlocked(null, "cell:43235", now = 2_000))
        // Another carrier, another kind of network, or a stale reading says nothing about this one.
        assertFalse(SmartConnectPlan.cloudflareBlocked(irancellCf0, "cell:43211", now = 2_000))
        assertFalse(SmartConnectPlan.cloudflareBlocked(irancellCf0, "wifi", now = 2_000))
        assertFalse(SmartConnectPlan.cloudflareBlocked(irancellCf0, "cell:43235", now = 1_000 + SmartConnectPlan.READING_MAX_AGE_MS))
    }

    private fun deferAether(cf: Boolean = true, aether: Boolean = true, smart: Boolean = true, force: Boolean = false,
                            lockExit: Boolean = false, verifiedHere: Boolean = false, alreadyLast: Boolean = false) =
        SmartConnectPlan.deferAether(cf, aether, smart, force, lockExit, verifiedHere, alreadyLast)

    @Test fun smartConnectHoldsAnAutomaticAetherLeadBackOnlyWhenCloudflareIsBlocked() {
        // The field case: Irancell cf=0, Smart Connect led with Aether and lost ~2m20s before saved servers ran.
        assertTrue(deferAether())
        // cf=1 (or unmeasured): Aether keeps its place.
        assertFalse(deferAether(cf = false))
        // Not Aether: nothing to hold back.
        assertFalse(deferAether(aether = false))
        // The user picked Aether (power button, Aether page): started as chosen.
        assertFalse(deferAether(smart = false))
        // Connect anyway, Trade mode or the power button's exact config.
        assertFalse(deferAether(force = true))
        assertFalse(deferAether(lockExit = true))
        // Aether carried traffic on this network before.
        assertFalse(deferAether(verifiedHere = true))
        // The deferred run itself, or a search after Aether already failed: never deferred again.
        assertFalse(deferAether(alreadyLast = true))
    }

}
