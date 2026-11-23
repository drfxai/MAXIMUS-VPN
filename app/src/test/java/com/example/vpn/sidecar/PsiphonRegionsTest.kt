package com.example.vpn.sidecar

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 171 report: direct Psiphon on Iranian cellular ran 2 × 120 s twice and ended "server candidates found but no
 * tunnel became ready (last notice: AvailableEgressRegions)". The region is now chosen from Psiphon's own
 * AvailableEgressRegions list and measured quality, inside a bounded budget.
 */
class PsiphonRegionsTest {
    private var now = 0L

    /**
     * A fake Psiphon start. [script] says, for elapsed ms in this start, what has been observed: the available regions
     * (after [availableAtMs]), whether a server is being dialed ([dialing]) and when the tunnel comes up ([readyAtMs]).
     */
    private inner class FakeStart(
        val region: String,
        val available: List<String>? = null,
        val availableAtMs: Long = 2_000,
        val dialing: Boolean = false,
        val readyAtMs: Long? = null,
        val connected: String? = null
    ) : PsiphonRegionRun.Start {
        private var elapsed = 0L
        var stopped = false
        override fun await(timeoutMs: Long, giveUp: () -> Boolean): Boolean {
            while (elapsed < timeoutMs) {
                if (readyAtMs != null && elapsed >= readyAtMs) return true
                if (giveUp()) return false
                elapsed += 500
                now += 500
            }
            return false
        }
        override fun available() = available?.takeIf { elapsed >= availableAtMs }
        override fun stage() = when {
            readyAtMs != null && elapsed >= readyAtMs -> 3
            dialing -> 2
            available() != null -> 1
            else -> 0
        }
        override fun connectedRegion() = connected?.takeIf { readyAtMs != null && elapsed >= readyAtMs }
        override fun stop() { stopped = true }
    }

    private val run = PsiphonRegionRun { now }

    @Test fun noticesYieldTheAvailableRegionsAndTheConnectedRegionWithoutAddresses() {
        val progress = PsiphonSidecar.StartupProgress()
        assertNull(progress.availableRegions())
        progress.observe("""Mon 2026-11-23 10:06 PM AvailableEgressRegions {"regions":["us","DE","NL","x1","DE"]}""")
        assertEquals(listOf("US", "DE", "NL"), progress.availableRegions())
        assertEquals(1, progress.progressStage)
        progress.observe("""{"noticeType":"ConnectingServer","data":{"protocol":"OSSH","region":"DE","ipAddress":"203.0.113.9"}}""")
        assertEquals(2, progress.progressStage)
        progress.observe("""{"noticeType":"ConnectedServerRegion","data":{"serverRegion":"de"}}""")
        assertEquals("DE", progress.connectedRegion())
        assertTrue(progress.recentNotices().orEmpty().contains("regions=5"))
        assertTrue(!progress.recentNotices().orEmpty().contains("203.0.113.9"))
    }

    @Test fun planKeepsTheUsersRegionAndOtherwiseLeadsWithTheBestRegionsThenAny() {
        assertEquals(listOf(PsiphonRegionPlan.Step("JP", 120_000, "region chosen in the profile")),
            PsiphonRegionPlan.initial("jp", listOf("DE"), 120_000))
        val auto = PsiphonRegionPlan.initial("", listOf("de", "NL", "FR"))
        assertEquals(listOf("DE", "NL", ""), auto.map { it.region })
        assertEquals(PsiphonRegionPlan.TOTAL_AUTO_MS, auto.sumOf { it.budgetMs })
        assertTrue(auto.last().budgetMs >= PsiphonRegionPlan.ANY_MIN_MS)
        assertEquals(listOf(""), PsiphonRegionPlan.initial("", emptyList()).map { it.region })
        assertTrue(PsiphonRegionPlan.TOTAL_AUTO_MS < 120_000)
    }

    @Test fun aRegionMissingFromTheAvailableListIsUnavailable() {
        assertTrue(PsiphonRegionPlan.unavailable("JP", listOf("DE", "NL")))
        assertTrue(!PsiphonRegionPlan.unavailable("DE", listOf("de", "NL")))
        assertTrue(!PsiphonRegionPlan.unavailable("", listOf("DE")))
        // No list yet, or an empty one, decides nothing.
        assertTrue(!PsiphonRegionPlan.unavailable("JP", null))
        assertTrue(!PsiphonRegionPlan.unavailable("JP", emptyList()))
    }

    @Test fun aFixedRegionPsiphonDoesNotOfferStopsAtOnceAndIsNeverSwitched() {
        val starts = mutableListOf<FakeStart>()
        val out = run.run("JP", listOf("DE"), 120_000, launch = { step ->
            FakeStart(step.region, available = listOf("DE", "NL", "US")).also { starts += it }
        })
        assertEquals(listOf("JP"), starts.map { it.region })
        assertTrue(starts.single().stopped)
        assertNull(out.ready)
        val failure = out.failure.orEmpty()
        assertTrue(failure, failure.contains("region JP") && failure.contains("DE, NL, US"))
        assertTrue("took ${now} ms", now < 5_000)
    }

