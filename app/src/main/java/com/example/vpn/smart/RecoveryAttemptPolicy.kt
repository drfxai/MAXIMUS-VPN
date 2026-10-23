package com.example.vpn.smart

/** One search's bounded scheduling; a failed bootstrap is not evidence against another API client. */
class RecoveryAttemptPolicy {
    private val blocked = mutableSetOf<String>()
    fun skip(engineIds: List<String>): Boolean = engineIds.any { it in blocked }
    fun record(engineId: String, failure: String?) {
        if (engineId in setOf("matrix-transport", "masque") &&
            failure?.contains("registration", ignoreCase = true) == true) blocked += engineId
    }
    companion object {
        // Tor's own progress-aware deadline stops stalled bootstraps, with a six-minute hard cap.
        // Leave room for its last socket check and two application requests after readiness.
        const val TOR_WINDOW_MS = 420_000L
        fun window(engineId: String?, remainingMs: Long): Long =
            if (engineId == "tor") TOR_WINDOW_MS else minOf(remainingMs, 90_000L)
    }
}
