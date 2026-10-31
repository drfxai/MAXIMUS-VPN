package com.example.ui.panels

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.panels.cloudflare.TokenTemplate
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.SmartOperation
import com.example.panels.servers.SmartServerHealth
import com.example.panels.servers.SmartManagerPlanner
import com.example.panels.servers.ToolScripts
import com.example.ui.panels.servers.ServersState
import com.example.ui.panels.servers.ServersViewModel

private val smartGold = Color(0xFFD9B96B)
private val smartOrange = Color(0xFFF48120)
private val smartBorder = Color(0xFF274F77)
private val pages = listOf("Dashboard", "Server", "Cloudflare", "Installation", "Nodes", "Monitoring")

/**
 * A read-only Smart Manager entry point on top of the existing authenticated SSH, installation
 * wizard and encrypted stores. Never advertises deployments, nodes or connectivity as verified
 * merely because a server returned systemd status.
 */
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
    val inspection = remember(server) { server?.let { SmartManagerPlanner.inspect(it) } }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("MAXIMUS SMART MANAGER", color = smartGold,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Verified facts only · installations require approval",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.forEachIndexed { index, name ->
                FilterChip(selected = page == index, onClick = { page = index },
                    label = { Text(name) })
            }
        }
        if (state.servers.isEmpty()) {
            SmartCard("No VPS connected") {
                Text("Add an Ubuntu VPS using pinned SSH. Smart Manager never installs anything before inspection.")
                Button(onClick = { vm.openAdd() }) { Text("Connect VPS") }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.servers.forEach { item ->
                FilterChip(selected = server?.id == item.id, onClick = { selectedId = item.id },
                    label = { Text(item.displayName) })
            }
        }

        if (server != null && inspection != null) when (page) {
            0 -> {
                SmartCard("Infrastructure Dashboard") {
                    val facts = server.facts
                    Text("Server: ${server.displayName} · ${server.host}")
                    Text("Inspection: ${if (facts == null) "Not inspected" else if (inspection.candidates.any { it.status == SmartManagerPlanner.Status.NEEDS_INSPECTION }) "Refresh required" else "Read-only inspection available"}")
                    Text("OS: ${facts?.osName?.ifBlank { "Unknown" } ?: "Unknown"} · CPU: ${facts?.cpus ?: 0}")
                    Text("RAM: ${facts?.ramAvailableMb ?: 0}/${facts?.ramMb ?: 0} MB available")
                    Text("Disk free: ${facts?.diskFreeMb ?: 0} MB · Services managed: ${server.tools.size}")
                    Text("Socket inventory: ${if (facts?.socketInspectionAvailable == true) "Collected" else "Unavailable — blocked"}")
                    Text("Ports observed: TCP ${facts?.tcpPorts?.size ?: 0} · UDP ${facts?.udpPorts?.size ?: 0}")
                    Text("Docker detected: ${facts?.dockerAvailable == true} · Psiphon unit detected: ${facts?.psiphonInstalled == true}")
                    Text("Interface IPv4: ${facts?.interfaceIpv4?.joinToString()?.ifBlank { "Unknown" } ?: "Unknown"}")
                    OutlinedButton(onClick = { vm.refresh(server.id) },
                        enabled = server.id !in state.checking) {
                        Text(if (server.id in state.checking) "Inspecting…" else "Refresh SSH inspection")
                    }
                }
                SmartCard("Engine admission") {
                    inspection.candidates.forEach { candidate ->
                        Text("${candidate.title} · ${candidate.status.name.replace('_', ' ')}",
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            1 -> SmartCard("Server Connection") {
                Text("Host: ${server.host}:${server.sshPort}")
                Text("User: ${server.username} · SSH identity: ${if (server.hostKeySha256.isNotBlank()) "Pinned" else "Unconfirmed"}")
                Text("SSH authentication and encrypted credentials are handled by the existing server manager.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.openDetail(server.id) }) { Text("Manage VPS") }
                    Button(onClick = { vm.openAdd() }) { Text("Add VPS") }
                }
            }
            2 -> SmartCard("Cloudflare Integration") {
                Text("Create a scoped token with Zone Read and DNS Edit; review the scope on Cloudflare.")
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = smartOrange),
                    onClick = {
                        val url = TokenTemplate.url(TokenTemplate.SMART_DOMAINS, "Maximus Smart Manager")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show() }
                    }
                ) { Text("Create Cloudflare API Token") }
                OutlinedButton(onClick = onOpenCloudflare) { Text("Cloudflare account & DNS") }
                OutlinedButton(onClick = onOpenSmartDomains) { Text("Smart Domains") }
                Text("DNS edits and proxy settings remain in the existing reviewed workflows.")
            }
            3 -> SmartCard("Intelligent Installation · read-only plan") {
                inspection.candidates.forEach { candidate ->
                    Text(candidate.title, fontWeight = FontWeight.Bold)
                    Text(candidate.reason, style = MaterialTheme.typography.bodySmall)
                    if (candidate.status == SmartManagerPlanner.Status.READY_FOR_WIZARD) {
                        OutlinedButton(onClick = { vm.openWizard(candidate.id, server.id) }) {
                            Text("Review ${candidate.title} installation")
                        }
                    }
                }
                Text("There is no one-click bundle deployment until transaction recovery, port reservations and multi-engine compatibility are validated.")
            }
            4 -> SmartCard("Unified Nodes · provenance") {
                val managed = vm.managedNodesFor(server.id)
                Text("${managed.size} generated profiles have managed-provenance records.")
                if (managed.isEmpty()) Text("No managed profiles tracked yet. Existing manual nodes are preserved.")
                managed.forEach { node ->
                    Text("${node.serviceId}: ${node.state.name}")
                    if (node.state != com.example.panels.servers.ManagedNodeRecord.State.AVAILABLE)
                        Text("Not eligible as a verified managed connection until real phone traffic passes.",
                            style = MaterialTheme.typography.bodySmall)
                }
                Text("Service inventory is separate from verified Nodes. A stored profile alone is not proof of a usable tunnel.")
                OutlinedButton(onClick = { vm.openDetail(server.id) }) {
                    Text("Inspect generated profiles & test")
                }
            }
            else -> SmartCard("Smart Connect & Monitoring") {
                val health = SmartServerHealth.evaluate(server)
                Text("Infrastructure: ${health.level.name.replace('_', ' ')}", fontWeight = FontWeight.Bold)
                health.concerns.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Service status comes from the last authenticated systemd inspection. It is not an internet connectivity test.")
                server.tools.forEach { tool ->
                    val units = ToolScripts.UNITS[tool.id]
                    val status = when {
                        server.facts == null || units.isNullOrEmpty() -> "Unknown"
                        units.all { server.facts?.active(it) == true } -> "Active (systemd only)"
                        else -> "Inactive or failed"
                    }
                    Text("${tool.id}: $status")
                }
                Text("Approved install operation journal", fontWeight = FontWeight.SemiBold)
                vm.operationsFor(server.id).forEach { op ->
                    Text("${op.toolId}: ${op.status.name}")
                    if (op.status == SmartOperation.Status.UNCERTAIN) {
                        Text("Remote effects unknown. Reinspection is required before another install.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { vm.reconcileUncertainOperation(server.id, op.id) }) {
                            Text("Reinspect & acknowledge interrupted operation")
                        }
                    }
                }
                Text("Smart Connect eligibility requires actual current-network tunnel transfer and security checks.")
                OutlinedButton(onClick = { vm.refresh(server.id) }) {
                    Text("Refresh service status")
                }
                OutlinedButton(onClick = { vm.openDetail(server.id) }) {
                    Text("Service health & diagnostics")
                }
            }
        }
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SmartCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, smartBorder)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = smartGold,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
