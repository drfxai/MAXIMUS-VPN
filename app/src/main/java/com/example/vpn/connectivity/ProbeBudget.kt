package com.example.vpn.connectivity

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Limits on how hard the app probes, so testing never floods the phone or the network. Pure settings;
 * [ProbeBudget.Gate] enforces the concurrency part.
 */
data class ProbeBudget(
    val maxConcurrentConfigProbes: Int = 3,
    val maxConcurrentDnsProbes: Int = 4,
    val maxConcurrentTlsProbes: Int = 3,
    val perStepTimeoutMs: Long = 6_000,
    val perConfigTimeoutMs: Long = 15_000,
    val totalRefreshTimeoutMs: Long = 120_000,
    val maxRetries: Int = 2,
    val retryBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 8_000,
    /** Below this battery level (and not charging) only one config is probed at a time. */
    val lowBatteryPercent: Int = 15,
    /** On a metered network only this many configs are probed at once. */
    val meteredConcurrentConfigProbes: Int = 1,
    /** The most raw candidates one refresh may test, after de-duplication. */
    val maxCandidatesPerRefresh: Int = 60
) {
    init {
        require(maxConcurrentConfigProbes in 1..16 && maxConcurrentDnsProbes in 1..16 && maxConcurrentTlsProbes in 1..16)
        require(maxRetries in 0..5) { "Retries are bounded" }
        require(perStepTimeoutMs in 500..60_000 && perConfigTimeoutMs >= perStepTimeoutMs)
        require(retryBackoffMs in 0..maxBackoffMs)
    }

    /** Backoff before retry [attempt] (1-based): doubles each time, capped. */
    fun backoffMs(attempt: Int): Long {
        if (attempt <= 0) return 0
        val shift = (attempt - 1).coerceAtMost(16)
        return (retryBackoffMs shl shift).coerceAtMost(maxBackoffMs)
    }

    /** Config probes allowed at once on this device right now. */
    fun configConcurrency(metered: Boolean?, batteryPercent: Int?, charging: Boolean?): Int {
        var n = maxConcurrentConfigProbes
        if (metered == true) n = minOf(n, meteredConcurrentConfigProbes)
        if (batteryPercent != null && batteryPercent < lowBatteryPercent && charging != true) n = 1
        return n.coerceAtLeast(1)
    }

    /** Semaphores built from a budget; one per process is enough. */
    class Gate(budget: ProbeBudget) {
        private val configs = Semaphore(budget.maxConcurrentConfigProbes)
        private val dns = Semaphore(budget.maxConcurrentDnsProbes)
        private val tls = Semaphore(budget.maxConcurrentTlsProbes)

        suspend fun <T> config(block: suspend () -> T): T = configs.withPermit { block() }
        suspend fun <T> dns(block: suspend () -> T): T = dns.withPermit { block() }
        suspend fun <T> tls(block: suspend () -> T): T = tls.withPermit { block() }
    }

    companion object {
        val DEFAULT = ProbeBudget()
    }
}
