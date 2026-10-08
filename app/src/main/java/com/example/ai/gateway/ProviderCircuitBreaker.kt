package com.example.ai.gateway

/**
 * Stops the gateway from hammering a failing provider. One breaker per provider:
 *
 * - CLOSED: requests flow.
 * - OPEN: requests are refused until [retryAt]; the router moves on to the next provider.
 * - HALF_OPEN: after the cooldown exactly one request is let through as a health check; success closes the
 *   breaker, failure opens it again with a doubled cooldown.
 *
 * Per error: an authentication failure keeps the provider off until its key changes (no timer); quota
 * exhaustion pauses it for [quotaCooldownMs] (or the provider's stated wait); a rate limit pauses it for the
 * provider's Retry-After, else [rateLimitCooldownMs]; timeouts and unreachable networks open after
 * [timeoutStreakToOpen] in a row; server errors open after [serverStreakToOpen] in a row with exponential
 * backoff. Model-level errors (unknown model, bad request) never trip the provider.
 */
class ProviderCircuitBreaker(
    val providerId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    val baseBackoffMs: Long = 15_000,
    val maxBackoffMs: Long = 10 * 60_000,
    val rateLimitCooldownMs: Long = 30_000,
    val quotaCooldownMs: Long = 60 * 60_000,
    val timeoutStreakToOpen: Int = 3,
    val serverStreakToOpen: Int = 2
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    var state: State = State.CLOSED
        private set
    var retryAt: Long? = null
        private set
    var failureStreak: Int = 0
        private set
    /** The error that opened the breaker, for the health state. */
    var openedBy: AiErrorKind? = null
        private set
    private var opens = 0
    private var probeInFlight = false

    var lastSuccessAt: Long? = null
        private set
    var lastFailureAt: Long? = null
        private set
    private val latencies = ArrayDeque<Long>()
    private val errorTimes = ArrayDeque<Long>()

    /** Whether a request may go now. Moving OPEN → HALF_OPEN hands out a single probe slot. */
    @Synchronized
    fun allow(): Boolean {
        val now = clock()
        return when (state) {
            State.CLOSED -> true
            State.OPEN -> {
                val at = retryAt ?: return false // no timer: waits for a new key
                if (now < at) return false
                state = State.HALF_OPEN
                probeInFlight = true
                true
            }
            State.HALF_OPEN -> if (probeInFlight) false else { probeInFlight = true; true }
        }
    }

    @Synchronized
    fun recordSuccess(latencyMs: Long) {
        val now = clock()
        state = State.CLOSED
        retryAt = null
        openedBy = null
        failureStreak = 0
        opens = 0
        probeInFlight = false
        lastSuccessAt = now
        latencies.addLast(latencyMs)
        while (latencies.size > WINDOW) latencies.removeFirst()
    }

    @Synchronized
    fun recordFailure(error: AiError) {
        if (error.kind == AiErrorKind.CANCELLED || error.kind == AiErrorKind.CIRCUIT_OPEN) { probeInFlight = false; return }
        val now = clock()
        lastFailureAt = now
        errorTimes.addLast(now)
        while (errorTimes.size > WINDOW) errorTimes.removeFirst()
        if (!error.kind.providerWide) {
            // One model failed, not the provider; a half-open probe that hit a bad model proves the provider answers.
            if (state == State.HALF_OPEN) { state = State.CLOSED; retryAt = null; openedBy = null }
            probeInFlight = false
            return
        }
        failureStreak++
        val wasProbe = state == State.HALF_OPEN
        probeInFlight = false
        val cooldown: Long? = when (error.kind) {
            AiErrorKind.AUTH_FAILED, AiErrorKind.NOT_CONFIGURED -> null
            AiErrorKind.QUOTA_EXHAUSTED -> error.retryAfterMs ?: quotaCooldownMs
            AiErrorKind.RATE_LIMITED -> (error.retryAfterMs ?: rateLimitCooldownMs).coerceAtLeast(1_000)
            AiErrorKind.TIMEOUT, AiErrorKind.UNREACHABLE ->
                if (wasProbe || failureStreak >= timeoutStreakToOpen) backoff() else return
            AiErrorKind.SERVER_ERROR, AiErrorKind.UNKNOWN ->
                if (wasProbe || failureStreak >= serverStreakToOpen) backoff() else return
            else -> backoff()
        }
        state = State.OPEN
        openedBy = error.kind
        opens++
        retryAt = cooldown?.let { now + it }
    }

    private fun backoff(): Long = (baseBackoffMs shl opens.coerceAtMost(10)).coerceAtMost(maxBackoffMs)

    /** A new key or a changed endpoint: whatever the provider refused before is worth one more try. */
    @Synchronized
    fun reset() {
        state = State.CLOSED
        retryAt = null
        openedBy = null
        failureStreak = 0
        opens = 0
        probeInFlight = false
    }

    @Synchronized
    fun health(enabled: Boolean = true, hasKey: Boolean = true): ProviderHealth {
        val now = clock()
        val st = when {
            !enabled || !hasKey -> ProviderHealthState.DISABLED
            state != State.CLOSED -> healthStateOf(openedBy ?: AiErrorKind.UNKNOWN)
            failureStreak > 0 -> ProviderHealthState.DEGRADED
            latencies.isNotEmpty() && latencies.average() > SLOW_MS -> ProviderHealthState.DEGRADED
            else -> ProviderHealthState.HEALTHY
        }
        return ProviderHealth(
            providerId = providerId, state = st,
            recentLatencyMs = latencies.takeIf { it.isNotEmpty() }?.let { l -> l.sorted()[l.size / 2] },
            recentErrors = errorTimes.count { now - it < RECENT_MS }, failureStreak = failureStreak,
            lastSuccessAt = lastSuccessAt, lastFailureAt = lastFailureAt, retryAt = if (state == State.OPEN) retryAt else null
        )
    }

    /** Recent success ratio (0..1) from the last few outcomes, for SMART ranking. */
    @Synchronized
    fun reliability(): Double {
        val ok = latencies.size
        val bad = errorTimes.count { clock() - it < RECENT_MS }
        return if (ok + bad == 0) 0.5 else ok.toDouble() / (ok + bad)
    }

    companion object {
        const val WINDOW = 10
        const val RECENT_MS = 15 * 60_000L
        const val SLOW_MS = 20_000.0

        fun healthStateOf(kind: AiErrorKind): ProviderHealthState = when (kind) {
            AiErrorKind.AUTH_FAILED -> ProviderHealthState.AUTH_FAILED
            AiErrorKind.NOT_CONFIGURED -> ProviderHealthState.DISABLED
            AiErrorKind.QUOTA_EXHAUSTED -> ProviderHealthState.QUOTA_EXHAUSTED
            AiErrorKind.RATE_LIMITED -> ProviderHealthState.RATE_LIMITED
            AiErrorKind.TIMEOUT, AiErrorKind.UNREACHABLE -> ProviderHealthState.UNREACHABLE
            else -> ProviderHealthState.TEMPORARY_ERROR
        }
    }
}
