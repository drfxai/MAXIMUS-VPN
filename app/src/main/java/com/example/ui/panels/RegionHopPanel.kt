package com.example.ui.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.panels.servers.RegionHopManager
import com.example.ui.panels.servers.ServersState
import com.example.ui.panels.servers.ServersViewModel
import org.json.JSONObject

/** The same controls appear after 3X-UI in Servers and in Smart Manager. */
@Composable
internal fun RegionHopPanel(vm: ServersViewModel, state: ServersState, serverId: String) {
    val context = LocalContext.current
    val server = state.servers.firstOrNull { it.id == serverId } ?: return
    val snapshot = state.regionHop[serverId]
    val idle = state.busy.isBlank() && state.psiphonInstall?.running != true && state.bundle?.running != true
    var region by remember(serverId) { mutableStateOf("DE") }
    var menu by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<String?>(null) }
    var address by remember(serverId) { mutableStateOf("https://127.0.0.1:2053/") }
    var token by remember(serverId) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("RegionHop · Psiphon locations", style = MaterialTheme.typography.titleMedium)
            Text("Optional after 3X-UI · ${server.displayName}")
            Text("Device → your VPS → Psiphon country → Internet. Local proxy ports stay private.", style = MaterialTheme.typography.bodySmall)
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
            if (!idle) Text(state.busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PsiphonSettingsImportButton(vm, idle)
                TextButton(onClick = { vm.regionHopAction(serverId, "status") }, enabled = idle) { Text("Inspect") }
            }
            if (snapshot?.installed != true) {
                Text(snapshot?.detail ?: "Inspect installation first. Root SSH and 3X-UI are required.")
                Button(onClick = { confirmInstall = true }, enabled = idle && server.canSignIn && server.username == "root" && server.facts?.xuiInstalled == true) { Text("Review RegionHop install") }
            } else {
                Text("Installed ${snapshot.version} · ${snapshot.locations.size} locations")
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = idle) { Text("Country: $region") }
                    DropdownMenu(menu, { menu = false }) {
                        RegionHopManager.REGIONS.forEach { code -> DropdownMenuItem(text = { Text("$code · ${java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH)}") }, onClick = { region = code; menu = false }) }
                    }
                }
                Button(onClick = { vm.regionHopAction(serverId, "add", JSONObject().put("region", region)) }, enabled = idle) { Text("Add + start location") }
                snapshot.locations.forEach { location ->
                    HorizontalDivider()
                    Text("Requested ${location.region} · ${location.name} · ${location.outbound}", style = MaterialTheme.typography.titleSmall)
                    Text("Local SOCKS ${location.port} · ${location.state} · phone traffic untested", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("start", "stop", "restart", "test").forEach { action ->
                            TextButton(onClick = { vm.regionHopAction(serverId, action, JSONObject().put("name", location.name)) }, enabled = idle) { Text(action.replaceFirstChar { it.uppercase() }) }
                        }
                    }
                    TextButton(onClick = { remove = location.name }, enabled = idle) { Text("Remove location") }
                }
                if (snapshot.detail.isNotBlank()) Text(snapshot.detail, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("3X-UI outbound synchronization", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(address, { address = it }, label = { Text("Loopback panel URL + web path") }, modifier = Modifier.fillMaxWidth(), enabled = idle)
                OutlinedTextField(token, { token = it }, label = { Text("3X-UI API token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = idle)
                Text("Sync creates regionhop-* outbounds. Assign them to your inbounds in 3X-UI Routing; existing user rules are preserved.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    vm.regionHopAction(serverId, "sync", JSONObject().put("url", address.trim()).put("token", token.trim()))
                    token = ""
                }, enabled = idle && token.isNotBlank()) { Text("Save token + sync outbounds") }
                TextButton(onClick = { confirmRemoveAll = true }, enabled = idle) { Text("Uninstall managed RegionHop") }
            }
        }
    }
    if (confirmInstall) AlertDialog(onDismissRequest = { confirmInstall = false }, title = { Text("Install RegionHop?") },
        text = { Text("Install pinned RegionHop 1.27.0 on ${server.displayName}, create isolated services and a loopback panel, and send your privately imported Psiphon settings to this VPS. 3X-UI routing changes require the separate Sync action.") },
        confirmButton = { TextButton(onClick = { confirmInstall = false; vm.regionHopAction(serverId, "install") }) { Text("Install on this VPS") } },
        dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("Cancel") } })
    if (confirmRemoveAll) AlertDialog(onDismissRequest = { confirmRemoveAll = false }, title = { Text("Uninstall RegionHop?") },
        text = { Text("Stops all locations and removes their linked RegionHop outbounds and managed panel files. Review 3X-UI routing rules that used these outbounds. 3X-UI itself is preserved.") },
        confirmButton = { TextButton(onClick = { confirmRemoveAll = false; vm.regionHopAction(serverId, "uninstall") }) { Text("Uninstall") } },
        dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } })
    remove?.let { name -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove $name?") },
        text = { Text("Stops this location and removes its RegionHop outbound from the linked 3X-UI panel.") },
        confirmButton = { TextButton(onClick = { remove = null; vm.regionHopAction(serverId, "remove", JSONObject().put("name", name)) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
}

@Composable
internal fun PsiphonSettingsImportButton(vm: ServersViewModel, enabled: Boolean = true) {
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { stream ->
                val buffer = ByteArray(512 * 1024 + 1); var count = 0
                while (count < buffer.size) { val n = stream.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                buffer.copyOf(count)
             }
            require(bytes.size <= 512 * 1024) { "Psiphon settings exceed 512 KiB" }
            vm.importPsiphonConfig(bytes.toString(Charsets.UTF_8))
        }.onFailure { vm.importPsiphonConfig("") }
    }
    TextButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = enabled) { Text("Import Psiphon.config") }
}

@Composable
internal fun RegionHopDialog(vm: ServersViewModel, state: ServersState, serverId: String, onDismiss: () -> Unit) {
    LaunchedEffect(serverId) { vm.regionHopAction(serverId, "status") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 720.dp)
            .verticalScroll(rememberScrollState())) {
            TextButton(onClick = onDismiss) { Text("Back") }
            RegionHopPanel(vm, state, serverId)
        }
    }
}
