package com.example.ui.protocols

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.data.model.VlessProfile
import com.example.panels.ManagedPanel
import com.example.panels.PanelStore
import com.example.panels.PanelType
import com.example.panels.ThreeXUiConfigGenerator
import com.example.vpn.VpnController
import com.example.vpn.lab.LabFamily
import com.example.vpn.lab.LabPriority
import com.example.vpn.lab.LabResult
import com.example.vpn.lab.ProtocolLab
import com.example.xray.RealDelayProbe
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The protocol test: which families work on this network, ranked, and setting up the one picked. */
class ProtocolsViewModel(app: Application) : AndroidViewModel(app) {

    private val panelStore = PanelStore(app)
    private val serverRepository = RayApplication.instance.serverRepository
    private val settingsRepository = RayApplication.instance.settingsRepository
    private val generator = ThreeXUiConfigGenerator()

    private val _state = MutableStateFlow(ProtocolsUiState())
    val state: StateFlow<ProtocolsUiState> = _state.asStateFlow()

    /** Test configs created on the server that the user has not kept yet. */
    @Volatile
    private var pendingTestConfigs: List<Pair<ManagedPanel, LabResult>> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val panels = withContext(Dispatchers.IO) {
                panelStore.load().filter {
                    it.type == PanelType.XUI && it.host.isNotBlank() && (it.password.isNotBlank() || it.apiToken.isNotBlank())
                }
            }
            val cdn = withContext(Dispatchers.IO) {
                serverRepository.getAllProfilesOnce().count { LabFamily.of(it) == LabFamily.CDN }
            }
            _state.update { s ->
                s.copy(
                    panels = panels,
                    panel = if (!s.usePanel) null else s.panel?.takeIf { p -> panels.any { it.id == p.id } } ?: panels.firstOrNull(),
                    savedCdnCount = cdn,
                    autoFailover = settingsRepository.getSettings().autoFailoverEnabled
                )
            }
        }
    }

    /** Null tests the configs already saved in the app instead of creating new ones on a server. */
    fun selectPanel(panel: ManagedPanel?) = _state.update { it.copy(panel = panel, usePanel = panel != null) }

    fun toggleFamily(family: LabFamily) = _state.update { s ->
        val next = if (family in s.families) s.families - family else s.families + family
        s.copy(families = next.ifEmpty { s.families })
    }

    /** Re-weighs the measured rounds for another priority; nothing is measured again. */
    fun setPriority(priority: LabPriority) = _state.update { s ->
        val rescored = s.results.map { it.copy(score = if (it.works) ProtocolLab.score(it, priority) else 0) }
        s.copy(priority = priority, results = ProtocolLab.rank(rescored), selected = null)
    }

    fun select(family: LabFamily) = _state.update { it.copy(selected = family) }

    fun dismissError() = _state.update { it.copy(error = "") }

    fun setAutoFailover(enabled: Boolean) {
        settingsRepository.updateSettings(settingsRepository.getSettings().copy(autoFailoverEnabled = enabled))
        _state.update { it.copy(autoFailover = enabled) }
    }

    fun runTest() {
        val s = _state.value
        if (s.phase is LabPhase.Running) return
        if (VpnController.connectionState.value.let { it.isTunnelUp || it.isBusy }) {
            _state.update { it.copy(error = "Disconnect the VPN first. The test needs your direct connection to see what your network blocks.") }
            return
        }
        _state.update { it.copy(phase = LabPhase.Running("Getting ready", 0, 1), error = "", selected = null) }
        viewModelScope.launch {
            try {
                discardPending()
                val panel = s.panel
                val lab = ProtocolLab(
                    measure = ::measure,
                    provision = panel?.let { p -> { family: LabFamily -> generator.generateInbound(p, family.serverProtocol!!, family.serverSecurity!!) } }
                )
                val progress: (ProtocolLab.Progress) -> Unit = { p ->
                    _state.update { it.copy(phase = LabPhase.Running(p.step, p.done, p.total)) }
                }
                val results = withContext(Dispatchers.IO) {
                    if (panel != null) {
                        lab.runOnServer(LabFamily.serverFamilies.filter { it in s.families }, priority = s.priority, onProgress = progress)
                    } else {
                        val saved = serverRepository.getAllProfilesOnce()
                        require(saved.isNotEmpty()) { "Add a server or some configs first, then run the test." }
                        lab.runOnSaved(saved, priority = s.priority, onProgress = progress)
                    }
                }
                if (panel != null) pendingTestConfigs = results.filter { it.generated != null }.map { panel to it }
                XrayLogManager.i("PROTOCOLS", "Test finished: " + results.joinToString { "${it.family.displayName}=${it.score}" })
                _state.update {
                    it.copy(phase = LabPhase.Done, results = results, serverRun = panel != null, testedAt = System.currentTimeMillis())
                }
            } catch (e: Exception) {
                XrayLogManager.w("PROTOCOLS", "Test failed: ${e.message}")
                _state.update { it.copy(phase = if (it.results.isEmpty()) LabPhase.Idle else LabPhase.Done, error = e.message ?: "The test failed") }
            }
        }
    }

    /**
     * Adds the chosen config (and, with [keepBackups], the next two working families for failover)
     * to the app and selects it. With [cleanUp] every other test config is removed from the server.
     */
    fun applyChoice(keepBackups: Boolean, cleanUp: Boolean, onReady: (VlessProfile) -> Unit) {
        val s = _state.value
        val chosen = s.chosen ?: return
        val profile = chosen.profile ?: return
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            try {
                val keep = listOf(chosen) + if (keepBackups) s.working.filter { it !== chosen }.take(2) else emptyList()
                withContext(Dispatchers.IO) {
                    if (s.serverRun) {
                        keep.mapNotNull { it.profile }.forEach { serverRepository.insert(it) }
                        val leftovers = pendingTestConfigs.filter { (_, r) -> keep.none { it.generated?.inboundId == r.generated?.inboundId } }
                        if (cleanUp) leftovers.forEach { (p, r) -> runCatching { generator.deleteInbound(p, r.generated!!.inboundId) } }
                        pendingTestConfigs = emptyList()
                    }
                    settingsRepository.setSelectedProfileId(profile.id)
                }
                _state.update { it.copy(applying = false) }
                onReady(profile)
            } catch (e: Exception) {
                _state.update { it.copy(applying = false, error = e.message ?: "Could not save the config") }
            }
        }
    }

    private fun measure(profiles: List<VlessProfile>): List<Long?> {
        val outcomes = RealDelayProbe.measure(profiles, PROBE_TIMEOUT_SEC)
        // Nothing measured at all (core busy, library missing) is not the same as "blocked".
        if (outcomes.isNotEmpty() && outcomes.all { it is RealDelayProbe.Outcome.NotRun }) {
            error("The test could not run: ${(outcomes.first() as RealDelayProbe.Outcome.NotRun).reason}")
        }
        return outcomes.map { (it as? RealDelayProbe.Outcome.Delay)?.latencyMs }
    }

    /** Removes test configs from an earlier run the user never kept. */
    private suspend fun discardPending() {
        val pending = pendingTestConfigs
        pendingTestConfigs = emptyList()
        if (pending.isEmpty()) return
        withContext(Dispatchers.IO) {
            pending.forEach { (p, r) -> runCatching { generator.deleteInbound(p, r.generated!!.inboundId) } }
        }
    }

    override fun onCleared() {
        val pending = pendingTestConfigs
        pendingTestConfigs = emptyList()
        if (pending.isNotEmpty()) {
            // The scope is gone; a short-lived thread still frees the server's test ports.
            Thread {
                pending.forEach { (p, r) -> runCatching { generator.deleteInbound(p, r.generated!!.inboundId) } }
            }.start()
        }
        super.onCleared()
    }

    companion object {
        private const val PROBE_TIMEOUT_SEC = 8
    }
}
