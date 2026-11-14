package com.example.vpn.sidecar

import com.example.data.model.*
import com.example.vpn.engine.ProfileExtras
import org.json.JSONObject
import java.io.File

/** Separately built PattNG-pinned Aether, authenticated registration and native SOCKS authentication. */
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
        // A gateway that carried verified HTTP/2 on this network before (CleanH2PeerMemory): dial it directly,
        // skipping the scan (--peer), and send the HTTP/2 transport to the same address (--h2-peer).
        cleanH2Peer(e, carrier, advanced)?.let { command += listOf("--peer", it, "--h2-peer", it) }
        if (advanced.tor != "off") {
            command += when (advanced.tor) { "chain" -> "--tor"; "reverse" -> "--tor-reverse"; else -> "--tor-only" }
            if (advanced.tor != "only") command += listOf("--tor-bind",
                "127.0.0.1:${if (chainTor && !chainPsiphon) context.socksPort else Sidecars.freeLoopbackPort()}")
            Sidecars.nativeLibraryDir?.let { dir -> command += listOf("--tor-pt-dir", dir, "--tor-pt", "obfs4=$dir/liblyrebird.so") }
        }
        val environment = mutableMapOf(
            "AETHER_DNS" to com.example.vpn.BundledResolvers.known("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4").joinToString(","),
            "MAXIMUS_CA_BUNDLE" to ca.absolutePath, "MAXIMUS_SOCKS_USER" to context.socksUser,
            "MAXIMUS_SOCKS_PASS" to context.socksPass, "AETHER_TLS_VERIFY" to "1", "RUST_LOG" to "warn",
            "LD_LIBRARY_PATH" to context.executable.parentFile!!.absolutePath)
        environment += advanced.environment(carrier)
        if (sniSplit(e, carrier)) environment[com.example.vpn.connectivity.IranHelloSplit.AETHER_SNI_SPLIT_ENV] = "1"
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
        val extras = ProfileExtras.read(profile)
        val options = AetherSettings.read(extras)
        val exit = BuiltinExitNode.start(options, settings, context, BuiltinExitNode.Route.read(extras))
        var api: BuiltinExitNode.Started? = null
        try {
            api = AetherApiBridge.start(profile, settings, context)
            if (exit == null && api == null) return null
            val helpers = listOfNotNull(exit, api)
            val environment = launch.environment.toMutableMap()
            exit?.let { environment["AETHER_UPSTREAM"] = it.upstream }
            api?.let {
                environment["MAXIMUS_API_UPSTREAM"] = it.upstream
                it.apiHttp?.let { value -> environment["MAXIMUS_API_HTTP"] = value }
                it.apiToken?.let { value -> environment["MAXIMUS_API_HTTP_TOKEN"] = value }
            }
            val child = SidecarProcess.start(ID, launch.copy(environment = environment), context.workDir, context.socksPort)
            return object : RunningEngine {
                override val isAlive get() = child.isAlive && helpers.all { it.running.isAlive }
                override val startupFailure get() = child.startupFailure
                override val transportGeneration get() = child.transportGeneration
                override fun awaitReady(timeoutMs: Long) = child.awaitReadyWhile(timeoutMs, { helpers.all { it.running.isAlive } })
                override fun awaitReadyUnless(timeoutMs: Long, giveUp: () -> Boolean) =
                    child.awaitReadyWhile(timeoutMs, { helpers.all { it.running.isAlive } }, giveUp)
                override fun stop() { try { child.stop() } finally { helpers.forEach { it.running.stop() } } }
            }
        } catch (e: Throwable) {
            try { api?.running?.stop() } finally { exit?.running?.stop() }
            throw e
        }
    }

    /** Extras key: the remembered clean HTTP/2 gateway to dial (set only by the session runner). */
    const val CLEAN_H2_PEER = "cleanH2Peer"
    /** Extras key: split the first HTTP/2 TLS write inside the server name (Iranian carriers). */
    const val SNI_SPLIT = "sniSplit"

    internal fun cleanH2Peer(e: JSONObject, carrier: String, advanced: AetherSettings): String? =
        e.optString(CLEAN_H2_PEER).takeIf { carrier == "h2" && advanced.endpoint.isBlank() && advanced.exitNode.isBlank() &&
            com.example.vpn.connections.CleanH2PeerMemory.validPeer(it) }

    /** The SNI split only means something with --fragment, on a carrier that speaks TLS over TCP. */
    internal fun sniSplit(e: JSONObject, carrier: String): Boolean =
        e.optBoolean(SNI_SPLIT) && e.optBoolean("fragment", true) && carrier in listOf("h2", "gool", "mim")

    /**
     * The MASQUE gateway Aether saved as its last HTTP/2 connection (`<identity>-lastconn.toml`, written when a
     * scanned gateway is put to use), if it was written at or after [sinceMs]. Null for H3/WireGuard sessions,
     * forced peers (Aether does not save those) and anything unreadable.
     */
    fun lastMasqueH2Peer(sinceMs: Long): String? = runCatching {
        val root = identityDirectory?.invoke() ?: return null
        val file = File(AetherIdentityStore.current(root), "identity-lastconn.toml")
        if (!file.isFile || file.lastModified() + 2_000 < sinceMs) return null
        parseLastConnection(file.readText())
    }.getOrNull()

    /** `peer = "…"` of a lastconn file whose `carrier = "masque-h2"`. Pure. */
    fun parseLastConnection(text: String): String? {
        fun value(key: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$key ") || it.startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"")
        if (value("carrier") != "masque-h2") return null
        return value("peer")?.takeIf(com.example.vpn.connections.CleanH2PeerMemory::validPeer)
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
