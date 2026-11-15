package com.example.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.ui.theme.AppTheme
import com.example.vpn.routing.InstalledApp
import com.example.vpn.routing.InstalledApps
import com.example.vpn.routing.IranianApps
import com.example.vpn.routing.SplitTunnel
import com.example.vpn.routing.SplitTunnelMode
import com.example.vpn.routing.SplitTunnelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-app routing picker. Layout and loading strategy follow WhiteAestherMobile's SplitTunnelScreen
 * (WhiteDNS/WhiteAestherMobile 6e2ade9e86c5, AGPL-3.0): a lazy list, app labels read off the main thread,
 * icons fetched per visible row. Saved immediately; applies the next time the tunnel is built.
 */
@Composable
fun SplitTunnelPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val self = context.packageName
    val stored by SplitTunnelStore.flow(context).collectAsState()
    val rules = stored ?: SplitTunnel()
    val update: (SplitTunnel) -> Unit = { SplitTunnelStore.save(context, it) }
    val listing = rules.mode != SplitTunnelMode.ALL

    val apps by produceState(initialValue = null as List<InstalledApp>?, listing) {
        if (!listing) return@produceState
        value = withContext(Dispatchers.IO) { InstalledApps.launchable(context).filterNot { it.packageName == self } }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(apps, query) {
        val all = apps.orEmpty()
        if (query.isBlank()) all else all.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Per-app routing", subtitle = rules.summary(), onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().testTag("split_app_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
        ) {
            item(key = "modes") {
                SettingsSectionLabel("Which apps use the tunnel", first = true)
                SettingsGroup {
                    SplitTunnelMode.entries.forEachIndexed { index, mode ->
                        if (index > 0) SettingsDivider(inset = false)
                        SettingsChoiceRow(
                            title = mode.title,
                            subtitle = mode.description,
                            selected = rules.mode == mode,
                            onSelect = { update(rules.copy(mode = mode)) }
                        )
                    }
                }
                SettingsFootnote("Changes apply the next time you connect or reconnect.")
            }

            if (!listing) return@LazyColumn

            item(key = "tools") {
                rules.validationError(self)?.let { SettingsFootnote(it, color = AppTheme.colors.statusWarning) }
                if (rules.mode == SplitTunnelMode.EXCEPT) {
                    Spacer(Modifier.height(12.dp))
                    SettingsGroup {
                        SettingsNavRow(
                            title = "Leave Iranian apps outside",
                            subtitle = "Selects installed banking, payment, operator and local apps. Review before connecting.",
                            icon = Icons.Default.AutoAwesome,
                            modifier = Modifier.testTag("split_suggest_iranian"),
                            enabled = apps != null,
                            trailing = {},
                            onClick = {
                                val found = IranianApps.suggest(apps.orEmpty())
                                update(rules.copy(packages = rules.packages + found))
                            }
                        )
                    }
                }
                SettingsSectionLabel(if (rules.mode == SplitTunnelMode.ONLY) "Apps to send through the tunnel" else "Apps to leave outside")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("split_search"),
                    placeholder = { Text("Search apps", color = AppTheme.colors.textMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = settingsFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                when {
                    apps == null -> SettingsFootnote("Reading the apps on this phone…")
                    visible.isEmpty() -> SettingsFootnote(
                        if (apps.orEmpty().isEmpty()) "This device reports no apps to list." else "Nothing matches \"$query\"."
                    )
                }
            }

            items(visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = app.packageName in rules.packages,
                    onToggle = { on ->
                        update(rules.copy(packages = if (on) rules.packages + app.packageName else rules.packages - app.packageName))
                    }
                )
            }

            item(key = "footer") {
                SettingsFootnote(
                    "Maximus itself is never in this list: its own traffic is routed by the engine. " +
                        "Apps left outside use your normal connection and are not protected."
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            InstalledApps.icon(context, app.packageName)?.let { drawable ->
                runCatching { drawable.toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("split_app_${app.packageName}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AppTheme.colors.surfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = icon
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(28.dp))
            } else {
                Text(app.label.take(1).uppercase(), color = AppTheme.colors.textSecondary, fontSize = 15.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(app.label, color = AppTheme.colors.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (app.system) "System app" else app.packageName,
                color = AppTheme.colors.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = AppTheme.colors.primary)
        )
    }
}

/** Drawn at 28dp; 96 px covers the densest screens. */
private const val ICON_PX = 96
