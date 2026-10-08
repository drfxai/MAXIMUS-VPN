package com.example.vpn.lab

import com.example.vpn.connectivity.ConnectionScore
import kotlin.math.sqrt

/**
 * Deterministic promotion (spec section 28): measurements decide, AI never does. Thresholds are fields, so
 * tests and later tuning can change them; the defaults follow the existing recovery ledger (rollback after a
 * few failures) and ConnectionScore's 6-hour evidence half-life.
 *
 * EXPERIMENTAL → CANDIDATE → VERIFIED → DEGRADED → RETIRED; a security refusal is REJECTED and never promoted.
 */
data class CandidatePromotionPolicy(
    val minAttemptsForCandidate: Int = 2,
    val minSuccessRateForCandidate: Double = 0.5,
    val minSuccessesForVerified: Int = 3,
    val minSuccessRateForVerified: Double = 0.8,
    /** Evidence older than this cannot keep or earn VERIFIED; the profile is DEGRADED until retested. */
    val maxEvidenceAgeMs: Long = 24 * 60 * 60_000L,
    val degradeBelowSuccessRate: Double = 0.6,
    val retireAfterConsecutiveFailures: Int = 3,
    /** A candidate that never worked after this many tries is retired on this network. */
    val retireNeverWorkedAfter: Int = 3
) {
    init {
        require(minSuccessesForVerified >= 2) { "One success is never enough to verify" }
        require(minSuccessRateForVerified in 0.5..1.0 && minSuccessRateForCandidate in 0.0..minSuccessRateForVerified)
        require(retireAfterConsecutiveFailures in 1..10)
    }

    fun evaluate(stats: CandidateStats, securityPassed: Boolean, previous: PromotionState, now: Long): PromotionState {
        if (!securityPassed) return PromotionState.REJECTED
        if (previous == PromotionState.RETIRED || previous == PromotionState.REJECTED) return previous
        val fresh = stats.lastSuccessAt?.let { now - it <= maxEvidenceAgeMs } ?: false
        if (stats.successes == 0 && stats.attempts >= retireNeverWorkedAfter) return PromotionState.RETIRED
        if (previous == PromotionState.VERIFIED || previous == PromotionState.DEGRADED) {
            if (stats.consecutiveFailures >= retireAfterConsecutiveFailures) return PromotionState.RETIRED
            if (stats.successRate < degradeBelowSuccessRate || !fresh || stats.consecutiveFailures > 0) return PromotionState.DEGRADED
            return PromotionState.VERIFIED
        }
        if (stats.successes >= minSuccessesForVerified && stats.successRate >= minSuccessRateForVerified && fresh &&
            stats.consecutiveFailures == 0 && stats.dnsThroughTunnelOk != false) return PromotionState.VERIFIED
        if (stats.attempts >= minAttemptsForCandidate && stats.successRate >= minSuccessRateForCandidate) return PromotionState.CANDIDATE
        return PromotionState.EXPERIMENTAL
    }

    /**
     * 0..1: the lower bound of the success rate (Wilson, 90%), aged like ConnectionScore's evidence. Few
     * attempts or old evidence give a low confidence however good the rate looks.
     */
    fun confidence(stats: CandidateStats, now: Long): Double {
        val n = stats.attempts
        if (n == 0) return 0.0
        val p = stats.successRate
        val z = 1.645
        val lower = (p + z * z / (2 * n) - z * sqrt((p * (1 - p) + z * z / (4 * n)) / n)) / (1 + z * z / n)
        val fresh = stats.lastSuccessAt?.let { ConnectionScore.freshness(now - it) } ?: 0.0
        return (lower.coerceIn(0.0, 1.0) * (0.5 + 0.5 * fresh)).let { Math.round(it * 100) / 100.0 }
    }

    /** The shared ranking score for a LAB result, through ConnectionScore so LAB and the server list agree. */
    fun score(stats: CandidateStats, securityPassed: Boolean, now: Long): ConnectionScore.Result = ConnectionScore.of(
        ConnectionScore.Inputs(
            securityProblem = if (securityPassed) null else "refused by the security gate",
            protocolOk = stats.successes > 0, tunnelOk = stats.successes > 0, internetOk = stats.successes > 0,
            dnsTunnelOk = stats.dnsThroughTunnelOk, recentSuccesses = stats.successes, recentAttempts = stats.attempts,
            historicalSuccesses = stats.successes, historicalAttempts = stats.attempts, latencyMs = stats.medianLatencyMs,
            jitterMs = stats.jitterMs, networkMatch = true, verifiedSessions = stats.successes, securityMode = "tls",
            lastEvidenceAt = stats.lastSuccessAt ?: stats.lastFailureAt
        ), now
    )

    companion object { val DEFAULT = CandidatePromotionPolicy() }
}
