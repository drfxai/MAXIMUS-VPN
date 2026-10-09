package com.example.vpn.lab

import com.example.vpn.smart.NetworkCapabilityProfile

/** One human-readable step of what LAB is doing now (spec section 43). */
data class LabStep(val title: String, val state: State, val detail: String = "") {
    enum class State { PENDING, RUNNING, DONE, FAILED, SKIPPED }
}

/**
 * The live view of a running Full Analysis, republished after every test: path rows move QUEUED → TESTING →
 * their result while the run goes on. [firstWorking] is the first path that passed a real request (shown at
 * once); [best] is the best path so far, verified first.
 */
data class LiveAnalysis(
    val sessionId: String,
    val networkLabel: String,
    val state: String,
    val phase: AdaptivePlanner.Phase,
    val hypothesis: String,
    /** "Tor WebTunnel · My bridge", or null between tests. */
    val currentTest: String?,
    val paths: List<LivePath>,
    val firstWorking: LabPick?,
    val best: LabPick?,
    val testsDone: Int,
    val budget: Int,
    /** Replanning steps so far ("A UDP method passed: UDP families raised."). */
    val events: List<String>,
    val updatedAt: Long
)

/** A saved config the LAB points at, with how sure it is. */
data class LabPick(val profileId: String, val name: String, val family: PathFamily, val status: PathStatus, val latencyMs: Long?)

/** Everything the LAB screens show; built by [LabController], plain data so screens can be previewed. */
data class LabSnapshot(
    val network: NetworkContext? = null,
    val capability: NetworkCapabilityProfile? = null,
    /** The classified state of the phone's own network (with hysteresis); null until first measured. */
    val networkState: NetworkStateReading? = null,
    val automation: AutomationLevel = AutomationLevel.RECOMMEND,
    val running: LabExperiment? = null,
    val steps: List<LabStep> = emptyList(),
    val experiments: List<LabExperiment> = emptyList(),
    val verified: List<VerifiedNetworkProfile> = emptyList(),
    val discoveries: List<LabDiscovery> = emptyList(),
    val networks: List<LabStore.NetworkSeen> = emptyList(),
    val plan: LabStore.ReturnPlan? = null,
    /** Config optimizer transactions, newest first (staged, committed, rolled back, refused). */
    val transactions: List<ConfigTransaction> = emptyList(),
    /** The last Full Analysis of the current network (null until one ran here). */
    val analysis: AnalysisReport? = null,
    /** True while a Full Analysis runs. */
    val analysisRunning: Boolean = false,
    /** What the running Full Analysis shows right now; null when none runs. */
    val live: LiveAnalysis? = null,
    val message: String? = null,
    /** Raw LAB log lines for the Advanced view; redacted like every other log. */
    val log: List<String> = emptyList()
)
