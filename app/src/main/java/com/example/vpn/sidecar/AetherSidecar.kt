package com.example.vpn.sidecar

import com.example.data.model.*
import com.example.vpn.engine.ProfileExtras
import org.json.JSONObject
import java.io.File

/** Separately built Aether 2.4.0, authenticated registration and native SOCKS authentication. */
object AetherSidecar : SidecarEngine {
    const val ID = "aether"
    fun readyLine(port: Int) = Regex("^MAXIMUS_AETHER_SOCKS_READY:$port$")
    override val id = ID
    override val binary = ID
    @Volatile var identityDirectory: (() -> File)? = null
    val transports = listOf("auto", "h2", "h3", "wireguard", "gool", "wiw", "mim")
    val families = listOf("auto", "ipv4", "ipv6")
    override fun handles(profile: VlessProfile) = ProfileExtras.read(profile).optString("engine") == ID
    override fun problem(profile: VlessProfile): String? = when {
        ProfileExtras.read(profile).optString("carrier", "auto") !in transports -> "Unsupported Aether transport"
        ProfileExtras.read(profile).optString("family", "auto") !in families -> "Unsupported IP family"
        else -> AetherSettings.read(ProfileExtras.read(profile)).problem(ProfileExtras.read(profile).optString("carrier"))
    }
    fun profile(transport: String = "auto", family: String = "auto", fragment: Boolean = true, advanced: AetherSettings = AetherSettings()): VlessProfile {
        require(transport in transports && family in families && advanced.problem() == null)
        return VlessProfile(id = "maximus-aether", name = advanced.alias.ifBlank { "Aether / WARP" }, address = ID, port = 0, uuid = "",
            protocolType = ProtocolType.MIXED, extraSettings = advanced.write(JSONObject()).put("engine", ID)
                .put("carrier", transport).put("family", family).put("fragment", fragment).toString())
    }
    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        require(context.upstreamSocks == null) { "Aether cannot discard a carrier hop" }
        problem(profile)?.let { error(it) }
        val e = ProfileExtras.read(profile)
        val advanced = AetherSettings.read(e)
        val ca = trustedRoots(context.workDir)
        val root = identityDirectory?.invoke() ?: error("Private identity storage is unavailable")
        root.mkdirs()
        val identityDir = AetherIdentityStore.current(root)
        val identity = File(identityDir, "identity.toml")
        val selectedCarrier = e.optString("carrier", "auto")
        val reverse = advanced.tor == "reverse" || advanced.psiphon == "reverse"
        require(!reverse || selectedCarrier !in listOf("wireguard", "wiw")) { "Reverse carriers require MASQUE over HTTP/2" }
        val carrier = if (reverse && selectedCarrier == "h3") "h2" else selectedCarrier
        val chainPsiphon = advanced.psiphon == "chain"
        val chainTor = advanced.tor == "chain"
        val ownPort = if (chainPsiphon || chainTor) Sidecars.freeLoopbackPort() else context.socksPort
        val command = mutableListOf(context.executable.absolutePath, "--bind", "127.0.0.1:$ownPort",
            "--config", identity.absolutePath, "--masque-config", identity.absolutePath,
            "--wg-config", File(identityDir, "wireguard.toml").absolutePath,
            "--scan", advanced.scan, if (e.optBoolean("rescan")) "--no-quick-reconnect" else "--quick-reconnect", "--tls-verify", "--startup-secs", "45", "--validate-secs", "12")
        command += when (carrier) {
            "h3" -> listOf("--masque", "--h3")
            "wireguard" -> listOf("--wg")
            "gool" -> listOf("--gool", "--h2")
            "wiw" -> listOf("--gool", "--gool-classic")
            "mim" -> listOf("--mim", "--h2")
            // Auto is resolved by the session runner: H2, H3, then WireGuard, each checked with HTTPS.
            else -> listOf("--masque", "--h2")
        }
        command += when (e.optString("family", "auto")) { "ipv4" -> "-4"; "ipv6" -> "-6"; else -> "--dual" }
        command += advanced.arguments(carrier, e.optBoolean("fragment", true))
        if (advanced.tor != "off") {
            command += when (advanced.tor) { "chain" -> "--tor"; "reverse" -> "--tor-reverse"; else -> "--tor-only" }
            if (advanced.tor != "only") command += listOf("--tor-bind",
                "127.0.0.1:${if (chainTor && !chainPsiphon) context.socksPort else Sidecars.freeLoopbackPort()}")
            Sidecars.nativeLibraryDir?.let { dir -> command += listOf("--tor-pt-dir", dir, "--tor-pt", "obfs4=$dir/liblyrebird.so") }
        }
        val environment = mutableMapOf(
            "MAXIMUS_CA_BUNDLE" to ca.absolutePath, "MAXIMUS_SOCKS_USER" to context.socksUser,
            "MAXIMUS_SOCKS_PASS" to context.socksPass, "AETHER_TLS_VERIFY" to "1", "RUST_LOG" to "warn",
            "LD_LIBRARY_PATH" to context.executable.parentFile!!.absolutePath)
        if (advanced.psiphon != "off") {
            val binary = Sidecars.ENGINES.first { it.id == PsiphonSidecar.ID }.let(Sidecars::executable)
                ?: error("The Psiphon runtime is missing")
            command += when (advanced.psiphon) { "chain" -> "--psiphon"; "reverse" -> "--psiphon-reverse"; else -> "--psiphon-only" }
            if (advanced.psiphon != "only") command += listOf("--psiphon-bind",
                "127.0.0.1:${if (chainPsiphon) context.socksPort else 0}")
            command += listOf("--psiphon-bin", binary.absolutePath)
            Sidecars.psiphonSettings?.invoke()?.takeIf { it.isNotBlank() }?.let { config ->
                val file = File(context.workDir, "psiphon-base.json").apply { writeText(config) }
                environment["AETHER_PSIPHON_CONFIG"] = file.absolutePath
            }
        }
        return SidecarLaunch(command, readyTimeoutMs = if (advanced.tor != "off" || advanced.psiphon != "off") 240_000L else 180_000L,
            readyLine = readyLine(context.socksPort),
            socksAuth = advanced.psiphon !in listOf("chain", "only"), environment = environment, logOutput = false)
    }
    override fun startPrepared(profile: VlessProfile, settings: AppSettings, launch: SidecarLaunch, context: SidecarContext): RunningEngine? {
        val options = AetherSettings.read(ProfileExtras.read(profile))
        val exit = BuiltinExitNode.start(options, settings, context) ?: return null
        try {
            val child = SidecarProcess.start(ID, launch.copy(environment = launch.environment +
                ("AETHER_UPSTREAM" to exit.upstream)), context.workDir, context.socksPort)
            return object : RunningEngine {
                override val isAlive get() = child.isAlive && exit.running.isAlive
                override val startupFailure get() = child.startupFailure
                override fun awaitReady(timeoutMs: Long) = child.awaitReady(timeoutMs) && exit.running.isAlive
                override fun stop() { try { child.stop() } finally { exit.running.stop() } }
            }
        } catch (e: Throwable) { exit.running.stop(); throw e }
    }

    fun trustedRoots(dir: File): File {
        dir.mkdirs()
        val roots = listOf(File("/apex/com.android.conscrypt/cacerts"), File("/system/etc/security/cacerts"))
            .flatMap { it.listFiles()?.toList().orEmpty() }.filter { it.isFile && it.canRead() }.distinctBy { it.name }
        check(roots.isNotEmpty()) { "Android trusted certificate store is unavailable" }
        return File(dir, "trusted-roots.pem").apply {
            outputStream().buffered().use { out -> roots.forEach { root -> root.inputStream().use { it.copyTo(out) }; out.write(10) } }
            setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true)
        }
    }
}