    @Test fun anySilentAfterItsRegionListRotatesThroughAvailableRegionsWithinTheBudget() {
        val starts = mutableListOf<FakeStart>()
        val out = run.run("", emptyList(), PsiphonRegionPlan.TOTAL_AUTO_MS, launch = { step ->
            when (step.region) {
                // The report: the region list arrives, then nothing is dialed.
                "" -> FakeStart("", available = listOf("US", "NL", "DE", "JP"))
                "DE" -> FakeStart("DE", available = listOf("US", "NL", "DE", "JP"), dialing = true)
                else -> FakeStart(step.region, available = listOf("US", "NL", "DE", "JP"), dialing = true, readyAtMs = 6_000, connected = step.region)
            }.also { starts += it }
        })
        assertEquals(listOf("", "DE", "NL"), starts.map { it.region })
        assertNotNull(out.ready)
        assertEquals("NL", out.winner?.region)
        assertTrue("took $now ms", now <= PsiphonRegionPlan.TOTAL_AUTO_MS)
        assertTrue(starts.dropLast(1).all { it.stopped })
    }

    @Test fun anyThatIsDialingKeepsItsWholeWindow() {
        val starts = mutableListOf<FakeStart>()
        val out = run.run("", emptyList(), PsiphonRegionPlan.TOTAL_AUTO_MS, launch = { step ->
            FakeStart(step.region, available = listOf("DE"), dialing = true, readyAtMs = 70_000, connected = "DE").also { starts += it }
        })
        assertEquals(1, starts.size)
        assertEquals("DE", out.winner?.region)
    }

    @Test fun theBestRememberedRegionGoesFirstAndAMissingOneIsCutAtOnce() {
        val starts = mutableListOf<FakeStart>()
        val out = run.run("", listOf("FR", "DE"), PsiphonRegionPlan.TOTAL_AUTO_MS, launch = { step ->
            when (step.region) {
                "FR" -> FakeStart("FR", available = listOf("DE", "NL"), availableAtMs = 1_000)
                "DE" -> FakeStart("DE", available = listOf("DE", "NL"), readyAtMs = 4_000, connected = "DE")
                else -> FakeStart(step.region)
            }.also { starts += it }
        })
        assertEquals(listOf("FR", "DE"), starts.map { it.region })
        assertEquals("DE", out.winner?.region)
        assertEquals(4_000L, out.winner?.elapsedMs)
        assertTrue("took $now ms", now < 10_000)
    }

    @Test fun nothingComesUpAndTheWholeRunStaysUnderTheOldSingleWindow() {
        val out = run.run("", listOf("DE"), PsiphonRegionPlan.TOTAL_AUTO_MS, launch = { step ->
            FakeStart(step.region, available = listOf("DE", "NL", "GB"))
        })
        assertNull(out.ready)
        assertNull(out.failure)
        assertNotNull(out.last)
        assertTrue("took $now ms", now <= PsiphonRegionPlan.TOTAL_AUTO_MS + 1_000)
    }

    @Test fun aStopEndsTheRunInsteadOfStartingTheNextRegion() {
        var cancelled = false
        val starts = mutableListOf<FakeStart>()
        assertThrows(CancellationException::class.java) {
            run.run("", listOf("DE"), PsiphonRegionPlan.TOTAL_AUTO_MS, launch = { step ->
                cancelled = true // Stop pressed while this start runs.
                FakeStart(step.region).also { starts += it }
            }, cancelled = { cancelled })
        }
        assertEquals(1, starts.size)
        assertTrue(starts.single().stopped)
    }

    @Test fun theBookRanksByVerifiedRateThenStartTimeAndHoldsFailures() {
        var stored: String? = null
        var clock = 1_000_000L
        fun book() = PsiphonRegionBook({ stored }, { stored = it }, { clock })
        val b = book()
        val net = "cell:43235"
        b.recordReady(net, "nl", 9_000)
        b.recordReady(net, "DE", 4_000)
        b.recordReady(net, "US", 3_000)
        assertEquals(listOf("US", "DE", "NL"), b.ranked(net))
        b.recordRate(net, "NL", 60_000)
        b.recordRate(net, "DE", 20_000)
        assertEquals(listOf("NL", "DE", "US"), book().ranked(net))
        clock += 1_000
        b.recordFailure(net, "NL")
        assertEquals(listOf("DE", "US"), b.ranked(net))
        clock += PsiphonRegionBook.FAILURE_HOLD_MS + 1
        assertEquals(listOf("NL", "DE", "US"), b.ranked(net))
        // Junk never gets in, and other networks learn nothing.
        b.recordReady(net, "Germany", 1_000)
        assertEquals(3, b.ranked(net).size)
        assertTrue(b.ranked("wifi:home").isEmpty())
        b.clear()
        assertTrue(book().ranked(net).isEmpty())
    }

