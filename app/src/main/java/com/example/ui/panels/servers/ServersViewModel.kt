package com.example.ui.panels.servers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.panels.PanelStore
import com.example.panels.servers.ActionProgress
import com.example.panels.servers.DnsDelegation
import com.example.panels.servers.InstallChecks
import com.example.panels.servers.InstallOptions
import com.example.panels.servers.InstallOutcome
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.ServerFacts
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.ServerManager
import com.example.panels.servers.ServerRandom
import com.example.panels.servers.ServerStore
import com.example.panels.servers.ServerToolCatalog
import com.example.panels.servers.ToolAdvisor
import com.example.panels.servers.ToolProfiles
import com.example.panels.servers.ToolScripts
import com.example.vpn.lab.EngineProbe
import com.example.vpn.sidecar.Sidecars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Which server page is open over the Panels tabs. */
sealed interface ServersRoute {
    data object None : ServersRoute
    data object Add : ServersRoute
    data object Wizard : ServersRoute
    data class Detail(val id: String) : ServersRoute
}

data class ServersState(
    val servers: List<ManagedServer> = emptyList(),
    /** Server id -> SSH port connect time in ms, or null when it did not answer. Missing = not measured yet. */
    val reach: Map<String, Long?> = emptyMap(),
    val reachChecking: Boolean = false,
    val route: ServersRoute = ServersRoute.None,
    val add: AddServerUi = AddServerUi(),
    val wizard: WizardUi? = null,
    val checking: Set<String> = emptySet(),
    val busy: String = "",
    val error: String = "",
    val logs: Pair<String, List<String>>? = null,
    /** "serverId/toolId" -> (passed, text). */
    val tests: Map<String, Pair<Boolean, String>> = emptyMap()
)

/** One-off events for the screen: a message to show, or a vibration on success. */
sealed interface ServersEvent {
    data class Message(val text: String) : ServersEvent
    data object Success : ServersEvent
    data object PanelsChanged : ServersEvent
}

/**
 * My Servers, the Install Center wizard and the server page. All SSH work runs on the IO
 * dispatcher through [ServerManager]; sign-in details stay in [ServerStore] on this device.
 */
class ServersViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ServerStore(app)
    private val manager = ServerManager()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(ServersState(servers = runCatching { store.load() }.getOrDefault(emptyList())))
    val state: StateFlow<ServersState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ServersEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ServersEvent> = _events
    private var job: Job? = null

    private fun server(id: String?) = _state.value.servers.firstOrNull { it.id == id }

    private fun save(server: ManagedServer) {
        store.save(server)
        _state.update { s -> s.copy(servers = s.servers.filterNot { it.id == server.id } + server) }
    }

    private fun emit(e: ServersEvent) { _events.tryEmit(e) }

    // ------------------------------------------------------------ list and reachability

    fun refreshReach() {
        if (_state.value.reachChecking) return
        val servers = _state.value.servers
        if (servers.isEmpty()) return
        _state.update { it.copy(reachChecking = true) }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                servers.map { s ->
                    async {
                        s.id to runCatching {
                            val start = System.nanoTime()
                            Socket().use { it.connect(InetSocketAddress(s.host, s.sshPort), 4_000) }
                            (System.nanoTime() - start) / 1_000_000
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            _state.update { it.copy(reach = it.reach + results, reachChecking = false) }
        }
    }

    fun rows(): List<ServerRowUi> = _state.value.let { st -> st.servers.map { row(it, st) } }

    private fun row(s: ManagedServer, st: ServersState = _state.value): ServerRowUi {
        val measured = st.reach.containsKey(s.id)
        val health = when {
            !s.canSignIn -> Health.SIGN_IN
            !measured || s.id in st.checking -> Health.CHECKING
            st.reach[s.id] == null -> Health.OFFLINE
            else -> Health.ONLINE
        }
        val f = s.facts
        val system = f?.let {
            listOf(it.osName.substringBefore(" LTS").ifBlank { it.osId }, if (it.ramMb > 0) "${(it.ramMb + 512) / 1024} GB" else "")
                .filter { x -> x.isNotBlank() }.joinToString(" · ")
        }.orEmpty()
        return ServerRowUi(s.id, s.displayName, s.host, s.location, health, st.reach[s.id], s.tools.map { it.id }, system)
    }

    fun advice(): List<AdviceUi> = _state.value.servers.flatMap { s ->
        ToolAdvisor.suggest(s, s.facts).map { AdviceUi(it.toolId, s.id, s.displayName, s.location, it.reason) }
    }

    fun installedCounts(): Map<String, Int> =
        _state.value.servers.flatMap { s -> s.tools.map { it.id } }.groupingBy { it }.eachCount()

    fun close() {
        if (_state.value.route is ServersRoute.Wizard && _state.value.wizard?.stage == WizardStage.RUNNING) return
        _state.update { it.copy(route = ServersRoute.None, wizard = null, logs = null, error = "", busy = "") }
        refreshReach()
    }

    // ------------------------------------------------------------ add server / sign in

    fun openAdd(editId: String? = null) {
        val existing = server(editId)
        _state.update {
            it.copy(route = ServersRoute.Add, add = existing?.let { s ->
                AddServerUi(editingId = s.id, location = s.location, name = s.name, host = s.host, port = s.sshPort.toString(), user = s.username)
            } ?: AddServerUi())
        }
    }

    fun changeAdd(ui: AddServerUi) = _state.update { it.copy(add = ui.copy(error = "")) }

    fun continueAdd() {
        val ui = _state.value.add
        if (!ui.formValid || ui.busy.isNotBlank()) return
        _state.update { it.copy(add = ui.copy(busy = "Reading the server's SSH key…", error = "")) }
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { manager.discoverHostKey(ui.host, ui.port.toInt()) } }
            _state.update { s ->
                s.copy(add = s.add.copy(busy = "", fingerprint = result.getOrNull().orEmpty(),
                    error = result.exceptionOrNull()?.message.orEmpty()))
            }
        }
    }

    fun trustAdd() {
        val ui = _state.value.add
        if (ui.fingerprint.isBlank() || ui.busy.isNotBlank()) return
        val base = server(ui.editingId)
        val draft = (base ?: ManagedServer(name = "", host = "", location = ui.location)).copy(
            name = ui.name.trim(), host = ui.host.trim(), sshPort = ui.port.toInt(), username = ui.user.trim(),
            location = ui.location, hostKeySha256 = ui.fingerprint, password = ui.password
        )
        _state.update { it.copy(add = ui.copy(busy = "Signing in and checking the server…")) }
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { manager.check(draft) } }
            result.onSuccess { checked ->
                save(checked)
                _state.update { it.copy(route = ServersRoute.Detail(checked.id), add = AddServerUi()) }
                emit(ServersEvent.Success)
                refreshReach()
            }.onFailure { e ->
                _state.update { it.copy(add = it.add.copy(busy = "", fingerprint = "", error = e.message ?: "Sign-in failed")) }
            }
        }
    }

    // ------------------------------------------------------------ server page

    fun openDetail(id: String) {
        _state.update { it.copy(route = ServersRoute.Detail(id), logs = null, error = "") }
        val s = server(id) ?: return
        if (s.canSignIn && System.currentTimeMillis() - s.checkedAt > 60_000) refresh(id)
    }

    fun refresh(id: String) {
        val s = server(id) ?: return
        if (!s.canSignIn || id in _state.value.checking) return
        _state.update { it.copy(checking = it.checking + id, error = "") }
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { manager.check(s) } }
            r.onSuccess { save(it) }
            _state.update { st ->
                st.copy(checking = st.checking - id, error = r.exceptionOrNull()?.message.orEmpty(),
                    reach = if (r.isSuccess) st.reach else st.reach + (id to null))
            }
        }
    }

    fun detailUi(id: String): ServerDetailUi? {
        val st = _state.value
        val s = server(id) ?: return null
        val f = s.facts
        val tools = s.tools.mapNotNull { t -> toolRow(s, t.id, f, st) }
        return ServerDetailUi(
            row = row(s, st), sshPort = s.sshPort, user = s.username,
            checkedText = if (s.checkedAt > 0) "Checked ${ago(s.checkedAt)}" else "",
            checking = id in st.checking,
            os = f?.osName.orEmpty(), cpus = f?.cpus ?: 0, load = f?.load1?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
            ramPercent = f?.ramUsedPercent ?: 0,
            ramText = f?.let { gb(it.ramMb - it.ramAvailableMb) + "/" + gb(it.ramMb) + " GB" }.orEmpty(),
            diskPercent = f?.diskUsedPercent ?: 0,
            diskText = f?.let { gb(it.diskMb - it.diskFreeMb) + "/" + gb(it.diskMb) + " GB" }.orEmpty(),
            uptime = f?.let { uptimeText(it.uptimeSec) }.orEmpty(),
            hasFacts = f != null,
            keyLogin = s.usesKey,
            passwordLoginOff = s.passwordLoginOff || f?.sshPasswordLogin == false,
            needsSignIn = !s.canSignIn,
            tools = tools,
            advice = ToolAdvisor.suggest(s, f).map { AdviceUi(it.toolId, s.id, s.displayName, s.location, it.reason) },
            busy = st.busy, error = st.error,
            logsTitle = st.logs?.first.orEmpty(), logs = st.logs?.second
        )
    }

    private fun toolRow(s: ManagedServer, id: String, f: ServerFacts?, st: ServersState): ToolRowUi? {
        val t = s.tool(id) ?: return null
        val title = ServerToolCatalog.byId(id)?.title ?: id
        val units = ToolScripts.UNITS[id]
        val running = units == null || f == null || units.all { f.active(it) }
        val status = when {
            units == null -> "On"
            f == null -> "Unknown"
            running -> "Running"
            else -> "Stopped"
        }
        val test = st.tests["${s.id}/$id"]
        return when (id) {
            ServerToolCatalog.XUI -> ToolRowUi(id, title, status, running, t.port?.let { "Panel on port $it" }.orEmpty(),
                canRestart = true, canLogs = true, canOpenPanel = t.panelId != null)
            ServerToolCatalog.DNSTT -> ToolRowUi(id, title, status, running, t.settings[ToolProfiles.DNSTT_DOMAIN].orEmpty(),
                canTest = true, canRestart = true, canLogs = true, canRotateKey = true,
                link = runCatching { ToolProfiles.dnsttLink(s, t) }.getOrNull(), testResult = test?.second, testOk = test?.first)
            ServerToolCatalog.HYSTERIA2 -> ToolRowUi(id, title, status, running, "UDP ${t.port} · Salamander",
                canTest = true, canRestart = true, canLogs = true, canRotatePort = true, canRotateKey = true,
                link = runCatching { ToolProfiles.hysteriaLink(s, t) }.getOrNull(), testResult = test?.second, testOk = test?.first)
            ServerToolCatalog.KEY_LOGIN -> null // shown in the security card
            ServerToolCatalog.FIREWALL -> ToolRowUi(id, title, if (f?.firewall == "ufw-active") "On" else if (f == null) "Unknown" else "Off",
                f == null || f.firewall == "ufw-active", "UFW")
            ServerToolCatalog.FAIL2BAN -> ToolRowUi(id, title, status, running, "SSH jail · 5 tries, 1 h ban", canRestart = true, canLogs = true)
            ServerToolCatalog.AUTO_UPDATES -> ToolRowUi(id, title, if (f == null || f.autoUpdates) "On" else "Off", f == null || f.autoUpdates, "Daily security fixes")
            ServerToolCatalog.BBR -> ToolRowUi(id, title, if (f == null || f.congestionControl == "bbr") "On" else "Off",
                f == null || f.congestionControl == "bbr", "Congestion control: ${f?.congestionControl ?: "bbr"}")
            ServerToolCatalog.SWAP -> ToolRowUi(id, title, if (f == null || f.swapMb > 0) "On" else "Off", f == null || f.swapMb > 0,
                f?.let { "${it.swapMb} MB" }.orEmpty())
            else -> ToolRowUi(id, title, status, running)
        }
    }

    private fun gb(mb: Long) = String.format(Locale.US, "%.1f", mb / 1024.0)
    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    private fun serverAction(id: String, busy: String, block: suspend (ManagedServer) -> Unit) {
        val s = server(id) ?: return
        if (_state.value.busy.isNotBlank()) return
        _state.update { it.copy(busy = busy, error = "") }
        job = viewModelScope.launch {
            try {
                block(s)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(error = e.message ?: "The action failed") }
            } finally {
                _state.update { it.copy(busy = "") }
            }
        }
    }

    private val quiet = object : ActionProgress {
        override fun step(index: Int) = Unit
        override fun log(line: String) = Unit
    }

    fun addKeyLogin(id: String) = serverAction(id, "Adding key login…") { s ->
        val out = withContext(Dispatchers.IO) { manager.install(s, ServerToolCatalog.KEY_LOGIN, InstallOptions(), quiet) }
        save(out.server)
        emit(ServersEvent.Message(out.summary))
        emit(ServersEvent.Success)
    }

    fun setPasswordLogin(id: String, off: Boolean) = serverAction(id, if (off) "Turning password login off…" else "Turning password login on…") { s ->
        val updated = withContext(Dispatchers.IO) { manager.setPasswordLogin(s, off, quiet) }
        save(updated)
        emit(ServersEvent.Message(if (off) "Password login is off. Only this phone's key can sign in." else "Password login is on again."))
    }

    fun restart(id: String, toolId: String) = serverAction(id, "Restarting ${toolTitle(toolId)}…") { s ->
        save(withContext(Dispatchers.IO) { manager.restart(s, toolId, quiet) })
        emit(ServersEvent.Message("${toolTitle(toolId)} restarted"))
    }

    fun logs(id: String, toolId: String) = serverAction(id, "Reading logs…") { s ->
        val lines = withContext(Dispatchers.IO) { manager.logs(s, toolId) }
        _state.update { it.copy(logs = "${toolTitle(toolId)} logs" to lines) }
    }

    fun rotatePort(id: String, toolId: String) = serverAction(id, "Moving ${toolTitle(toolId)} to a new port…") { s ->
        applyOutcome(withContext(Dispatchers.IO) { manager.rotatePort(s, toolId, quiet) })
        emit(ServersEvent.Message("New port set; your config was updated"))
    }

    fun rotateKey(id: String, toolId: String) = serverAction(id, "Creating new keys…") { s ->
        applyOutcome(withContext(Dispatchers.IO) { manager.rotateKey(s, toolId, quiet) })
        emit(ServersEvent.Message("New keys set; your config was updated"))
    }

    fun uninstall(id: String, toolId: String) = serverAction(id, "Removing ${toolTitle(toolId)}…") { s ->
        val updated = withContext(Dispatchers.IO) { manager.uninstall(s, toolId, quiet) }
        val tool = s.tool(toolId)
        tool?.profileId?.let { pid -> runCatching { RayApplication.instance.serverRepository.delete(pid) } }
        if (toolId == ServerToolCatalog.XUI) {
            tool?.panelId?.let { pid -> PanelStore(getApplication()).delete(pid) }
            emit(ServersEvent.PanelsChanged)
        }
        save(updated)
        emit(ServersEvent.Message("${toolTitle(toolId)} removed"))
    }

    fun remove(id: String) {
        store.delete(id)
        _state.update { it.copy(servers = it.servers.filterNot { s -> s.id == id }, route = ServersRoute.None) }
    }

    fun test(id: String, toolId: String) = serverAction(id, "Testing ${toolTitle(toolId)} from this phone…") { s ->
        val profile = profileFor(s, toolId) ?: error("No config for this tool on this phone")
        val result = testProfile(profile)
        _state.update { it.copy(tests = it.tests + ("${s.id}/$toolId" to result)) }
    }

    fun panelIdFor(id: String): String? = server(id)?.tool(ServerToolCatalog.XUI)?.panelId

    private suspend fun profileFor(s: ManagedServer, toolId: String): VlessProfile? {
        val t = s.tool(toolId) ?: return null
        val repo = RayApplication.instance.serverRepository
        return t.profileId?.let { repo.getProfileById(it) } ?: when (toolId) {
            ServerToolCatalog.DNSTT -> ToolProfiles.dnsttProfile(s, t)
            ServerToolCatalog.HYSTERIA2 -> ToolProfiles.hysteriaProfile(s, t)
            else -> null
        }
    }

    /** A real request through the profile: sidecar engines in an isolated run, Xray configs through the tester. */
    private suspend fun testProfile(profile: VlessProfile): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val engine = Sidecars.forProfile(profile)
        if (engine != null) {
            val r = EngineProbe.test(getApplication(), engine, profile, 25)
            when {
                r.stage.carriesTraffic -> true to "Real request passed in ${r.latencyMs} ms through the tunnel"
                r.notTested -> false to "Not tested: ${r.failure.orEmpty()}"
                else -> false to "No answer through the tunnel yet: ${r.failure.orEmpty()}. New DNS records can take a few minutes."
            }
        } else {
            when (val st = com.example.vpn.ServerTester.testServer(profile, 8_000).status) {
                is ServerTestStatus.Available -> true to "Real request passed in ${st.latencyMs} ms"
                is ServerTestStatus.Slow -> true to "Passed, slowly (${st.latencyMs} ms)"
                is ServerTestStatus.Unavailable -> false to "Failed: ${st.reason}"
                is ServerTestStatus.InvalidConfig -> false to "Config problem: ${st.error}"
                else -> false to "Not tested"
            }
        }
    }

    /** Saves an install's server and puts its config in the profile list (replacing the old one). */
    private suspend fun applyOutcome(out: InstallOutcome): ManagedServer {
        var server = out.server
        val profile = out.profile
        val tool = out.tool
        if (profile != null && tool != null) {
            val repo = RayApplication.instance.serverRepository
            tool.profileId?.let { old -> runCatching { repo.delete(old) } }
            val (inserted, duplicates) = repo.insertAllWithDeduplication(listOf(profile))
            val saved = inserted.firstOrNull() ?: duplicates.firstOrNull()?.let { repo.getProfileByFingerprint(it.effectiveFingerprint) }
            if (saved != null) server = server.withTool(tool.copy(profileId = saved.id))
        }
        out.panel?.let { panel ->
            val panels = PanelStore(getApplication())
            panels.load().filter { it.type == panel.type && it.host.equals(panel.host, true) }.forEach { panels.delete(it.id) }
            panels.save(panel)
            emit(ServersEvent.PanelsChanged)
        }
        save(server)
        return server
    }

    // ------------------------------------------------------------ install wizard

    fun openWizard(toolId: String, serverId: String? = null) {
        val tool = ServerToolCatalog.byId(toolId) ?: return
        if (tool.comingLater) return
        _state.update { it.copy(route = ServersRoute.Wizard, wizard = WizardUi(tool, rows()), error = "") }
        val fitting = _state.value.servers.filter { tool.fitsOn(it.location) && it.canSignIn }
        val pick = serverId ?: fitting.singleOrNull()?.id
        if (pick != null) pickServer(pick)
    }

    fun pickServer(id: String) {
        val s = server(id) ?: return
        val w = _state.value.wizard ?: return
        val existing = s.tool(w.tool.id)
        _state.update {
            it.copy(wizard = w.copy(
                serverId = id, stage = WizardStage.CHECK, checks = null, reinstall = existing != null,
                baseDomain = existing?.settings?.get(ToolProfiles.DNSTT_BASE).orEmpty(),
                tunnelLabel = existing?.settings?.get(ToolProfiles.DNSTT_LABEL) ?: "t",
                port = "", renew = false, error = ""
            ).withRecords(s))
        }
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { manager.check(s) } }
            r.onSuccess { checked ->
                save(checked)
                _state.update { st -> st.copy(wizard = st.wizard?.copy(checks = InstallChecks.forTool(w.tool, checked, checked.facts!!))) }
            }.onFailure { e ->
                _state.update { st -> st.copy(wizard = st.wizard?.copy(
                    checks = listOf(com.example.panels.servers.CheckItem("Signed in over SSH", false, e.message.orEmpty())),
                    error = "")) }
            }
        }
    }

    private fun WizardUi.withRecords(s: ManagedServer?): WizardUi =
        if (tool.id == ServerToolCatalog.DNSTT && s != null && DnsDelegation.problem(baseDomain, tunnelLabel) == null)
            copy(records = DnsDelegation.records(baseDomain, tunnelLabel, s.host))
        else copy(records = emptyList())

    fun changeWizard(ui: WizardUi) = _state.update { it.copy(wizard = ui.withRecords(server(ui.serverId))) }

    fun wizardBack() {
        val w = _state.value.wizard ?: return close()
        val prev = when (w.stage) {
            WizardStage.CHECK -> if (_state.value.servers.count { w.tool.fitsOn(it.location) } > 1) WizardStage.SERVER else null
            WizardStage.SETTINGS -> WizardStage.CHECK
            WizardStage.SUMMARY -> if (hasSettings(w)) WizardStage.SETTINGS else WizardStage.CHECK
            WizardStage.RUNNING -> return
            else -> null
        }
        if (prev == null) close() else _state.update { it.copy(wizard = w.copy(stage = prev)) }
    }

    private fun hasSettings(w: WizardUi) = w.tool.id in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.HYSTERIA2)

    fun wizardNext() {
        val w = _state.value.wizard ?: return
        val next = when (w.stage) {
            WizardStage.CHECK -> if (hasSettings(w)) WizardStage.SETTINGS else WizardStage.SUMMARY
            WizardStage.SETTINGS -> WizardStage.SUMMARY
            else -> return
        }
        _state.update { it.copy(wizard = w.copy(stage = next)) }
    }

    fun randomPort() {
        val w = _state.value.wizard ?: return
        val f = server(w.serverId)?.facts
        val port = ServerRandom.port((f?.udpPorts.orEmpty()) + (f?.tcpPorts.orEmpty()))
        _state.update { it.copy(wizard = w.copy(port = port.toString())) }
    }

    fun checkDns() {
        val w = _state.value.wizard ?: return
        if (w.records.size != 2 || w.dnsChecking) return
        _state.update { it.copy(wizard = w.copy(dnsChecking = true)) }
        viewModelScope.launch {
            val checks = withContext(Dispatchers.IO) {
                val (a, ns) = w.records
                DnsDelegation.judge(w.records, doh(a.name, a.type), doh(ns.name, "NS"))
            }
            _state.update { it.copy(wizard = it.wizard?.copy(dnsChecks = checks, dnsChecking = false)) }
        }
    }

    /** A DoH JSON answer from Cloudflare, else Google; null when neither answered. */
    private fun doh(name: String, type: String): String? {
        val q = "name=${URLEncoder.encode(name, "UTF-8")}&type=$type"
        for (url in listOf("https://1.1.1.1/dns-query?$q", "https://dns.google/resolve?$q")) {
            val body = runCatching {
                http.newCall(Request.Builder().url(url).header("Accept", "application/dns-json").build()).execute().use {
                    if (it.isSuccessful) it.body?.string() else null
                }
            }.getOrNull()
            if (body != null) return body
        }
        return null
    }

    fun install() {
        val w = _state.value.wizard ?: return
        val s = server(w.serverId) ?: return
        val steps = when (w.tool.id) {
            ServerToolCatalog.DNSTT -> ToolScripts.DNSTT_STEPS
            ServerToolCatalog.HYSTERIA2 -> ToolScripts.HYSTERIA_STEPS
            ServerToolCatalog.XUI -> manager.xuiSteps
            ServerToolCatalog.KEY_LOGIN -> listOf("Create a key on this phone") + ToolScripts.KEY_STEPS + "Sign in with the key"
            ServerToolCatalog.FIREWALL -> ToolScripts.FIREWALL_STEPS
            ServerToolCatalog.FAIL2BAN -> ToolScripts.FAIL2BAN_STEPS
            ServerToolCatalog.AUTO_UPDATES -> ToolScripts.UPDATES_STEPS
            ServerToolCatalog.BBR -> ToolScripts.BBR_STEPS
            else -> ToolScripts.SWAP_STEPS
        }
        _state.update { it.copy(wizard = w.copy(stage = WizardStage.RUNNING, steps = steps, currentStep = 1, log = emptyList(), error = "")) }
        val progress = object : ActionProgress {
            override fun step(index: Int) {
                val offset = if (w.tool.id == ServerToolCatalog.KEY_LOGIN) 1 else 0
                _state.update { st -> st.copy(wizard = st.wizard?.copy(currentStep = (index + offset).coerceAtMost(steps.size))) }
            }
            override fun log(line: String) {
                _state.update { st -> st.copy(wizard = st.wizard?.let { it.copy(log = (it.log + line).takeLast(60)) }) }
            }
        }
        val options = InstallOptions(baseDomain = w.baseDomain, tunnelLabel = w.tunnelLabel.ifBlank { "t" },
            port = w.port.toIntOrNull(), renew = w.renew)
        job = viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.IO) { manager.install(s, w.tool.id, options, progress) }
                val saved = applyOutcome(out)
                _state.update { st ->
                    st.copy(wizard = st.wizard?.copy(
                        stage = WizardStage.DONE, currentStep = steps.size + 1, summary = out.summary,
                        link = out.link, profileName = out.profile?.name, serverId = saved.id,
                        records = if (w.tool.id == ServerToolCatalog.DNSTT) DnsDelegation.records(w.baseDomain, w.tunnelLabel, saved.host) else emptyList()
                    ))
                }
                emit(ServersEvent.Success)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { st -> st.copy(wizard = st.wizard?.copy(stage = WizardStage.FAILED, error = e.message ?: "The install stopped")) }
            }
        }
    }

    fun retry() {
        val w = _state.value.wizard ?: return
        _state.update { it.copy(wizard = w.copy(stage = WizardStage.SUMMARY, error = "")) }
    }

    fun wizardTest() {
        val w = _state.value.wizard ?: return
        val s = server(w.serverId) ?: return
        _state.update { it.copy(wizard = w.copy(testing = true, testResult = null)) }
        viewModelScope.launch {
            val r = runCatching { profileFor(s, w.tool.id)?.let { testProfile(it) } ?: (false to "No config to test") }
                .getOrElse { false to (it.message ?: "Test failed") }
            _state.update { st ->
                st.copy(wizard = st.wizard?.copy(testing = false, testResult = r.second, testOk = r.first),
                    tests = st.tests + ("${s.id}/${w.tool.id}" to r))
            }
        }
    }

    fun openServerFromWizard() {
        val id = _state.value.wizard?.serverId ?: return
        _state.update { it.copy(wizard = null) }
        openDetail(id)
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    companion object {
        fun locationLabel(l: ServerLocation) = if (l == ServerLocation.IRAN) "Iran" else "Abroad"
    }
}
