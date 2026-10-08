package com.example.vpn.connectivity

import com.example.vpn.diagnostics.FailureStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Checks a config or a running tunnel step by step (DNS, TCP, TLS, proxy protocol, engine, TUN, a request
 * through the tunnel, DNS through the tunnel, a short stability watch) and says exactly which step
 * failed. Steps are supplied by the caller, so the same engine drives a pre-connect test, the
 * post-connect verification and diagnostics.
 *
 * Rules:
 * - ICMP is never a step; nothing here restarts a tunnel because ping fails.
 * - Every step has a time limit and a bounded number of retries with exponential backoff
 *   ([ProbeBudget]). A security refusal, a refused certificate or a failed login is never retried.
 * - A run whose session or network changed while it ran is reported [HealthReport.stale] and must
 *   not be used as current evidence.
 */
class MultiProbeHealthEngine(
    private val budget: ProbeBudget = ProbeBudget.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** One step. [run] returns a measured value in ms (or null) and throws when the step fails. */
    interface StepProbe {
        val step: ProbeStep
        suspend fun run(): Long?
    }

    /** Says whether the run still belongs to the current session and network. */
    fun interface Freshness {
        fun isCurrent(): Boolean
    }

    /** What the run is about; kept in every [ProbeResult]. */
    data class Subject(
        val sessionId: String,
        val configFingerprint: String? = null,
        val engine: String? = null,
        val networkProfileId: String? = null
    )

    suspend fun run(
        subject: Subject,
        steps: List<StepProbe>,
        freshness: Freshness = Freshness { true },
        /** Checked before any network step; a non-null reason refuses the run. */
        securityProblem: () -> String? = { null }
    ): HealthReport {
        securityProblem()?.let {
            val now = clock()
            val r = ProbeResult(
                steps.firstOrNull()?.step ?: ProbeStep.DNS_RESOLUTION, now, now, false, FailureStage.SECURITY_REJECTED,
                subject.networkProfileId, subject.configFingerprint, subject.engine
            )
            return HealthReport(subject.sessionId, listOf(r), ProbeVerdict.SECURITY_REJECTED, r.step, FailureStage.SECURITY_REJECTED)
        }
        val results = mutableListOf<ProbeResult>()
        val deadline = clock() + budget.perConfigTimeoutMs
        for (probe in steps) {
            if (!freshness.isCurrent()) return stale(subject, results)
            val result = runStep(subject, probe, deadline, freshness)
            results += result
            if (!freshness.isCurrent()) return stale(subject, results)
            if (!result.success) {
                val stage = result.failureReason ?: probe.step.failure
                return HealthReport(subject.sessionId, results, verdictOf(results, stage), probe.step, stage)
            }
        }
        val verdict = results.lastOrNull()?.step?.passVerdict ?: ProbeVerdict.UNKNOWN
        return HealthReport(subject.sessionId, results, verdict)
    }

    private suspend fun runStep(subject: Subject, probe: StepProbe, deadline: Long, freshness: Freshness): ProbeResult {
        val started = clock()
        var attempt = 0
        var lastStage: FailureStage
        while (true) {
            val left = deadline - clock()
            if (left <= 0) {
                lastStage = FailureStage.TIMEOUT
                break
            }
            try {
                val value = withTimeout(minOf(budget.perStepTimeoutMs, left)) { probe.run() }
                return ProbeResult(probe.step, started, clock(), true, null, subject.networkProfileId,
                    subject.configFingerprint, subject.engine, attempt, value)
            } catch (e: TimeoutCancellationException) {
                lastStage = FailureStage.TIMEOUT
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastStage = stageOf(probe.step, e)
            }
            if (!retryable(lastStage) || attempt >= budget.maxRetries || !freshness.isCurrent()) break
            attempt++
            val wait = budget.backoffMs(attempt)
            if (clock() + wait >= deadline) break
            delay(wait)
        }
        return ProbeResult(probe.step, started, clock(), false, lastStage, subject.networkProfileId,
            subject.configFingerprint, subject.engine, attempt)
    }

    private fun stale(subject: Subject, results: List<ProbeResult>) =
        HealthReport(subject.sessionId, results, ProbeVerdict.UNKNOWN, null, FailureStage.NETWORK_CHANGED, stale = true)

    companion object {
        /** Failures worth one more try: the network may have dropped a packet. Never security ones. */
        fun retryable(stage: FailureStage): Boolean = when (stage) {
            FailureStage.TIMEOUT, FailureStage.TCP_CONNECT_FAILED, FailureStage.HTTP_REQUEST_FAILED,
            FailureStage.TLS_HANDSHAKE_FAILED, FailureStage.DNS_RESOLUTION_FAILED, FailureStage.DNS_TUNNEL_FAILED,
            FailureStage.UNKNOWN -> true
            else -> false
        }

        /**
         * The stage of a step's failure: the exception's own stage when it names one more precisely
         * (a refused certificate during TLS), otherwise the step's default.
         */
        fun stageOf(step: ProbeStep, e: Throwable): FailureStage {
            if (e is SecurityRejected) return FailureStage.SECURITY_REJECTED
            val found = FailureStage.of(e)
            return when {
                found == FailureStage.UNKNOWN -> step.failure
                // Inside the tunnel, a resolution error is the tunnel's DNS, not the phone's.
                step == ProbeStep.DNS_THROUGH_TUNNEL && found == FailureStage.DNS_RESOLUTION_FAILED -> FailureStage.DNS_TUNNEL_FAILED
                else -> found
            }
        }

        /** The verdict for a run that stopped at [stage]. "BLOCKED" means it did not get through, nothing more. */
        fun verdictOf(results: List<ProbeResult>, stage: FailureStage): ProbeVerdict = when (stage) {
            FailureStage.SECURITY_REJECTED -> ProbeVerdict.SECURITY_REJECTED
            FailureStage.CERTIFICATE_VALIDATION_FAILED, FailureStage.PROXY_AUTH_FAILED -> ProbeVerdict.INVALID
            FailureStage.TIMEOUT -> ProbeVerdict.TIMEOUT
            FailureStage.CANCELLED, FailureStage.NETWORK_CHANGED -> ProbeVerdict.UNKNOWN
            else -> if (results.any { it.success && it.step >= ProbeStep.TUN_ESTABLISH }) ProbeVerdict.DEGRADED else ProbeVerdict.BLOCKED
        }

        /**
         * A stability step: [samples] requests [intervalMs] apart; passes when at least [minSuccesses]
         * went through. Its value is the median latency of the successful ones.
         */
        fun stability(samples: Int, intervalMs: Long, minSuccesses: Int, request: suspend () -> Long): StepProbe {
            require(samples in 1..10 && minSuccesses in 1..samples)
            return object : StepProbe {
                override val step = ProbeStep.STABILITY
                override suspend fun run(): Long? {
                    val ok = mutableListOf<Long>()
                    var last: Exception? = null
                    repeat(samples) { i ->
                        try { ok += request() } catch (e: CancellationException) { throw e } catch (e: Exception) { last = e }
                        if (i < samples - 1) delay(intervalMs)
                    }
                    if (ok.size < minSuccesses) throw IllegalStateException("Only ${ok.size}/$samples requests went through", last)
                    return ok.sorted()[ok.size / 2]
                }
            }
        }

        fun step(step: ProbeStep, block: suspend () -> Long?): StepProbe = object : StepProbe {
            override val step = step
            override suspend fun run(): Long? = block()
        }
    }

    /** Thrown by a step that refuses to run for a security reason. */
    class SecurityRejected(message: String) : Exception(message)
}