    // --- Build 173 report (Irancell cell:43235, 01:35:20–01:39:21 +0330): direct Psiphon on any region listed 27
    // regions within 0.6 s, then nothing for 120 s, then a TCP-only retry for another 120 s: 240 s before failing. ---

    private val reportRegions = listOf("AT", "BE", "BG", "CA", "CH", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "HU",
        "IE", "IN", "IT", "JP", "LV", "NL", "NO", "PL", "RO", "RS", "SE", "SG", "US")

    @Test fun theReportsSilenceAfterTheRegionListEndsInsideTheCapWithTheTcpStepInsideIt() {
        assertEquals(27, reportRegions.size)
        val starts = mutableListOf<PsiphonRegionPlan.Step>()
        val out = run.run("", emptyList(), PsiphonRegionPlan.TOTAL_AUTO_MS, launch = { step ->
            starts += step
            FakeStart(step.region, available = reportRegions, availableAtMs = 600)
        })
        assertNull(out.ready)
        assertTrue("took $now ms", now <= PsiphonRegionPlan.TOTAL_AUTO_MS)
        assertEquals(listOf("", "DE", "NL"), starts.map { it.region })
        // The old TCP-only retry now runs as the last rotation step, inside the same budget.
        assertEquals(listOf(false, false, true), starts.map { it.tcpOnly })
        // And no second full window follows an automatic region run.
        assertTrue(!PsiphonRegionPlan.tcpRetryAllowed(regionRun = true, userRegion = "", failure = null))
        assertTrue(PsiphonRegionPlan.tcpRetryAllowed(regionRun = false, userRegion = "", failure = null))
        assertTrue(PsiphonRegionPlan.tcpRetryAllowed(regionRun = true, userRegion = "DE", failure = null))
        assertTrue(!PsiphonRegionPlan.tcpRetryAllowed(regionRun = true, userRegion = "JP", failure = "unavailable"))
    }

    @Test fun theUsersDirectPickOnANetworkWhereOnlyTheChainWorksFailsFast() {
        assertEquals(PsiphonRegionPlan.TOTAL_AUTO_MS, PsiphonRegionPlan.totalBudget("", 120_000, manual = true, knownBlocked = false))
        assertEquals(PsiphonRegionPlan.TOTAL_AUTO_MS, PsiphonRegionPlan.totalBudget("", 120_000, manual = false, knownBlocked = true))
        assertEquals(PsiphonRegionPlan.KNOWN_BLOCKED_MS, PsiphonRegionPlan.totalBudget("", 120_000, manual = true, knownBlocked = true))
        assertEquals(PsiphonRegionPlan.KNOWN_BLOCKED_MS, PsiphonRegionPlan.totalBudget("DE", 120_000, manual = true, knownBlocked = true))
        assertEquals(120_000L, PsiphonRegionPlan.totalBudget("DE", 120_000, manual = true, knownBlocked = false))
        val out = run.run("", emptyList(), PsiphonRegionPlan.KNOWN_BLOCKED_MS, launch = { step ->
            FakeStart(step.region, available = reportRegions, availableAtMs = 600)
        })
        assertNull(out.ready)
        assertTrue("took $now ms", now <= PsiphonRegionPlan.KNOWN_BLOCKED_MS)
    }

    @Test fun aPinnedRestartLeavesTheRegionThatWentDead() {
        // Nothing remembered: the first other listed region in the usual order goes first, then any.
        val steps = PsiphonRegionPlan.initial("", emptyList(), avoid = setOf("de"), knownAvailable = reportRegions)
        assertEquals(listOf("NL", ""), steps.map { it.region })
        assertEquals(PsiphonRegionPlan.TOTAL_AUTO_MS, steps.sumOf { it.budgetMs })
        // A remembered region that went dead is skipped, the others keep their order.
        assertEquals(listOf("FR", ""), PsiphonRegionPlan.initial("", listOf("DE", "FR"), avoid = setOf("DE")).map { it.region })
        // A fixed region is the user's: never changed.
        assertEquals(listOf("DE"), PsiphonRegionPlan.initial("DE", emptyList(), avoid = setOf("DE"), knownAvailable = reportRegions).map { it.region })
        // Without a known list nothing is invented.
        assertEquals(listOf(""), PsiphonRegionPlan.initial("", emptyList(), avoid = setOf("DE")).map { it.region })
        // The rotation after a silent any never goes back to it either.
        val starts = mutableListOf<String>()
        run.run("", emptyList(), PsiphonRegionPlan.TOTAL_AUTO_MS, avoid = setOf("DE"), launch = { step ->
            starts += step.region
            FakeStart(step.region, available = listOf("DE", "NL", "FR"), availableAtMs = 600)
        })
        assertTrue("$starts", "DE" !in starts)
        assertTrue("took $now ms", now <= PsiphonRegionPlan.TOTAL_AUTO_MS)
    }
}
