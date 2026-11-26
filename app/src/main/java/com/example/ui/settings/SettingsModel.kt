package com.example.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.data.model.AppSettings
import com.example.data.model.DesyncMethod
import com.example.data.model.DesyncProfile
import com.example.data.model.EngineType
import com.example.data.model.RoutingMode
import com.example.data.model.ScoringProfile

/** Settings sub-pages. The hub lists them; each opens as its own page inside the Settings tab. */
enum class SettingsPage {
    CONNECTION, ROUTING_DNS, ANTI_CENSORSHIP, PRIVACY, SHARING, APPEARANCE, UPDATES, ENGINES, DIAGNOSTICS, ABOUT
}

/**
 * Everything a Settings page can change. The screen wires it to [com.example.ui.viewmodel.SettingsViewModel]
 * one-to-one, so the same preference keys and side effects are used as before; previews and screenshot
 * tests use [Noop].
 */
interface SettingsActions {
    fun setDarkTheme(dark: Boolean) {}
    fun setScoringProfile(profile: ScoringProfile) {}
    fun setAutoFailover(enabled: Boolean) {}
    fun setPowerButtonFallback(enabled: Boolean) {}
    fun setSpeedPickOnMetered(enabled: Boolean) {}
    fun setPreferredEngine(engine: EngineType) {}
    fun setRoutingMode(mode: RoutingMode) {}
    fun setDnsServer(dns: String) {}
    fun setFreeConfigs(enabled: Boolean) {}
    fun setKillSwitch(enabled: Boolean) {}
    fun setHotspotShare(enabled: Boolean) {}
    fun regenerateHotspotCredentials() {}
    fun setIpv6(enabled: Boolean) {}
    fun setAutoReconnect(enabled: Boolean) {}
    fun updateSettings(settings: AppSettings) {}

    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}

/** The update check, as the header chip and the Updates page show it. */
data class UpdateUi(
    val checking: Boolean = false,
    val checked: Boolean = false,
    val hasUpdate: Boolean = false,
    val latestVersion: String? = null,
    val checkFailed: Boolean = false
)

/** The local SOCKS5 proxy, as the Connection page shows it. */
data class LocalProxyUi(
    val running: Boolean = false,
    val starting: Boolean = false,
    val port: Int = 0,
    val user: String = "",
    val password: String = "",
    val error: String? = null,
    val canStart: Boolean = false
)

/** Short labels and icons used across the hub subtitles and the pages. */
internal object SettingsLabels {
    fun scoring(profile: ScoringProfile): String = when (profile) {
        ScoringProfile.BALANCED -> "Balanced"
        ScoringProfile.GAMING -> "Gaming"
        ScoringProfile.STREAMING -> "Streaming"
        ScoringProfile.DOWNLOADING -> "Downloads"
        ScoringProfile.LOW_LATENCY -> "Low latency"
        ScoringProfile.MAX_STABILITY -> "Max stability"
    }

    fun scoringIcon(profile: ScoringProfile): ImageVector = when (profile) {
        ScoringProfile.BALANCED -> Icons.Default.Tune
        ScoringProfile.GAMING -> Icons.Default.SportsEsports
        ScoringProfile.STREAMING -> Icons.Default.Movie
        ScoringProfile.DOWNLOADING -> Icons.Default.Download
        ScoringProfile.LOW_LATENCY -> Icons.Default.Bolt
        ScoringProfile.MAX_STABILITY -> Icons.Default.Shield
    }

    fun engineShort(engine: EngineType): String = when (engine) {
        EngineType.AUTO -> "Auto engine"
        EngineType.SING_BOX -> "sing-box"
        EngineType.MIHOMO -> "Mihomo"
        EngineType.XRAY -> "Xray-core"
    }

    fun engineTitle(engine: EngineType): String = when (engine) {
        EngineType.AUTO -> "Auto (smart select)"
        EngineType.SING_BOX -> "sing-box 1.14.4"
        EngineType.MIHOMO -> "Mihomo / Clash Meta"
        EngineType.XRAY -> "Xray-core"
    }

    fun engineDescription(engine: EngineType): String = when (engine) {
        EngineType.AUTO -> "Picks the best engine per config: Xray for VLESS/XTLS Reality, Mihomo for YAML and proxy groups"
        EngineType.SING_BOX -> "VLESS, VMess, Trojan and Shadowsocks; incompatible custom settings stay on Xray"
        EngineType.MIHOMO -> "Fast rule matching, URL-test latency failover and proxy groups"
        EngineType.XRAY -> "Full VLESS + XTLS Vision + Reality kernel with direct TCP/gRPC routing"
    }

