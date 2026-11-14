package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettings
import com.example.data.model.DesyncProfile
import com.example.data.model.ScoringProfile
import com.example.ui.theme.AppTheme

/* ------------------------------------------------------------------ Connection */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsPage(
    settings: AppSettings,
    actions: SettingsActions,
    learnedSummary: String,
    localProxy: LocalProxyUi,
    onOpenLearnedNetworks: () -> Unit,
    onToggleLocalProxy: () -> Unit,
    onBack: () -> Unit
) {
    var showScoring by rememberSaveable { mutableStateOf(false) }
    var showEngines by rememberSaveable { mutableStateOf(false) }
    Column {
        SettingsTopBar("Connection", onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel("Smart Connect optimises for", first = true)
            SettingsGroup {
                Column(Modifier.padding(16.dp)) {
                    SettingsChips(
                        options = ScoringProfile.values().toList(),
                        selected = settings.scoringProfile,
                        label = SettingsLabels::scoring,
                        icon = SettingsLabels::scoringIcon,
                        onSelect = actions::setScoringProfile
                    )
                }
                SettingsDivider(inset = false)
                SettingsNavRow(
                    title = "How servers are scored",
                    subtitle = settings.scoringProfile.description,
                    icon = Icons.Default.Info,
                    iconTint = AppTheme.colors.textSecondary,
                    onClick = { showScoring = true }
                )
            }

            SettingsSectionLabel("Failover")
            SettingsGroup {
                SettingsSwitchRow(
                    title = "Automated failover",
                    subtitle = "Switch to the next best server on >30% loss or >800 ms",
                    icon = Icons.Default.SwapHoriz,
                    checked = settings.autoFailoverEnabled,
                    onCheckedChange = actions::setAutoFailover
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Power button fallback",
                    subtitle = if (settings.powerButtonFallback)
                        "Your server first; recovery engines (MASQUE, Psiphon, Tor) only if it carries no traffic"
                    else "Starts only the selected config",
                    icon = Icons.Default.PowerSettingsNew,
                    checked = settings.powerButtonFallback,
                    onCheckedChange = actions::setPowerButtonFallback
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "Learned network data",
                    subtitle = learnedSummary,
                    icon = Icons.Default.Psychology,
                    onClick = onOpenLearnedNetworks
                )
            }

            SettingsSectionLabel("Proxy core")
            SettingsGroup {
                SettingsNavRow(
                    title = "Engine",
                    subtitle = SettingsLabels.engineTitle(settings.preferredEngine),
                    icon = Icons.Default.Memory,
                    modifier = Modifier.testTag("settings_engine_row"),
                    onClick = { showEngines = true }
                )
            }

            SettingsAdvanced(title = "Local proxy mode") {
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Local SOCKS5 proxy", color = AppTheme.colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Serves the selected server to apps configured with SOCKS5. Device-wide VPN and the packet guard are inactive in this mode.",
                            color = AppTheme.colors.textMuted, fontSize = 12.5.sp, lineHeight = 17.sp
                        )
                        localProxy.error?.let { Text(it, color = AppTheme.colors.statusWarning, fontSize = 12.5.sp) }
                        if (localProxy.running) {
                            SelectionContainer {
                                Text(
                                    "SOCKS5 127.0.0.1:${localProxy.port}\nUsername: ${localProxy.user}\nPassword: ${localProxy.password}",
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AppTheme.colors.primary
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        val active = localProxy.running || localProxy.starting
                        Button(
                            onClick = onToggleLocalProxy,
                            enabled = active || localProxy.canStart,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (active) AppTheme.colors.surfaceElevated else AppTheme.colors.primary,
                                contentColor = if (active) AppTheme.colors.textPrimary else AppTheme.colors.onPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when {
                                    localProxy.starting -> "Cancel proxy startup"
                                    localProxy.running -> "Stop local proxy"
                                    else -> "Start selected server as local proxy"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showScoring) {
        ModalBottomSheet(
            onDismissRequest = { showScoring = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppTheme.colors.surfaceCard
        ) {
            SheetTitle("How servers are scored", "Smart Connect ranks servers with these weights")
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                ScoringProfile.values().forEach { profile ->
                    SettingsChoiceRow(
                        title = SettingsLabels.scoring(profile),
                        subtitle = profile.description,
                        icon = SettingsLabels.scoringIcon(profile),
                        selected = settings.scoringProfile == profile,
                        onSelect = { actions.setScoringProfile(profile); showScoring = false }
                    )
                }
            }
        }
    }

    if (showEngines) {
        ModalBottomSheet(
            onDismissRequest = { showEngines = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppTheme.colors.surfaceCard
        ) {
            SheetTitle("Proxy core", "Used for configs that more than one engine can run")
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                SettingsLabels.engines.forEach { engine ->
                    SettingsChoiceRow(
                        title = SettingsLabels.engineTitle(engine),
                        subtitle = SettingsLabels.engineDescription(engine),
                        selected = settings.preferredEngine == engine,
                        onSelect = { actions.setPreferredEngine(engine); showEngines = false }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Text(title, color = AppTheme.colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = AppTheme.colors.textMuted, fontSize = 13.sp)
        }
    }
}

/* ------------------------------------------------------------------ Routing & DNS */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingDnsSettingsPage(
    settings: AppSettings,
    actions: SettingsActions,
    dnsTestRunning: Boolean,
    dnsTestResult: String?,
    onCustomDns: () -> Unit,
    onTestVpnDns: () -> Unit,
    onTestResolver: () -> Unit,
    onOpenResolverData: () -> Unit,
    onBack: () -> Unit
) {
    var showDns by rememberSaveable { mutableStateOf(false) }
    Column {
        SettingsTopBar("Routing & DNS", onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel("Routing", first = true)
            SettingsGroup {
                SettingsLabels.routingModes.forEachIndexed { index, mode ->
                    if (index > 0) SettingsDivider(inset = false)
                    SettingsChoiceRow(
                        title = SettingsLabels.routingTitle(mode),
                        subtitle = SettingsLabels.routingDescription(mode),
                        selected = settings.routingMode == mode,
                        onSelect = { actions.setRoutingMode(mode) }
                    )
                }
            }

            SettingsSectionLabel("DNS")
            SettingsGroup {
                SettingsNavRow(
                    title = "DNS provider",
                    subtitle = SettingsLabels.dns(settings.dnsServer),
                    icon = Icons.Default.Dns,
                    modifier = Modifier.testTag("settings_dns_row"),
                    onClick = { showDns = true }
                )
                SettingsDivider()
                SettingsNavRow(
                    title = if (dnsTestRunning) "Testing VPN DNS…" else "Test current VPN DNS",
                    icon = Icons.Default.NetworkCheck,
                    enabled = !dnsTestRunning,
                    trailing = { if (dnsTestRunning) SmallSpinner() },
                    onClick = onTestVpnDns
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "Test selected resolver",
                    icon = Icons.Default.TravelExplore,
                    enabled = !dnsTestRunning,
                    trailing = {},
                    onClick = onTestResolver
                )
            }
            SettingsFootnote(dnsTestResult ?: "DNS changes apply the next time you connect.")

            SettingsSectionLabel("Resolvers")
            SettingsGroup {
                SettingsNavRow(
                    title = "Resolver databases",
                    subtitle = "Update, roll back and refresh clean IPs",
                    icon = Icons.Default.Storage,
                    onClick = onOpenResolverData
                )
            }
        }
    }

    if (showDns) {
        ModalBottomSheet(
            onDismissRequest = { showDns = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppTheme.colors.surfaceCard
        ) {
            SheetTitle("DNS provider", "Upstream DNS or DNS-over-HTTPS")
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                val default = AppSettings().dnsServer
                SettingsChoiceRow(
                    title = "Use default DNS",
                    subtitle = SettingsLabels.dns(default),
                    selected = false,
                    onSelect = { actions.setDnsServer(default); showDns = false }
                )
                SettingsLabels.dnsPresets.forEach { (value, label) ->
                    SettingsChoiceRow(
                        title = label,
                        selected = settings.dnsServer == value,
                        onSelect = { actions.setDnsServer(value); showDns = false }
                    )
                }
                val isCustom = SettingsLabels.dnsPresets.none { it.first == settings.dnsServer }
                SettingsChoiceRow(
                    title = "Custom DNS…",
                    subtitle = if (isCustom) settings.dnsServer else "IP address or DoH URL",
                    selected = isCustom,
                    onSelect = { showDns = false; onCustomDns() }
                )
            }
        }
    }
}

@Composable
private fun SmallSpinner() {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppTheme.colors.primary)
}

/* ------------------------------------------------------------------ Anti-censorship */

@Composable
fun AntiCensorshipSettingsPage(
    settings: AppSettings,
    actions: SettingsActions,
    onBack: () -> Unit
) {
    val desync = settings.defaultDesyncConfig
    Column {
        SettingsTopBar("Anti-censorship", onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel("SNI spoofing & bypass engine", first = true)
            SettingsGroup {
                SettingsSwitchRow(
                    title = "Desync packet mutation",
                    subtitle = "Split, reorder and inject a decoy SNI to get past DPI filters",
                    icon = Icons.Default.VpnLock,
                    checked = desync.enabled,
                    onCheckedChange = { enabled ->
                        actions.updateSettings(settings.copy(defaultDesyncConfig = desync.copy(enabled = enabled)))
                    }
                )
                if (desync.enabled) {
                    SettingsDivider(inset = false)
                    Column(Modifier.padding(16.dp)) {
                        Text("Preset", color = AppTheme.colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        SettingsChips(
                            options = SettingsLabels.desyncPresets,
                            selected = desync.profile,
                            label = { it.displayName },
                            onSelect = { profile ->
                                actions.updateSettings(
                                    settings.copy(
                                        defaultDesyncConfig = desync.copy(
                                            profile = profile,
                                            method = SettingsLabels.desyncMethodFor(profile, desync.method)
                                        )
                                    )
                                )
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            SettingsLabels.desyncDescription(desync.profile),
                            color = AppTheme.colors.textMuted, fontSize = 12.5.sp, lineHeight = 17.sp
                        )
                    }
                }
            }
            if (desync.enabled) {
                SettingsAdvanced(title = "Advanced · fake SNI pool") {
                    SettingsGroup {
                        Column(Modifier.padding(16.dp)) {
                            Text("Multi-domain fake SNI pool", color = AppTheme.colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text("Comma-separated decoy domains", color = AppTheme.colors.textMuted, fontSize = 12.5.sp)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = desync.fakeSniPool,
                                onValueChange = { pool ->
                                    actions.updateSettings(settings.copy(defaultDesyncConfig = desync.copy(fakeSniPool = pool)))
                                },
                                placeholder = { Text("www.google.com, www.microsoft.com, speedtest.net", fontSize = 12.sp) },
                                colors = settingsFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            SettingsSectionLabel("Free configs")
            SettingsGroup {
                SettingsSwitchRow(
                    title = "Free configs",
                    subtitle = if (settings.freeConfigsEnabled)
                        "Downloads the free public server list into Servers and Free Configs"
                    else "Nothing is downloaded, tested or shown",
                    icon = Icons.Default.Public,
                    checked = settings.freeConfigsEnabled,
                    onCheckedChange = actions::setFreeConfigs
                )
            }
        }
    }
}

@Composable
internal fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AppTheme.colors.surfaceElevated,
    unfocusedContainerColor = AppTheme.colors.surfaceElevated,
    focusedBorderColor = AppTheme.colors.primary,
    unfocusedBorderColor = AppTheme.colors.borderSubtle,
    focusedTextColor = AppTheme.colors.textPrimary,
    unfocusedTextColor = AppTheme.colors.textPrimary
)

/* ------------------------------------------------------------------ Privacy & security */

@Composable
fun PrivacySettingsPage(
    settings: AppSettings,
    actions: SettingsActions,
    onOpenAndroidVpnSettings: () -> Unit,
    onBack: () -> Unit
) {
    Column {
        SettingsTopBar("Privacy & security", onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel("Protection", first = true)
            SettingsGroup {
                SettingsSwitchRow(
                    title = "In-tunnel packet guard",
                    subtitle = "Blocks leaks inside the tunnel while it reconnects",
                    icon = Icons.Default.Lock,
                    checked = settings.killSwitchEnabled,
                    onCheckedChange = actions::setKillSwitch
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "Always-on VPN",
                    subtitle = "Open Android settings: Always-on and Block connections without VPN",
                    icon = Icons.Default.VpnLock,
                    trailing = { ExternalIcon() },
                    onClick = onOpenAndroidVpnSettings
                )
            }
            SettingsFootnote("For system-wide blocking during disconnects, also enable Android's Always-on VPN and Block connections without VPN.")

            SettingsSectionLabel("Network")
            SettingsGroup {
                SettingsSwitchRow(
                    title = "Route IPv6 through VPN",
                    subtitle = "Native Xray routes IPv6; it is blocked on the IPv4-only compatibility tunnel",
                    icon = Icons.Default.Route,
                    checked = settings.ipv6Enabled,
                    onCheckedChange = actions::setIpv6
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Auto-reconnect",
                    subtitle = "Re-establish the tunnel when switching between Wi-Fi and mobile data",
                    icon = Icons.Default.SyncAlt,
                    checked = settings.autoReconnect,
                    onCheckedChange = actions::setAutoReconnect
                )
            }
        }
    }
}

@Composable
internal fun ExternalIcon() {
    Icon(
        Icons.AutoMirrored.Filled.OpenInNew,
        contentDescription = null,
        tint = AppTheme.colors.textMuted,
        modifier = Modifier.size(18.dp)
    )
}

/* ------------------------------------------------------------------ Sharing */

/** Client details for hotspot sharing, or null while no hotspot address is known. */
data class HotspotShareUi(val socks: String, val http: String, val user: String, val password: String)

@Composable
fun SharingSettingsPage(
    settings: AppSettings,
    actions: SettingsActions,
    share: HotspotShareUi?,
    onCopy: (value: String, label: String) -> Unit,
    onBack: () -> Unit
) {
    Column {
        SettingsTopBar("Sharing", onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel("Hotspot", first = true)
            SettingsGroup {
                SettingsSwitchRow(
                    title = "Share VPN over hotspot",
                    subtitle = "Password-protected proxy for devices on your hotspot; works only while connected",
                    icon = Icons.Default.WifiTethering,
                    checked = settings.hotspotShareEnabled,
                    onCheckedChange = actions::setHotspotShare
                )
            }
            if (settings.hotspotShareEnabled) {
                SettingsSectionLabel("On the other device")
                SettingsGroup {
                    if (share == null) {
                        Text(
                            "Turn on your phone's hotspot, then connect the VPN. The address to use on other devices will appear here.",
                            color = AppTheme.colors.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        CopyRow("SOCKS5 proxy", share.socks) { onCopy(share.socks, "SOCKS5 address") }
                        SettingsDivider(inset = false)
                        CopyRow("HTTP proxy", share.http) { onCopy(share.http, "HTTP address") }
                        SettingsDivider(inset = false)
                        CopyRow("Username", share.user) { onCopy(share.user, "Username") }
                        SettingsDivider(inset = false)
                        CopyRow("Password", share.password) { onCopy(share.password, "Password") }
                    }
                }
                SettingsFootnote("Only devices on your hotspot can connect, and only with these credentials. Changes take effect on the next connect.")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = actions::regenerateHotspotCredentials,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Generate new password")
                }
            }
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit) {
    SettingsNavRow(
        title = value,
        subtitle = label,
        trailing = {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label", tint = AppTheme.colors.primary, modifier = Modifier.size(18.dp))
        },
        onClick = onCopy
    )
}

/* ------------------------------------------------------------------ Appearance */

@Composable
fun AppearanceSettingsPage(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    Column {
        SettingsTopBar("Appearance", onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel("Theme", first = true)
            SettingsGroup {
                SettingsChoiceRow(
                    title = "Dark",
                    subtitle = "Easy on the eyes at night",
                    icon = Icons.Default.DarkMode,
                    selected = settings.darkTheme,
                    onSelect = { actions.setDarkTheme(true) }
                )
                SettingsDivider()
                SettingsChoiceRow(
                    title = "Light",
                    subtitle = "Bright and high-contrast",
                    icon = Icons.Default.LightMode,
                    selected = !settings.darkTheme,
                    onSelect = { actions.setDarkTheme(false) }
                )
            }
            SettingsFootnote("You can also switch with the sun/moon toggle on Home.")
        }
    }
}

/* ------------------------------------------------------------------ Updates & data */

data class UpdatesDataUi(
    val resolverBusy: Boolean = false,
    val resolverProgress: Int = -1,
    val resolverInstalled: Boolean = false,
    val resolverStatus: String? = null,
    val cleanIpBusy: Boolean = false,
    val cleanIpStatus: String? = null,
    val vpnOnlyUpdates: Boolean = false
)

@Composable
fun UpdatesSettingsPage(
    versionName: String,
    update: UpdateUi,
    data: UpdatesDataUi,
    onCheckForUpdates: () -> Unit,
    onCoreManager: () -> Unit,
    onUpdateResolvers: () -> Unit,
    onRollbackResolvers: () -> Unit,
    onRefreshCleanIps: () -> Unit,
    onVpnOnlyUpdates: (Boolean) -> Unit,
    onChangelog: () -> Unit,
    onBack: () -> Unit
) {
    Column {
        SettingsTopBar("Updates & data", onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel("App", first = true)
            SettingsGroup {
                SettingsNavRow(
                    title = "Check for updates",
                    subtitle = "Version $versionName · stable channel",
                    icon = Icons.Default.SystemUpdate,
                    trailing = {
                        if (update.checking) SmallSpinner() else UpdateChip(update, onCheckForUpdates)
                    },
                    onClick = onCheckForUpdates
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "VPN-only updates",
                    subtitle = "No direct fallback when the VPN is disconnected",
                    icon = Icons.Default.Lock,
                    checked = data.vpnOnlyUpdates,
                    onCheckedChange = onVpnOnlyUpdates
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "Changelog",
                    subtitle = "What's new in recent versions",
                    icon = Icons.AutoMirrored.Filled.Article,
                    trailing = { ExternalIcon() },
                    onClick = onChangelog
                )
            }

            SettingsSectionLabel("Network data")
            SettingsGroup {
                SettingsNavRow(
                    title = "Update resolver databases",
                    subtitle = when {
                        data.resolverBusy -> "Downloading verified data… ${data.resolverProgress.coerceAtLeast(0)}%"
                        data.resolverInstalled -> "Verified update installed · rollback available"
                        else -> "Six resolver lists · SHA-256 checked"
                    },
                    icon = Icons.Default.Storage,
                    trailing = { if (data.resolverBusy) SmallSpinner() else SettingsChevron() },
                    onClick = onUpdateResolvers
                )
                if (data.resolverInstalled) {
                    SettingsDivider()
                    SettingsNavRow(
                        title = "Roll back resolver databases",
                        subtitle = "Restore the previously verified list set",
                        icon = Icons.Default.Restore,
                        iconTint = AppTheme.colors.textSecondary,
                        onClick = onRollbackResolvers
                    )
                }
                SettingsDivider()
                SettingsNavRow(
                    title = "Refresh verified clean IPs",
                    subtitle = if (data.cleanIpBusy) "Testing the selected CDN node…" else "Retest endpoints for the selected node on this network",
                    icon = Icons.Default.Speed,
                    trailing = { if (data.cleanIpBusy) SmallSpinner() else SettingsChevron() },
                    onClick = onRefreshCleanIps
                )
            }
            listOfNotNull(data.resolverStatus, data.cleanIpStatus).forEach { SettingsFootnote(it) }

            SettingsSectionLabel("Engines")
            SettingsGroup {
                SettingsNavRow(
                    title = "Core manager",
                    subtitle = "Bundled engines, availability and update policy",
                    icon = Icons.Default.Inventory2,
                    onClick = onCoreManager
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ Diagnostics */

@Composable
fun DiagnosticsSettingsPage(
    onViewLogs: () -> Unit,
    onViewConfig: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    Column {
        SettingsTopBar("Diagnostics", onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel("Engine", first = true)
            SettingsGroup {
                SettingsNavRow(
                    title = "Live logs & telemetry",
                    subtitle = "Connection events, engine output and tests",
                    icon = Icons.Default.Terminal,
                    modifier = Modifier.testTag("view_diagnostics_button"),
                    onClick = onViewLogs
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "Raw engine config",
                    subtitle = "Generated JSON with secrets redacted",
                    icon = Icons.Default.Code,
                    modifier = Modifier.testTag("view_xray_config_button"),
                    onClick = onViewConfig
                )
            }

            Spacer(Modifier.height(32.dp))
            SettingsGroup {
                SettingsNavRow(
                    title = "Reset all settings",
                    subtitle = "Restore routing and engine settings to defaults",
                    icon = Icons.Default.Restore,
                    iconTint = AppTheme.colors.statusError,
                    titleColor = AppTheme.colors.statusError,
                    trailing = {},
                    modifier = Modifier.testTag("settings_reset"),
                    onClick = onReset
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ About & community */

@Composable
fun AboutSettingsPage(
    versionName: String,
    coreSummary: String,
    cryptoSubtitle: String,
    onTelegram: () -> Unit,
    onYouTube: () -> Unit,
    onGitHub: () -> Unit,
    onCrypto: () -> Unit,
    onCoffee: () -> Unit,
    telegramLogo: @Composable () -> Unit,
    youTubeLogo: @Composable () -> Unit,
    gitHubLogo: @Composable () -> Unit,
    onBack: () -> Unit
) {
    Column {
        SettingsTopBar("About & community", onBack = onBack)
        SettingsPageColumn {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(size = 52)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Maximus VPN", color = AppTheme.colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Version $versionName", color = AppTheme.colors.textMuted, fontSize = 13.sp)
                    }
                }
                SettingsDivider(inset = false)
                SettingsInfoLine("Developer", "DrFXAi")
                SettingsInfoLine("Engines", coreSummary)
                SettingsInfoLine("Protocols", "VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · TUIC · SOCKS5 · HTTP")
                Spacer(Modifier.height(4.dp))
            }

            SettingsSectionLabel("Community")
            SettingsGroup {
                SettingsNavRow(title = "Telegram", subtitle = "News, updates and community", leading = { LogoTile(telegramLogo) }, trailing = { ExternalIcon() }, onClick = onTelegram)
                SettingsDivider()
                SettingsNavRow(title = "YouTube", subtitle = "Tutorials and guides", leading = { LogoTile(youTubeLogo) }, trailing = { ExternalIcon() }, onClick = onYouTube)
                SettingsDivider()
                SettingsNavRow(title = "GitHub", subtitle = "Source code, issues and contributions", leading = { LogoTile(gitHubLogo) }, trailing = { ExternalIcon() }, onClick = onGitHub)
            }

            SettingsSectionLabel("Support Maximus")
            SettingsGroup {
                SettingsNavRow(title = "Support with crypto", subtitle = cryptoSubtitle, icon = Icons.Default.CurrencyBitcoin, onClick = onCrypto)
                SettingsDivider()
                SettingsNavRow(title = "Buy a coffee", subtitle = "Support the developer", icon = Icons.Default.LocalCafe, onClick = onCoffee)
            }
            SettingsFootnote("Executable engine upgrades ship only as signed APKs.", Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun LogoTile(logo: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(AppTheme.colors.surfaceElevated),
        contentAlignment = Alignment.Center
    ) { logo() }
}
