package com.example.vpn.connections

import android.content.Context
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.ChainRunner
import com.example.vpn.sidecar.EngineChain
import com.example.vpn.sidecar.ServerlessSidecar
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.RadianceSidecar
import com.example.vpn.sidecar.ShirokhorshidSidecar
import com.example.vpn.sidecar.TorSidecar

/** Built-ins are kept out of the saved-server race: Direct must never become an implicit VPN exit. */
object BuiltinConnections {
    const val AETHER = "maximus-aether"
    const val DIRECT = "maximus-serverless"
    enum class ConfigFreeMethod(val id: String, val title: String, val description: String, val aetherTransport: String? = null) {
        AETHER_WARP("config-free-aether", "Aether / WARP", "Cloudflare tunnel with on-device registration"),
        TOR_SNOWFLAKE("config-free-tor", "Tor / Snowflake", "Tor through the built-in Snowflake bridge"),
        SERVERLESS("config-free-direct", "Serverless Direct", "Direct access with your ISP exit IP"),
        PSIPHON("config-free-psiphon", "Psiphon", "Uses configured Psiphon network settings"),
        SHIROKHORSHID("config-free-shirokhorshid", "Shir o Khorshid",
            "Community Psiphon fork for Iran: Conduit, CDN fronting"),
        PSIPHON_RADIANCE("config-free-psiphon-radiance", "Psiphon over Lantern",
            "Lantern carries the connection to the Psiphon network"),
        // Build 177 (B14): the Radiance engine ships inside the app; "when the engine is installed" was outdated.
        RADIANCE("config-free-radiance", "Lantern / Radiance", "Lantern network; the engine is built into the app"),
        AETHER_H2("config-free-aether-h2", "MASQUE HTTP/2", "TCP transport", "h2"),
        AETHER_H3("config-free-aether-h3", "MASQUE HTTP/3", "QUIC over UDP", "h3"),
        AETHER_WG("config-free-aether-wg", "WireGuard", "WARP over WireGuard", "wireguard"),
        AETHER_GOOL("config-free-aether-gool", "Gool", "WireGuard inside MASQUE", "gool"),
        AETHER_GOOL_CLASSIC("config-free-aether-wiw", "Gool Classic", "WireGuard inside WireGuard", "wiw"),
        AETHER_MIM("config-free-aether-mim", "MASQUE-in-MASQUE", "Nested MASQUE transport", "mim"),
        AETHER_PSIPHON("config-free-aether-psiphon", "Aether → Psiphon",
            "Psiphon exits through an Aether carrier", "h2"),
        AETHER_TOR("config-free-aether-tor", "Aether → Tor", "Tor exits through an Aether carrier", "h2");

        fun profile(): VlessProfile = when (this) {
            AETHER_WARP -> AetherSidecar.profile()
            TOR_SNOWFLAKE -> TorSidecar.profile(name = title)
            SERVERLESS -> ServerlessSidecar.profile()
            PSIPHON -> PsiphonSidecar.profile()
            SHIROKHORSHID -> ShirokhorshidSidecar.profile()
            PSIPHON_RADIANCE -> ChainRunner.profile(EngineChain.Chain(listOf(
                EngineChain.Hop(PsiphonSidecar.ID), EngineChain.Hop(RadianceSidecar.id)
            )), titleOf = EngineChain::titleOf).copy(name = title)
            RADIANCE -> RadianceSidecar.profile()
            else -> AetherSidecar.profile(
                transport = requireNotNull(aetherTransport),
                advanced = AetherSettings(
                    alias = title,
                    psiphon = if (this == AETHER_PSIPHON) "chain" else "off",
                    tor = if (this == AETHER_TOR) "chain" else "off"
                )
            )
        }.copy(id = id)

        fun missingDependency(): String? = when (this) {
            AETHER_PSIPHON -> com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(PSIPHON.profile())
            AETHER_TOR -> com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(TOR_SNOWFLAKE.profile())
            else -> null
        }
    }

    /** Whether [id] is one of the built-in (config-free) connections rather than a saved config. */
    fun isBuiltin(id: String?): Boolean = id != null && (id == AETHER || id == DIRECT || ConfigFreeMethod.entries.any { it.id == id })

    fun resolve(context: Context, id: String): VlessProfile? {
        ConfigFreeMethod.entries.firstOrNull { it.id == id }?.let { method ->
            return when (method) {
                ConfigFreeMethod.AETHER_WARP -> resolve(context, AETHER)?.copy(id = id)
                ConfigFreeMethod.SERVERLESS -> resolve(context, DIRECT)?.copy(id = id)
                ConfigFreeMethod.SHIROKHORSHID -> ShirokhorshidSidecar.profile(ShirokhorshidSettings.load(context)).copy(id = id)
                else -> method.profile()
            }
        }
        if (id != AETHER && id != DIRECT) return null
        val p = context.getSharedPreferences("galaxy_connections", Context.MODE_PRIVATE)
        return runCatching {
            val family = p.getString(if (id == DIRECT) "direct_family" else "aether_family", p.getString("family", "auto"))
                .takeIf { it in AetherSidecar.families } ?: "auto"
            fun advanced(key: String) = AetherSettings.read(org.json.JSONObject(p.getString(key, "{}") ?: "{}"))
            if (id == AETHER) AetherSidecar.profile(
                p.getString("transport", "auto").takeIf { it in AetherSidecar.transports } ?: "auto",
                family, p.getBoolean("fragment", true), advanced("aether_advanced")
            ) else ServerlessSidecar.profile(
                p.getString("variant", "auto").takeIf { it in ServerlessSidecar.variants } ?: "auto",
                family, p.getBoolean("vpnFallback", false),
                p.getString("direct_resolver", "auto").takeIf { it in ServerlessSidecar.resolvers } ?: "auto",
                advanced("direct_advanced")
            )
        }.getOrElse { if (id == AETHER) AetherSidecar.profile() else ServerlessSidecar.profile() }
    }
}
