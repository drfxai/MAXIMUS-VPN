package com.example.ui.lab

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.RayApplication
import com.example.ai.agents.AiChat
import com.example.ai.agents.LabAgent
import com.example.ai.agents.ResearchAgent
import com.example.ai.gateway.AiException
import com.example.ai.gateway.AiGatewayHolder
import com.example.data.model.ConnectionStatus
import com.example.ui.ai.relative
import com.example.vpn.RayVpnService
import com.example.vpn.lab.LabBrief
import com.example.vpn.lab.research.GitHubReleaseSource
import com.example.vpn.lab.research.ResearchPipeline
import com.example.vpn.lab.research.ResearchStore
import com.example.vpn.smart.NetworkKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the LAB screen's state: the controller's snapshot plus the saved configs (names only) and research. */
class NetworkLabViewModel(app: Application) : AndroidViewModel(app) {
    private val lab = RayApplication.instance.lab
    private val revival = RayApplication.instance.configRevival
    private var revivalJob: Job? = null
    private val gateway = AiGatewayHolder.get(app)
    private val chat = AiChat { consumer, request -> gateway.chat(consumer, request) }
    private val research = run {
        val prefs = app.getSharedPreferences("lab_research", Application.MODE_PRIVATE)
        ResearchStore(load = { prefs.getString("v1", null) }, save = { prefs.edit().putString("v1", it).apply() })
    }
    private val pipeline = ResearchPipeline()
    private val local = MutableStateFlow(NetworkLabUiState())
    private val _state = MutableStateFlow(NetworkLabUiState())
    val state: StateFlow<NetworkLabUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(lab.state, local, RayVpnService.vpnState) { snap, l, vpn ->
                l.copy(snapshot = snap, vpnOn = vpn.status != ConnectionStatus.DISCONNECTED, aiReady = gateway.isConfigured())
            }.collect { _state.value = it }
        }
        loadConfigs()
        loadResearch()
    }

    private fun loadConfigs() = viewModelScope.launch {
        val configs = withContext(Dispatchers.IO) { runCatching { RayApplication.instance.serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList()) }
        val eligible = runCatching { revival.eligibleCount(configs) }.getOrDefault(0)
        val detected = com.example.vpn.connectivity.NetworkFirewalls.detect(NetworkKey.current(getApplication<Application>()))
        val sc = com.example.vpn.sidecar.Sidecars
        val psiphonReady = runCatching { sc.psiphon().let { sc.isBundled(it) && it.problem(com.example.vpn.sidecar.PsiphonSidecar.profile()) == null } }.getOrDefault(true)
        val torReady = runCatching { sc.ENGINES.first { it.id == "tor" }.let { sc.isBundled(it) } }.getOrDefault(true)
        local.update { it.copy(revival = it.revival.copy(eligible = eligible, detected = detected),
            chain = it.chain.copy(psiphonReady = psiphonReady, torReady = torReady)) }
        local.value = local.value.copy(configs = configs.map { p ->
            LabConfigOption(p.id, p.name.ifBlank { "Config" }, listOf(p.protocolType.displayName, p.transport.uppercase().ifBlank { null }, p.security.uppercase().ifBlank { null })
                .filterNotNull().filter { it != "NONE" }.joinToString(" · "))
        })
    }

    private fun loadResearch() {
        local.value = local.value.copy(research = research.all().map { r ->
            LabResearchItem(r.id, r.title, r.state.title, r.source, listOf(r.summary, r.compatibility).filter { it.isNotBlank() }.joinToString(" "), r.createdAt)
        })
    }

    fun open(section: LabSection?) { local.value = local.value.copy(section = section) }

    /** Revives the dead Cloudflare configs (VPN off); results stay on the Revive page. */
    fun revive() {
        if (revivalJob?.isActive == true) return
        revivalJob = viewModelScope.launch {
            local.update { it.copy(revival = it.revival.copy(running = true, done = 0, total = 0, current = "Starting")) }
            val chosen = local.value.revival.chosen
            val report = withContext(Dispatchers.IO) {
                val profiles = runCatching { RayApplication.instance.serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                val network = NetworkKey.current(getApplication<Application>())
                revival.run(profiles, network, cancelled = { !isActive }, firewall = chosen) { p ->
                    local.update { it.copy(revival = it.revival.copy(done = p.done, total = p.total, current = p.current ?: it.revival.current)) }
                }
            }
            local.update { it.copy(revival = it.revival.copy(running = false, current = null, results = report.results, ranAt = System.currentTimeMillis(),
                echResolvers = report.echResolvers, cleanIps = report.cleanIps, scannedForIps = report.scannedForIps,
                detected = if (chosen == null) report.firewall else it.revival.detected)) }
        }.also { job ->
            job.invokeOnCompletion { local.update { it.copy(revival = it.revival.copy(running = false, current = null)) } }
        }
    }

    fun stopRevive() { revivalJob?.cancel() }

    private var servicesJob: Job? = null
    private val serviceHttp by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }

    /** Opens each service's front page from the app (through the VPN when it is on) and judges the answer. */
    fun checkServices() {
        if (servicesJob?.isActive == true) return
        val throughVpn = _state.value.vpnOn
        servicesJob = viewModelScope.launch {
            local.update { it.copy(services = it.services.copy(running = true, partial = emptyList())) }
            val report = withContext(Dispatchers.IO) {
                com.example.vpn.lab.ServiceCheck.run(fetch = { url ->
                    val start = System.nanoTime()
                    serviceHttp.newCall(okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36")
                        .header("Accept-Language", "en-US,en;q=0.9").build()).execute().use { r ->
                        com.example.vpn.lab.ServiceCheck.Response(r.code, r.request.url.toString(), r.peekBody(65_536).string(),
                            (System.nanoTime() - start) / 1_000_000)
                    }
                }, throughVpn = throughVpn, cancelled = { !isActive }) { res ->
                    local.update { it.copy(services = it.services.copy(partial = it.services.partial + res)) }
                }
            }
            local.update { it.copy(services = ServicesUi(report = report)) }
        }.also { job -> job.invokeOnCompletion { local.update { it.copy(services = it.services.copy(running = false)) } } }
    }

    fun stopServices() { servicesJob?.cancel() }

    fun chainOrder(order: ChainOrder) = local.update { it.copy(chain = it.chain.copy(order = order, savedName = null)) }
    fun chainRegion(code: String) = local.update { it.copy(chain = it.chain.copy(region = code, savedName = null)) }

    /** Saves the built chain as a server profile the user can connect to. */
    fun saveChain() {
        if (_state.value.chain.saving) return
        local.update { it.copy(chain = it.chain.copy(saving = true)) }
        viewModelScope.launch {
            val ui = _state.value.chain
            val chain = com.example.vpn.sidecar.EngineChain.Chain(listOf(
                hop(ui.order.first, ui.region), hop(ui.order.second, ui.region)))
            val profile = com.example.vpn.sidecar.ChainRunner.profile(chain) { titleOf(it) }
            withContext(Dispatchers.IO) { runCatching { RayApplication.instance.serverRepository.insert(profile) } }
            local.update { it.copy(chain = it.chain.copy(saving = false, savedName = profile.name)) }
            lab.showMessage("Saved the chain \"${profile.name}\". Connect to it on the Servers screen.")
        }
    }

    private fun hop(engineId: String, region: String) =
        com.example.vpn.sidecar.EngineChain.Hop(engineId, region = if (engineId == "psiphon") region else "")

    private fun titleOf(engineId: String) = if (engineId == "psiphon") "Psiphon" else "Tor"
    fun chooseFirewall(f: com.example.vpn.connectivity.NetworkFirewalls.Firewall?) = local.update { it.copy(revival = it.revival.copy(chosen = f)) }
    fun pick(show: Boolean) { local.value = local.value.copy(picking = show); if (show) loadConfigs() }
    fun run(profileId: String) { pick(false); open(LabSection.LIVE); lab.experiment(profileId, userStarted = true) }
    fun fullAnalysis() {
        open(LabSection.LIVE)
        // Optional AI checkpoint inside the loop; without a configured provider the LAB runs exactly the same.
        lab.familyAdvisor = if (gateway.isConfigured()) { brief, families -> LabAgent(chat).rankFamilies(brief, families) } else null
        lab.fullAnalysis()
    }
    fun refreshNetwork() = viewModelScope.launch { lab.refreshNetwork(userStarted = true) }

    /**
     * USE RECOMMENDED: selects the saved config the LAB measured best, as if the user picked it on the
     * Servers screen. Nothing is changed in the config, and nothing connects until the user taps Connect.
     */
    fun useRecommended(profileId: String, name: String) {
        RayApplication.instance.settingsRepository.setSelectedProfileId(profileId)
        lab.showMessage("Selected $name. Connect to use it; the config itself is unchanged.")
    }

    /** Advisory only: the answer becomes an unverified discovery and a list of changes the user may choose to test. */
    fun askAgent() {
        if (local.value.advising) return
        local.value = local.value.copy(advising = true)
        viewModelScope.launch {
            try {
                val advice = LabAgent(chat).advise(LabBrief.of(lab.state.value))
                lab.recordAdvice(advice.explanation, advice.patterns, advice.confidence, advice.model)
                local.value = local.value.copy(suggestions = advice.suggestions.map { LabSuggestionUi(it.field, it.value, it.why) })
            } catch (e: AiException) {
                local.value = local.value.copy(suggestions = emptyList())
                lab.showMessage("LAB Agent could not answer: ${e.error.userMessage()} The LAB itself is unaffected.")
            } finally {
                local.value = local.value.copy(advising = false)
            }
        }
    }

    /** Tests a suggestion on the config of the latest experiment on this network, if there is one. */
    fun testSuggestion(s: LabSuggestionUi) {
        val snap = lab.state.value
        val parent = snap.experiments.firstOrNull { it.contextKey == snap.network?.contextKey }?.parentProfileId ?: return
        open(LabSection.LIVE)
        lab.testSuggestion(parent, s.field, s.value)
    }

    /** Reads release notes from allowlisted sources; with AI set up, the notes are also summarized (labelled as such). */
    fun refreshResearch() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val found = withContext(Dispatchers.IO) {
            GitHubReleaseSource.ALLOWED.filter { it == "XTLS/Xray-core" }.flatMap { repo ->
                runCatching { pipeline.candidates(repo, GitHubReleaseSource(repo).releases(), now) }.getOrDefault(emptyList())
            }
        }
        val added = research.merge(found, now)
        research.all().forEach { research.update(pipeline.review(it, now)) }
        if (gateway.isConfigured()) {
            added.take(2).forEach { c ->
                runCatching { ResearchAgent(chat).summarize(c.title, c.summary) }.getOrNull()?.let { s ->
                    research.update(c.copy(summary = s.summary, summaryBy = "AI summary"))
                }
            }
        }
        loadResearch()
    }
}

