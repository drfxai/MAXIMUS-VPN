
package com.example.ui.panels

import androidx.compose.ui.res.stringResource
import com.example.R
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
        ServerToolCatalog.COTTEN, ServerToolCatalog.DNSTT, ServerToolCatalog.MASTERDNS)
    val titles = listOf("Psiphon", "SOAR", "CottenDNS", "DNSTT", "MasterDNS")
    val icons = listOf(Icons.Default.Security, Icons.Default.Bolt, Icons.Default.Language, Icons.Default.Layers, Icons.Default.Dns)
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
        StudioHero(titles.take(4), icons.take(4))
        Text(server?.displayName ?: "Connect your first VPS", color = StudioText,
            fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(when {
            fresh && server?.canSignIn == true -> stringResource(R.string.ui_ssh_authenticated_inspection_fresh)
            facts != null -> stringResource(R.string.ui_inspection_expired_refresh_over_ssh)
            else -> stringResource(R.string.ui_authenticated_ssh_inspection_required)
        }, color = StudioMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (server == null) {
            MxAction(stringResource(R.string.ui_add_vps), onClick = { vm.openAdd() }, strong = true)
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
                MxAction(stringResource(R.string.ui_regionhop_psiphon_country_exits), { showRegionHop = true })
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
                MxAction(stringResource(R.string.ui_vps_settings), { vm.openDetail(server.id) })
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
                    MxCard(stringResource(R.string.ui_batch_installation), stringResource(R.string.ui_sequential_vps_changes_never_an), Mx.Cyan) {
                        Text(stringResource(R.string.ui_selected, batch.order.joinToString { id ->
                            ServerToolCatalog.byId(id)?.title ?: id
                        }), color = Mx.White, fontSize = 12.sp)
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
                            stringResource(R.string.ui_dns_delegation_and_end_to),
                            color = Mx.Muted, fontSize = 11.sp)
                    }
                }
                BoxWithConstraints {
                    if (maxWidth >= 600.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MxChecks(server, inspection, vm, Modifier.weight(1f))
                            MxCard(stringResource(R.string.ui_automation_flow), stringResource(R.string.ui_inspect_select_dns_review_install),
                                Mx.Cyan, Modifier.weight(1f)) {
                                MxFlowSteps(listOf("Inspect", "Select", "Cloudflare",
                                    "Review", "Install", "Verify"))
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MxChecks(server, inspection, vm)
                            MxCard(stringResource(R.string.ui_automation_flow), stringResource(R.string.ui_six_step_guided_deployment), Mx.Cyan) {
                                MxFlowSteps(listOf("Inspect", "Select", "Cloudflare",
                                    "Review", "Install", "Verify"))
                            }
                        }
                    }
                }
            }
            5 -> {
                MxCard(stringResource(R.string.ui_service_logs), stringResource(R.string.ui_selected_vps_read_only_ssh), StudioBlue) {
                    val loggable = server.tools.filter { SmartManagerState.hasLogs(it.id) }
                    loggable.forEach { tool ->
                        MxAction(ServerToolCatalog.byId(tool.id)?.title ?: tool.id, { vm.logs(server.id, tool.id) },
                            enabled = state.busy.isBlank())
                    }
                    if (loggable.isEmpty()) Text(stringResource(R.string.ui_no_managed_services_yet), color = StudioMuted)
                    val operations by produceState(emptyList<com.example.panels.servers.SmartOperation>(),
                        server.id, state.busy, state.bundle, state.psiphonInstall, state.wizard?.stage) {
                        value = withContext(Dispatchers.IO) { vm.operationsFor(server.id) }
                    }
                    operations.forEach { operation ->
                        Text("${operation.toolId} · ${operation.status.name.lowercase()}", color = StudioMuted, fontSize = 11.sp)
                        // An interrupted install blocks every later install on this VPS until reconciled.
                        if (operation.status == com.example.panels.servers.SmartOperation.Status.UNCERTAIN) {
                            Text(stringResource(R.string.ui_interrupted_remote_effects_unknown_reins),
                                color = Mx.Red, fontSize = 11.sp)
                            MxAction(stringResource(R.string.ui_reinspect_acknowledge), {
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
            server.id in state.checking -> stringResource(R.string.ui_refreshing_ssh_inspection)
            state.busy.isNotBlank() -> state.busy
            server.checkedAt > 0L -> stringResource(R.string.ui_ssh_inspection_completed_ago, if (seconds < 60) "${seconds}s" else "${seconds / 60}m")
            else -> stringResource(R.string.ui_awaiting_first_ssh_inspection)
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
            title = { Text(stringResource(R.string.ui_smart_installation_review)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_selected, chosenCandidates.joinToString { it.title }.ifBlank { "None" }))
                    if (chosenCandidates.size > 1) {
                        Text(stringResource(R.string.ui_supported_batch_3x_ui_exactly))
                        OutlinedTextField(value = batchDomain, onValueChange = { batchDomain = it },
                            singleLine = true, label = { Text(stringResource(R.string.ui_your_dns_domain)) },
                            placeholder = { Text("example.com") })
                        OutlinedTextField(value = batchLabel, onValueChange = { batchLabel = it },
                            singleLine = true, label = { Text(stringResource(R.string.ui_tunnel_label)) },
                            placeholder = { Text("t") })
                        Text(stringResource(R.string.ui_no_dns_records_will_be))
                    }
                    blockers.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                    batchPlan?.blockers?.filterNot { it in blockers }?.forEach {
                        Text("• $it", color = MaterialTheme.colorScheme.error)
                    }
                    if (canSingle) Text(stringResource(R.string.ui_open_the_individual_approval_wizard))
                    if (canBatch) Text(stringResource(R.string.ui_one_approval_will_execute_both))
                }
            },
            confirmButton = {
                TextButton(enabled = canSingle || canBatch, onClick = {
                    if (canBatch) vm.installApprovedBundle(requireNotNull(server).id,
                        selectedIds, batchDomain, batchLabel)
                    else if (canSingle) vm.openWizard(chosenCandidates.single().id, server?.id)
                    showPlan = false
                }) { Text(if (canBatch) stringResource(R.string.ui_approve_install_both) else stringResource(R.string.ui_open_verified_installer)) }
            },
            dismissButton = { TextButton(onClick = { showPlan = false }) { Text(stringResource(R.string.ui_close)) } }
        )
    }
}

@Composable
private fun MxInstallSelector(
    candidates: List<SmartManagerPlanner.Candidate>, chosen: Set<String>,
    onChange: (Set<String>) -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier
) {
    MxCard(stringResource(R.string.ui_one_click_multi_install), stringResource(R.string.ui_select_panels_and_engines_on),
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
            MxAction(stringResource(R.string.ui_install_selected, chosen.size), onInstall,
                Modifier.weight(1.4f), strong = true, enabled = chosen.isNotEmpty())
            MxAction(stringResource(R.string.ui_preview_plan), onInstall, Modifier.weight(1f))
        }
        Text(stringResource(R.string.ui_safety_gate_unsupported_bundles_cannot),
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
    MxCard(stringResource(R.string.ui_cloudflare_integration), stringResource(R.string.ui_create_a_scoped_token_verify),
        Mx.Gold, modifier) {
        MxAction(stringResource(R.string.ui_create_cloudflare_api_token), strong = true, onClick = {
            val uri = TokenTemplate.url(TokenTemplate.SMART_DOMAINS, "Maximus Smart Manager")
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
                .onFailure { status = "Browser unavailable" }
        }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = token, onValueChange = { if (it.length <= 100) token = it.trim() },
            label = { Text(stringResource(R.string.ui_cloudflare_api_token_2), fontSize = 11.sp) },
            placeholder = { Text(stringResource(R.string.ui_paste_your_scoped_token), fontSize = 11.sp) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Mx.White, unfocusedTextColor = Mx.White,
                focusedBorderColor = Mx.Gold, unfocusedBorderColor = Mx.Line,
                focusedLabelColor = Mx.Gold, unfocusedLabelColor = Mx.Muted))
        Text(stringResource(R.string.ui_zone_read_dns_edit_only),
            color = Mx.Muted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MxAction(if (working) stringResource(R.string.ui_verifying_2) else stringResource(R.string.ui_verify_save), onClick = {
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
            MxAction(stringResource(R.string.ui_discover_zones), onClick = {
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
            Text(stringResource(R.string.ui_select_domain_zone), color = Mx.Gold, fontSize = 11.sp)
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
            MxAction(stringResource(R.string.ui_smart_domains_2), onSmartDomains, Modifier.weight(1f))
            MxAction(stringResource(R.string.ui_cloudflare_dns), onCloudflare, Modifier.weight(1f))
        }
        Text(stringResource(R.string.ui_create_a_token_on_cloudflare),
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
    MxCard(stringResource(R.string.ui_pre_flight_checks), stringResource(R.string.ui_validated_against_the_selected_vps), Mx.Cyan, modifier) {
        MxInfoRow(stringResource(R.string.ui_ssh_fingerprint),
            if (server?.hostKeySha256.isNullOrBlank()) "Pending" else "Pinned",
            if (server?.hostKeySha256.isNullOrBlank()) Mx.Orange else Mx.Green)
        MxInfoRow(stringResource(R.string.ui_port_conflicts), if (facts?.socketInspectionAvailable == true && fresh) "Scanned" else "Needs scan",
            if (facts?.socketInspectionAvailable == true && fresh) Mx.Green else Mx.Orange)
        // Public listeners only: the local systemd-resolved stub (127.0.0.53) is not a conflict.
        val port53 = SmartManagerState.publicPort53Occupied(facts)
        MxInfoRow(stringResource(R.string.ui_dns_safety), when (port53) {
            null -> "Pending"
            true -> "Port 53: occupied"
            false -> "Port 53: available"
        }, if (port53 == false) Mx.Green else Mx.Orange)
        MxInfoRow(stringResource(R.string.ui_firewall), facts?.firewall ?: "Not inspected", Mx.Cyan)
        MxInfoRow(stringResource(R.string.ui_resource_fit), if (facts == null) "Pending" else
            if (facts.blocker() == null) "Preliminary" else "Blocked",
            if (facts?.blocker() == null && facts != null) Mx.Green else Mx.Orange)
        MxInfoRow(stringResource(R.string.ui_rollback_plan), "Per-engine wizard", Mx.Cyan)
        if (server != null) MxAction(stringResource(R.string.ui_run_smart_inspection), { vm.refresh(server.id) },
            Modifier.fillMaxWidth(), strong = true)
        inspection?.limitations?.take(2)?.forEach {
            Text("• $it", color = Mx.Muted, fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}
