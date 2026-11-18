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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
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
            val savedFamilyCounts = withContext(Dispatchers.IO) {
                savedProfiles().groupingBy(LabFamily::of).eachCount()
            }
            _state.update { s ->
                s.copy(
                    panels = panels,
                    panel = if (!s.usePanel) null else s.panel?.takeIf { p -> panels.any { it.id == p.id } } ?: panels.firstOrNull(),
                    savedFamilyCounts = savedFamilyCounts,
                    autoFailover = settingsRepository.getSettings().autoFailoverEnabled
                )
            }
        }
    }

    /** Null tests the configs already saved in the app instead of creating new ones on a server. */
    fun selectPanel(panel: ManagedPanel?) = _state.update { current ->
        if ((current.panel?.id == panel?.id && current.usePanel == (panel != null)) || current.phase is LabPhase.Running || current.applying) current
        else current.copy(panel = panel, usePanel = panel != null, phase = LabPhase.Idle, results = emptyList(),
            selected = null, testedAt = 0L, error = "")
    }

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
        if (s.phase is LabPhase.Running || s.applying) return
        if (VpnController.connectionState.value.let { it.isTunnelUp || it.isBusy }) {
            _state.update { it.copy(error = "Disconnect the VPN first. The test needs your direct connection to see what your network blocks.") }
            return
        }
        _state.update { it.copy(phase = LabPhase.Running("Getting ready", 0, 1), results = emptyList(), error = "", selected = null) }
        viewModelScope.launch {
            try {
                discardPending()
                val panel = s.panel
                val lab = ProtocolLab(
                    measure = ::measure,
                    provision = panel?.let { p -> { family: LabFamily -> generator.generateInbound(p, family.serverProtocol!!, family.serverSecurity!!) } },
                    discard = panel?.let { p -> { config -> generator.deleteInbound(p, config.inboundId) } }
                )
                val results = withContext(Dispatchers.IO) {
                    val progress: (ProtocolLab.Progress) -> Unit = { p ->
                        ensureActive()
                        _state.update { it.copy(phase = LabPhase.Running(p.step, p.done, p.total)) }
                    }
                    if (panel != null) {
                        lab.runOnServer(LabFamily.serverFamilies.filter { it in s.families }, priority = s.priority, onProgress = progress)
                            .also { results -> pendingTestConfigs = results.filter { it.generated != null }.map { panel to it } }
                    } else {
                        val saved = savedProfiles()
                        require(saved.isNotEmpty()) { "Add a server or some configs first, then run the test." }
                        lab.runOnSaved(saved, priority = s.priority, onProgress = progress)
                    }
                }
                XrayLogManager.i("PROTOCOLS", "Test finished: " + results.joinToString { "${it.family.displayName}=${it.score}" })
                _state.update {
                    val ranked = if (it.priority == s.priority) results else ProtocolLab.rank(results.map { result ->
                        result.copy(score = ProtocolLab.score(result, it.priority))
                    })
                    it.copy(phase = LabPhase.Done, results = ranked, serverRun = panel != null, testedAt = System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { discardPending() }
                throw e
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
        if (s.phase != LabPhase.Done || s.applying) return
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

    private suspend fun savedProfiles(): List<VlessProfile> =
        serverRepository.getAllProfilesOnce().filterNot { com.example.vpn.sidecar.Sidecars.isEngineProfile(it) }

    private fun measure(profiles: List<VlessProfile>): List<RealDelayProbe.Outcome> =
        RealDelayProbe.measure(profiles, PROBE_TIMEOUT_SEC)

    @Synchronized
    private fun takePending(): List<Pair<ManagedPanel, LabResult>> =
        pendingTestConfigs.also { pendingTestConfigs = emptyList() }

    /** Removes test configs from an earlier run the user never kept. */
    private suspend fun discardPending() {
        val pending = takePending()
        if (pending.isEmpty()) return
        withContext(Dispatchers.IO) {
            pending.forEach { (p, r) -> runCatching { generator.deleteInbound(p, r.generated!!.inboundId) } }
        }
    }

    override fun onCleared() {
        val pending = takePending()
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
