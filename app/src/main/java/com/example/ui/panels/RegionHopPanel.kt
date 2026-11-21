package com.example.ui.panels

import androidx.compose.ui.res.stringResource
import com.example.R
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
            Text(stringResource(R.string.ui_regionhop_psiphon_locations), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ui_optional_after_3x_ui, server.displayName))
            Text(stringResource(R.string.ui_device_your_vps_psiphon_country), style = MaterialTheme.typography.bodySmall)
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
            if (!idle) Text(state.busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PsiphonSettingsImportButton(vm, idle)
                TextButton(onClick = { vm.regionHopAction(serverId, "status") }, enabled = idle) { Text(stringResource(R.string.ui_inspect)) }
            }
            if (snapshot?.installed != true) {
                Text(snapshot?.detail ?: "Inspect installation first. Root SSH and 3X-UI are required.")
                Button(onClick = { confirmInstall = true }, enabled = idle && server.canSignIn && server.username == "root" && server.facts?.xuiInstalled == true) { Text(stringResource(R.string.ui_review_regionhop_install)) }
            } else {
                Text(stringResource(R.string.ui_installed_locations, snapshot.version, snapshot.locations.size))
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = idle) { Text(stringResource(R.string.ui_country, region)) }
                    DropdownMenu(menu, { menu = false }) {
                        RegionHopManager.REGIONS.forEach { code -> DropdownMenuItem(text = { Text("$code · ${java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH)}") }, onClick = { region = code; menu = false }) }
                    }
                }
                Button(onClick = { vm.regionHopAction(serverId, "add", JSONObject().put("region", region)) }, enabled = idle) { Text(stringResource(R.string.ui_add_start_location)) }
                snapshot.locations.forEach { location ->
                    HorizontalDivider()
                    Text(stringResource(R.string.ui_requested, location.region, location.name, location.outbound), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.ui_local_socks_phone_traffic_untested, location.port, location.state), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("start", "stop", "restart", "test").forEach { action ->
                            TextButton(onClick = { vm.regionHopAction(serverId, action, JSONObject().put("name", location.name)) }, enabled = idle) { Text(action.replaceFirstChar { it.uppercase() }) }
                        }
                    }
                    TextButton(onClick = { remove = location.name }, enabled = idle) { Text(stringResource(R.string.ui_remove_location)) }
                }
                if (snapshot.detail.isNotBlank()) Text(snapshot.detail, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(stringResource(R.string.ui_t_3x_ui_outbound_synchronization), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.ui_loopback_panel_url_web_path)) }, modifier = Modifier.fillMaxWidth(), enabled = idle)
                OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.ui_t_3x_ui_api_token)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = idle)
                Text(stringResource(R.string.ui_sync_creates_regionhop_outbounds_assign), style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    vm.regionHopAction(serverId, "sync", JSONObject().put("url", address.trim()).put("token", token.trim()))
                    token = ""
                }, enabled = idle && token.isNotBlank()) { Text(stringResource(R.string.ui_save_token_sync_outbounds)) }
                TextButton(onClick = { confirmRemoveAll = true }, enabled = idle) { Text(stringResource(R.string.ui_uninstall_managed_regionhop)) }
            }
        }
    }
    if (confirmInstall) AlertDialog(onDismissRequest = { confirmInstall = false }, title = { Text(stringResource(R.string.ui_install_regionhop)) },
        text = { Text(stringResource(R.string.ui_install_pinned_regionhop_1_27, server.displayName)) },
        confirmButton = { TextButton(onClick = { confirmInstall = false; vm.regionHopAction(serverId, "install") }) { Text(stringResource(R.string.ui_install_on_this_vps)) } },
        dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text(stringResource(R.string.ui_cancel)) } })
    if (confirmRemoveAll) AlertDialog(onDismissRequest = { confirmRemoveAll = false }, title = { Text(stringResource(R.string.ui_uninstall_regionhop)) },
        text = { Text(stringResource(R.string.ui_stops_all_locations_and_removes)) },
        confirmButton = { TextButton(onClick = { confirmRemoveAll = false; vm.regionHopAction(serverId, "uninstall") }) { Text(stringResource(R.string.ui_uninstall)) } },
        dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text(stringResource(R.string.ui_cancel)) } })
    remove?.let { name -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(stringResource(R.string.ui_remove_2, name)) },
        text = { Text(stringResource(R.string.ui_stops_this_location_and_removes)) },
        confirmButton = { TextButton(onClick = { remove = null; vm.regionHopAction(serverId, "remove", JSONObject().put("name", name)) }) { Text(stringResource(R.string.ui_remove)) } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text(stringResource(R.string.ui_cancel)) } }) }
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
    TextButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = enabled) { Text(stringResource(R.string.ui_import_psiphon_config)) }
}

@Composable
internal fun RegionHopDialog(vm: ServersViewModel, state: ServersState, serverId: String, onDismiss: () -> Unit) {
    LaunchedEffect(serverId) { vm.regionHopAction(serverId, "status") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 720.dp)
            .verticalScroll(rememberScrollState())) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_back)) }
            RegionHopPanel(vm, state, serverId)
        }
    }
}
