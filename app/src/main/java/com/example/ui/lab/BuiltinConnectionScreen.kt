package com.example.ui.lab

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val fallback = direct && ui.options.vpnFallback && session.activeProfile?.id == BuiltinConnections.AETHER
    val own = fallback || session.activeProfile?.id == if (direct) BuiltinConnections.DIRECT else BuiltinConnections.AETHER
    val valid = direct || ui.options.advanced.problem() == null
    val configurable = idle && !ui.working
    val bundled = model.bundled()
    val options = ui.options
    val title = if (direct) "Serverless Direct" else "Aether / WARP"
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TitleBar(c, title, "MAXIMUS LAB · Built-in connection", Icons.AutoMirrored.Rounded.ArrowBack, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(c) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabText(when {
                        !bundled -> "Engine unavailable in this build"
                        ui.working -> "Running checks"
                        fallback && !idle -> "WARP fallback · ${session.statusLabel}"
                        own && !idle -> session.statusLabel
                        !idle -> "Another connection is active"
                        ui.result?.traffic == true -> "Access check passed"
                        else -> "Ready to test"
                    }, c.text, 21.sp, FontWeight.Bold)
                    LabText(if (fallback && !idle) "Direct access failed. The enabled WARP fallback is active."
                        else if (direct) "Direct DPI profiles use your ISP's public exit IP."
                        else "Cloudflare WARP tunnel with an identity stored on this device.", c.text2, 13.sp)
                    if (ui.working) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                            LabText(if (ui.identity) "Stored on this device" else "Created on first access check", c.text2, 12.sp)
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
            } else {
                GroupLabel(c, "Endpoint discovery")
                Panel(c) {
                    OptionRow("Scan mode", options.advanced.scan, AetherSettings.scans, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(scan = value)) }
                    }
                    OptionRow("Obfuscation", options.advanced.noize, AetherSettings.noises,
                        configurable && options.transport in listOf("auto", "h3", "wireguard", "wiw")) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(noize = value)) }
                    }
                    SettingField("Endpoint · blank to scan", options.advanced.endpoint, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(endpoint = value)) }
                    }
                }
                GroupLabel(c, "WARP registration and TLS")
                Panel(c) {
                    OptionRow("API host / SNI", options.advanced.apiHost, AetherSettings.apiHosts, configurable) { value ->
                        model.options { it.copy(advanced = it.advanced.copy(apiHost = value)) }
                    }
                    SettingField("API IP · blank for automatic", options.advanced.apiAddress, configurable) { value ->
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
                    KeyValue(c, "WireGuard key", if (ui.wireguardIdentity) "Stored" else "Not created")
                    KeyValue(c, "MASQUE key", if (ui.masqueIdentity) "Stored" else "Not created")
                    var keyKind by remember { mutableStateOf("masque") }
                    OptionRow("New keys for", keyKind, listOf("masque", "wg"), configurable) { keyKind = it }
                    TextButton(onClick = { model.renewIdentity(keyKind) }, enabled = configurable && bundled && valid) {
                        Text("Get new WARP keys")
                    }
                }
                Footnote(c, "ECH requires a usable ECH key. TLS uses Chrome by default. API host and SNI stay matched; certificate verification remains enabled.")
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
            (if (own && !idle) session.errorMessage ?: ui.message else ui.message)?.let {
                Footnote(c, it)
            }
            Footnote(c, "Access checks run through the local engine. Starting the VPN runs the connection check again.")
            SecondaryButton(c, if (ui.working) "Cancel" else "Run access checks", null,
                Modifier.padding(horizontal = Gutter).fillMaxWidth(), enabled = ui.working || configurable && bundled && valid) {
                if (ui.working) model.cancel() else model.testAccess()
            }
        }
        val canConnect = ui.result?.traffic == true || direct && ui.options.vpnFallback
        PrimaryButton(c, when {
            own && !idle -> "Stop connection"
            direct && ui.options.vpnFallback -> "Connect with WARP fallback"
            else -> "Connect"
        }, null,
            Modifier.padding(horizontal = Gutter, vertical = 10.dp).fillMaxWidth(),
            enabled = own && !idle || configurable && bundled && valid && canConnect) {
            if (own && !idle) VpnController.stopVpn(context)
            else if ((model.ui.value.result?.traffic == true || direct && model.ui.value.options.vpnFallback) &&
                VpnController.connectionState.value.status == ConnectionStatus.DISCONNECTED) {
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
private fun OptionRow(title: String, selected: String, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    val c = labColors
    var expanded by remember { mutableStateOf(false) }
    KeyValue(c, title, "") {
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) { Text(optionLabel(selected)) }
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = {
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
private fun SettingField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val c = labColors
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        enabled = enabled, singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, unfocusedTextColor = c.text,
            focusedBorderColor = c.accent, unfocusedBorderColor = c.stroke,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.text2, cursorColor = c.accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
}
