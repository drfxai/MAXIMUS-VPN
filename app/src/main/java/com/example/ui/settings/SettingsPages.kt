package com.example.ui.settings

import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettings
import com.example.data.model.DesyncProfile
import com.example.data.model.ScoringProfile
import com.example.ui.theme.AppTheme
import com.example.vpn.lab.CoreCapabilityRegistry
import com.example.vpn.sidecar.RadianceSidecar
import com.example.vpn.sidecar.Sidecars

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
        SettingsTopBar(stringResource(R.string.ui_connection), onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel(stringResource(R.string.ui_smart_connect_optimises_for), first = true)
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
                    title = stringResource(R.string.ui_how_servers_are_scored),
                    subtitle = settings.scoringProfile.description,
                    icon = Icons.Default.Info,
                    iconTint = AppTheme.colors.textSecondary,
                    onClick = { showScoring = true }
                )
            }

            SettingsSectionLabel(stringResource(R.string.ui_failover))
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_automated_failover),
                    subtitle = stringResource(R.string.ui_switch_to_the_next_best),
                    icon = Icons.Default.SwapHoriz,
                    checked = settings.autoFailoverEnabled,
                    onCheckedChange = actions::setAutoFailover
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_power_button_fallback),
                    subtitle = if (settings.powerButtonFallback)
                        stringResource(R.string.ui_your_server_first_recovery_engines)
                    else stringResource(R.string.ui_starts_only_the_selected_config),
                    icon = Icons.Default.PowerSettingsNew,
                    checked = settings.powerButtonFallback,
                    onCheckedChange = actions::setPowerButtonFallback
                )
                SettingsDivider()
                SettingsNavRow(
                    title = stringResource(R.string.ui_learned_network_data),
                    subtitle = learnedSummary,
                    icon = Icons.Default.Psychology,
                    onClick = onOpenLearnedNetworks
                )
            }

            SettingsSectionLabel(stringResource(R.string.ui_proxy_core))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ui_engine),
                    subtitle = SettingsLabels.engineTitle(settings.preferredEngine),
                    icon = Icons.Default.Memory,
                    modifier = Modifier.testTag("settings_engine_row"),
                    onClick = { showEngines = true }
                )
            }

            SettingsAdvanced(title = stringResource(R.string.ui_local_proxy_mode)) {
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ui_local_socks5_proxy), color = AppTheme.colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.ui_serves_the_selected_server_to),
                            color = AppTheme.colors.textMuted, fontSize = 12.5.sp, lineHeight = 17.sp
                        )
                        localProxy.error?.let { Text(it, color = AppTheme.colors.statusWarning, fontSize = 12.5.sp) }
                        if (localProxy.running) {
                            SelectionContainer {
                                Text(
                                    stringResource(R.string.ui_socks5_127_0_0_1, localProxy.port, localProxy.user, localProxy.password),
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
                                    localProxy.starting -> stringResource(R.string.ui_cancel_proxy_startup)
                                    localProxy.running -> stringResource(R.string.ui_stop_local_proxy)
                                    else -> stringResource(R.string.ui_start_selected_server_as_local)
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
            SheetTitle(stringResource(R.string.ui_how_servers_are_scored), stringResource(R.string.ui_smart_connect_ranks_servers_with))
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
            SheetTitle(stringResource(R.string.ui_proxy_core), stringResource(R.string.ui_used_for_configs_that_more))
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
    onCompareResolvers: () -> Unit,
    onOpenResolverData: () -> Unit,
    onBack: () -> Unit,
    splitTunnelSummary: String? = null,
    onOpenSplitTunnel: (() -> Unit)? = null
) {
    var showDns by rememberSaveable { mutableStateOf(false) }
    Column {
        SettingsTopBar(stringResource(R.string.ui_routing_dns), onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel(stringResource(R.string.ui_routing), first = true)
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

            if (onOpenSplitTunnel != null) {
                SettingsSectionLabel(stringResource(R.string.ui_apps))
                SettingsGroup {
                    SettingsNavRow(
                        title = stringResource(R.string.ui_per_app_routing),
                        subtitle = splitTunnelSummary ?: "Every app on this phone",
                        icon = Icons.Default.Apps,
                        modifier = Modifier.testTag("settings_split_tunnel_row"),
                        onClick = onOpenSplitTunnel
                    )
                }
            }

            SettingsSectionLabel("DNS")
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ui_dns_provider),
                    subtitle = SettingsLabels.dns(settings.dnsServer),
                    icon = Icons.Default.Dns,
                    modifier = Modifier.testTag("settings_dns_row"),
                    onClick = { showDns = true }
                )
                SettingsDivider()
                SettingsNavRow(
                    title = stringResource(R.string.ui_test_current_vpn_dns),
                    icon = Icons.Default.NetworkCheck,
                    enabled = !dnsTestRunning,
                    trailing = { if (dnsTestRunning) SmallSpinner() },
                    onClick = onTestVpnDns
                )
                SettingsDivider()
                SettingsNavRow(
                    title = stringResource(R.string.ui_test_selected_resolver),
                    icon = Icons.Default.TravelExplore,
                    enabled = !dnsTestRunning,
                    trailing = {},
                    onClick = onTestResolver
                )
                SettingsDivider()
                SettingsNavRow(
                    title = stringResource(R.string.ui_compare_encrypted_dns),
                    subtitle = stringResource(R.string.ui_measure_cloudflare_google_and_quad9),
                    icon = Icons.Default.Speed,
                    enabled = !dnsTestRunning,
                    onClick = onCompareResolvers
                )
            }
            SettingsFootnote(
                if (dnsTestRunning) stringResource(R.string.ui_testing_dns_on_this_connection)
                else dnsTestResult ?: "DNS changes apply the next time you connect."
            )

            SettingsSectionLabel(stringResource(R.string.ui_resolvers))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ui_resolver_databases),
                    subtitle = stringResource(R.string.ui_update_roll_back_and_refresh),
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
            SheetTitle(stringResource(R.string.ui_dns_provider), stringResource(R.string.ui_doh_encrypts_queries_udp_presets))
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                val default = AppSettings().dnsServer
                SettingsChoiceRow(
                    title = stringResource(R.string.ui_use_default_dns),
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
                    title = stringResource(R.string.ui_custom_dns),
                    subtitle = if (isCustom) settings.dnsServer else stringResource(R.string.ui_ip_address_or_doh_url),
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
        SettingsTopBar(stringResource(R.string.ui_anti_censorship), onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel(stringResource(R.string.ui_sni_spoofing_bypass_engine), first = true)
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_desync_packet_mutation),
                    subtitle = stringResource(R.string.ui_split_reorder_and_inject_a),
                    icon = Icons.Default.VpnLock,
                    checked = desync.enabled,
                    onCheckedChange = { enabled ->
                        actions.updateSettings(settings.copy(defaultDesyncConfig = desync.copy(enabled = enabled)))
                    }
                )
                if (desync.enabled) {
                    SettingsDivider(inset = false)
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.ui_preset), color = AppTheme.colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                SettingsAdvanced(title = stringResource(R.string.ui_advanced_fake_sni_pool)) {
                    SettingsGroup {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.ui_multi_domain_fake_sni_pool_2), color = AppTheme.colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(stringResource(R.string.ui_comma_separated_decoy_domains), color = AppTheme.colors.textMuted, fontSize = 12.5.sp)
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

            SettingsSectionLabel(stringResource(R.string.ui_free_configs_2))
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_free_configs_2),
                    subtitle = if (settings.freeConfigsEnabled)
                        stringResource(R.string.ui_downloads_the_free_public_server)
                    else stringResource(R.string.ui_nothing_is_downloaded_tested_or),
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
    onBack: () -> Unit,
    batteryUnrestricted: Boolean? = null,
    onRequestBackgroundRunning: (() -> Unit)? = null
) {
    Column {
        SettingsTopBar(stringResource(R.string.ui_privacy_security), onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel(stringResource(R.string.ui_protection), first = true)
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_in_tunnel_packet_guard),
                    subtitle = stringResource(R.string.ui_blocks_leaks_inside_the_tunnel),
                    icon = Icons.Default.Lock,
                    checked = settings.killSwitchEnabled,
                    onCheckedChange = actions::setKillSwitch
                )
                SettingsDivider()
                SettingsNavRow(
                    title = stringResource(R.string.ui_always_on_vpn),
                    subtitle = stringResource(R.string.ui_open_android_settings_always_on),
                    icon = Icons.Default.VpnLock,
                    trailing = { ExternalIcon() },
                    onClick = onOpenAndroidVpnSettings
                )
            }
            SettingsFootnote(stringResource(R.string.ui_for_system_wide_blocking_during))

            SettingsSectionLabel(stringResource(R.string.ui_network_2))
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_route_ipv6_through_vpn),
                    subtitle = stringResource(R.string.ui_native_xray_routes_ipv6_it),
                    icon = Icons.Default.Route,
                    checked = settings.ipv6Enabled,
                    onCheckedChange = actions::setIpv6
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_auto_reconnect),
                    subtitle = stringResource(R.string.ui_re_establish_the_tunnel_when),
                    icon = Icons.Default.SyncAlt,
                    checked = settings.autoReconnect,
                    onCheckedChange = actions::setAutoReconnect
                )
            }

            if (onRequestBackgroundRunning != null) {
                SettingsSectionLabel(stringResource(R.string.ui_startup_background))
                SettingsGroup {
                    SettingsSwitchRow(
                        title = stringResource(R.string.ui_connect_when_the_phone_starts),
                        subtitle = stringResource(R.string.ui_starts_the_config_selected_in),
                        icon = Icons.Default.PowerSettingsNew,
                        modifier = Modifier.testTag("settings_connect_on_boot"),
                        checked = settings.autoConnectOnBoot,
                        onCheckedChange = { actions.updateSettings(settings.copy(autoConnectOnBoot = it)) }
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        title = stringResource(R.string.ui_background_running),
                        subtitle = when (batteryUnrestricted) {
                            true -> stringResource(R.string.ui_allowed_android_will_not_stop)
                            false -> stringResource(R.string.ui_restricted_android_may_stop_the)
                            null -> stringResource(R.string.ui_let_maximus_keep_running_when)
                        },
                        icon = Icons.Default.BatteryChargingFull,
                        iconTint = if (batteryUnrestricted == false) AppTheme.colors.statusWarning else AppTheme.colors.primary,
                        modifier = Modifier.testTag("settings_background_running"),
                        trailing = { ExternalIcon() },
                        onClick = onRequestBackgroundRunning
                    )
                }
                SettingsFootnote(stringResource(R.string.ui_connect_on_boot_needs_vpn))
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
        SettingsTopBar(stringResource(R.string.ui_sharing), onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel(stringResource(R.string.ui_hotspot), first = true)
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_share_vpn_over_hotspot),
                    subtitle = stringResource(R.string.ui_password_protected_proxy_for_devices),
                    icon = Icons.Default.WifiTethering,
                    checked = settings.hotspotShareEnabled,
                    onCheckedChange = actions::setHotspotShare
                )
            }
            if (settings.hotspotShareEnabled) {
                SettingsSectionLabel(stringResource(R.string.ui_on_the_other_device))
                SettingsGroup {
                    if (share == null) {
                        Text(
                            stringResource(R.string.ui_turn_on_your_phone_s),
                            color = AppTheme.colors.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        CopyRow(stringResource(R.string.ui_socks5_proxy), share.socks) { onCopy(share.socks, "SOCKS5 address") }
                        SettingsDivider(inset = false)
                        CopyRow(stringResource(R.string.ui_http_proxy), share.http) { onCopy(share.http, "HTTP address") }
                        SettingsDivider(inset = false)
                        CopyRow(stringResource(R.string.ui_username), share.user) { onCopy(share.user, "Username") }
                        SettingsDivider(inset = false)
                        CopyRow(stringResource(R.string.ui_password), share.password) { onCopy(share.password, "Password") }
                    }
                }
                SettingsFootnote(stringResource(R.string.ui_only_devices_on_your_hotspot))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = actions::regenerateHotspotCredentials,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_generate_new_password))
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
        SettingsTopBar(stringResource(R.string.ui_appearance), onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel(stringResource(R.string.ui_theme), first = true)
            SettingsGroup {
                SettingsChoiceRow(
                    title = stringResource(R.string.ui_dark),
                    subtitle = stringResource(R.string.ui_easy_on_the_eyes_at),
                    icon = Icons.Default.DarkMode,
                    selected = settings.darkTheme,
                    onSelect = { actions.setDarkTheme(true) }
                )
                SettingsDivider()
                SettingsChoiceRow(
                    title = stringResource(R.string.ui_light),
                    subtitle = stringResource(R.string.ui_bright_and_high_contrast),
                    icon = Icons.Default.LightMode,
                    selected = !settings.darkTheme,
                    onSelect = { actions.setDarkTheme(false) }
                )
            }
            SettingsFootnote(stringResource(R.string.ui_you_can_also_switch_with))
            LanguageSettingsSection()
        }
    }
}

