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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracker = NetworkSessionTracker()
    private val recentStarts = ArrayDeque<Long>()
    private val lastStartForConfig = mutableMapOf<String, Long>()
    private var job: Job? = null
    private var refreshJob: Job? = null
    private val steps = mutableListOf<LabStep>()
    private val log = ArrayDeque<String>()

    private val stateTracker = NetworkStateTracker()
    private val _state = MutableStateFlow(LabSnapshot())
    val state: StateFlow<LabSnapshot> = _state.asStateFlow()

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
                // Losing one network (mobile data dropping while Wi-Fi stays, for example) is not losing the
                // phone's network: the refresh measures what is left and starts a new session only when the
                // network really changed, or ends the session when nothing is left.
                override fun onLost(network: Network) = scheduleRefresh()
                // Capability updates arrive every few seconds on Wi-Fi (signal strength, bandwidth). They do
                // not change the network, so they no longer start a measurement each time.
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
            publish(message = "No network outside the VPN right now.", networkState = null)
            return
        }
        val key = NetworkKey.current(context)
        val families = NetworkSessionTracker.families(cap.ipv4Available, cap.ipv6Available)
        val (ctx, isNew) = tracker.observe(key, families, labelOf(key))
        // Health counts only what was measured: an unmeasured check is neither a pass nor a fail.
        val core = listOf(cap.dnsWorking, cap.tcpAvailable, cap.tlsAvailable, cap.cloudflareReachable, cap.internationalReachable).filterNotNull()
        store.recordNetwork(ctx, if (core.isEmpty()) null else core.count { it } * 100 / core.size, cap.observations().filter { !it.endsWith("not measured") }.joinToString(" · "))
        val reading = stateTracker.update(ctx.sessionId, NetworkStateClassifier.classify(cap))
        XrayLogManager.i("LAB", "Network state: ${reading.summary()}.")
        val plan = store.planFor(ctx.contextKey, policy)
        if (isNew) {
            com.example.vpn.diagnostics.ConnectionMetrics.labSessions.incrementAndGet()
            XrayLogManager.i("LAB", "Network session ${ctx.sessionId} on ${NetworkKey.describe(key)} ($families).")
            if (!plan.explore && store.automation().mayExperiment && job?.isActive != true) {
                job = scope.launch { runCatching { revalidate(ctx, plan.revalidate, userStarted = false) } }
            }
        }
        publish(network = ctx, capability = cap, plan = plan, networkState = reading)
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

    fun dismissMessage() = publish(message = null)

    fun showMessage(message: String) = publish(message = message)

    /** Runs one experiment for a saved config: a baseline real request, then safe copies if the baseline failed. */
    fun experiment(profileId: String, userStarted: Boolean = true) = launchJob { runExperiment(profileId, userStarted) }

    /** "Retest" on a Verified Profile: one recheck round of that profile on the current network. */
    fun retest(profileId: String) = launchJob {
        val v = store.verifiedProfiles().firstOrNull { it.profileId == profileId } ?: return@launchJob
        if (tracker.current == null) refreshNetwork()
        val ctx = tracker.current ?: return@launchJob publish(message = "No network to test on.")
        if (ctx.contextKey != v.contextKey) return@launchJob publish(message = "${v.profileId} belongs to ${v.networkLabel}; the phone is on ${ctx.label} now.")
        revalidate(ctx, listOf(v), userStarted = true)
    }

    private fun launchJob(block: suspend () -> Unit) {
        if (job?.isActive == true) { publish(message = "An experiment is already running."); return }
        job = scope.launch {
            try {
                block()
            } catch (c: kotlinx.coroutines.CancellationException) {
                step(null, LabStep.State.SKIPPED)
                publish(running = null, message = "Experiment cancelled. Nothing was changed.")
                throw c
            } catch (e: Exception) {
                publish(running = null, message = "LAB stopped: ${e.message}")
            }
        }
    }

    /** An AI reading becomes an unverified discovery card; it never changes a measurement or a state. */
    fun recordAdvice(explanation: String, patterns: List<String>, confidence: Double, model: String) {
        if (explanation.isBlank()) return
        val ctx = tracker.current
        store.addDiscovery(LabDiscovery("advice-${ctx?.contextKey ?: "none"}", LabDiscovery.Kind.NETWORK_BEHAVIOR,
            "LAB Agent: " + (patterns.firstOrNull() ?: "reading of ${ctx?.label ?: "this network"}"),
            explanation + " (AI reading, not a measurement.)", "LAB Agent · $model", confidence.coerceIn(0.0, 1.0), false, System.currentTimeMillis()))
        publish()
    }

    /**
     * Tests one change the LAB Agent suggested, only if the user asks. The suggestion goes through the same
     * allowlist and security gate as every candidate; a refused one is reported and never tested.
     */
    fun testSuggestion(profileId: String, field: String, value: String) = launchJob {
        val parent = loadProfile(profileId) ?: return@launchJob publish(message = "That config no longer exists.")
        val (verdict, copy) = CandidateMutationPolicy.checkRequest(parent, mapOf(field to value))
        if (copy == null) return@launchJob publish(message = "LAB refused the suggestion: ${verdict.reason}")
        if (tracker.current == null) refreshNetwork()
        val ctx = tracker.current ?: return@launchJob publish(message = "No network to test on.")
        val now = System.currentTimeMillis()
        refusal(true, parent.effectiveFingerprint, now)?.let { return@launchJob publish(message = it) }
        recentStarts.addLast(now)
        val id = store.newExperimentId()
        val mutation = CandidateGenerator.suggestionId(field, value)
        val cand = LabCandidate(CandidateGenerator.idOf(id, mutation, null), id, parent.id, parent.effectiveFingerprint, mutation, null,
            BpbRecoveryEngine.diff(parent, copy) + if (field == "targetStrategy") listOf(com.example.vpn.connectivity.FieldChange(field, parent.targetStrategy, value)) else emptyList(),
            now, true, null, PromotionState.EXPERIMENTAL)
        steps.clear()
        steps += LabStep("LAB Agent suggestion checked by the security gate", LabStep.State.DONE, "$field passed")
        listOf(STEP_TESTING, STEP_STABILITY, STEP_DONE).forEach { steps += LabStep(it, LabStep.State.PENDING) }
        val e = LabExperiment(id, ctx.sessionId, ctx.contextKey, ctx.label, parent.id, parent.effectiveFingerprint, null, ExperimentState.CREATED, now,
            candidates = listOf(cand), aiRecommendationId = "lab-agent", note = "Testing a LAB Agent suggestion.")
        store.saveExperiment(e)
        run(e, mapOf(cand.candidateId to copy), budget, parent, ctx)
    }

    private suspend fun runExperiment(profileId: String, userStarted: Boolean) {
        if (!userStarted && !store.automation().mayExperiment) return
        val parent = loadProfile(profileId) ?: return publish(message = "That config no longer exists.")
        val now = System.currentTimeMillis()
        refusal(userStarted, parent.effectiveFingerprint, now)?.let { return publish(message = it) }
        recentStarts.addLast(now)
        lastStartForConfig[parent.effectiveFingerprint] = now
        steps.clear()
        LIVE_STEPS.forEach { steps += LabStep(it, LabStep.State.PENDING) }

        step(STEP_BASELINE, LabStep.State.RUNNING)
        refreshNetwork(userStarted)
        val ctx = tracker.current ?: return fail(STEP_BASELINE, "No network outside the VPN.")
        val cap = NetworkCapabilityDetector.last
        step(STEP_BASELINE, LabStep.State.DONE, "${ctx.label} · ${ctx.families}")
        step(STEP_DNS, cap?.dnsWorking.asStep(), measuredWord(cap?.dnsWorking))
        step(STEP_IPV6, cap?.ipv6Available.asStep(), measuredWord(cap?.ipv6Available))
        step(STEP_TLS, cap?.tlsAvailable.asStep(), measuredWord(cap?.tlsAvailable))

        step(STEP_TRANSPORT, LabStep.State.RUNNING, parent.name)
        val baseline = withContext(Dispatchers.IO) { RealDelayProbe.measure(parent, budget.probeTimeoutSec) }
        val category = when (baseline) {
            is RealDelayProbe.Outcome.NotRun -> return fail(STEP_TRANSPORT, "Could not test (${baseline.reason}). Nothing was changed.")
            is RealDelayProbe.Outcome.Delay -> {
                step(STEP_TRANSPORT, LabStep.State.DONE, "Works as it is (${baseline.latencyMs} ms)")
                steps.replaceAll { if (it.state == LabStep.State.PENDING) it.copy(state = LabStep.State.SKIPPED) else it }
                return publish(message = "${parent.name} already works here (${baseline.latencyMs} ms); nothing to test.")
            }
            is RealDelayProbe.Outcome.Failed -> categoryOf(baseline.reason, parent, cap)
        }
        step(STEP_TRANSPORT, LabStep.State.FAILED, FailureClassifier.assess(category).text)

        step(STEP_CANDIDATES, LabStep.State.RUNNING)
        val id = store.newExperimentId()
        val builds = generator.generate(
            id, parent, category, cap,
            endpoints = endpoints.validated(ctx.networkKey),
            retired = store.retiredMutations(ctx.contextKey, parent.effectiveFingerprint),
            max = budget.maxCandidates
        )
        if (builds.isEmpty()) return fail(STEP_CANDIDATES, "No safe change applies to this failure; the original config is left as it is.")
        step(STEP_CANDIDATES, LabStep.State.DONE, "${builds.size} copies")
        val passed = builds.count { it.profile != null }
        step(STEP_SECURITY, if (passed > 0) LabStep.State.DONE else LabStep.State.FAILED, "$passed of ${builds.size} passed")

        val created = LabExperiment(
            id, ctx.sessionId, ctx.contextKey, ctx.label, parent.id, parent.effectiveFingerprint, category,
            ExperimentState.CREATED, now, candidates = builds.map { it.candidate },
            note = FailureClassifier.assess(category).text
        )
        store.saveExperiment(created)
        val copies = builds.mapNotNull { b -> b.profile?.let { b.candidate.candidateId to it } }.toMap()
        run(created, copies, budget, parent, ctx)
    }

    /** Network Memory: a returning network rechecks what worked there once before anything broader runs. */
    private suspend fun revalidate(ctx: NetworkContext, profiles: List<VerifiedNetworkProfile>, userStarted: Boolean) {
        val now = System.currentTimeMillis()
        val first = profiles.firstOrNull() ?: return
        refusal(userStarted, first.parentFingerprint, now)?.let { if (userStarted) publish(message = it); return }
        // One original config per recheck, so results and any AUTO_APPLY stay with the right config.
        val parent = loadProfile(first.parentProfileId) ?: return
        recentStarts.addLast(now)
        val id = store.newExperimentId()
        val built = profiles.filter { it.parentFingerprint == first.parentFingerprint }.mapNotNull { v ->
            val copy = generator.rebuild(parent, v.mutationProfileId, v.endpoint) ?: return@mapNotNull null
            LabCandidate(
                CandidateGenerator.idOf(id, v.mutationProfileId, v.endpoint), id, parent.id, parent.effectiveFingerprint, v.mutationProfileId,
                v.endpoint, BpbRecoveryEngine.diff(parent, copy).map { if (it.field == "address") it.copy(from = "original") else it },
                now, true, null, PromotionState.EXPERIMENTAL
            ) to copy
        }
        if (built.isEmpty()) return
        steps.clear()
        steps += LabStep(STEP_RECHECK, LabStep.State.DONE, "${ctx.label}: ${built.size} profile(s) that worked here before")
        listOf(STEP_TESTING, STEP_STABILITY, STEP_DONE).forEach { steps += LabStep(it, LabStep.State.PENDING) }
        val e = LabExperiment(id, ctx.sessionId, ctx.contextKey, ctx.label, parent.id, parent.effectiveFingerprint, null,
            ExperimentState.CREATED, now, candidates = built.map { it.first }, note = "Recheck on a returning network.")
        run(e, built.associate { it.first.candidateId to it.second }, budget.copy(rounds = 1), parent, ctx)
    }

    private suspend fun run(e: LabExperiment, copies: Map<String, VlessProfile>, b: LabBudget, parent: VlessProfile, ctx: NetworkContext) {
        var round = 0
        val result = ExperimentEngine(b, policy).run(e, copies, ::test, { tracker.isCurrent(ctx.sessionId) }) { x ->
            store.saveExperiment(x)
            when (x.state) {
                ExperimentState.TESTING -> {
                    round = x.measurements.map { it.stage }.distinct().size + 1
                    step(STEP_TESTING, LabStep.State.RUNNING, "Round ${round.coerceAtMost(b.rounds)} of ${b.rounds} · ${x.candidates.count { it.securityPassed }} copies")
                }
                ExperimentState.VERIFYING -> {
                    step(STEP_TESTING, LabStep.State.DONE, "${x.measurements.size} real requests")
                    step(STEP_STABILITY, LabStep.State.RUNNING)
                }
                else -> Unit
            }
            publish(running = x.takeIf { !it.state.terminal })
        }
        val ok = result.state == ExperimentState.VERIFIED || result.state == ExperimentState.CANDIDATE
        if (result.state != ExperimentState.CANCELLED && result.state != ExperimentState.REJECTED) {
            step(STEP_STABILITY, if (ok) LabStep.State.DONE else LabStep.State.FAILED, result.best?.let { "${it.candidateId} · ${(it.stats.successRate * 100).toInt()}%" } ?: "")
        }
        step(STEP_DONE, if (ok) LabStep.State.DONE else LabStep.State.FAILED, result.state.name.lowercase().replaceFirstChar { it.uppercase() })
        steps.replaceAll { if (it.state == LabStep.State.PENDING || it.state == LabStep.State.RUNNING) it.copy(state = LabStep.State.SKIPPED) else it }
        finish(result, parent, ctx)
    }

    private fun Boolean?.asStep() = when (this) { true -> LabStep.State.DONE; false -> LabStep.State.FAILED; null -> LabStep.State.SKIPPED }
    private fun measuredWord(v: Boolean?) = when (v) { true -> "Works"; false -> "Blocked or unavailable"; null -> "Not measured" }

    private fun step(title: String?, state: LabStep.State, detail: String = "") {
        if (title == null) {
            steps.replaceAll { if (it.state == LabStep.State.RUNNING || it.state == LabStep.State.PENDING) it.copy(state = state) else it }
        } else {
            val i = steps.indexOfFirst { it.title == title }
            val s = LabStep(title, state, detail)
            if (i >= 0) steps[i] = s else steps += s
            if (state != LabStep.State.PENDING) log("$title: ${state.name.lowercase()}${if (detail.isNotBlank()) " · $detail" else ""}")
        }
        publish()
    }

    private fun fail(title: String, why: String) {
        step(title, LabStep.State.FAILED, why)
        steps.replaceAll { if (it.state == LabStep.State.PENDING) it.copy(state = LabStep.State.SKIPPED) else it }
        publish(running = null, message = why)
    }

    private fun log(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        log.addLast("$t $line")
        while (log.size > 80) log.removeFirst()
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

    /**
     * AUTO_APPLY: only reviewed recovery profiles, through the ledger the VPN already uses with its rollback.
     * Every attempt is a [ConfigTransaction]: refused ones are kept with their reason, staged ones are later
     * committed or rolled back from what the ledger saw in real use.
     */
    private fun apply(v: VerifiedNetworkProfile, parent: VlessProfile, ctx: NetworkContext) {
        val rp = RecoveryProfiles.BUILT_IN.firstOrNull { it.key == v.mutationProfileId } ?: return
        val now = System.currentTimeMillis()
        val copy = rp.derive(parent, v.endpoint)
        val fields = copy?.let { BpbRecoveryEngine.diff(parent, it).map { c -> c.field } } ?: emptyList()
        var tx = ConfigOptimizer.propose(store.newTransactionId(), parent.effectiveFingerprint, rp.key, ctx.networkKey, v.endpoint, fields,
            "${v.strategy} verified on ${v.networkLabel}: ${v.stats.successes}/${v.stats.attempts} real requests passed", now)
        val gate = copy?.let { RecoverySecurityGate.check(parent, it) }
        val refusal = when {
            rp.isExpired(now) -> "The recovery profile has expired."
            copy == null -> "The recovery profile does not apply to this config."
            gate?.passed != true -> "Refused by the security gate."
            !CandidateMutationPolicy.check(parent, copy).allowed -> "Refused by the mutation policy."
            else -> null
        }
        tx = ConfigOptimizer.validate(tx, refusal, now)
        store.saveTransaction(tx)
        if (tx.state != ConfigTransaction.State.STAGED || copy == null || gate == null) {
            XrayLogManager.i("LAB", "${tx.id} refused for ${parent.name}: ${tx.note}")
            return
        }
        val candidate = DerivedRecoveryCandidate(
            BpbRecoveryEngine.idOf(parent.effectiveFingerprint, rp.key, v.endpoint), parent.effectiveFingerprint, parent.id, rp.key,
            now, minOf(now + APPLY_TTL_MS, rp.expiresAt), ctx.networkKey, BpbRecoveryEngine.diff(parent, copy), copy, gate, v.endpoint,
            rollbackAfter = rp.rollbackPolicy
        )
        ledger.record(candidate, success = true, now = now)
        XrayLogManager.i("LAB", "${tx.id}: ${v.profileId} will be tried first for ${parent.name} on ${v.networkLabel}; it rolls back on failure.")
    }

    /** Commits, rolls back or expires staged transactions from what the recovery ledger saw in real use. */
    private fun reconcileTransactions() {
        val now = System.currentTimeMillis()
        val entries = ledger.entries()
        store.reconcileTransactions { tx -> ConfigOptimizer.reconcile(tx, entries.firstOrNull { ConfigOptimizer.matches(tx, it) }, now) }
            .forEach { XrayLogManager.i("LAB", "${it.id} ${it.state.name.lowercase().replace('_', ' ')}: ${it.note}") }
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
        message: String? = _state.value.message,
        networkState: NetworkStateReading? = _state.value.networkState
    ) {
        reconcileTransactions()
        _state.value = LabSnapshot(
            network = network ?: tracker.current, capability = capability, automation = store.automation(), running = running, steps = steps.toList(),
            experiments = store.experiments().sortedByDescending { it.startTime }, verified = store.verifiedProfiles(),
            discoveries = store.discoveries(), networks = store.networks(), plan = plan, message = message, log = log.toList(),
            networkState = networkState, transactions = store.transactions()
        )
    }

    companion object {
        const val APPLY_TTL_MS = 24L * 60 * 60 * 1000

        const val STEP_BASELINE = "Measuring network baseline"
        const val STEP_DNS = "Checking DNS"
        const val STEP_IPV6 = "Testing IPv6"
        const val STEP_TLS = "Testing TLS"
        const val STEP_TRANSPORT = "Evaluating the config as it is"
        const val STEP_CANDIDATES = "Creating safe candidates"
        const val STEP_SECURITY = "Security validation"
        const val STEP_TESTING = "Testing candidates"
        const val STEP_STABILITY = "Stability verification"
        const val STEP_DONE = "Experiment completed"
        const val STEP_RECHECK = "Known network: rechecking what worked"
        val LIVE_STEPS = listOf(STEP_BASELINE, STEP_DNS, STEP_IPV6, STEP_TLS, STEP_TRANSPORT, STEP_CANDIDATES, STEP_SECURITY, STEP_TESTING, STEP_STABILITY, STEP_DONE)
    }
}
