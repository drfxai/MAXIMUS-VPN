package com.example.vpn.verify

import com.example.vpn.diagnostics.FailureStage
import java.util.Locale

/**
 * The graded upload check (see [TrafficCheck.upload]): each reference it tried, each size it sent there, how
 * many bytes were written over time and where a transfer stopped. On Iranian mobile networks the upload is
 * often throttled on the tunnel's own leg, so a single upload that fails says little; this says how far it got.
 *
 * Byte counts are bytes the app wrote to its socket. They include local buffering, so a stall count is where
 * writing stopped, not proof of how much the far side received; a grade is confirmed only by the reference.
 */
data class UploadProbe(
    val attempts: List<HostAttempt>,
    /** References not tried: Cloudflare-hosted behind a Cloudflare Worker exit, or the time budget was spent. */
    val skipped: List<String> = emptyList(),
    val grades: List<Int> = TrafficCheck.UPLOAD_GRADES
) {
    enum class Outcome { OK, STALLED, FAILED, NOT_TESTED }

    /** [bytes] written [atMs] after the transfer began. */
    data class Sample(val atMs: Long, val bytes: Int)

    data class Grade(
        val size: Int,
        val outcome: Outcome,
        /** Bytes written to the socket before it was confirmed or stopped. */
        val sentBytes: Int,
        val elapsedMs: Long,
        val timeline: List<Sample> = emptyList(),
        val stage: FailureStage? = null
    )

    data class HostAttempt(val host: String, val grades: List<Grade>) {
        /** Largest size the reference confirmed, or 0. */
        val confirmedBytes: Int get() = grades.filter { it.outcome == Outcome.OK }.maxOfOrNull { it.size } ?: 0
        /** The first grade that was not confirmed, if any. */
        val firstMiss: Grade? get() = grades.firstOrNull { it.outcome != Outcome.OK }
        fun complete(all: List<Int>): Boolean = all.isNotEmpty() && confirmedBytes >= (all.maxOrNull() ?: 0)
    }

    /** The attempt that got furthest: most bytes confirmed, then most bytes written before it stalled or failed. */
    val best: HostAttempt? get() = attempts.maxWithOrNull(compareBy<HostAttempt>({ it.confirmedBytes }, { a ->
        a.firstMiss?.takeIf { it.outcome == Outcome.STALLED || it.outcome == Outcome.FAILED }?.sentBytes ?: -1 }))

    val confirmedBytes: Int get() = best?.confirmedBytes ?: 0

    /** Bytes written when the best attempt's first unconfirmed grade stalled; null when nothing stalled there. */
    val stallBytes: Int? get() = best?.firstMiss?.takeIf { it.outcome == Outcome.STALLED }?.sentBytes

    /** Some grades confirmed and a larger one was not. */
    val partial: Boolean get() = confirmedBytes > 0 && best?.firstMiss != null

    /** As a leg: passed from [TrafficCheck.UPLOAD_PASS_BYTES] confirmed; partial or failed below; not tested when nothing answered. */
    fun toLeg(notTested: List<String> = emptyList()): LegResult {
        val b = best
        val okGrade = b?.grades?.filter { it.outcome == Outcome.OK }?.maxByOrNull { it.size }
        val bar = TrafficCheck.UPLOAD_PASS_BYTES.coerceAtMost(grades.maxOrNull() ?: 0)
        if (okGrade != null && okGrade.size >= bar) return LegResult.Passed(okGrade.size, okGrade.elapsedMs)
        val measured = attempts.any { a -> a.grades.any { it.outcome != Outcome.NOT_TESTED } }
        if (b == null || !measured) return LegResult.NotTested(notTested.joinToString("; ").ifEmpty { "no upload reference" } + skippedNote())
        val miss = b.firstMiss
        // stalled stays false: a socket write is local buffering, never proof of an MTU black hole (see TrafficCheck).
        return LegResult.Failed(miss?.stage ?: FailureStage.TIMEOUT, miss?.sentBytes ?: 0, stalled = false,
            reason = summary() + "; remote receipt unconfirmed")
    }

    private fun skippedNote() = if (skipped.isEmpty()) "" else " (skipped ${skipped.joinToString(", ")})"

    /** "speed.cloudflare.com 16 KB ok, 64 KB ok, 256 KB stalled after 98304 B" for the best attempt. */
    fun summary(): String {
        val b = best ?: return "no upload reference" + skippedNote()
        return "${b.host} " + b.grades.joinToString(", ") { g -> "${g.size / 1024} KB " + gradeWord(g) }
    }

    private fun gradeWord(g: Grade): String = when (g.outcome) {
        Outcome.OK -> "ok"
        Outcome.STALLED -> "stalled after ${g.sentBytes} B"
        Outcome.FAILED -> "failed after ${g.sentBytes} B (${g.stage?.name ?: "error"})"
        Outcome.NOT_TESTED -> "not tested"
    }

    /** Every attempt, every grade with its time and its bytes-over-time, for the log and the diagnostics report. */
    fun describe(): String {
        if (attempts.isEmpty()) return "upload grades: none tried" + skippedNote()
        val parts = attempts.joinToString(" | ") { a ->
            a.host + ": " + a.grades.joinToString(", ") { g ->
                "${g.size / 1024} KB ${gradeWord(g)} in ${secs(g.elapsedMs)}" +
                    if (g.outcome != Outcome.OK && g.timeline.isNotEmpty()) " [" + timeline(g.timeline) + "]" else ""
            }
        }
        val verdict = when {
            best?.complete(grades) == true -> "all grades ok"
            partial -> "partial: ${confirmedBytes / 1024} KB ok" + (stallBytes?.let { ", stalled at $it B" } ?: "")
            else -> "no grade confirmed" + (stallBytes?.let { ", stalled at $it B" } ?: "")
        }
        return "upload grades ($verdict): $parts" + skippedNote()
    }

    companion object {
        /** "0.4s:16K 1.1s:32K 9.8s:48K": bytes written over time. */
        fun timeline(samples: List<Sample>): String =
            samples.joinToString(" ") { "${secs(it.atMs)}:${it.bytes / 1024}K" }

        private fun secs(ms: Long): String = String.format(Locale.US, "%.1fs", ms / 1000.0)
    }
}
