package com.example.ui.home

import com.example.vpn.smart.SearchProgress
import com.example.vpn.smart.SearchStage
import com.example.vpn.smart.StageReport
import com.example.vpn.verify.LegResult
import java.util.Locale

/** How a stage's badge reads; the screen picks its color. */
enum class StageBadge(val text: String) {
    WAITING("Waiting"), TESTING("Testing"), VERIFIED("Verified"), FAILED("Failed"),
    SKIPPED("Skipped"), NOT_REACHED("Not reached"), CHOSEN("Chosen")
}

/** One line of the stage list: "A", "Route that worked here before", "Failed again now: 1 tested", Failed. */
data class StageRow(val label: String, val title: String, val detail: String, val badge: StageBadge, val current: Boolean = false)

/**
 * What the Smart Connect sheet shows, worked out from the attempt's real search ([SearchProgress]) and its
 * upload and download check. Pure, so every line is tested without a phone. A value the state does not
 * hold is left out, never filled in.
 */
object SmartConnectRows {
    /** The bounds the stage descriptions mention, taken from the service's own limits. */
    data class Limits(val roundSize: Int, val recoverySec: Long, val revivalSec: Long)

    private val LAST = SearchStage.values().last()

    fun badge(o: StageReport.Outcome): StageBadge = when (o) {
        StageReport.Outcome.PENDING -> StageBadge.WAITING
        StageReport.Outcome.RUNNING -> StageBadge.TESTING
        StageReport.Outcome.VERIFIED -> StageBadge.VERIFIED
        StageReport.Outcome.FAILED -> StageBadge.FAILED
        StageReport.Outcome.SKIPPED -> StageBadge.SKIPPED
        StageReport.Outcome.NOT_REACHED -> StageBadge.NOT_REACHED
        StageReport.Outcome.CHOSEN -> StageBadge.CHOSEN
    }

    /** What a stage tries, in a few words, for a stage that has not run yet. */
    fun description(stage: SearchStage, limits: Limits): String = when (stage) {
        SearchStage.REMEMBERED_ROUTE -> "The route that carried traffic on this network before"
        SearchStage.SELECTED -> "The server you chose"
        SearchStage.SAME_SERVER -> "Recovered settings, fragment, fingerprint, ECH"
        SearchStage.OTHER_SERVERS -> "Your other servers, in rounds of ${limits.roundSize}"
        SearchStage.EMERGENCY -> "Clean Cloudflare address · WARP and Tor in GOD MODE only"
        SearchStage.RECOVERY -> "Other families and built-in engines, up to ${limits.recoverySec} s"
        SearchStage.REVIVAL -> "Dead Cloudflare configs, up to ${limits.revivalSec} s"
    }

    /** One line on what the running stage is doing, under its heading. */
    fun runningLine(stage: SearchStage, limits: Limits): String = when (stage) {
        SearchStage.REMEMBERED_ROUTE -> "Trying the route that worked here before. It has to carry a real request again now."
        SearchStage.SELECTED -> "Testing the server you chose with a real request."
        SearchStage.SAME_SERVER -> "Trying recovered settings and disguises on the same server: fragment, fingerprint, ECH."
        SearchStage.OTHER_SERVERS -> "Testing your other servers in rounds of ${limits.roundSize}. Nothing is used until a real request goes through it."
        SearchStage.EMERGENCY -> "Trying ways out that need no server of yours. Nothing is used until a real request goes through it."
        SearchStage.RECOVERY -> "Trying other families and built-in engines, for up to ${limits.recoverySec} s."
        SearchStage.REVIVAL -> "Reviving dead Cloudflare configs, for up to ${limits.revivalSec} s."
    }

    /** The line under the heading: what the running stage does, or that the path found is being started. */
    fun headlineLine(r: StageReport, limits: Limits): String? = when (r.outcome) {
        StageReport.Outcome.RUNNING -> runningLine(r.stage, limits)
        StageReport.Outcome.VERIFIED, StageReport.Outcome.CHOSEN ->
            "A path was found. The tunnel is starting, and it counts only once a real request goes through it."
        else -> null
    }

    /** The stage the heading shows: the running one, or else the last one that ran. */
    fun headline(p: SearchProgress): StageReport? = p.current ?: p.stages.lastOrNull {
        it.outcome == StageReport.Outcome.VERIFIED || it.outcome == StageReport.Outcome.FAILED || it.outcome == StageReport.Outcome.CHOSEN
    }

