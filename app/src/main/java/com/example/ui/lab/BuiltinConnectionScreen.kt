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
    val model: ConnectionsViewModel = viewModel(factory = factory)
    val ui by model.ui.collectAsStateWithLifecycle()
    val session by vpn.connectionState.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refreshOptions() }
    val c = labColors
    val idle = session.status == ConnectionStatus.DISCONNECTED
    val own = session.activeProfile?.id == if (direct) BuiltinConnections.DIRECT else BuiltinConnections.AETHER
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
                        own && !idle -> session.statusLabel
                        !idle -> "Another connection is active"
                        ui.result?.traffic == true -> "Access check passed"
                        else -> "Ready to test"
                    }, c.text, 21.sp, FontWeight.Bold)
                    LabText(if (direct) "Direct DPI profiles use your ISP's public exit IP."
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
                        TextButton(model::renewIdentity, enabled = configurable && bundled) {
                            Text(if (ui.identity) "Renew" else "Create")
                        }
                    }
                }
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
                Modifier.padding(horizontal = Gutter).fillMaxWidth(), enabled = ui.working || configurable && bundled) {
                if (ui.working) model.cancel() else model.testAccess()
            }
        }
        PrimaryButton(c, if (own && !idle) "Stop connection" else "Connect", null,
            Modifier.padding(horizontal = Gutter, vertical = 10.dp).fillMaxWidth(),
            enabled = own && !idle || configurable && bundled && ui.result?.traffic == true) {
            if (own && !idle) VpnController.stopVpn(context)
            else if (model.ui.value.result?.traffic == true &&
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
    "gool" -> "Gool"
    "ipv4" -> "IPv4"
    "ipv6" -> "IPv6"
    "oob" -> "OOB"
    else -> option
}
