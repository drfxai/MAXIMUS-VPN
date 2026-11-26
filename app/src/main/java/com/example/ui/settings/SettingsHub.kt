package com.example.ui.settings

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AppSettings
import com.example.ui.theme.AppTheme

private data class HubEntry(val page: SettingsPage, @androidx.annotation.StringRes val title: Int, val icon: ImageVector)

private val connectionEntry = HubEntry(SettingsPage.CONNECTION, R.string.ui_connection, Icons.Default.Hub)
private val routingEntry = HubEntry(SettingsPage.ROUTING_DNS, R.string.ui_routing_dns, Icons.Default.Route)

private val protectionEntries = listOf(
    HubEntry(SettingsPage.PRIVACY, R.string.ui_privacy_security, Icons.Default.Security),
    HubEntry(SettingsPage.ANTI_CENSORSHIP, R.string.ui_anti_censorship, Icons.Default.VpnLock)
)
private val appEntries = listOf(
    HubEntry(SettingsPage.APPEARANCE, R.string.ui_appearance, Icons.Default.Palette),
    HubEntry(SettingsPage.UPDATES, R.string.ui_app_updates, Icons.Default.SystemUpdate),
    HubEntry(SettingsPage.ENGINES, R.string.ui_engine_updates, Icons.Default.Memory),
    HubEntry(SettingsPage.DIAGNOSTICS, R.string.ui_diagnostics, Icons.Default.Terminal)
)
private val aboutEntry = HubEntry(SettingsPage.ABOUT, R.string.ui_about_community, Icons.Default.Info)
private val sharingEntry = HubEntry(SettingsPage.SHARING, R.string.ui_sharing, Icons.Default.WifiTethering)

@Composable
fun SettingsHub(
    settings: AppSettings,
    versionName: String,
    update: UpdateUi,
    onOpen: (SettingsPage) -> Unit,
    onOpenAiAgent: () -> Unit,
    onUpdateChip: () -> Unit,
    onFreeConfigsChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // B19: subtitles from string resources in the app's language.
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val hub = { s: AppSettings, page: SettingsPage, u: UpdateUi, v: String ->
        SettingsLabels.hubText(s, page, u, v) { id, args -> resources.getString(id, *args) }
    }
    SettingsPageColumn(modifier.testTag("settings_screen")) {
        Spacer(Modifier.height(16.dp))
        HubHeader(versionName, update, onUpdateChip)
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.ui_free_configs_2),
                subtitle = stringResource(R.string.ui_show_free_maximus_servers_in),
                icon = Icons.Default.Public,
                checked = settings.freeConfigsEnabled,
                onCheckedChange = onFreeConfigsChanged,
                modifier = Modifier.testTag("settings_free_configs")
            )
        }

        SettingsSectionLabel(stringResource(R.string.ui_network_2))
        val connectionSummary = hub(settings, connectionEntry.page, update, versionName)
        val routingSummary = hub(settings, routingEntry.page, update, versionName)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 346.dp && LocalDensity.current.fontScale <= 1.15f) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeaturedSetting(connectionEntry, connectionSummary, AppTheme.colors.primary,
                        Modifier.weight(1f).fillMaxHeight(), onOpen)
                    FeaturedSetting(routingEntry, routingSummary, AppTheme.colors.metricDownload,
                        Modifier.weight(1f).fillMaxHeight(), onOpen)
                }
            } else {
                SettingsGroup {
                    HubRow(connectionEntry, connectionSummary, onOpen)
                    SettingsDivider()
                    HubRow(routingEntry, routingSummary, onOpen)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            HubRow(sharingEntry, hub(settings, sharingEntry.page, update, versionName), onOpen)
        }

        SettingsSectionLabel(stringResource(R.string.ui_protection))
        SettingsGroup {
            protectionEntries.forEachIndexed { index, entry ->
                if (index > 0) SettingsDivider()
                HubRow(entry, hub(settings, entry.page, update, versionName), onOpen)
            }
        }

        SettingsSectionLabel(stringResource(R.string.ui_app_tools))
        SettingsGroup {
            appEntries.forEachIndexed { index, entry ->
                if (index > 0) SettingsDivider()
                HubRow(entry, hub(settings, entry.page, update, versionName), onOpen)
            }
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.ui_ai_agent_2),
                subtitle = stringResource(R.string.ui_gemini_for_log_diagnosis_and),
                icon = Icons.Default.AutoAwesome,
                modifier = Modifier.testTag("settings_ai_agent"),
                onClick = onOpenAiAgent
            )
            SettingsDivider()
            HubRow(aboutEntry, hub(settings, aboutEntry.page, update, versionName), onOpen)
        }
    }
}

@Composable
private fun HubRow(entry: HubEntry, summary: String, onOpen: (SettingsPage) -> Unit) {
    SettingsNavRow(
        title = stringResource(entry.title),
        subtitle = summary,
        icon = entry.icon,
        modifier = Modifier.testTag("settings_" + entry.page.name.lowercase()),
        onClick = { onOpen(entry.page) }
    )
}

@Composable
private fun FeaturedSetting(
    entry: HubEntry,
    summary: String,
    accent: Color,
    modifier: Modifier,
    onOpen: (SettingsPage) -> Unit
) {
    val colors = AppTheme.colors
    Surface(
        modifier = modifier,
        shape = SettingsGroupShape,
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Column(
            Modifier
                .testTag("settings_" + entry.page.name.lowercase())
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.08f), Color.Transparent)))
                .clickable(role = Role.Button) { onOpen(entry.page) }
                .padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                SettingsIconTile(entry.icon, accent)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = colors.textSecondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(entry.title), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(summary, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun HubHeader(versionName: String, update: UpdateUi, onUpdateChip: () -> Unit) {
    val colors = AppTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.26f))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(colors.primary.copy(alpha = if (colors.isDark) 0.19f else 0.10f), Color.Transparent)
                    )
                )
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(size = 46)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_settings), color = colors.textPrimary, fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.ui_maximus_vpn_v, versionName), color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            UpdateChip(update, onUpdateChip)
        }
    }
}

@Composable
internal fun UpdateChip(update: UpdateUi, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val (label, color) = when {
        update.checking -> stringResource(R.string.settings_chip_checking) to colors.textSecondary
        update.hasUpdate -> stringResource(R.string.settings_chip_update_ready) to if (colors.isDark) colors.statusWarning else Color(0xFF92400E)
        update.checkFailed -> stringResource(R.string.settings_chip_retry) to colors.textSecondary
        update.checked -> stringResource(R.string.settings_chip_up_to_date) to if (colors.isDark) colors.statusConnected else Color(0xFF065F46)
        else -> stringResource(R.string.settings_chip_check) to colors.textSecondary
    }
    Box(
        modifier = Modifier
            .testTag("settings_update_chip")
            .clip(RoundedCornerShape(50))
            .heightIn(min = 48.dp)
            .clickable(enabled = !update.checking, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SettingsStatusChip(label = label, color = color)
    }
}

@Composable
internal fun AppLogo(size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_maximus_logo),
            contentDescription = "Maximus VPN logo",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
