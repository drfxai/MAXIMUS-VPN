package com.example.ui.lab

import androidx.compose.ui.res.stringResource
import com.example.R
import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.CenterFocusWeak
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.ConnectionStatus
import com.example.ui.connections.ConnectionsViewModel
import com.example.ui.protocols.LabText
import com.example.ui.protocols.labColors
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.VpnViewModel
import com.example.vpn.VpnController
import com.example.vpn.connections.BuiltinConnections
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.ServerlessSidecar

/** Reuse LAB typography/components with the same palette as the main Maximus screen. */
private val connectionColors: com.example.ui.protocols.LabColors
    @Composable get() {
        val colors = AppTheme.colors
        return labColors.copy(bg = colors.background, card = colors.surfaceCard,
            cardAlt = colors.surfaceInput, stroke = colors.borderSubtle,
            text = colors.textPrimary, text2 = colors.textSecondary, text3 = colors.textMuted,
            accent = colors.primary, onAccent = colors.onPrimary,
            accentSoft = colors.primaryContainer, good = colors.statusConnected,
            bad = colors.statusError, okay = colors.statusWarning)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BuiltinConnectionScreen(
    direct: Boolean,
    vpn: VpnViewModel,
    onPermission: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val application = context.applicationContext as Application
    val factory = remember(application, direct) {
        viewModelFactory { initializer { ConnectionsViewModel(application, direct) } }
    }
    val model: ConnectionsViewModel = viewModel(key = if (direct) "serverless-direct" else "aether-warp", factory = factory)
    val ui by model.ui.collectAsStateWithLifecycle()
    val session by vpn.connectionState.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refreshOptions() }
    val c = connectionColors
    val colors = AppTheme.colors
    val idle = session.status == ConnectionStatus.DISCONNECTED
    val failed = session.status == ConnectionStatus.FAILED
    val fallback = direct && session.selectedProfileId == BuiltinConnections.DIRECT && session.activeProfile?.id == BuiltinConnections.AETHER
    val own = fallback || session.activeProfile?.id == if (direct) BuiltinConnections.DIRECT else BuiltinConnections.AETHER
    val valid = ui.options.advanced.problem(if (direct) null else ui.options.transport) == null
    val configurable = idle && !ui.working
    val bundled = model.bundled()
    val options = ui.options
    val title = if (direct) "Serverless Direct" else "Aether WARP"
    val logSummary = remember(ui.log) { BuiltinLog.summarize(ui.log) }
    var tab by rememberSaveable(direct) { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }
    val runChecks = {
        tab = 2
        model.testAccess()
    }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TitleBar(c, title, stringResource(R.string.ui_maximus_vpn_connections), Icons.AutoMirrored.Rounded.ArrowBack, onBack)
        Surface(Modifier.padding(horizontal = Gutter, vertical = 8.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp), color = c.card,
            border = BorderStroke(1.dp, c.accent.copy(alpha = .28f))) {
            Column(Modifier.background(Brush.linearGradient(listOf(c.accentSoft, c.card))).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = c.accentSoft) {
                        Icon(if (direct) Icons.Rounded.Bolt else Icons.Rounded.Cloud, null,
                            Modifier.padding(12.dp).size(28.dp), tint = c.accent)
                    }
                    Column(Modifier.weight(1f)) {
                        LabText(when {
                            !bundled -> stringResource(R.string.ui_engine_unavailable)
                            ui.working -> stringResource(R.string.ui_working)
                            failed -> stringResource(R.string.ui_connection_failed)
                            fallback && !idle -> stringResource(R.string.ui_warp_fallback)
                            own && !idle -> session.statusLabel
                            !idle -> stringResource(R.string.ui_another_session_is_active)
                            ui.result?.traffic == true -> stringResource(R.string.ui_local_access_verified)
                            else -> stringResource(R.string.ui_ready_to_connect)
                        }, c.text, 18.sp, FontWeight.Bold)
                        LabText(if (direct) stringResource(R.string.ui_dpi_bypass_isp_exit_ip) else stringResource(R.string.ui_cloudflare_tunnel_private_device_keys),
                            c.text2, 12.sp)
                    }
                }
                // Status chips: the current choices, read from the saved options and stored keys.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (direct) {
                        HeroChip(c, stringResource(R.string.ui_profile), optionLabel(options.variant))
                        HeroChip(c, stringResource(R.string.bc_chip_dns), optionLabel(options.resolver))
                        HeroChip(c, stringResource(R.string.bc_chip_fallback),
                            stringResource(if (options.vpnFallback) R.string.bc_on else R.string.bc_off))
                    } else {
                        HeroChip(c, stringResource(R.string.ui_transport), transportChipLabel(options.transport))
                        HeroChip(c, familyChipLabel(options.family))
                        HeroChip(c, stringResource(R.string.bc_chip_keys),
                            stringResource(if (ui.identity) R.string.bc_keys_stored else R.string.bc_keys_not_created))
                    }
                }
                if (ui.working || own && session.isBusy) LinearProgressIndicator(
                    Modifier.fillMaxWidth(), color = c.accent, trackColor = c.stroke)
                if (fallback && !idle) LabText(stringResource(R.string.ui_direct_access_failed_your_enabled), c.text2, 12.sp)
                val message = if (failed || own && !idle) session.errorMessage ?: ui.message else ui.message
                message?.let { LabText(it, c.text2, 12.sp) }
                if (ui.working) TextButton(onClick = model::cancel) { Text(stringResource(R.string.ui_cancel_operation)) }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.accent,
            modifier = Modifier.padding(horizontal = Gutter)) {
            listOf(R.string.bc_tab_connect, R.string.bc_tab_advanced, R.string.bc_tab_diagnostics).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index },
                    selectedContentColor = c.accent, unselectedContentColor = c.text2,
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(label), fontSize = 14.sp, fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1)
                            if (index == 2 && logSummary.issues > 0) {
                                Spacer(Modifier.width(5.dp))
                                Box(Modifier.size(17.dp).clip(CircleShape).background(colors.surfacePill), contentAlignment = Alignment.Center) {
                                    Text(logSummary.issues.coerceAtMost(99).toString(), color = c.text2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == 0) {
                if (direct) {
                    GroupLabel(c, stringResource(R.string.bc_dpi_profile))
                    Panel(c) {
                        PanelBlock(c, stringResource(R.string.ui_profile), stringResource(R.string.ui_auto_tries_dpi_methods_and)) {
                            PillSegmented(c, ServerlessSidecar.variants, options.variant, configurable, ::optionLabel) { value ->
                                model.options { it.copy(variant = value) }
                            }
                        }
                        Hairline(c)
                        PanelBlock(c, stringResource(R.string.ui_ip_family)) {
                            PillSegmented(c, AetherSidecar.families, options.family, configurable, ::optionLabel) { value ->
                                model.options { it.copy(family = value) }
                            }
                        }
                        ToggleRow(c, stringResource(R.string.ui_warp_fallback), stringResource(R.string.ui_switch_to_warp_if_direct),
                            options.vpnFallback, configurable) { value -> model.options { it.copy(vpnFallback = value) } }
                    }
                    GroupLabel(c, stringResource(R.string.bc_resolver))
                    Panel(c) {
                        PanelBlock(c, stringResource(R.string.ui_preferred_resolver), stringResource(R.string.ui_both_resolvers_use_authenticated_https)) {
                            PillSegmented(c, ServerlessSidecar.resolvers, options.resolver, configurable, ::optionLabel) { value ->
                                model.options { o -> o.copy(resolver = value) }
                            }
                        }
                    }
                } else {
                    GroupLabel(c, stringResource(R.string.ui_transport))
                    ChipGroup(c, AetherSidecar.transports, options.transport, configurable, ::transportChipLabel) { value ->
                        model.options { it.copy(transport = value) }
                    }
                    Footnote(c, stringResource(R.string.ui_auto_selects_a_transport_endpoint))
                    GroupLabel(c, stringResource(R.string.bc_tunnel_settings))
                    Panel(c) {
                        PanelBlock(c, stringResource(R.string.ui_ip_family)) {
                            PillSegmented(c, AetherSidecar.families, options.family, configurable, ::optionLabel) { value ->
                                model.options { it.copy(family = value) }
                            }
                        }
                        ToggleRow(c, stringResource(R.string.ui_tls_fragmentation), stringResource(R.string.ui_split_supported_tls_handshakes),
                            options.fragment, configurable) { value -> model.options { it.copy(fragment = value) } }
                        Hairline(c)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                LabText(stringResource(R.string.ui_warp_identity), c.text, 15.sp, FontWeight.SemiBold)
                                LabText(if (ui.identity) stringResource(R.string.ui_stored_on_this_device) else stringResource(R.string.ui_created_automatically_when_connecting), c.text2, 12.5.sp)
                            }
                            FilledTonalButton(onClick = { model.renewIdentity() }, enabled = configurable && bundled && valid,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.surfacePill, contentColor = c.accent)) {
                                Text(if (ui.identity) stringResource(R.string.ui_renew) else stringResource(R.string.ui_create), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                GroupLabel(c, stringResource(R.string.ui_profile))
                Panel(c) {
                    SettingField(stringResource(R.string.ui_alias_remarks), options.advanced.alias, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(alias = value)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (tab == 1) {
                if (!direct) {
                    InfoNote(c, stringResource(R.string.bc_advanced_hint))
                    var relayOpen by rememberSaveable { mutableStateOf(false) }
                    CollapsibleSection(c, Icons.Rounded.SyncAlt, stringResource(R.string.bc_relay_chain), summary = {
                        SummaryText(c, listOf("Psiphon" to optionLabel(options.advanced.psiphon), "Tor" to optionLabel(options.advanced.tor)))
                    }, expanded = relayOpen, onToggle = { relayOpen = !relayOpen }, tag = "builtin_section_relay") {
                        OptionRow("Psiphon", options.advanced.psiphon, AetherSettings.relayModes, configurable) { value ->
                            model.options { it.copy(advanced = it.advanced.copy(psiphon = value)) }
                        }
                        OptionRow("Tor", options.advanced.tor, AetherSettings.relayModes, configurable) { value ->
                            model.options { it.copy(advanced = it.advanced.copy(tor = value)) }
                        }
                    }
                    var endpointOpen by rememberSaveable { mutableStateOf(false) }
                    val customEndpoint = options.advanced.endpointAddress.isNotBlank() || options.advanced.endpointPort.isNotBlank() ||
                        options.advanced.mtu.isNotBlank()
                    CollapsibleSection(c, Icons.Rounded.CenterFocusWeak, stringResource(R.string.bc_endpoint_discovery), summary = {
                        SummaryText(c, listOf(
                            stringResource(R.string.bc_scan_summary, optionLabel(options.advanced.scan).replaceFirstChar { it.uppercase() }) to null,
                            stringResource(R.string.bc_obfuscation_summary, optionLabel(options.advanced.noize)) to null,
                            stringResource(if (customEndpoint) R.string.bc_endpoint_custom else R.string.bc_endpoint_auto) to null))
                    }, expanded = endpointOpen, onToggle = { endpointOpen = !endpointOpen }, tag = "builtin_section_endpoint") {
                        OptionRow(stringResource(R.string.ui_scan_mode), options.advanced.scan, AetherSettings.scans, configurable) { value ->
                            model.options { it.copy(advanced = it.advanced.copy(scan = value)) }
                        }
                        OptionRow(stringResource(R.string.ui_obfuscation), options.advanced.noize, AetherSettings.noises,
                            configurable && options.transport in listOf("auto", "h3", "wireguard", "wiw")) { value ->
                            model.options { it.copy(advanced = it.advanced.copy(noize = value)) }
                        }
                        SettingField(stringResource(R.string.ui_address_blank_to_scan), options.advanced.endpointAddress, configurable) { value ->
                            model.options { it.copy(advanced = it.advanced.withEndpoint(address = value)) }
                        }
                        SettingField(stringResource(R.string.ui_port), options.advanced.endpointPort, configurable) { value ->
                            model.options { it.copy(advanced = it.advanced.withEndpoint(port = value)) }
                        }
                        SettingField(stringResource(R.string.ui_wireguard_mtu_blank_for_automatic), options.advanced.mtu,
                            configurable && options.transport in listOf("auto", "wireguard", "wiw")) { value ->
                            model.options { it.copy(advanced = it.advanced.copy(mtu = value.filter { ch -> ch in '0'..'9' }.take(4))) }
                        }
                    }
                    var registrationOpen by rememberSaveable { mutableStateOf(false) }
                    val storedKeys = AetherSettings.keyKinds.count { ui.keyStatus[it] == true }
                    CollapsibleSection(c, Icons.Rounded.Lock, stringResource(R.string.bc_registration_tls), summary = {
                        LabText(stringResource(R.string.bc_registration_summary), c.text2, 12.5.sp, maxLines = 3, lineHeight = 16.sp)
                    }, expanded = registrationOpen, onToggle = { registrationOpen = !registrationOpen }, tag = "builtin_section_registration",
                        badge = {
                            StatusChip("$storedKeys/${AetherSettings.keyKinds.size}",
                                if (storedKeys > 0) colors.statusConnected else c.text3)
                        }) {
                        SubLabel(c, stringResource(R.string.bc_api))
                        InnerGroup(c) {
                            InlineDropdownRow(c, stringResource(R.string.ui_api_host), options.advanced.apiHost, AetherSettings.apiHosts, configurable, ::optionLabel) { value ->
                                model.options { it.copy(advanced = it.advanced.copy(apiHost = value)) }
                            }
                            Hairline(c, 14.dp)
                            InlineDropdownRow(c, stringResource(R.string.ui_api_server_name_sni), options.advanced.apiSni, AetherSettings.apiHosts, configurable, ::optionLabel) { value ->
                                model.options { it.copy(advanced = it.advanced.copy(apiSni = value)) }
                            }
                            Hairline(c, 14.dp)
                            InlineDropdownRow(c, stringResource(R.string.ui_account_api_route), options.advanced.apiRoute, AetherSettings.apiRoutes, configurable, ::optionLabel) { value ->
                                model.options { it.copy(advanced = it.advanced.copy(apiRoute = value)) }
                            }
                            Hairline(c, 14.dp)
                            val fingerprintAuto = stringResource(R.string.bc_fingerprint_auto_by_firewall)
                            InlineDropdownRow(c, stringResource(R.string.ui_fingerprint), options.advanced.fingerprint,
                                com.example.vpn.sidecar.AetherTlsProfiles.choices, configurable,
                                { if (it == com.example.vpn.sidecar.AetherTlsProfiles.AUTO) fingerprintAuto else optionLabel(it) }) { value ->
                                model.options { it.copy(advanced = it.advanced.copy(fingerprint = value)) }
                            }
                        }
                        Footnote(c, stringResource(R.string.ui_auto_oob_uses_android_tls), inPanel = true)
                        SettingField(stringResource(R.string.ui_api_address_blank_for_automatic), options.advanced.apiAddress, configurable) { value ->
                            model.options { it.copy(advanced = it.advanced.copy(apiAddress = value)) }
                        }
                        SettingField(stringResource(R.string.bc_api_relay_field), options.advanced.apiRelay, configurable) { value ->
                            model.options { it.copy(advanced = it.advanced.copy(apiRelay = value.trim())) }
                        }
                        TextButton(onClick = {
                            runCatching { context.assets.open("warp-relay/worker.js").bufferedReader().use { it.readText() } }
                                .onSuccess { clipboard.setText(AnnotatedString(it)) }
                        }, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = c.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.ui_copy_warp_api_relay_worker), color = c.accent, fontWeight = FontWeight.SemiBold)
                        }
                        Footnote(c, "Irancell/MCI: each WARP account call races Aether's TLS against an OOB ClientHello lane. A relay on your own Worker (set RELAY_KEY, enter https://<worker>.workers.dev/<RELAY_KEY>) joins the race.", inPanel = true)
                        SubLabel(c, stringResource(R.string.bc_tls_fragment))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                            Box(Modifier.weight(1f)) {
                                SettingField(stringResource(R.string.bc_fragment_bytes), options.advanced.fragmentSize, configurable && options.fragment, padding = 4.dp) { value ->
                                    model.options { it.copy(advanced = it.advanced.copy(fragmentSize = value)) }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                SettingField(stringResource(R.string.bc_fragment_delay), options.advanced.fragmentDelay, configurable && options.fragment, padding = 4.dp) { value ->
                                    model.options { it.copy(advanced = it.advanced.copy(fragmentDelay = value)) }
                                }
                            }
                        }
                        ToggleRow(c, stringResource(R.string.ui_encrypted_client_hello), stringResource(R.string.ui_ech_requires_a_usable_ech),
                            options.advanced.ech, configurable) { value -> model.options { it.copy(advanced = it.advanced.copy(ech = value)) } }
                        SubLabel(c, stringResource(R.string.bc_warp_keys)) {
                            Dot(colors.statusConnected, 7.dp)
                            Spacer(Modifier.width(5.dp))
                            LabText(stringResource(R.string.bc_stored), c.text2, 12.sp, maxLines = 1)
                            Spacer(Modifier.width(10.dp))
                            Dot(c.text3, 7.dp)
                            Spacer(Modifier.width(5.dp))
                            LabText(stringResource(R.string.bc_not_created), c.text2, 12.sp, maxLines = 1)
                        }
                        KeyGrid(c, AetherSettings.keyKinds, ui.keyStatus)
                        var keyKind by rememberSaveable { mutableStateOf("all") }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                CompactDropdown(c, stringResource(R.string.bc_for), keyKind, AetherSettings.keyKinds, configurable) { keyKind = it }
                            }
                            FilledTonalButton(onClick = { model.renewIdentity(keyKind) }, enabled = configurable && bundled && valid,
                                modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.surfacePill, contentColor = c.accent)) {
                                Icon(Icons.Rounded.Key, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.bc_get_new_keys), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                    var exitOpen by rememberSaveable { mutableStateOf(false) }
                    CollapsibleSection(c, Icons.AutoMirrored.Rounded.Login, stringResource(R.string.bc_exit_node), summary = {
                        Row(verticalAlignment = Alignment.Top) {
                            LabText(stringResource(R.string.ui_outbound), c.text2, 12.5.sp, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            LabText(stringResource(R.string.bc_exit_summary, exitNodeLabel(options.advanced.exitNode, ui.exitNodes)),
                                c.text2, 12.5.sp, maxLines = 2, lineHeight = 16.sp)
                        }
                    }, expanded = exitOpen, onToggle = { exitOpen = !exitOpen }, tag = "builtin_section_exit") {
                        ExitNodeFields(c, options, ui.exitNodes, configurable, direct = false) { change -> model.options(change) }
                    }
                    OutlinedButton(onClick = { model.testAccess() }, enabled = configurable && bundled && valid,
                        modifier = Modifier.padding(horizontal = Gutter).fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, colors.borderMedium)) {
                        Icon(Icons.Rounded.GpsFixed, null, tint = c.text, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ui_scan_for_an_endpoint), color = c.text, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    GroupLabel(c, stringResource(R.string.bc_exit_node))
                    Panel(c) {
                        ExitNodeFields(c, options, ui.exitNodes, configurable, direct = true) { change -> model.options(change) }
                    }
                    InfoNote(c, stringResource(R.string.bc_exit_note))
                    GroupLabel(c, stringResource(R.string.bc_related))
                    Panel(c) {
                        NavRow(c, Icons.Rounded.Tune, stringResource(R.string.bc_related_connect), stringResource(R.string.bc_related_connect_detail),
                            chevron = Icons.Rounded.ChevronRight) { tab = 0 }
                    }
                }
            }
            if (tab == 2) {
                val result = ui.result
                val passed = listOf(result?.dns, result?.tls, result?.traffic).count { it == true }
                SectionHeader(c, stringResource(R.string.bc_status))
                StatusTiles(c, logSummary, passed)
                SectionHeader(c, stringResource(R.string.bc_log), Icons.Rounded.ContentCopy, stringResource(R.string.ui_copy)) {
                    clipboard.setText(AnnotatedString(ui.log.joinToString("\n").ifBlank { ui.message.orEmpty() }))
                }
                LogViewer(c, logSummary, ui.message ?: stringResource(R.string.bc_no_operation))
                SectionHeader(c, stringResource(R.string.bc_access_checks), Icons.Rounded.MonitorHeart,
                    if (ui.working) stringResource(R.string.ui_cancel) else stringResource(R.string.bc_run),
                    enabled = ui.working || configurable && bundled && valid) {
                    if (ui.working) model.cancel() else model.testAccess()
                }
                Panel(c) {
                    CheckRow(c, stringResource(R.string.bc_check_dns), result?.dns)
                    Hairline(c)
                    CheckRow(c, stringResource(R.string.bc_check_tls), result?.tls)
                    Hairline(c)
                    CheckRow(c, stringResource(R.string.bc_check_traffic), result?.traffic)
                    Hairline(c)
                    KeyValue(c, stringResource(R.string.bc_check_time), result?.latencyMs?.let { "$it ms" } ?: "—")
                }
                InfoNote(c, stringResource(R.string.ui_these_checks_measure_the_local))
                val preview = model.commandPreview()
                SectionHeader(c, stringResource(if (direct) R.string.bc_direct_command else R.string.bc_aether_command),
                    Icons.Rounded.ContentCopy, stringResource(R.string.ui_copy)) {
                    clipboard.setText(AnnotatedString(preview))
                }
                var commandOpen by rememberSaveable { mutableStateOf(false) }
                Column(Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(colors.consoleBackground).border(1.dp, c.stroke, RoundedCornerShape(18.dp))
                    .clickable { commandOpen = !commandOpen }.padding(14.dp)) {
                    Text("$ $preview", color = c.text2, fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                        maxLines = if (commandOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                }
                Footnote(c, stringResource(R.string.ui_connect_starts_the_engine_and))
            }
        }
        Row(Modifier.navigationBarsPadding().padding(horizontal = Gutter, vertical = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val checksEnabled = configurable && bundled && valid
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, colors.borderMedium, RoundedCornerShape(16.dp))
                .clickable(enabled = checksEnabled, onClickLabel = stringResource(R.string.ui_run_access_checks)) { runChecks() }
                .testTag("builtin_access_checks"), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MonitorHeart, stringResource(R.string.ui_run_access_checks),
                    tint = if (checksEnabled) c.text else c.text3, modifier = Modifier.size(22.dp))
            }
            PrimaryButton(c, when {
                failed -> "Stop failed session"
                own && !idle -> "Stop connection"
                direct && ui.options.vpnFallback -> "Connect with WARP fallback"
                else -> if (direct) "Connect Direct" else "Connect WARP"
            }, Icons.Rounded.PowerSettingsNew,
                Modifier.weight(1f).height(52.dp),
                enabled = failed || own && !idle || configurable && bundled && valid) {
                if (failed || own && !idle) VpnController.stopVpn(context, com.example.vpn.StopOrigin.ENGINE_PAGE)
                else if (VpnController.connectionState.value.status == ConnectionStatus.DISCONNECTED) {
                    // The user's own pick of this connection: only it runs (ManualEnginePick). A WARP that carries no
                    // traffic here ends with a reason instead of handing over to saved servers or the recovery engines;
                    // Smart Connect is the button that may choose another. Direct keeps its own explicit WARP fallback switch.
                    vpn.startOrRequestPermission(context, model.profile(), exact = true) { onPermission() }
                }
            }
        }
    }
}

/** Exit-node controls shared by both workspaces: the outbound picker and the two fields that shape the freedom exit. */
@Composable
private fun ExitNodeFields(
    c: com.example.ui.protocols.LabColors, options: ConnectionsViewModel.Options, nodes: List<com.example.data.model.VlessProfile>,
    configurable: Boolean, direct: Boolean, change: ((ConnectionsViewModel.Options) -> ConnectionsViewModel.Options) -> Unit
) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(colors.surfacePill), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.Login, null, tint = c.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            LabText(stringResource(R.string.ui_outbound), c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
            LabText(stringResource(if (direct) R.string.bc_outbound_detail_direct else R.string.bc_outbound_detail_warp), c.text2, 12.5.sp)
        }
    }
    val freedom = stringResource(R.string.bc_freedom_default)
    val unavailable = stringResource(R.string.bc_unavailable_node)
    OptionRow("", options.advanced.exitNode, listOf("") + nodes.map { it.id }, configurable,
        label = { id -> if (id.isBlank()) freedom else nodes.firstOrNull { it.id == id }?.name ?: unavailable }) { value ->
        change { it.copy(advanced = it.advanced.copy(exitNode = value)) }
    }
    Hairline(c)
    LabText(stringResource(R.string.bc_only_freedom), c.text2, 13.sp, FontWeight.SemiBold,
        Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp), maxLines = 1)
    SettingField(stringResource(R.string.ui_exit_node_finalmask_raw_json), options.advanced.exitFinalMask,
        configurable && options.advanced.exitNode.isBlank(), multiline = true) { value ->
        change { it.copy(advanced = it.advanced.copy(exitFinalMask = value)) }
    }
    SettingField(stringResource(R.string.ui_exit_node_dialmode), options.advanced.exitDialMode,
        configurable && options.advanced.exitNode.isBlank()) { value ->
        change { it.copy(advanced = it.advanced.copy(exitDialMode = value)) }
    }
    if (!direct) Footnote(c, stringResource(R.string.bc_exit_note), inPanel = true)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun exitNodeLabel(id: String, nodes: List<com.example.data.model.VlessProfile>): String =
    if (id.isBlank()) stringResource(R.string.bc_freedom_default)
    else nodes.firstOrNull { it.id == id }?.name ?: stringResource(R.string.bc_unavailable_node)

/** A switch row with a title and description, inside a panel. */
@Composable
private fun ToggleRow(c: com.example.ui.protocols.LabColors, title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LabText(title, c.text, 15.sp, FontWeight.SemiBold)
            LabText(detail, c.text2, 12.5.sp, maxLines = 4, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary, checkedTrackColor = colors.primary, checkedBorderColor = colors.primary,
                uncheckedThumbColor = colors.textMuted, uncheckedTrackColor = colors.surfaceElevated, uncheckedBorderColor = colors.borderMedium))
    }
}

