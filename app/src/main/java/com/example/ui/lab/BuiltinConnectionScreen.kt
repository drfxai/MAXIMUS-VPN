package com.example.ui.lab

import androidx.compose.ui.res.stringResource
import com.example.R
import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PowerSettingsNew
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
    val idle = session.status == ConnectionStatus.DISCONNECTED
    val failed = session.status == ConnectionStatus.FAILED
    val fallback = direct && session.selectedProfileId == BuiltinConnections.DIRECT && session.activeProfile?.id == BuiltinConnections.AETHER
    val own = fallback || session.activeProfile?.id == if (direct) BuiltinConnections.DIRECT else BuiltinConnections.AETHER
    val valid = ui.options.advanced.problem(if (direct) null else ui.options.transport) == null
    val configurable = idle && !ui.working
    val bundled = model.bundled()
    val options = ui.options
    val title = if (direct) "Serverless Direct" else "Aether WARP"
    var tab by rememberSaveable(direct) { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }
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
            listOf("Connect", "Advanced", "Diagnostics").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index },
                    text = { Text(label, fontSize = 12.sp) })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == 0) {
            Panel(c) {
                SettingField(stringResource(R.string.ui_alias_remarks), options.advanced.alias, configurable) { value ->
                    model.options { it.copy(advanced = it.advanced.copy(alias = value)) }
                }
            }
            GroupLabel(c, if (direct) "DPI profile" else "Tunnel settings")
            Panel(c) {
                val choices = if (direct) ServerlessSidecar.variants else AetherSidecar.transports
                OptionRow(if (direct) stringResource(R.string.ui_profile) else stringResource(R.string.ui_transport), if (direct) options.variant else options.transport,
                    choices, configurable) { value ->
                    model.options { if (direct) it.copy(variant = value) else it.copy(transport = value) }
                }
                Hairline(c)
                OptionRow(stringResource(R.string.ui_ip_family), options.family, AetherSidecar.families, configurable) { value ->
                    model.options { it.copy(family = value) }
                }
                Hairline(c)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LabText(if (direct) stringResource(R.string.ui_warp_fallback) else stringResource(R.string.ui_tls_fragmentation), c.text, 14.sp)
                        LabText(if (direct) stringResource(R.string.ui_switch_to_warp_if_direct) else stringResource(R.string.ui_split_supported_tls_handshakes), c.text2, 12.sp)
                    }
                    Switch(checked = if (direct) options.vpnFallback else options.fragment,
                        enabled = configurable, onCheckedChange = { value ->
                            model.options { if (direct) it.copy(vpnFallback = value) else it.copy(fragment = value) }
                        })
                }
                if (!direct) {
                    Hairline(c)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            LabText(stringResource(R.string.ui_warp_identity), c.text, 14.sp)
                            LabText(if (ui.identity) stringResource(R.string.ui_stored_on_this_device) else stringResource(R.string.ui_created_automatically_when_connecting), c.text2, 12.sp)
                        }
                        TextButton(onClick = { model.renewIdentity() }, enabled = configurable && bundled && valid) {
                            Text(if (ui.identity) stringResource(R.string.ui_renew) else stringResource(R.string.ui_create))
                        }
                    }
                }
            }
            if (direct) {
                GroupLabel(c, "Encrypted DNS")
                Panel(c) {
                    OptionRow(stringResource(R.string.ui_preferred_resolver), options.resolver, ServerlessSidecar.resolvers, configurable) {
                        model.options { o -> o.copy(resolver = it) }
                    }
                }
                Footnote(c, stringResource(R.string.ui_both_resolvers_use_authenticated_https))
            }
            Footnote(c, if (direct) stringResource(R.string.ui_auto_tries_dpi_methods_and)
                else stringResource(R.string.ui_auto_selects_a_transport_endpoint))
            SecondaryButton(c, stringResource(R.string.ui_run_access_checks), null,
                Modifier.padding(horizontal = Gutter).fillMaxWidth(), enabled = configurable && bundled && valid) {
                tab = 2
                model.testAccess()
            }
            }
            if (tab == 1) {
            if (!direct) {
                GroupLabel(c, "Relay chain")
                Panel(c) {
                    OptionRow("Psiphon", options.advanced.psiphon, AetherSettings.relayModes, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(psiphon = value)) }
                    }
                    OptionRow("Tor", options.advanced.tor, AetherSettings.relayModes, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(tor = value)) }
                    }
                }
                GroupLabel(c, "Endpoint discovery")
                Panel(c) {
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
                GroupLabel(c, "WARP registration and TLS")
                Panel(c) {
                    OptionRow(stringResource(R.string.ui_api_host), options.advanced.apiHost, AetherSettings.apiHosts, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiHost = value)) }
                    }
                    OptionRow(stringResource(R.string.ui_api_server_name_sni), options.advanced.apiSni, AetherSettings.apiHosts, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiSni = value)) }
                    }
                    OptionRow(stringResource(R.string.ui_account_api_route), options.advanced.apiRoute, AetherSettings.apiRoutes, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiRoute = value)) }
                    }
                    Footnote(c, stringResource(R.string.ui_auto_oob_uses_android_tls))
                    OptionRow(stringResource(R.string.ui_fingerprint), options.advanced.fingerprint, com.example.vpn.sidecar.AetherTlsProfiles.names, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(fingerprint = value)) }
                    }
                    SettingField(stringResource(R.string.ui_api_address_blank_for_automatic), options.advanced.apiAddress, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiAddress = value)) }
                    }
                    SettingField("WARP API relay · your Worker https:// address, blank for none", options.advanced.apiRelay, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiRelay = value.trim())) }
                    }
                    TextButton(onClick = {
                        runCatching { context.assets.open("warp-relay/worker.js").bufferedReader().use { it.readText() } }
                            .onSuccess { clipboard.setText(AnnotatedString(it)) }
                    }) { Text(stringResource(R.string.ui_copy_warp_api_relay_worker)) }
                    Footnote(c, "Irancell/MCI: each WARP account call races Aether's TLS against an OOB ClientHello lane. A relay on your own Worker (set RELAY_KEY, enter https://<worker>.workers.dev/<RELAY_KEY>) joins the race.")
                    SettingField(stringResource(R.string.ui_fragment_bytes_default_8_16), options.advanced.fragmentSize, configurable && options.fragment) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(fragmentSize = value)) }
                    }
                    SettingField(stringResource(R.string.ui_fragment_delay_ms_default_2), options.advanced.fragmentDelay, configurable && options.fragment) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(fragmentDelay = value)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabText(stringResource(R.string.ui_encrypted_client_hello), c.text, 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = options.advanced.ech, enabled = configurable, onCheckedChange = { value ->
                            model.options { it.copy(advanced = it.advanced.copy(ech = value)) }
                        })
                    }
                    AetherSettings.keyKinds.forEach { kind ->
                        KeyValue(c, optionLabel(kind), if (ui.keyStatus[kind] == true) "Stored" else "Not created")
                    }
                    var keyKind by rememberSaveable { mutableStateOf("all") }
                    OptionRow(stringResource(R.string.ui_new_keys_for), keyKind, AetherSettings.keyKinds, configurable) { keyKind = it }
                    TextButton(onClick = { model.renewIdentity(keyKind) }, enabled = configurable && bundled && valid) {
                        Text(stringResource(R.string.ui_get_new_warp_keys))
                    }
                }
                Footnote(c, stringResource(R.string.ui_ech_requires_a_usable_ech))
            }
            GroupLabel(c, "Exit-node")
            Panel(c) {
                OptionRow(stringResource(R.string.ui_outbound), options.advanced.exitNode, listOf("") + ui.exitNodes.map { it.id }, configurable,
                    label = { id -> if (id.isBlank()) "freedom (default)" else ui.exitNodes.firstOrNull { it.id == id }?.name ?: "Unavailable node" }) { value ->
                    model.options { it.copy(advanced = it.advanced.copy(exitNode = value)) }
                }
                SettingField(stringResource(R.string.ui_exit_node_finalmask_raw_json), options.advanced.exitFinalMask,
                    configurable && options.advanced.exitNode.isBlank(), multiline = true) { value ->
                    model.options { it.copy(advanced = it.advanced.copy(exitFinalMask = value)) }
                }
                SettingField(stringResource(R.string.ui_exit_node_dialmode), options.advanced.exitDialMode,
                    configurable && options.advanced.exitNode.isBlank()) { value ->
                    model.options { it.copy(advanced = it.advanced.copy(exitDialMode = value)) }
                }
            }
            if (!direct) {
                SecondaryButton(c, stringResource(R.string.ui_scan_for_an_endpoint), null,
                    Modifier.padding(horizontal = Gutter).fillMaxWidth(), enabled = configurable && bundled && valid) {
                    model.testAccess()
                }
            }
            }
            if (tab == 2) {
            Footnote(c, stringResource(R.string.ui_these_checks_measure_the_local))
            GroupLabel(c, if (direct) "Direct command" else "Aether command")
            Panel(c) {
                val preview = model.commandPreview()
                Text(preview, Modifier.padding(14.dp), color = c.text2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { clipboard.setText(AnnotatedString(preview)) }) { Text(stringResource(R.string.ui_copy)) }
            }
            GroupLabel(c, "Log")
            Panel(c) {
                Text(ui.log.joinToString("\n").ifBlank { ui.message ?: "No operation recorded" },
                    Modifier.padding(14.dp), color = c.text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { clipboard.setText(AnnotatedString(ui.log.joinToString("\n").ifBlank { ui.message.orEmpty() })) }) { Text(stringResource(R.string.ui_copy)) }
            }
            GroupLabel(c, "Access checks")
            Panel(c) {
                val result = ui.result
                KeyValue(c, "Authenticated DNS response", outcome(result?.dns))
                Hairline(c)
                KeyValue(c, "HTTPS certificate", outcome(result?.tls))
                Hairline(c)
                KeyValue(c, "HTTPS traffic", outcome(result?.traffic))
                result?.latencyMs?.let { KeyValue(c, "HTTPS check time", "$it ms") }
            }
            Footnote(c, stringResource(R.string.ui_connect_starts_the_engine_and))
            SecondaryButton(c, if (ui.working) stringResource(R.string.ui_cancel) else stringResource(R.string.ui_run_access_checks), null,
                Modifier.padding(horizontal = Gutter).fillMaxWidth(), enabled = ui.working || configurable && bundled && valid) {
                if (ui.working) model.cancel() else model.testAccess()
            }
            }
        }
        PrimaryButton(c, when {
            failed -> "Stop failed session"
            own && !idle -> "Stop connection"
            direct && ui.options.vpnFallback -> "Connect with WARP fallback"
            else -> if (direct) "Connect Direct" else "Connect WARP"
        }, Icons.Rounded.PowerSettingsNew,
            Modifier.navigationBarsPadding().padding(horizontal = Gutter, vertical = 10.dp).fillMaxWidth(),
            enabled = failed || own && !idle || configurable && bundled && valid) {
            if (failed || own && !idle) VpnController.stopVpn(context)
            else if (VpnController.connectionState.value.status == ConnectionStatus.DISCONNECTED) {
                // Same rule as the power button: with "Power button fallback" on (default) a WARP that carries no
                // traffic here hands over to saved servers and the recovery engines (Psiphon, Tor); off pins it.
                // Direct keeps its own explicit WARP fallback switch and stays pinned.
                vpn.startOrRequestPermission(context, model.profile(),
                    exact = direct || com.example.vpn.safety.PowerButtonPolicy.exact(
                        com.example.RayApplication.instance.settingsRepository.getSettings())) { onPermission() }
            }
        }
    }
}

private fun outcome(value: Boolean?) = when (value) {
    true -> "Passed"
    false -> "Failed"
    null -> "Pending"
}

@Composable
private fun OptionRow(title: String, selected: String, options: List<String>, enabled: Boolean, label: (String) -> String = ::optionLabel, onSelect: (String) -> Unit) {
    val c = connectionColors
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
        LabText(title, c.text2, 12.sp)
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
    else -> option
}

@Composable
private fun SettingField(label: String, value: String, enabled: Boolean, multiline: Boolean = false, onChange: (String) -> Unit) {
    val c = connectionColors
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        enabled = enabled, singleLine = !multiline, maxLines = if (multiline) 8 else 1,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, unfocusedTextColor = c.text,
            focusedBorderColor = c.accent, unfocusedBorderColor = c.stroke,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.text2, cursorColor = c.accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
}
