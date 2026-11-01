package com.example.ui.panels

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
import androidx.compose.material.icons.filled.Info
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
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.SmartManagerPlanner
import com.example.panels.servers.SmartNodeStore
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
    val checking = server?.id in state.checking
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
                        Text("MAXIMUS  /  INFRASTRUCTURE", color = Sm.Gold, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        Text("Smart Manager", color = Sm.White, fontSize = 23.sp,
                            fontWeight = FontWeight.ExtraBold)
                        Text("Control your servers from one cockpit", color = Sm.Muted, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SmPill(if (fresh) "SSH INSPECTED" else "INSPECTION REQUIRED",
                        if (fresh) Sm.Green else Sm.Orange)
                    SmPill("${state.servers.size} VPS", Sm.Cyan)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { page = 3 }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Sm.Gold,
                            contentColor = Sm.Night), shape = RoundedCornerShape(11.dp)) {
                        Text("Review install plan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = {
                        if (server == null) vm.openAdd() else vm.refresh(server.id)
                    }, enabled = !checking, shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, Sm.Border)) {
                        Icon(Icons.Default.Refresh, null, tint = Sm.Cyan, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scan", color = Sm.White, fontSize = 12.sp)
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
            SmPanel("CONNECT YOUR FIRST VPS", Icons.Default.Storage) {
                Text("Add an Ubuntu server with a verified SSH host key. No installation runs during inspection.",
                    color = Sm.Muted, fontSize = 13.sp)
                SmAction("Add VPS", onClick = { vm.openAdd() })
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
                    SmPanel("SERVER HEALTH", Icons.Default.Storage, "Last authenticated SSH survey") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.displayName, color = Sm.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(server.host, color = Sm.Cyan, fontSize = 12.sp)
                            }
                            SmPill(if (fresh) "INSPECTED" else "STALE / UNKNOWN",
                                if (fresh) Sm.Green else Sm.Orange)
                        }
                        Text(if (facts == null) "Run Scan to read VPS health"
                             else "${facts.osName.ifBlank { "Unknown Linux" }} · ${facts.cpus} CPU",
                            color = Sm.Muted, fontSize = 12.sp, maxLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmMetric("RAM FREE", if (facts != null && facts.ramMb > 0)
                                "${facts.ramAvailableMb} MB" else "—", Sm.Cyan, Modifier.weight(1f))
                            SmMetric("DISK FREE", if (facts != null && facts.diskMb > 0)
                                "${facts.diskFreeMb} MB" else "—", Sm.Gold, Modifier.weight(1f))
                            SmMetric("SERVICES", server.tools.size.toString(), Sm.Green, Modifier.weight(1f))
                        }
                        if (facts != null && facts.ramMb > 0) {
                            SmMeter("Memory usage", (1f - facts.ramAvailableMb.toFloat() / facts.ramMb)
                                .coerceIn(0f, 1f), Sm.Cyan)
                        }
                        Text("Port inventory  ·  " +
                            if (facts?.socketInspectionAvailable == true)
                                "TCP ${facts.tcpPorts.size}  /  UDP ${facts.udpPorts.size}"
                            else "Unavailable — install admission blocked",
                            color = Sm.Muted, fontSize = 12.sp)
                    }
                    SmPanel("ENGINE READINESS", Icons.Default.Bolt, "Eligibility is not a connection test") {
                        inspection.candidates.forEach { candidate ->
                            SmEngineLine(candidate.title, candidate.status)
                        }
                        SmAction("View installation options", onClick = { page = 3 }, quiet = true)
                    }
                }
                1 -> SmPanel("SERVER CONTROL", Icons.Default.Storage, "Pinned SSH credentials") {
                    SmDatum("Server", "${server.host}:${server.sshPort}")
                    SmDatum("Username", server.username)
                    SmDatum("Host key", if (server.hostKeySha256.isNotBlank()) "Pinned" else "Not confirmed")
                    SmDatum("Interface IPv4",
                        server.facts?.interfaceIpv4?.joinToString()?.ifBlank { "Unavailable" } ?: "Not inspected")
                    SmAction("Manage VPS", onClick = { vm.openDetail(server.id) })
                    SmAction("Add another VPS", onClick = { vm.openAdd() }, quiet = true)
                }
                2 -> SmPanel("CLOUDFLARE CONTROL", Icons.Default.Cloud, "Scoped DNS permissions") {
                    Text("Zone Read + DNS Edit only on the zones you approve. Every change remains reviewable.",
                        color = Sm.Muted, fontSize = 13.sp)
                    SmAction("Create scoped API token", onClick = {
                        val url = TokenTemplate.url(TokenTemplate.SMART_DOMAINS, "Maximus Smart Manager")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show() }
                    })
                    SmAction("Cloudflare DNS", onClick = onOpenCloudflare, quiet = true)
                    SmAction("Smart Domains", onClick = onOpenSmartDomains, quiet = true)
                    Text("Non-HTTP tunnels use DNS-only. No DNS write occurs on this screen.",
                        color = Sm.Muted, fontSize = 11.sp)
                }
                3 -> SmPanel("INSTALLATION PLANNER", Icons.Default.Dns, "Read-only checks · explicit approval") {
                    inspection.candidates.forEach { candidate ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            SmEngineLine(candidate.title, candidate.status)
                            Text(candidate.reason, color = Sm.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                            if (candidate.status == SmartManagerPlanner.Status.READY_FOR_WIZARD) {
                                SmAction("Review ${candidate.title}", onClick = {
                                    vm.openWizard(candidate.id, server.id)
                                }, quiet = true)
                            }
                        }
                    }
                    Text("One-click multi-engine deployment is disabled until rollback and protocol compatibility are verified.",
                        color = Sm.Orange, fontSize = 11.sp)
                }
                4 -> SmPanel("MANAGED NODES", Icons.Default.CheckCircle, "Phone traffic verification required") {
                    val managed = vm.managedNodesFor(server.id)
                    Text("${managed.size} managed provenance records", color = Sm.Cyan,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (managed.isEmpty()) Text("No managed node was generated on this VPS yet.",
                        color = Sm.Muted, fontSize = 12.sp)
                    managed.forEach { node ->
                        SmDatum(node.serviceId, node.state.name)
                    }
                    Text("A stored profile is not a verified tunnel. Manual imported nodes remain unchanged.",
                        color = Sm.Muted, fontSize = 12.sp)
                    SmAction("Inspect and test profiles", onClick = { vm.openDetail(server.id) })
                }
                else -> SmPanel("LIVE SERVICE INSPECTION", Icons.Default.Security,
                    "Read-only; no automatic repair") {
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
                            Text("Remote effects unknown: inspect before another installation.",
                                color = Sm.Red, fontSize = 12.sp)
                            SmAction("Reinspect interrupted operation", quiet = true, onClick = {
                                vm.reconcileUncertainOperation(server.id, op.id)
                            })
                        }
                    }
                    SmAction("Refresh service inspection", onClick = { vm.refresh(server.id) })
                    SmAction("Full VPS diagnostics", onClick = { vm.openDetail(server.id) }, quiet = true)
                }
            }
        }
        if (state.error.isNotBlank()) {
            Text(state.error, color = Sm.Red, fontSize = 12.sp)
        }
        Text("MAXIMUS VPN  ·  Managed infrastructure  ·  Verified facts only",
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
            SmartManagerPlanner.Status.INSTALLED -> "Detected"
            SmartManagerPlanner.Status.READY_FOR_WIZARD -> "Review"
            SmartManagerPlanner.Status.UNVERIFIED -> "Unverified"
            SmartManagerPlanner.Status.NEEDS_INSPECTION -> "Scan required"
            SmartManagerPlanner.Status.BLOCKED -> "Blocked"
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