/** App language: applies at once and is remembered (Android 13+ also lists it in system app settings). */
@Composable
private fun LanguageSettingsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    val current = remember(context) { com.example.ui.language.LanguageStore.current(context) }
    SettingsSectionLabel(stringResource(R.string.settings_language))
    SettingsGroup {
        com.example.ui.language.AppLanguage.entries.forEachIndexed { index, language ->
            if (index > 0) SettingsDivider()
            SettingsChoiceRow(
                title = language.nativeName,
                subtitle = com.example.ui.language.LanguageStore.contextFor(context, language)
                    .getString(R.string.lang_picker_card_caption),
                icon = Icons.Default.Translate,
                selected = current == language,
                onSelect = { activity?.let { com.example.ui.language.LanguageStore.apply(it, language) } }
            )
        }
    }
    SettingsFootnote(stringResource(R.string.settings_language_footnote))
}

/* ------------------------------------------------------------------ Updates & data */

data class UpdatesDataUi(
    val resolverBusy: Boolean = false,
    val resolverProgress: Int = -1,
    val resolverInstalled: Boolean = false,
    val resolverStatus: String? = null,
    val cleanIpBusy: Boolean = false,
    val cleanIpStatus: String? = null,
    val vpnOnlyUpdates: Boolean = false,
    val geoTag: String = "",
    val geoBusy: Boolean = false,
    val geoStatus: String? = null,
    val geoViaVpn: Boolean = true
)