    /** Order shown in the engine picker (unchanged from the previous screen). */
    val engines = listOf(EngineType.AUTO, EngineType.SING_BOX, EngineType.MIHOMO, EngineType.XRAY)

    fun routingShort(mode: RoutingMode): String = when (mode) {
        RoutingMode.RULE_BYPASS_LAN -> "Bypass LAN"
        RoutingMode.GLOBAL -> "Global proxy"
        RoutingMode.BYPASS_SELECTED -> "Custom bypass"
        RoutingMode.IRAN_DIRECT -> "Iran direct"
        RoutingMode.IRAN_DIRECT_BLOCK_ADS -> "Iran direct + ads"
    }

    fun routingTitle(mode: RoutingMode): String = when (mode) {
        RoutingMode.RULE_BYPASS_LAN -> "Bypass LAN & local"
        RoutingMode.GLOBAL -> "Global proxy"
        RoutingMode.BYPASS_SELECTED -> "Custom bypass list"
        RoutingMode.IRAN_DIRECT -> "Iran direct"
        RoutingMode.IRAN_DIRECT_BLOCK_ADS -> "Iran direct + block ads"
    }

    fun routingDescription(mode: RoutingMode): String = when (mode) {
        RoutingMode.RULE_BYPASS_LAN -> "LAN and private IPs go direct; everything else is tunnelled"
        RoutingMode.GLOBAL -> "All traffic goes through the tunnel"
        RoutingMode.BYPASS_SELECTED -> "Your custom domain and IP patterns go direct"
        RoutingMode.IRAN_DIRECT -> "LAN, Iranian sites (.ir, category-ir) and Iranian IPs go direct; the rest is tunnelled (Xray engine)"
        RoutingMode.IRAN_DIRECT_BLOCK_ADS -> "As Iran direct, and Iranian ad domains are blocked (Xray engine)"
    }

    /** Order shown in the routing list (unchanged from the previous screen). */
    val routingModes = listOf(
        RoutingMode.RULE_BYPASS_LAN, RoutingMode.GLOBAL, RoutingMode.BYPASS_SELECTED,
        RoutingMode.IRAN_DIRECT, RoutingMode.IRAN_DIRECT_BLOCK_ADS
    )

    /** Literal endpoints keep DNS bootstrap independent of the ISP resolver. */
    val dnsPresets = listOf(
        "https://1.1.1.1/dns-query" to "Cloudflare DoH (Encrypted)",
        "https://8.8.8.8/dns-query" to "Google DoH (Encrypted)",
        "https://9.9.9.9/dns-query" to "Quad9 DoH (Secure)",
        "1.1.1.1" to "Cloudflare UDP (1.1.1.1)",
        "8.8.8.8" to "Google DNS UDP (8.8.8.8)",
        "9.9.9.9" to "Quad9 Secure UDP (9.9.9.9)",
        "1.0.0.1" to "Cloudflare UDP (1.0.0.1)",
        "1.1.1.2" to "Cloudflare UDP · Malware filter (1.1.1.2)",
        "1.1.1.3" to "Cloudflare UDP · Malware and adult filters (1.1.1.3)",
        "8.8.4.4" to "Google DNS UDP (8.8.4.4)",
        "9.9.9.10" to "Quad9 UDP · No threat blocking (9.9.9.10)",
        "149.112.112.10" to "Quad9 UDP · No threat blocking (149.112.112.10)",
        "208.67.222.222" to "OpenDNS UDP (208.67.222.222)",
        "208.67.220.220" to "OpenDNS UDP (208.67.220.220)"
    )

    val dnsBenchmarkResolvers = listOf(
        "https://1.1.1.1/dns-query" to "Cloudflare",
        "https://8.8.8.8/dns-query" to "Google",
        "https://9.9.9.9/dns-query" to "Quad9"
    )

    private val dnsAliases = mapOf(
        "https://dns.google/dns-query" to "https://8.8.8.8/dns-query",
        "https://dns.quad9.net/dns-query" to "https://9.9.9.9/dns-query"
    )

    fun dns(dns: String): String =
        dnsPresets.firstOrNull { it.first == (dnsAliases[dns] ?: dns) }?.second ?: "Custom DNS"

