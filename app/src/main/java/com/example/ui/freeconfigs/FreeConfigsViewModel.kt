package com.example.ui.freeconfigs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.data.model.VlessProfile
import com.example.vpn.VpnController
import com.example.vpn.hub.FreeConfigList
import com.example.xray.RealDelayProbe
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The signed free list (the "MAXIMUS Free" subscription): its servers, tested with a real request on
 * this network, and the fastest one to connect to.
 */
class FreeConfigsViewModel(app: Application) : AndroidViewModel(app) {

    private val serverRepository = RayApplication.instance.serverRepository
    private val subscriptionRepository = RayApplication.instance.subscriptionRepository
    private val subscriptionManager = RayApplication.instance.subscriptionManager

    private val _state = MutableStateFlow(FreeConfigsUiState(available = FreeConfigList.available()))
    val state: StateFlow<FreeConfigsUiState> = _state.asStateFlow()

    /** Test results on this network since the screen opened, by profile id. */
    private val latency = mutableMapOf<String, Long?>()
    private val tested = mutableSetOf<String>()
    private val passes = mutableMapOf<String, Int>()
    private val runs = mutableMapOf<String, Int>()
    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            serverRepository.getProfilesBySubscription(FreeConfigList.URL).collect { profiles -> publish(profiles) }
        }
        viewModelScope.launch {
            publish(withContext(Dispatchers.IO) { serverRepository.getProfilesBySubscription(FreeConfigList.URL).first() })
            loadSubscription()
            val s = _state.value
            when {
                !s.available -> Unit
                // First visit, the list has not arrived yet, or it is due: fetch it, then test it.
                s.total == 0 || s.lastUpdated == 0L || System.currentTimeMillis() >= s.nextUpdate -> refresh()
                else -> testAll()
            }
        }
    }

    private suspend fun loadSubscription() {
        val sub = withContext(Dispatchers.IO) { subscriptionRepository.getSubscriptionByUrl(FreeConfigList.URL) }
        _state.update {
            it.copy(
                loading = false,
                subscribed = sub != null,
                verified = sub != null && sub.lastUpdated > 0 && sub.lastError == null,
                syncError = sub?.lastError,
                lastUpdated = sub?.lastUpdated ?: 0L,
                refreshIntervalMinutes = sub?.refreshIntervalMinutes ?: 360
            )
        }
    }

    private fun publish(profiles: List<VlessProfile>) {
        val nodes = profiles.map { p ->
            val ms = latency[p.id]
            FreeNode(
                profile = p,
                country = p.countryCode ?: FreeConfigList.countryOf(p.name),
                latencyMs = ms,
                health = when {
                    p.id !in tested -> NodeHealth.QUEUED
                    ms == null -> NodeHealth.OFFLINE
                    else -> FreeNode.healthOf(ms)
                },
                passes = passes[p.id] ?: 0,
                runs = runs[p.id] ?: 0
            )
        }
        _state.update { it.copy(nodes = nodes) }
    }

    /** Fetches the list again (adding the subscription if this install never had it), then tests it. */
    fun refresh() {
        if (!_state.value.available || _state.value.syncing) return
        _state.update { it.copy(syncing = true, message = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val sub = subscriptionRepository.getSubscriptionByUrl(FreeConfigList.URL)
                    if (sub != null) subscriptionManager.syncSubscription(sub)
                    else subscriptionManager.addAndSyncSubscription(FreeConfigList.NAME, FreeConfigList.URL)
                }
            }
            loadSubscription()
            result.exceptionOrNull()?.let { e ->
                XrayLogManager.w("FREE", "Free list refresh failed: ${e.message}")
                _state.update { it.copy(message = "Could not update the list. The last verified list stays in use.") }
            }
            _state.update { it.copy(syncing = false) }
            testAll()
        }
    }

    /** Sends a real request through every server, fastest-first results appear as they come in. */
    fun testAll() {
        if (_state.value.testing) return
        if (VpnController.connectionState.value.let { it.isConnected || it.isBusy }) {
            _state.update { it.copy(message = "Disconnect first to test servers on your own network.") }
            return
        }
        val profiles = _state.value.nodes.map { it.profile }
        if (profiles.isEmpty()) return
        _state.update { it.copy(testing = true, message = null) }
        testJob = viewModelScope.launch {
            try {
                for (batch in profiles.chunked(BATCH)) {
                    val outcomes = withContext(Dispatchers.IO) { RealDelayProbe.measure(batch, TIMEOUT_SEC) }
                    batch.zip(outcomes).forEach { (p, outcome) ->
                        if (outcome is RealDelayProbe.Outcome.NotRun) return@forEach
                        val ms = (outcome as? RealDelayProbe.Outcome.Delay)?.latencyMs
                        latency[p.id] = ms
                        tested += p.id
                        runs[p.id] = (runs[p.id] ?: 0) + 1
                        if (ms != null) passes[p.id] = (passes[p.id] ?: 0) + 1
                        withContext(Dispatchers.IO) { runCatching { serverRepository.updateLatency(p.id, ms) } }
                    }
                    publish(_state.value.nodes.map { it.profile })
                }
            } finally {
                _state.update { it.copy(testing = false) }
            }
        }
    }

    fun setSort(sort: FreeSort) = _state.update { it.copy(sort = sort) }

    fun setProtocol(protocol: String?) = _state.update { it.copy(protocol = protocol) }

    fun toggleHidden() = _state.update { it.copy(showHidden = !it.showHidden) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        testJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val BATCH = 24
        private const val TIMEOUT_SEC = 6
    }
}
