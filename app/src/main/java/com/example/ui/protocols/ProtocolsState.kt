package com.example.ui.protocols

import com.example.panels.ManagedPanel
import com.example.vpn.lab.LabFamily
import com.example.vpn.lab.LabPriority
import com.example.vpn.lab.LabResult

sealed class LabPhase {
    data object Idle : LabPhase()
    data class Running(val step: String, val done: Int, val total: Int) : LabPhase()
    data object Done : LabPhase()
}

data class ProtocolsUiState(
    val panels: List<ManagedPanel> = emptyList(),
    val panel: ManagedPanel? = null,
    val priority: LabPriority = LabPriority.BALANCED,
    val families: Set<LabFamily> = LabFamily.serverFamilies.toSet(),
    val savedCdnCount: Int = 0,
    val phase: LabPhase = LabPhase.Idle,
    val results: List<LabResult> = emptyList(),
    /** True when [results] came from the user's server rather than saved configs. */
    val serverRun: Boolean = false,
    val testedAt: Long = 0L,
    val selected: LabFamily? = null,
    val error: String = "",
    val autoFailover: Boolean = true,
    val applying: Boolean = false,
    val usePanel: Boolean = true
) {
    val working: List<LabResult> get() = results.filter { it.works }
    val blocked: List<LabResult> get() = results.filterNot { it.works }
    val best: LabResult? get() = working.firstOrNull()
    val chosen: LabResult? get() = working.firstOrNull { it.family == selected } ?: best
}
