package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.ProbeBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The LAB's limits (spec sections 27 and 51), on top of the shared [ProbeBudget]. Nothing scans continuously:
 * an experiment is a few rounds of real requests through at most [maxCandidates] copies, spaced out, capped in
 * time, and admitted only within an hourly allowance. Background experiments also need an unmetered network,
 * enough battery and an idle VPN.
 */
data class LabBudget(
    val maxCandidates: Int = 5,
    val rounds: Int = 3,
    val roundSpacingMs: Long = 15_000,
    val probeTimeoutSec: Int = 8,
    val maxExperimentMs: Long = 4 * 60_000,
    val maxExperimentsPerHour: Int = 4,
    val minGapSameConfigMs: Long = 10 * 60_000,
    val lowBatteryPercent: Int = ProbeBudget.DEFAULT.lowBatteryPercent + 10,
    val probe: ProbeBudget = ProbeBudget.DEFAULT
) {
    init {
        require(maxCandidates in 1..10 && rounds in 1..10 && maxExperimentsPerHour in 1..20)
        require(roundSpacingMs >= 0 && maxExperimentMs in 10_000..30 * 60_000)
    }

    /** The device state an admission decision depends on. Null means not known. */
    data class DeviceState(
        val metered: Boolean?, val batteryPercent: Int?, val charging: Boolean?, val vpnRunning: Boolean, val userStarted: Boolean
    )

    /** Null when an experiment may start now, else why not (shown to the user as is). */
    fun refusal(state: DeviceState, recentStarts: List<Long>, lastForConfig: Long?, now: Long): String? = when {
        state.vpnRunning -> "Experiments run while the VPN is off (the test core cannot run beside the VPN's)."
        recentStarts.count { now - it < 3_600_000L } >= maxExperimentsPerHour -> "Hourly experiment limit reached; LAB waits instead of probing harder."
        lastForConfig != null && now - lastForConfig < minGapSameConfigMs && !state.userStarted -> "This config was tested a few minutes ago."
        !state.userStarted && state.metered == true -> "On a metered network LAB only experiments when you start it."
        !state.userStarted && state.batteryPercent != null && state.batteryPercent < lowBatteryPercent && state.charging != true -> "Battery is low; background experiments are paused."
        else -> null
    }

    companion object { val DEFAULT = LabBudget() }
}

/** One real-request result for a candidate copy. [measured] is false when nothing could be measured (core busy). */
data class TestOutcome(val measured: Boolean, val success: Boolean, val latencyMs: Long? = null, val failure: LabFailureCategory? = null) {
    companion object {
        fun notRun() = TestOutcome(measured = false, success = false)
        fun passed(ms: Long) = TestOutcome(true, true, ms)
        fun failed(category: LabFailureCategory) = TestOutcome(true, false, failure = category)
    }
}

/** Tests a batch of copies with real requests (on the phone: the bundled core's pingBatch). */
fun interface CandidateTester {
    suspend fun test(profiles: List<VlessProfile>): List<TestOutcome>
}

/**
 * Runs one bounded, cancellable experiment (spec section 26):
 *
 * CREATED → QUEUED → TESTING (rounds of real requests) → VERIFYING (promotion) → CANDIDATE / VERIFIED,
 * or REJECTED (nothing passed the security gate), FAILED (nothing worked), CANCELLED (stopped, network
 * changed, or the core could not run).
 *
 * The original config is never changed; copies are rebuilt in memory and only their measurements are kept.
 * Candidates that fail twice in a row without a success are dropped early to save requests.
 */
