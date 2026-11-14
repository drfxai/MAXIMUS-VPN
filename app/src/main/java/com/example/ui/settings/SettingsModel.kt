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
    CONNECTION, ROUTING_DNS, ANTI_CENSORSHIP, PRIVACY, SHARING, APPEARANCE, UPDATES, DIAGNOSTICS, ABOUT
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
    val latestVersion: String? = null
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
    }

    fun routingTitle(mode: RoutingMode): String = when (mode) {
        RoutingMode.RULE_BYPASS_LAN -> "Bypass LAN & local"
        RoutingMode.GLOBAL -> "Global proxy"
        RoutingMode.BYPASS_SELECTED -> "Custom bypass list"
    }

    fun routingDescription(mode: RoutingMode): String = when (mode) {
        RoutingMode.RULE_BYPASS_LAN -> "LAN and private IPs go direct; everything else is tunnelled"
        RoutingMode.GLOBAL -> "All traffic goes through the tunnel"
        RoutingMode.BYPASS_SELECTED -> "Your custom domain and IP patterns go direct"
    }

    /** Order shown in the routing list (unchanged from the previous screen). */
    val routingModes = listOf(RoutingMode.RULE_BYPASS_LAN, RoutingMode.GLOBAL, RoutingMode.BYPASS_SELECTED)

    /** The DNS presets offered before; values are the exact strings stored before. */
    val dnsPresets = listOf(
        "https://1.1.1.1/dns-query" to "Cloudflare DoH (Encrypted)",
        "https://8.8.8.8/dns-query" to "Google DoH (Encrypted)",
        "https://9.9.9.9/dns-query" to "Quad9 DoH (Secure)",
        "1.1.1.1" to "Cloudflare UDP (1.1.1.1)",
        "8.8.8.8" to "Google DNS UDP (8.8.8.8)",
        "9.9.9.9" to "Quad9 Secure UDP (9.9.9.9)"
    )

    fun dns(dns: String): String = when (dns) {
        "1.1.1.1" -> "Cloudflare UDP (1.1.1.1)"
        "8.8.8.8" -> "Google DNS UDP (8.8.8.8)"
        "9.9.9.9" -> "Quad9 Secure UDP (9.9.9.9)"
        "https://1.1.1.1/dns-query" -> "Cloudflare DoH (Encrypted)"
        "https://8.8.8.8/dns-query", "https://dns.google/dns-query" -> "Google DoH (Encrypted)"
        "https://9.9.9.9/dns-query", "https://dns.quad9.net/dns-query" -> "Quad9 DoH (Secure)"
        else -> "Custom DNS"
    }

    fun dnsShort(dns: String): String = when (dns) {
        "1.1.1.1" -> "Cloudflare DNS"
        "8.8.8.8" -> "Google DNS"
        "9.9.9.9" -> "Quad9 DNS"
        "https://1.1.1.1/dns-query" -> "Cloudflare DoH"
        "https://8.8.8.8/dns-query", "https://dns.google/dns-query" -> "Google DoH"
        "https://9.9.9.9/dns-query", "https://dns.quad9.net/dns-query" -> "Quad9 DoH"
        else -> "Custom DNS"
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

    fun hub(settings: AppSettings, page: SettingsPage, update: UpdateUi, versionName: String): String = when (page) {
        SettingsPage.CONNECTION -> "${scoring(settings.scoringProfile)} · ${engineShort(settings.preferredEngine)}"
        SettingsPage.ROUTING_DNS -> "${routingShort(settings.routingMode)} · ${dnsShort(settings.dnsServer)}"
        SettingsPage.ANTI_CENSORSHIP -> buildString {
            append(if (settings.defaultDesyncConfig.enabled) "SNI spoofing ${settings.defaultDesyncConfig.profile.displayName}" else "SNI spoofing off")
            append(" · Free configs ")
            append(if (settings.freeConfigsEnabled) "on" else "off")
        }
        SettingsPage.PRIVACY -> listOfNotNull(
            if (settings.killSwitchEnabled) "Packet guard" else null,
            if (settings.autoReconnect) "Auto-reconnect" else null,
            if (settings.ipv6Enabled) "IPv6 routed" else "IPv6 blocked"
        ).joinToString(" · ")
        SettingsPage.SHARING -> if (settings.hotspotShareEnabled) "Hotspot sharing on" else "Hotspot sharing off"
        SettingsPage.APPEARANCE -> if (settings.darkTheme) "Dark theme" else "Light theme"
        SettingsPage.UPDATES -> when {
            update.checking -> "Checking for updates…"
            update.hasUpdate -> "Update available" + (update.latestVersion?.let { " · v$it" } ?: "")
            update.checked -> "Up to date · v$versionName"
            else -> "Version $versionName · cores, resolvers, clean IPs"
        }
        SettingsPage.DIAGNOSTICS -> "Live logs, engine config, reset"
        SettingsPage.ABOUT -> "DrFXAi · community and support"
    }
}