@Composable
fun UpdatesSettingsPage(
    versionName: String,
    update: UpdateUi,
    data: UpdatesDataUi,
    installedBuild: Int,
    onCheckForUpdates: () -> Unit,
    onUpdateResolvers: () -> Unit,
    onRollbackResolvers: () -> Unit,
    onRefreshCleanIps: () -> Unit,
    onVpnOnlyUpdates: (Boolean) -> Unit,
    onChangelog: () -> Unit,
    onBack: () -> Unit,
    onUpdateGeoFiles: () -> Unit = {},
    onGeoViaVpn: (Boolean) -> Unit = {}
) {
    Column {
        SettingsTopBar(stringResource(R.string.ui_app_updates), onBack = onBack)
        SettingsPageColumn {
            Spacer(Modifier.height(16.dp))
            UpdateOverview(
                title = "Maximus VPN",
                subtitle = if (installedBuild > 0) stringResource(R.string.ui_installed_build, installedBuild, versionName) else stringResource(R.string.ui_installed_version, versionName),
                icon = Icons.Default.SystemUpdate,
                update = update,
                onCheckForUpdates = onCheckForUpdates
            )
            SettingsSectionLabel(stringResource(R.string.ui_release_preferences))
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_vpn_only_updates),
                    subtitle = stringResource(R.string.ui_no_direct_fallback_when_the),
                    icon = Icons.Default.Lock,
                    checked = data.vpnOnlyUpdates,
                    onCheckedChange = onVpnOnlyUpdates
                )
                SettingsDivider()
                SettingsNavRow(
                    title = stringResource(R.string.ui_changelog),
                    subtitle = stringResource(R.string.ui_what_s_new_in_recent),
                    icon = Icons.AutoMirrored.Filled.Article,
                    trailing = { ExternalIcon() },
                    onClick = onChangelog
                )
            }

            SettingsSectionLabel(stringResource(R.string.ui_network_data))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ui_update_resolver_databases),
                    subtitle = when {
                        data.resolverBusy -> stringResource(R.string.ui_downloading_verified_data_pct, data.resolverProgress.coerceAtLeast(0))
                        data.resolverInstalled -> stringResource(R.string.ui_verified_update_installed_rollback_avail)
                        else -> stringResource(R.string.ui_six_resolver_lists_sha_256)
                    },
                    icon = Icons.Default.Storage,
                    trailing = { if (data.resolverBusy) SmallSpinner() else SettingsChevron() },
                    onClick = onUpdateResolvers
                )
                if (data.resolverInstalled) {
                    SettingsDivider()
                    SettingsNavRow(
                        title = stringResource(R.string.ui_roll_back_resolver_databases),
                        subtitle = stringResource(R.string.ui_restore_the_previously_verified_list),
                        icon = Icons.Default.Restore,
                        iconTint = AppTheme.colors.textSecondary,
                        onClick = onRollbackResolvers
                    )
                }
                SettingsDivider()
                SettingsNavRow(
                    title = stringResource(R.string.ui_refresh_verified_clean_ips),
                    subtitle = if (data.cleanIpBusy) stringResource(R.string.ui_testing_the_selected_cdn_node) else stringResource(R.string.ui_retest_endpoints_for_the_selected),
                    icon = Icons.Default.Speed,
                    trailing = { if (data.cleanIpBusy) SmallSpinner() else SettingsChevron() },
                    onClick = onRefreshCleanIps
                )
            }
            listOfNotNull(data.resolverStatus, data.cleanIpStatus).forEach { SettingsFootnote(it) }

            SettingsSectionLabel(stringResource(R.string.ui_geofiles))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ui_update_geofiles),
                    subtitle = when {
                        data.geoBusy -> stringResource(R.string.ui_geofiles_downloading)
                        data.geoTag.isNotBlank() -> stringResource(R.string.ui_geofiles_current_tag, data.geoTag)
                        else -> stringResource(R.string.ui_geofiles_iran_rules)
                    },
                    icon = Icons.Default.Public,
                    trailing = { if (data.geoBusy) SmallSpinner() else SettingsChevron() },
                    modifier = Modifier.testTag("settings_update_geofiles"),
                    onClick = onUpdateGeoFiles
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.ui_geofiles_via_proxy),
                    subtitle = stringResource(R.string.ui_geofiles_via_proxy_sub),
                    icon = Icons.Default.VpnLock,
                    checked = data.geoViaVpn,
                    onCheckedChange = onGeoViaVpn,
                    modifier = Modifier.testTag("settings_geofiles_via_proxy")
                )
            }
            data.geoStatus?.let { SettingsFootnote(it) }

        }
    }
}