    fun dnsShort(dns: String): String = when (dns) {
        "1.1.1.1" -> "Cloudflare DNS"
        "8.8.8.8" -> "Google DNS"
        "9.9.9.9" -> "Quad9 DNS"
        "https://1.1.1.1/dns-query" -> "Cloudflare DoH"
        "https://8.8.8.8/dns-query", "https://dns.google/dns-query" -> "Google DoH"
        "https://9.9.9.9/dns-query", "https://dns.quad9.net/dns-query" -> "Quad9 DoH"
        else -> dns(dns)
    }

    /** The four desync presets offered before, and the method each one selects (unchanged). */
    val desyncPresets = listOf(DesyncProfile.LIGHT, DesyncProfile.BALANCED, DesyncProfile.SEVERE, DesyncProfile.ADAPTIVE)

    fun desyncMethodFor(profile: DesyncProfile, current: DesyncMethod): DesyncMethod = when (profile) {
        DesyncProfile.LIGHT -> DesyncMethod.SPLIT
        DesyncProfile.BALANCED -> DesyncMethod.DISORDER
        DesyncProfile.SEVERE -> DesyncMethod.DISORDER_OOB
        DesyncProfile.ADAPTIVE -> DesyncMethod.FAKE_SNI
        else -> current
    }

    fun desyncDescription(profile: DesyncProfile): String = when (profile) {
        DesyncProfile.LIGHT -> "Splits the TLS ClientHello payload into small segments"
        DesyncProfile.BALANCED -> "Sends split segments out of order to break DPI reassembly"
        DesyncProfile.SEVERE -> "Out-of-order segments plus a TCP urgent (OOB) byte"
        DesyncProfile.ADAPTIVE -> "Injects a decoy SNI before the real ClientHello"
        else -> profile.description
    }

    /**
     * The hub subtitle under each Settings entry, from string resources (build 177, B19: the Russian hub showed
     * "Balanced · Auto engine", "Hotspot sharing off", "Dark theme" …). [str] reads a resource with its format args
     * (Resources.getString in the app, a fake in tests). Brand and engine names (Cloudflare DoH, sing-box) stay as they are.
     */
    fun hubText(settings: AppSettings, page: SettingsPage, update: UpdateUi, versionName: String,
                str: (Int, Array<out Any>) -> String): String {
        fun t(id: Int, vararg args: Any) = str(id, args)
        return when (page) {
            SettingsPage.CONNECTION -> "${t(scoringRes(settings.scoringProfile))} · " +
                (if (settings.preferredEngine == EngineType.AUTO) t(com.example.R.string.settings_hub_auto_engine) else engineShort(settings.preferredEngine))
            SettingsPage.ROUTING_DNS -> "${t(routingShortRes(settings.routingMode))} · " +
                (dnsShortOrNull(settings.dnsServer) ?: t(com.example.R.string.settings_hub_custom_dns))
            SettingsPage.ANTI_CENSORSHIP -> (if (settings.defaultDesyncConfig.enabled)
                t(com.example.R.string.settings_hub_sni_on, t(desyncRes(settings.defaultDesyncConfig.profile)))
                else t(com.example.R.string.settings_hub_sni_off)) + " · " +
                t(if (settings.freeConfigsEnabled) com.example.R.string.settings_hub_free_on else com.example.R.string.settings_hub_free_off)
            SettingsPage.PRIVACY -> listOfNotNull(
                if (settings.killSwitchEnabled) t(com.example.R.string.ui_kill_switch) else null,
                if (settings.autoReconnect) t(com.example.R.string.settings_hub_auto_reconnect) else null,
                t(if (settings.ipv6Enabled) com.example.R.string.settings_hub_ipv6_routed else com.example.R.string.settings_hub_ipv6_blocked)
            ).joinToString(" · ")
            SettingsPage.SHARING -> t(if (settings.hotspotShareEnabled) com.example.R.string.settings_hub_hotspot_on else com.example.R.string.settings_hub_hotspot_off)
            SettingsPage.APPEARANCE -> t(if (settings.darkTheme) com.example.R.string.settings_hub_dark else com.example.R.string.settings_hub_light)
            SettingsPage.UPDATES -> when {
                update.checking -> t(com.example.R.string.settings_hub_update_checking)
                update.hasUpdate -> t(com.example.R.string.settings_hub_update_available) + (update.latestVersion?.let { " · v$it" } ?: "")
                update.checkFailed -> t(com.example.R.string.settings_hub_update_unavailable, versionName)
                update.checked -> t(com.example.R.string.settings_hub_update_current, versionName)
                else -> t(com.example.R.string.settings_hub_update_default, versionName)
            }
            SettingsPage.ENGINES -> t(com.example.R.string.settings_hub_engines)
            SettingsPage.DIAGNOSTICS -> t(com.example.R.string.settings_hub_diagnostics)
            SettingsPage.ABOUT -> t(com.example.R.string.settings_hub_about)
        }
    }

