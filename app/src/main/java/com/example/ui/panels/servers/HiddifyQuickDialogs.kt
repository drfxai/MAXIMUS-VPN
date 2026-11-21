package com.example.ui.panels.servers

import androidx.compose.ui.res.stringResource
import com.example.R
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
        title = { Text(stringResource(R.string.ui_quick_hiddify_config)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ui_create_one_user_on_this))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ui_user_name)) }, singleLine = true)
                OutlinedTextField(days, { days = it }, label = { Text(stringResource(R.string.ui_expiry_days)) }, singleLine = true)
                OutlinedTextField(quota, { quota = it }, label = { Text(stringResource(R.string.ui_traffic_quota_gb)) }, singleLine = true)
                if (busy) Text(state.busy)
                if (state.error.isNotBlank()) Text(state.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && name.length <= 80 &&
                (days.toIntOrNull() ?: 0) in 1..10000 && (quota.toIntOrNull() ?: 0) in 1..1000000,
                onClick = { vm.quickHiddifyUser(id, name, days.toInt(), quota.toInt(), dismiss) }) {
                Text(stringResource(R.string.ui_create_import))
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

@Composable
internal fun HiddifyTelegramDialog(vm: ServersViewModel, id: String, busy: Boolean, copy: (String) -> Unit, dismiss: () -> Unit) {
    var userLink by remember(id) { mutableStateOf("") }
    var links by remember(id) { mutableStateOf<List<HiddifyQuickSetup.TelegramLink>>(emptyList()) }
    val state by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.ui_telegram_proxy_web_proxy)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ui_read_links_enabled_by_hiddify))
                OutlinedTextField(userLink, { userLink = it; links = emptyList() }, label = { Text(stringResource(R.string.ui_hiddify_user_link_optional)) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (busy) Text(state.busy)
                if (state.error.isNotBlank()) Text(state.error)
                links.forEachIndexed { index, link ->
                    TextButton(onClick = { copy(link.link) }, enabled = !busy) { Text(stringResource(R.string.ui_copy_2, link.title, index + 1)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.hiddifyTelegram(id, userLink) { links = it } }, enabled = !busy) { Text(stringResource(R.string.ui_get_links)) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(R.string.ui_close)) } }
    )
}
