package com.example.vpn.lab

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import com.example.data.model.ConnectionStatus
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.RayVpnService
import com.example.vpn.connectivity.DerivedRecoveryCandidate
import com.example.vpn.connectivity.RecoveryLedger
import com.example.vpn.connectivity.RecoveryProfiles
import com.example.vpn.connectivity.RecoverySecurityGate
import com.example.vpn.connectivity.BpbRecoveryEngine
import com.example.vpn.connectivity.EndpointScoringEngine
import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.smart.NetworkCapabilityDetector
import com.example.vpn.smart.NetworkCapabilityProfile
import com.example.vpn.smart.NetworkKey
import com.example.xray.RealDelayProbe
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone side of the LAB: watches network sessions, runs bounded experiments with the bundled core's real
 * requests, and keeps the store. Nothing here changes a saved config, the kill switch, DNS or the running VPN.
 *
 * Experiments need the VPN off (the test core cannot run beside the VPN's). At AUTO_APPLY, a VERIFIED copy made
 * from a reviewed recovery profile is recorded in the recovery ledger, which the VPN already tries first on that
 * network with one real request and rolls back after repeated failures. Other copies are recommendations only.
 */
class LabController(
    private val context: Context,
    val store: LabStore,
    private val ledger: RecoveryLedger,
    private val endpoints: EndpointScoringEngine,
    private val loadProfile: suspend (String) -> VlessProfile?,
    private val budget: LabBudget = LabBudget.DEFAULT,
    private val generator: CandidateGenerator = CandidateGenerator(),
    private val policy: CandidatePromotionPolicy = CandidatePromotionPolicy.DEFAULT
) {
    data class Snapshot(
        val network: NetworkContext? = null,
        val capability: NetworkCapabilityProfile? = null,
        val automation: AutomationLevel = AutomationLevel.RECOMMEND,
        val running: LabExperiment? = null,
        val experiments: List<LabExperiment> = emptyList(),
        val verified: List<VerifiedNetworkProfile> = emptyList(),
        val discoveries: List<LabDiscovery> = emptyList(),
        val networks: List<LabStore.NetworkSeen> = emptyList(),
        val plan: LabStore.ReturnPlan? = null,
        val message: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracker = NetworkSessionTracker()
    private val recentStarts = ArrayDeque<Long>()
    private val lastStartForConfig = mutableMapOf<String, Long>()
    private var job: Job? = null
    private var refreshJob: Job? = null

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun start() {
        publish()
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleRefresh()
                override fun onLost(network: Network) {
                    tracker.lost()
                    scheduleRefresh()
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleRefresh()
            })
        }.onFailure { XrayLogManager.w("LAB", "Network watch unavailable: ${it.message}") }
        scheduleRefresh()
    }

    /** Network callbacks come in bursts; one measurement a few seconds after the last one. */
    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(3_000)
            refreshNetwork(userStarted = false)
        }
    }

    /** Measures the phone's own network and starts a new session when it changed. */
    suspend fun refreshNetwork(userStarted: Boolean = true) {
        val cap = runCatching { NetworkCapabilityDetector.detect(context) }.getOrNull()
        if (cap == null) {
            tracker.lost()
            publish(message = "No network outside the VPN right now.")
            return
        }
        val key = NetworkKey.current(context)
        val families = NetworkSessionTracker.families(cap.ipv4Available, cap.ipv6Available)
        val (ctx, isNew) = tracker.observe(key, families, labelOf(key))
        val measured = cap.observations().count { !it.endsWith("not measured") }
        val working = listOf(cap.dnsWorking, cap.tcpAvailable, cap.tlsAvailable, cap.cloudflareReachable).count { it == true }
        store.recordNetwork(ctx, if (measured == 0) null else working * 25, cap.observations().filter { !it.endsWith("not measured") }.joinToString(" · "))
        val plan = store.planFor(ctx.contextKey, policy)
        if (isNew) {
            XrayLogManager.i("LAB", "Network session ${ctx.sessionId} on ${NetworkKey.describe(key)} ($families).")
            if (!plan.explore && store.automation().mayExperiment) scope.launch { revalidate(ctx, plan, userStarted = false) }
        }
        publish(network = ctx, capability = cap, plan = plan)
    }

    fun setAutomation(level: AutomationLevel) {
        store.setAutomation(level)
        publish()
    }

    fun setDisabled(profileId: String, disabled: Boolean) { store.setDisabled(profileId, disabled); publish() }
    fun retire(profileId: String) { store.retire(profileId); publish() }

    fun cancel() {
        job?.cancel()
    }

    /** Runs one experiment for a saved config: a baseline real request, then safe copies if the baseline failed. */
    fun experiment(profileId: String, userStarted: Boolean = true) {
        if (job?.isActive == true) { publish(message = "An experiment is already running."); return }
        job = scope.launch { runCatching { runExperiment(profileId, userStarted) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) publish(message = "LAB stopped: ${it.message}") } }
    }

    private suspend fun runExperiment(profileId: String, userStarted: Boolean) {
        if (tracker.current == null) refreshNetwork(userStarted)
        val ctx = tracker.current ?: return publish(message = "No network to experiment on.")
        if (!userStarted && !store.automation().mayExperiment) return
        val parent = loadProfile(profileId) ?: return publish(message = "That config no longer exists.")
        val now = System.currentTimeMillis()
        refusal(userStarted, parent.effectiveFingerprint, now)?.let { return publish(message = it) }
        recentStarts.addLast(now)
        lastStartForConfig[parent.effectiveFingerprint] = now

        val cap = NetworkCapabilityDetector.last
        publish(message = "Measuring ${parent.name} as it is...")
        val baseline = withContext(Dispatchers.IO) { RealDelayProbe.measure(parent, budget.probeTimeoutSec) }
        val category = when (baseline) {
            is RealDelayProbe.Outcome.NotRun -> return publish(message = "LAB could not test (${baseline.reason}). Nothing was changed.")
            is RealDelayProbe.Outcome.Delay -> return publish(message = "${parent.name} already works here (${baseline.latencyMs} ms); nothing to test.")
            is RealDelayProbe.Outcome.Failed -> categoryOf(baseline.reason, parent, cap)
        }
        val id = store.newExperimentId()
        val builds = generator.generate(
            id, parent, category, cap,
            endpoints = endpoints.validated(ctx.networkKey),
            retired = store.retiredMutations(ctx.contextKey, parent.effectiveFingerprint),
            max = budget.maxCandidates
        )
        val created = LabExperiment(
            id, ctx.sessionId, ctx.contextKey, ctx.label, parent.id, parent.effectiveFingerprint, category,
            ExperimentState.CREATED, now, candidates = builds.map { it.candidate },
            note = FailureClassifier.assess(category).text
        )
        store.saveExperiment(created)
        val copies = builds.mapNotNull { b -> b.profile?.let { b.candidate.candidateId to it } }.toMap()
        val result = ExperimentEngine(budget, policy).run(created, copies, ::test, { tracker.isCurrent(ctx.sessionId) }) { e ->
            store.saveExperiment(e)
            publish(running = e.takeIf { !it.state.terminal })
        }
        finish(result, parent, ctx)
    }

    /** Network Memory: a returning network rechecks what worked there once before anything broader runs. */
    private suspend fun revalidate(ctx: NetworkContext, plan: LabStore.ReturnPlan, userStarted: Boolean) {
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        val first = plan.revalidate.firstOrNull() ?: return
        if (refusal(userStarted, first.parentFingerprint, now) != null) return
        // One original config per recheck, so results and any AUTO_APPLY stay with the right config.
        val parents = plan.revalidate.filter { it.parentFingerprint == first.parentFingerprint }
            .mapNotNull { v -> loadProfile(v.parentProfileId)?.let { v to it } }
        if (parents.isEmpty()) return
        recentStarts.addLast(now)
        val id = store.newExperimentId()
        val built = parents.mapNotNull { (v, p) ->
            val copy = generator.rebuild(p, v.mutationProfileId, v.endpoint) ?: return@mapNotNull null
            LabCandidate(
                CandidateGenerator.idOf(id, v.mutationProfileId, v.endpoint), id, p.id, p.effectiveFingerprint, v.mutationProfileId,
                v.endpoint, BpbRecoveryEngine.diff(p, copy).map { if (it.field == "address") it.copy(from = "original") else it },
                now, true, null, PromotionState.EXPERIMENTAL
            ) to copy
        }
        if (built.isEmpty()) return
        val e = LabExperiment(id, ctx.sessionId, ctx.contextKey, ctx.label, first.parentProfileId, first.parentFingerprint, null,
            ExperimentState.CREATED, now, candidates = built.map { it.first }, note = "Recheck on a returning network.")
        job = scope.launch {
            val result = ExperimentEngine(budget.copy(rounds = 1), policy).run(e, built.associate { it.first.candidateId to it.second }, ::test,
                { tracker.isCurrent(ctx.sessionId) }) { store.saveExperiment(it); publish(running = it.takeIf { x -> !x.state.terminal }) }
            parents.firstOrNull { it.second.id == result.parentProfileId }?.second?.let { finish(result, it, ctx) }
        }
    }

    private suspend fun test(profiles: List<VlessProfile>): List<TestOutcome> = withContext(Dispatchers.IO) {
        RealDelayProbe.measure(profiles, budget.probeTimeoutSec).map { o ->
            when (o) {
                is RealDelayProbe.Outcome.Delay -> TestOutcome.passed(o.latencyMs)
                is RealDelayProbe.Outcome.Failed -> TestOutcome.failed(categoryOf(o.reason, null, NetworkCapabilityDetector.last))
                is RealDelayProbe.Outcome.NotRun -> TestOutcome.notRun()
            }
        }
    }

    private fun finish(result: LabExperiment, parent: VlessProfile, ctx: NetworkContext) {
        store.saveExperiment(result)
        val changed = store.absorb(result, policy)
        val now = System.currentTimeMillis()
        changed.filter { it.state == PromotionState.VERIFIED }.forEach { v ->
            store.addDiscovery(LabDiscovery("verified-${v.profileId}", LabDiscovery.Kind.PROFILE_VERIFIED,
                "${v.strategy} works on ${v.networkLabel}", "${parent.name}: ${v.stats.successes}/${v.stats.attempts} real requests passed" +
                    (v.stats.medianLatencyMs?.let { ", median $it ms" } ?: "") + ".", "Measured on this phone", v.confidence, true, now))
            if (store.automation() == AutomationLevel.AUTO_APPLY) apply(v, parent, ctx)
        }
        changed.filter { it.state == PromotionState.DEGRADED || it.state == PromotionState.RETIRED }.forEach { v ->
            store.addDiscovery(LabDiscovery("degraded-${v.profileId}", LabDiscovery.Kind.PROFILE_DEGRADED,
                "${v.strategy} stopped working on ${v.networkLabel}", "It is ${v.state.name.lowercase()} and is not used.", "Measured on this phone",
                v.confidence, true, now))
        }
        XrayLogManager.i("LAB", "${result.experimentId} ended ${result.state}: ${result.note}")
        publish(running = null, message = result.note)
    }

    /** AUTO_APPLY: only reviewed recovery profiles, through the ledger the VPN already uses with its rollback. */
    private fun apply(v: VerifiedNetworkProfile, parent: VlessProfile, ctx: NetworkContext) {
        val rp = RecoveryProfiles.BUILT_IN.firstOrNull { it.key == v.mutationProfileId } ?: return
        val now = System.currentTimeMillis()
        if (rp.isExpired(now)) return
        val copy = rp.derive(parent, v.endpoint) ?: return
        val gate = RecoverySecurityGate.check(parent, copy)
        if (!gate.passed || !CandidateMutationPolicy.check(parent, copy).allowed) return
        val candidate = DerivedRecoveryCandidate(
            BpbRecoveryEngine.idOf(parent.effectiveFingerprint, rp.key, v.endpoint), parent.effectiveFingerprint, parent.id, rp.key,
            now, minOf(now + APPLY_TTL_MS, rp.expiresAt), ctx.networkKey, BpbRecoveryEngine.diff(parent, copy), copy, gate, v.endpoint,
            rollbackAfter = rp.rollbackPolicy
        )
        ledger.record(candidate, success = true, now = now)
        XrayLogManager.i("LAB", "${v.profileId} will be tried first for ${parent.name} on ${v.networkLabel}; it rolls back on failure.")
    }

    private fun refusal(userStarted: Boolean, fingerprint: String, now: Long): String? {
        while (recentStarts.isNotEmpty() && now - recentStarts.first() > 3_600_000L) recentStarts.removeFirst()
        return budget.refusal(deviceState(userStarted), recentStarts.toList(), lastStartForConfig[fingerprint], now)
    }

    private fun deviceState(userStarted: Boolean): LabBudget.DeviceState {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.let { b ->
            val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null
        }
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 }
        val metered = runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered }.getOrNull()
        return LabBudget.DeviceState(
            metered = metered, batteryPercent = level, charging = plugged,
            vpnRunning = RayVpnService.vpnState.value.status != ConnectionStatus.DISCONNECTED, userStarted = userStarted
        )
    }

    private fun categoryOf(reason: String, parent: VlessProfile?, cap: NetworkCapabilityProfile?): LabFailureCategory {
        val family = parent?.address?.let { a -> if (RecoverySecurityGate.isIpLiteral(a.removePrefix("[").removeSuffix("]"))) BpbRecoveryEngine.familyOf(a) else null }
        val transport = parent?.transport?.lowercase().orEmpty()
        return FailureClassifier.classify(
            FailureStage.fromText(reason), cap, endpointFamily = family,
            udpTransport = parent?.protocolType in setOf(ProtocolType.HYSTERIA2, ProtocolType.WIREGUARD),
            usesQuic = transport == "quic" || parent?.alpn?.contains("h3") == true
        )
    }

    private fun labelOf(key: String): String = when {
        key == "wifi" -> "Wi-Fi"
        key == "ethernet" -> "Ethernet"
        key.startsWith("cell") -> NetworkKey.describe(key).substringBefore(" (").let { if (it.startsWith("cell")) "Mobile data" else it }
        else -> "Other network"
    }

    private fun publish(
        network: NetworkContext? = _state.value.network,
        capability: NetworkCapabilityProfile? = _state.value.capability,
        running: LabExperiment? = _state.value.running,
        plan: LabStore.ReturnPlan? = _state.value.plan,
        message: String? = _state.value.message
    ) {
        _state.value = Snapshot(
            network = network ?: tracker.current, capability = capability, automation = store.automation(), running = running,
            experiments = store.experiments().sortedByDescending { it.startTime }, verified = store.verifiedProfiles(),
            discoveries = store.discoveries(), networks = store.networks(), plan = plan, message = message
        )
    }

    companion object {
        const val APPLY_TTL_MS = 24L * 60 * 60 * 1000
    }
}
