package com.example.ui.lab

import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vpn.experimental.ReachabilityScanner

/** Inputs are explicit, profiles are saved as unverified clones, connection remains a separate action. */
data class TransportToolsUi(
    val running: Boolean = false,
    val results: List<ReachabilityScanner.Result> = emptyList(),
    val message: String = "",
    val warpSplit: Boolean = false,
    /** Build 181 Phase 5: Smart SNI / ECH status on this network and the last LAB clean-IP scan. */
    val smartSni: String = "",
    val echStatus: String = "",
    val cleanIpScanning: Boolean = false,
    val cleanIpScan: com.example.vpn.cleanip.SmartIpScan.Report? = null,
    val cleanIpError: String = ""
)

@Composable
internal fun TransportToolsPage(state: NetworkLabUiState, actions: NetworkLabActions, frontingOnly: Boolean = false) {
    var endpoints by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var sni by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var splitHello by rememberSaveable { mutableStateOf(false) }
    var fake by rememberSaveable { mutableStateOf(false) }
    var decoy by rememberSaveable { mutableStateOf("www.microsoft.com") }
    var ttl by rememberSaveable { mutableStateOf("8") }
    var expanded by remember { mutableStateOf(false) }
    val ui = state.transportTools
    val bases = state.configs.filter { it.supportsDomainFronting }
    val base = bases.firstOrNull { it.id == selected } ?: bases.singleOrNull().takeIf { frontingOnly }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)) {
        if (!frontingOnly) {
            item {
                Text(stringResource(R.string.ui_lab_smart_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ui_lab_current_sni, ui.smartSni.ifBlank { "…" }))
                Text(stringResource(R.string.ui_lab_ech_status, ui.echStatus.ifBlank { "…" }))
            }
            item {
                Button(onClick = actions.onScanCleanIps, enabled = !ui.cleanIpScanning && !state.vpnOn) {
                    Text(if (ui.cleanIpScanning) stringResource(R.string.ui_lab_scanning) else stringResource(R.string.ui_lab_scan_clean_ips))
                }
            }
            ui.cleanIpScan?.let { r ->
                val edges = r.warp + r.cdn
                item { Text(stringResource(R.string.ui_lab_scan_result, r.count, r.warp.size, r.cdn.size, r.bestMs?.let { "$it ms" } ?: "—")) }
                items(edges.size) { i ->
                    val e = edges[i]
                    Text("${e.kind.name} ${e.address} · ${e.medianMs ?: "—"} ms · ${e.lossPercent}%" +
                        (e.kbps?.takeIf { it > 0 }?.let { " · $it KB/s" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ui.cleanIpError.isNotBlank()) item { Text(ui.cleanIpError) }
            item { HorizontalDivider() }
            item {
                Text(stringResource(R.string.ui_reachability_allowlist_candidates), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ui_tests_only_the_public_ip))
            }
            item { OutlinedTextField(endpoints, { endpoints = it }, label = { Text(stringResource(R.string.ui_public_ip_or_ip_port)) }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onScanEndpoints(endpoints) }, enabled = !ui.running && !state.vpnOn) { Text(stringResource(R.string.ui_scan)) }
                OutlinedButton(onClick = actions.onStopEndpoints, enabled = ui.running) { Text(stringResource(R.string.ui_stop)) }
            } }
            item { if (state.vpnOn) Text(stringResource(R.string.ui_disconnect_the_vpn_before_measuring)) }
            items(ui.results.size) { i ->
                val r = ui.results[i]
                Text(stringResource(R.string.ui_tcp_2, r.target.address, r.target.port, r.successes, r.medianMs?.let { "$it ms" } ?: "no response"))
            }
            item { HorizontalDivider() }
        }
        item {
            if (!frontingOnly) Text(stringResource(R.string.ui_sni_host_fronted_tunnel), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ui_choose_a_websocket_tls_profile))
            if (bases.isEmpty()) Text(stringResource(R.string.ui_import_a_vless_vmess_or))
            if (frontingOnly && state.vpnOn) Text(stringResource(R.string.ui_disconnect_the_vpn_before_testing))
        }
        if (frontingOnly) item {
            Text(stringResource(R.string.ui_t_1_choose_a_saved_profile), style = MaterialTheme.typography.titleMedium)
        }
        item { Box {
            OutlinedButton(onClick = { expanded = true }, enabled = bases.isNotEmpty() && !ui.running) { Text(base?.name ?: "Choose a base config") }
            DropdownMenu(expanded, { expanded = false }) {
                bases.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { selected = c.id; expanded = false }) }
            }
        } }
        if (frontingOnly) item {
            Text(stringResource(R.string.ui_t_2_enter_the_front_and), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ui_use_the_public_ip_tls))
        }
        item { OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.ui_front_ip)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(sni, { sni = it }, label = { Text(stringResource(R.string.ui_tls_sni_certificate_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.ui_origin_http_host)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        if (!frontingOnly) item { Row { Checkbox(splitHello, { splitHello = it; if (it) fake = false }); Text(stringResource(R.string.ui_native_xray_tls_record_tcp)) } }
        if (!frontingOnly) item { Row { Checkbox(fake, { fake = it; if (it) splitHello = false }); Text(stringResource(R.string.ui_native_fake_sni_packets_experimental)) } }
        if (!frontingOnly && fake) {
            item { OutlinedTextField(decoy, { decoy = it }, label = { Text(stringResource(R.string.ui_decoy_sni_real_tls_identity)) }, singleLine = true) }
            item { OutlinedTextField(ttl, { ttl = it }, label = { Text(stringResource(R.string.ui_decoy_ttl_1_32)) }, singleLine = true) }
            item { Text(stringResource(R.string.ui_ttl_must_expire_before_the)) }
        }
        if (frontingOnly) item {
            Text(stringResource(R.string.ui_t_3_test_and_save), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ui_the_test_checks_traffic_through))
        }
        item {
            Button(
                onClick = { base?.let { actions.onSaveFronted(it.id, endpoint, sni, host, !frontingOnly && splitHello,
                    if (!frontingOnly && fake) decoy else "", ttl.toIntOrNull() ?: 0) } },
                enabled = base != null && endpoint.isNotBlank() && sni.isNotBlank() &&
                    host.isNotBlank() && !ui.running && !state.vpnOn
            ) { Text(if (ui.running) stringResource(R.string.ui_testing) else stringResource(R.string.ui_test_and_save_fronted_profile)) }
        }
        if (!frontingOnly) {
            item { HorizontalDivider() }
            item {
                Text(stringResource(R.string.ui_warp_registration_tls_splitting), style = MaterialTheme.typography.titleMedium)
                Row {
                    Checkbox(ui.warpSplit, actions.onWarpSplit)
                    Text(stringResource(R.string.ui_fragment_clienthello_into_small_tls))
                }
                Text(stringResource(R.string.ui_existing_warp_accounts_are_reused))
            }
            item { Text(stringResource(R.string.ui_dns_timing_jitter_is_included)) }
            item { Text(stringResource(R.string.ui_server_side_l3_candidate_tests)) }
        }
        item { if (ui.message.isNotBlank()) Text(ui.message) }
    }
}
