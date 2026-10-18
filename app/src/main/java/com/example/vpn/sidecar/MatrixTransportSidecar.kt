package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONObject
import java.io.File

/** Hardened, separately built Aether-derived carrier; account TLS and MASQUE pins are mandatory. */
object MatrixTransportSidecar : SidecarEngine {
    override val id = "matrix-transport"
    override val binary = "matrixtransport"
    val MODES = setOf("h2", "h3", "wireguard", "gool")
    fun mode(profile: VlessProfile): String = ProfileExtras.read(profile).optString("carrier", "h2")
    override fun handles(profile: VlessProfile) = ProfileExtras.read(profile).optString("engine") == id
    override fun problem(profile: VlessProfile): String? =
        if (mode(profile) !in MODES) "Unknown Matrix Transport carrier." else null

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        problem(profile)?.let { error(it) }
        require(context.upstreamSocks == null) { "Matrix Transport must be the outer carrier." }
        val identity = File(context.workDir, "identity.toml")
        val command = mutableListOf(context.executable.absolutePath,
            "--bind", "127.0.0.1:${context.socksPort}", "--config", identity.absolutePath,
            "--masque-config", identity.absolutePath, "--wg-config", identity.absolutePath,
            "--api-camo", "external", "--scan", "balanced", "--scan-once",
            "--startup-secs", "35", "--validate-secs", "10")
        when (mode(profile)) {
            "h2" -> command += listOf("--masque", "--h2")
            "h3" -> command += "--masque"
            "wireguard" -> command += "--wireguard"
            "gool" -> command += "--gool"
        }
        return SidecarLaunch(command, socksAuth = false, readyTimeoutMs = 45_000L,
            environment = mapOf("AETHER_API_CAMO" to "external", "RUST_LOG" to "warn", "LD_LIBRARY_PATH" to context.executable.parentFile.absolutePath))
    }

    fun profile(mode: String = "h2"): VlessProfile {
        require(mode in MODES)
        return VlessProfile(name = "Matrix Transport ($mode)", address = id, port = 0, uuid = "",
            protocolType = ProtocolType.MIXED, profileType = ProfileType.VLESS,
            extraSettings = JSONObject().put("engine", id).put("carrier", mode).toString())
    }
}