/** An info icon and a quiet line, outside panels. */
@Composable
private fun InfoNote(c: com.example.ui.protocols.LabColors, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter + 4.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, tint = c.text3, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        LabText(text, c.text2, 12.5.sp, maxLines = 6, lineHeight = 17.sp)
    }
}

/** The WARP key kinds as cells with a Stored / Not created dot; long names take a full row. */
@Composable
private fun KeyGrid(c: com.example.ui.protocols.LabColors, kinds: List<String>, status: Map<String, Boolean>) {
    val rows = mutableListOf<List<String>>()
    var pending: String? = null
    kinds.forEach { kind ->
        if (optionLabel(kind).length > 12) {
            rows.add(listOf(kind))
        } else if (pending == null) {
            pending = kind
        } else {
            rows.add(listOf(pending!!, kind))
            pending = null
        }
    }
    pending?.let { rows.add(listOf(it)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { kind -> KeyCell(c, kind, status[kind] == true, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun KeyCell(c: com.example.ui.protocols.LabColors, kind: String, stored: Boolean, modifier: Modifier) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.clip(shape).border(1.dp, colors.borderSubtle, shape).padding(horizontal = 12.dp, vertical = 10.dp)
        .testTag("warp_key_$kind"), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (stored) colors.statusConnected else c.text3, 7.dp)
        Spacer(Modifier.width(8.dp))
        LabText(optionLabel(kind), if (stored) c.text else c.text2, 13.5.sp, FontWeight.Medium, maxLines = 1)
    }
}

/** "For  All keys ⌄": a compact bordered dropdown. */
@Composable
private fun CompactDropdown(c: com.example.ui.protocols.LabColors, prefix: String, selected: String, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box {
        Row(Modifier.fillMaxWidth().height(48.dp).clip(shape).border(1.dp, AppTheme.colors.borderMedium, shape)
            .clickable(enabled = enabled) { expanded = true }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LabText(prefix, c.text2, 13.sp, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            LabText(optionLabel(selected), if (enabled) c.text else c.text3, 13.5.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Rounded.ExpandMore, null, tint = c.text2, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

private fun transportChipLabel(option: String) = if (option == "gool") "Gool" else optionLabel(option)

@Composable
private fun familyChipLabel(option: String) = when (option) {
    "ipv4" -> "IPv4"
    "ipv6" -> "IPv6"
    else -> stringResource(R.string.bc_family_dual)
}

@Composable
private fun OptionRow(title: String, selected: String, options: List<String>, enabled: Boolean, label: (String) -> String = ::optionLabel, onSelect: (String) -> Unit) {
    val c = connectionColors
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
        if (title.isNotBlank()) LabText(title, c.text2, 12.sp)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, c.stroke), contentPadding = PaddingValues(12.dp)) {
                Text(label(selected), Modifier.weight(1f), color = if (enabled) c.text else c.text3)
                Text("⌄", color = c.accent)
            }
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(label(option)) }, onClick = {
                        expanded = false
                        onSelect(option)
                    })
                }
            }
        }
    }
}

