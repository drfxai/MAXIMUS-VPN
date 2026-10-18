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

}
