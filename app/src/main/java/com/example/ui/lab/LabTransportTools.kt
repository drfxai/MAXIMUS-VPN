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
internal fun TransportToolsPage(state: NetworkLabUiState, actions: NetworkLabActions) {
    var endpoints by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var sni by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var splitHello by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val ui = state.transportTools
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)) {
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
        item {
            Text("SNI / Host fronted tunnel", style = MaterialTheme.typography.titleMedium)
            Text("Requires a compatible TLS front and WebSocket origin configured by your server operator. The TLS certificate must match SNI. This saves a new profile; it is not marked verified.")
        }
        item { Box {
            OutlinedButton(onClick = { expanded = true }) { Text(state.configs.firstOrNull { it.id == selected }?.name ?: "Choose a base config") }
            DropdownMenu(expanded, { expanded = false }) {
                state.configs.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { selected = c.id; expanded = false }) }
            }
        } }
        item { OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Front IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(sni, { sni = it }, label = { Text("TLS SNI / certificate name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(host, { host = it }, label = { Text("Origin HTTP Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { Row { Checkbox(splitHello, { splitHello = it }); Text("Native Xray TLS record + TCP fragmentation") } }
        item { Button(onClick = { actions.onSaveFronted(selected, endpoint, sni, host, splitHello) }, enabled = selected.isNotBlank() && !ui.running && !state.vpnOn) { Text("Test and save fronted profile") } }
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
        item { if (ui.message.isNotBlank()) Text(ui.message) }
    }
}