private val managedEngineNames = mapOf(
    "sing-box" to "sing-box",
    "mihomo" to "Mihomo",
    "aether" to "Aether / WARP",
    "psiphon" to "Psiphon",
    "tor" to "Tor",
    "radiance" to "Lantern / Radiance"
)

@Composable
internal fun EngineUpdatesSettingsPage(
    update: UpdateUi,
    psiphonStatus: String?,
    psiphonImporting: Boolean,
    radianceStatus: String?,
    onCheckForUpdates: () -> Unit,
    onImportPsiphon: () -> Unit,
    onAddRadiance: () -> Unit,
    onBack: () -> Unit
) {
    Column {
        SettingsTopBar(stringResource(R.string.ui_engine_updates), onBack = onBack)
        SettingsPageColumn {
            Spacer(Modifier.height(16.dp))
            UpdateOverview(
                title = stringResource(R.string.ui_connection_engines),
                subtitle = stringResource(R.string.ui_engine_upgrades_are_included_in),
                icon = Icons.Default.Memory,
                update = update,
                onCheckForUpdates = onCheckForUpdates
            )
            SettingsSectionLabel(stringResource(R.string.ui_engine_inventory))
            SettingsGroup {
                EngineInventoryRow(CoreCapabilityRegistry.CORE, stringResource(R.string.ui_included_in_the_app))
                Sidecars.ENGINES.forEach { engine ->
                    managedEngineNames[engine.id]?.let { name ->
                        SettingsDivider()
                        EngineInventoryRow(name, if (Sidecars.isBundled(engine)) stringResource(R.string.ui_included_in_the_app) else stringResource(R.string.ui_unavailable_on_this_device))
                    }
                }
            }
            SettingsSectionLabel(stringResource(R.string.ui_engine_setup))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ui_import_psiphon_config),
                    subtitle = psiphonStatus,
                    icon = Icons.Default.Storage,
                    enabled = !psiphonImporting,
                    trailing = { if (psiphonImporting) SmallSpinner() else SettingsChevron() },
                    onClick = onImportPsiphon
                )
                if (Sidecars.isBundled(RadianceSidecar)) {
                    SettingsDivider()
                    SettingsNavRow(
                        title = stringResource(R.string.ui_add_lantern_radiance_to_nodes),
                        icon = Icons.Default.Public,
                        onClick = onAddRadiance
                    )
                }
            }
            radianceStatus?.let { SettingsFootnote(it) }
        }
    }
}