@Composable
fun NetworkLabScreen(onNavigateBack: () -> Unit, viewModel: NetworkLabViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val lab = RayApplication.instance.lab
    BackHandler(enabled = state.section != null || state.picking) {
        if (state.picking) viewModel.pick(false) else viewModel.open(null)
    }
    LaunchedEffect(Unit) { viewModel.refreshNetwork() }
    NetworkLabContent(
        state = state,
        actions = remember(viewModel) {
            NetworkLabActions(
                onOpen = viewModel::open, onPick = viewModel::pick, onRun = viewModel::run, onFullAnalysis = viewModel::fullAnalysis, onCancel = lab::cancel,
                onAutomation = lab::setAutomation, onRefreshNetwork = { viewModel.refreshNetwork() }, onRetest = { viewModel.open(LabSection.LIVE); lab.retest(it) },
                onDisable = lab::setDisabled, onRetire = lab::retire, onDismissMessage = lab::dismissMessage,
                onResearchRefresh = { viewModel.refreshResearch() }, onAskAgent = viewModel::askAgent, onTestSuggestion = viewModel::testSuggestion,
                onUseRecommended = viewModel::useRecommended, onRevive = viewModel::revive, onStopRevive = viewModel::stopRevive,
                onFirewall = viewModel::chooseFirewall, onCheckServices = viewModel::checkServices, onStopServices = viewModel::stopServices,
                onChainOrder = viewModel::chainOrder, onChainRegion = viewModel::chainRegion, onSaveChain = viewModel::saveChain
            )
        },
        relativeTime = ::relative
    )
}
