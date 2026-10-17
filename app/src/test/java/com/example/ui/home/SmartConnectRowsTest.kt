package com.example.ui.home

import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.smart.SearchProgress
import com.example.vpn.smart.SearchStage
import com.example.vpn.smart.SearchTracker
import com.example.vpn.smart.SmartConnectPlan
import com.example.vpn.smart.StageReport
import com.example.vpn.verify.LegResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Smart Connect sheet shows comes from the real search and checks, never from made-up values. */
class SmartConnectRowsTest {
    private val limits = SmartConnectRows.Limits(roundSize = 5, recoverySec = 90, revivalSec = 60)

    private fun tracker(): SearchTracker = SearchTracker("a1", SmartConnectPlan.of(SmartConnectPlan.Input(
        godMode = false, lockExit = false, force = false, mayMove = true, reading = null)))

    /** The search of the searching preview: A, B and C failed, D is running. */
    private fun searching(): SearchProgress = tracker().apply {
        start(SearchStage.REMEMBERED_ROUTE); count(carried = false); finish(SearchStage.REMEMBERED_ROUTE, verified = false)
        start(SearchStage.SELECTED); count(carried = false); finish(SearchStage.SELECTED, verified = false)
        start(SearchStage.SAME_SERVER); count(carried = false, paths = 5); finish(SearchStage.SAME_SERVER, verified = false)
        start(SearchStage.OTHER_SERVERS); count(carried = false, paths = 4)
    }.progress()

    @Test fun theHeadingNamesTheRunningStageOutOfAll() {
        val p = searching()
        val h = SmartConnectRows.headline(p)!!
        assertEquals(SearchStage.OTHER_SERVERS, h.stage)
        assertEquals("Stage D of G · Other saved servers", SmartConnectRows.heading(h.stage))
        assertEquals("Testing your other servers in rounds of 5. Nothing is used until a real request goes through it.",
            SmartConnectRows.headlineLine(h, limits))
        assertEquals(0.5f, SmartConnectRows.position(h.stage), 0.001f)
        assertEquals(Triple(11, 0, 11), Triple(p.tested, p.verified, p.failed))
    }

    @Test fun eachStageShowsItsOwnCountsAndOutcome() {
        val rows = SmartConnectRows.stageRows(searching(), limits, selectedName = "DE Reality")
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G"), rows.map { it.label })
        assertEquals("Failed again now: 1 tested", rows[0].detail)
        assertEquals("DE Reality · 1 tested", rows[1].detail)
        assertEquals("5 tested", rows[2].detail)
        assertEquals("4 tested so far", rows[3].detail)
        assertTrue(rows[3].current)
        assertEquals("Clean Cloudflare address · WARP and Tor in GOD MODE only", rows[4].detail)
        assertEquals("Other families and built-in engines, up to 90 s", rows[5].detail)
        assertEquals("Dead Cloudflare configs, up to 60 s", rows[6].detail)
        assertEquals(listOf(StageBadge.FAILED, StageBadge.FAILED, StageBadge.FAILED, StageBadge.TESTING,
            StageBadge.WAITING, StageBadge.WAITING, StageBadge.WAITING), rows.map { it.badge })
    }

    @Test fun theConnectedSummaryFoldsTheStagesNeverReached() {
        val t = tracker()
        t.skip(SearchStage.REMEMBERED_ROUTE, "No route remembered for this network")
        t.start(SearchStage.SELECTED); t.count(carried = false); t.finish(SearchStage.SELECTED, verified = false)
        t.start(SearchStage.SAME_SERVER); t.count(carried = false, paths = 5); t.finish(SearchStage.SAME_SERVER, verified = false)
        t.start(SearchStage.OTHER_SERVERS); t.count(carried = false, paths = 7); t.count(carried = true); t.finish(SearchStage.OTHER_SERVERS, verified = true)
        t.end()
        val p = t.progress()
        assertEquals("Found in stage D after 14 tests.", SmartConnectRows.foundLine(p))
        val rows = SmartConnectRows.stageRows(p, limits, selectedName = "DE Reality", summary = true)
        assertEquals(listOf("A", "B", "C", "D", "E–G"), rows.map { it.label })
        assertEquals("No route remembered for this network", rows[0].detail)
        assertEquals(StageBadge.SKIPPED, rows[0].badge)
        assertEquals("1 tested", rows[1].detail) // no server name in the summary
        assertEquals("8 tested, 1 verified", rows[3].detail)
        assertEquals(StageBadge.VERIFIED, rows[3].badge)
        assertEquals(StageRow("E–G", "Later stages", "Not needed", StageBadge.NOT_REACHED), rows[4])
    }

    @Test fun nothingFoundMeansNoFoundLine() {
        assertNull(SmartConnectRows.foundLine(searching()))
        val one = SearchProgress("a1", SearchStage.values().map { StageReport(it) })
        assertNull(SmartConnectRows.headline(one))
    }

    @Test fun aStageThatCouldNotTestSaysWhy() {
        val t = tracker()
        t.start(SearchStage.SELECTED); t.finish(SearchStage.SELECTED, verified = false, note = "The path test could not run")
        assertEquals("The path test could not run", SmartConnectRows.stageRows(t.progress(), limits)[1].detail)
    }

    @Test fun evidenceIsShownAsMeasured() {
        assertEquals("64 KB · 1.9 Mbit/s", SmartConnectRows.legValue(LegResult.Passed(65_536, 276), upload = false))
        assertEquals("24 KB echoed · 0.8 Mbit/s", SmartConnectRows.legValue(LegResult.Passed(24_576, 245), upload = true))
        assertEquals("Stalled after 16 KB", SmartConnectRows.legValue(LegResult.Failed(FailureStage.UNKNOWN, 16_384, stalled = true, reason = "x"), upload = false))
        assertEquals("Not tested", SmartConnectRows.legValue(LegResult.NotTested("no reference"), upload = true))
        assertEquals("40 kbit/s", SmartConnectRows.rate(5_000))
        assertEquals("just now", SmartConnectRows.ago(1_000, 30_000))
        assertEquals("4 min ago", SmartConnectRows.ago(0, 4 * 60_000L + 5_000))
    }
}