@Composable
private fun EngineInventoryRow(name: String, status: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIconTile(Icons.Default.Memory)
        Column {
            Text(name, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary)
            Text(status, fontSize = 12.sp, color = AppTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun UpdateOverview(
    title: String,
    subtitle: String,
    icon: ImageVector,
    update: UpdateUi,
    onCheckForUpdates: () -> Unit
) {
    val colors = AppTheme.colors
    val status = when {
        update.checking -> "Checking the official release…"
        update.hasUpdate -> update.latestVersion?.let { "Version ${it} is available" } ?: "Update available"
        update.checkFailed -> "Could not check for updates"
        update.checked -> "You're up to date"
        else -> "Stable channel"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsGroupShape,
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.26f))
    ) {
        Column(
            Modifier
                .background(
                    Brush.linearGradient(
                        listOf(colors.primary.copy(alpha = if (colors.isDark) 0.18f else 0.10f), Color.Transparent)
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsIconTile(icon, size = 44)
                Column {
                    Text(title, color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            Text(status, color = colors.textSecondary, fontSize = 13.sp)
            Button(
                modifier = Modifier.fillMaxWidth().testTag("settings_check_updates"),
                onClick = onCheckForUpdates,
                enabled = !update.checking,
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
            ) {
                Text(
                    when {
                        update.checking -> stringResource(R.string.ui_checking)
                        update.hasUpdate -> stringResource(R.string.ui_review_app_update)
                        update.checkFailed -> stringResource(R.string.ui_retry_update_check)
                        else -> stringResource(R.string.ui_check_for_updates)
                    }
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
        SettingsTopBar(stringResource(R.string.ui_diagnostics), onBack = onBack)
        SettingsPageColumn {
            SettingsSectionLabel(stringResource(R.string.ui_engine), first = true)
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ui_live_logs_telemetry),
                    subtitle = stringResource(R.string.ui_connection_events_engine_output_and),
                    icon = Icons.Default.Terminal,
                    modifier = Modifier.testTag("view_diagnostics_button"),
                    onClick = onViewLogs
                )
                SettingsDivider()
                SettingsNavRow(
                    title = stringResource(R.string.ui_raw_engine_config),
                    subtitle = stringResource(R.string.ui_generated_json_with_secrets_redacted),
                    icon = Icons.Default.Code,
                    modifier = Modifier.testTag("view_xray_config_button"),
                    onClick = onViewConfig
                )
            }

            Spacer(Modifier.height(32.dp))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ui_reset_all_settings),
                    subtitle = stringResource(R.string.ui_restore_routing_and_engine_settings),
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
        SettingsTopBar(stringResource(R.string.ui_about_community), onBack = onBack)
        SettingsPageColumn {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(size = 52)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Maximus VPN", color = AppTheme.colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ui_version, versionName), color = AppTheme.colors.textMuted, fontSize = 13.sp)
                    }
                }
                SettingsDivider(inset = false)
                SettingsInfoLine(stringResource(R.string.ui_developer), "DrFXAi")
                SettingsInfoLine(stringResource(R.string.ui_engines), coreSummary)
                SettingsInfoLine(stringResource(R.string.ui_protocols), "VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · TUIC · SOCKS5 · HTTP")
                Spacer(Modifier.height(4.dp))
            }

            SettingsSectionLabel(stringResource(R.string.ui_community))
            SettingsGroup {
                SettingsNavRow(title = "Telegram", subtitle = stringResource(R.string.ui_news_updates_and_community), leading = { LogoTile(telegramLogo) }, trailing = { ExternalIcon() }, onClick = onTelegram)
                SettingsDivider()
                SettingsNavRow(title = "YouTube", subtitle = stringResource(R.string.ui_tutorials_and_guides), leading = { LogoTile(youTubeLogo) }, trailing = { ExternalIcon() }, onClick = onYouTube)
                SettingsDivider()
                SettingsNavRow(title = "GitHub", subtitle = stringResource(R.string.ui_source_code_issues_and_contributions), leading = { LogoTile(gitHubLogo) }, trailing = { ExternalIcon() }, onClick = onGitHub)
            }

            SettingsSectionLabel(stringResource(R.string.ui_support_maximus))
            SettingsGroup {
                SettingsNavRow(title = stringResource(R.string.ui_support_with_crypto), subtitle = cryptoSubtitle, icon = Icons.Default.CurrencyBitcoin, onClick = onCrypto)
                SettingsDivider()
                SettingsNavRow(title = stringResource(R.string.ui_buy_a_coffee), subtitle = stringResource(R.string.ui_support_the_developer), icon = Icons.Default.LocalCafe, onClick = onCoffee)
            }
            SettingsFootnote(stringResource(R.string.ui_executable_engine_upgrades_ship_only), Modifier.padding(top = 4.dp))
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
