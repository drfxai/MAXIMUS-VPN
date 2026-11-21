package com.example.ui.panels

import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.panels.servers.PrivatePsiphonServerScripts
import com.example.ui.panels.servers.ServersState
import com.example.ui.panels.servers.ServersViewModel

/**
 * Standalone Psiphon Server page: safe private client bootstrap import and measurement. Automatic
 * remote psiphond install remains disabled until reviewed artifact hashes and rollback tests exist.
 * Never displays a pasted server entry back to the user or inserts it in diagnostics.
 */
@Composable
internal fun PrivatePsiphonServerScreen(vm: ServersViewModel, state: ServersState) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf("") }
    val server = state.servers.firstOrNull { it.id == chosen } ?: state.servers.singleOrNull()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_private_psiphon_server), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.ui_use_a_private_psiphond_server))
        if (state.servers.isEmpty()) {
            Button(onClick = { vm.openAdd() }) { Text(stringResource(R.string.ui_add_a_vps_first)) }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.servers.forEach { s ->
                    OutlinedButton(onClick = { chosen = s.id }) { Text(s.displayName.take(16)) }
                }
            }
        }
        if (server != null) {
            val managed = vm.managedNodesFor(server.id)
                .firstOrNull { it.serviceId == PrivatePsiphonServerScripts.TOOL_ID }
            Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Color(0xFF274F77))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.ui_selected_vps_2, server.displayName))
                    if (managed == null) {
                        OutlinedTextField(
                            value = entry,
                            onValueChange = { if (it.length <= 262_144) entry = it },
                            label = { Text(stringResource(R.string.ui_paste_private_server_entry_dat)) },
                            visualTransformation = PasswordVisualTransformation(),
                            minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = {
                            vm.importPrivatePsiphonEntry(server.id, entry)
                            entry = ""
                        }, enabled = entry.isNotBlank()) { Text(stringResource(R.string.ui_encrypt_register_private_node_2)) }
                    } else {
                        Text(stringResource(R.string.ui_managed_profile, managed.state.name))
                        Text(stringResource(R.string.ui_this_is_not_an_approved))
                        Button(onClick = { vm.testPrivatePsiphon(server.id) }) { Text(stringResource(R.string.ui_test_real_psiphon_tunnel)) }
                        state.tests["${server.id}/${PrivatePsiphonServerScripts.TOOL_ID}"]?.let { test ->
                            Text(if (test.first) stringResource(R.string.ui_tunnel_verified) else stringResource(R.string.ui_tunnel_not_verified),
                                color = if (test.first) Color(0xFF00D6A4) else MaterialTheme.colorScheme.error)
                            Text(test.second)
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.ui_remote_psiphon_auto_install_is),
            style = MaterialTheme.typography.bodySmall)
    }
}
