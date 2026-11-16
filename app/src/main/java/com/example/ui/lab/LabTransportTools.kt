package com.example.ui.lab

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
    val warpSplit: Boolean = false
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
                Text("Reachability / allowlist candidates", style = MaterialTheme.typography.titleMedium)
                Text("Tests only the public IP endpoints you enter, on the physical network. A successful TCP connect does not prove an L3 allowlist or a working VPN.")
            }
            item { OutlinedTextField(endpoints, { endpoints = it }, label = { Text("Public IP or IP:port · up to 32") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onScanEndpoints(endpoints) }, enabled = !ui.running && !state.vpnOn) { Text("Scan") }
                OutlinedButton(onClick = actions.onStopEndpoints, enabled = ui.running) { Text("Stop") }
            } }
            item { if (state.vpnOn) Text("Disconnect the VPN before measuring the physical network.") }
            items(ui.results.size) { i ->
                val r = ui.results[i]
                Text("${r.target.address}:${r.target.port} · TCP ${r.successes}/2 · ${r.medianMs?.let { "$it ms" } ?: "no response"}")
            }
            item { HorizontalDivider() }
        }
        item {
            if (!frontingOnly) Text("SNI / Host fronted tunnel", style = MaterialTheme.typography.titleMedium)
            Text("Choose a WebSocket TLS profile and enter the front IP, TLS name and origin Host configured by your server operator.")
            if (bases.isEmpty()) Text("Import a VLESS, VMess or Trojan WebSocket TLS profile to begin.")
            if (frontingOnly && state.vpnOn) Text("Disconnect the VPN before testing this route.")
        }
        if (frontingOnly) item {
            Text("1. Choose a saved profile", style = MaterialTheme.typography.titleMedium)
        }
        item { Box {
            OutlinedButton(onClick = { expanded = true }, enabled = bases.isNotEmpty() && !ui.running) { Text(base?.name ?: "Choose a base config") }
            DropdownMenu(expanded, { expanded = false }) {
                bases.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { selected = c.id; expanded = false }) }
            }
        } }
        if (frontingOnly) item {
            Text("2. Enter the front and destination", style = MaterialTheme.typography.titleMedium)
            Text("Use the public IP, TLS certificate name and destination Host provided by your operator.")
        }
        item { OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Front IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(sni, { sni = it }, label = { Text("TLS SNI / certificate name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(host, { host = it }, label = { Text("Origin HTTP Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        if (!frontingOnly) item { Row { Checkbox(splitHello, { splitHello = it; if (it) fake = false }); Text("Native Xray TLS record + TCP fragmentation") } }
        if (!frontingOnly) item { Row { Checkbox(fake, { fake = it; if (it) splitHello = false }); Text("Native fake-SNI packets (experimental)") } }
        if (!frontingOnly && fake) {
            item { OutlinedTextField(decoy, { decoy = it }, label = { Text("Decoy SNI · real TLS identity stays unchanged") }, singleLine = true) }
            item { OutlinedTextField(ttl, { ttl = it }, label = { Text("Decoy TTL · 1–32") }, singleLine = true) }
            item { Text("TTL must expire before the real server. A wrong value can break TLS; test each network. Requires the bundled ByeDPI engine.") }
        }
        if (frontingOnly) item {
            Text("3. Test and save", style = MaterialTheme.typography.titleMedium)
            Text("The test checks traffic through the proxy. Saved profiles are available in Nodes.")
        }
        item {
            Button(
                onClick = { base?.let { actions.onSaveFronted(it.id, endpoint, sni, host, !frontingOnly && splitHello,
                    if (!frontingOnly && fake) decoy else "", ttl.toIntOrNull() ?: 0) } },
                enabled = base != null && endpoint.isNotBlank() && sni.isNotBlank() &&
                    host.isNotBlank() && !ui.running && !state.vpnOn
            ) { Text(if (ui.running) "Testing…" else "Test and save fronted profile") }
        }
        if (!frontingOnly) {
            item { HorizontalDivider() }
            item {
                Text("WARP registration · TLS splitting", style = MaterialTheme.typography.titleMedium)
                Row {
                    Checkbox(ui.warpSplit, actions.onWarpSplit)
                    Text("Fragment ClientHello into small TLS records on new registration (experimental).")
                }
                Text("Existing WARP accounts are reused. TLS trust and certificate checks stay enabled. Physical packet boundaries depend on Android and the network.")
            }
            item { Text("DNS timing jitter is included in the dnstt build, with positive random delay and the existing query-rate cap. The native engine is bundled in official releases.") }
            item { Text("Server-side L3 candidate tests and TCP/UDP/ICMP/ICMPv6 spoof carriers are available in Servers → Maximus Tunnel → Spoof carrier. Configure both managed servers and explicit source IPs there.") }
        }
        item { if (ui.message.isNotBlank()) Text(ui.message) }
    }
}