    fun scoringRes(profile: ScoringProfile): Int = when (profile) {
        ScoringProfile.BALANCED -> com.example.R.string.settings_scoring_balanced
        ScoringProfile.GAMING -> com.example.R.string.settings_scoring_gaming
        ScoringProfile.STREAMING -> com.example.R.string.settings_scoring_streaming
        ScoringProfile.DOWNLOADING -> com.example.R.string.settings_scoring_downloads
        ScoringProfile.LOW_LATENCY -> com.example.R.string.settings_scoring_low_latency
        ScoringProfile.MAX_STABILITY -> com.example.R.string.settings_scoring_max_stability
    }

    fun routingShortRes(mode: RoutingMode): Int = when (mode) {
        RoutingMode.RULE_BYPASS_LAN -> com.example.R.string.settings_routing_bypass_lan
        RoutingMode.GLOBAL -> com.example.R.string.settings_routing_global
        RoutingMode.BYPASS_SELECTED -> com.example.R.string.settings_routing_custom
        RoutingMode.IRAN_DIRECT -> com.example.R.string.settings_routing_iran_direct
        RoutingMode.IRAN_DIRECT_BLOCK_ADS -> com.example.R.string.settings_routing_iran_direct_ads
    }

    fun desyncRes(profile: DesyncProfile): Int = when (profile) {
        DesyncProfile.OFF -> com.example.R.string.settings_desync_off
        DesyncProfile.LIGHT -> com.example.R.string.settings_desync_light
        DesyncProfile.BALANCED -> com.example.R.string.settings_desync_balanced
        DesyncProfile.SEVERE -> com.example.R.string.settings_desync_severe
        DesyncProfile.ADAPTIVE -> com.example.R.string.settings_desync_adaptive
        DesyncProfile.CUSTOM -> com.example.R.string.settings_desync_custom
    }

    /** The resolver's brand name, or null for a custom one (the caller localizes "Custom DNS"). */
    fun dnsShortOrNull(dns: String): String? = dnsShort(dns).takeIf { it != "Custom DNS" }

    fun hub(settings: AppSettings, page: SettingsPage, update: UpdateUi, versionName: String): String = when (page) {
        SettingsPage.CONNECTION -> "${scoring(settings.scoringProfile)} · ${engineShort(settings.preferredEngine)}"
        SettingsPage.ROUTING_DNS -> "${routingShort(settings.routingMode)} · ${dnsShort(settings.dnsServer)}"
        SettingsPage.ANTI_CENSORSHIP -> buildString {
            append(if (settings.defaultDesyncConfig.enabled) "SNI spoofing ${settings.defaultDesyncConfig.profile.displayName}" else "SNI spoofing off")
            append(" · Free configs ")
            append(if (settings.freeConfigsEnabled) "on" else "off")
        }
        SettingsPage.PRIVACY -> listOfNotNull(
            if (settings.killSwitchEnabled) "Kill switch" else null,
            if (settings.autoReconnect) "Auto-reconnect" else null,
            if (settings.ipv6Enabled) "IPv6 routed" else "IPv6 blocked"
        ).joinToString(" · ")
        SettingsPage.SHARING -> if (settings.hotspotShareEnabled) "Hotspot sharing on" else "Hotspot sharing off"
        SettingsPage.APPEARANCE -> if (settings.darkTheme) "Dark theme" else "Light theme"
        SettingsPage.UPDATES -> when {
            update.checking -> "Checking for updates…"
            update.hasUpdate -> "Update available" + (update.latestVersion?.let { " · v$it" } ?: "")
            update.checkFailed -> "Update check unavailable · v$versionName"
            update.checked -> "Up to date · v$versionName"
            else -> "Version $versionName · cores, resolvers, clean IPs"
        }
        SettingsPage.ENGINES -> "Bundled engines and configuration"
        SettingsPage.DIAGNOSTICS -> "Live logs, engine config, reset"
        SettingsPage.ABOUT -> "DrFXAi · community and support"
    }
}
