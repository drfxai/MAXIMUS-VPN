
package com.example.ui.panels

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.servers.PrivatePsiphonServerScripts
import com.example.panels.servers.PrivatePsiphonArtifact
import com.example.panels.servers.INSPECTION_TTL_MS
import kotlinx.coroutines.delay
import com.example.panels.servers.ServerFacts
import com.example.ui.panels.servers.ServersState
import com.example.ui.panels.servers.ServersViewModel

/**
 * Reference-matched Psiphon deployment cockpit.
 * A reviewed fixed-SHA256 upstream amd64 psiphond binary can be installed on an explicitly
 * approved VPS. Installation and client registration do NOT prove real tunnel traffic.
 */
@Composable
internal fun PremiumPsiphonScreen(vm: ServersViewModel, state: ServersState, initialServerId: String? = null) {
    var selected by rememberSaveable(initialServerId) { mutableStateOf(initialServerId) }
    var entry by remember { mutableStateOf("") }
    var showSafety by remember { mutableStateOf(false) }
    var installIp by rememberSaveable { mutableStateOf("") }
    var installPort by rememberSaveable { mutableStateOf("9999") }
    val server = state.servers.firstOrNull { it.id == selected } ?: state.servers.firstOrNull()
    val facts = server?.facts
    LaunchedEffect(server?.id) {
        installIp = server?.host?.takeIf { PrivatePsiphonServerScripts.validIpv4(it) }.orEmpty()
        installPort = "9999"
    }
    val installing = state.psiphonInstall?.takeIf { it.serverId == server?.id }
    var inspectionNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            inspectionNow = System.currentTimeMillis()
        }
    }
    val fresh = server != null && facts != null &&
        inspectionNow - server.checkedAt in 0..INSPECTION_TTL_MS
    val node = server?.let { vm.managedNodesFor(it.id).firstOrNull {
        n -> n.serviceId == PrivatePsiphonServerScripts.TOOL_ID
    } }
    val test = server?.let { state.tests["${it.id}/${PrivatePsiphonServerScripts.TOOL_ID}"] }
    val verified = test?.first == true
    val steps = listOf("Connect VPS", "Verify psiphond", "Generate entry", "Register node", "Test tunnel")
    // These are the real prerequisites. A detected service alone is not installation approval.
    val readiness = listOf(
        server?.canSignIn == true, fresh, facts?.canAdmin == true,
        facts?.socketInspectionAvailable == true,
        node != null, verified
    ).count { it }.toFloat() / 6f

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Mx.Black).padding(8.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        MxHero("MAXIMUS VPN  /  PRIVATE SERVER", "Private Psiphon Server",
            "Guided deployment • encrypted server entry • real tunnel verification")

        MxCard("Deployment Flow", "Secure • reviewed • VPS-powered", Mx.Gold) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    steps.take(3).forEachIndexed { i, label ->
                        MxPsStep(i + 1, label, Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    steps.drop(3).forEachIndexed { i, label ->
                        MxPsStep(i + 4, label, Modifier.weight(1f))
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        if (state.servers.isEmpty()) {
            MxCard("Add a VPS", "A personally controlled server is required", Mx.Gold) {
                MxAction("Connect VPS", { vm.openAdd() }, Modifier.fillMaxWidth(), strong = true)
            }
            return@Column
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.servers.forEach { item ->
                val active = item.id == server?.id
                Surface(
                    color = if (active) Mx.Card else Mx.Navy,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (active) Mx.Gold else Mx.Line),
                    modifier = Modifier.clickable { selected = item.id }
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, null, tint = Mx.Gold,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(item.displayName, color = Mx.White, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (server != null) {
            BoxWithConstraints {
                val wide = maxWidth >= 330.dp
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        PsDeployment(server, facts, fresh, readiness, vm,
                            { showSafety = true }, Modifier.weight(1.2f))
                        PsReadiness(server, facts, readiness, node != null, verified, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        PsDeployment(server, facts, fresh, readiness, vm, { showSafety = true })
                        PsReadiness(server, facts, readiness, node != null, verified)
                    }
                }
            }

            installing?.let { progress ->
                MxCard("Private Psiphon deployment", "Actual SSH operation • never marks tunnel verified", Mx.Cyan) {
                    MxInfoRow("Operation", progress.stage,
                        if (progress.error.isBlank()) Mx.Cyan else Mx.Orange)
                    MxInfoRow("Managed server", if (progress.serverInstalled) "Started" else "Not confirmed",
                        if (progress.serverInstalled) Mx.Green else Mx.Orange)
                    MxInfoRow("Client entry", if (progress.entryRegistered) "Encrypted and registered" else "Pending",
                        if (progress.entryRegistered) Mx.Green else Mx.Orange)
                    if (progress.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (progress.error.isNotBlank()) Text(progress.error +
                        " Inspect VPS facts and the operation journal before retrying.",
                        color = Mx.Orange, fontSize = 11.sp)
                    if (progress.entryRegistered) MxAction("Verify phone-to-VPS tunnel", {
                        vm.testPrivatePsiphon(progress.serverId)
                    }, Modifier.fillMaxWidth(), strong = true)
                }
            }
            BoxWithConstraints {
                val wide = maxWidth >= 330.dp
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        PsImport(entry, { entry = it }, node != null, {
                            vm.importPrivatePsiphonEntry(server.id, entry)
                            entry = ""
                        }, {
                            vm.testPrivatePsiphon(server.id)
                        }, test, Modifier.weight(1f))
                        MxCard("Cloudflare Helper", "Domain and DNS setup for your private node",
                            Mx.Gold, Modifier.weight(1f)) {
                            Text("DNS changes and API tokens are managed in Smart Domains.",
                                color = Mx.Muted, fontSize = 11.sp)
                            MxStatus("HTTPS proxy not assumed", Mx.Cyan)
                            MxAction("Open Smart Manager • Cloudflare", {
                                vm.openDetail(server.id)
                            }, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        PsImport(entry, { entry = it }, node != null, {
                            vm.importPrivatePsiphonEntry(server.id, entry)
                            entry = ""
                        }, { vm.testPrivatePsiphon(server.id) }, test)
                        MxCard("Cloudflare Helper", "Managed through Smart Manager", Mx.Gold) {
                            Text("Use the Cloudflare tab or Smart Domains for scoped DNS setup. No record is changed here.",
                                color = Mx.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            MxCard("Smart Checks", "Pre-deployment SSH, resource and tunnel checks", Mx.Gold) {
                MxInfoRow("SSH reachability",
                    if (fresh) "Inspected" else "Pending",
                    if (fresh) Mx.Green else Mx.Orange)
                MxInfoRow("SSH fingerprint",
                    if (server.hostKeySha256.isNotBlank()) "Pinned" else "Pending",
                    if (server.hostKeySha256.isNotBlank()) Mx.Green else Mx.Orange)
                MxInfoRow("Open ports", if (facts?.socketInspectionAvailable == true)
                    "TCP ${facts.tcpPorts.size} / UDP ${facts.udpPorts.size}" else "Not inspected", Mx.Cyan)
                MxInfoRow("Firewall", facts?.firewall ?: "Not inspected", Mx.Cyan)
                MxInfoRow("psiphond", when {
                    facts?.psiphonInstalled == true -> "Service detected • unverified"
                    facts?.psiphonDataRetained == true -> "Service absent • private keys retained"
                    else -> "Not detected"
                }, Mx.Orange)
                MxInfoRow("Upstream SHA-256 review", "Pinned official AMD64 binary", Mx.Green)
                MxInfoRow("Android-to-VPS test", if (verified) "Verified" else "Pending",
                    if (verified) Mx.Green else Mx.Orange)
                MxAction("Run Compatibility Check", { vm.refresh(server.id) }, Modifier.fillMaxWidth())
            }

            MxCard("Management", "Manage your VPS and securely imported private node", Mx.Gold) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MxAction("VPS", { vm.openDetail(server.id) }, Modifier.weight(1f))
                    MxAction("Scan", { vm.refresh(server.id) }, Modifier.weight(1f))
                    MxAction("Test tunnel", { vm.testPrivatePsiphon(server.id) },
                        Modifier.weight(1.2f), enabled = node != null)
                }
                Text("Installation uses a pinned official AMD64 binary and an isolated systemd user. Use VPS management to inspect services; node traffic still requires a real phone test.",
                    color = Mx.Muted, fontSize = 10.sp)
            }
        }
        if (state.busy.isNotBlank()) Text(state.busy, color = Mx.Cyan, fontSize = 11.sp)
        if (state.error.isNotBlank()) Text(state.error, color = Mx.Red, fontSize = 11.sp)
        Text("Server installation and encrypted bootstrap registration are not evidence of VPN traffic. Test real traffic after setup.",
            color = Mx.Muted, fontSize = 10.sp, modifier = Modifier.padding(5.dp))
    }

    if (showSafety) {
        val port = installPort.toIntOrNull()
        val allowed = server?.canSignIn == true && server.username == "root" &&
            fresh && facts?.let(PrivatePsiphonArtifact::supported) == true &&
            facts?.psiphonInstalled == false && facts.psiphonDataRetained == false &&
            PrivatePsiphonServerScripts.validIpv4(installIp) &&
            port != null && port in 1024..65535 && port !in setOf(22, 53) &&
            port !in facts.tcpPorts && port !in facts.udpPorts &&
            node == null && installing?.running != true
        AlertDialog(
            onDismissRequest = { showSafety = false },
            title = { Text("Approve private Psiphon VPS installation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Official psiphond for Linux AMD64. Fixed upstream commit, exact 37.8 MB size, verified SHA-256. No unsigned installer, no custom shell commands.")
                    Text("VPS: ${server?.displayName ?: "Not selected"} • root SSH required")
                    OutlinedTextField(value = installIp, onValueChange = { installIp = it.take(15) },
                        label = { Text("Public IPv4 advertised to clients") },
                        placeholder = { Text("Example: 8.8.8.8") }, singleLine = true)
                    OutlinedTextField(value = installPort, onValueChange = {
                        installPort = it.filter(Char::isDigit).take(5)
                    }, label = { Text("Dedicated TCP OSSH port") }, singleLine = true)
                    Text("Creates a dedicated systemd service and private keys on this VPS. Failed installs are rolled back where possible; interrupted outcomes require manual inspection. The public IP must be reachable from clients; cloud NAT setups may need provider-side port forwarding.")
                    Text("After installation, the private server entry is encrypted on this phone. Tunnel connectivity remains UNVERIFIED until you run the phone test.")
                    if (!allowed) Text("Unavailable: inspect the VPS, select an unused port/public IPv4, ensure Linux AMD64/root SSH, and resolve existing Psiphon files or node registration.",
                        color = Mx.Orange)
                }
            },
            confirmButton = {
                TextButton(enabled = allowed, onClick = {
                    vm.installPrivatePsiphon(requireNotNull(server).id, installIp, requireNotNull(port))
                    showSafety = false
                }) { Text("Approve & install on VPS") }
            },
            dismissButton = {
                TextButton(onClick = { showSafety = false }) { Text("Cancel") }
            }
        )
    }
}
@Composable
private fun MxPsStep(number: Int, name: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Mx.Card, shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, Mx.Gold.copy(alpha = .55f))) {
        Column(Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${number.toString().padStart(2, '0')}", color = Mx.Gold,
                fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Text(name, color = Mx.White, fontSize = 10.sp, maxLines = 2,
                lineHeight = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
@Composable
private fun PsDeployment(
    server: com.example.panels.servers.ManagedServer,
    facts: ServerFacts?, fresh: Boolean, readiness: Float,
    vm: ServersViewModel, onInstallation: () -> Unit, modifier: Modifier = Modifier
) {
    MxCard("One-Click Deployment", "Secure private Psiphon on your selected VPS",
        Mx.Gold, modifier) {
        MxInfoRow("Selected VPS", server.displayName, Mx.Cyan)
        MxInfoRow("SSH host verification", if (server.canSignIn) "Trusted" else "Required",
            if (server.canSignIn) Mx.Green else Mx.Orange)
        MxInfoRow("System", facts?.osName ?: "Unknown")
        MxInfoRow("Port conflict prevention", if (fresh && facts?.socketInspectionAvailable == true)
            "Inspected" else "Run scan", if (fresh && facts?.socketInspectionAvailable == true) Mx.Green else Mx.Orange)
        MxInfoRow("psiphond installer", "Official binary • SHA-256 pinned • AMD64 only", Mx.Green)
        MxAction("Install on Selected Server", onInstallation, Modifier.fillMaxWidth(), strong = true)
        MxAction("Run Compatibility Check", { vm.refresh(server.id) }, Modifier.fillMaxWidth())
    }
}
@Composable
private fun PsReadiness(
    server: com.example.panels.servers.ManagedServer,
    facts: ServerFacts?, readiness: Float, registered: Boolean, verified: Boolean,
    modifier: Modifier = Modifier
) {
    MxCard("Installation Readiness", "Measured prerequisites — not installation success",
        Mx.Cyan, modifier) {
        MxProgress("Ready prerequisites", readiness)
        MxInfoRow("Node Registration", if (registered) "Registered" else "Unregistered",
            if (registered) Mx.Green else Mx.Orange)
        MxInfoRow("Tunnel Verification", if (verified) "Verified" else "Pending",
            if (verified) Mx.Green else Mx.Orange)
        MxInfoRow("System Health", if (facts == null) "Unknown" else
            "${facts.cpus} CPU • ${facts.ramAvailableMb} MB free", Mx.Cyan)
    }
}
@Composable
private fun PsImport(
    entry: String, onEntryChange: (String) -> Unit, registered: Boolean,
    onImport: () -> Unit, onTest: () -> Unit,
    test: Pair<Boolean, String>?, modifier: Modifier = Modifier
) {
    MxCard("Secure Import (server-entry.dat)",
        "Already have a private psiphond entry? Encrypt and register it.",
        Mx.Gold, modifier) {
        if (!registered) {
            OutlinedTextField(value = entry, onValueChange = {
                if (it.length <= 262_144) onEntryChange(it)
            }, label = { Text("Paste private server-entry.dat (hex)") },
                visualTransformation = PasswordVisualTransformation(),
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Mx.White, unfocusedTextColor = Mx.White,
                    focusedBorderColor = Mx.Gold, unfocusedBorderColor = Mx.Line,
                    focusedLabelColor = Mx.Gold, unfocusedLabelColor = Mx.Muted))
            MxAction("Encrypt & Register Private Node", onImport, Modifier.fillMaxWidth(),
                strong = true, enabled = entry.isNotBlank())
        } else {
            MxStatus("Encrypted node registered", Mx.Green)
            MxAction("Test real Psiphon tunnel", onTest, Modifier.fillMaxWidth(), strong = true)
            if (test != null) {
                MxStatus(if (test.first) "Tunnel verified" else "Tunnel not verified",
                    if (test.first) Mx.Green else Mx.Orange)
                Text(test.second, color = Mx.Muted, fontSize = 11.sp)
            }
        }
        Text("The private entry is hidden and stored with Android Keystore encryption.",
            color = Mx.Muted, fontSize = 10.sp)
    }
}
