
package com.example.ui.panels

import androidx.compose.ui.res.stringResource
import com.example.R
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
        PsiphonSettingsImportButton(vm, state.busy.isBlank() && state.psiphonInstall?.running != true)
        MxHero(stringResource(R.string.ui_maximus_vpn_private_server), stringResource(R.string.ui_private_psiphon_server),
            stringResource(R.string.ui_guided_deployment_encrypted_server_entry))

        MxCard(stringResource(R.string.ui_deployment_flow), stringResource(R.string.ui_secure_reviewed_vps_powered), Mx.Gold) {
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
            MxCard(stringResource(R.string.ui_add_a_vps), stringResource(R.string.ui_a_personally_controlled_server_is), Mx.Gold) {
                MxAction(stringResource(R.string.ui_connect_vps), { vm.openAdd() }, Modifier.fillMaxWidth(), strong = true)
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
                MxCard(stringResource(R.string.ui_private_psiphon_deployment), stringResource(R.string.ui_actual_ssh_operation_never_marks), Mx.Cyan) {
                    MxInfoRow(stringResource(R.string.ui_operation), progress.stage,
                        if (progress.error.isBlank()) Mx.Cyan else Mx.Orange)
                    MxInfoRow(stringResource(R.string.ui_managed_server), if (progress.serverInstalled) "Started" else "Not confirmed",
                        if (progress.serverInstalled) Mx.Green else Mx.Orange)
                    MxInfoRow(stringResource(R.string.ui_client_entry), if (progress.entryRegistered) "Encrypted and registered" else "Pending",
                        if (progress.entryRegistered) Mx.Green else Mx.Orange)
                    if (progress.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (progress.error.isNotBlank()) Text(progress.error +
                        " Inspect VPS facts and the operation journal before retrying.",
                        color = Mx.Orange, fontSize = 11.sp)
                    if (progress.entryRegistered) MxAction(stringResource(R.string.ui_verify_phone_to_vps_tunnel), {
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
                        MxCard(stringResource(R.string.ui_cloudflare_helper), stringResource(R.string.ui_domain_and_dns_setup_for),
                            Mx.Gold, Modifier.weight(1f)) {
                            Text(stringResource(R.string.ui_dns_changes_and_api_tokens),
                                color = Mx.Muted, fontSize = 11.sp)
                            MxStatus(stringResource(R.string.ui_https_proxy_not_assumed), Mx.Cyan)
                            MxAction(stringResource(R.string.ui_open_smart_manager_cloudflare), {
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
                        MxCard(stringResource(R.string.ui_cloudflare_helper), stringResource(R.string.ui_managed_through_smart_manager), Mx.Gold) {
                            Text(stringResource(R.string.ui_use_the_cloudflare_tab_or),
                                color = Mx.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            MxCard(stringResource(R.string.ui_smart_checks), stringResource(R.string.ui_pre_deployment_ssh_resource_and), Mx.Gold) {
                MxInfoRow(stringResource(R.string.ui_ssh_reachability),
                    if (fresh) "Inspected" else "Pending",
                    if (fresh) Mx.Green else Mx.Orange)
                MxInfoRow(stringResource(R.string.ui_ssh_fingerprint),
                    if (server.hostKeySha256.isNotBlank()) "Pinned" else "Pending",
                    if (server.hostKeySha256.isNotBlank()) Mx.Green else Mx.Orange)
                MxInfoRow(stringResource(R.string.ui_open_ports), if (facts?.socketInspectionAvailable == true)
                    "TCP ${facts.tcpPorts.size} / UDP ${facts.udpPorts.size}" else "Not inspected", Mx.Cyan)
                MxInfoRow(stringResource(R.string.ui_firewall), facts?.firewall ?: "Not inspected", Mx.Cyan)
                MxInfoRow("psiphond", when {
                    facts?.psiphonInstalled == true -> "Service detected • unverified"
                    facts?.psiphonDataRetained == true -> "Service absent • private keys retained"
                    else -> "Not detected"
                }, Mx.Orange)
                MxInfoRow(stringResource(R.string.ui_upstream_sha_256_review), "Pinned official AMD64 binary", Mx.Green)
                MxInfoRow(stringResource(R.string.ui_android_to_vps_test), if (verified) "Verified" else "Pending",
                    if (verified) Mx.Green else Mx.Orange)
                MxAction(stringResource(R.string.ui_run_compatibility_check), { vm.refresh(server.id) }, Modifier.fillMaxWidth())
            }

            MxCard(stringResource(R.string.ui_management), stringResource(R.string.ui_manage_your_vps_and_securely), Mx.Gold) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MxAction("VPS", { vm.openDetail(server.id) }, Modifier.weight(1f))
                    MxAction(stringResource(R.string.ui_scan), { vm.refresh(server.id) }, Modifier.weight(1f))
                    MxAction(stringResource(R.string.ui_test_tunnel), { vm.testPrivatePsiphon(server.id) },
                        Modifier.weight(1.2f), enabled = node != null)
                }
                Text(stringResource(R.string.ui_installation_uses_a_pinned_official),
                    color = Mx.Muted, fontSize = 10.sp)
            }
        }
        if (state.busy.isNotBlank()) Text(state.busy, color = Mx.Cyan, fontSize = 11.sp)
        if (state.error.isNotBlank()) Text(state.error, color = Mx.Red, fontSize = 11.sp)
        Text(stringResource(R.string.ui_server_installation_and_encrypted_bootst),
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
            title = { Text(stringResource(R.string.ui_approve_private_psiphon_vps_installation)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_official_psiphond_for_linux_amd64))
                    Text(stringResource(R.string.ui_vps_root_ssh_required, server?.displayName ?: "Not selected"))
                    OutlinedTextField(value = installIp, onValueChange = { installIp = it.take(15) },
                        label = { Text(stringResource(R.string.ui_public_ipv4_advertised_to_clients)) },
                        placeholder = { Text(stringResource(R.string.ui_example_8_8_8_8)) }, singleLine = true)
                    OutlinedTextField(value = installPort, onValueChange = {
                        installPort = it.filter(Char::isDigit).take(5)
                    }, label = { Text(stringResource(R.string.ui_dedicated_tcp_ossh_port)) }, singleLine = true)
                    Text(stringResource(R.string.ui_creates_a_dedicated_systemd_service))
                    Text(stringResource(R.string.ui_after_installation_the_private_server))
                    if (!allowed) Text(stringResource(R.string.ui_unavailable_inspect_the_vps_select),
                        color = Mx.Orange)
                }
            },
            confirmButton = {
                TextButton(enabled = allowed, onClick = {
                    vm.installPrivatePsiphon(requireNotNull(server).id, installIp, requireNotNull(port))
                    showSafety = false
                }) { Text(stringResource(R.string.ui_approve_install_on_vps)) }
            },
            dismissButton = {
                TextButton(onClick = { showSafety = false }) { Text(stringResource(R.string.ui_cancel)) }
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
    MxCard(stringResource(R.string.ui_one_click_deployment), stringResource(R.string.ui_secure_private_psiphon_on_your),
        Mx.Gold, modifier) {
        MxInfoRow(stringResource(R.string.ui_selected_vps), server.displayName, Mx.Cyan)
        MxInfoRow(stringResource(R.string.ui_ssh_host_verification), if (server.canSignIn) "Trusted" else "Required",
            if (server.canSignIn) Mx.Green else Mx.Orange)
        MxInfoRow(stringResource(R.string.ui_system), facts?.osName ?: "Unknown")
        MxInfoRow(stringResource(R.string.ui_port_conflict_prevention), if (fresh && facts?.socketInspectionAvailable == true)
            "Inspected" else "Run scan", if (fresh && facts?.socketInspectionAvailable == true) Mx.Green else Mx.Orange)
        MxInfoRow(stringResource(R.string.ui_psiphond_installer), "Official binary • SHA-256 pinned • AMD64 only", Mx.Green)
        MxAction(stringResource(R.string.ui_install_on_selected_server), onInstallation, Modifier.fillMaxWidth(), strong = true)
        MxAction(stringResource(R.string.ui_run_compatibility_check), { vm.refresh(server.id) }, Modifier.fillMaxWidth())
    }
}
@Composable
private fun PsReadiness(
    server: com.example.panels.servers.ManagedServer,
    facts: ServerFacts?, readiness: Float, registered: Boolean, verified: Boolean,
    modifier: Modifier = Modifier
) {
    MxCard(stringResource(R.string.ui_installation_readiness), stringResource(R.string.ui_measured_prerequisites_not_installation),
        Mx.Cyan, modifier) {
        MxProgress(stringResource(R.string.ui_ready_prerequisites), readiness)
        MxInfoRow(stringResource(R.string.ui_node_registration), if (registered) "Registered" else "Unregistered",
            if (registered) Mx.Green else Mx.Orange)
        MxInfoRow(stringResource(R.string.ui_tunnel_verification), if (verified) "Verified" else "Pending",
            if (verified) Mx.Green else Mx.Orange)
        MxInfoRow(stringResource(R.string.ui_system_health), if (facts == null) "Unknown" else
            "${facts.cpus} CPU • ${facts.ramAvailableMb} MB free", Mx.Cyan)
    }
}
@Composable
private fun PsImport(
    entry: String, onEntryChange: (String) -> Unit, registered: Boolean,
    onImport: () -> Unit, onTest: () -> Unit,
    test: Pair<Boolean, String>?, modifier: Modifier = Modifier
) {
    MxCard(stringResource(R.string.ui_secure_import_server_entry_dat),
        stringResource(R.string.ui_already_have_a_private_psiphond),
        Mx.Gold, modifier) {
        if (!registered) {
            OutlinedTextField(value = entry, onValueChange = {
                if (it.length <= 262_144) onEntryChange(it)
            }, label = { Text(stringResource(R.string.ui_paste_private_server_entry_dat)) },
                visualTransformation = PasswordVisualTransformation(),
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Mx.White, unfocusedTextColor = Mx.White,
                    focusedBorderColor = Mx.Gold, unfocusedBorderColor = Mx.Line,
                    focusedLabelColor = Mx.Gold, unfocusedLabelColor = Mx.Muted))
            MxAction(stringResource(R.string.ui_encrypt_register_private_node), onImport, Modifier.fillMaxWidth(),
                strong = true, enabled = entry.isNotBlank())
        } else {
            MxStatus(stringResource(R.string.ui_encrypted_node_registered), Mx.Green)
            MxAction(stringResource(R.string.ui_test_real_psiphon_tunnel), onTest, Modifier.fillMaxWidth(), strong = true)
            if (test != null) {
                MxStatus(if (test.first) stringResource(R.string.ui_tunnel_verified) else stringResource(R.string.ui_tunnel_not_verified),
                    if (test.first) Mx.Green else Mx.Orange)
                Text(test.second, color = Mx.Muted, fontSize = 11.sp)
            }
        }
        Text(stringResource(R.string.ui_the_private_entry_is_hidden),
            color = Mx.Muted, fontSize = 10.sp)
    }
}
