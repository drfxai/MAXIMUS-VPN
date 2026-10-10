package com.example.ui.panels.servers

import com.example.data.model.VlessProfile
import com.example.panels.servers.ActionProgress
import com.example.panels.servers.CheckItem
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.ServerToolCatalog
import com.example.panels.servers.tunnel.TunnelChecks
import com.example.panels.servers.tunnel.TunnelConfigs
import com.example.panels.servers.tunnel.TunnelManager
import com.example.panels.servers.tunnel.TunnelPorts
import com.example.panels.servers.tunnel.TunnelScripts
import com.example.panels.servers.tunnel.TunnelSpec
import com.example.panels.servers.tunnel.TunnelStatus
import com.example.panels.servers.tunnel.TunnelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TunnelRoute {
    data object None : TunnelRoute
    data object Setup : TunnelRoute
    data class Detail(val iranId: String) : TunnelRoute
}

data class TunnelState(
    val route: TunnelRoute = TunnelRoute.None,
    val setup: TunnelSetupUi? = null,
    /** Iran server id -> last status read on it. */
    val status: Map<String, TunnelStatus> = emptyMap(),
    val checking: Set<String> = emptySet(),
    val busy: String = "",
    val error: String = "",
    val logs: List<String>? = null,
    val testing: Set<String> = emptySet(),
    /** Iran server id -> (passed, text) of the last phone test. */
    val tests: Map<String, Pair<Boolean, String>> = emptyMap()
)

/**
 * Maximus Tunnel for the Panels screens: the Tunnel tab, the setup and the tunnel page. A tunnel is
 * stored as the tunnel tool on both of its servers (see [TunnelSpec]); the phone's config lives in
 * the normal server list. All SSH work runs on the IO dispatcher.
 */
