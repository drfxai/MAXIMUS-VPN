package com.example.ui.panels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.panels.cloudflare.relay.CloudflareRelay
import com.example.panels.cloudflare.relay.CloudflareRelay.Event
import com.example.panels.cloudflare.relay.CloudflareRelay.Phase
import com.example.panels.cloudflare.relay.CloudflareRelayApi
import com.example.panels.cloudflare.relay.CloudflareRelayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Panels → Cloudflare → Cloudflare relay shows. The key itself is never part of the state. */
data class RelayUi(
    val state: CloudflareRelay.State = CloudflareRelay.State(),
    val relay: CloudflareRelay.Relay? = null,
    val quota: CloudflareRelay.Quota? = null,
    val progress: String? = null,
    val withDomain: Boolean = false,
    val zones: List<CloudflareRelayApi.Zone> = emptyList(),
    val reachable: Boolean? = null,
    val bestEdgeIp: String? = null
)

/** Build 181 Phase 7: the "Connect Cloudflare account" flow (CloudflareRelay.reduce is the state machine). */
class CloudflareRelayViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = CloudflareRelayManager(app)
    private val prefs = app.getSharedPreferences("maximus_cf_relay", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(RelayUi())
    /** The last key entered, in memory only, for "Try again"; never in the UI state, never logged. */
    @Volatile private var lastKey: String? = null
    val ui: StateFlow<RelayUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val relay = withContext(Dispatchers.IO) { manager.current() }
            if (relay != null) {
                _ui.update { it.copy(state = CloudflareRelay.reduce(it.state, Event.Restore), relay = relay,
                    quota = com.example.vpn.connections.CloudflareRelayRuntime.quota) }
                refreshQuota()
            } else {
                // Coming back from the browser after the process was stopped: resume at the step the user reached.
                val saved = runCatching { Phase.valueOf(prefs.getString(PHASE_KEY, Phase.START.name)!!) }.getOrDefault(Phase.START)
                val phase = if (saved == Phase.OPENED || saved == Phase.KEY_PAGE) saved else Phase.START
                _ui.update { it.copy(state = CloudflareRelay.State(phase), withDomain = prefs.getBoolean(DOMAIN_KEY, false)) }
            }
            refreshEdge()
        }
    }

    private fun send(e: Event) {
        _ui.update { it.copy(state = CloudflareRelay.reduce(it.state, e)) }
        val phase = _ui.value.state.phase
        prefs.edit().putString(PHASE_KEY, if (phase == Phase.OPENED || phase == Phase.KEY_PAGE) phase.name else Phase.START.name).apply()
    }

    fun openedCloudflare() = send(Event.OpenedCloudflare)

    fun setWithDomain(on: Boolean) {
        prefs.edit().putBoolean(DOMAIN_KEY, on).apply()
        _ui.update { it.copy(withDomain = on) }
    }

    /** The template link for step 2 (it also moves the card to step 3). */
    fun keyPageUrl(): String {
        send(Event.OpenedKeyPage)
        return CloudflareRelay.tokenUrl(_ui.value.withDomain)
    }

    fun submitKey(token: String) {
        val s = _ui.value.state.phase
        if (s == Phase.VERIFYING || s == Phase.DEPLOYING || s == Phase.ACTIVE) return
        send(Event.KeyEntered)
        lastKey = token.trim()
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                manager.connect(token.trim()) { line -> _ui.update { it.copy(progress = line) } }
            }
            when (outcome) {
                is CloudflareRelayManager.Outcome.Active -> {
                    send(Event.Checked(CloudflareRelay.Checklist(true, true, true)))
                    send(Event.Deployed)
                    lastKey = null
                    _ui.update { it.copy(relay = outcome.relay, reachable = outcome.reachable, progress = null) }
                    if (_ui.value.withDomain) loadZones()
                    refreshQuota()
                }
                is CloudflareRelayManager.Outcome.Failed -> {
                    send(Event.Failed(outcome.checklist, outcome.message))
                    _ui.update { it.copy(progress = null) }
                }
            }
        }
    }

    /** Try again: the same key once more (its permissions may have been edited in Cloudflare), else back to step 3. */
    fun retry() {
        val key = lastKey
        if (key != null) submitKey(key) else send(Event.OpenedKeyPage)
    }

    override fun onCleared() {
        lastKey = null
        super.onCleared()
    }

    fun rotate() {
        send(Event.RotateStarted)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { manager.rotate { line -> _ui.update { it.copy(progress = line) } } } }
            result.onSuccess { r -> send(Event.RotateDone); _ui.update { it.copy(relay = r, progress = null, reachable = null) } }
                .onFailure { e -> send(Event.ActionFailed(e.message ?: "Rotate failed")); _ui.update { it.copy(progress = null) } }
        }
    }

    fun disconnect() {
        send(Event.RemoveStarted)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { manager.disconnect { line -> _ui.update { it.copy(progress = line) } } } }
            result.onSuccess {
                send(Event.Removed)
                _ui.update { RelayUi(state = it.state) }
            }.onFailure { e ->
                send(Event.ActionFailed((e.message ?: "Cloudflare did not answer") +
                    ". The relay is still there; try again, or delete it in the Cloudflare dashboard."))
                _ui.update { it.copy(progress = null) }
            }
        }
    }

    fun setSmartUse(on: Boolean) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { manager.setSmartUse(on) }
            if (r != null) _ui.update { it.copy(relay = r) }
        }
    }

    fun loadZones() {
        viewModelScope.launch {
            val zones = withContext(Dispatchers.IO) { runCatching { manager.zones() }.getOrDefault(emptyList()) }
            _ui.update { it.copy(zones = zones) }
        }
    }

    fun useDomain(zone: CloudflareRelayApi.Zone) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { manager.useDomain(zone) { line -> _ui.update { it.copy(progress = line) } } } }
            result.onSuccess { r -> _ui.update { it.copy(relay = r, progress = null) } }
                .onFailure { e -> _ui.update { it.copy(progress = null, state = it.state.copy(message = e.message)) } }
        }
    }

    fun refreshQuota() {
        viewModelScope.launch {
            val q = withContext(Dispatchers.IO) { runCatching { manager.refreshQuota() }.getOrNull() }
            if (q != null) _ui.update { it.copy(quota = q) }
        }
    }

    private fun refreshEdge() {
        viewModelScope.launch {
            val ip = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.vpn.connections.CloudflareRelayRuntime.cleanIp(com.example.vpn.connections.BuiltinMethodMemory.network())
                }.getOrNull()
            }
            _ui.update { it.copy(bestEdgeIp = ip) }
        }
    }

    private companion object {
        const val PHASE_KEY = "flow_phase_v1"
        const val DOMAIN_KEY = "flow_with_domain_v1"
    }
}
