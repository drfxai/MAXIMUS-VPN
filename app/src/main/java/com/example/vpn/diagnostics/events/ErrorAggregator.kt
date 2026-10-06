package com.example.vpn.diagnostics.events

import com.example.vpn.diagnostics.FailureStage

/**
 * Groups repeated failures so a report says "18 proxy requests timed out" once, with when it first
 * and last happened, which profiles, networks and sessions it touched, instead of 18 lines.
 */
object ErrorAggregator {
    data class Group(
        val name: String,
        val stage: FailureStage?,
        val count: Int,
        val firstAt: Long,
        val lastAt: Long,
        val profiles: Set<String>,
        val networks: Set<String>,
        val sessions: Set<String>,
        /** One example message (already redacted). */
        val sample: String?
    ) {
        /** "18 × proxy.request (TIMEOUT)". */
        val summary: String get() = "$count × $name" + (stage?.let { " (${it.name})" } ?: "")
    }

    private fun isFailure(e: DiagEvent) =
        e.result == "FAIL" || e.severity == Severity.ERROR || e.severity == Severity.FATAL || e.severity == Severity.WARN && e.failureStage != null

    /** Failure groups, most frequent first; each set is bounded so one noisy error cannot grow a report. */
    fun aggregate(events: List<DiagEvent>, maxIds: Int = 10): List<Group> =
        events.filter(::isFailure)
            .groupBy { it.name to it.failureStage }
            .map { (key, list) ->
                Group(
                    name = key.first,
                    stage = key.second,
                    count = list.size,
                    firstAt = list.minOf { it.timestamp },
                    lastAt = list.maxOf { it.timestamp },
                    profiles = list.mapNotNull { it.profileRef }.distinct().take(maxIds).toSet(),
                    networks = list.mapNotNull { it.network }.distinct().take(maxIds).toSet(),
                    sessions = list.mapNotNull { it.sessionId }.distinct().take(maxIds).toSet(),
                    sample = list.last().exception ?: list.last().attributes["message"]
                )
            }
            .sortedWith(compareByDescending<Group> { it.count }.thenByDescending { it.lastAt })
}