private fun optionLabel(option: String) = when (option) {
    "auto" -> "Auto"
    "all" -> "All keys"
    "chrome" -> "Chrome"
    "chrome-120" -> "Chrome 120"
    "firefox" -> "Firefox"
    "semi-python" -> "Semi-Python"
    "go" -> "Go"
    "gool-classic" -> "WARP-in-WARP"
    "off" -> "Off"
    "chain" -> "Inside WARP"
    "reverse" -> "Carry WARP"
    "only" -> "Standalone"
    "h2" -> "MASQUE H2"
    "h3" -> "MASQUE H3"
    "wireguard" -> "WireGuard"
    "" -> "Automatic"
    "masque" -> "MASQUE"
    "wg" -> "WireGuard"
    "gool" -> "WireGuard over MASQUE"
    "wiw" -> "WARP-in-WARP"
    "mim" -> "MASQUE-in-MASQUE"
    "cloudflare" -> "Cloudflare"
    "google" -> "Google"
    "ipv4" -> "IPv4"
    "ipv6" -> "IPv6"
    "oob" -> "OOB"
    "fragA" -> "Frag A"
    "fragB" -> "Frag B"
    else -> option
}

@Composable
private fun SettingField(label: String, value: String, enabled: Boolean, multiline: Boolean = false, padding: androidx.compose.ui.unit.Dp = 14.dp, onChange: (String) -> Unit) {
    val c = connectionColors
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        enabled = enabled, singleLine = !multiline, maxLines = if (multiline) 8 else 1,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, unfocusedTextColor = c.text,
            focusedBorderColor = c.accent, unfocusedBorderColor = c.stroke,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.text2, cursorColor = c.accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = padding, vertical = 4.dp))
}
