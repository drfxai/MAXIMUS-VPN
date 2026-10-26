package com.example.ui.panels.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.panels.servers.panel.HiddifyQuickSetup

@Composable
internal fun HiddifyQuickDialog(vm: ServersViewModel, id: String, busy: Boolean, dismiss: () -> Unit) {
    var name by remember(id) { mutableStateOf("Maximus") }
    var days by remember(id) { mutableStateOf("30") }
    var quota by remember(id) { mutableStateOf("100") }
    val state by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("Quick Hiddify config") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create one user on this panel, then import its subscription into Nodes. This changes the server. If a reply is lost, Retry checks the pending user before creating anything else.")
                OutlinedTextField(name, { name = it }, label = { Text("User name") }, singleLine = true)
                OutlinedTextField(days, { days = it }, label = { Text("Expiry (days)") }, singleLine = true)
                OutlinedTextField(quota, { quota = it }, label = { Text("Traffic quota (GB)") }, singleLine = true)
                if (busy) Text(state.busy)
                if (state.error.isNotBlank()) Text(state.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && name.length <= 80 &&
                (days.toIntOrNull() ?: 0) in 1..10000 && (quota.toIntOrNull() ?: 0) in 1..1000000,
                onClick = { vm.quickHiddifyUser(id, name, days.toInt(), quota.toInt(), dismiss) }) {
                Text("Create & import")
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
internal fun HiddifyTelegramDialog(vm: ServersViewModel, id: String, busy: Boolean, copy: (String) -> Unit, dismiss: () -> Unit) {
    var userLink by remember(id) { mutableStateOf("") }
    var links by remember(id) { mutableStateOf<List<HiddifyQuickSetup.TelegramLink>>(emptyList()) }
    val state by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("Telegram proxy / Web proxy") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Read links enabled by Hiddify. A Web proxy is listed separately from raw MTProto; neither is assumed to have verified CDN support. Paste a user page link, or leave blank to use the last created user.")
                OutlinedTextField(userLink, { userLink = it; links = emptyList() }, label = { Text("Hiddify user link (optional)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (busy) Text(state.busy)
                if (state.error.isNotBlank()) Text(state.error)
                links.forEachIndexed { index, link ->
                    TextButton(onClick = { copy(link.link) }, enabled = !busy) { Text("Copy ${link.title} ${index + 1}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.hiddifyTelegram(id, userLink) { links = it } }, enabled = !busy) { Text("Get links") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Close") } }
    )
}