class ExperimentEngine(
    private val budget: LabBudget = LabBudget.DEFAULT,
    private val promotion: CandidatePromotionPolicy = CandidatePromotionPolicy.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    /** Says whether the network session the experiment started in is still the current one. */
    fun interface Freshness { fun isCurrent(): Boolean }

    suspend fun run(
        experiment: LabExperiment,
        copies: Map<String, VlessProfile>,
        tester: CandidateTester,
        freshness: Freshness,
        onUpdate: (LabExperiment) -> Unit = {}
    ): LabExperiment {
        var e = experiment.copy(state = ExperimentState.QUEUED, candidates = experiment.candidates.take(budget.maxCandidates))
        onUpdate(e)
        if (e.candidates.none { it.securityPassed && copies[it.candidateId] != null }) {
            return finish(e, ExperimentState.REJECTED, "No candidate passed the experiment allowlist and security gate.", onUpdate)
        }
        e = e.copy(state = ExperimentState.TESTING)
        onUpdate(e)
        val deadline = clock() + budget.maxExperimentMs
        try {
            for (round in 1..budget.rounds) {
                if (!freshness.isCurrent()) return finish(e, ExperimentState.CANCELLED, "The network changed; results from the old network were not used.", onUpdate)
                val live = e.candidates.filter { c -> c.securityPassed && copies[c.candidateId] != null && !dropped(c) }
                if (live.isEmpty()) break
                val started = clock()
                val outcomes = withTimeoutOrNull((deadline - clock()).coerceAtLeast(1)) {
                    tester.test(live.map { copies.getValue(it.candidateId) })
                } ?: return finish(e, if (e.candidates.any { it.stats.successes > 0 }) ExperimentState.VERIFYING else ExperimentState.CANCELLED,
                    "The experiment reached its time limit.", onUpdate).let { verify(it, onUpdate) }
                if (!freshness.isCurrent()) return finish(e, ExperimentState.CANCELLED, "The network changed; results from the old network were not used.", onUpdate)
                val now = clock()
                if (outcomes.none { it.measured }) {
                    return finish(e, ExperimentState.CANCELLED, "Nothing could be measured (the test core did not run).", onUpdate)
                }
                val byId = live.mapIndexed { i, c -> c.candidateId to outcomes.getOrElse(i) { TestOutcome.notRun() } }.toMap()
                val updated = e.candidates.map { c ->
                    val o = byId[c.candidateId] ?: return@map c
                    if (!o.measured) c else c.copy(stats = c.stats.record(o.success, o.latencyMs, o.failure, now))
                }
                val measurements = live.map { c ->
                    val o = byId.getValue(c.candidateId)
                    LabMeasurement(e.experimentId, c.candidateId, "round $round", started, now, if (o.measured) o.success else null, o.failure, o.latencyMs)
                }
                e = e.copy(candidates = updated, measurements = (e.measurements + measurements).takeLast(LabExperiment.MAX_MEASUREMENTS))
                onUpdate(e)
                if (round < budget.rounds) {
                    if (clock() + budget.roundSpacingMs >= deadline) break
                    sleep(budget.roundSpacingMs)
                }
            }
        } catch (c: CancellationException) {
            onUpdate(e.copy(state = ExperimentState.CANCELLED, endTime = clock(), note = "Cancelled."))
            throw c
        }
        return verify(e.copy(state = ExperimentState.VERIFYING).also(onUpdate), onUpdate)
    }

    private fun dropped(c: LabCandidate) = c.stats.successes == 0 && c.stats.consecutiveFailures >= 2

    private fun verify(e: LabExperiment, onUpdate: (LabExperiment) -> Unit): LabExperiment {
        if (e.state.terminal) return e
        val now = clock()
        val promoted = e.candidates.map { c -> c.copy(state = promotion.evaluate(c.stats, c.securityPassed, c.state, now)) }
        val state = when {
            promoted.any { it.state == PromotionState.VERIFIED } -> ExperimentState.VERIFIED
            promoted.any { it.state == PromotionState.CANDIDATE || (it.state == PromotionState.EXPERIMENTAL && it.stats.successes > 0) } -> ExperimentState.CANDIDATE
            else -> ExperimentState.FAILED
        }
        val note = when (state) {
            ExperimentState.VERIFIED -> "A copy worked in every check and was verified on this network."
            ExperimentState.CANDIDATE -> "A copy worked but needs more evidence before it is verified."
            else -> "No copy got a request through on this network."
        }
        return e.copy(candidates = promoted, state = state, endTime = now, note = note).also(onUpdate)
    }

    private fun finish(e: LabExperiment, state: ExperimentState, note: String, onUpdate: (LabExperiment) -> Unit): LabExperiment {
        if (state == ExperimentState.VERIFYING) return e.copy(state = state, note = note)
        return e.copy(state = state, endTime = clock(), note = note).also(onUpdate)
    }
}
