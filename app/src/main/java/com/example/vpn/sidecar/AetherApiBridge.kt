package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.verify.Dialer
import com.example.vpn.verify.TrafficCheck
import com.example.vpn.verify.Socks5
import com.example.xray.XrayLogManager
import java.io.File

/** Account HTTPS only: never puts the WARP transport itself inside Direct. */
object AetherApiBridge {
    fun allowed(options: AetherSettings) = options.apiRoute != "native" &&
        !BuiltinExitNode.needed(options) && options.tor == "off" && options.psiphon == "off"

    fun keyKind(profile: VlessProfile) = AetherIdentityStore.kindForTransport(
        ProfileExtras.read(profile).optString("carrier"))
    fun hasStoredKeys(profile: VlessProfile): Boolean {
        val root = AetherSidecar.identityDirectory?.invoke() ?: return false
        return AetherIdentityStore.hasStored(root, keyKind(profile))
    }
    /** Distinguish shared API outages from a failure specific to MASQUE or WireGuard signup. */
    const val API_UNAVAILABLE = "api-unavailable"
    fun registrationFailed(cause: String) = cause.startsWith("WARP API registration failed")
    fun bootstrapBlocked(cause: String) = registrationFailed(cause) &&
        listOf("TLS", "certificate", "timed out", "network operation", "closed or refused",
            "rate limited", "API unreachable").any { cause.contains(it, ignoreCase = true) }
    fun skipAfterBootstrapFailure(profile: VlessProfile, failedKinds: Set<String>): Boolean =
        (API_UNAVAILABLE in failedKinds || keyKind(profile) in failedKinds) && !hasStoredKeys(profile)

    /** Check the actual WARP API TLS identity, not a generic website that may be reachable
     * through Direct OOB while both Cloudflare registration hosts are blocked.
     * An HTTP error status still proves TLS/HTTP reachability; it does not prove signup will work.
     */
    internal fun apiHostsForProbe(options: AetherSettings): List<String> {
        val selected = options.apiHost.ifBlank { options.apiSni }
        return if (selected.isNotBlank()) listOf(selected)
            else listOf("api.devices.cloudflare.com", "api.cloudflareclient.com")
    }

    private fun apiTlsReachable(options: AetherSettings, endpoint: Socks5.Endpoint): Boolean {
        val pinnedAddress = options.apiAddress.trim()
        val dialer = Dialer { host, port, timeout ->
            Socks5.connect(endpoint, pinnedAddress.ifBlank { host }, port, timeout)
        }
        val probe = TrafficCheck(dialer, stepTimeoutMs = 6_000)
        for (host in apiHostsForProbe(options)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                val response = probe.exchange(TrafficCheck.Target(host, 443, "/"), "HEAD", null, keepBody = false)
                if (response.status in 200..599) return true
            } catch (e: Exception) {
                if (e is InterruptedException || e is java.util.concurrent.CancellationException) throw e
                // The other authenticated hostname may still work.
            }
        }
        return false
    }

    fun start(profile: VlessProfile, settings: AppSettings, parent: SidecarContext,
        force: Boolean = false): BuiltinExitNode.Started? {
        val extras = ProfileExtras.read(profile)
        val options = AetherSettings.read(extras)
        if (!allowed(options)) return null
        if (!force && hasStoredKeys(profile)) return null
        val executable = Sidecars.executable(ServerlessSidecar)
        if (executable == null) {
            check(options.apiRoute == "auto") { "Direct API runtime is missing" }
            return null
        }
        val context = parent.copy(workDir = File(parent.workDir, "api-oob").apply { mkdirs() },
            executable = executable, socksPort = Sidecars.freeLoopbackPort(),
            socksUser = Sidecars.randomToken(9), socksPass = Sidecars.randomToken())
        val direct = ServerlessSidecar.profile("oob", extras.optString("family", "auto"))
        var started: RunningEngine? = null
        try {
            val launch = ServerlessSidecar.prepare(direct, settings, context)
            val process = SidecarProcess.start("aether-api-oob", launch, context.workDir, context.socksPort)
            started = process
            check(process.awaitReady(launch.readyTimeoutMs)) { "Direct API bridge did not start" }
            val endpoint = Socks5.Endpoint("127.0.0.1", context.socksPort, context.socksUser, context.socksPass)
            if (!apiTlsReachable(options, endpoint) || !process.isAlive) {
                if (options.apiRoute == "auto") {
                    XrayLogManager.i("BUILTIN", "aether/api · WARP API TLS unreachable via Direct OOB; trying native API route")
                    process.stop()
                    return null
                }
                error("WARP API TLS unreachable via forced Direct OOB route")
            }
            XrayLogManager.i("BUILTIN", "aether/api · Android TLS precheck passed via Direct OOB; native Aether registration still pending")
            return BuiltinExitNode.Started(process,
                "socks5://${context.socksUser}:${context.socksPass}@127.0.0.1:${context.socksPort}")
        } catch (e: Throwable) {
            started?.stop()
            if (options.apiRoute == "auto" && e is Exception && e !is InterruptedException &&
                e !is java.util.concurrent.CancellationException) {
                XrayLogManager.i("BUILTIN", "aether/api · Direct OOB bootstrap unavailable; using native API route")
                return null
            }
            throw e
        }
    }
}
