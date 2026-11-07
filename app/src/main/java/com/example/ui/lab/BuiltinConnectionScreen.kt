package com.example.ui.lab

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
import com.example.ui.viewmodel.VpnViewModel
import com.example.vpn.VpnController
import com.example.vpn.connections.BuiltinConnections
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.ServerlessSidecar

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
    val c = labColors
    val idle = session.status == ConnectionStatus.DISCONNECTED
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
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TitleBar(c, title, "MAXIMUS VPN · CONNECTIONS", Icons.AutoMirrored.Rounded.ArrowBack, onBack)
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
                            !bundled -> "Engine unavailable"
                            ui.working -> "Working…"
                            fallback && !idle -> "WARP fallback"
                            own && !idle -> session.statusLabel
                            !idle -> "Another session is active"
                            ui.result?.traffic == true -> "Local access verified"
                            else -> "Ready to connect"
                        }, c.text, 18.sp, FontWeight.Bold)
                        LabText(if (direct) "DPI bypass · ISP exit IP" else "Cloudflare tunnel · Private device keys",
                            c.text2, 12.sp)
                    }
                }
                if (ui.working || own && session.isBusy) LinearProgressIndicator(
                    Modifier.fillMaxWidth(), color = c.accent, trackColor = c.stroke)
                if (fallback && !idle) LabText("Direct access failed. Your enabled WARP fallback is in use.", c.text2, 12.sp)
                val message = if (own && !idle) session.errorMessage ?: ui.message else ui.message
                message?.let { LabText(it, c.text2, 12.sp) }
                if (ui.working) TextButton(onClick = model::cancel) { Text("Cancel operation") }
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
                SettingField("Alias (remarks)", options.advanced.alias, configurable) { value ->
                    model.options { it.copy(advanced = it.advanced.copy(alias = value)) }
                }
            }
            GroupLabel(c, if (direct) "DPI profile" else "Tunnel settings")
            Panel(c) {
                val choices = if (direct) ServerlessSidecar.variants else AetherSidecar.transports
                OptionRow(if (direct) "Profile" else "Transport", if (direct) options.variant else options.transport,
                    choices, configurable) { value ->
                    model.options { if (direct) it.copy(variant = value) else it.copy(transport = value) }
                }
                Hairline(c)
                OptionRow("IP family", options.family, AetherSidecar.families, configurable) { value ->
                    model.options { it.copy(family = value) }
                }
                Hairline(c)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LabText(if (direct) "WARP fallback" else "TLS fragmentation", c.text, 14.sp)
                        LabText(if (direct) "Switch to WARP if Direct fails" else "Split supported TLS handshakes", c.text2, 12.sp)
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
                            LabText("WARP identity", c.text, 14.sp)
                            LabText(if (ui.identity) "Stored on this device" else "Created automatically when connecting", c.text2, 12.sp)
                        }
                        TextButton(onClick = { model.renewIdentity() }, enabled = configurable && bundled && valid) {
                            Text(if (ui.identity) "Renew" else "Create")
                        }
                    }
                }
            }
            if (direct) {
                GroupLabel(c, "Encrypted DNS")
                Panel(c) {
                    OptionRow("Preferred resolver", options.resolver, ServerlessSidecar.resolvers, configurable) {
                        model.options { o -> o.copy(resolver = it) }
                    }
                }
                Footnote(c, "Both resolvers use authenticated HTTPS through the DPI profile. The other resolver remains available as fallback.")
            }
            Footnote(c, if (direct) "Auto tries DPI methods and remembers the last verified method for this network. Advanced routing is optional."
                else "Auto selects a transport. Endpoint, registration, relay and exit-node controls are available in Advanced.")
            SecondaryButton(c, "Run access checks", null,
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
                    OptionRow("Scan mode", options.advanced.scan, AetherSettings.scans, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(scan = value)) }
                    }
                    OptionRow("Obfuscation", options.advanced.noize, AetherSettings.noises,
                        configurable && options.transport in listOf("auto", "h3", "wireguard", "wiw")) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(noize = value)) }
                    }
                    SettingField("Address · blank to scan", options.advanced.endpointAddress, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.withEndpoint(address = value)) }
                    }
                    SettingField("Port", options.advanced.endpointPort, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.withEndpoint(port = value)) }
                    }
                }
                GroupLabel(c, "WARP registration and TLS")
                Panel(c) {
                    OptionRow("API host", options.advanced.apiHost, AetherSettings.apiHosts, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiHost = value)) }
                    }
                    OptionRow("API server name (SNI)", options.advanced.apiSni, AetherSettings.apiHosts, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiSni = value)) }
                    }
                    OptionRow("Account API route", options.advanced.apiRoute, AetherSettings.apiRoutes, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiRoute = value)) }
                    }
                    Footnote(c, "Auto tests Direct OOB for key registration. Native uses the direct API path. This does not change the WARP tunnel or disable certificate checks.")
                    OptionRow("Fingerprint", options.advanced.fingerprint, com.example.vpn.sidecar.AetherTlsProfiles.names, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(fingerprint = value)) }
                    }
                    SettingField("API address · blank for automatic", options.advanced.apiAddress, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiAddress = value)) }
                    }
                    SettingField("Fragment bytes · default 8-16", options.advanced.fragmentSize, configurable && options.fragment) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(fragmentSize = value)) }
                    }
                    SettingField("Fragment delay ms · default 2-10", options.advanced.fragmentDelay, configurable && options.fragment) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(fragmentDelay = value)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabText("Encrypted Client Hello", c.text, 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = options.advanced.ech, enabled = configurable, onCheckedChange = { value ->
                            model.options { it.copy(advanced = it.advanced.copy(ech = value)) }
                        })
                    }
                    AetherSettings.keyKinds.forEach { kind ->
                        KeyValue(c, optionLabel(kind), if (ui.keyStatus[kind] == true) "Stored" else "Not created")
                    }
                    var keyKind by rememberSaveable { mutableStateOf("all") }
                    OptionRow("New keys for", keyKind, AetherSettings.keyKinds, configurable) { keyKind = it }
                    TextButton(onClick = { model.renewIdentity(keyKind) }, enabled = configurable && bundled && valid) {
                        Text("Get new WARP keys")
                    }
                }
                Footnote(c, "ECH requires a usable ECH key. TLS uses Chrome by default. API host and SNI stay matched; certificate verification remains enabled.")
            }
            GroupLabel(c, "Exit-node")
            Panel(c) {
                OptionRow("Outbound", options.advanced.exitNode, listOf("") + ui.exitNodes.map { it.id }, configurable,
                    label = { id -> if (id.isBlank()) "freedom (default)" else ui.exitNodes.firstOrNull { it.id == id }?.name ?: "Unavailable node" }) { value ->
                    model.options { it.copy(advanced = it.advanced.copy(exitNode = value)) }
                }
                SettingField("exit-node finalMask · raw JSON", options.advanced.exitFinalMask,
                    configurable && options.advanced.exitNode.isBlank(), multiline = true) { value ->
                    model.options { it.copy(advanced = it.advanced.copy(exitFinalMask = value)) }
                }
                SettingField("exit-node dialMode", options.advanced.exitDialMode,
                    configurable && options.advanced.exitNode.isBlank()) { value ->
                    model.options { it.copy(advanced = it.advanced.copy(exitDialMode = value)) }
                }
            }
            if (!direct) {
                SecondaryButton(c, "Scan for an endpoint", null,
                    Modifier.padding(horizontal = Gutter).fillMaxWidth(), enabled = configurable && bundled && valid) {
                    model.testAccess()
                }
            }
            }
            if (tab == 2) {
            Footnote(c, "These checks measure the local engine. They do not prove that every Android app is using the VPN or that DNS cannot leak.")
            GroupLabel(c, if (direct) "Direct command" else "Aether command")
            Panel(c) {
                val preview = model.commandPreview()
                Text(preview, Modifier.padding(14.dp), color = c.text2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { clipboard.setText(AnnotatedString(preview)) }) { Text("Copy") }
            }
            GroupLabel(c, "Log")
            Panel(c) {
                Text(ui.log.joinToString("\n").ifBlank { ui.message ?: "No operation recorded" },
                    Modifier.padding(14.dp), color = c.text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { clipboard.setText(AnnotatedString(ui.log.joinToString("\n").ifBlank { ui.message.orEmpty() })) }) { Text("Copy") }
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
            Footnote(c, "Connect starts the engine and checks access automatically. The optional access test checks the local engine.")
            SecondaryButton(c, if (ui.working) "Cancel" else "Run access checks", null,
                Modifier.padding(horizontal = Gutter).fillMaxWidth(), enabled = ui.working || configurable && bundled && valid) {
                if (ui.working) model.cancel() else model.testAccess()
            }
            }
        }
        PrimaryButton(c, when {
            own && !idle -> "Stop connection"
            direct && ui.options.vpnFallback -> "Connect with WARP fallback"
            else -> if (direct) "Connect Direct" else "Connect WARP"
        }, Icons.Rounded.PowerSettingsNew,
            Modifier.navigationBarsPadding().padding(horizontal = Gutter, vertical = 10.dp).fillMaxWidth(),
            enabled = own && !idle || configurable && bundled && valid) {
            if (own && !idle) VpnController.stopVpn(context)
            else if (VpnController.connectionState.value.status == ConnectionStatus.DISCONNECTED) {
                vpn.startOrRequestPermission(context, model.profile(), exact = true) { onPermission() }
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
    val c = labColors
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
    val c = labColors
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        enabled = enabled, singleLine = !multiline, maxLines = if (multiline) 8 else 1,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, unfocusedTextColor = c.text,
            focusedBorderColor = c.accent, unfocusedBorderColor = c.stroke,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.text2, cursorColor = c.accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
}
