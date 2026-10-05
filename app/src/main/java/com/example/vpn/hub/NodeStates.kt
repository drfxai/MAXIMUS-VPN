package com.example.vpn.hub

import com.example.data.model.VlessProfile

enum class SecurityState { VERIFIED, UNVERIFIED, INSECURE, UNSUPPORTED }
enum class HealthState { UNTESTED, HEALTHY, DEGRADED, OFFLINE }
enum class PerformanceState { UNKNOWN, FAST, NORMAL, SLOW }

/** A config from a free source with its three separate states. Only HEALTHY, non-quarantined nodes are offered. */
data class HubNode(
    val profile: VlessProfile,
    val providerId: String,
    val security: SecurityState,
    val health: HealthState = HealthState.UNTESTED,
    val performance: PerformanceState = PerformanceState.UNKNOWN,
    /** Why it is quarantined, or null. */
    val quarantineReason: String? = null
) {
    val quarantined: Boolean get() = quarantineReason != null
    val approved: Boolean get() = !quarantined && health == HealthState.HEALTHY
}

/** Turns real-request results into health and performance states. */
object ConfigHealthScorer {
    const val FAST_MS = 400L
    const val SLOW_MS = 1500L

    fun performance(latencyMs: Long?): PerformanceState = when {
        latencyMs == null -> PerformanceState.UNKNOWN
        latencyMs <= FAST_MS -> PerformanceState.FAST
        latencyMs <= SLOW_MS -> PerformanceState.NORMAL
        else -> PerformanceState.SLOW
    }

    /** [successes] of [attempts] real requests carried traffic. */
    fun health(successes: Int, attempts: Int): HealthState = when {
        attempts == 0 -> HealthState.UNTESTED
        successes == 0 -> HealthState.OFFLINE
        successes < attempts -> HealthState.DEGRADED
        else -> HealthState.HEALTHY
    }
}
