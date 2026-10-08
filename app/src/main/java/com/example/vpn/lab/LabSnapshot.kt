package com.example.vpn.lab

import com.example.vpn.smart.NetworkCapabilityProfile

/** One human-readable step of what LAB is doing now (spec section 43). */
data class LabStep(val title: String, val state: State, val detail: String = "") {
    enum class State { PENDING, RUNNING, DONE, FAILED, SKIPPED }
}

/** Everything the LAB screens show; built by [LabController], plain data so screens can be previewed. */
data class LabSnapshot(
    val network: NetworkContext? = null,
    val capability: NetworkCapabilityProfile? = null,
    val automation: AutomationLevel = AutomationLevel.RECOMMEND,
    val running: LabExperiment? = null,
    val steps: List<LabStep> = emptyList(),
    val experiments: List<LabExperiment> = emptyList(),
    val verified: List<VerifiedNetworkProfile> = emptyList(),
    val discoveries: List<LabDiscovery> = emptyList(),
    val networks: List<LabStore.NetworkSeen> = emptyList(),
    val plan: LabStore.ReturnPlan? = null,
    val message: String? = null,
    /** Raw LAB log lines for the Advanced view; redacted like every other log. */
    val log: List<String> = emptyList()
)
