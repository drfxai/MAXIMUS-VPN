package com.example.vpn.connections

import android.content.Context
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.ServerlessSidecar
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.RadianceSidecar
import com.example.vpn.sidecar.TorSidecar

/** Built-ins are kept out of the saved-server race: Direct must never become an implicit VPN exit. */
object BuiltinConnections {
    const val AETHER = "maximus-aether"
    const val DIRECT = "maximus-serverless"
    enum class ConfigFreeMethod(val id: String, val title: String, val description: String) {
        AETHER_WARP("config-free-aether", "Aether / WARP", "Cloudflare tunnel with on-device registration"),
        TOR_SNOWFLAKE("config-free-tor", "Tor / Snowflake", "Tor through the built-in Snowflake bridge"),
        SERVERLESS("config-free-direct", "Serverless Direct", "Direct access with your ISP exit IP"),
        PSIPHON("config-free-psiphon", "Psiphon", "Uses configured Psiphon network settings"),
        RADIANCE("config-free-radiance", "Lantern / Radiance", "Uses the Lantern network when the engine is installed");

        fun profile(): VlessProfile = when (this) {
            AETHER_WARP -> AetherSidecar.profile()
            TOR_SNOWFLAKE -> TorSidecar.profile(name = title)
            SERVERLESS -> ServerlessSidecar.profile()
            PSIPHON -> PsiphonSidecar.profile(tcpOnly = true)
            RADIANCE -> RadianceSidecar.profile()
        }.copy(id = id)
    }

    fun resolve(context: Context, id: String): VlessProfile? {
        ConfigFreeMethod.entries.firstOrNull { it.id == id }?.let { return it.profile() }
        if (id != AETHER && id != DIRECT) return null
        val p = context.getSharedPreferences("galaxy_connections", Context.MODE_PRIVATE)
        val family = p.getString(if (id == DIRECT) "direct_family" else "aether_family", p.getString("family", "auto")).takeIf { it in AetherSidecar.families } ?: "auto"
        return if (id == AETHER) AetherSidecar.profile(
            p.getString("transport", "auto").takeIf { it in AetherSidecar.transports } ?: "auto",
            family, p.getBoolean("fragment", true), AetherSettings.read(org.json.JSONObject(p.getString("aether_advanced", "{}") ?: "{}")))
        else ServerlessSidecar.profile(p.getString("variant", "auto").takeIf { it in ServerlessSidecar.variants } ?: "auto", family, p.getBoolean("vpnFallback", false), p.getString("direct_resolver", "auto").takeIf { it in ServerlessSidecar.resolvers } ?: "auto",
            AetherSettings.read(org.json.JSONObject(p.getString("direct_advanced", "{}") ?: "{}")))
    }
}
