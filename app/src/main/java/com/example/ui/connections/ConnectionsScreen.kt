package com.example.ui.connections

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.*
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.VpnViewModel
import com.example.vpn.sidecar.*
import java.util.Locale

@Composable
fun ConnectionsScreen(vpn: VpnViewModel, settings: SettingsViewModel, onPermission: () -> Unit,
    onNavigate: (String) -> Unit, model: ConnectionsViewModel = viewModel()) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val state by vpn.connectionState.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var detail by rememberSaveable { mutableStateOf(false) }
    val busy = ui.working || state.isBusy
    ConnectionsContent(ui, state, detail, appSettings.killSwitchEnabled, model.bundled(),
        onDetail = { detail = it }, onOptions = model::options,
        onConnect = {
            if (!busy) {
                detail = true
                if (state.status != ConnectionStatus.DISCONNECTED) com.example.vpn.VpnController.stopVpn(context)
                else vpn.startOrRequestPermission(context, model.profile(), exact = true) { onPermission() }
            }
        }, onTest = model::testAccess, onRenew = model::renewIdentity, onCancel = model::cancel,
        onKillSwitch = settings::setKillSwitch, onTheme = settings::toggleDarkTheme, onNavigate = onNavigate)
}

@Composable
fun ConnectionsContent(ui: ConnectionsViewModel.UiState, state: ConnectionState, detail: Boolean,
    killSwitch: Boolean, bundled: Boolean, onDetail: (Boolean) -> Unit,
    onOptions: ((ConnectionsViewModel.Options) -> ConnectionsViewModel.Options) -> Unit,
    onConnect: () -> Unit, onTest: () -> Unit, onRenew: () -> Unit, onCancel: () -> Unit,
    onKillSwitch: (Boolean) -> Unit, onTheme: () -> Unit, onNavigate: (String) -> Unit) {
    val c = AppTheme.colors
    val direct = ui.options.direct
    val activeId = state.activeProfile?.let { com.example.vpn.engine.ProfileExtras.read(it).optString("engine") }
    val owns = activeId == if (direct) ServerlessSidecar.ID else AetherSidecar.ID
    val connected = owns && state.status == ConnectionStatus.CONNECTED
    val occupied = state.status != ConnectionStatus.DISCONNECTED
    val enabled = !ui.working && !occupied
    var menu by remember { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    val heading = if (!detail) "Connections" else if (direct) "Direct Serverless" else "Aether / WARP"
    GalaxyBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(if (detail) 12.dp else 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (detail) IconButton(onClick = { onDetail(false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to connections", tint = c.textPrimary)
                } else {
                    Text("MAXIMUS", color = if (c.isDark) Color(0xFFEBC998) else Color(0xFF936529), fontWeight = FontWeight.Bold,
                        fontSize = 18.sp, letterSpacing = 1.sp)
                    Text(" VPN", color = c.textSecondary, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onTheme, modifier = Modifier.testTag("galaxy_theme")) {
                    Icon(if (c.isDark) Icons.Default.LightMode else Icons.Default.DarkMode, "Switch Galaxy theme", tint = c.primary)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Other tools", tint = c.textPrimary) }
                    DropdownMenu(menu, { menu = false }) {
                        listOf("Nodes" to "servers", "Top 10" to "top10", "Network lab" to "lab", "AI Agent" to "ai_agent").forEach { (label, route) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onNavigate(route) })
                        }
                    }
                }
            }
            Text(heading, color = c.textPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            if (!detail) {
                GalaxyTabs(listOf("Aether", "Direct"), if (direct) 1 else 0, enabled) { i -> onOptions { it.copy(direct = i == 1) } }
                GalaxyPlanet(Modifier.height(140.dp), animate = !ui.working)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when { ui.working -> "Checking your connection"; connected -> "Connected"; owns && state.isBusy -> "Establishing connection";
                        occupied -> state.statusLabel; else -> "Ready to connect" }, color = c.textPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (direct) "DPI resistance · Your ISP IP" else "Cloudflare WARP · No VPS needed", color = c.textSecondary, fontSize = 14.sp)
                }
                GlassPanel {
                    SettingRow(Icons.Default.Layers, if (direct) "Profile" else "Transport",
                        if (direct) ui.options.variant else transportLabel(ui.options.transport), onClick = { options = true }, enabled = enabled)
                    HorizontalDivider(color = c.borderSubtle)
                    SettingRow(Icons.Default.Badge, if (direct) "Public IP" else "Identity",
                        if (direct) "Unchanged" else if (ui.identity) "Registered" else "Create on first use")
                }
                if (direct) Notice("Your public IP stays unchanged")
            } else {
                if (direct) {
                    Notice("Your public IP stays unchanged")
                    Text("Profile", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    GalaxyTabs(listOf("Auto", "fragA", "fragB", "OOB"), ServerlessSidecar.variants.indexOf(ui.options.variant), enabled) { i ->
                        onOptions { it.copy(variant = ServerlessSidecar.variants[i]) }
                    }
                    Text("Access checks", color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    GlassPanel {
                        val r = ui.result
                        CheckRow("DNS resolution", r?.dns)
                        CheckRow("HTTPS certificate", r?.tls)
                        CheckRow("Real traffic", r?.traffic)
                    }
                    GlassPanel {
                        SettingRow(Icons.Default.Language, "IP family", familyLabel(ui.options.family), { options = true }, enabled)
                        HorizontalDivider(color = c.borderSubtle)
                        ToggleRow("VPN fallback", "Use Aether if direct access fails", ui.options.vpnFallback, enabled) { v -> onOptions { it.copy(vpnFallback = v) } }
                    }
                } else {
                    GlassPanel {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (connected) Icons.Default.CheckCircle else Icons.Default.Shield, null,
                                tint = if (connected) c.statusConnected else c.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (owns) state.statusLabel else "Ready to connect", color = c.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Text("Cloudflare WARP", color = c.textSecondary, fontSize = 12.sp)
                            }
                            if (state.isBusy && owns) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    TunnelFlow(connected)
                    GlassPanel {
                        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Metric(if (owns) state.pingMs?.let { "$it ms" } ?: "—" else "—", "Latency")
                            Metric(if (owns && connected) speed(state.downloadSpeedBps) else "—", "Download")
                            Metric(if (owns && connected) speed(state.uploadSpeedBps) else "—", "Upload")
                        }
                    }
                    GlassPanel {
                        SettingRow(Icons.Default.Layers, "Transport", transportLabel(ui.options.transport), { options = true }, enabled)
                        SettingRow(Icons.Default.Language, "IPv4 / IPv6", familyLabel(ui.options.family), { options = true }, enabled)
                        ToggleRow("Fragment", "TLS ClientHello on H2 and registration", ui.options.fragment, enabled) { v -> onOptions { it.copy(fragment = v) } }
                        ToggleRow("Kill switch", "Block traffic if the tunnel fails", killSwitch, enabled, onKillSwitch)
                    }
                    GlassPanel {
                        SettingRow(Icons.Default.Key, "WARP identity", if (ui.identity) "Registered" else "Not registered")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = onRenew, enabled = enabled && bundled) { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Text(" Renew key") }
                            TextButton(onClick = onTest, enabled = enabled && bundled) { Icon(Icons.Default.Radar, null, Modifier.size(18.dp)); Text(" Scan endpoints") }
                        }
                    }
                }
            }
            if (!bundled) Notice("This build does not include the required engine. Install the complete release.")
            ui.message?.let { Text(it, color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.testTag("connection_message")) }
            if (owns) state.errorMessage?.let { Text(it, color = c.statusError, fontSize = 13.sp) }
            if (ui.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onCancel, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Cancel") }
            } else {
                if (detail && direct) OutlinedButton(onTest, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled && bundled) {
                    Icon(Icons.Default.Science, null, Modifier.size(20.dp)); Text("  Test access")
                }
                Button(onConnect, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("connection_action"),
                    enabled = !state.isBusy && (bundled || occupied), shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)) {
                    Icon(if (occupied) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Text(if (occupied) "Disconnect" else if (detail && direct) "Connect directly" else "Connect",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                if (!detail) TextButton({ onDetail(true) }, Modifier.align(Alignment.CenterHorizontally)) { Text("Connection settings") }
            }
            if (occupied && !owns) Text("Another connection is active. Disconnect before changing methods.", color = c.statusWarning, fontSize = 13.sp)
        }
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text("Connection settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Address family")
                GalaxyTabs(listOf("Auto", "IPv4", "IPv6"), AetherSidecar.families.indexOf(ui.options.family), enabled) { i -> onOptions { it.copy(family = AetherSidecar.families[i]) } }
                if (!direct) {
                    Text("Transport")
                    listOf("auto", "h2", "h3", "wireguard", "gool").forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable(enabled) { onOptions { it.copy(transport = t) } }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(ui.options.transport == t, onClick = { onOptions { it.copy(transport = t) } }, enabled = enabled)
                            Text(transportLabel(t))
                        }
                    }
                }
                Text("Changes apply to the next connection.", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton({ options = false }) { Text("Done") } })
}

