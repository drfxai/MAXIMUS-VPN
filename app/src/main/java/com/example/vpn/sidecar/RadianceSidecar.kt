package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONObject
import java.io.File

/** Lantern's Radiance backend in SOCKS mode. MAXIMUS remains the Android VPN service. */
object RadianceSidecar : SidecarEngine {
    override val id = "radiance"
    override val binary = "radiance"
    const val REVISION = "051b4de90773"

    override fun handles(profile: VlessProfile): Boolean =
        ProfileExtras.read(profile).optString("engine") == id

    fun profile() = VlessProfile(
        id = "builtin-radiance",
        name = "Lantern / Radiance",
        address = "radiance",
        port = 0,
        uuid = "",
        protocolType = ProtocolType.MIXED,
        extraSettings = JSONObject().put("engine", id).toString()
    )

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        require(context.upstreamSocks == null) { "Radiance does not support an upstream hop." }
        val data = File(context.workDir, "radiance-data")
        check(data.isDirectory || data.mkdirs()) { "Could not create the Radiance data directory." }
        return SidecarLaunch(
            command = listOf(context.executable.absolutePath),
            environment = mapOf(
                "RADIANCE_DATA_PATH" to data.absolutePath,
                "RADIANCE_SOCKS_ADDRESS" to "127.0.0.1:${context.socksPort}",
                "RADIANCE_DISABLE_STDOUT_LOG" to "true",
                "MAXIMUS_SOCKS_USER" to context.socksUser,
                "MAXIMUS_SOCKS_PASS" to context.socksPass
            ),
            readyTimeoutMs = 60_000,
            readyLine = Regex("^MAXIMUS_RADIANCE_READY$"),
            logOutput = false
        )
    }
}
