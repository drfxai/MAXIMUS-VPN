package com.example.ui.connections

import android.app.Application
import android.util.AtomicFile
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
        val wireguardIdentity: Boolean = false, val masqueIdentity: Boolean = false)
    private fun readOptions() = Options(
        prefs.getString("transport", "auto").orEmpty().takeIf { it in AetherSidecar.transports } ?: "auto",
        prefs.getString(if (direct) "direct_family" else "aether_family", prefs.getString("family", "auto")).orEmpty().takeIf { it in AetherSidecar.families } ?: "auto",
        prefs.getString("variant", "auto").orEmpty().takeIf { it in ServerlessSidecar.variants } ?: "auto",
        prefs.getBoolean("fragment", true), prefs.getBoolean("vpnFallback", false),
        AetherSettings.read(org.json.JSONObject(prefs.getString("aether_advanced", "{}") ?: "{}")),
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
        val o = change(readOptions())
        require(o.transport in AetherSidecar.transports && o.family in AetherSidecar.families && o.variant in ServerlessSidecar.variants && o.resolver in ServerlessSidecar.resolvers)
        prefs.edit().putString("transport", o.transport).putString(if (direct) "direct_family" else "aether_family", o.family)
            .putString("aether_advanced", o.advanced.write(org.json.JSONObject()).toString())
            .putString("direct_resolver", o.resolver).putString("variant", o.variant).putBoolean("fragment", o.fragment).putBoolean("vpnFallback", o.vpnFallback).apply()
        _ui.update { it.copy(options = o, result = null, message = if (direct) null else o.advanced.problem()) }
        refreshIdentity()
    }
    fun profile(): VlessProfile = _ui.value.options.let { if (direct) ServerlessSidecar.profile(it.variant, it.family, it.vpnFallback, it.resolver)
        else AetherSidecar.profile(it.transport, it.family, it.fragment, it.advanced) }
    fun bundled(): Boolean = Sidecars.isBundled(if (direct) ServerlessSidecar else AetherSidecar)
    fun cancel() { operation?.cancel() }
    private fun refreshIdentity() {
        val dir = File(app.noBackupFilesDir, "identities/aether")
        fun present(name: String) = File(dir, name).let { it.isFile && it.length() > 0 }
        val wg = present("wireguard.toml")
        val masque = present("identity.toml")
        _ui.update { it.copy(wireguardIdentity = wg, masqueIdentity = masque,
            identity = if (it.options.transport in listOf("wireguard", "wiw")) wg else masque) }
    }
    private fun task(block: suspend () -> Unit) {
        if (_ui.value.working) return
        if (VpnController.connectionState.value.status != ConnectionStatus.DISCONNECTED) {
            _ui.update { it.copy(message = "Disconnect the current session before setup or diagnostics.") }; return
        }
        operation = viewModelScope.launch {
            _ui.update { it.copy(working = true, message = null, result = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: ConnectionEngineRunner.NoVerifiedPath) {
                _ui.update { it.copy(message = e.message, result = e.lastCheck) }
            }
            catch (_: Exception) {
                _ui.update { it.copy(message = "Setup failed. Check engine availability or WARP registration and try again.") }
            }
            finally { _ui.update { it.copy(working = false) }; refreshIdentity() }
        }
    }
    fun testAccess() = task {
        if (!direct) _ui.value.options.advanced.problem()?.let { error(it) }
        val original = profile()
        val profile = original.copy(extraSettings = org.json.JSONObject(original.extraSettings).put("rescan", true).toString())
        val testedEpoch = networkEpoch.get()
        val result = runInterruptible(Dispatchers.IO) {
            val engine = Sidecars.forProfile(profile) ?: error("No engine")
            val executable = Sidecars.executable(engine) ?: error("Engine unavailable")
            val dir = File(app.noBackupFilesDir, "connection-tests/${Sidecars.randomToken(6)}").apply { mkdirs() }
            var started: ConnectionEngineRunner.Started? = null
            try {
                val ctx = SidecarContext(dir, executable, Sidecars.freeLoopbackPort(), Sidecars.randomToken(9),
                    Sidecars.randomToken(), app.settingsRepository.getSettings().operationalMode)
                started = ConnectionEngineRunner.start(engine, profile, app.settingsRepository.getSettings(), ctx, checkDns = true)
                started.check
            } finally { try { started?.running?.stop() } finally { dir.deleteRecursively() } }
        }
        _ui.update {
            if (networkEpoch.get() == testedEpoch) it.copy(result = result, message = "HTTPS data passed through the local engine.")
            else it.copy(result = null, message = "Network changed during the test. Run access checks again.")
        }
    }
    fun renewIdentity(kind: String = if (_ui.value.options.transport in listOf("wireguard", "wiw")) "wg" else "masque") = task {
        require(kind in listOf("wg", "masque"))
        runInterruptible(Dispatchers.IO) {
            val engine = AetherSidecar
            val executable = Sidecars.executable(engine) ?: error("Engine unavailable")
            val identityDir = File(app.noBackupFilesDir, "identities/aether").apply { mkdirs() }
            val staging = File(identityDir, "pending-${Sidecars.randomToken(6)}").apply { mkdirs() }
            var process: Process? = null
            try {
                val settings = app.settingsRepository.getSettings()
                val ctx = SidecarContext(staging, executable, Sidecars.freeLoopbackPort(), Sidecars.randomToken(9),
                    Sidecars.randomToken(), settings.operationalMode)
                val launch = engine.prepare(AetherSidecar.profile(_ui.value.options.transport, _ui.value.options.family,
                    _ui.value.options.fragment, _ui.value.options.advanced), settings, ctx)
                val args = launch.command.toMutableList()
                for (flag in listOf("--config", "--masque-config", "--wg-config")) {
                    val at = args.indexOf(flag); require(at >= 0); args[at + 1] = File(staging, "identity.toml").absolutePath
                }
                args += listOf("--register", kind)
                process = ProcessBuilder(args).directory(staging).redirectErrorStream(true).apply {
                    environment().putAll(launch.environment)
                }.start()
                process.outputStream.close()
                val child = process
                val reader = Thread({ runCatching { child.inputStream.use { input -> val b = ByteArray(1024); while(input.read(b) >= 0) {} } } },
                    "aether-identity").apply { isDaemon = true; start() }
                val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
                while (isRunning(child) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                check(!isRunning(child) && child.exitValue() == 0)
                reader.join(500)
                val fresh = File(staging, "identity.toml"); check(fresh.isFile && fresh.length() in 32..131072)
                val name = if (kind == "wg") "wireguard.toml" else "identity.toml"
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                check(VpnController.connectionState.value.status == ConnectionStatus.DISCONNECTED) {
                    "Connection started during identity renewal"
                }
                val target = AtomicFile(File(identityDir, name))
                val out = target.startWrite()
                try { fresh.inputStream().use { it.copyTo(out) }; target.finishWrite(out) }
                catch (e: Exception) { target.failWrite(out); throw e }
                identityDir.listFiles()?.filter { it.name.contains("lastconn") }?.forEach { it.delete() }
            } finally { process?.let { if (isRunning(it)) EngineProcessStop.stop(it) }; staging.deleteRecursively() }
        }
        _ui.update { it.copy(message = "New WARP identity registered and saved.") }
    }
    override fun onCleared() {
        operation?.cancel()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }
    private fun isRunning(process: Process) = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }
}