    /** "Stage D of G · Other saved servers". */
    fun heading(stage: SearchStage): String = "Stage ${stage.letter} of ${LAST.letter} · ${stage.title}"

    /** Where [stage] sits in the search, 0..1, for the thin meter under the heading. */
    fun position(stage: SearchStage): Float = (stage.ordinal + 0.5f) / SearchStage.values().size

    /** The stage that found the path in use: verified, or chosen and then checked by the tunnel. */
    fun foundIn(p: SearchProgress): StageReport? = p.stages.lastOrNull { it.outcome == StageReport.Outcome.VERIFIED }
        ?: p.stages.lastOrNull { it.outcome == StageReport.Outcome.CHOSEN }

    /** "Found in stage D after 14 tests." or null when no stage found it. */
    fun foundLine(p: SearchProgress): String? = foundIn(p)?.let { s ->
        "Found in stage ${s.stage.letter} after ${p.tested} ${if (p.tested == 1) "test" else "tests"}."
    }

    /**
     * The stage list. [selectedName] (the server the user chose) is shown on stage B while searching.
     * [summary] is the connected view: counts only, and the stages never reached are one "Later stages" row.
     */
    fun stageRows(p: SearchProgress, limits: Limits, selectedName: String? = null, summary: Boolean = false): List<StageRow> {
        val found = p.stages.any { it.outcome == StageReport.Outcome.VERIFIED || it.outcome == StageReport.Outcome.CHOSEN }
        val running = p.current?.stage
        val rows = p.stages.map { r ->
            StageRow(r.stage.letter.toString(), r.stage.title, detail(r, limits, if (summary) null else selectedName, found), badge(r.outcome), r.stage == running)
        }
        if (!summary) return rows
        val tail = p.stages.takeLastWhile { it.outcome == StageReport.Outcome.NOT_REACHED }
        if (tail.size < 2) return rows
        val later = StageRow("${tail.first().stage.letter}–${tail.last().stage.letter}", "Later stages",
            if (found) "Not needed" else "Not reached", StageBadge.NOT_REACHED)
        return rows.dropLast(tail.size) + later
    }

    private fun detail(r: StageReport, limits: Limits, selectedName: String?, found: Boolean): String {
        val counts = if (r.tested == 1) "1 tested" else "${r.tested} tested"
        return when (r.outcome) {
            StageReport.Outcome.PENDING -> selectedName?.takeIf { r.stage == SearchStage.SELECTED } ?: description(r.stage, limits)
            StageReport.Outcome.RUNNING -> "${r.tested} tested so far"
            StageReport.Outcome.VERIFIED -> "$counts, ${r.verified} verified"
            StageReport.Outcome.SKIPPED -> r.note ?: "Skipped"
            StageReport.Outcome.CHOSEN -> r.note ?: "Chosen; checked once it runs"
            StageReport.Outcome.NOT_REACHED -> if (found) "Not needed" else description(r.stage, limits)
            StageReport.Outcome.FAILED -> when {
                r.tested == 0 -> r.note ?: "Nothing could be tested"
                r.stage == SearchStage.REMEMBERED_ROUTE -> "Failed again now: $counts"
                r.stage == SearchStage.SELECTED && selectedName != null -> "$selectedName · $counts"
                else -> listOfNotNull(r.note, counts).joinToString(" · ")
            }
        }
    }

    /** "64 KB · 1.9 Mbit/s", "24 KB echoed · 0.8 Mbit/s", or what stopped the leg. */
    fun legValue(leg: LegResult, upload: Boolean): String = when (leg) {
        is LegResult.Passed -> "${leg.bytes / 1024} KB${if (upload) " echoed" else ""} · ${rate(leg.bytesPerSecond)}"
        is LegResult.Failed -> if (leg.stalled) "Stalled after ${leg.bytes / 1024} KB" else "Failed"
        is LegResult.NotTested -> "Not tested"
    }

    /** A measured rate in bits: "1.9 Mbit/s", or kbit/s below a tenth of a megabit. */
    fun rate(bytesPerSecond: Long): String {
        val bits = bytesPerSecond * 8
        return if (bits < 100_000) "${bits / 1000} kbit/s" else String.format(Locale.US, "%.1f Mbit/s", bits / 1_000_000.0)
    }

    /** "just now", "4 min ago", "2 h ago". */
    fun ago(at: Long, now: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }
}