class TunnelController(
    private val scope: CoroutineScope,
    private val servers: () -> List<ManagedServer>,
    private val rows: () -> List<ServerRowUi>,
    private val save: (ManagedServer) -> Unit,
    private val message: (String) -> Unit,
    private val success: () -> Unit,
    private val testProfile: suspend (VlessProfile) -> Pair<Boolean, String>,
    /** Puts [VlessProfile] in the server list, replacing the profile with the old id; returns the new id. */
    private val storeProfile: suspend (String?, VlessProfile) -> String?,
    private val deleteProfile: suspend (String) -> Unit,
    private val manager: TunnelManager
) {
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()
    private var job: Job? = null
    private var checks: TunnelChecks? = null

    private fun server(id: String?) = servers().firstOrNull { it.id == id }

    private fun specOf(s: ManagedServer?): TunnelSpec? = s?.tool(ServerToolCatalog.MAXIMUS_TUNNEL)?.let { TunnelSpec.fromSettings(it.settings) }

    /** The Iran server of every tunnel with its spec and the server abroad. */
    private fun tunnels(): List<Triple<ManagedServer, TunnelSpec, ManagedServer>> = servers()
        .filter { it.location == ServerLocation.IRAN }
        .mapNotNull { iran ->
            val spec = specOf(iran)?.takeIf { it.iranId == iran.id } ?: return@mapNotNull null
            val abroad = server(spec.abroadId) ?: return@mapNotNull null
            Triple(iran, spec, abroad)
        }

    // ------------------------------------------------------------------ tab and cards

    fun tabUi(): TunnelTabUi {
        val all = servers()
        val list = tunnels()
        val freeIran = all.count { it.location == ServerLocation.IRAN && specOf(it) == null }
        val freeAbroad = all.count { it.location == ServerLocation.ABROAD && specOf(it) == null }
        return TunnelTabUi(
            tunnels = list.map { (iran, spec, abroad) -> card(iran, spec, abroad) },
            hasIran = all.any { it.location == ServerLocation.IRAN },
            hasAbroad = all.any { it.location == ServerLocation.ABROAD },
            canAddMore = freeIran > 0 && freeAbroad > 0
        )
    }

    private fun card(iran: ManagedServer, spec: TunnelSpec, abroad: ManagedServer): TunnelCardUi {
        val st = _state.value
        val status = st.status[iran.id]
        val total = spec.transports.sumOf { if (it in TunnelTransport.ROTATING && spec.rotationHours > 0) 2 else 1 }
        val health = when {
            iran.id in st.checking -> Health.CHECKING
            status == null -> Health.CHECKING
            !status.running || status.working == 0 -> Health.OFFLINE
            else -> Health.ONLINE
        }
        return TunnelCardUi(
            iranId = iran.id, iranName = iran.displayName, iranHost = iran.host,
            abroadName = abroad.displayName, abroadHost = abroad.host,
            health = health, latencyMs = status?.best?.let { status.paths[it] },
            transports = TunnelTransport.entries.filter { it in spec.transports },
            rotationText = if (spec.rotationHours > 0) "Ports move every ${spec.rotationHours} h" else "Fixed ports",
            working = status?.working, total = status?.paths?.size ?: total
        )
    }

    private fun paths(spec: TunnelSpec, status: TunnelStatus?): List<TunnelPathUi> {
        val now = System.currentTimeMillis()
        val best = status?.best
        return TunnelTransport.entries.filter { it in spec.transports }.flatMap { t ->
            if (t !in TunnelTransport.ROTATING) {
                val port = if (t == TunnelTransport.HYSTERIA2) spec.hyPort else spec.entryPort
                listOf(TunnelPathUi(t, port, false, status?.paths?.get(t.tagPrefix), status != null && t.tagPrefix in status.paths, best == t.tagPrefix))
            } else {
                val ports = TunnelPorts.current(spec, t, now)
                ports.mapIndexed { i, port ->
                    val tag = "${t.tagPrefix}-${if (i == 0) "now" else "prev"}"
                    TunnelPathUi(t, port, i > 0, status?.paths?.get(tag), status != null && tag in status.paths, best == tag)
                }
            }
        }
    }

    // ------------------------------------------------------------------ tunnel page

    fun openDetail(iranId: String) {
        _state.update { it.copy(route = TunnelRoute.Detail(iranId), logs = null, error = "") }
        val known = _state.value.status[iranId]
        if (known == null || System.currentTimeMillis() - known.checkedAt > 60_000) check(iranId)
    }

    fun detailUi(iranId: String): TunnelDetailUi? {
        val iran = server(iranId) ?: return null
        val spec = specOf(iran) ?: return null
        val abroad = server(spec.abroadId) ?: return null
        val st = _state.value
        val status = st.status[iranId]
        val next = TunnelPorts.nextChangeMs(spec.rotationHours)
        val test = st.tests[iranId]
        return TunnelDetailUi(
            card = card(iran, spec, abroad),
            paths = paths(spec, status),
            checking = iranId in st.checking,
            checkedText = status?.let { "Checked ${ago(it.checkedAt)} from the Iran server" }.orEmpty(),
            entryPort = spec.entryPort,
            rotationHours = spec.rotationHours,
            nextChangeText = next?.let { "Next change in ${duration(it - System.currentTimeMillis())}" } ?: "Move them any time",
            entrySni = spec.entrySni, exitSni = spec.exitSni,
            profileName = TunnelConfigs.name(iran, abroad),
            link = runCatching { TunnelConfigs.entryLink(spec, iran, abroad) }.getOrNull(),
            busy = st.busy, error = st.error,
            testing = iranId in st.testing, testResult = test?.second, testOk = test?.first,
            logs = st.logs
        )
    }

    fun check(iranId: String) {
        val iran = server(iranId) ?: return
        if (iranId in _state.value.checking) return
        _state.update { it.copy(checking = it.checking + iranId, error = "") }
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { manager.status(iran) } }
            _state.update { st ->
                st.copy(
                    checking = st.checking - iranId,
                    status = r.getOrNull()?.let { st.status + (iranId to it) } ?: st.status,
                    error = r.exceptionOrNull()?.message.orEmpty()
                )
            }
        }
    }

    /** Checks every tunnel once when the tab opens. */
    fun checkAll() = tunnels().forEach { (iran, _, _) -> if (_state.value.status[iran.id] == null) check(iran.id) }

    fun test(iranId: String) {
        val iran = server(iranId) ?: return
        val spec = specOf(iran) ?: return
        val abroad = server(spec.abroadId) ?: return
        _state.update { it.copy(testing = it.testing + iranId) }
        scope.launch {
            val r = runCatching { testProfile(TunnelConfigs.entryProfile(spec, iran, abroad)) }.getOrElse { false to (it.message ?: "Test failed") }
            _state.update { it.copy(testing = it.testing - iranId, tests = it.tests + (iranId to r)) }
            _state.update { st -> st.setup?.let { s -> st.copy(setup = s.copy(testing = false, testResult = r.second, testOk = r.first)) } ?: st }
        }
    }

    private fun action(iranId: String, busy: String, block: suspend (ManagedServer, TunnelSpec, ManagedServer) -> Unit) {
        val iran = server(iranId) ?: return
        val spec = specOf(iran) ?: return
        val abroad = server(spec.abroadId) ?: return
        if (_state.value.busy.isNotBlank()) return
        _state.update { it.copy(busy = busy, error = "") }
        job = scope.launch {
            try {
                block(iran, spec, abroad)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(error = e.message ?: "The action failed") }
            } finally {
                _state.update { it.copy(busy = "") }
            }
        }
    }

    fun reseed(iranId: String) = action(iranId, "Moving the ports on both servers…") { iran, spec, abroad ->
        val (i, a) = withContext(Dispatchers.IO) { manager.reseed(iran, abroad, spec) }
        save(i); save(a)
        message("Ports moved on both servers")
        check(iranId)
    }

    fun restart(iranId: String) = action(iranId, "Restarting both servers' tunnel…") { iran, _, abroad ->
        withContext(Dispatchers.IO) { manager.restart(abroad); manager.restart(iran) }
        message("Tunnel restarted")
        check(iranId)
    }

    fun logs(iranId: String) = action(iranId, "Reading logs…") { iran, _, _ ->
        val lines = withContext(Dispatchers.IO) { manager.logs(iran) }
        _state.update { it.copy(logs = lines) }
    }

    fun remove(iranId: String) = action(iranId, "Removing the tunnel…") { iran, spec, abroad ->
        val abroadResult = runCatching { withContext(Dispatchers.IO) { manager.uninstall(abroad, spec) } }
        save(abroadResult.getOrElse { abroad.withoutTool(ServerToolCatalog.MAXIMUS_TUNNEL) })
        save(withContext(Dispatchers.IO) { manager.uninstall(iran, spec) })
        iran.tool(ServerToolCatalog.MAXIMUS_TUNNEL)?.profileId?.let { runCatching { deleteProfile(it) } }
        _state.update { it.copy(route = TunnelRoute.None, status = it.status - iranId) }
        message(if (abroadResult.isSuccess) "Tunnel removed from both servers" else "Removed in Iran; the server abroad did not answer, remove it there later")
    }

    // ------------------------------------------------------------------ setup

    fun openSetup(editIranId: String? = null) {
        val all = rows()
        val iranRows = all.filter { it.location == ServerLocation.IRAN }
        val abroadRows = all.filter { it.location == ServerLocation.ABROAD }
        val editSpec = specOf(server(editIranId))
        checks = null
        val ui = TunnelSetupUi(
            iranRows = iranRows, abroadRows = abroadRows,
            iranId = editSpec?.iranId ?: iranRows.singleOrNull { it.health != Health.SIGN_IN && specOf(server(it.id)) == null }?.id,
            abroadId = editSpec?.abroadId ?: abroadRows.singleOrNull { it.health != Health.SIGN_IN && specOf(server(it.id)) == null }?.id,
            update = editSpec != null,
            transports = editSpec?.transports ?: TunnelTransport.entries.toSet(),
            rotationHours = editSpec?.rotationHours ?: 24,
            entrySni = editSpec?.entrySni.orEmpty(), exitSni = editSpec?.exitSni.orEmpty(),
            steps = manager.steps
        )
        _state.update { it.copy(route = TunnelRoute.Setup, setup = ui, error = "") }
        if (editSpec != null) next()
    }

    fun pickIran(id: String) = _state.update { st -> st.copy(setup = st.setup?.copy(iranId = id, checks = null)) }
    fun pickAbroad(id: String) = _state.update { st -> st.copy(setup = st.setup?.copy(abroadId = id, checks = null)) }
    fun change(ui: TunnelSetupUi) = _state.update { it.copy(setup = ui) }

    fun next() {
        val ui = _state.value.setup ?: return
        when (ui.stage) {
            TunnelStage.PICK -> {
                val iran = server(ui.iranId) ?: return
                val abroad = server(ui.abroadId) ?: return
                _state.update { it.copy(setup = ui.copy(stage = TunnelStage.CHECK, checks = null, error = "")) }
                scope.launch {
                    val r = runCatching { withContext(Dispatchers.IO) { manager.check(iran, abroad) } }
                    r.onSuccess { c ->
                        checks = c
                        save(c.iran); save(c.abroad)
                        val taken = listOfNotNull(
                            specOf(c.iran)?.takeIf { it.abroadId != abroad.id }?.let { CheckItem("Iran server is free", false, "it already has a tunnel to another server") },
                            specOf(c.abroad)?.takeIf { it.iranId != iran.id }?.let { CheckItem("Server abroad is free", false, "it already serves another Iran server") }
                        )
                        _state.update { st ->
                            st.copy(setup = st.setup?.let { s ->
                                s.copy(
                                    checks = c.items + taken,
                                    // A new tunnel starts with every way the check found open; an update keeps its choice.
                                    transports = if (s.update) s.transports else c.recommended,
                                    recommended = c.recommended,
                                    iranSites = c.iranSites, abroadSites = c.abroadSites,
                                    entrySni = s.entrySni.ifBlank { c.iranSites.firstOrNull() ?: TunnelScripts.IRAN_SNI.first() },
                                    exitSni = s.exitSni.ifBlank { c.abroadSites.firstOrNull() ?: TunnelScripts.ABROAD_SNI.first() }
                                )
                            })
                        }
                    }.onFailure { e ->
                        _state.update { st -> st.copy(setup = st.setup?.copy(checks = listOf(CheckItem("Signed in to both servers", false, e.message.orEmpty())))) }
                    }
                }
            }
            TunnelStage.CHECK -> if (ui.checksPass) _state.update { it.copy(setup = ui.copy(stage = TunnelStage.OPTIONS)) }
            else -> Unit
        }
    }

    fun back() {
        val ui = _state.value.setup ?: return close()
        when (ui.stage) {
            TunnelStage.RUNNING -> return
            TunnelStage.CHECK -> if (ui.update) close() else _state.update { it.copy(setup = ui.copy(stage = TunnelStage.PICK)) }
            TunnelStage.OPTIONS -> _state.update { it.copy(setup = ui.copy(stage = TunnelStage.CHECK)) }
            else -> close()
        }
    }

    fun install() {
        val ui = _state.value.setup ?: return
        val c = checks ?: return
        val iran = server(ui.iranId) ?: return
        val abroad = server(ui.abroadId) ?: return
        val previous = specOf(iran)?.takeIf { it.abroadId == abroad.id }
        _state.update { it.copy(setup = ui.copy(stage = TunnelStage.RUNNING, currentStep = 1, log = emptyList(), error = "")) }
        val progress = object : ActionProgress {
            override fun step(index: Int) = _state.update { st -> st.copy(setup = st.setup?.copy(currentStep = index)) }
            override fun log(line: String) = _state.update { st -> st.copy(setup = st.setup?.let { it.copy(log = (it.log + line).takeLast(80)) }) }
        }
        job = scope.launch {
            try {
                val planned = manager.plan(c, ui.transports, ui.rotationHours, previous).let { p ->
                    p.copy(entrySni = ui.entrySni.ifBlank { p.entrySni }, exitSni = ui.exitSni.ifBlank { p.exitSni })
                }
                val out = withContext(Dispatchers.IO) { manager.install(iran, abroad, planned, ui.renewKeys, progress) }
                val oldProfile = iran.tool(ServerToolCatalog.MAXIMUS_TUNNEL)?.profileId
                val profileId = storeProfile(oldProfile, out.profile)
                val savedIran = out.iran.withTool(out.iran.tool(ServerToolCatalog.MAXIMUS_TUNNEL)!!.copy(profileId = profileId))
                save(savedIran); save(out.abroad)
                val status = TunnelStatus(out.paths, emptyList(), running = true, serverTime = 0L)
                _state.update { st ->
                    st.copy(
                        status = if (out.paths.isEmpty()) st.status else st.status + (savedIran.id to status),
                        setup = st.setup?.copy(
                            stage = TunnelStage.DONE, currentStep = manager.steps.size + 1,
                            paths = paths(out.spec, status.takeIf { out.paths.isNotEmpty() }),
                            profileName = out.profile.name, link = out.link
                        )
                    )
                }
                success()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { st -> st.copy(setup = st.setup?.copy(stage = TunnelStage.FAILED, error = e.message ?: "The setup stopped")) }
            }
        }
    }

    fun retry() = _state.update { st -> st.copy(setup = st.setup?.copy(stage = TunnelStage.OPTIONS, error = "")) }

    fun testSetup() {
        val id = _state.value.setup?.iranId ?: return
        _state.update { st -> st.copy(setup = st.setup?.copy(testing = true, testResult = null)) }
        test(id)
    }

    fun openFromSetup() {
        val id = _state.value.setup?.iranId ?: return
        _state.update { it.copy(setup = null) }
        openDetail(id)
    }

    fun close() {
        if (_state.value.setup?.stage == TunnelStage.RUNNING) return
        _state.update { it.copy(route = TunnelRoute.None, setup = null, logs = null, error = "", busy = "") }
    }

    fun cancel() { job?.cancel() }

    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    private fun duration(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }
}
