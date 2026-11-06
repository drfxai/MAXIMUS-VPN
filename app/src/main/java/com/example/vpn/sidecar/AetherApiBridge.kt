package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.connections.AccessProbe
import com.example.vpn.verify.Socks5
import com.example.xray.XrayLogManager
import java.io.File

/** Account HTTPS only: never puts the WARP transport itself inside Direct. */
object AetherApiBridge {
    fun allowed(options: AetherSettings) = options.apiRoute != "native" &&
        !BuiltinExitNode.needed(options) && options.tor == "off" && options.psiphon == "off"

    fun keyKind(profile: VlessProfile) = when (ProfileExtras.read(profile).optString("carrier")) {
        "wireguard" -> "wg"; "wiw" -> "gool-classic"; "mim" -> "mim"; "gool" -> "gool"; else -> "masque"
    }
    fun hasStoredKeys(profile: VlessProfile): Boolean {
        val root = AetherSidecar.identityDirectory?.invoke() ?: return false
        return AetherIdentityStore.required(keyKind(profile)).all {
            File(AetherIdentityStore.current(root), it).let { file -> file.isFile && file.length() >= 32 }
        }
    }
    fun bootstrapBlocked(cause: String) = cause.startsWith("WARP API registration failed") &&
        listOf("TLS", "certificate", "timed out").any { cause.contains(it, ignoreCase = true) }
    fun skipAfterBootstrapFailure(profile: VlessProfile, failedKinds: Set<String>): Boolean =
        failedKinds.isNotEmpty() && (keyKind(profile) in failedKinds || !hasStoredKeys(profile))

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
        val launch = ServerlessSidecar.prepare(direct, settings, context)
        val process = SidecarProcess.start("aether-api-oob", launch, context.workDir, context.socksPort)
        try {
            check(process.awaitReady(launch.readyTimeoutMs)) { "Direct API bridge did not start" }
            if (options.apiRoute == "auto" && !AccessProbe.check(
                    Socks5.Endpoint("127.0.0.1", context.socksPort, context.socksUser, context.socksPass), false).traffic) {
                XrayLogManager.i("BUILTIN", "aether/api · Direct OOB unverified; using native API route")
                process.stop()
                return null
            }
            XrayLogManager.i("BUILTIN", "aether/api · Direct OOB bootstrap selected; CA and hostname checks enabled")
            return BuiltinExitNode.Started(process,
                "socks5://${context.socksUser}:${context.socksPass}@127.0.0.1:${context.socksPort}")
        } catch (e: Throwable) {
            process.stop()
            if (options.apiRoute == "auto" && e is Exception && e !is InterruptedException &&
                e !is java.util.concurrent.CancellationException) {
                XrayLogManager.i("BUILTIN", "aether/api · Direct OOB bootstrap unavailable; using native API route")
                return null
            }
            throw e
        }
    }
}
