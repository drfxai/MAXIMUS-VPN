package com.example.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AppSettings
import com.example.ui.theme.AppTheme

private data class HubEntry(val page: SettingsPage, val title: String, val icon: ImageVector)

private val networkEntries = listOf(
    HubEntry(SettingsPage.CONNECTION, "Connection", Icons.Default.Hub),
    HubEntry(SettingsPage.ROUTING_DNS, "Routing & DNS", Icons.Default.Route),
    HubEntry(SettingsPage.ANTI_CENSORSHIP, "Anti-censorship", Icons.Default.VpnLock),
    HubEntry(SettingsPage.PRIVACY, "Privacy & security", Icons.Default.Security),
    HubEntry(SettingsPage.SHARING, "Sharing", Icons.Default.WifiTethering)
)

/**
 * The Settings hub: a compact header and grouped category rows. Each row opens its own page; the
 * subtitle shows the current value so most people never need to open it.
 */
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
        Text(
            "Settings",
            color = AppTheme.colors.textPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 16.dp)
        )

        HubHeader(versionName = versionName, update = update, onUpdateChip = onUpdateChip)

        SettingsSectionLabel("Network")
        SettingsGroup {
            networkEntries.forEachIndexed { index, entry ->
                if (index > 0) SettingsDivider()
                SettingsNavRow(
                    title = entry.title,
                    subtitle = SettingsLabels.hub(settings, entry.page, update, versionName),
                    icon = entry.icon,
                    modifier = Modifier.testTag("settings_" + entry.page.name.lowercase()),
                    onClick = { onOpen(entry.page) }
                )
            }
        }

        SettingsSectionLabel("App")
        SettingsGroup {
            SettingsNavRow(
                title = "AI agent",
                subtitle = "Gemini co-pilot for log diagnosis and tuning",
                icon = Icons.Default.AutoAwesome,
                modifier = Modifier.testTag("settings_ai_agent"),
                onClick = onOpenAiAgent
            )
            SettingsDivider()
            SettingsNavRow(
                title = "Appearance",
                subtitle = SettingsLabels.hub(settings, SettingsPage.APPEARANCE, update, versionName),
                icon = Icons.Default.Palette,
                modifier = Modifier.testTag("settings_appearance"),
                onClick = { onOpen(SettingsPage.APPEARANCE) }
            )
            SettingsDivider()
            SettingsNavRow(
                title = "Updates & data",
                subtitle = SettingsLabels.hub(settings, SettingsPage.UPDATES, update, versionName),
                icon = Icons.Default.SystemUpdate,
                modifier = Modifier.testTag("settings_updates"),
                onClick = { onOpen(SettingsPage.UPDATES) }
            )
            SettingsDivider()
            SettingsNavRow(
                title = "Diagnostics",
                subtitle = SettingsLabels.hub(settings, SettingsPage.DIAGNOSTICS, update, versionName),
                icon = Icons.Default.Terminal,
                modifier = Modifier.testTag("settings_diagnostics"),
                onClick = { onOpen(SettingsPage.DIAGNOSTICS) }
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            SettingsNavRow(
                title = "About & community",
                subtitle = SettingsLabels.hub(settings, SettingsPage.ABOUT, update, versionName),
                icon = Icons.Default.Info,
                modifier = Modifier.testTag("settings_about"),
                onClick = { onOpen(SettingsPage.ABOUT) }
            )
        }
    }
}

@Composable
private fun HubHeader(versionName: String, update: UpdateUi, onUpdateChip: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsGroupShape,
        color = AppTheme.colors.surfaceCard,
        border = if (AppTheme.colors.isDark) null else androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppLogo(size = 48)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Maximus VPN", color = AppTheme.colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("Version $versionName", color = AppTheme.colors.textMuted, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            UpdateChip(update, onUpdateChip)
        }
    }
}

@Composable
internal fun UpdateChip(update: UpdateUi, onClick: () -> Unit) {
    val (label, color) = when {
        update.checking -> "Checking…" to AppTheme.colors.textSecondary
        update.hasUpdate -> "Update ready" to AppTheme.colors.statusWarning
        update.checked -> "Up to date" to AppTheme.colors.statusConnected
        else -> "Check" to AppTheme.colors.textSecondary
    }
    SettingsStatusChip(label = label, color = color, onClick = onClick, modifier = Modifier.testTag("settings_update_chip"))
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
