
package com.example.ui.panels

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.panels.servers.SmartManagerState
import com.example.panels.servers.SmartBundlePlanner
import com.example.panels.servers.SmartServerHealth
import com.example.ui.panels.servers.ServersState
import com.example.ui.panels.servers.ServersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.example.panels.servers.INSPECTION_TTL_MS
import kotlinx.coroutines.withContext

/** Network Studio layout backed by authenticated SSH inventory and existing deployment flows. */
@Composable
internal fun PremiumSmartManagerScreen(
    vm: ServersViewModel, state: ServersState,
    onOpenCloudflare: () -> Unit, onOpenSmartDomains: () -> Unit,
    onOpenPsiphon: (String) -> Unit, onBack: () -> Unit
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selectedServer by rememberSaveable { mutableStateOf<String?>(null) }
    val server = state.servers.firstOrNull { it.id == selectedServer } ?: state.servers.firstOrNull()
    var chosen by remember(server?.id) { mutableStateOf(setOf<String>()) }
    var showRegionHop by remember(server?.id) { mutableStateOf(false) }
    var showPlan by remember(server?.id) { mutableStateOf(false) }
    var batchDomain by rememberSaveable(server?.id) { mutableStateOf("") }
    var batchLabel by rememberSaveable(server?.id) { mutableStateOf("t") }
    val facts = server?.facts
    var inspectionNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            inspectionNow = System.currentTimeMillis()
        }
    }
    val inspection = server?.let { SmartManagerPlanner.inspect(it, inspectionNow) }
    val fresh = server != null && facts != null &&
        inspectionNow - server.checkedAt in 0..INSPECTION_TTL_MS
    val candidates = inspection?.candidates.orEmpty()
    // The ledger is Keystore-encrypted: never decrypt it on the main thread on every 5 s tick.
    val nodes by produceState(emptyList<com.example.panels.servers.ManagedNodeRecord>(),
        server?.id, server?.checkedAt, state.tests, state.busy, state.bundle, state.psiphonInstall) {
        val id = server?.id
        value = if (id == null) emptyList() else withContext(Dispatchers.IO) { vm.managedNodesFor(id) }
    }
    val network = com.example.vpn.smart.NetworkKey.current(LocalContext.current)
    val verifiedServices = nodes.filter {
        fresh && it.networkKey == network &&
            it.state == com.example.panels.servers.ManagedNodeRecord.State.AVAILABLE &&
            inspectionNow - it.updatedAt in 0..INSPECTION_TTL_MS
    }.map { it.serviceId }.toSet()
    val engineIds = listOf(SmartManagerPlanner.PRIVATE_PSIPHON, ServerToolCatalog.SOAR,
        ServerToolCatalog.COTTEN, ServerToolCatalog.DNSTT)
    val titles = listOf("Psiphon", "SOAR", "CottenDNS", "DNSTT")
    val icons = listOf(Icons.Default.Security, Icons.Default.Bolt, Icons.Default.Language, Icons.Default.Layers)
    val udp53 = if (fresh && facts?.socketInspectionAvailable == true)
        facts.publicUdpPorts?.let { 53 !in it } else null

    Column(Modifier.fillMaxWidth().background(StudioInk).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioHeading(onBack, onOpenCloudflare)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            state.servers.forEach { vps ->
                StudioTab(vps.displayName, Icons.Default.Storage, vps.id == server?.id) {
                    selectedServer = vps.id
                    chosen = emptySet()
                }
            }
            IconButton(onClick = { vm.openAdd() }) {
                Icon(Icons.Default.Add, "Add VPS", tint = StudioMuted)
            }
        }
        HorizontalDivider(color = StudioLine)
        StudioHero(titles, icons)
        Text(server?.displayName ?: "Connect your first VPS", color = StudioText,
            fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(when {
            fresh && server?.canSignIn == true -> "SSH authenticated · Inspection fresh"
            facts != null -> "Inspection expired · Refresh over SSH"
            else -> "Authenticated SSH inspection required"
        }, color = StudioMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (server == null) {
            MxAction("Add VPS", onClick = { vm.openAdd() }, strong = true)
            return@Column
        }
        StudioResources(facts.takeIf { fresh })
        Surface(color = StudioPanel, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, StudioLine)) {
            Row(Modifier.fillMaxWidth()) {
                listOf(0 to "Services", 1 to "Network", 3 to "Deploy", 5 to "Logs").forEachIndexed { i, (id, label) ->
                    StudioTab(label, listOf(Icons.Default.Dashboard, Icons.Default.Hub,
                        Icons.Default.Upload, Icons.Default.List)[i], page == id, Modifier.weight(1f)) { page = id }
                }
            }
        }
        when (page) {
            0 -> {
                MxAction("RegionHop · Psiphon country exits", { showRegionHop = true })
                StudioTableHeader()
                engineIds.forEachIndexed { index, id ->
                    val candidate = inspection?.candidate(id)
                    val psiphon = index == 0
                    val installed = if (psiphon) facts?.psiphonInstalled == true else server.tool(id) != null
                    // DNSTT runs as two units (socks + dnstt); every one must be active.
                    val running = SmartManagerState.allUnitsActive(facts,
                        if (psiphon) com.example.panels.servers.PrivatePsiphonServerScripts.TOOL_ID else id)
                    val verified = running && id in verifiedServices
                    val (status, tint, action) = when {
                        !fresh -> Triple("Inspect first", StudioMuted, "Inspect")
                        verified -> Triple("Traffic verified", Mx.Green, "Manage")
                        running -> Triple("Running, untested", Mx.Green, if (psiphon) "Manage" else "Test")
                        installed -> Triple("Installed", StudioMuted, "Manage")
                        psiphon -> Triple("Not installed", StudioMuted, "Set up")
                        candidate?.status == SmartManagerPlanner.Status.BLOCKED -> Triple("Review required", StudioGold, "Review")
                        else -> Triple("Not installed", StudioMuted, "Set up")
                    }
                    StudioServiceRow(titles[index], icons[index], status, tint, action,
                        enabled = state.busy.isBlank() && server.id !in state.checking && state.bundle?.running != true) {
                        when {
                            !fresh -> vm.refresh(server.id)
                            psiphon -> onOpenPsiphon(server.id)
                            action == "Test" -> vm.test(server.id, id)
                            (installed || running) -> vm.openDetail(server.id)
                            action == "Review" -> { chosen = setOf(id); showPlan = true }
                            else -> vm.openWizard(id, server.id)
                        }
                    }
                }
            }
            1 -> {
                MxChecks(server, inspection, vm)
                MxCloudflareSetup(onOpenCloudflare, onOpenSmartDomains)
                MxAction("VPS settings", { vm.openDetail(server.id) })
            }
            3 -> {
                // The approved artwork puts selection and Cloudflare side by side on wide screens.
                BoxWithConstraints {
                    val wide = maxWidth >= 600.dp
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
                state.bundle?.takeIf { it.serverId == server?.id }?.let { batch ->
                    MxCard("Batch installation", "Sequential VPS changes · never an atomic rollback", Mx.Cyan) {
                        Text("Selected: ${batch.order.joinToString { id ->
                            ServerToolCatalog.byId(id)?.title ?: id
                        }}", color = Mx.White, fontSize = 12.sp)
                        Text("Completed: ${batch.completed.size}/${batch.order.size}" +
                            (if (batch.running) " · running ${ServerToolCatalog.byId(batch.active)?.title ?: batch.active}"
                                else if (batch.finished) " · server-side setup complete" else " · stopped"),
                            color = if (batch.error.isBlank()) Mx.Green else Mx.Orange,
                            fontSize = 12.sp)
                        if (batch.error.isNotBlank()) Text(
                            "Stopped after ${batch.completed.size} completed installation(s): ${batch.error}. " +
                                "Earlier tools were preserved; inspect the server and operation journal before retrying.",
                            color = Mx.Orange, fontSize = 11.sp)
                        if (batch.finished) Text(
                            "DNS delegation and end-to-end phone tunnel verification are still required.",
                            color = Mx.Muted, fontSize = 11.sp)
                    }
                }
                BoxWithConstraints {
                    if (maxWidth >= 600.dp) {
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
            }
            5 -> {
                MxCard("Service logs", "Selected VPS · Read-only SSH", StudioBlue) {
                    val loggable = server.tools.filter { SmartManagerState.hasLogs(it.id) }
                    loggable.forEach { tool ->
                        MxAction(ServerToolCatalog.byId(tool.id)?.title ?: tool.id, { vm.logs(server.id, tool.id) },
                            enabled = state.busy.isBlank())
                    }
                    if (loggable.isEmpty()) Text("No managed services yet.", color = StudioMuted)
                    val operations by produceState(emptyList<com.example.panels.servers.SmartOperation>(),
                        server.id, state.busy, state.bundle, state.psiphonInstall, state.wizard?.stage) {
                        value = withContext(Dispatchers.IO) { vm.operationsFor(server.id) }
                    }
                    operations.forEach { operation ->
                        Text("${operation.toolId} · ${operation.status.name.lowercase()}", color = StudioMuted, fontSize = 11.sp)
                        // An interrupted install blocks every later install on this VPS until reconciled.
                        if (operation.status == com.example.panels.servers.SmartOperation.Status.UNCERTAIN) {
                            Text("Interrupted: remote effects unknown. Reinspect before another installation.",
                                color = Mx.Red, fontSize = 11.sp)
                            MxAction("Reinspect & acknowledge", {
                                vm.reconcileUncertainOperation(server.id, operation.id)
                            }, enabled = state.busy.isBlank() && state.bundle?.running != true &&
                                state.psiphonInstall?.running != true)
                        }
                    }
                    state.logsFor(server.id)?.takeIf { state.busy.isBlank() && state.error.isBlank() }?.let { (title, lines) ->
                        Text(title, color = StudioText, fontWeight = FontWeight.Bold)
                        lines.takeLast(80).forEach { Text(it, color = StudioMuted, fontSize = 11.sp) }
                    }
                }
            }
        }
        StudioReadiness(udp53, verifiedServices.any { it in engineIds.drop(1) }) {
            page = 3
            showPlan = chosen.isNotEmpty()
        }
        val seconds = ((inspectionNow - server.checkedAt) / 1000).coerceAtLeast(0)
        StudioActivity(when {
            server.id in state.checking -> "Refreshing SSH inspection…"
            state.busy.isNotBlank() -> state.busy
            server.checkedAt > 0L -> "SSH inspection completed · ${if (seconds < 60) "${seconds}s" else "${seconds / 60}m"} ago"
            else -> "Awaiting first SSH inspection"
        })
        if (state.error.isNotBlank()) Text(state.error, color = Mx.Red, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    }

    if (showRegionHop && server != null) RegionHopDialog(vm, state, server.id) { showRegionHop = false }

    if (showPlan) {
        val chosenCandidates = candidates.filter { it.id in chosen }
        val selectedIds = chosenCandidates.map { it.id }.toSet()
        val blockers = inspection?.let { SmartManagerPlanner.bundleBlockers(it, chosen) }.orEmpty()
        val isSingle = chosenCandidates.size == 1
        val batchPlan = inspection?.takeIf { chosenCandidates.size > 1 }?.let {
            SmartBundlePlanner.review(it, chosen, batchDomain, batchLabel)
        }
        val canSingle = isSingle && blockers.isEmpty() && state.busy.isBlank() &&
            state.bundle?.running != true &&
            chosenCandidates.single().id != SmartManagerPlanner.PRIVATE_PSIPHON
        val canBatch = batchPlan?.eligible == true && blockers.isEmpty() &&
            server != null && state.busy.isBlank() && state.bundle?.running != true
        AlertDialog(
            onDismissRequest = { showPlan = false },
            title = { Text("Smart Installation Review") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected: ${chosenCandidates.joinToString { it.title }.ifBlank { "None" }}")
                    if (chosenCandidates.size > 1) {
                        Text("Supported batch: 3X-UI + exactly one DNS tunnel. One approval starts a sequential, stop-on-error installation; it is NOT atomic. Existing successful installs are preserved on later failure.")
                        OutlinedTextField(value = batchDomain, onValueChange = { batchDomain = it },
                            singleLine = true, label = { Text("Your DNS domain") },
                            placeholder = { Text("example.com") })
                        OutlinedTextField(value = batchLabel, onValueChange = { batchLabel = it },
                            singleLine = true, label = { Text("Tunnel label") },
                            placeholder = { Text("t") })
                        Text("No DNS records will be changed automatically. You must configure delegation and verify traffic after installation.")
                    }
                    blockers.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                    batchPlan?.blockers?.filterNot { it in blockers }?.forEach {
                        Text("• $it", color = MaterialTheme.colorScheme.error)
                    }
                    if (canSingle) Text("Open the individual approval wizard before installation.")
                    if (canBatch) Text("One approval will execute both selected services in sequence; each step is recorded and checked over pinned SSH.")
                }
            },
            confirmButton = {
                TextButton(enabled = canSingle || canBatch, onClick = {
                    if (canBatch) vm.installApprovedBundle(requireNotNull(server).id,
                        selectedIds, batchDomain, batchLabel)
                    else if (canSingle) vm.openWizard(chosenCandidates.single().id, server?.id)
                    showPlan = false
                }) { Text(if (canBatch) "Approve & install both" else "Open verified installer") }
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
                SmartManagerPlanner.Status.UNVERIFIED -> "Review"
                SmartManagerPlanner.Status.NEEDS_INSPECTION -> "Scan"
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
    val fresh = server != null && System.currentTimeMillis() - server.checkedAt in 0..INSPECTION_TTL_MS
    val facts = server?.facts.takeIf { fresh }
    MxCard("Pre-flight Checks", "Validated against the selected VPS", Mx.Cyan, modifier) {
        MxInfoRow("SSH fingerprint",
            if (server?.hostKeySha256.isNullOrBlank()) "Pending" else "Pinned",
            if (server?.hostKeySha256.isNullOrBlank()) Mx.Orange else Mx.Green)
        MxInfoRow("Port conflicts", if (facts?.socketInspectionAvailable == true && fresh) "Scanned" else "Needs scan",
            if (facts?.socketInspectionAvailable == true && fresh) Mx.Green else Mx.Orange)
        // Public listeners only: the local systemd-resolved stub (127.0.0.53) is not a conflict.
        val port53 = SmartManagerState.publicPort53Occupied(facts)
        MxInfoRow("DNS safety", when (port53) {
            null -> "Pending"
            true -> "Port 53: occupied"
            false -> "Port 53: available"
        }, if (port53 == false) Mx.Green else Mx.Orange)
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
