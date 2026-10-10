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
import com.example.vpn.sidecar.MihomoSidecar
import com.example.vpn.sidecar.Sidecars
import com.example.vpn.sidecar.TorSidecar
import com.example.BuildConfig
import com.example.xray.RealDelayProbe
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock

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
    private val loadAllProfiles: suspend () -> List<VlessProfile> = { emptyList() },
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
    @Volatile private var analysisRunning = false
    @Volatile private var live: LiveAnalysis? = null

    /**
     * Optional AI checkpoint: given a sanitized brief and the family names on offer, returns family names in
     * the order it would try them. Set by the app when an AI provider is configured; null means no AI.
     */
    @Volatile var familyAdvisor: (suspend (brief: String, families: List<String>) -> List<String>)? = null
    /** Raw failure text of the last Full Analysis test per config, for the failure classifier. */
    private val analysisFailures = mutableMapOf<String, String>()

    private val stateTracker = NetworkStateTracker()
    private val _state = MutableStateFlow(LabSnapshot())
    val state: StateFlow<LabSnapshot> = _state.asStateFlow()

    fun start() {
        // Engine work folders hold generated configs and credentials; none may outlive a crash.
        scope.launch(Dispatchers.IO) { EngineProbe.cleanupAll(context) }
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
        // Identity V2: two Wi-Fi networks get different keys (salted fingerprint, no SSID), so one never
        // inherits the other's history.
        val key = com.example.vpn.smart.NetworkIdentity.current(context)
        val families = NetworkSessionTracker.families(cap.ipv4Available, cap.ipv6Available)
        val (ctx, isNew) = tracker.observe(key, families, labelOf(key))
        // Health counts only what was measured: an unmeasured check is neither a pass nor a fail.
        val core = listOf(cap.dnsWorking, cap.tcpAvailable, cap.tlsAvailable, cap.cloudflareReachable, cap.internationalReachable).filterNotNull()
        store.recordNetwork(ctx, if (core.isEmpty()) null else core.count { it } * 100 / core.size, cap.observations().filter { !it.endsWith("not measured") }.joinToString(" · "))
        val reading = stateTracker.update(ctx.sessionId, NetworkStateClassifier.classify(cap))
        XrayLogManager.i("LAB", "Network state: ${reading.summary()}.")
        observeIntoBrain(key, cap, reading)
        val plan = store.planFor(ctx.contextKey, policy)
        if (isNew) {
            com.example.vpn.diagnostics.ConnectionMetrics.labSessions.incrementAndGet()
            XrayLogManager.i("LAB", "Network session ${ctx.sessionId} on ${NetworkKey.describe(key)} ($families).")
            if (!plan.explore && store.automation().mayExperiment && job?.isActive != true && ExperimentPlanner.automaticRefusal(reading) == null) {
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

    /**
     * START FULL ANALYSIS: one user tap, twelve phases. Fresh measurements decide; network memory only
     * changes what is tried first. Every result is a real request outside the VPN, or NOT_TESTED with why.
     * Saved configs are never changed; derived copies go through the mutation policy and security gate.
     */
    fun fullAnalysis() = launchJob {
        analysisRunning = true
        try { testLock.withLock { runFullAnalysis() } } finally { analysisRunning = false; live = null; publish() }
    }

    private suspend fun runFullAnalysis() {
        val startedAt = System.currentTimeMillis()
        analysisFailures.clear()
        steps.clear()
        live = null
        ANALYSIS_STEPS.forEach { steps += LabStep(it, LabStep.State.PENDING) }

        // OBSERVE. Identify the network and measure it twice: the state machine needs two readings to move.
        step(STEP_A_IDENTIFY, LabStep.State.RUNNING)
        refreshNetwork(userStarted = true)
        val ctx = tracker.current ?: return fail(STEP_A_IDENTIFY, "No network outside the VPN.")
        step(STEP_A_IDENTIFY, LabStep.State.DONE, "${ctx.label} · ${ctx.families} · session ${ctx.sessionId}")
        step(STEP_A_MEASURE, LabStep.State.RUNNING, "second measurement and DNS resolvers")
        refreshNetwork(userStarted = true)
        if (!tracker.isCurrent(ctx.sessionId)) return fail(STEP_A_MEASURE, "The network changed during the analysis. Start it again.")
        val cap = NetworkCapabilityDetector.last
        val resolvers = measureResolvers(ctx)
        val burst = measureBurst(resolvers)
        val intl = cap?.let { c -> c.internationalTried?.let { "international ${c.internationalOk ?: 0}/$it" } } ?: "international not measured"
        step(STEP_A_MEASURE, LabStep.State.DONE, "$intl · ${resolvers.count { it.usable }}/${resolvers.size} resolvers answer honestly")

        // CLASSIFY and DIAGNOSE (observation and assessment kept apart).
        val reading = _state.value.networkState
        step(STEP_A_CLASSIFY, if (reading == null) LabStep.State.FAILED else LabStep.State.DONE,
            reading?.let { "${it.primary.title} · ${(it.confidence * 100).toInt()}%" } ?: "not measured")
        val domains = reading?.restrictions.orEmpty().map { it.title }
        step(STEP_A_DIAGNOSE, LabStep.State.DONE, domains.joinToString(", ").ifBlank { "no restriction measured" })

        // HYPOTHESES. Plan from the state and the device budget; fresh memory and an optional AI checkpoint only reorder.
        val device = deviceState(userStarted = true)
        val plan = ExperimentPlanner.plan(reading, cap, ExperimentPlanner.budget(
            ExperimentPlanner.Budget(true, device.batteryPercent, device.charging, device.metered)))
        val profiles = loadAllProfiles()
        val families = profiles.map { it to familyOf(it) }
        val available = families.filter { !it.first.allowInsecure }.map { it.second }.toSet()
        val now0 = System.currentTimeMillis()
        val memory = LabEvidence.priors(store.evidenceFor(ctx.contextKey), ctx.contextKey, now0, BuildConfig.VERSION_CODE)
        val lowBattery = device.charging != true && (device.batteryPercent ?: 100) < 20
        // Paths that failed a real request on this network moments ago are not retested (ConnectivityBrain).
        val book = com.example.vpn.connectivity.ConnectivityBrain.book
        val recentlyFailed = profiles.mapNotNull { p ->
            if (book.eligibility(com.example.vpn.connectivity.PathRef.of(p)) == com.example.vpn.connectivity.PathEligibility.RECENTLY_FAILED)
                p.id to "failed a real request on this network moments ago" else null
        }.toMap()
        val planner = AdaptivePlanner(plan, cap, AdaptivePlanner.Priors(memory = memory, recentlyFailed = recentlyFailed), AdaptivePlanner.Device(device.metered, lowBattery))
        val requestsAtStart = RealDelayProbe.requestsSent.get()
        val bytesAtStart = appBytes()
        var engineStarts = 0
        val aiFirst = aiCheckpoint("after classification", reading, plan, available, planner)
        step(STEP_A_PLAN, LabStep.State.DONE, "${plan.mode.title} · up to ${plan.budget} tests" +
            (if (memory.isNotEmpty()) " · fresh memory for ${memory.size} famil${if (memory.size == 1) "y" else "ies"}" else "") +
            (if (aiFirst.isNotEmpty()) " · AI checkpoint: ${aiFirst.joinToString { it.title }} first (reorder only)" else ""))

        val notTested = mutableMapOf<PathFamily, String>()
        val rejected = mutableListOf<RankedMethod>()
        val engineResults = mutableMapOf<String, EngineProbe.Result>()
        val vpnOn = device.vpnRunning
        val proven = store.verifiedProfiles().filter { it.contextKey == ctx.contextKey && it.state == PromotionState.VERIFIED }.map { it.parentFingerprint }.toSet()
        families.filter { it.first.allowInsecure }.forEach { (p, f) ->
            rejected += RankedMethod(p.id, p.name, f, PathStatus.SECURITY_REJECTED, ConnectionStage.NOT_TESTED, null, null,
                "certificate checks are off in this config; never recommended", 0, 0)
        }
        // The candidate pool: every family, one config each first, bounded; the planner picks the order live.
        val pool = FullAnalysis.select(families.filter { !it.first.allowInsecure }, plan.copy(budget = MAX_POOL), proven)
        val byId = pool.associateBy { it.profile.id }
        planner.add(pool.map { pk ->
            AdaptivePlanner.Candidate(pk.profile.id, pk.profile.name, pk.family,
                ipv6 = pk.profile.address.contains(':'), provenHere = pk.profile.effectiveFingerprint in proven)
        })
        val notConfigured = mutableSetOf<PathFamily>()
        if (plan.mode == ExperimentPlanner.Mode.EMERGENCY_RECOVERY) {
            ExperimentPlanner.emergencyOrder(cap).filter { f -> f !in available && f !in plan.skip }.forEach { f ->
                notConfigured += f
                notTested[f] = "no ${f.title} config is saved" + when (f) {
                    PathFamily.DNS_TUNNEL -> "; add a dnstt config for your own server"
                    PathFamily.PSIPHON -> "; Psiphon needs a config from Psiphon Inc."
                    in PathFamily.TOR_FAMILIES -> "; add a Tor config with ${f.title.removePrefix("Tor ")} bridges"
                    else -> ""
                }
            }
        }

        var current: String? = null
        val extraRows = mutableListOf<LivePath>()
        fun publishLive(finished: Boolean = false, notRequired: Set<PathFamily> = emptySet()) {
            val now = System.currentTimeMillis()
            val rows = FullAnalysis.networkPaths(cap, now).map { it.copy(sessionId = ctx.sessionId) } +
                listOfNotNull(FullAnalysis.resolverPath(resolvers), burst?.let { FullAnalysis.burstPath(it) }).map { it.copy(sessionId = ctx.sessionId) } +
                FullAnalysis.liveFamilyRows(planner.tracks(), current, plan, notTested, ctx.sessionId, now, finished, planner.stopReason, notRequired, notConfigured) + extraRows
            fun pick(t: AdaptivePlanner.Track?) = t?.let { LabPick(it.candidate.id, it.candidate.name, it.candidate.family, it.status, it.bestLatency) }
            live = LiveAnalysis(ctx.sessionId, ctx.label, reading?.primary?.title ?: "Unknown", planner.phase, planner.hypothesis(),
                current?.let { id -> byId[id]?.let { "${it.family.title} · ${it.profile.name}" } }, rows,
                pick(planner.firstWorking?.let { planner.track(it) }), pick(planner.best()), planner.spent, plan.budget, planner.events(), now)
            publish()
        }

        var aiAfterWave = false
        var announced = false
        var stoppedBy: String? = null
        if (vpnOn) {
            families.forEach { (_, f) -> notTested[f] = "the VPN is on; methods are tested outside the VPN only while it is off" }
            listOf(STEP_A_EXPERIMENT, STEP_A_VERIFY, STEP_A_CONFIG).forEach { step(it, LabStep.State.SKIPPED, "turn the VPN off to test methods") }
            stoppedBy = "the VPN is on"
        } else {
            step(STEP_A_EXPERIMENT, LabStep.State.RUNNING, "${pool.size} candidate configs in ${pool.map { it.family }.distinct().size} families")
            publishLive()
            // EXECUTE → RECORD → RECLASSIFY → REPLAN, one test at a time so every row updates live.
            loop@ while (true) {
                if (!tracker.isCurrent(ctx.sessionId)) { stoppedBy = "the network changed; results stay with the earlier network"; break }
                when (val d = planner.decide(System.currentTimeMillis())) {
                    is AdaptivePlanner.Decision.Stop -> { stoppedBy = d.why; break@loop }
                    is AdaptivePlanner.Decision.Wait -> { delay((d.untilMs - System.currentTimeMillis()).coerceIn(100, 5_000)); continue@loop }
                    is AdaptivePlanner.Decision.Test -> {
                        val pk = byId[d.candidate.id] ?: continue@loop
                        current = pk.profile.id
                        val phaseStep = if (planner.phase == AdaptivePlanner.Phase.FAST_RECOVERY) STEP_A_EXPERIMENT else STEP_A_VERIFY
                        step(phaseStep, LabStep.State.RUNNING, "${pk.family.title} · ${if (d.recheck) "recheck" else "test"} ${planner.spent + 1}/${plan.budget} · ${d.why}")
                        publishLive()
                        val engine = Sidecars.forProfile(pk.profile)
                        if (pk.family.engine || engine != null) {
                            val r = if (engine == null) null else withContext(Dispatchers.IO) {
                                EngineProbe.test(context, engine, pk.profile, ENGINE_TIMEOUT_SEC, requests = 2)
                            }
                            when {
                                r == null -> planner.notTested(pk.profile.id, "no engine in this build carries it")
                                r.notTested -> planner.notTested(pk.profile.id, r.failure ?: "not measured")
                                else -> {
                                    engineStarts++
                                    engineResults[pk.profile.id] = r
                                    planner.record(pk.profile.id, r.outcomes, r.latencyMs, System.currentTimeMillis(), r.failure ?: r.stage.title, r.spanMs)
                                    book.recordPath(com.example.vpn.connectivity.EvidenceSource.LAB, com.example.vpn.connectivity.MeasurementType.ENGINE_REQUEST,
                                        com.example.vpn.connectivity.PathRef.of(pk.profile), pk.family.name, r.stage.carriesTraffic, r.latencyMs,
                                        if (r.stage.carriesTraffic) null else r.stage.name, detail = r.engineId)
                                }
                            }
                        } else {
                            when (val o = withContext(Dispatchers.IO) { RealDelayProbe.measure(listOf(pk.profile), budget.probeTimeoutSec).first() }) {
                                is RealDelayProbe.Outcome.Delay -> planner.record(pk.profile.id, listOf(true), o.latencyMs, System.currentTimeMillis())
                                is RealDelayProbe.Outcome.Failed -> {
                                    analysisFailures[pk.profile.id] = o.reason
                                    planner.record(pk.profile.id, listOf(false), null, System.currentTimeMillis(),
                                        FailureClassifier.assess(categoryOf(o.reason, pk.profile, cap)).text)
                                }
                                is RealDelayProbe.Outcome.NotRun -> planner.notTested(pk.profile.id, o.reason)
                            }
                        }
                        current = null
                        if (!announced && planner.firstWorking != null) {
                            announced = true
                            step(STEP_A_EXPERIMENT, LabStep.State.DONE, "first working path: ${pk.profile.name} (${pk.family.title})")
                            showMessage("First working path: ${pk.profile.name}. Still verifying and comparing; you can use it now.")
                        }
                        // AI checkpoint after the first wave, when nothing passed or the evidence conflicts.
                        val conflict = ExperimentPlanner.udpDead(cap) && planner.tracks().any { it.candidate.family.udp && it.passes > 0 }
                        if (!aiAfterWave && (planner.spent >= FIRST_WAVE && planner.firstWorking == null || conflict)) {
                            aiAfterWave = true
                            aiCheckpoint(if (conflict) "conflicting evidence" else "after the first wave", reading, plan, available, planner)
                        }
                        publishLive()
                    }
                }
            }
            if (planner.firstWorking == null) step(STEP_A_EXPERIMENT, LabStep.State.DONE, "no path passed in ${planner.spent} test(s)")
            step(STEP_A_VERIFY, LabStep.State.DONE, "${planner.verifiedFamilies().size} verified famil${if (planner.verifiedFamilies().size == 1) "y" else "ies"} · stopped: ${stoppedBy ?: "done"}")
        }

        // Deep optimization with evidence-driven experiments only: ECH where a TLS hypothesis exists, MTU where a
        // WireGuard path failed while UDP answers. Each is bounded and uses derived copies; saved configs never change.
        val deepResults = mutableListOf<RankedMethod>()
        if (!vpnOn && stoppedBy?.startsWith("the network changed") != true && tracker.isCurrent(ctx.sessionId)) {
            step(STEP_A_VERIFY, LabStep.State.RUNNING, "evidence-driven experiments: ECH and MTU")
            extraRows += echExperiment(pool, planner, reading, ctx, deepResults)
            publishLive()
            extraRows += mtuExperiment(pool, planner, cap, ctx, deepResults)
            publishLive()
            step(STEP_A_VERIFY, LabStep.State.DONE, "${planner.verifiedFamilies().size} verified famil${if (planner.verifiedFamilies().size == 1) "y" else "ies"} · stopped: ${stoppedBy ?: "done"}")
        }

        // Results from what this run measured, then safe copies when nothing ordinary passed on an ordinary network.
        val results = mutableListOf<RankedMethod>()
        results += deepResults
        results += rejected
        planner.tracks().forEach { t ->
            val pk = byId[t.candidate.id] ?: return@forEach
            results += methodOf(pk, t, engineResults[t.candidate.id])
        }
        if (!vpnOn) {
            val failedParent = results.firstOrNull { it.status == PathStatus.FAILED && !it.family.engine }
            if (plan.mode == ExperimentPlanner.Mode.ORDINARY && results.none { it.stage.carriesTraffic } && failedParent != null && !planner.ordinaryMutationsStopped) {
                step(STEP_A_CONFIG, LabStep.State.RUNNING, failedParent.name)
                deriveFor(failedParent, cap, ctx)?.let { results += it }
                steps.removeAll { it.title !in ANALYSIS_STEPS }
                step(STEP_A_CONFIG, LabStep.State.DONE, results.lastOrNull { it.profileId == failedParent.profileId && it.name != failedParent.name }?.why ?: "no safe copy passed")
            } else step(STEP_A_CONFIG, LabStep.State.SKIPPED, when {
                plan.mode == ExperimentPlanner.Mode.EMERGENCY_RECOVERY -> "emergency recovery makes no config copies"
                results.any { it.stage.carriesTraffic } -> "a saved config already works"
                else -> "no ordinary config to derive from"
            })
        }

        // RANK.
        val ranked = FullAnalysis.rank(results, plan.prefer)
        step(STEP_A_SCORE, LabStep.State.DONE, ranked.firstOrNull { it.stage.carriesTraffic }?.let { "best: ${it.name} (${it.status.title})" } ?: "no method passed")

        // Isolation is concluded only from strong negative evidence across every available family.
        val isolation = if (vpnOn) null else EmergencyRecovery.conclude(cap, planner.tracks(), available.filter { it !in plan.skip }.toSet())
        if (isolation != null) book.markRecoveryExhausted()
        val stateTitle = isolation?.title ?: reading?.primary?.title ?: "Unknown"
        val notRequired = if (stoppedBy?.startsWith("three independent") == true)
            planner.tracks().filter { it.outcomes.isEmpty() && it.notTested == null }.map { it.candidate.family }.toSet() - planner.tracks().filter { it.outcomes.isNotEmpty() }.map { it.candidate.family }.toSet()
        else emptySet()
        val now = System.currentTimeMillis()
        planner.tracks().filter { it.outcomes.isEmpty() && it.notTested == null && it.candidate.family !in notRequired }.forEach { t ->
            notTested.getOrPut(t.candidate.family) { "not reached: ${stoppedBy ?: "the run ended"}" }
        }
        val paths = (FullAnalysis.networkPaths(cap, now) + listOfNotNull(FullAnalysis.resolverPath(resolvers), burst?.let { FullAnalysis.burstPath(it) }) +
            FullAnalysis.familyPaths(families.map { it.second }.toSet() + notTested.keys, ranked, plan, notTested, now, notRequired, notConfigured) +
            extraRows).map { it.copy(sessionId = ctx.sessionId) }
        step(STEP_A_PATHS, LabStep.State.DONE, "${paths.count { it.status == PathStatus.VERIFIED || it.status == PathStatus.CANDIDATE || it.status == PathStatus.AVAILABLE }} open of ${paths.size}")

        // LEARN: one sanitized evidence record per tested config; history only reorders later runs.
        val plainDns = paths.firstOrNull { it.key == "dns-plain" }?.status?.name
        store.addEvidence(planner.tracks().filter { it.outcomes.isNotEmpty() }.mapNotNull { t ->
            val pk = byId[t.candidate.id] ?: return@mapNotNull null
            LabEvidence(ctx.contextKey, t.candidate.family, pk.profile.effectiveFingerprint, t.passes > 0,
                engineResults[t.candidate.id]?.stage ?: if (t.passes > 0) ConnectionStage.APPLICATION_REQUEST_PASSED else ConnectionStage.NOT_TESTED,
                t.bestLatency, t.passes, t.outcomes.size, plainDns, "passed", now, now + LabEvidence.TTL_MS, BuildConfig.VERSION_CODE)
        })
        val firstName = planner.firstWorking?.let { planner.track(it)?.candidate?.name }
        val best = ranked.firstOrNull { it.stage.carriesTraffic }
        val note = when {
            vpnOn -> "The VPN is on, so methods were not tested. Turn it off and run the analysis again."
            stoppedBy?.startsWith("the network changed") == true -> "The network changed during the analysis. Results stay with ${ctx.label}; run it again on the new network."
            best != null && best.status == PathStatus.VERIFIED -> "Use ${best.name}. It passed ${best.passes} real requests on ${ctx.label} and is verified."
            best != null -> "Use ${best.name} for now: it passed a real request on ${ctx.label} (${best.status.title.lowercase()})."
            isolation != null -> "No verified international egress after recovery on ${ctx.label}: every available, configured method was tested and none " +
                "carried a real request. This is about the methods Maximus has here; other methods or servers may still work."
            plan.mode == ExperimentPlanner.Mode.EMERGENCY_RECOVERY -> "No path got traffic through on ${ctx.label}. " +
                (EmergencyRecovery.whyNot(cap, planner.tracks(), available) ?: "Add recovery configs (DNS tunnel, Tor bridges, Psiphon) and try again.")
            else -> "No saved config got a real request through on ${ctx.label}."
        }
        val report = AnalysisReport(ctx.sessionId, ctx.contextKey, ctx.label, startedAt, now, stateTitle, reading?.confidence ?: 0.0,
            plan.mode.title, plan.reasons, domains, paths, ranked, FullAnalysis.untested(cap, reading, burst != null), note,
            firstWorking = firstName, events = planner.events() + resourceLine(planner.spent, RealDelayProbe.requestsSent.get() - requestsAtStart,
                engineStarts, bytesAtStart, now - startedAt), stopReason = stoppedBy)
        store.saveReport(report)
        step(STEP_A_MEMORY, LabStep.State.DONE, "saved for ${ctx.label}; old results only reorder what is tried")
        step(STEP_A_REPORT, LabStep.State.DONE, note)
        XrayLogManager.i("LAB", "Full analysis on ${ctx.label}: $stateTitle, ${ranked.count { it.stage.carriesTraffic }} method(s) passed, stopped: ${stoppedBy ?: "done"}.")
        live = null
        publish(running = null, message = note)
    }

    /** Bytes this app sent and received (Xray probes run in-process; engine programs share the app's uid). */
    private fun appBytes(): Long? = runCatching {
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) null else rx + tx
    }.getOrNull()

    /** The run's resource use, each budget counted on its own. */
    private fun resourceLine(tests: Int, requests: Long, engineStarts: Int, bytesAtStart: Long?, elapsedMs: Long): String {
        val bytes = bytesAtStart?.let { start -> appBytes()?.let { (it - start).coerceAtLeast(0) } }
        return "Resources: $tests candidate test(s) · $requests real request(s) · $engineStarts engine start(s) · " +
            (bytes?.let { "${(it + 1023) / 1024} KB" } ?: "data not measured") + " · ${elapsedMs / 1000}s"
    }

    private val ECH_FAMILIES = setOf(PathFamily.VLESS_TLS, PathFamily.XHTTP, PathFamily.WEBSOCKET, PathFamily.GRPC, PathFamily.HTTP2, PathFamily.TROJAN, PathFamily.VMESS)

    /**
     * ECH, measured rather than assumed: only when a TLS hypothesis exists (TLS path failure, suspected SNI
     * interference) and a TLS config can carry ECH. At most two configs, each a derived copy whose ECHConfig comes
     * over DoH; a pass is rechecked after the stable window. Returns the "ECH" row.
     */
    private suspend fun echExperiment(pool: List<FullAnalysis.Pick>, planner: AdaptivePlanner, reading: NetworkStateReading?,
                                      ctx: NetworkContext, out: MutableList<RankedMethod>): List<LivePath> {
        val tls = pool.filter { it.family in ECH_FAMILIES }
        val capable = tls.filter { EchMeasurement.unsupportedReason(it.profile) == null }
        if (tls.isEmpty()) return listOf(LivePath("ech", "ECH", PathStatus.NOT_CONFIGURED, reason = "no TLS config is saved"))
        if (capable.isEmpty()) return listOf(LivePath("ech", "ECH", PathStatus.UNSUPPORTED,
            reason = EchMeasurement.unsupportedReason(tls.first().profile) ?: "no config can carry ECH"))
        val baselineReliable = capable.any { planner.track(it.profile.id)?.status == PathStatus.VERIFIED }
        val priority = EchMeasurement.priority(reading?.restrictions.orEmpty(), reading?.primary ?: NetworkState.UNKNOWN, baselineReliable, false)
        if (priority < 0.5) return listOf(LivePath("ech", "ECH", PathStatus.NOT_TESTED,
            reason = if (baselineReliable) "ordinary TLS already works here; ECH was not needed" else "no TLS or SNI hypothesis on this network"))
        // Configs whose ordinary TLS failed first: that is where ECH can change the outcome.
        val order = capable.sortedBy { planner.track(it.profile.id)?.passes ?: 0 }.take(2)
        var best: Pair<FullAnalysis.Pick, EchMeasurement.Result>? = null
        var attempts = 0
        for (pk in order) {
            val variant = EchMeasurement.variant(pk.profile) ?: continue
            val t = planner.track(pk.profile.id)
            val baseline = when {
                t == null || t.outcomes.isEmpty() -> null
                t.passes > 0 -> RealDelayProbe.Outcome.Delay(t.bestLatency ?: 0)
                else -> RealDelayProbe.Outcome.Failed(t.failure ?: "failed")
            }
            val first = withContext(Dispatchers.IO) { RealDelayProbe.measure(listOf(variant), budget.probeTimeoutSec).first() }
            attempts++
            val recheck = if (first is RealDelayProbe.Outcome.Delay) {
                delay(FullAnalysis.STABLE_WINDOW_MS); attempts++
                withContext(Dispatchers.IO) { RealDelayProbe.measure(listOf(variant), budget.probeTimeoutSec).first() }
            } else null
            val r = EchMeasurement.judge(baseline, first, recheck)
            com.example.vpn.connectivity.ConnectivityBrain.book.recordPath(com.example.vpn.connectivity.EvidenceSource.LAB,
                com.example.vpn.connectivity.MeasurementType.ECH, com.example.vpn.connectivity.PathRef.of(variant), "ECH",
                EchMeasurement.proven(r.state), r.latencyMs, if (EchMeasurement.proven(r.state)) null else r.state.name, detail = r.state.name)
            if (best == null || EchMeasurement.proven(r.state)) best = pk to r
            if (EchMeasurement.proven(r.state)) {
                out += RankedMethod(pk.profile.id, "${pk.profile.name} · ECH", pk.family,
                    if (r.state == EchMeasurement.State.ECH_VERIFIED) PathStatus.VERIFIED else PathStatus.CANDIDATE,
                    if (r.state == EchMeasurement.State.ECH_VERIFIED) ConnectionStage.STABILITY_VERIFIED else ConnectionStage.APPLICATION_REQUEST_PASSED,
                    r.latencyMs, null, "safe copy with ECH (config fetched over DoH): ${r.why}; the saved config is unchanged",
                    if (recheck != null) 2 else 1, if (r.state == EchMeasurement.State.ECH_VERIFIED) 2 else 1)
                break
            }
        }
        val (pk, r) = best ?: return listOf(LivePath("ech", "ECH", PathStatus.NOT_TESTED, reason = "no ECH copy could be built"))
        val status = when (r.state) {
            EchMeasurement.State.ECH_VERIFIED -> PathStatus.VERIFIED
            EchMeasurement.State.ECH_APPLICATION_TRAFFIC_VERIFIED -> PathStatus.CANDIDATE
            EchMeasurement.State.ECH_DEGRADED -> PathStatus.DEGRADED
            EchMeasurement.State.ECH_NOT_TESTED -> PathStatus.NOT_TESTED
            EchMeasurement.State.ECH_CONFIG_UNAVAILABLE -> PathStatus.UNSUPPORTED
            else -> PathStatus.FAILED
        }
        return listOf(LivePath("ech", "ECH", status, latencyMs = r.latencyMs, checkedAt = System.currentTimeMillis(),
            reason = "${pk.profile.name}: ${r.state.title}. ${r.why}", tried = order.size, attempts = attempts,
            passed = if (EchMeasurement.proven(r.state)) 1 else 0, sessionId = ctx.sessionId))
    }

    /**
     * MTU, only on evidence: a WireGuard config failed while UDP on this network answers. A bounded search over
     * derived copies (floor first, then upward); a value that passes twice is committed for this network, engine,
     * transport and address family only. Returns the "MTU (WireGuard)" row.
     */
    private suspend fun mtuExperiment(pool: List<FullAnalysis.Pick>, planner: AdaptivePlanner, cap: NetworkCapabilityProfile?,
                                      ctx: NetworkContext, out: MutableList<RankedMethod>): List<LivePath> {
        val wg = pool.filter { it.family == PathFamily.WIREGUARD }
        if (wg.isEmpty()) return listOf(LivePath("mtu", "MTU (WireGuard)", PathStatus.NOT_CONFIGURED, reason = "no WireGuard config is saved"))
        val failed = wg.firstOrNull { t -> planner.track(t.profile.id)?.let { it.outcomes.isNotEmpty() && it.passes == 0 } == true }
        val signals = MtuIntelligence.Signals(udpAliveButWireGuardStalls = failed != null && cap?.udpAvailable == true)
        if (failed == null || MtuIntelligence.suspicion(signals) == MtuIntelligence.Suspicion.NONE) return listOf(LivePath("mtu", "MTU (WireGuard)",
            PathStatus.NOT_TESTED, reason = if (failed == null) "no WireGuard failure to explain" else "UDP is not shown to work here, so MTU is not the first suspect"))
        val p = failed.profile
        val v6 = p.address.contains(':')
        val key = MtuIntelligence.Key(ctx.networkKey, "xray", "wireguard", if (v6) "ipv6" else "ipv4")
        val search = MtuIntelligence.Search(MtuIntelligence.wireGuardMtu(p), v6)
        var attempts = 0
        while (true) {
            if (!tracker.isCurrent(ctx.sessionId)) break
            val mtu = search.next() ?: break
            val tx = MtuIntelligence.cache.propose(key, mtu, System.currentTimeMillis()).stage()
            val copy = MtuIntelligence.withWireGuardMtu(p, mtu)
            val o = withContext(Dispatchers.IO) { RealDelayProbe.measure(listOf(copy), budget.probeTimeoutSec).first() }
            attempts++
            if (o is RealDelayProbe.Outcome.NotRun) break
            val ok = o is RealDelayProbe.Outcome.Delay
            search.record(mtu, ok)
            com.example.vpn.connectivity.ConnectivityBrain.book.recordPath(com.example.vpn.connectivity.EvidenceSource.LAB,
                com.example.vpn.connectivity.MeasurementType.MTU, com.example.vpn.connectivity.PathRef.of(copy), "MTU $mtu", ok,
                (o as? RealDelayProbe.Outcome.Delay)?.latencyMs, if (ok) null else "MTU_$mtu")
            MtuIntelligence.cache.store(if (ok) tx.verify() else tx.rollBack())
        }
        // The highest value that passed is confirmed once more after the stable window before it is committed.
        search.best()?.let { mtu ->
            if (!tracker.isCurrent(ctx.sessionId)) return@let
            val copy = MtuIntelligence.withWireGuardMtu(p, mtu)
            delay(FullAnalysis.STABLE_WINDOW_MS)
            val again = withContext(Dispatchers.IO) { RealDelayProbe.measure(listOf(copy), budget.probeTimeoutSec).first() }
            attempts++
            val tx = MtuIntelligence.cache.propose(key, mtu, System.currentTimeMillis()).stage().verify()
            if (again is RealDelayProbe.Outcome.Delay) {
                MtuIntelligence.cache.store(tx.commit())
                out += RankedMethod(p.id, "${p.name} · MTU $mtu", PathFamily.WIREGUARD, PathStatus.VERIFIED, ConnectionStage.STABILITY_VERIFIED,
                    again.latencyMs, null, "safe copy with WireGuard MTU $mtu passed twice where the saved MTU ${search.current} failed; the saved config is unchanged", 2, 2)
            } else MtuIntelligence.cache.store(tx.rollBack())
        }
        val best = search.best()
        val committed = MtuIntelligence.cache.working(key, System.currentTimeMillis())
        val suspicion = MtuIntelligence.suspicion(signals.copy(lowerMtuRestored = if (committed != null) 2 else if (best != null) 1 else 0,
            lowerMtuNoImprovement = if (search.ruledOut) 1 else 0))
        return listOf(LivePath("mtu", "MTU (WireGuard)",
            when { committed != null -> PathStatus.VERIFIED; best != null -> PathStatus.CANDIDATE; search.tested.isEmpty() -> PathStatus.NOT_TESTED; else -> PathStatus.FAILED },
            checkedAt = System.currentTimeMillis(), attempts = attempts, tried = search.tested.size, passed = search.tested.count { it.value },
            reason = "${p.name}: ${suspicion.title}; " + (search.tested.entries.joinToString { "${it.key} ${if (it.value) "passed" else "failed"}" }.ifBlank { "nothing measured" }) +
                (committed?.let { "; $it kept for this network only" } ?: ""), sessionId = ctx.sessionId))
    }

    /**
     * The LAB's measurements become shared evidence of the current network session (ConnectivityBrain), so
     * Smart Connect, recovery and failover reason from the same facts. DNS properties are kept apart.
     */
    private fun observeIntoBrain(key: String, cap: NetworkCapabilityProfile, reading: NetworkStateReading) {
        val book = com.example.vpn.connectivity.ConnectivityBrain.book
        book.observeNetwork(key)
        book.recordReading(reading)
        val src = com.example.vpn.connectivity.EvidenceSource.LAB
        val T = com.example.vpn.connectivity.MeasurementType
        cap.internationalReachable?.let { book.recordNetwork(src, T.NETWORK_REACHABILITY, "international", it, reading.confidence) }
        cap.domesticReachable?.let { book.recordNetwork(src, T.NETWORK_REACHABILITY, "domestic", it) }
        // Only the controlled comparison (same address, filtered vs neutral name) is SNI evidence.
        cap.sniFiltered?.let { book.recordNetwork(src, T.SNI_COMPARISON, "sni", !it, detail = "${cap.sniCut ?: 0}/${cap.sniPairs ?: 0} cut") }
        cap.udpAvailable?.let { book.recordNetwork(src, T.UDP, "udp", it) }
        cap.quicStatus?.takeIf { it != "QUIC_NOT_MEASURED" }?.let { book.recordNetwork(src, T.QUIC, "quic", it == "QUIC_AVAILABLE", detail = it) }
        cap.dnsManipulated?.let { book.recordNetwork(src, T.DNS, "dns-poisoned", !it) }
        val K = com.example.vpn.connectivity.DnsEvidenceKind
        val S = com.example.vpn.connectivity.DnsEvidenceStatus
        when {
            cap.dnsManipulated == true -> book.recordDns(K.SYSTEM_DNS, S.POISONED)
            cap.dnsWorking == true -> book.recordDns(K.SYSTEM_DNS, S.WORKS)
            cap.dnsWorking == false -> book.recordDns(K.SYSTEM_DNS, S.FAILED)
        }
        cap.udpAvailable?.let { book.recordDns(K.DIRECT_FOREIGN_DNS, if (it) S.WORKS else S.FAILED) }
        // DoH and DoT answers come over an authenticated TLS session, so a pass is verified.
        cap.dohReachable?.let { book.recordDns(K.PRECONNECT_DOH_RESOLUTION, if (it) S.VERIFIED else S.FAILED) }
        cap.dotReachable?.let { book.recordDns(K.PRECONNECT_DOT, if (it) S.VERIFIED else S.FAILED) }
        // Recursive egress needs a controlled foreign authoritative zone; DoH or 1.1.1.1 never stand in for it.
        cap.recursiveDnsEgress?.let { book.recordDns(K.RECURSIVE_FOREIGN_DNS_EGRESS, if (it) S.VERIFIED else S.FAILED) }
    }

    /** A path that carried real traffic in Connect mode's fast recovery. */
    data class FastRecoveryResult(
        val profile: VlessProfile,
        val family: PathFamily,
        val latencyMs: Long?,
        val status: PathStatus,
        /** Carried by its own engine program (Psiphon, Tor, DNS tunnel, Mihomo). */
        val engine: Boolean,
        val tested: Int
    )

    /** Outcome of a fast recovery: the path, or why none (with what was tested). */
    data class FastRecoveryOutcome(val result: FastRecoveryResult?, val tested: Int, val exhausted: Boolean, val why: String)

    private val testLock = kotlinx.coroutines.sync.Mutex()

    /**
     * CONNECT mode's escalation, called by the VPN service when no saved path carried traffic: the adaptive planner
     * with the CONNECT goal over every saved family plus the built-in recovery engines this build supports
     * (Psiphon, Tor), skipping paths the connect just tested ([exclude]: profile ids or PathRefs) and paths that failed on this
     * network moments ago. Stops at the first verified path or [deadlineMs]. Engine tests run inside
     * [engineWindow], which lets the service open its blocking interface for the app's own engine sockets.
     * Returns at once when a LAB run holds the test core.
     */
    suspend fun fastRecovery(
        exclude: Set<String>,
        deadlineMs: Long,
        engineWindow: suspend (() -> EngineProbe.Result) -> EngineProbe.Result,
        onProgress: (String) -> Unit = {}
    ): FastRecoveryOutcome {
        if (!testLock.tryLock()) return FastRecoveryOutcome(null, 0, false, "the LAB is running an analysis")
        try {
            val book = com.example.vpn.connectivity.ConnectivityBrain.book
            com.example.vpn.connectivity.ConnectivityBrain.refreshSession()
            val cap = NetworkCapabilityDetector.last?.takeIf { System.currentTimeMillis() - it.measuredAt < 10 * 60_000L }
            val reading = stateTracker.current() ?: cap?.let { NetworkStateClassifier.classify(it) }
            val device = deviceState(userStarted = true)
            val plan = ExperimentPlanner.plan(reading, cap, ExperimentPlanner.budget(
                ExperimentPlanner.Budget(true, device.batteryPercent, device.charging, device.metered)))
            val saved = loadAllProfiles().filter { !it.allowInsecure && com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(it) == null }
            // The engines that need nothing of the user's, when this build carries them.
            val builtIn = listOfNotNull(
                runCatching { com.example.vpn.sidecar.PsiphonSidecar.profile() }.getOrNull(),
                runCatching { TorSidecar.profile() }.getOrNull()
            ).filter { b -> com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(b) == null && saved.none { s -> familyOf(s) == familyOf(b) } }
            val all = (saved + builtIn).filter { it.id !in exclude && com.example.vpn.connectivity.PathRef.of(it) !in exclude }
            if (all.isEmpty()) return FastRecoveryOutcome(null, 0, true, "no other saved config or recovery engine is available")
            val families = all.map { it to familyOf(it) }
            val recent = all.mapNotNull { p ->
                val ref = com.example.vpn.connectivity.PathRef.of(p)
                if (book.eligibility(ref) == com.example.vpn.connectivity.PathEligibility.RECENTLY_FAILED) p.id to "failed a real request on this network moments ago" else null
            }.toMap()
            val pool = FullAnalysis.select(families, plan.copy(budget = MAX_POOL), emptySet())
            val byId = pool.associateBy { it.profile.id }
            val now0 = System.currentTimeMillis()
            val memory = tracker.current?.let { LabEvidence.priors(store.evidenceFor(it.contextKey), it.contextKey, now0, BuildConfig.VERSION_CODE) }.orEmpty()
            val lowBattery = device.charging != true && (device.batteryPercent ?: 100) < 20
            val planner = AdaptivePlanner(plan, cap, AdaptivePlanner.Priors(memory = memory, recentlyFailed = recent),
                AdaptivePlanner.Device(device.metered, lowBattery), AdaptivePlanner.Goal.CONNECT)
            planner.add(pool.map { pk -> AdaptivePlanner.Candidate(pk.profile.id, pk.profile.name, pk.family, ipv6 = pk.profile.address.contains(':')) })
            loop@ while (System.currentTimeMillis() < deadlineMs) {
                when (val d = planner.decide(System.currentTimeMillis())) {
                    is AdaptivePlanner.Decision.Stop -> break@loop
                    is AdaptivePlanner.Decision.Wait -> { delay((d.untilMs - System.currentTimeMillis()).coerceIn(100, 3_000)); continue@loop }
                    is AdaptivePlanner.Decision.Test -> {
                        val pk = byId[d.candidate.id] ?: continue@loop
                        onProgress("${pk.family.title}: ${if (d.recheck) "rechecking" else "testing"} ${pk.profile.name}")
                        val engine = Sidecars.forProfile(pk.profile)
                        if (engine != null) {
                            val r = engineWindow { EngineProbe.test(context, engine, pk.profile, ENGINE_TIMEOUT_SEC, requests = 2) }
                            if (r.notTested) planner.notTested(pk.profile.id, r.failure ?: "not measured")
                            else {
                                planner.record(pk.profile.id, r.outcomes, r.latencyMs, System.currentTimeMillis(), r.failure ?: r.stage.title, r.spanMs)
                                book.recordPath(com.example.vpn.connectivity.EvidenceSource.RECOVERY, com.example.vpn.connectivity.MeasurementType.ENGINE_REQUEST,
                                    com.example.vpn.connectivity.PathRef.of(pk.profile), pk.family.name, r.stage.carriesTraffic, r.latencyMs,
                                    if (r.stage.carriesTraffic) null else r.stage.name, detail = r.engineId)
                            }
                        } else when (val o = withContext(Dispatchers.IO) { RealDelayProbe.measure(listOf(pk.profile), budget.probeTimeoutSec).first() }) {
                            is RealDelayProbe.Outcome.Delay -> planner.record(pk.profile.id, listOf(true), o.latencyMs, System.currentTimeMillis())
                            is RealDelayProbe.Outcome.Failed -> planner.record(pk.profile.id, listOf(false), null, System.currentTimeMillis(), o.reason)
                            is RealDelayProbe.Outcome.NotRun -> planner.notTested(pk.profile.id, o.reason)
                        }
                    }
                }
            }
            val tested = planner.tracks().count { it.outcomes.isNotEmpty() }
            val best = planner.verifiedFamilies().firstOrNull() ?: planner.best()
            if (best != null) {
                val pk = byId.getValue(best.candidate.id)
                XrayLogManager.i("LAB", "Connect recovery: ${pk.profile.name} (${pk.family.title}) carried real traffic after $tested test(s).")
                return FastRecoveryOutcome(FastRecoveryResult(pk.profile, pk.family, best.bestLatency, best.status,
                    Sidecars.forProfile(pk.profile) != null, tested), tested, false, "")
            }
            val available = families.map { it.second }.filter { it !in plan.skip }.toSet()
            val exhausted = EmergencyRecovery.conclude(cap, planner.tracks(), available) != null
            if (exhausted) book.markRecoveryExhausted()
            val why = when {
                System.currentTimeMillis() >= deadlineMs -> "the recovery time limit was reached after $tested test(s)"
                exhausted -> "every available family was tested and none carried traffic"
                else -> "no candidate carried traffic ($tested tested)"
            }
            return FastRecoveryOutcome(null, tested, exhausted, why)
        } finally {
            testLock.unlock()
        }
    }

    /** The family of a saved config from its fields: Tor by its bridge transport, Mihomo by its proxy type. */
    private fun familyOf(p: VlessProfile): PathFamily {
        val engine = Sidecars.forProfile(p)?.id
        val detail = when (engine) {
            "tor" -> runCatching { TorSidecar.bridges(p).firstOrNull()?.substringBefore(' ') }.getOrNull() ?: "invalid"
            "mihomo" -> MihomoSidecar.proxyOf(p)?.let { o ->
                val type = o.optString("type").lowercase()
                if (type == "wireguard" && o.has("amnezia-wg-option")) "wireguard+awg" else type
            }
            else -> null
        }
        return PathFamily.of(p, engine, detail)
    }

    /**
     * Optional AI checkpoint: the advisor sees the state, the restrictions and family titles with this run's
     * pass/fail counts only (no network key, address, config name or credential). Its answer passes a
     * schema check (family names), a capability check (families with a saved, untested-or-tested config
     * that the plan does not skip), and can only reorder; the experiments stay deterministic, and an AI
     * failure or timeout changes nothing.
     */
    private suspend fun aiCheckpoint(
        moment: String,
        reading: NetworkStateReading?,
        plan: ExperimentPlanner.Plan,
        available: Set<PathFamily>,
        planner: AdaptivePlanner
    ): List<PathFamily> {
        val advisor = familyAdvisor ?: return emptyList()
        val offered = available.filter { it !in plan.skip }
        if (offered.size < 2) return emptyList()
        val brief = buildString {
            appendLine("Checkpoint: $moment.")
            appendLine("Network state: ${reading?.primary?.title ?: "Unknown"}; restrictions: ${reading?.restrictions.orEmpty().joinToString { it.title }.ifBlank { "none measured" }}.")
            appendLine("Plan: ${plan.mode.title}.")
            offered.forEach { f ->
                val mine = planner.tracks().filter { it.candidate.family == f && it.outcomes.isNotEmpty() }
                appendLine("- ${f.name}: " + if (mine.isEmpty()) "not tested yet" else "${mine.count { it.passes > 0 }}/${mine.size} configs passed")
            }
        }
        val answer = withTimeoutOrNull(AI_TIMEOUT_MS) { runCatching { advisor(brief, offered.map { it.name }) }.getOrNull() } ?: return emptyList()
        val valid = AiCheckpoint.validate(answer, offered.toSet())
        if (valid.isNotEmpty()) {
            planner.hint(valid)
            XrayLogManager.i("LAB", "AI checkpoint ($moment): ${valid.joinToString { it.title }} first; order only.")
        }
        return valid
    }

    /** Loss, jitter and rate limiting from a short UDP DNS burst to the best honest resolver; null when none answers. */
    private suspend fun measureBurst(resolvers: List<ResolverIntelligence.Result>): ResolverIntelligence.Burst? {
        val best = resolvers.firstOrNull { it.usable && it.udp == true } ?: return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = NetworkCapabilityDetector.physical(cm) ?: return null
        return withContext(Dispatchers.IO) { runCatching { ResolverIntelligence.burst(network, best) }.getOrNull() }
    }

    /** Bounded resolver checks on the phone's own network, saved per network with an expiry. */
    private suspend fun measureResolvers(ctx: NetworkContext): List<ResolverIntelligence.Result> {
        store.resolversFor(ctx.networkKey).takeIf { it.isNotEmpty() }?.let { return it }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = NetworkCapabilityDetector.physical(cm) ?: return emptyList()
        val own = runCatching { cm.getLinkProperties(network)?.dnsServers.orEmpty() }.getOrDefault(emptyList())
        val results = coroutineScope {
            ResolverIntelligence.candidates(own).map { c ->
                async(Dispatchers.IO) { runCatching { ResolverIntelligence.evaluate(network, c, ctx.networkKey) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        store.saveResolvers(ctx.networkKey, results)
        return ResolverIntelligence.rank(results)
    }

    /** One ranked row from what the planner recorded for a config (and the engine result, for engine configs). */
    private fun methodOf(pk: FullAnalysis.Pick, t: AdaptivePlanner.Track, r: EngineProbe.Result?): RankedMethod {
        val status = t.status
        val attempts = t.outcomes.size
        val stage = when {
            status == PathStatus.VERIFIED -> ConnectionStage.STABILITY_VERIFIED
            t.passes > 0 -> r?.stage?.takeIf { it.carriesTraffic }?.let { minOf(it, ConnectionStage.DNS_THROUGH_TUNNEL_PASSED) } ?: ConnectionStage.APPLICATION_REQUEST_PASSED
            else -> r?.stage ?: ConnectionStage.NOT_TESTED
        }
        val via = if (r != null) " through the ${r.engineId} engine" + if (pk.family == PathFamily.DNS_TUNNEL) " (${EngineProbe.dnsTunnelStage(r).name.lowercase().replace('_', ' ')})" else "" else ""
        val why = when (status) {
            PathStatus.VERIFIED -> "${t.passes} real requests passed$via, ${FullAnalysis.STABLE_WINDOW_MS / 1000}s+ apart"
            PathStatus.DEGRADED -> "passed$via, then failed its recheck"
            PathStatus.CANDIDATE -> "one real request passed$via; not rechecked yet"
            PathStatus.NOT_TESTED -> "not tested: ${t.notTested ?: "not reached"}"
            else -> t.failure ?: "no real request passed"
        }
        return RankedMethod(pk.profile.id, pk.profile.name, pk.family, status, stage, t.bestLatency,
            FullAnalysis.confidence(t.passes, attempts), why, attempts, t.passes)
    }

    /** Runs the ordinary experiment on one failed saved config and reports its best copy, if one passed. */
    private suspend fun deriveFor(failed: RankedMethod, cap: NetworkCapabilityProfile?, ctx: NetworkContext): RankedMethod? {
        val parent = loadProfile(failed.profileId) ?: return null
        val now = System.currentTimeMillis()
        val id = store.newExperimentId()
        val reason = categoryOf(analysisFailures[failed.profileId] ?: failed.why, parent, cap)
        val builds = generator.generate(id, parent, reason, cap, endpoints = endpoints.validated(ctx.vpnKey),
            retired = store.retiredMutations(ctx.contextKey, parent.effectiveFingerprint), max = budget.maxCandidates)
        val copies = builds.mapNotNull { b -> b.profile?.let { b.candidate.candidateId to it } }.toMap()
        if (copies.isEmpty()) return null
        val e = LabExperiment(id, ctx.sessionId, ctx.contextKey, ctx.label, parent.id, parent.effectiveFingerprint, reason, ExperimentState.CREATED, now,
            candidates = builds.map { it.candidate }, note = "Full analysis: safe copies of ${parent.name}.")
        store.saveExperiment(e)
        recentStarts.addLast(now)
        run(e, copies, budget, parent, ctx)
        val result = store.experiments().firstOrNull { it.experimentId == id } ?: return null
        val best = result.best ?: return null
        val verified = result.state == ExperimentState.VERIFIED
        return RankedMethod(parent.id, "${parent.name} · ${store.strategyLabel(best.mutationProfileId)}", failed.family,
            if (verified) PathStatus.VERIFIED else PathStatus.CANDIDATE,
            if (best.stats.successes >= 2) ConnectionStage.STABILITY_VERIFIED else ConnectionStage.APPLICATION_REQUEST_PASSED,
            best.stats.medianLatencyMs, FullAnalysis.confidence(best.stats.successes, best.stats.attempts),
            "safe copy (${result.experimentId}) passed ${best.stats.successes}/${best.stats.attempts} real requests; the saved config is unchanged", best.stats.attempts, best.stats.successes)
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
        val reading = _state.value.networkState
        if (!userStarted) ExperimentPlanner.automaticRefusal(reading)?.let { return fail(STEP_BASELINE, it) }
        step(STEP_BASELINE, LabStep.State.DONE, "${ctx.label} · ${ctx.families}" + (reading?.let { " · ${it.primary.title}" } ?: ""))
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
            endpoints = endpoints.validated(ctx.vpnKey),
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
        var tx = ConfigOptimizer.propose(store.newTransactionId(), parent.effectiveFingerprint, rp.key, ctx.vpnKey, v.endpoint, fields,
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
            now, minOf(now + APPLY_TTL_MS, rp.expiresAt), ctx.vpnKey, BpbRecoveryEngine.diff(parent, copy), copy, gate, v.endpoint,
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
        key == "wifi" || key.startsWith("wifi:") -> com.example.vpn.smart.NetworkIdentity.label(key, "Wi-Fi")
        key == "ethernet" || key.startsWith("ethernet:") -> com.example.vpn.smart.NetworkIdentity.label(key, "Ethernet")
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
            networkState = networkState, transactions = store.transactions(),
            analysis = (network ?: tracker.current)?.let { store.reportFor(it.contextKey) }, analysisRunning = analysisRunning, live = live
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
        const val ENGINE_TIMEOUT_SEC = 20
        /** Configs in the candidate pool of one analysis; the planner tests only as many as the budget allows. */
        const val MAX_POOL = 24
        /** Tests after which, with nothing passed, the AI checkpoint is asked again. */
        const val FIRST_WAVE = 3
        const val AI_TIMEOUT_MS = 10_000L

        const val STEP_A_IDENTIFY = "1 · Identifying the network"
        const val STEP_A_MEASURE = "2 · Measuring the physical network"
        const val STEP_A_CLASSIFY = "3 · Classifying the network state"
        const val STEP_A_DIAGNOSE = "4 · Diagnosing failure domains"
        const val STEP_A_PLAN = "5 · Hypotheses and plan"
        const val STEP_A_EXPERIMENT = "6 · Fast recovery: first working path"
        const val STEP_A_VERIFY = "7 · Deep optimization: verify and compare"
        const val STEP_A_SCORE = "8 · Scoring"
        const val STEP_A_CONFIG = "9 · Trying safe config copies"
        const val STEP_A_PATHS = "10 · Building live connectivity paths"
        const val STEP_A_MEMORY = "11 · Updating network memory"
        const val STEP_A_REPORT = "12 · Final report"
        val ANALYSIS_STEPS = listOf(STEP_A_IDENTIFY, STEP_A_MEASURE, STEP_A_CLASSIFY, STEP_A_DIAGNOSE, STEP_A_PLAN, STEP_A_EXPERIMENT, STEP_A_VERIFY,
            STEP_A_SCORE, STEP_A_CONFIG, STEP_A_PATHS, STEP_A_MEMORY, STEP_A_REPORT)
        val LIVE_STEPS = listOf(STEP_BASELINE, STEP_DNS, STEP_IPV6, STEP_TLS, STEP_TRANSPORT, STEP_CANDIDATES, STEP_SECURITY, STEP_TESTING, STEP_STABILITY, STEP_DONE)
    }
}
