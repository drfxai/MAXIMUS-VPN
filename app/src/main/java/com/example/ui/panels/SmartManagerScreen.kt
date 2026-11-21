package com.example.ui.panels

import androidx.compose.ui.res.stringResource
import com.example.R
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.cloudflare.TokenTemplate
import com.example.panels.servers.SmartManagerPlanner
import com.example.panels.servers.SmartOperation
import com.example.panels.servers.SmartServerHealth
import com.example.panels.servers.ToolScripts
import com.example.ui.panels.servers.ServersState
import com.example.ui.panels.servers.ServersViewModel

/** No fabricated telemetry. All dashboard figures come from the last pinned-SSH survey. */
private object Sm {
    val Night = Color(0xFF070B14)
    val Navy = Color(0xFF0D1B30)
    val Raised = Color(0xFF10243E)
    val Border = Color(0xFF27415F)
    val Gold = Color(0xFFF2BF60)
    val Blue = Color(0xFF2777FF)
    val Cyan = Color(0xFF47C8F6)
    val Green = Color(0xFF41DEA9)
    val Orange = Color(0xFFFFAB63)
    val Red = Color(0xFFFF7385)
    val White = Color(0xFFF4F7FF)
    val Muted = Color(0xFF97A9C0)
}
private val smPages = listOf("Overview", "Servers", "Cloudflare", "Deploy", "Nodes", "Monitor")
private val smIcons = listOf(Icons.Default.Bolt, Icons.Default.Storage, Icons.Default.Cloud,
    Icons.Default.Dns, Icons.Default.CheckCircle, Icons.Default.Security)

