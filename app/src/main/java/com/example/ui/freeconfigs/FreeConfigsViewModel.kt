package com.example.ui.freeconfigs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.data.model.VlessProfile
import com.example.vpn.VpnController
import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.hub.ConnectivityMeasurement
import com.example.vpn.hub.FreeConfigList
import com.example.vpn.smart.NetworkCapabilityDetector
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
 * this network when the user asks, one server at a time, and the fastest one to connect to.
 */
class FreeConfigsViewModel(app: Application) : AndroidViewModel(app) {

    private val serverRepository = RayApplication.instance.serverRepository
    private val subscriptionRepository = RayApplication.instance.subscriptionRepository
    private val subscriptionManager = RayApplication.instance.subscriptionManager
    private val evidence = RayApplication.instance.freeConfigEvidence

    private val _state = MutableStateFlow(
        FreeConfigsUiState(
            available = FreeConfigList.available(),
            enabled = RayApplication.instance.settingsRepository.getSettings().freeConfigsEnabled
        )
    )
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
            publish(withContext(Dispatchers.IO) { trimSurplus() })
            loadSubscription()
            val s = _state.value
            // First visit, the list has not arrived yet, or it is due: fetch it. Nothing is tested until the user asks.
            if (s.available && s.enabled && (s.total == 0 || s.lastUpdated == 0L || System.currentTimeMillis() >= s.nextUpdate)) refresh()
        }
    }

    /**
     * Installs that took an earlier, longer list still hold hundreds of servers. Keep the best
     * [FreeConfigList.MAX_CONFIGS] (never favourites or the server in use) so the screen, the test run
     * and the database work on a small set. Returns what is left.
     */
    private suspend fun trimSurplus(): List<VlessProfile> {
        val saved = serverRepository.getProfilesBySubscription(FreeConfigList.URL).first()
        if (saved.size <= FreeConfigList.MAX_CONFIGS) return saved
        val keep = runCatching { RayApplication.instance.settingsRepository.getSettings().selectedProfileId }.getOrNull()
        val extra = FreeConfigList.surplus(saved, keep)
        extra.forEach { runCatching { serverRepository.delete(it.id) } }
        XrayLogManager.i("FREE", "Trimmed ${extra.size} servers beyond the ${FreeConfigList.MAX_CONFIGS} the free list keeps.")
        return saved - extra.toSet()
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
            // Before a test here, the last saved result stands in (it may come from another network).
            val ms = if (p.id in tested) latency[p.id] else p.lastLatencyMs
            FreeNode(
                profile = p,
                country = p.countryCode ?: FreeConfigList.countryOf(p.name),
                latencyMs = ms,
                health = when {
                    p.id !in tested -> if (ms != null) FreeNode.healthOf(ms) else NodeHealth.QUEUED
                    ms == null -> NodeHealth.OFFLINE
                    else -> FreeNode.healthOf(ms)
                },
                sites = FreeConfigList.sitesOf(p.name),
                lifecycle = evidence.lifecycle(p.effectiveFingerprint),
                passes = passes[p.id] ?: 0,
                runs = runs[p.id] ?: 0
            )
        }
        _state.update { it.copy(nodes = nodes) }
    }

    /** Fetches the list again (adding the subscription if this install never had it). */
    fun refresh() {
        if (!_state.value.available || !_state.value.enabled || _state.value.syncing) return
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
        }
    }

    /**
     * Sends a real request through every server, one server at a time so the phone stays responsive;
     * each row updates as its test finishes. [stopTest] ends the run.
     */
    fun testAll() = test(_state.value.nodes.map { it.profile })

    /** Tests one server. */
    fun testOne(node: FreeNode) = test(listOf(node.profile))

    private fun test(profiles: List<VlessProfile>) {
        if (_state.value.testing || profiles.isEmpty()) return
        if (VpnController.connectionState.value.let { it.isTunnelUp || it.isBusy }) {
            _state.update { it.copy(message = "Disconnect first to test servers on your own network.") }
            return
        }
        _state.update { it.copy(testing = true, testDone = 0, testTotal = profiles.size, message = null) }
        testJob = viewModelScope.launch {
            try {
                // What this network allows, so each result is filed under an anonymous network bucket.
                val network = NetworkCapabilityDetector.last?.takeIf { System.currentTimeMillis() - it.measuredAt < NETWORK_PROFILE_MAX_AGE_MS }
                    ?: runCatching { NetworkCapabilityDetector.detect(getApplication()) }.getOrNull()
                for ((i, p) in profiles.withIndex()) {
                    val outcome = withContext(Dispatchers.IO) { RealDelayProbe.measure(p, TIMEOUT_SEC) }
                    if (outcome !is RealDelayProbe.Outcome.NotRun) {
                        val ms = (outcome as? RealDelayProbe.Outcome.Delay)?.latencyMs
                        withContext(Dispatchers.IO) {
                            runCatching {
                                evidence.record(p.effectiveFingerprint, ConnectivityMeasurement(
                                    timestamp = System.currentTimeMillis(),
                                    kind = ConnectivityMeasurement.Kind.TEST,
                                    success = ms != null,
                                    networkKey = network?.key(),
                                    rttMs = ms,
                                    failureStage = (outcome as? RealDelayProbe.Outcome.Failed)?.let { FailureStage.fromText(it.reason) }
                                ))
                            }
                        }
                        latency[p.id] = ms
                        tested += p.id
                        runs[p.id] = (runs[p.id] ?: 0) + 1
                        if (ms != null) passes[p.id] = (passes[p.id] ?: 0) + 1
                        withContext(Dispatchers.IO) { runCatching { serverRepository.updateLatency(p.id, ms) } }
                    }
                    _state.update { it.copy(testDone = i + 1) }
                    publish(_state.value.nodes.map { it.profile })
                }
            } finally {
                _state.update { it.copy(testing = false) }
            }
        }
    }

    /** Stops a test run; servers already tested keep their result. */
    fun stopTest() {
        testJob?.cancel()
    }

    fun setSort(sort: FreeSort) = _state.update { it.copy(sort = sort) }

    fun setProtocol(protocol: String?) = _state.update { it.copy(protocol = protocol) }

    fun setSite(site: String?) = _state.update { it.copy(site = site) }

    fun toggleHidden() = _state.update { it.copy(showHidden = !it.showHidden) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        testJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TIMEOUT_SEC = 6
        private const val NETWORK_PROFILE_MAX_AGE_MS = 5 * 60_000L
    }
}