@Composable private fun GlassPanel(content: @Composable ColumnScope.() -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(Brush.verticalGradient(listOf(c.surfaceCard.copy(alpha = .94f), c.surface.copy(alpha = .88f))))
        .border(1.dp, c.borderSubtle.copy(alpha = .65f), RoundedCornerShape(20.dp)), content = content)
}
@Composable private fun SettingRow(icon: ImageVector, label: String, value: String, onClick: (() -> Unit)? = null, enabled: Boolean = true) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled, onClick = onClick) else Modifier)
        .heightIn(min = 54.dp).padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp), tint = c.textSecondary); Spacer(Modifier.width(12.dp))
        Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 155.dp))
        if (onClick != null) Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = c.textSecondary)
    }
}
@Composable private fun ToggleRow(label: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, color = c.textPrimary, fontSize = 14.sp)
            Text(hint, color = c.textSecondary, fontSize = 11.sp)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
@Composable private fun CheckRow(label: String, passed: Boolean?) {
    val c = AppTheme.colors
    SettingRow(if (passed == true) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, label,
        when(passed) { true -> "Passed"; false -> "Failed"; null -> "Not tested" })
}
@Composable private fun Notice(text: String) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.statusWarning.copy(alpha = .12f))
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = c.statusWarning); Spacer(Modifier.width(10.dp))
        Text(text, color = if (c.isDark) Color(0xFFFFD684) else Color(0xFF71420B), fontSize = 13.sp)
    }
}
@Composable private fun Metric(value: String, label: String) {
    val c = AppTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = c.textSecondary, fontSize = 11.sp)
    }
}
@Composable private fun TunnelFlow(connected: Boolean) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(Icons.Default.Smartphone to "Device", Icons.Default.Cloud to "WARP", Icons.Default.Language to "Internet").forEachIndexed { i, (icon, label) ->
            if (i > 0) HorizontalDivider(Modifier.width(28.dp), color = if (connected) c.statusConnected else c.borderMedium)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = RoundedCornerShape(18.dp), color = c.primary.copy(alpha = .10f)) { Icon(icon, null, Modifier.padding(16.dp).size(26.dp), tint = c.primary) }
                Spacer(Modifier.height(5.dp)); Text(label, color = c.textSecondary, fontSize = 11.sp)
            }
        }
    }
}
@Composable private fun GalaxyTabs(labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfacePill.copy(alpha = .5f))
        .border(1.dp, c.borderSubtle, RoundedCornerShape(28.dp)).padding(4.dp)) {
        labels.forEachIndexed { i, label ->
            Surface(modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("tab_$label"),
                shape = RoundedCornerShape(24.dp), color = if (i == selected) c.primary else Color.Transparent,
                onClick = { onSelect(i) }, enabled = enabled) {
                Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 14.sp, color = if(i == selected) c.onPrimary else c.textSecondary, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
private fun transportLabel(value: String) = when(value) { "h2" -> "MASQUE · HTTP/2"; "h3" -> "MASQUE · HTTP/3"; "wireguard" -> "WireGuard"; "gool" -> "WireGuard over MASQUE"; else -> "Auto · H2 / H3" }
private fun familyLabel(value: String) = when(value) { "ipv4" -> "IPv4"; "ipv6" -> "IPv6"; else -> "Automatic" }
private fun speed(bytes: Long) = String.format(Locale.US, "%.1f Mbps", bytes * 8.0 / 1_000_000)
