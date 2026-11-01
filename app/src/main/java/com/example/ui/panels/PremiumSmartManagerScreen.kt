
package com.example.ui.panels

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.cloudflare.CloudflareDnsApi
import com.example.panels.cloudflare.SmartDomains
import com.example.panels.cloudflare.SmartDomainsStore
import com.example.panels.cloudflare.TokenTemplate
import com.example.panels.servers.ServerToolCatalog
import com.example.panels.servers.SmartManagerPlanner
import com.example.panels.servers.SmartServerHealth
import com.example.ui.panels.servers.ServersState
import com.example.ui.panels.servers.ServersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI recreated from the two owner-approved MAXIMUS reference images.
 *
 * All amounts reflect cached authenticated SSH facts. No imaginary CPU history, fake
 * green install states, or unverified automatic multi-install is represented as live.
 */
@Composable
internal fun PremiumSmartManagerScreen(
    vm: ServersViewModel, state: ServersState,
    onOpenCloudflare: () -> Unit, onOpenSmartDomains: () -> Unit
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selectedServer by rememberSaveable { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var showPlan by remember { mutableStateOf(false) }
    val server = state.servers.firstOrNull { it.id == selectedServer } ?: state.servers.firstOrNull()
    val facts = server?.facts
    val inspection = server?.let { SmartManagerPlanner.inspect(it) }
    val fresh = server != null && facts != null && System.currentTimeMillis() - server.checkedAt in 0..60_000L
    val candidates = inspection?.candidates.orEmpty()
    val readiness = if (candidates.isEmpty()) null else
        (candidates.count { it.status == SmartManagerPlanner.Status.READY_FOR_WIZARD ||
            it.status == SmartManagerPlanner.Status.INSTALLED }.toFloat() / candidates.size)
    val tabs = listOf("Overview", "Servers", "Cloudflare", "Deploy", "Nodes", "Monitor")
    val icons = listOf(Icons.Default.Bolt, Icons.Default.Storage, Icons.Default.Cloud,
        Icons.Default.Dashboard, Icons.Default.CheckCircle, Icons.Default.Security)
    val nodes = server?.let { vm.managedNodesFor(it.id) }.orEmpty()

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(Mx.Black).padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MxHero("MAXIMUS  /  SMART MANAGER", "Autonomous Infrastructure",
            "Intelligent VPS control • deployment • Cloudflare")

        if (state.servers.isEmpty()) {
            MxCard("CONNECT YOUR FIRST VPS", "Verified SSH access is required", Mx.Gold) {
                MxAction("Add VPS", onClick = { vm.openAdd() }, strong = true)
            }
            return@Column
        }

        // Reference: selected VPS, managed nodes, readiness and deployment health.
        BoxWithConstraints {
            val columns = if (maxWidth > 580.dp) 4 else 2
            val labels = listOf(
                Triple("VPS", server?.displayName.orEmpty(), Mx.Gold),
                Triple("Nodes", nodes.size.toString(), Mx.Cyan),
                Triple("Install readiness", if (fresh && readiness != null)
                    "${(readiness * 100).toInt()}%" else "Scan first", if (fresh) Mx.Green else Mx.Orange),
                Triple("Deploy health", if (!fresh) "Unverified" else
                    if (facts?.blocker() == null) "Inspected" else "Blocked",
                    if (fresh && facts?.blocker() == null) Mx.Green else Mx.Orange)
            )
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                labels.chunked(columns).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        group.forEach { (label, value, tint) ->
                            Surface(Modifier.weight(1f), color = Mx.Card,
                                shape = RoundedCornerShape(11.dp),
                                border = BorderStroke(1.dp, Mx.Line)) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(label, color = Mx.Muted, fontSize = 10.sp, maxLines = 1)
                                    Text(value, color = tint, fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        // The reference's CPU/RAM/Disk/Ports strip, with honest measurements rather than
        // fabricated time-series charts. Load average is explicitly marked as LOAD.
        BoxWithConstraints {
            val metricColumns = if (maxWidth >= 600.dp) 4 else 2
            val ram = facts?.ramMb?.takeIf { it > 0L }
            val disk = facts?.diskMb?.takeIf { it > 0L }
            val stats = listOf(
                Triple("CPU LOAD (1m)", if (facts == null || facts.cpus <= 0) "—" else
                    "${"%.2f".format(java.util.Locale.US, facts.load1)} / ${facts.cpus}",
                    if (facts == null || facts.cpus <= 0) null else
                        (facts.load1.toFloat() / facts.cpus).coerceIn(0f, 1f)),
                Triple("RAM FREE", ram?.let { "${facts?.ramAvailableMb} MB" } ?: "—",
                    ram?.let { (facts!!.ramAvailableMb.toFloat() / it).coerceIn(0f, 1f) }),
                Triple("DISK FREE", disk?.let { "${facts?.diskFreeMb?.div(1024)} GB" } ?: "—",
                    disk?.let { (facts!!.diskFreeMb.toFloat() / it).coerceIn(0f, 1f) }),
                Triple("PORTS TCP / UDP", if (facts?.socketInspectionAvailable == true)
                    "${facts.tcpPorts.size} / ${facts.udpPorts.size}" else "Not scanned",
                    null)
            )
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                stats.chunked(metricColumns).forEachIndexed { row, group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        group.forEachIndexed { index, s ->
                            MxMetric(s.first, s.second, s.third,
                                if (row * metricColumns + index == 2) Mx.Gold else Mx.Cyan,
                                Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Matching reference: icon tabs with a blue selected surface.
        BoxWithConstraints {
            val columns = if (maxWidth >= 680.dp) 6 else 3
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                tabs.indices.chunked(columns).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        group.forEach { index ->
                            val active = page == index
                            Surface(Modifier.weight(1f).clickable { page = index },
                                color = if (active) Mx.Blue else Mx.Card,
                                border = BorderStroke(1.dp, if (active) Mx.Cyan else Mx.Line),
                                shape = RoundedCornerShape(11.dp)) {
                                Row(Modifier.padding(horizontal = 5.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center) {
                                    Icon(icons[index], null, tint = if (active) Mx.White else Mx.Muted,
                                        modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(tabs[index], color = if (active) Mx.White else Mx.Muted,
                                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (page == 0 || page == 3) {
            // The approved artwork puts selection and Cloudflare side by side on wide screens.
            BoxWithConstraints {
                val wide = maxWidth >= 660.dp
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MxInstallSelector(candidates, chosen, { chosen = it },
                            onInstall = { showPlan = true }, Modifier.weight(1f))
                        MxCloudflareSetup(onOpenCloudflare, onOpenSmartDomains, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MxInstallSelector(candidates, chosen, { chosen = it },
                            onInstall = { showPlan = true })
                        MxCloudflareSetup(onOpenCloudflare, onOpenSmartDomains)
                    }
                }
            }
            BoxWithConstraints {
                if (maxWidth >= 660.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MxChecks(server, inspection, vm, Modifier.weight(1f))
                        MxCard("Automation Flow", "Inspect → select → DNS → review → install → verify",
                            Mx.Cyan, Modifier.weight(1f)) {
                            MxFlowSteps(listOf("Inspect", "Select", "Cloudflare",
                                "Review", "Install", "Verify"))
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MxChecks(server, inspection, vm)
                        MxCard("Automation Flow", "Six-step guided deployment", Mx.Cyan) {
                            MxFlowSteps(listOf("Inspect", "Select", "Cloudflare",
                                "Review", "Install", "Verify"))
                        }
                    }
                }
            }
        } else if (page == 1 && server != null) {
            MxCard("Server Control", "SSH authentication and live inspections", Mx.Gold) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.servers.forEach { vps ->
                        MxAction(vps.displayName, { selectedServer = vps.id })
                    }
                }
                MxInfoRow("Address", server.host, Mx.Cyan)
                MxInfoRow("SSH host fingerprint", if (server.hostKeySha256.isBlank())
                    "Missing" else "Pinned", if (server.hostKeySha256.isBlank()) Mx.Orange else Mx.Green)
                MxInfoRow("System", facts?.osName ?: "Unknown")
                MxInfoRow("Inspection", if (fresh) "Current" else "Scan required",
                    if (fresh) Mx.Green else Mx.Orange)
                MxAction("Inspect VPS", { vm.refresh(server.id) }, strong = true)
                MxAction("Open VPS management", { vm.openDetail(server.id) })
            }
        } else if (page == 2) {
            MxCloudflareSetup(onOpenCloudflare, onOpenSmartDomains)
        } else if (page == 4) {
            MxCard("Managed Nodes", "Client traffic is verified separately", Mx.Cyan) {
                MxInfoRow("Managed node records", nodes.size.toString(), Mx.Cyan)
                nodes.forEach { MxInfoRow(it.serviceId, it.state.name) }
                if (nodes.isEmpty()) Text("No managed nodes on this VPS.", color = Mx.Muted, fontSize = 12.sp)
                if (server != null) MxAction("Manage and test nodes", { vm.openDetail(server.id) })
            }
        } else if (page == 5) {
            MxCard("Service Monitor", "Based on latest authenticated VPS scan", Mx.Cyan) {
                MxInfoRow("SSH inventory", if (fresh) "Current" else "Stale / missing",
                    if (fresh) Mx.Green else Mx.Orange)
                facts?.services?.toSortedMap()?.forEach { (name, status) ->
                    MxInfoRow(name, status, if (status == "active") Mx.Green else Mx.Orange)
                }
                if (facts?.services.isNullOrEmpty()) Text("Scan the VPS to view service state.",
                    color = Mx.Muted, fontSize = 12.sp)
                if (server != null) MxAction("Refresh monitor", { vm.refresh(server.id) }, strong = true)
            }
        }
        if (state.error.isNotBlank()) Text(state.error, color = Mx.Red, fontSize = 12.sp)
        Text("MAXIMUS VPN  •  Verified facts only; no installation starts during inspection.",
            color = Mx.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp))
    }

    if (showPlan) {
        val chosenCandidates = candidates.filter { it.id in chosen }
        val blockers = inspection?.let { SmartManagerPlanner.bundleBlockers(it, chosen) }.orEmpty()
        val canStart = chosenCandidates.size == 1 && blockers.isEmpty() &&
            chosenCandidates.single().id != SmartManagerPlanner.PRIVATE_PSIPHON
        AlertDialog(
            onDismissRequest = { showPlan = false },
            title = { Text("Smart Installation Review") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected: ${chosenCandidates.joinToString { it.title }.ifBlank { "None" }}")
                    if (blockers.isEmpty() && !canStart) Text(
                        "Atomic multi-engine installation is not yet verified. Each tool requires its own approved wizard, compatibility check and rollback."
                    )
                    blockers.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                    if (canStart) Text("One supported engine is eligible. Open its wizard to review configuration and explicitly approve installation.")
                }
            },
            confirmButton = {
                TextButton(enabled = canStart, onClick = {
                    val id = chosenCandidates.single().id
                    vm.openWizard(id, server?.id)
                    showPlan = false
                }) { Text("Open verified installer") }
            },
            dismissButton = { TextButton(onClick = { showPlan = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun MxInstallSelector(
    candidates: List<SmartManagerPlanner.Candidate>, chosen: Set<String>,
    onChange: (Set<String>) -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier
) {
    MxCard("One-click Multi-Install", "Select panels and engines on the chosen VPS",
        Mx.Cyan, modifier) {
        candidates.forEach { c ->
            val statusColor = when (c.status) {
                SmartManagerPlanner.Status.INSTALLED -> Mx.Cyan
                SmartManagerPlanner.Status.READY_FOR_WIZARD -> Mx.Green
                SmartManagerPlanner.Status.UNVERIFIED -> Mx.Orange
                SmartManagerPlanner.Status.NEEDS_INSPECTION -> Mx.Gold
                SmartManagerPlanner.Status.BLOCKED -> Mx.Red
            }
            val state = when (c.status) {
                SmartManagerPlanner.Status.INSTALLED -> "Detected"
                SmartManagerPlanner.Status.READY_FOR_WIZARD -> "Review"
                SmartManagerPlanner.Status.UNVERIFIED -> "Unverified"
                SmartManagerPlanner.Status.NEEDS_INSPECTION -> "Scan required"
                SmartManagerPlanner.Status.BLOCKED -> "Blocked"
            }
            Surface(color = Mx.Card, shape = RoundedCornerShape(10.dp),
                border = BorderStroke(.6.dp, Mx.Line), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                    Checkbox(checked = c.id in chosen,
                        onCheckedChange = { selected ->
                            onChange(if (selected) chosen + c.id else chosen - c.id)
                        },
                        colors = CheckboxDefaults.colors(checkedColor = Mx.Blue, uncheckedColor = Mx.Muted),
                        modifier = Modifier.size(35.dp))
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(c.title, color = Mx.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1)
                        Text(c.reason, color = Mx.Muted, fontSize = 9.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    MxStatus(state, statusColor)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MxAction("Install Selected (${chosen.size})", onInstall,
                Modifier.weight(1.4f), strong = true, enabled = chosen.isNotEmpty())
            MxAction("Preview Plan", onInstall, Modifier.weight(1f))
        }
        Text("Safety gate: unsupported bundles cannot install automatically.",
            color = Mx.Muted, fontSize = 10.sp)
    }
}

@Composable
private fun MxCloudflareSetup(
    onCloudflare: () -> Unit, onSmartDomains: () -> Unit, modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember(context) { SmartDomainsStore(context) }
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var tokenStored by remember { mutableStateOf(store.token().isNotBlank()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (tokenStored) "Token stored securely" else "Token required") }
    var zones by remember { mutableStateOf<List<SmartDomains.Zone>>(emptyList()) }
    var zone by remember { mutableStateOf(store.zoneId()) }
    MxCard("Cloudflare Integration", "Create a scoped token, verify zones, then manage DNS",
        Mx.Gold, modifier) {
        MxAction("Create Cloudflare API Token", strong = true, onClick = {
            val uri = TokenTemplate.url(TokenTemplate.SMART_DOMAINS, "Maximus Smart Manager")
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
                .onFailure { status = "Browser unavailable" }
        }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = token, onValueChange = { if (it.length <= 100) token = it.trim() },
            label = { Text("Cloudflare API Token", fontSize = 11.sp) },
            placeholder = { Text("Paste your scoped token", fontSize = 11.sp) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Mx.White, unfocusedTextColor = Mx.White,
                focusedBorderColor = Mx.Gold, unfocusedBorderColor = Mx.Line,
                focusedLabelColor = Mx.Gold, unfocusedLabelColor = Mx.Muted))
        Text("Zone Read + DNS Edit only; secret saved using Android Keystore.",
            color = Mx.Muted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MxAction(if (working) "Verifying..." else "Verify & Save", onClick = {
                val value = token.trim()
                if (!TokenTemplate.looksLikeToken(value)) {
                    status = "Invalid API token format"
                } else {
                    working = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { CloudflareDnsApi().zones(value) }
                        }
                        result.onSuccess {
                            zones = it
                            if (it.isEmpty()) {
                                status = "No accessible zones. Check token scope."
                            } else {
                                store.saveToken(value)
                                token = ""
                                tokenStored = true
                                status = "${it.size} zones verified • token encrypted"
                            }
                        }.onFailure { status = "Token verification failed; no changes made" }
                        working = false
                    }
                }
            }, Modifier.weight(1f), enabled = !working && token.isNotBlank())
            MxAction("Discover zones", onClick = {
                val value = store.token()
                if (value.isBlank()) status = "Verify a token first"
                else {
                    working = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { CloudflareDnsApi().zones(value) }
                        }
                        result.onSuccess { zones = it; status = "${it.size} accessible zones" }
                            .onFailure { status = "Unable to retrieve zones" }
                        working = false
                    }
                }
            }, Modifier.weight(1f), enabled = tokenStored && !working)
        }
        MxStatus(status, if (tokenStored) Mx.Green else Mx.Orange)
        if (zones.isNotEmpty()) {
            Text("Select Domain (Zone)", color = Mx.Gold, fontSize = 11.sp)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                zones.filter { it.status == "active" }.take(12).forEach { z ->
                    Surface(color = if (zone == z.id) Mx.Blue.copy(alpha = .23f) else Mx.Card,
                        border = BorderStroke(1.dp, if (zone == z.id) Mx.Cyan else Mx.Line),
                        shape = RoundedCornerShape(9.dp), modifier = Modifier
                            .fillMaxWidth().clickable {
                                zone = z.id
                                store.saveZoneId(z.id)
                            }) {
                        Text("◉ ${z.name}", modifier = Modifier.padding(9.dp),
                            color = Mx.White, fontSize = 11.sp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MxAction("Smart Domains", onSmartDomains, Modifier.weight(1f))
            MxAction("Cloudflare DNS", onCloudflare, Modifier.weight(1f))
        }
        Text("Create a token on Cloudflare's site. No zone or DNS record is modified by this card.",
            color = Mx.Muted, fontSize = 10.sp)
    }
}

@Composable
private fun MxChecks(
    server: com.example.panels.servers.ManagedServer?,
    inspection: SmartManagerPlanner.Inspection?,
    vm: ServersViewModel, modifier: Modifier = Modifier
) {
    val facts = server?.facts
    val fresh = server != null && System.currentTimeMillis() - server.checkedAt in 0..60_000L
    MxCard("Pre-flight Checks", "Validated against the selected VPS", Mx.Cyan, modifier) {
        MxInfoRow("SSH fingerprint",
            if (server?.hostKeySha256.isNullOrBlank()) "Pending" else "Pinned",
            if (server?.hostKeySha256.isNullOrBlank()) Mx.Orange else Mx.Green)
        MxInfoRow("Port conflicts", if (facts?.socketInspectionAvailable == true && fresh) "Scanned" else "Needs scan",
            if (facts?.socketInspectionAvailable == true && fresh) Mx.Green else Mx.Orange)
        MxInfoRow("DNS safety", if (facts?.socketInspectionAvailable == true && fresh)
            "Port 53: ${if (53 in facts.udpPorts || 53 in facts.tcpPorts) "occupied" else "available"}"
            else "Pending", if (facts?.socketInspectionAvailable == true && fresh &&
                53 !in facts.udpPorts && 53 !in facts.tcpPorts) Mx.Green else Mx.Orange)
        MxInfoRow("Firewall", facts?.firewall ?: "Not inspected", Mx.Cyan)
        MxInfoRow("Resource fit", if (facts == null) "Pending" else
            if (facts.blocker() == null) "Preliminary" else "Blocked",
            if (facts?.blocker() == null && facts != null) Mx.Green else Mx.Orange)
        MxInfoRow("Rollback plan", "Per-engine wizard", Mx.Cyan)
        if (server != null) MxAction("Run smart inspection", { vm.refresh(server.id) },
            Modifier.fillMaxWidth(), strong = true)
        inspection?.limitations?.take(2)?.forEach {
            Text("• $it", color = Mx.Muted, fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}
