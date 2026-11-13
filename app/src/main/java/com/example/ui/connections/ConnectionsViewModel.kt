package com.example.ui.connections

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import com.example.vpn.VpnController
import com.example.vpn.connections.AccessProbe
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.sidecar.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Owns setup and diagnostics, while RayVpnService owns connected sessions and their kill switch. */
class ConnectionsViewModel(application: Application, private val direct: Boolean) : AndroidViewModel(application) {
    private val app = application as RayApplication
    private val prefs = app.getSharedPreferences("galaxy_connections", 0)
    data class Options(val transport: String = "auto", val family: String = "auto",
        val variant: String = "auto", val fragment: Boolean = true, val vpnFallback: Boolean = false,
        val advanced: AetherSettings = AetherSettings(), val resolver: String = "auto")
    data class UiState(val options: Options, val working: Boolean = false, val message: String? = null,
        val result: AccessProbe.Result? = null, val identity: Boolean = false,
        val wireguardIdentity: Boolean = false, val masqueIdentity: Boolean = false,
        val keyStatus: Map<String, Boolean> = emptyMap(), val exitNodes: List<VlessProfile> = emptyList(),
        val log: List<String> = emptyList())
    private fun readOptions() = Options(
        prefs.getString("transport", "auto").orEmpty().takeIf { it in AetherSidecar.transports } ?: "auto",
        prefs.getString(if (direct) "direct_family" else "aether_family", prefs.getString("family", "auto")).orEmpty().takeIf { it in AetherSidecar.families } ?: "auto",
        prefs.getString("variant", "auto").orEmpty().takeIf { it in ServerlessSidecar.variants } ?: "auto",
        prefs.getBoolean("fragment", true), prefs.getBoolean("vpnFallback", false),
        AetherSettings.read(org.json.JSONObject(prefs.getString(if (direct) "direct_advanced" else "aether_advanced", "{}") ?: "{}")),
        prefs.getString("direct_resolver", "auto").orEmpty().takeIf { it in ServerlessSidecar.resolvers } ?: "auto")
    private val _ui = MutableStateFlow(UiState(readOptions()))
    val ui = _ui.asStateFlow()
    private var operation: Job? = null
    private val networkEpoch = java.util.concurrent.atomic.AtomicLong()
    private val connectivity = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { invalidateChecks() }
        override fun onLost(network: android.net.Network) { invalidateChecks() }
        override fun onLinkPropertiesChanged(network: android.net.Network, properties: android.net.LinkProperties) {
            invalidateChecks()
        }
    }
    private fun invalidateChecks() { networkEpoch.incrementAndGet(); _ui.update { it.copy(result = null) } }
    init {
        refreshIdentity()
        viewModelScope.launch {
            val nodes = withContext(Dispatchers.IO) { app.serverRepository.getAllProfilesOnce() }
            _ui.update { it.copy(exitNodes = nodes.filter(BuiltinExitNode::eligible)) }
        }
        viewModelScope.launch {
            com.example.xray.XrayLogManager.logsFlow.collect { lines ->
                val engine = if (direct) "serverless" else "aether"
                val relevant = lines.filter { it.contains("[BUILTIN] $engine/") }.takeLast(40)
                if (relevant.isNotEmpty()) _ui.update { it.copy(log = relevant) }
            }
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        viewModelScope.launch {
            VpnController.connectionState.collect { state ->
                if (state.status != ConnectionStatus.DISCONNECTED) {
                    operation?.cancel()
                    invalidateChecks()
                }
                if (state.status == ConnectionStatus.CONNECTED || state.status == ConnectionStatus.FAILED) refreshIdentity()
            }
        }
    }
    fun refreshOptions() {
        val latest = readOptions()
        _ui.update { if (it.options == latest) it else it.copy(options = latest, result = null, message = null) }
        refreshIdentity()
    }
    fun options(change: (Options) -> Options) {
        if (_ui.value.working || VpnController.connectionState.value.status != ConnectionStatus.DISCONNECTED) return
        val o = change(_ui.value.options)
        require(o.transport in AetherSidecar.transports && o.family in AetherSidecar.families && o.variant in ServerlessSidecar.variants && o.resolver in ServerlessSidecar.resolvers)
        prefs.edit().apply {
            if (direct) {
                putString("direct_family", o.family)
                putString("direct_resolver", o.resolver)
                putString("variant", o.variant)
                putBoolean("vpnFallback", o.vpnFallback)
                if (o.advanced.problem() == null) putString("direct_advanced", o.advanced.write(org.json.JSONObject()).toString())
            } else {
                putString("aether_family", o.family)
                putString("transport", o.transport)
                putBoolean("fragment", o.fragment)
                // Incomplete text belongs to this editor, never to a service or fallback launch.
                if (o.advanced.problem() == null) {
                    putString("aether_advanced", o.advanced.write(org.json.JSONObject()).toString())
                }
            }
        }.apply()
        _ui.update { it.copy(options = o, result = null, message = o.advanced.problem(if (direct) null else o.transport)) }
        refreshIdentity()
    }
    fun commandPreview(): String {
        val o = _ui.value.options
        if (direct) return "serverless run -c <private-config> · ${o.variant} · ${o.family} · DNS ${o.resolver}"
        o.advanced.problem(o.transport)?.let { return "Complete the settings: $it" }
        val reverse = o.advanced.tor == "reverse" || o.advanced.psiphon == "reverse"
        val carrier = if (o.transport == "auto" || reverse && o.transport == "h3") "h2" else o.transport
        val command = mutableListOf("aether", "--bind", "127.0.0.1:<local-port>", "--scan", o.advanced.scan,
            "--quick-reconnect", "--tls-verify")
        command += when (carrier) {
            "h3" -> listOf("--masque", "--h3")
            "wireguard" -> listOf("--wg")
            "gool" -> listOf("--gool", "--h2")
            "wiw" -> listOf("--gool", "--gool-classic")
            "mim" -> listOf("--mim", "--h2")
            else -> listOf("--masque", "--h2")
        }
        command += when (o.family) { "ipv4" -> "-4"; "ipv6" -> "-6"; else -> "--dual" }
        command += o.advanced.arguments(carrier, o.fragment)
        if (o.advanced.tor != "off") command += when(o.advanced.tor) { "chain" -> "--tor"; "reverse" -> "--tor-reverse"; else -> "--tor-only" }
        if (o.advanced.psiphon != "off") command += when(o.advanced.psiphon) { "chain" -> "--psiphon"; "reverse" -> "--psiphon-reverse"; else -> "--psiphon-only" }
        if (BuiltinExitNode.needed(o.advanced)) command += listOf("--upstream", "<private-local-exit>")
        return command.joinToString(" ")
    }

    fun profile(): VlessProfile = _ui.value.options.let { if (direct) ServerlessSidecar.profile(it.variant, it.family, it.vpnFallback, it.resolver, it.advanced)
        else AetherSidecar.profile(it.transport, it.family, it.fragment, it.advanced) }
    fun bundled(): Boolean = Sidecars.isBundled(if (direct) ServerlessSidecar else AetherSidecar)
    fun cancel() { operation?.cancel() }
    private fun refreshIdentity() {
        val root = File(app.noBackupFilesDir, "identities/aether")
        val keys = AetherSettings.keyKinds.associateWith { AetherIdentityStore.hasStored(root, it) }
        _ui.update { it.copy(wireguardIdentity = keys["wg"] == true,
            masqueIdentity = keys["masque"] == true,
            identity = keys[AetherIdentityStore.kindForTransport(it.options.transport)] == true,
            keyStatus = keys) }
    }

    private fun task(block: suspend () -> Unit) {
        if (_ui.value.working) return
        if (VpnController.connectionState.value.status != ConnectionStatus.DISCONNECTED) {
            _ui.update { it.copy(message = "Disconnect the current session before setup or diagnostics.") }; return
        }
        operation = viewModelScope.launch {
            _ui.update { it.copy(working = true, message = null, result = null, log = emptyList()) }
            try { block() }
            catch (e: CancellationException) {
                _ui.update { it.copy(message = "Operation cancelled; stored keys were preserved.") }
                throw e
            }
            catch (e: ConnectionEngineRunner.NoVerifiedPath) {
                _ui.update { it.copy(message = e.message, result = e.lastCheck) }
            }
            catch (e: RegistrationFailure) {
                com.example.xray.XrayLogManager.i("BUILTIN", "aether/keys · FAIL · ${e.message}")
                _ui.update { it.copy(message = e.message, log = (it.log + e.message.orEmpty()).takeLast(40)) }
            }
            catch (_: Exception) {
                _ui.update { it.copy(message = "Setup failed. Check engine availability or WARP registration and try again.") }
            }
            finally { _ui.update { it.copy(working = false) }; refreshIdentity() }
        }
    }
    fun testAccess() = task {
        _ui.value.options.advanced.problem(if (direct) null else _ui.value.options.transport)?.let {
            _ui.update { state -> state.copy(message = it) }; return@task
        }
        val original = profile()
        val profile = original.copy(extraSettings = org.json.JSONObject(original.extraSettings).put("rescan", true).toString())
        val testedEpoch = networkEpoch.get()
        val result = identityRenewal.withLock {
            runInterruptible(Dispatchers.IO) {
                val engine = Sidecars.forProfile(profile) ?: error("No engine")
                val executable = Sidecars.executable(engine) ?: error("Engine unavailable")
                val dir = File(app.noBackupFilesDir, "connection-tests/${Sidecars.randomToken(6)}").apply { mkdirs() }
                var started: ConnectionEngineRunner.Started? = null
                try {
                    val ctx = SidecarContext(dir, executable, Sidecars.freeLoopbackPort(), Sidecars.randomToken(9),
                        Sidecars.randomToken(), app.settingsRepository.getSettings().operationalMode)
                    // The LAB's fresh reading of this same link: on a Cloudflare-filtered network WireGuard goes
                    // first and MASQUE gets its short window, exactly as Connect does.
                    val hints = ConnectionEngineRunner.linkHints(com.example.vpn.smart.NetworkEnvironment.freshEvidence(
                        com.example.vpn.smart.NetworkCapabilityDetector.last,
                        com.example.vpn.smart.NetworkIdentity.current(app), System.currentTimeMillis()))
                    started = ConnectionEngineRunner.start(engine, profile, app.settingsRepository.getSettings(), ctx, checkDns = true,
                        ipv4Only = hints.ipv4Only, preferWireGuard = hints.cloudflareFiltered,
                        onProgress = { line -> _ui.update { it.copy(message = line, log = (it.log + line).takeLast(40)) } })
                    started.check
                } finally { try { started?.running?.stop() } finally { dir.deleteRecursively() } }
            }
        }
        _ui.update {
            if (networkEpoch.get() == testedEpoch) it.copy(result = result, message = "HTTPS data passed through the local engine.")
            else it.copy(result = null, message = "Network changed during the test. Run access checks again.")
        }
    }
    fun renewIdentity(kind: String = AetherIdentityStore.kindForTransport(_ui.value.options.transport)) = task {
        _ui.value.options.advanced.problem(_ui.value.options.transport)?.let {
            _ui.update { state -> state.copy(message = it) }; return@task
        }
        require(kind in AetherSettings.keyKinds)
        AetherIdentityRegistrar.lock.withLock {
            runInterruptible(Dispatchers.IO) {
                val engine = AetherSidecar
                val executable = Sidecars.executable(engine) ?: error("Engine unavailable")
                val identityDir = File(app.noBackupFilesDir, "identities/aether").apply { mkdirs() }
                val token = Sidecars.randomToken(9)
                val staging = File(identityDir, "pending-$token").apply { mkdirs() }
                AetherIdentityStore.retainUnchanged(identityDir, staging, kind)
                var process: Process? = null
                var exit: BuiltinExitNode.Started? = null
                var api: BuiltinExitNode.Started? = null
                try {
                    val settings = app.settingsRepository.getSettings()
                    val ctx = SidecarContext(staging, executable, Sidecars.freeLoopbackPort(), Sidecars.randomToken(9),
                        Sidecars.randomToken(), settings.operationalMode)
                    val launch = engine.prepare(AetherSidecar.profile("h2", _ui.value.options.family,
                        _ui.value.options.fragment, _ui.value.options.advanced), settings, ctx)
                    val args = launch.command.toMutableList()
                    for ((flag, name) in listOf("--config" to "base.toml", "--masque-config" to "identity.toml", "--wg-config" to "wireguard.toml")) {
                        val at = args.indexOf(flag)
                        require(at >= 0)
                        args[at + 1] = File(staging, name).absolutePath
                    }
                    args += listOf("--register", kind)
                    exit = BuiltinExitNode.start(_ui.value.options.advanced, settings, ctx)
                    api = AetherApiBridge.start(AetherSidecar.profile("h2", _ui.value.options.family,
                        _ui.value.options.fragment, _ui.value.options.advanced), settings, ctx)
                    process = ProcessBuilder(args).directory(staging).redirectErrorStream(true).apply {
                        environment().putAll(launch.environment)
                        exit?.let { environment()["AETHER_UPSTREAM"] = it.upstream }
                        api?.let {
                            environment()["MAXIMUS_API_UPSTREAM"] = it.upstream
                            it.apiHttp?.let { value -> environment()["MAXIMUS_API_HTTP"] = value }
                            it.apiToken?.let { value -> environment()["MAXIMUS_API_HTTP_TOKEN"] = value }
                        }
                    }.start()
                    process.outputStream.close()
                    val child = process
                    val failure = java.util.concurrent.atomic.AtomicReference("WARP registration did not complete")
                    val reader = Thread({
                        runCatching { child.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                SidecarProcess.reportApiTlsEvent(line)
                                if (SidecarProcess.isFailureLine(line))
                                    failure.set(SidecarProcess.preferFailure(failure.get(), line))
                            }
                        } }
                    }, "aether-identity").apply { isDaemon = true; start() }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 240_000
                    while (isRunning(child) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                    if (isRunning(child)) throw RegistrationFailure("WARP registration timed out")
                    reader.join(500)
                    if (child.exitValue() != 0) throw RegistrationFailure(failure.get())
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    check(VpnController.connectionState.value.status == ConnectionStatus.DISCONNECTED) {
                        "Connection started during identity renewal"
                    }
                    api?.running?.stop()
                    api = null
                    exit?.running?.stop()
                    exit = null
                    AetherIdentityStore.publish(identityDir, staging, kind, token)
                    com.example.xray.XrayLogManager.i("BUILTIN", "aether/keys · $kind · PASS")
                } finally {
                    try { process?.let { if (isRunning(it)) EngineProcessStop.stop(it) } }
                    finally { try { api?.running?.stop() } finally { try { exit?.running?.stop() } finally { staging.deleteRecursively() } } }
                }
            }
        }
        _ui.update { it.copy(message = "Requested WARP identities registered and activated.") }
    }
    override fun onCleared() {
        operation?.cancel()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }
    private class RegistrationFailure(message: String) : IllegalStateException(message)
    private fun isRunning(process: Process) = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }
}
