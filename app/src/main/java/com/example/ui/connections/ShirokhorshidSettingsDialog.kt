package com.example.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.vpn.connections.BuiltinMethodMemory
import com.example.vpn.connections.ShirokhorshidSettings
import com.example.vpn.sidecar.ShirokhorshidEdgeMemory
import com.example.vpn.sidecar.ShirokhorshidSidecar

/** Settings for the Shir o Khorshid method of "Connect without a config"; saved on the phone only. */
@Composable
fun ShirokhorshidSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { ShirokhorshidSettings.load(context) }
    val remembered = remember {
        runCatching { ShirokhorshidEdgeMemory.edges(BuiltinMethodMemory.network()).size }.getOrDefault(0)
    }
    var mode by remember { mutableStateOf(initial.mode) }
    var region by remember { mutableStateOf(initial.region) }
    var cdn by remember { mutableStateOf(initial.cdnFronting) }
    var edgesText by remember { mutableStateOf(initial.edges.joinToString("\n")) }
    var avoidIran by remember { mutableStateOf(initial.avoidIranConduits) }

    val regionCode = region.trim().uppercase()
    val regionOk = com.example.vpn.sidecar.PsiphonSidecar.validRegion(regionCode)
    val edges = ShirokhorshidSidecar.parseEdges(edgesText)
    val badEdge = edges.firstOrNull { !ShirokhorshidSidecar.validEdge(it) }
    val options = ShirokhorshidSidecar.Options(mode, regionCode, cdn, edges.take(ShirokhorshidSidecar.MAX_EDGES), avoidIran)
    val valid = regionOk && badEdge == null && options.problem() == null
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_shirokhorshid_settings)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.ui_shirokhorshid_protocol), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShirokhorshidSidecar.Mode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = { Text(stringResource(modeTitle(m))) },
                            modifier = Modifier.testTag("sok_mode_${m.key}")
                        )
                    }
                }
                Text(stringResource(modeDetail(mode)), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)

                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it.filter(Char::isLetter).take(2).uppercase() },
                    label = { Text(stringResource(R.string.ui_shirokhorshid_country)) },
                    supportingText = {
                        Text(stringResource(if (regionOk) R.string.ui_shirokhorshid_country_hint
                            else R.string.ui_shirokhorshid_country_invalid))
                    },
                    isError = !regionOk,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("sok_region")
                )

                SwitchRow(stringResource(R.string.ui_shirokhorshid_cdn), stringResource(R.string.ui_shirokhorshid_cdn_detail),
                    cdn, enabled = mode != ShirokhorshidSidecar.Mode.CONDUIT) { cdn = it }
                if (cdn && mode != ShirokhorshidSidecar.Mode.CONDUIT) {
                    OutlinedTextField(
                        value = edgesText,
                        onValueChange = { edgesText = it.take(4_000) },
                        label = { Text(stringResource(R.string.ui_shirokhorshid_edges)) },
                        supportingText = {
                            Text(if (badEdge != null) stringResource(R.string.ui_shirokhorshid_edges_invalid, badEdge)
                                else stringResource(R.string.ui_shirokhorshid_edges_hint))
                        },
                        isError = badEdge != null,
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag("sok_edges")
                    )
                    if (remembered > 0) {
                        Text(stringResource(R.string.ui_shirokhorshid_remembered, remembered),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                SwitchRow(stringResource(R.string.ui_shirokhorshid_avoid_iran),
                    stringResource(R.string.ui_shirokhorshid_avoid_iran_detail),
                    avoidIran, enabled = mode != ShirokhorshidSidecar.Mode.DIRECT) { avoidIran = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { ShirokhorshidSettings.save(context, options); onDismiss() },
                modifier = Modifier.testTag("sok_save")
            ) { Text(stringResource(R.string.ui_shirokhorshid_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } }
    )
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun modeTitle(mode: ShirokhorshidSidecar.Mode): Int = when (mode) {
    ShirokhorshidSidecar.Mode.AUTO -> R.string.ui_shirokhorshid_mode_auto
    ShirokhorshidSidecar.Mode.CONDUIT -> R.string.ui_shirokhorshid_mode_conduit
    ShirokhorshidSidecar.Mode.DIRECT -> R.string.ui_shirokhorshid_mode_direct
}

private fun modeDetail(mode: ShirokhorshidSidecar.Mode): Int = when (mode) {
    ShirokhorshidSidecar.Mode.AUTO -> R.string.ui_shirokhorshid_mode_auto_detail
    ShirokhorshidSidecar.Mode.CONDUIT -> R.string.ui_shirokhorshid_mode_conduit_detail
    ShirokhorshidSidecar.Mode.DIRECT -> R.string.ui_shirokhorshid_mode_direct_detail
}
