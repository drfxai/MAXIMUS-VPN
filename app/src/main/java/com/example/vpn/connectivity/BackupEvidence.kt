package com.example.vpn.connectivity

import com.example.data.model.VlessProfile

/**
 * What is known about a backup server, from its last real test (lastLatencyMs / lastTestedTimestamp).
 * A backup is only called verified with a recent passed test; parsing and engine support alone make it
 * merely AVAILABLE. Tests are not tied to a network, so "verified" means "carried traffic recently on
 * this phone", not "will work on this network".
 */
enum class BackupReadiness(val label: String) {
    VERIFIED("verified recently"),
    PREVIOUSLY_VERIFIED("previously verified"),
    AVAILABLE("not tested"),
    RECENTLY_FAILED("failed its last test")
}

object BackupEvidence {
    /** A passed test younger than this counts as recent evidence. */
    const val RECENT_MS = 30 * 60_000L

    fun readiness(profile: VlessProfile, now: Long = System.currentTimeMillis()): BackupReadiness {
        val testedAt = profile.lastTestedTimestamp
        val recent = testedAt != null && now - testedAt in 0..RECENT_MS
        return when {
            profile.lastLatencyMs != null && recent -> BackupReadiness.VERIFIED
            profile.lastLatencyMs != null -> BackupReadiness.PREVIOUSLY_VERIFIED
            recent -> BackupReadiness.RECENTLY_FAILED
            else -> BackupReadiness.AVAILABLE
        }
    }

    /**
     * Ranking for backup selection: evidence first, the stored score only within the same evidence
     * level, so a recently verified server always beats an untested one with a higher old score.
     */
    fun rank(profile: VlessProfile, now: Long = System.currentTimeMillis()): Double = when (readiness(profile, now)) {
        BackupReadiness.VERIFIED -> 3_000.0
        BackupReadiness.PREVIOUSLY_VERIFIED -> 2_000.0
        BackupReadiness.AVAILABLE -> 1_000.0
        BackupReadiness.RECENTLY_FAILED -> 0.0
    } + profile.overallScore.coerceIn(0.0, 999.0)

    fun describe(profile: VlessProfile, now: Long = System.currentTimeMillis()): String {
        val state = readiness(profile, now)
        val age = profile.lastTestedTimestamp?.let { ((now - it).coerceAtLeast(0) / 60_000).let { m -> if (m < 1) "under a minute" else "$m min" } }
        return when (state) {
            BackupReadiness.VERIFIED -> "verified $age ago"
            else -> state.label
        }
    }
}