@Composable
internal fun SmartManagerScreen(
    vm: ServersViewModel,
    state: ServersState,
    onOpenCloudflare: () -> Unit,
    onOpenSmartDomains: () -> Unit
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val server = state.servers.firstOrNull { it.id == selectedId } ?: state.servers.firstOrNull()
    val inspection = server?.let { SmartManagerPlanner.inspect(it) }
    val context = LocalContext.current
    val checking = server?.id?.let { it in state.checking } == true
    val fresh = server != null && server.facts != null && server.checkedAt > 0 &&
        System.currentTimeMillis() >= server.checkedAt &&
        System.currentTimeMillis() - server.checkedAt <= 60_000L

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Sm.Night)
        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // High-contrast hero. The CTA opens the review plan, never a remote install.
        Surface(shape = RoundedCornerShape(22.dp), color = Color.Transparent,
            border = BorderStroke(1.dp, Sm.Gold.copy(alpha = 0.60f))) {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(
                listOf(Color(0xFF142C4A), Color(0xFF0B1527), Color(0xFF291F1D))))
                .padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    SmIcon(Icons.Default.Security, Sm.Gold)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_maximus_infrastructure), color = Sm.Gold, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        Text("Smart Manager", color = Sm.White, fontSize = 23.sp,
                            fontWeight = FontWeight.ExtraBold)
                        Text(stringResource(R.string.ui_control_your_servers_from_one), color = Sm.Muted, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SmPill(if (fresh) stringResource(R.string.ui_ssh_inspected) else stringResource(R.string.ui_inspection_required),
                        if (fresh) Sm.Green else Sm.Orange)
                    SmPill("${state.servers.size} VPS", Sm.Cyan)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { page = 3 }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Sm.Gold,
                            contentColor = Sm.Night), shape = RoundedCornerShape(11.dp)) {
                        Text(stringResource(R.string.ui_review_install_plan), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = {
                        if (server == null) vm.openAdd() else vm.refresh(server.id)
                    }, enabled = !checking, shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, Sm.Border)) {
                        Icon(Icons.Default.Refresh, null, tint = Sm.Cyan, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ui_scan), color = Sm.White, fontSize = 12.sp)
                    }
                }
            }
        }

        // Six destinations fit in two short rows; no horizontal menu hiding Monitoring.
        smPages.chunked(3).forEachIndexed { group, items ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { index, name ->
                    val target = group * 3 + index
                    val active = page == target
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                        .background(if (active) Sm.Blue.copy(alpha = 0.30f) else Sm.Navy)
                        .clickable { page = target }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(smIcons[target], null,
                            tint = if (active) Sm.Cyan else Sm.Muted, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(name, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) Sm.White else Sm.Muted, maxLines = 1)
                    }
                }
            }
        }

        if (state.servers.isEmpty()) {
            SmPanel(stringResource(R.string.ui_connect_your_first_vps), Icons.Default.Storage) {
                Text(stringResource(R.string.ui_add_an_ubuntu_server_with),
                    color = Sm.Muted, fontSize = 13.sp)
                SmAction(stringResource(R.string.ui_add_vps), onClick = { vm.openAdd() })
            }
            return@Column
        }

        // A compact VPS selector; no duplicate long infrastructure introduction.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            state.servers.forEach { candidate ->
                val active = server?.id == candidate.id
                Surface(
                    modifier = Modifier.clickable { selectedId = candidate.id },
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) Sm.Raised else Sm.Navy,
                    border = BorderStroke(1.dp, if (active) Sm.Gold else Sm.Border)
                ) {
                    Text(candidate.displayName, color = if (active) Sm.White else Sm.Muted,
                        fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }

        if (server != null && inspection != null) {
            when (page) {
                0 -> {
                    val facts = server.facts
                    SmPanel(stringResource(R.string.ui_server_health), Icons.Default.Storage, stringResource(R.string.ui_last_authenticated_ssh_survey)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.displayName, color = Sm.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(server.host, color = Sm.Cyan, fontSize = 12.sp)
                            }
                            SmPill(if (fresh) stringResource(R.string.ui_inspected) else stringResource(R.string.ui_stale_unknown),
                                if (fresh) Sm.Green else Sm.Orange)
                        }
                        Text(if (facts == null) stringResource(R.string.ui_run_scan_to_read_vps)
                             else stringResource(R.string.ui_cpu, facts.osName.ifBlank { "Unknown Linux" }, facts.cpus),
                            color = Sm.Muted, fontSize = 12.sp, maxLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmMetric(stringResource(R.string.ui_ram_free), if (facts != null && facts.ramMb > 0)
                                "${facts.ramAvailableMb} MB" else "—", Sm.Cyan, Modifier.weight(1f))
                            SmMetric(stringResource(R.string.ui_disk_free), if (facts != null && facts.diskMb > 0)
                                "${facts.diskFreeMb} MB" else "—", Sm.Gold, Modifier.weight(1f))
                            SmMetric(stringResource(R.string.ui_services), server.tools.size.toString(), Sm.Green, Modifier.weight(1f))
                        }
                        if (facts != null && facts.ramMb > 0) {
                            SmMeter(stringResource(R.string.ui_memory_usage), (1f - facts.ramAvailableMb.toFloat() / facts.ramMb)
                                .coerceIn(0f, 1f), Sm.Cyan)
                        }
                        Text("Port inventory  ·  " +
                            if (facts?.socketInspectionAvailable == true)
                                "TCP ${facts.tcpPorts.size}  /  UDP ${facts.udpPorts.size}"
                            else "Unavailable — install admission blocked",
                            color = Sm.Muted, fontSize = 12.sp)
                    }
                    SmPanel(stringResource(R.string.ui_engine_readiness), Icons.Default.Bolt, stringResource(R.string.ui_eligibility_is_not_a_connection)) {
                        inspection.candidates.forEach { candidate ->
                            SmEngineLine(candidate.title, candidate.status)
                        }
                        SmAction(stringResource(R.string.ui_view_installation_options), onClick = { page = 3 }, quiet = true)
                    }
                }
                1 -> SmPanel(stringResource(R.string.ui_server_control), Icons.Default.Storage, stringResource(R.string.ui_pinned_ssh_credentials)) {
                    SmDatum(stringResource(R.string.ui_server), "${server.host}:${server.sshPort}")
                    SmDatum(stringResource(R.string.ui_username), server.username)
                    SmDatum(stringResource(R.string.ui_host_key), if (server.hostKeySha256.isNotBlank()) "Pinned" else "Not confirmed")
                    SmDatum(stringResource(R.string.ui_interface_ipv4),
                        server.facts?.interfaceIpv4?.joinToString()?.ifBlank { "Unavailable" } ?: "Not inspected")
                    SmAction(stringResource(R.string.ui_manage_vps), onClick = { vm.openDetail(server.id) })
                    SmAction(stringResource(R.string.ui_add_another_vps), onClick = { vm.openAdd() }, quiet = true)
                }
                2 -> SmPanel(stringResource(R.string.ui_cloudflare_control), Icons.Default.Cloud, stringResource(R.string.ui_scoped_dns_permissions)) {
                    Text(stringResource(R.string.ui_zone_read_dns_edit_only_2),
                        color = Sm.Muted, fontSize = 13.sp)
                    SmAction(stringResource(R.string.ui_create_scoped_api_token), onClick = {
                        val url = TokenTemplate.url(TokenTemplate.SMART_DOMAINS, "Maximus Smart Manager")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { Toast.makeText(context, context.getString(R.string.ui_no_browser_available), Toast.LENGTH_SHORT).show() }
                    })
                    SmAction(stringResource(R.string.ui_cloudflare_dns), onClick = onOpenCloudflare, quiet = true)
                    SmAction(stringResource(R.string.ui_smart_domains_2), onClick = onOpenSmartDomains, quiet = true)
                    Text(stringResource(R.string.ui_non_http_tunnels_use_dns),
                        color = Sm.Muted, fontSize = 11.sp)
                }
                3 -> SmPanel(stringResource(R.string.ui_installation_planner), Icons.Default.Dns, stringResource(R.string.ui_read_only_checks_explicit_approval)) {
                    inspection.candidates.forEach { candidate ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            SmEngineLine(candidate.title, candidate.status)
                            Text(candidate.reason, color = Sm.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                            if (candidate.status == SmartManagerPlanner.Status.READY_FOR_WIZARD) {
                                SmAction(stringResource(R.string.ui_review, candidate.title), onClick = {
                                    vm.openWizard(candidate.id, server.id)
                                }, quiet = true)
                            }
                        }
                    }
                    Text(stringResource(R.string.ui_one_click_multi_engine_deployment),
                        color = Sm.Orange, fontSize = 11.sp)
                }
                4 -> SmPanel(stringResource(R.string.ui_managed_nodes), Icons.Default.CheckCircle, stringResource(R.string.ui_phone_traffic_verification_required)) {
                    val managed = vm.managedNodesFor(server.id)
                    Text(stringResource(R.string.ui_managed_provenance_records, managed.size), color = Sm.Cyan,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (managed.isEmpty()) Text(stringResource(R.string.ui_no_managed_node_was_generated),
                        color = Sm.Muted, fontSize = 12.sp)
                    managed.forEach { node ->
                        SmDatum(node.serviceId, node.state.name)
                    }
                    Text(stringResource(R.string.ui_a_stored_profile_is_not),
                        color = Sm.Muted, fontSize = 12.sp)
                    SmAction(stringResource(R.string.ui_inspect_and_test_profiles), onClick = { vm.openDetail(server.id) })
                }
                else -> SmPanel(stringResource(R.string.ui_live_service_inspection), Icons.Default.Security,
                    stringResource(R.string.ui_read_only_no_automatic_repair)) {
                    val health = SmartServerHealth.evaluate(server)
                    SmPill(health.level.name.replace('_', ' '),
                        if (health.level == SmartServerHealth.Level.SERVICES_ACTIVE_UNVERIFIED) Sm.Cyan
                        else Sm.Orange)
                    health.concerns.forEach { concern ->
                        Text(concern, color = Sm.Muted, fontSize = 12.sp)
                    }
                    server.tools.forEach { tool ->
                        val units = ToolScripts.UNITS[tool.id]
                        SmDatum(tool.id, when {
                            units.isNullOrEmpty() || server.facts == null -> "Unknown"
                            units.all { server.facts?.active(it) == true } -> "Active service · unverified tunnel"
                            else -> "Inactive / failed"
                        })
                    }
                    vm.operationsFor(server.id).forEach { op ->
                        SmDatum(op.toolId, op.status.name)
                        if (op.status == SmartOperation.Status.UNCERTAIN) {
                            Text(stringResource(R.string.ui_remote_effects_unknown_inspect_before),
                                color = Sm.Red, fontSize = 12.sp)
                            SmAction(stringResource(R.string.ui_reinspect_interrupted_operation), quiet = true, onClick = {
                                vm.reconcileUncertainOperation(server.id, op.id)
                            })
                        }
                    }
                    SmAction(stringResource(R.string.ui_refresh_service_inspection), onClick = { vm.refresh(server.id) })
                    SmAction(stringResource(R.string.ui_full_vps_diagnostics), onClick = { vm.openDetail(server.id) }, quiet = true)
                }
            }
        }
        if (state.error.isNotBlank()) {
            Text(state.error, color = Sm.Red, fontSize = 12.sp)
        }
        Text(stringResource(R.string.ui_maximus_vpn_managed_infrastructure_verif),
            color = Sm.Muted.copy(alpha = 0.65f), fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 4.dp))
    }
}

