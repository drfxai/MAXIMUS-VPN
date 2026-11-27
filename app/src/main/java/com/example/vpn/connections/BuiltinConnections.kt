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
        // Build 178 field report: Shir o Khorshid alone found no tunnel on Irancell while Psiphon over Lantern worked. An
        // explicit, separately labelled chain; a plain Shir o Khorshid pick never goes through Lantern by itself.
        SHIROKHORSHID_RADIANCE("config-free-shirokhorshid-radiance", "Shir o Khorshid over Lantern",
            "Lantern carries the connection to Shir o Khorshid (an explicit chain)"),
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
        AETHER_TOR("config-free-aether-tor", "Aether → Tor", "Tor exits through an Aether carrier", "h2"),
        // Build 178: Cloudflare filtered (cf=0) on every Aether carrier. An explicit pick: WARP MASQUE HTTP/2 dialled through
        // the Lantern hop, so the phone never reaches a filtered Cloudflare address itself. Never inside a plain Aether pick.
        AETHER_LANTERN("config-free-aether-lantern", "Aether over Lantern",
            "WARP through the Lantern network, avoiding filtered Cloudflare addresses (an explicit chain)", "h2"),
        // Build 181 (3.4): Serverless Direct, and sites that do not answer directly go through Lantern (Xray sockopt
        // fallback). An explicit, labelled pick; its exit is still the ISP's for everything that works directly.
        SERVERLESS_LANTERN("config-free-direct-lantern", "Serverless + Lantern",
            "Direct access; sites that do not answer go through Lantern"),
        // Build 181 (Phase 6): the user's own server with its own ECH key (Panels → My Servers → ECH). A separately
        // labelled pick; plain TLS to the same server after ECH failed on this network. Never inside another pick.
        PERSONAL_ECH("config-free-personal-ech", "Personal server (ECH)",
            "Your own server, with ECH and an outer name that passes this network");

        fun profile(): VlessProfile = when (this) {
            AETHER_WARP -> AetherSidecar.profile()
            TOR_SNOWFLAKE -> TorSidecar.profile(name = title)
            SERVERLESS -> ServerlessSidecar.profile()
            SERVERLESS_LANTERN -> ServerlessSidecar.profile(advanced = AetherSettings(alias = title)).let { p ->
                p.copy(extraSettings = org.json.JSONObject(p.extraSettings).put(ServerlessSidecar.KEY_LANTERN_FALLBACK, true).toString())
            }
            PSIPHON -> PsiphonSidecar.profile()
            // Placeholder until a server has ECH (missingDependency() then explains); resolve() reads the real config.
            PERSONAL_ECH -> VlessProfile(name = title, address = "127.0.0.1", port = 443, uuid = "00000000-0000-0000-0000-000000000000",
                security = "tls", sni = "personal-ech.invalid")
            SHIROKHORSHID -> ShirokhorshidSidecar.profile()
            PSIPHON_RADIANCE -> ChainRunner.profile(EngineChain.Chain(listOf(
                EngineChain.Hop(PsiphonSidecar.ID), EngineChain.Hop(RadianceSidecar.id)
            )), titleOf = EngineChain::titleOf).copy(name = title)
            RADIANCE -> RadianceSidecar.profile()
            SHIROKHORSHID_RADIANCE -> ChainRunner.profile(EngineChain.Chain(listOf(
                EngineChain.Hop(ShirokhorshidSidecar.ID), EngineChain.Hop(RadianceSidecar.id)
            )), titleOf = EngineChain::titleOf).copy(name = title)
            AETHER_LANTERN -> AetherSidecar.profile(transport = "h2", advanced = AetherSettings(alias = title)).let { p ->
                p.copy(extraSettings = org.json.JSONObject(p.extraSettings)
                    .put(com.example.vpn.sidecar.LanternRelayHop.EXTRA, true).toString())
            }
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
            AETHER_LANTERN, SHIROKHORSHID_RADIANCE, SERVERLESS_LANTERN -> com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(RADIANCE.profile())
            PERSONAL_ECH -> if (com.example.vpn.smartsni.PersonalEch.available()) null
                else "Turn on ECH for your server in Panels → My Servers first."
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
                // The user's Direct settings, plus the Lantern fallback.
                ConfigFreeMethod.SERVERLESS_LANTERN -> resolve(context, DIRECT)?.let { p ->
                    p.copy(id = id, name = method.title, extraSettings = org.json.JSONObject(p.extraSettings)
                        .put(ServerlessSidecar.KEY_LANTERN_FALLBACK, true).toString())
                } ?: method.profile()
                ConfigFreeMethod.SHIROKHORSHID -> ShirokhorshidSidecar.profile(ShirokhorshidSettings.load(context)).copy(id = id)
                // The ECH-enabled server that is not failing here; ECH itself is added at connect (PersonalEch).
                ConfigFreeMethod.PERSONAL_ECH -> com.example.vpn.smartsni.PersonalEch.profile(BuiltinMethodMemory.network())
                    ?.copy(id = id, name = method.title)
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
