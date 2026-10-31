package com.example.ui.panels.servers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.panels.PanelStore
import com.example.panels.cloudflare.CloudflareDnsApi
import com.example.panels.cloudflare.SmartDomainsStore
import com.example.panels.servers.ActionProgress
import com.example.panels.servers.DnsDelegation
import com.example.panels.servers.InstallChecks
import com.example.panels.servers.InstallOptions
import com.example.panels.servers.InstallOutcome
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.ServerFacts
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.ServerManager
import com.example.panels.servers.SmartOperation
import com.example.panels.servers.SmartOperationStore
import com.example.panels.servers.SmartNodeStore
import com.example.panels.servers.ManagedNodeRecord
import com.example.panels.servers.PrivatePsiphonServerScripts
import com.example.vpn.sidecar.PrivatePsiphonStore
import com.example.vpn.sidecar.PrivatePsiphonEntry
import com.example.vpn.smart.NetworkKey
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.panels.servers.ServerRandom
import com.example.panels.servers.ServerStore
import com.example.panels.servers.ServerToolCatalog
import com.example.panels.servers.ToolAdvisor
import com.example.panels.servers.ToolProfiles
import com.example.panels.servers.ToolScripts
import com.example.panels.servers.ServerBinaries
import com.example.panels.servers.panel.HiddifyPanelProvider
import com.example.panels.servers.panel.HiddifyScripts
import com.example.panels.servers.panel.PanelStatus
import com.example.panels.servers.tunnel.TunnelManager
import com.example.panels.servers.tunnel.TunnelSpec
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
    val tests: Map<String, Pair<Boolean, String>> = emptyMap(),
    /** Servers whose "Hiddify found" card was closed with Not now (this session only). */
    val foundDismissed: Set<String> = emptySet()
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
    private val operations = SmartOperationStore(app)
    private val managedNodes = SmartNodeStore(app)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(ServersState(servers = runCatching { store.load() }.getOrDefault(emptyList())))
    val state: StateFlow<ServersState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ServersEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ServersEvent> = _events
    private var job: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { operations.markInterrupted() }.onFailure {
                _state.update { state -> state.copy(error = "Cannot read secure operation history. Server mutations are disabled until storage is repaired.") }
            }
        }
    }

    fun managedNodesFor(serverId: String): List<ManagedNodeRecord> =
        runCatching { managedNodes.all().filter { it.serverId == serverId } }.getOrDefault(emptyList())

    private suspend fun recordManagedTest(server: ManagedServer, toolId: String, traffic: Boolean) {
        val profileId = server.tool(toolId)?.profileId ?: return
        withContext(Dispatchers.IO) {
            val exists = managedNodes.all().any {
                it.serverId == server.id && it.serviceId == toolId && it.profileId == profileId
            }
            if (exists) managedNodes.result(server.id, toolId, profileId, traffic,
                networkKey = NetworkKey.current(getApplication()))
        }
    }

    /** Save a private psiphond server entry in Keystore, without claiming the remote VPS is installed. */
    fun importPrivatePsiphonEntry(serverId: String, raw: String) {
        val server = server(serverId) ?: return emit(ServersEvent.Message("Select a saved VPS."))
        if (raw.isBlank() || raw.length > 262_144) {
            emit(ServersEvent.Message("Paste a bounded private psiphond server-entry.dat value."))
            return
        }
        viewModelScope.launch {
            try {
                val duplicate = withContext(Dispatchers.IO) {
                    managedNodes.all().any {
                        it.serverId == serverId && it.serviceId == PrivatePsiphonServerScripts.TOOL_ID
                    }
                }
                check(!duplicate) { "A private Psiphon profile is already registered for this VPS. Test or review it first." }
                val description = requireNotNull(PrivatePsiphonEntry.inspect(raw.trim())) {
                    "Invalid private Psiphon server entry; check the complete psiphond output."
                }
                if (PrivatePsiphonEntry.validIp(server.host)) {
                    check(description.ipAddress == server.host) {
                        "The server entry targets a different IP than the selected VPS. No profile was saved."
                    }
                }
                val id = withContext(Dispatchers.IO) { PrivatePsiphonStore(getApplication()).save(raw) }
                val profile = PsiphonSidecar.privateProfile(id, server.displayName, server.host)
                val stored = storeProfile(null, profile) ?: error("The private profile could not be saved.")
                withContext(Dispatchers.IO) {
                    managedNodes.configured(serverId, PrivatePsiphonServerScripts.TOOL_ID, stored)
                }
                emit(ServersEvent.Message("Private server entry encrypted and added to Nodes as unverified. Run the phone traffic test."))
                _state.update { it.copy(tests = it.tests - ("${serverId}/${PrivatePsiphonServerScripts.TOOL_ID}")) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                emit(ServersEvent.Message(com.example.panels.servers.ServerLogSanitizer.redact(
                    e.message ?: "Private Psiphon import failed.")))
            }
        }
    }

    fun testPrivatePsiphon(serverId: String) = serverAction(serverId, "Testing private Psiphon tunnel from this phone…") { s ->
        val record = withContext(Dispatchers.IO) {
            managedNodes.all().firstOrNull {
                it.serverId == serverId && it.serviceId == PrivatePsiphonServerScripts.TOOL_ID
            }
        } ?: error("Import a private Psiphon server entry first.")
        val profile = RayApplication.instance.serverRepository.getProfileById(record.profileId)
            ?: error("The imported profile no longer exists.")
        val test = testProfile(profile)
        withContext(Dispatchers.IO) {
            managedNodes.result(serverId, PrivatePsiphonServerScripts.TOOL_ID,
                record.profileId, test.first, networkKey = NetworkKey.current(getApplication()))
        }
        _state.update {
            it.copy(tests = it.tests + ("${serverId}/${PrivatePsiphonServerScripts.TOOL_ID}" to test))
        }
        emit(ServersEvent.Message(if (test.first) "Private Psiphon: real tunnel traffic verified."
            else "Private Psiphon not verified: ${test.second}"))
    }

    /** Local audited history: UNCERTAIN requires fresh inspection and manual reconciliation. */
    fun operationsFor(serverId: String): List<SmartOperation> =
        runCatching { operations.all().filter { it.serverId == serverId }.takeLast(10).reversed() }
            .getOrDefault(emptyList())

    fun reconcileUncertainOperation(serverId: String, operationId: String) = serverAction(
        serverId, "Inspecting server before acknowledging interrupted operation…"
    ) { s ->
        val op = withContext(Dispatchers.IO) { operations.all().firstOrNull { it.id == operationId } }
        require(op != null && op.serverId == serverId && op.status == SmartOperation.Status.UNCERTAIN)
        val fresh = withContext(Dispatchers.IO) { manager.check(s) }
        check(fresh.facts?.socketInspectionAvailable == true) { "Cannot safely inspect this VPS yet." }
        save(fresh)
        withContext(Dispatchers.IO) { operations.transition(op.id, SmartOperation.Status.RECONCILED) }
        emit(ServersEvent.Message("Operation acknowledged after SSH inspection; check installed services and profiles before retrying."))
    }

    /** Maximus Tunnel: the Tunnel tab, its setup and the tunnel page. */
    val tunnel = TunnelController(
        scope = viewModelScope,
        servers = { _state.value.servers },
        rows = ::rows,
        save = ::save,
        message = { emit(ServersEvent.Message(it)) },
        success = { emit(ServersEvent.Success) },
        testProfile = ::testProfile,
        storeProfile = ::storeProfile,
        deleteProfile = { RayApplication.instance.serverRepository.delete(it) },
        manager = TunnelManager(fetchProgram = ::downloadProgram)
    )

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
        if (_state.value.route is ServersRoute.Wizard &&
            (_state.value.wizard?.stage == WizardStage.RUNNING || _state.value.wizard?.dnsProvisioning == true)) return
        wizardTestGeneration++
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
            foundHiddify = f?.takeIf { it.hiddifyInstalled && s.tool(ServerToolCatalog.HIDDIFY) == null && id !in st.foundDismissed }
                ?.let { hiddifySummary(it) },
            advice = ToolAdvisor.suggest(s, f).map { AdviceUi(it.toolId, s.id, s.displayName, s.location, it.reason) },
            busy = st.busy, error = st.error,
            logsTitle = st.logs?.first.orEmpty(), logs = st.logs?.second
        )
    }

    private fun toolRow(s: ManagedServer, id: String, f: ServerFacts?, st: ServersState): ToolRowUi? {
        val t = s.tool(id) ?: return null
        val title = ServerToolCatalog.byId(id)?.title ?: id
        val units = ToolScripts.UNITS[id]
        val running = units == null || f != null && units.all { f.active(it) }
        val status = when {
            units == null -> "On"
            f == null -> "Unknown"
            running -> "Running"
            else -> "Stopped"
        }
        val test = st.tests["${s.id}/$id"]
        return when (id) {
            ServerToolCatalog.XUI -> ToolRowUi(id, title, status, running, t.port?.let { "Panel on port $it" }.orEmpty(),
                canRestart = true, canLogs = true, canOpenPanel = t.panelId != null,
                canBackup = true, canStart = f != null && !running, canStop = f != null && running)
            ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN -> ToolRowUi(id, title, status, running,
                t.settings[ToolProfiles.DNSTT_DOMAIN].orEmpty(),
                canTest = true, canRestart = true, canLogs = true, canStart = !running, canStop = running,
                link = runCatching {
                    if (id == ServerToolCatalog.SOAR) ToolProfiles.soarLink(s, t) else ToolProfiles.cottenLink(s, t)
                }.getOrNull(), testResult = test?.second, testOk = test?.first)
            ServerToolCatalog.DNSTT -> ToolRowUi(id, title, status, running, t.settings[ToolProfiles.DNSTT_DOMAIN].orEmpty(),
                canTest = true, canRestart = true, canLogs = true, canRotateKey = true,
                link = runCatching { ToolProfiles.dnsttLink(s, t) }.getOrNull(), testResult = test?.second, testOk = test?.first)
            ServerToolCatalog.HYSTERIA2 -> ToolRowUi(id, title, status, running, "UDP ${t.port} · Salamander",
                canTest = true, canRestart = true, canLogs = true, canRotatePort = true, canRotateKey = true,
                link = runCatching { ToolProfiles.hysteriaLink(s, t) }.getOrNull(), testResult = test?.second, testOk = test?.first)
            ServerToolCatalog.MAXIMUS_TUNNEL -> {
                val spec = TunnelSpec.fromSettings(t.settings)
                val peer = server(if (s.location == com.example.panels.servers.ServerLocation.IRAN) spec?.abroadId else spec?.iranId)
                ToolRowUi(id, title, status, running,
                    peer?.let { (if (s.location == com.example.panels.servers.ServerLocation.IRAN) "To " else "From ") + it.displayName }.orEmpty(),
                    canRestart = true, canLogs = true, canUninstall = false, canOpenTunnel = spec != null)
            }
            ServerToolCatalog.HIDDIFY -> hiddifyRow(t, f)
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

    private fun hiddifyUnits(f: ServerFacts) = f.services.filterKeys { it.startsWith("hiddify-") }

    private fun hiddifySummary(f: ServerFacts): String {
        val units = hiddifyUnits(f)
        return listOfNotNull(f.hiddifyVersion.ifBlank { null }?.let { "Version $it" },
            units.takeIf { it.isNotEmpty() }?.let { u -> "${u.count { it.value == "active" }} of ${u.size} services active" })
            .joinToString(" · ").ifBlank { "Files found; no services detected. Installation is unverified." }
    }

    private fun hiddifyRow(t: com.example.panels.servers.InstalledTool, f: ServerFacts?): ToolRowUi {
        val units = f?.let { hiddifyUnits(it) }.orEmpty()
        val active = units.count { it.value == "active" }
        val down = units.filterValues { it != "active" }.keys
        val version = f?.hiddifyVersion?.ifBlank { null } ?: t.settings[HiddifyPanelProvider.K_VERSION]
        val status = when {
            f == null -> "Unknown"
            units.isEmpty() -> "Needs repair"
            down.isEmpty() -> "Running"
            active == 0 -> "Stopped"
            else -> "${down.size} of ${units.size} down"
        }
        val backup = t.settings[ServerManager.K_LAST_BACKUP]?.toLongOrNull()
        return ToolRowUi(
            ServerToolCatalog.HIDDIFY, "Hiddify", status, healthy = down.isEmpty() && units.isNotEmpty(),
            detail = listOfNotNull(version?.let { "v$it" }, "ports 80, 443",
                if (t.settings[HiddifyPanelProvider.K_IMPORTED] == "1") "added from server" else null).joinToString(" · "),
            warn = active > 0 && down.isNotEmpty(),
            stats = if (units.isEmpty()) emptyList() else listOf(
                "Services" to "$active of ${units.size} active",
                "Last backup" to (backup?.let { whenText(it) } ?: "None from this phone")),
            warning = if (f != null && units.isEmpty()) "Hiddify files exist but no managed services were detected. Use Diagnostics or Remove Hiddify to inspect or recover this incomplete installation." else if (active > 0 && down.isNotEmpty())
                down.take(3).joinToString(" and ") + (if (down.size > 3) " and ${down.size - 3} more" else "") +
                    (if (down.size == 1) " is" else " are") + " not active. Restart, or open Logs to see why." else null,
            // The action can recover a missing admin link over SSH; never hide the entry point.
            canOpenPanel = true,
            canBackup = units.isNotEmpty(), canRestart = units.isNotEmpty(), canLogs = true,
            canUpdate = version != null && PanelStatus.compareVersions(HiddifyScripts.VERSION, version) > 0,
            canStart = f != null && active < units.size, canStop = active > 0
        )
    }

    private fun whenText(t: Long): String {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = t
        val time = String.format(Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        return if ((cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)) == today) "Today $time" else ago(t)
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
                _state.update { it.copy(error = com.example.panels.servers.ServerLogSanitizer.redact(e.message ?: "The action failed")) }
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
        emit(ServersEvent.Message(if (toolId == ServerToolCatalog.HIDDIFY)
            "Hiddify services, installation and panel data deleted." else "${toolTitle(toolId)} removed"))
    }

    fun backup(id: String, toolId: String) = serverAction(id, "Backing up ${toolTitle(toolId)}…") { s ->
        val (updated, path) = withContext(Dispatchers.IO) { manager.backupPanel(s, toolId, quiet) }
        save(updated)
        emit(ServersEvent.Message("Backup saved on the server: $path"))
    }

    fun updateHiddify(id: String) = serverAction(id, "Backing up, then updating Hiddify to ${HiddifyScripts.VERSION}. This can take 30 minutes…") { s ->
        val out = withContext(Dispatchers.IO) { manager.updateHiddify(s, quiet) }
        save(out.server)
        emit(ServersEvent.Message(out.summary))
        emit(ServersEvent.Success)
    }

    fun setRunning(id: String, toolId: String, run: Boolean) =
        serverAction(id, (if (run) "Starting " else "Stopping ") + toolTitle(toolId) + "…") { s ->
            save(withContext(Dispatchers.IO) { manager.setPanelRunning(s, toolId, run, quiet) })
        }

    fun importHiddify(id: String) = serverAction(id, "Adding Hiddify…") { s ->
        save(withContext(Dispatchers.IO) { manager.importHiddify(s) })
        emit(ServersEvent.Message("Hiddify files registered for management; service and traffic health are separate checks. Nothing on the server changed."))
        emit(ServersEvent.Success)
    }

    fun dismissFound(id: String) = _state.update { it.copy(foundDismissed = it.foundDismissed + id) }

    /** Hiddify's secret URL is stored only in the encrypted server store and never logged. */
    fun hiddifyAdminUrl(id: String): String? = HiddifyPanelProvider.validatedAdminUrl(
        server(id)?.tool(ServerToolCatalog.HIDDIFY)?.settings?.get(HiddifyPanelProvider.K_ADMIN_URL)
    )

    /** Recover a missing v14 admin URL from the server, without reinstalling Hiddify. */
    fun openHiddifyAdmin(id: String, onUrl: (String) -> Unit) {
        hiddifyAdminUrl(id)?.let { onUrl(it); return }
        serverAction(id, "Retrieving Hiddify admin link…") { server ->
            val updated = withContext(Dispatchers.IO) { manager.hiddify.restoreAdminUrl(server) }
            save(updated)
            onUrl(requireNotNull(hiddifyAdminUrl(id)) { "The recovered Hiddify link is invalid" })
        }
    }

    private fun hiddifyApi() = com.example.panels.servers.panel.HiddifyQuickSetup(
        http.newBuilder().dns(RayApplication.instance.subscriptionManager.managedPanelDns()).build())

    /** Explicit UI confirmation starts one creation; a pending UUID survives a lost response/restart. */
    fun quickHiddifyUser(id: String, name: String, days: Int, quotaGb: Int, onDone: () -> Unit) =
        serverAction(id, "Creating Hiddify user and importing subscription…") { original ->
            var current = original
            if (hiddifyAdminUrl(id) == null) {
                current = withContext(Dispatchers.IO) { manager.hiddify.restoreAdminUrl(current) }
                save(current)
            }
            val tool = requireNotNull(current.tool(ServerToolCatalog.HIDDIFY))
            val operation = tool.settings["quick_pending_user"] ?: java.util.UUID.randomUUID().toString()
            current = current.withTool(tool.copy(settings = tool.settings + ("quick_pending_user" to operation)))
            save(current)
            val userUrl = withContext(Dispatchers.IO) {
                hiddifyApi().createUser(
                    requireNotNull(hiddifyAdminUrl(id)), name, days, quotaGb, operation)
            }
            val updatedTool = requireNotNull(current.tool(ServerToolCatalog.HIDDIFY))
            // Persist the result before importing: a failed download must not create another account.
            save(current.withTool(updatedTool.copy(settings = (updatedTool.settings - "quick_pending_user") +
                ("quick_user_url" to userUrl))))
            onDone()
            importHiddifyUserSubscription(userUrl)
        }

    private suspend fun importHiddifyUserSubscription(userUrl: String) {
        val result = withContext(Dispatchers.IO) {
            RayApplication.instance.subscriptionManager.addAndSyncSubscription("Hiddify", "${userUrl}sub/")
        }
        check(result.isSuccess) { "User link saved; import failed. Use Import saved configs to retry. " + (result.errorMessage ?: "No subscription data") }
        emit(ServersEvent.Message("Hiddify subscription imported: ${result.addedCount} new nodes. Traffic is not yet verified."))
    }

    fun importSavedHiddify(id: String) = serverAction(id, "Importing saved Hiddify configs…") { s ->
        val link = s.tool(ServerToolCatalog.HIDDIFY)?.settings?.get("quick_user_url")
            ?: error("Create a user first, or paste your existing user subscription in Nodes > Add")
        importHiddifyUserSubscription(link)
    }

    fun hiddifyTelegram(id: String, userLink: String,
        onLinks: (List<com.example.panels.servers.panel.HiddifyQuickSetup.TelegramLink>) -> Unit) =
        serverAction(id, "Reading Telegram proxy links…") { s ->
            val link = userLink.ifBlank { s.tool(ServerToolCatalog.HIDDIFY)?.settings?.get("quick_user_url").orEmpty() }
            require(link.isNotBlank()) { "Paste a Hiddify user link, or create a user first" }
            val links = withContext(Dispatchers.IO) { hiddifyApi().telegram(link) }
            check(links.isNotEmpty()) { "The panel returned no Telegram proxies. Enable them in Hiddify first; CDN support is not implied." }
            onLinks(links)
        }

    fun remove(id: String) {
        store.delete(id)
        _state.update { it.copy(servers = it.servers.filterNot { s -> s.id == id }, route = ServersRoute.None) }
    }

    fun test(id: String, toolId: String) = serverAction(id, "Testing ${toolTitle(toolId)} from this phone…") { s ->
        val profile = profileFor(s, toolId) ?: error("No config for this tool on this phone")
        val result = withDelegation(s, toolId, testProfile(profile))
        recordManagedTest(s, toolId, result.first)
        _state.update { it.copy(tests = it.tests + ("${s.id}/$toolId" to result)) }
        if (!result.first && toolId in setOf(ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN, ServerToolCatalog.DNSTT)) {
            // Keep phone/engine evidence separate from a read-only server snapshot.
            try {
                val checked = withContext(Dispatchers.IO) { manager.check(s) }
                save(checked)
                val lines = withContext(Dispatchers.IO) { manager.logs(checked, toolId) }
                _state.update { it.copy(logs = "${toolTitle(toolId)} server diagnostics (phone test failed)" to lines) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val detail = com.example.panels.servers.ServerLogSanitizer.redact(e.message ?: "SSH unavailable")
                _state.update { it.copy(logs = "Server diagnostics unavailable" to listOf(detail)) }
            }
        }
    }

    fun panelIdFor(id: String): String? = server(id)?.tool(ServerToolCatalog.XUI)?.panelId

    private suspend fun profileFor(s: ManagedServer, toolId: String): VlessProfile? {
        val t = s.tool(toolId) ?: return null
        val repo = RayApplication.instance.serverRepository
        return t.profileId?.let { repo.getProfileById(it) } ?: when (toolId) {
            ServerToolCatalog.DNSTT -> ToolProfiles.dnsttProfile(s, t)
            ServerToolCatalog.SOAR -> ToolProfiles.soarProfile(s, t)
            ServerToolCatalog.COTTEN -> ToolProfiles.cottenProfile(s, t)
            ServerToolCatalog.HYSTERIA2 -> ToolProfiles.hysteriaProfile(s, t)
            else -> null
        }
    }

    /**
     * A failed DNS tunnel test with the DNS records that do not point at [s] in front: a delegation to
     * another address explains the failure on its own, before any engine, resolver or server setting.
     */
    private suspend fun withDelegation(s: ManagedServer, toolId: String, result: Pair<Boolean, String>): Pair<Boolean, String> {
        if (result.first || toolId !in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN)) return result
        val t = s.tool(toolId) ?: return result
        val base = t.settings[ToolProfiles.DNSTT_BASE] ?: return result
        val label = t.settings[ToolProfiles.DNSTT_LABEL] ?: return result
        if (DnsDelegation.problem(base, label) != null) return result
        val records = DnsDelegation.records(base, label, s.host, ownNameServer = t.settings[ToolProfiles.DNSTT_OWN_NS] == "1").take(2)
        val bad = withContext(Dispatchers.IO) {
            runCatching { DnsDelegation.judge(records, doh(records[0].name, records[0].type), doh(records[1].name, "NS")) }.getOrDefault(emptyList())
        }.filter { it.status == DnsDelegation.Status.WRONG || it.status == DnsDelegation.Status.MISSING }
        if (bad.isEmpty()) return result
        return false to (bad.joinToString(" ") { "DNS ${it.record.type} ${it.record.name}: ${it.detail}" } + " " + result.second)
    }

    /** A real request through the profile: sidecar engines in an isolated run, Xray configs through the tester. */
    private suspend fun testProfile(profile: VlessProfile): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val engine = Sidecars.forProfile(profile)
        if (engine != null) {
            val r = EngineProbe.test(getApplication(), engine, profile, 25)
            when {
                r.stage.carriesTraffic -> true to "Real request passed in ${r.latencyMs} ms through the tunnel"
                r.notTested -> false to "Not tested: ${r.failure.orEmpty()}"
                else -> false to "No verified traffic through this engine: ${r.failure.orEmpty()}. Check delegation, resolver transport, server authentication and egress; the cause is not established."
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

    /** The Iran server of the tunnel [serverId] belongs to. */
    fun tunnelIranIdFor(serverId: String): String? =
        server(serverId)?.tool(ServerToolCatalog.MAXIMUS_TUNNEL)?.let { TunnelSpec.fromSettings(it.settings)?.iranId }

    /** Puts [profile] in the profile list, replacing [oldId]; returns the saved profile's id. */
    private suspend fun storeProfile(oldId: String?, profile: VlessProfile): String? {
        val repo = RayApplication.instance.serverRepository
        // A reinstalled DNS tunnel keeps the resolvers and rate chosen on this phone.
        val previous = oldId?.let { runCatching { repo.getProfileById(it) }.getOrNull() }
        val stored = if (previous != null && com.example.vpn.sidecar.DnsttSidecar().handles(previous) &&
            com.example.vpn.sidecar.DnsttSidecar().handles(profile)) com.example.vpn.sidecar.DnsttSidecar.carryOver(previous, profile) else profile
        oldId?.let { old -> runCatching { repo.delete(old) } }
        val (inserted, duplicates) = repo.insertAllWithDeduplication(listOf(stored))
        return (inserted.firstOrNull() ?: duplicates.firstOrNull()?.let { repo.getProfileByFingerprint(it.effectiveFingerprint) })?.id
    }

    /** A pinned server program downloaded on this phone, for a server that cannot reach GitHub. Checked by the caller. */
    private fun downloadProgram(name: String): ByteArray? {
        val client = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
        val base = if (name in com.example.panels.servers.tunnel.SpoofCarrierScripts.hashes.keys.map { "spoof-linux-$it" })
            com.example.panels.servers.tunnel.SpoofCarrierScripts.BASE_URL else ServerBinaries.BASE_URL
        require(name in ServerBinaries.SHA256 || name in com.example.panels.servers.tunnel.SpoofCarrierScripts.hashes.keys.map { "spoof-linux-$it" })
        return client.newCall(Request.Builder().url("$base/$name").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            val limit = com.example.panels.servers.RemoteShell.MAX_TRANSFER
            if (body.contentLength() > limit) return null
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > limit) return null
                    out.write(buffer, 0, n)
                }
            }
            out.toByteArray()
        }
    }

    /** Saves an install's server and puts its config in the profile list (replacing the old one). */
    private suspend fun applyOutcome(out: InstallOutcome): ManagedServer {
        var server = out.server
        val profile = out.profile
        val tool = out.tool
        if (profile != null && tool != null) {
            storeProfile(tool.profileId, profile)?.let { server = server.withTool(tool.copy(profileId = it)) }
        }
        out.panel?.let { panel ->
            val panels = PanelStore(getApplication())
            panels.load().filter { it.type == panel.type && it.host.equals(panel.host, true) }.forEach { panels.delete(it.id) }
            panels.save(panel)
            emit(ServersEvent.PanelsChanged)
        }
        save(server)
        if (tool != null) {
            server.tool(tool.id)?.profileId?.let { profileId ->
                withContext(Dispatchers.IO) {
                    managedNodes.configured(server.id, tool.id, profileId)
                }
            }
        }
        return server
    }

    // ------------------------------------------------------------ install wizard

    fun openWizard(toolId: String, serverId: String? = null) {
        val tool = ServerToolCatalog.byId(toolId) ?: return
        if (tool.comingLater) return
        wizardTestGeneration++
        _state.update { it.copy(route = ServersRoute.Wizard, wizard = WizardUi(tool, rows()), error = "") }
        val fitting = _state.value.servers.filter { s -> tool.fitsOn(s.location) && s.canSignIn && tool.conflicts.none { s.tool(it) != null } }
        val pick = serverId ?: fitting.singleOrNull()?.id
        if (pick != null) pickServer(pick)
    }

    fun pickServer(id: String) {
        if (_state.value.wizard?.dnsProvisioning == true) return
        val s = server(id) ?: return
        val w = _state.value.wizard ?: return
        val existing = s.tool(w.tool.id)
        wizardTestGeneration++
        _state.update {
            it.copy(wizard = w.copy(
                serverId = id, stage = WizardStage.CHECK, checks = null, reinstall = existing != null,
                baseDomain = existing?.settings?.get(ToolProfiles.DNSTT_BASE).orEmpty(),
                tunnelLabel = existing?.settings?.get(ToolProfiles.DNSTT_LABEL) ?: "t",
                backupDomains = existing?.let { ToolProfiles.backupBases(it).joinToString(", ") }.orEmpty(),
                port = "", renew = false, error = ""
            ).withRecords(s))
        }
        // Reuse the user's already authorized Cloudflare domain automatically, when reachable.
        if (w.tool.id in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN) &&
            existing?.settings?.get(ToolProfiles.DNSTT_BASE).orEmpty().isBlank()) {
            viewModelScope.launch {
                val suggested = withContext(Dispatchers.IO) {
                    runCatching {
                        val dnsStore = SmartDomainsStore(getApplication())
                        val token = dnsStore.token().takeIf { it.isNotBlank() } ?: return@runCatching null
                        val zones = CloudflareDnsApi().zones(token)
                        com.example.panels.cloudflare.SmartFill.zone(zones.filter { it.status == "active" }, dnsStore.zoneId())?.name
                    }.getOrNull()
                }
                if (!suggested.isNullOrBlank()) {
                    _state.update { st ->
                        st.copy(wizard = st.wizard?.let { current ->
                            if (current.serverId == id && current.tool.id == w.tool.id &&
                                current.baseDomain.isBlank() && !current.dnsProvisioning)
                                current.copy(baseDomain = suggested).withRecords(s)
                            else current
                        })
                    }
                }
            }
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
        if (tool.id in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN) &&
            s != null && DnsDelegation.problem(baseDomain, tunnelLabel) == null)
            copy(records = DnsDelegation.records(baseDomain, tunnelLabel, s.host,
                backups.takeIf { tool.id == ServerToolCatalog.DNSTT && DnsDelegation.backupProblem(baseDomain, it) == null }.orEmpty(),
                ownNameServer))
        else copy(records = emptyList())

    // A DNS write operates on a frozen preview. Do not let the form change its target mid-write.
    fun changeWizard(ui: WizardUi) = _state.update { state ->
        if (state.wizard?.dnsProvisioning == true) state
        else {
            // A name server chosen for one domain or tunnel name does not carry over to another.
            val old = state.wizard
            val keepOwn = old != null && old.baseDomain == ui.baseDomain && old.tunnelLabel == ui.tunnelLabel
            state.copy(wizard = ui.copy(ownNameServer = ui.ownNameServer && keepOwn).withRecords(server(ui.serverId)))
        }
    }

    fun wizardBack() {
        val w = _state.value.wizard ?: return close()
        if (w.dnsProvisioning) return
        val prev = when (w.stage) {
            WizardStage.CHECK -> if (_state.value.servers.count { w.tool.fitsOn(it.location) } > 1) WizardStage.SERVER else null
            WizardStage.SETTINGS -> WizardStage.CHECK
            WizardStage.SUMMARY -> if (hasSettings(w)) WizardStage.SETTINGS else WizardStage.CHECK
            WizardStage.RUNNING -> return
            else -> null
        }
        if (prev == null) close() else _state.update { it.copy(wizard = w.copy(stage = prev)) }
    }

    private fun hasSettings(w: WizardUi) = w.tool.id in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN, ServerToolCatalog.HYSTERIA2)

    fun wizardNext() {
        val w = _state.value.wizard ?: return
        if (w.dnsProvisioning) return
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
        if (w.records.isEmpty() || w.records.size % 2 != 0 || w.dnsChecking) return
        _state.update { it.copy(wizard = w.copy(dnsChecking = true)) }
        viewModelScope.launch {
            // Each domain's pair of records: the name server's address, then the delegation.
            val checks = withContext(Dispatchers.IO) {
                w.records.chunked(2).flatMap { pair ->
                    val (a, ns) = pair
                    DnsDelegation.judge(pair, doh(a.name, a.type), doh(ns.name, "NS"))
                }
            }
            _state.update { it.copy(wizard = it.wizard?.copy(dnsChecks = checks, dnsChecking = false)) }
        }
    }

    /**
     * User-initiated one-tap DNSTT DNS delegation. Uses the existing Keystore-protected
     * Smart domains token; never creates/changes a token or switches the user's zone.
     * DNS records are preflighted across all required zones before writes begin.
     */
    fun provisionDns() {
        val w = _state.value.wizard ?: return
        if (w.tool.id !in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN) || w.records.isEmpty() ||
            w.dnsProvisioning || w.dnsChecking) return
        _state.update { it.copy(wizard = w.copy(dnsProvisioning = true, dnsProvisionResult = "")) }
        viewModelScope.launch {
            var records = w.records
            var own = w.ownNameServer
            var moved: CloudflareDnsApi.NameServerTaken? = null
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val token = SmartDomainsStore(getApplication()).token()
                    require(token.isNotBlank()) {
                        "First connect your Cloudflare DNS token in Panels > Cloudflare > Smart domains."
                    }
                    try {
                        CloudflareDnsApi().provisionDnstt(token, records)
                    } catch (taken: CloudflareDnsApi.NameServerTaken) {
                        // ns.<domain> belongs to another address. Leave it, and give this tunnel its own
                        // name server instead of delegating it to a server that does not run it.
                        val server = server(w.serverId)
                        if (own || server == null) throw taken
                        own = true
                        moved = taken
                        records = DnsDelegation.records(w.baseDomain, w.tunnelLabel, server.host,
                            if (w.tool.id == ServerToolCatalog.DNSTT) w.backups else emptyList(), ownNameServer = true)
                        CloudflareDnsApi().provisionDnstt(token, records)
                    }
                }
            }
            // Never apply a late result to a different server or a changed domain.
            val current = _state.value.wizard
            if (current?.serverId != w.serverId || current?.records != w.records) return@launch
            val note = moved?.let { "${it.name} already points at ${it.address}, not this server, so it was left as it is " +
                "and this tunnel uses its own name server ${records.first().name}. " }.orEmpty()
            val message = outcome.fold(
                onSuccess = { n -> note + "Created $n DNS records in Cloudflare (existing matching records kept). Check propagation, then test the tunnel." },
                onFailure = { e -> (if (e is CloudflareDnsApi.NameServerTaken) "${e.message}. Every tunnel delegated to ${e.name} goes to ${e.address}, " +
                    "not this server. Point it at this server in Cloudflare, or delete that record if the old server is gone."
                    else note + (e.message ?: "Cloudflare setup failed; check your token and domain permissions.")) }
            )
            _state.update { st ->
                st.copy(wizard = st.wizard?.let { cur -> cur.copy(dnsProvisioning = false, dnsProvisionResult = message,
                    records = records, ownNameServer = own, dnsChecks = if (records != w.records) null else cur.dnsChecks) })
            }
            if (outcome.isSuccess) checkDns()
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
        if (w.dnsProvisioning || w.stage != WizardStage.SUMMARY) return
        val s = server(w.serverId) ?: return
        val steps = when (w.tool.id) {
            ServerToolCatalog.DNSTT -> ToolScripts.DNSTT_STEPS
            ServerToolCatalog.SOAR -> com.example.panels.servers.DnsServerScripts.SOAR_STEPS
            ServerToolCatalog.COTTEN -> com.example.panels.servers.DnsServerScripts.COTTEN_STEPS
            ServerToolCatalog.HYSTERIA2 -> ToolScripts.HYSTERIA_STEPS
            ServerToolCatalog.XUI -> manager.xuiSteps
            ServerToolCatalog.HIDDIFY -> HiddifyScripts.INSTALL_STEPS
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
            backupBases = if (w.tool.id == ServerToolCatalog.DNSTT) w.backups else emptyList(),
            port = w.port.toIntOrNull(), renew = w.renew, ownNameServer = w.ownNameServer)
        job = viewModelScope.launch {
            var operation: SmartOperation? = null
            try {
                // Never trust cached or UI-only checks: freshly inspect over pinned SSH immediately
                // before mutating. Journal durable intent before running any install script.
                val inspected = withContext(Dispatchers.IO) { manager.check(s) }
                val facts = requireNotNull(inspected.facts)
                val blockers = InstallChecks.forTool(w.tool, inspected, facts).filter { it.blocking && !it.ok }
                check(blockers.isEmpty()) { blockers.joinToString("; ") { it.detail } }
                operation = withContext(Dispatchers.IO) {
                    operations.begin(s.id, w.tool.id, inspected.checkedAt)
                }
                val out = withContext(Dispatchers.IO) { manager.install(inspected, w.tool.id, options, progress) }
                withContext(Dispatchers.IO) {
                    operations.transition(requireNotNull(operation).id, SmartOperation.Status.VERIFYING)
                }
                val saved = applyOutcome(out)
                // COMPLETED means the approved install + local outcome persist succeeded,
                // not that the Android client has passed a traffic-through-tunnel test.
                withContext(Dispatchers.IO) {
                    operations.transition(requireNotNull(operation).id, SmartOperation.Status.COMPLETED)
                }
                _state.update { st ->
                    st.copy(wizard = st.wizard?.copy(
                        stage = WizardStage.DONE, currentStep = steps.size + 1, summary = out.summary,
                        link = out.link, profileName = out.profile?.name, serverId = saved.id,
                        records = if (w.tool.id in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN))
                            DnsDelegation.records(w.baseDomain, w.tunnelLabel, saved.host,
                                if (w.tool.id == ServerToolCatalog.DNSTT) w.backups else emptyList(), w.ownNameServer) else emptyList()
                    ))
                }
                // The delegation decides whether any query reaches the new server: show it on the result.
                if (w.tool.id in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN)) checkDns()
                emit(ServersEvent.Success)
            } catch (e: Exception) {
                // We cannot infer whether remote side effects happened from a thrown exception.
                // Block further installs until a human re-inspects and reconciles this operation.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    operation?.let { runCatching { operations.transition(it.id, SmartOperation.Status.UNCERTAIN) } }
                }
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { st -> st.copy(wizard = st.wizard?.copy(stage = WizardStage.FAILED,
                    error = com.example.panels.servers.ServerLogSanitizer.redact(e.message ?: "The install stopped"))) }
            }
        }
    }

    fun retry() {
        val w = _state.value.wizard ?: return
        _state.update { it.copy(wizard = w.copy(stage = WizardStage.SUMMARY, error = "")) }
    }

    private var wizardTestGeneration = 0L
    fun wizardTest() {
        val w = _state.value.wizard ?: return
        if (w.testing) return
        val s = server(w.serverId) ?: return
        val generation = ++wizardTestGeneration
        _state.update { it.copy(wizard = w.copy(testing = true, testResult = null, testOk = null)) }
        viewModelScope.launch {
            val r = try {
                profileFor(s, w.tool.id)?.let { withDelegation(s, w.tool.id, testProfile(it)) } ?: (false to "No config to test")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false to com.example.panels.servers.ServerLogSanitizer.redact(e.message ?: "Test failed")
            }
            val stillCurrent = _state.value.wizard?.let { current ->
                generation == wizardTestGeneration && current.serverId == w.serverId &&
                    current.tool.id == w.tool.id && current.link == w.link
            } == true
            if (stillCurrent) {
                runCatching { recordManagedTest(s, w.tool.id, r.first) }
                    .onFailure { emit(ServersEvent.Message("Managed-node verification record could not be updated.")) }
            }
            _state.update { st ->
                val current = st.wizard
                if (current == null || generation != wizardTestGeneration || current.serverId != w.serverId ||
                    current.tool.id != w.tool.id || current.link != w.link) st
                else st.copy(wizard = current.copy(testing = false, testResult = r.second, testOk = r.first),
                    tests = st.tests + ("${s.id}/${w.tool.id}" to r))
            }
        }
    }

    fun openServerFromWizard() {
        val id = _state.value.wizard?.serverId ?: return
        wizardTestGeneration++
        _state.update { it.copy(wizard = null) }
        openDetail(id)
    }

    override fun onCleared() {
        job?.cancel()
        tunnel.cancel()
        super.onCleared()
    }

    companion object {
        fun locationLabel(l: ServerLocation) = if (l == ServerLocation.IRAN) "Iran" else "Abroad"
    }
}