@Composable
private fun SmIcon(icon: ImageVector, tint: Color) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
        .background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun SmPanel(title: String, icon: ImageVector, subtitle: String = "",
    content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Sm.Navy,
        border = BorderStroke(1.dp, Sm.Border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmIcon(icon, Sm.Gold)
                Column {
                    Text(title, color = Sm.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp)
                    if (subtitle.isNotBlank()) Text(subtitle, color = Sm.Muted, fontSize = 11.sp)
                }
            }
            content()
        }
    }
}

@Composable
private fun SmPill(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
    }
}

@Composable
private fun SmMetric(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Sm.Raised)
        .padding(horizontal = 8.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = Sm.Muted, fontSize = 9.sp, maxLines = 1)
        Text(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmMeter(label: String, progress: Float, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Sm.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("${(progress * 100).toInt()}%", color = Sm.White, fontSize = 11.sp)
    }
    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(8.dp))
        .background(Sm.Border)) {
        Box(Modifier.fillMaxWidth(progress).height(5.dp)
            .clip(RoundedCornerShape(8.dp)).background(tint))
    }
}

@Composable
private fun SmEngineLine(name: String, status: SmartManagerPlanner.Status) {
    val tint = when (status) {
        SmartManagerPlanner.Status.INSTALLED -> Sm.Cyan
        SmartManagerPlanner.Status.READY_FOR_WIZARD -> Sm.Green
        SmartManagerPlanner.Status.UNVERIFIED -> Sm.Orange
        SmartManagerPlanner.Status.NEEDS_INSPECTION -> Sm.Gold
        SmartManagerPlanner.Status.BLOCKED -> Sm.Red
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(8.dp)).background(tint))
        Spacer(Modifier.width(9.dp))
        Text(name, color = Sm.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(when (status) {
            SmartManagerPlanner.Status.INSTALLED -> stringResource(R.string.ui_detected)
            SmartManagerPlanner.Status.READY_FOR_WIZARD -> stringResource(R.string.ui_review_2)
            SmartManagerPlanner.Status.UNVERIFIED -> stringResource(R.string.ui_unverified)
            SmartManagerPlanner.Status.NEEDS_INSPECTION -> stringResource(R.string.ui_scan_required)
            SmartManagerPlanner.Status.BLOCKED -> stringResource(R.string.ui_blocked)
        }, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmDatum(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Sm.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = Sm.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.3f))
    }
}

@Composable
private fun SmAction(label: String, onClick: () -> Unit, quiet: Boolean = false) {
    if (quiet) {
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(11.dp),
            border = BorderStroke(1.dp, Sm.Border), modifier = Modifier.fillMaxWidth()) {
            Text(label, color = Sm.Cyan, fontSize = 12.sp)
        }
    } else {
        Button(onClick = onClick, shape = RoundedCornerShape(11.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Sm.Blue),
            modifier = Modifier.fillMaxWidth()) {
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
