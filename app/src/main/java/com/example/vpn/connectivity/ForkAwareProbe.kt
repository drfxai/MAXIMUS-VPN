package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.xray.RealDelayProbe

/**
 * Real-request tests for recovery candidates where some need the patterniha Xray fork (a `dialMode`, see
 * [com.example.vpn.sidecar.ForkXraySidecar]). Stock libXray drops `dialMode`, so its probe would test a different
 * path; those candidates are tested on the fork instead (an isolated fork sidecar and a real request through it),
 * one at a time, and every other candidate goes through the usual batched stock probe. Order is kept.
 */
object ForkAwareProbe {
    fun <R> measure(
        profiles: List<VlessProfile>,
        needsFork: (VlessProfile) -> Boolean,
        stock: (List<VlessProfile>) -> List<R>,
        fork: (VlessProfile) -> R,
        missing: R
    ): List<R> {
        val forked = profiles.map(needsFork)
        val plain = profiles.filterIndexed { i, _ -> !forked[i] }
        val plainResults = if (plain.isEmpty()) emptyList() else stock(plain)
        var next = 0
        return profiles.mapIndexed { i, p ->
            if (forked[i]) fork(p) else plainResults.getOrNull(next++) ?: missing
        }
    }

    /** A fork engine test as a recovery test result. */
    fun testResult(carried: Boolean, notTested: Boolean, latencyMs: Long?, failure: String?): DerivedRecoveryCandidate.TestResult = when {
        carried -> DerivedRecoveryCandidate.TestResult.Passed(latencyMs ?: 0L)
        notTested -> DerivedRecoveryCandidate.TestResult.NotRun(failure ?: "the fork could not run")
        else -> DerivedRecoveryCandidate.TestResult.Failed(failure ?: "no traffic through the fork")
    }

    /** A fork engine test as a probe outcome. */
    fun outcome(carried: Boolean, notTested: Boolean, latencyMs: Long?, failure: String?): RealDelayProbe.Outcome = when {
        carried -> RealDelayProbe.Outcome.Delay(latencyMs ?: 0L, "fork-xray")
        notTested -> RealDelayProbe.Outcome.NotRun(failure ?: "the fork could not run")
        else -> RealDelayProbe.Outcome.Failed(failure ?: "no traffic through the fork")
    }
}
