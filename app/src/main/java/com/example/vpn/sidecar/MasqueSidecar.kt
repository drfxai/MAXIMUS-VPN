package com.example.vpn.sidecar

import com.example.core.SecretRedactor
import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONObject
import java.io.File

/**
 * Cloudflare WARP carried over MASQUE (WireGuard tunnelled inside HTTP/3 / QUIC), run by the bundled
 * `usque` program. It reaches a non-Iran Cloudflare exit over port 443 QUIC, so it works where the
 * plain WireGuard UDP that ordinary WARP uses is blocked. The program offers a local SOCKS5 proxy and
 * the app only talks to it over that loopback port.
 *
 * The device registers itself with Cloudflare once (an ECDSA key pair made on the phone, only the
 * public half sent), the way the WARP registration does, and the result is kept in `usque`'s own
 * config file inside the engine's work directory and reused on later connects.
 *
 * This is only the HTTP/3 (QUIC) MASQUE path. An HTTP/2 MASQUE fallback would need the app to split the
 * TLS handshake itself, which it does not do, so it is not offered here.
 */
object MasqueSidecar : SidecarEngine {
    override val id = ID
    override val binary = BINARY

    override fun handles(profile: VlessProfile): Boolean =
        profile.protocolType == ProtocolType.MIXED && ProfileExtras.read(profile).optString(KEY_ENGINE) == ID

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        val config = File(context.workDir, CONFIG_FILE)
        if (!config.isFile || config.length() == 0L) register(context.executable, config)
        // usque's SOCKS server checks the login only when both are given, so the per-connection token
        // keeps other apps on the phone off the port.
        val command = buildList {
            add(context.executable.absolutePath)
            add("--config"); add(config.absolutePath)
            add("socks")
            add("--bind"); add("127.0.0.1")
            add("--port"); add(context.socksPort.toString())
            add("--username"); add(context.socksUser)
            add("--password"); add(context.socksPass)
            // When a hop before this one dials through it, usque still exits to Cloudflare; it has no
            // upstream-proxy option, so MASQUE is only ever a single hop or a chain's exit.
        }
        return SidecarLaunch(command = command, readyTimeoutMs = READY_TIMEOUT_MS)
    }

    /**
     * Registers a new device with Cloudflare and writes [config]. Blocking; runs as the app's own
     * child, which Android keeps out of the VPN, so it reaches Cloudflare over the phone's network.
     */
    private fun register(executable: File, config: File) {
        config.parentFile?.mkdirs()
        val process = ProcessBuilder(
            executable.absolutePath, "--config", config.absolutePath,
            "register", "--accept-tos", "--model", "PC"
        ).redirectErrorStream(true).start()
        // Drain the output on a thread so the child's pipe never fills and blocks it.
        val log = StringBuilder()
        val reader = Thread({
            runCatching { process.inputStream.bufferedReader().forEachLine { if (log.length < 4_000) log.appendLine(it) } }
        }, "usque-register").apply { isDaemon = true; start() }
        // Process.waitFor(timeout) and destroyForcibly need API 26; the app supports 24, so poll exitValue().
        val deadline = System.currentTimeMillis() + REGISTER_TIMEOUT_MS
        while (isRunning(process) && System.currentTimeMillis() < deadline) Thread.sleep(100)
        if (isRunning(process)) {
            process.destroy()
            throw java.io.IOException("MASQUE registration timed out")
        }
        reader.join(500)
        if (process.exitValue() != 0 || !config.isFile || config.length() == 0L) {
            throw java.io.IOException("MASQUE registration failed: ${SecretRedactor.redact(log.toString().take(200)).trim()}")
        }
    }

    private fun isRunning(process: Process): Boolean = try {
        process.exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    }

    /** A new MASQUE profile. */
    fun profile(): VlessProfile = VlessProfile(
        name = PROFILE_NAME,
        address = "masque",
        port = 0,
        uuid = "",
        protocolType = ProtocolType.MIXED,
        profileType = ProfileType.VLESS,
        extraSettings = JSONObject().put(KEY_ENGINE, ID).toString()
    )

    const val ID = "masque"
    const val BINARY = "usque"
    const val KEY_ENGINE = "engine"
    const val PROFILE_NAME = "Cloudflare MASQUE"
    const val CONFIG_FILE = "usque.json"
    private const val READY_TIMEOUT_MS = 30_000L
    private const val REGISTER_TIMEOUT_MS = 30_000L
}
