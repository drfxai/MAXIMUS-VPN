package com.example.vpn.sidecar

import com.example.data.model.*
import com.example.vpn.engine.ProfileExtras
import org.json.JSONObject
import java.io.File

/** Separately built Aether 2.4.0, authenticated registration and native SOCKS authentication. */
object AetherSidecar : SidecarEngine {
    const val ID = "aether"
    val READY_LINE = Regex("^MAXIMUS_AETHER_SOCKS_READY$")
    override val id = ID
    override val binary = ID
    @Volatile var identityDirectory: (() -> File)? = null
    val transports = listOf("auto", "h2", "h3", "wireguard", "gool")
    val families = listOf("auto", "ipv4", "ipv6")
    override fun handles(profile: VlessProfile) = ProfileExtras.read(profile).optString("engine") == ID
    override fun problem(profile: VlessProfile): String? = when {
        ProfileExtras.read(profile).optString("carrier", "auto") !in transports -> "Unsupported Aether transport"
        ProfileExtras.read(profile).optString("family", "auto") !in families -> "Unsupported IP family"
        else -> null
    }
    fun profile(transport: String = "auto", family: String = "auto", fragment: Boolean = true): VlessProfile {
        require(transport in transports && family in families)
        return VlessProfile(id = "maximus-aether", name = "Aether / WARP", address = ID, port = 0, uuid = "",
            protocolType = ProtocolType.MIXED, extraSettings = JSONObject().put("engine", ID)
                .put("carrier", transport).put("family", family).put("fragment", fragment).toString())
    }
    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        require(context.upstreamSocks == null) { "Aether cannot discard a carrier hop" }
        problem(profile)?.let { error(it) }
        val e = ProfileExtras.read(profile)
        val ca = trustedRoots(context.workDir)
        val identityDir = identityDirectory?.invoke() ?: error("Private identity storage is unavailable")
        identityDir.mkdirs()
        val identity = File(identityDir, "identity.toml")
        val carrier = e.optString("carrier", "auto")
        val command = mutableListOf(context.executable.absolutePath, "--bind", "127.0.0.1:${context.socksPort}",
            "--config", identity.absolutePath, "--masque-config", identity.absolutePath,
            "--wg-config", File(identityDir, "wireguard.toml").absolutePath,
            "--scan", "balanced", if (e.optBoolean("rescan")) "--no-quick-reconnect" else "--quick-reconnect", "--tls-verify", "--startup-secs", "45", "--validate-secs", "12")
        command += when (carrier) {
            "h3" -> listOf("--masque", "--h3")
            "wireguard" -> listOf("--wg")
            "gool" -> listOf("--gool", "--h2")
            // Auto is resolved by the session runner: H2, H3, then WireGuard, each checked with HTTPS.
            else -> listOf("--masque", "--h2")
        }
        command += when (e.optString("family", "auto")) { "ipv4" -> "-4"; "ipv6" -> "-6"; else -> "--dual" }
        if (e.optBoolean("fragment", true)) command += "--fragment"
        return SidecarLaunch(command, readyTimeoutMs = 90_000L, readyLine = READY_LINE, environment = mapOf(
            "MAXIMUS_CA_BUNDLE" to ca.absolutePath, "MAXIMUS_SOCKS_USER" to context.socksUser,
            "MAXIMUS_SOCKS_PASS" to context.socksPass, "AETHER_TLS_VERIFY" to "1", "RUST_LOG" to "warn",
            "LD_LIBRARY_PATH" to context.executable.parentFile!!.absolutePath), logOutput = false)
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
