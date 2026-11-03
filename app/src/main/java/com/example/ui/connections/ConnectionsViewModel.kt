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
class ConnectionsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RayApplication
    private val prefs = app.getSharedPreferences("galaxy_connections", 0)
    data class Options(val direct: Boolean = false, val transport: String = "auto", val family: String = "auto",
        val variant: String = "auto", val fragment: Boolean = true, val vpnFallback: Boolean = false)
    data class UiState(val options: Options, val working: Boolean = false, val message: String? = null,
        val result: AccessProbe.Result? = null, val identity: Boolean = false)
    private val _ui = MutableStateFlow(UiState(Options(prefs.getBoolean("direct", false),
        prefs.getString("transport", "auto").orEmpty().takeIf { it in AetherSidecar.transports } ?: "auto",
        prefs.getString("family", "auto").orEmpty().takeIf { it in AetherSidecar.families } ?: "auto",
        prefs.getString("variant", "auto").orEmpty().takeIf { it in ServerlessSidecar.variants } ?: "auto",
        prefs.getBoolean("fragment", true), prefs.getBoolean("vpnFallback", false))))
    val ui = _ui.asStateFlow()
    private var operation: Job? = null
    private var lastNetwork: String? = null
    private var fallbackAttempt: String? = null
    init {
        refreshIdentity()
        viewModelScope.launch {
            VpnController.connectionState.collect { state ->
                val network = com.example.vpn.smart.NetworkKey.current(app)
                if (network != lastNetwork) { lastNetwork = network; _ui.update { it.copy(result = null) } }
                if (state.status == ConnectionStatus.FAILED && state.activeProfile?.id == "maximus-serverless" &&
                    _ui.value.options.vpnFallback && !state.attemptId.isNullOrBlank() && state.attemptId != fallbackAttempt) {
                    fallbackAttempt = state.attemptId
                    _ui.update { it.copy(message = "Direct access failed. Switching to Aether as enabled.") }
                    VpnController.startVpn(app, AetherSidecar.profile(_ui.value.options.transport, _ui.value.options.family,
                        _ui.value.options.fragment), exact = true)
                }
                if (state.status == ConnectionStatus.CONNECTED || state.status == ConnectionStatus.FAILED) refreshIdentity()
            }
        }
    }
    fun options(change: (Options) -> Options) {
        if (_ui.value.working) return
        val o = change(_ui.value.options)
        require(o.transport in AetherSidecar.transports && o.family in AetherSidecar.families && o.variant in ServerlessSidecar.variants)
        prefs.edit().putBoolean("direct", o.direct).putString("transport", o.transport).putString("family", o.family)
            .putString("variant", o.variant).putBoolean("fragment", o.fragment).putBoolean("vpnFallback", o.vpnFallback).apply()
        _ui.update { it.copy(options = o, result = null, message = null) }
        refreshIdentity()
    }
    fun profile(): VlessProfile = _ui.value.options.let { if (it.direct) ServerlessSidecar.profile(it.variant, it.family)
        else AetherSidecar.profile(it.transport, it.family, it.fragment) }
    fun bundled(): Boolean = Sidecars.forProfile(profile())?.let(Sidecars::isBundled) == true
    fun cancel() { operation?.cancel(); operation = null }
    private fun refreshIdentity() {
        val name = if (_ui.value.options.transport == "wireguard") "wireguard.toml" else "identity.toml"
        val file = File(app.noBackupFilesDir, "identities/aether/$name")
        _ui.update { it.copy(identity = file.isFile && file.length() > 0) }
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
            catch (_: Exception) { _ui.update { it.copy(message = "Operation failed. The previous identity and connection settings were kept.") } }
            finally { _ui.update { it.copy(working = false) }; refreshIdentity() }
        }
    }
    fun testAccess() = task {
        val profile = profile()
        val result = runInterruptible(Dispatchers.IO) {
            val engine = Sidecars.forProfile(profile) ?: error("No engine")
            val executable = Sidecars.executable(engine) ?: error("Engine unavailable")
            val dir = File(app.noBackupFilesDir, "connection-tests/${Sidecars.randomToken(6)}").apply { mkdirs() }
            var started: ConnectionEngineRunner.Started? = null
            try {
                val ctx = SidecarContext(dir, executable, Sidecars.freeLoopbackPort(), Sidecars.randomToken(9),
                    Sidecars.randomToken(), app.settingsRepository.getSettings().operationalMode)
                started = ConnectionEngineRunner.start(engine, profile, app.settingsRepository.getSettings(), ctx)
                started.check
            } finally { try { started?.running?.stop() } finally { dir.deleteRecursively() } }
        }
        _ui.update { it.copy(result = result, message = "DNS, TLS and real data passed through this method.") }
    }
    fun renewIdentity() = task {
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
                    _ui.value.options.fragment), settings, ctx)
                val args = launch.command.toMutableList()
                for (flag in listOf("--config", "--masque-config", "--wg-config")) {
                    val at = args.indexOf(flag); require(at >= 0); args[at + 1] = File(staging, "identity.toml").absolutePath
                }
                args += listOf("--register", if (_ui.value.options.transport == "wireguard") "wg" else "masque")
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
                val name = if (_ui.value.options.transport == "wireguard") "wireguard.toml" else "identity.toml"
                val target = AtomicFile(File(identityDir, name))
                val out = target.startWrite()
                try { fresh.inputStream().use { it.copyTo(out) }; target.finishWrite(out) }
                catch (e: Exception) { target.failWrite(out); throw e }
                identityDir.listFiles()?.filter { it.name.contains("lastconn") }?.forEach { it.delete() }
            } finally { process?.let { if (isRunning(it)) EngineProcessStop.stop(it) }; staging.deleteRecursively() }
        }
        _ui.update { it.copy(message = "New WARP identity registered and saved.") }
    }
    private fun isRunning(process: Process) = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }
}
