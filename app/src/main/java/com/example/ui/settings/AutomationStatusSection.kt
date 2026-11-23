package com.example.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.automation.AutomationRow
import com.example.ui.automation.AutomationUiState
import com.example.ui.automation.AutomationViewModel
import com.example.ui.theme.AppTheme
import com.example.vpn.automation.AutomationTask
import com.example.vpn.automation.SkipReason
import com.example.vpn.automation.TaskOutcome

/** The upkeep status, wired to its ViewModel. Minimal on purpose: the state is in [AutomationUiState]. */
@Composable
internal fun AutomationStatusSection(viewModel: AutomationViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AutomationStatusCard(state, onRun = viewModel::runNow, onAutoDrop = viewModel::setAutoDrop)
}

@Composable
internal fun AutomationStatusCard(
    state: AutomationUiState,
    onRun: (AutomationTask?) -> Unit,
    onAutoDrop: (Boolean) -> Unit,
    now: Long = System.currentTimeMillis()
) {
    SettingsSectionLabel(stringResource(R.string.automation_title))
    SettingsGroup(Modifier.testTag("automation_status")) {
        state.rows.forEachIndexed { i, row ->
            if (i > 0) SettingsDivider()
            SettingsNavRow(
                title = stringResource(titleOf(row.task)),
                subtitle = subtitleOf(row, now),
                icon = iconOf(row.task),
                trailing = {
                    Text(
                        if (row.outcome == TaskOutcome.OK || row.found > 0) row.found.toString() else "–",
                        color = AppTheme.colors.textSecondary
                    )
                },
                modifier = Modifier.testTag("automation_${row.task.name.lowercase()}"),
                onClick = { onRun(row.task) }
            )
        }
        SettingsDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.automation_auto_drop),
            subtitle = stringResource(R.string.automation_auto_drop_sub),
            icon = Icons.Default.DeleteSweep,
            checked = state.autoDrop,
            onCheckedChange = onAutoDrop
        )
        SettingsDivider()
        SettingsNavRow(
            title = stringResource(R.string.automation_run_now),
            subtitle = stringResource(R.string.automation_run_now_sub),
            icon = Icons.Default.Autorenew,
            trailing = { if (state.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else SettingsChevron() },
            modifier = Modifier.testTag("automation_run_now"),
            onClick = { onRun(null) }
        )
    }
    SettingsFootnote(stringResource(R.string.automation_footnote))
}

private fun titleOf(task: AutomationTask): Int = when (task) {
    AutomationTask.RESOLVER_SCAN -> R.string.automation_task_resolver_scan
    AutomationTask.DNS_LADDER -> R.string.automation_task_dns_ladder
    AutomationTask.CLEAN_IP_SCAN -> R.string.automation_task_clean_ip
    AutomationTask.CONFIG_REVIVAL -> R.string.automation_task_revival
    AutomationTask.GEO_UPDATE -> R.string.automation_task_geo
}

private fun iconOf(task: AutomationTask) = when (task) {
    AutomationTask.RESOLVER_SCAN -> Icons.Default.Dns
    AutomationTask.DNS_LADDER -> Icons.Default.Hub
    AutomationTask.CLEAN_IP_SCAN -> Icons.Default.Radar
    AutomationTask.CONFIG_REVIVAL -> Icons.Default.Healing
    AutomationTask.GEO_UPDATE -> Icons.Default.CloudDownload
}

@Composable
private fun subtitleOf(row: AutomationRow, now: Long): String {
    if (row.lastRunAt <= 0) return stringResource(R.string.automation_never)
    val ago = DateUtils.getRelativeTimeSpanString(row.lastRunAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    val skip = when (row.skip) {
        SkipReason.VPN_ACTIVE -> stringResource(R.string.automation_skip_vpn)
        SkipReason.FRESH -> stringResource(R.string.automation_skip_fresh)
        SkipReason.NOTHING_TO_DO -> stringResource(R.string.automation_skip_nothing)
        SkipReason.METERED -> stringResource(R.string.automation_skip_metered)
        SkipReason.NEEDS_TUNNEL -> stringResource(R.string.automation_skip_tunnel)
        SkipReason.NOT_THIS_TRIGGER, null -> null
    }
    val main = when (row.outcome) {
        TaskOutcome.FAILED -> stringResource(R.string.automation_failed, ago, row.detail.take(80))
        TaskOutcome.SKIPPED, null -> skip?.let { "$ago · $it" } ?: ago
        TaskOutcome.OK -> stringResource(R.string.automation_found, ago, row.found)
    }
    return if (row.outcome != TaskOutcome.SKIPPED && skip != null) "$main\n$skip" else main
}
