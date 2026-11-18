package com.example.ui.settings

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

private data class HubEntry(val page: SettingsPage, val title: String, val icon: ImageVector)

private val connectionEntry = HubEntry(SettingsPage.CONNECTION, "Connection", Icons.Default.Hub)
private val routingEntry = HubEntry(SettingsPage.ROUTING_DNS, "Routing & DNS", Icons.Default.Route)

private val protectionEntries = listOf(
    HubEntry(SettingsPage.PRIVACY, "Privacy & security", Icons.Default.Security),
    HubEntry(SettingsPage.ANTI_CENSORSHIP, "Anti-censorship", Icons.Default.VpnLock)
)
private val appEntries = listOf(
    HubEntry(SettingsPage.APPEARANCE, "Appearance", Icons.Default.Palette),
    HubEntry(SettingsPage.UPDATES, "Updates & data", Icons.Default.SystemUpdate),
    HubEntry(SettingsPage.DIAGNOSTICS, "Diagnostics", Icons.Default.Terminal)
)
private val aboutEntry = HubEntry(SettingsPage.ABOUT, "About & community", Icons.Default.Info)
private val sharingEntry = HubEntry(SettingsPage.SHARING, "Sharing", Icons.Default.WifiTethering)

@Composable
fun SettingsHub(
    settings: AppSettings,
    versionName: String,
    update: UpdateUi,
    onOpen: (SettingsPage) -> Unit,
    onOpenAiAgent: () -> Unit,
    onUpdateChip: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsPageColumn(modifier.testTag("settings_screen")) {
        Spacer(Modifier.height(16.dp))
        HubHeader(versionName, update, onUpdateChip)

        SettingsSectionLabel("Network")
        val connectionSummary = SettingsLabels.hub(settings, connectionEntry.page, update, versionName)
        val routingSummary = SettingsLabels.hub(settings, routingEntry.page, update, versionName)
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
            HubRow(sharingEntry, SettingsLabels.hub(settings, sharingEntry.page, update, versionName), onOpen)
        }

        SettingsSectionLabel("Protection")
        SettingsGroup {
            protectionEntries.forEachIndexed { index, entry ->
                if (index > 0) SettingsDivider()
                HubRow(entry, SettingsLabels.hub(settings, entry.page, update, versionName), onOpen)
            }
        }

        SettingsSectionLabel("App & tools")
        SettingsGroup {
            appEntries.forEachIndexed { index, entry ->
                if (index > 0) SettingsDivider()
                HubRow(entry, SettingsLabels.hub(settings, entry.page, update, versionName), onOpen)
            }
            SettingsDivider()
            SettingsNavRow(
                title = "AI agent",
                subtitle = "Gemini for log diagnosis and tuning",
                icon = Icons.Default.AutoAwesome,
                modifier = Modifier.testTag("settings_ai_agent"),
                onClick = onOpenAiAgent
            )
            SettingsDivider()
            HubRow(aboutEntry, SettingsLabels.hub(settings, aboutEntry.page, update, versionName), onOpen)
        }
    }
}

@Composable
private fun HubRow(entry: HubEntry, summary: String, onOpen: (SettingsPage) -> Unit) {
    SettingsNavRow(
        title = entry.title,
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
            Text(entry.title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                    Text("Settings", color = colors.textPrimary, fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text("Maximus VPN · v$versionName", color = colors.textSecondary, fontSize = 13.sp)
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
        update.checking -> "Checking…" to colors.textSecondary
        update.hasUpdate -> "Update ready" to if (colors.isDark) colors.statusWarning else Color(0xFF92400E)
        update.checkFailed -> "Retry update check" to colors.textSecondary
        update.checked -> "Up to date" to if (colors.isDark) colors.statusConnected else Color(0xFF065F46)
        else -> "Check for updates" to colors.textSecondary
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
