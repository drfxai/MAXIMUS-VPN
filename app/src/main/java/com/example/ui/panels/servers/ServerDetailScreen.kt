package com.example.ui.panels.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.servers.ServerToolCatalog

/** One installed tool on the server page. */
data class ToolRowUi(
    val id: String,
    val title: String,
    /** "Running", "Stopped", "On"... */
    val status: String,
    val healthy: Boolean,
    val detail: String = "",
    val canTest: Boolean = false,
    val canRestart: Boolean = false,
    val canLogs: Boolean = false,
    val canRotatePort: Boolean = false,
    val canRotateKey: Boolean = false,
    val canOpenPanel: Boolean = false,
    val canUninstall: Boolean = true,
    val canOpenTunnel: Boolean = false,
    val canBackup: Boolean = false,
    val canUpdate: Boolean = false,
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    /** Some services down while others run: an amber badge instead of red. */
    val warn: Boolean = false,
    /** Label -> value pairs under the row ("Services", "9 of 9 active"). */
    val stats: List<Pair<String, String>> = emptyList(),
    /** A problem worth reading before acting, shown under the row. */
    val warning: String? = null,
    val link: String? = null,
    val testResult: String? = null,
    val testOk: Boolean? = null
)

data class ServerDetailUi(
    val row: ServerRowUi,
    val sshPort: Int,
    val user: String,
    val checkedText: String = "",
    val checking: Boolean = false,
    val os: String = "",
    val cpus: Int = 0,
    val load: String = "",
    val ramPercent: Int = 0,
    val ramText: String = "",
    val diskPercent: Int = 0,
    val diskText: String = "",
    val uptime: String = "",
    val hasFacts: Boolean = false,
    val keyLogin: Boolean = false,
    val passwordLoginOff: Boolean = false,
    val needsSignIn: Boolean = false,
    val tools: List<ToolRowUi> = emptyList(),
    /** What a Hiddify found on the server but not added on this phone looks like ("Version 14.0.5 · 9 of 9 services active"). */
    val foundHiddify: String? = null,
    val advice: List<AdviceUi> = emptyList(),
    val busy: String = "",
    val error: String = "",
    val logsTitle: String = "",
    val logs: List<String>? = null
)

data class ServerDetailActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSignIn: () -> Unit,
    val onKeyLogin: () -> Unit,
    val onPasswordLogin: (off: Boolean) -> Unit,
    val onTest: (String) -> Unit,
    val onRestart: (String) -> Unit,
    val onLogs: (String) -> Unit,
    val onRotatePort: (String) -> Unit,
    val onRotateKey: (String) -> Unit,
    val onOpenPanel: (String) -> Unit,
    val onCopy: (String) -> Unit,
    val onUninstall: (String) -> Unit,
    val onOpenTunnel: () -> Unit = {},
    val onBackup: (String) -> Unit = {},
    val onUpdate: (String) -> Unit = {},
    val onStart: (String) -> Unit = {},
    val onStop: (String) -> Unit = {},
    val onImportHiddify: () -> Unit = {},
    val onQuickHiddify: () -> Unit = {},
    val onImportHiddifyConfigs: () -> Unit = {},
    val onHiddifyTelegram: () -> Unit = {},
    val onDismissFound: () -> Unit = {},
    val onInstall: (String) -> Unit,
    val onInstallCenter: () -> Unit,
    val onRemove: () -> Unit
)

@Composable
internal fun ServerDetailScreen(ui: ServerDetailUi, actions: ServerDetailActions) {
    val idle = ui.busy.isBlank()
    ScreenFrame(
        title = ui.row.name,
        subtitle = "${ui.user}@${ui.row.host}:${ui.sshPort}",
        subtitleMono = true,
        onBack = actions.onBack,
        leading = { FlagIcon(ui.row.location, height = 18.dp) },
        actions = {
            IconButton(onClick = actions.onRefresh, enabled = !ui.checking && idle && !ui.needsSignIn) {
                if (ui.checking) CircularProgressIndicator(Modifier.size(18.dp), color = Sv.Blue, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, "Check again", tint = Sv.TextSoft)
            }
        },
        bottomBar = if (!idle) ({
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Sv.Blue, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(ui.busy, color = Sv.TextSoft, fontSize = 13.sp)
            }
        }) else null
    ) {
        if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
        if (ui.needsSignIn) {
            Notice("This server was added before My Servers existed. Sign in once to manage it here.", NoticeKind.WARN)
            PrimaryButton("Sign in", actions.onSignIn, Modifier.fillMaxWidth())
        }
        OverviewCard(ui)
        if (ui.foundHiddify != null) FoundHiddifyCard(ui.foundHiddify, actions, idle)

        SectionLabel("Sign-in security")
        GroupCard {
            ListRow(
                "Key login",
                subtitle = if (ui.keyLogin) "This phone's key" else "Sign in with a key instead of a password",
                leading = { IconTile(Icons.Rounded.Key, 32.dp) },
                trailing = {
                    if (ui.keyLogin) Badge("On", Tone.GOOD, dot = true)
                    else if (!ui.needsSignIn) TextAction("Set up", actions.onKeyLogin, enabled = idle)
                    else Badge("Off")
                }
            )
            RowDivider(58.dp)
            ListRow(
                "Password login",
                subtitle = when {
                    ui.passwordLoginOff -> "Only keys can sign in"
                    ui.keyLogin -> "Turn off to stop password guessing"
                    else -> "Needs key login first"
                },
                leading = { IconTile(Icons.Rounded.Lock, 32.dp) },
                trailing = {
                    when {
                        ui.needsSignIn -> Unit
                        ui.passwordLoginOff -> TextAction("Turn on", { actions.onPasswordLogin(false) }, Sv.Muted, enabled = idle)
                        ui.keyLogin -> TextAction("Turn off", { actions.onPasswordLogin(true) }, Sv.Amber, enabled = idle)
                        else -> Badge("On", Tone.WARN, dot = true)
                    }
                }
            )
        }

        SectionLabel("Installed") {
            TextAction("Add tool", actions.onInstallCenter)
        }
        GroupCard {
            if (ui.tools.isEmpty()) {
                ListRow("Nothing installed yet", subtitle = "Add a panel, a tunnel or protection from the Install tab.")
            } else {
                Rows(ui.tools) { ToolBlock(it, actions, idle) }
            }
        }

        // Expose Hiddify's setup entry points even when its admin URL was not captured during install.
        // Quick creation and Telegram use the reviewed v14 API; domain management opens the official UI.
        if (ui.tools.any { it.id == ServerToolCatalog.HIDDIFY }) {
            SectionLabel("Hiddify management")
            GroupCard {
                ListRow("Remove Hiddify", subtitle = "Stop the panel or safely recover incomplete files; confirmation required",
                    trailing = { TextAction("Remove", { actions.onUninstall(ServerToolCatalog.HIDDIFY) }, enabled = idle) })
                RowDivider(0.dp)
                ListRow(
                    "Open admin panel",
                    subtitle = "Recover your secure admin link over SSH if needed",
                    leading = { ToolBadge(ServerToolCatalog.HIDDIFY, 32.dp) },
                    trailing = { TextAction("Open", { actions.onOpenPanel(ServerToolCatalog.HIDDIFY) }, enabled = idle) }
                )
                RowDivider(0.dp)
                ListRow(
                    "Quick create config",
                    subtitle = "Create a user with a quota and expiry, then import its subscription into Nodes",
                    trailing = { TextAction("Create", actions.onQuickHiddify, enabled = idle) }
                )
                RowDivider(0.dp)
                ListRow("Import saved configs", subtitle = "Refresh the last user subscription without creating another user",
                    trailing = { TextAction("Import", actions.onImportHiddifyConfigs, enabled = idle) })
                RowDivider(0.dp)
                ListRow(
                    "CDN & domains",
                    subtitle = "Open Admin V2, then Settings > Domains to configure a CDN domain",
                    trailing = { TextAction("Manage", { actions.onOpenPanel(ServerToolCatalog.HIDDIFY) }, enabled = idle) }
                )
                RowDivider(0.dp)
                ListRow(
                    "Telegram proxy",
                    subtitle = "Read MTProto or Web proxy links from your user account; CDN compatibility depends on the proxy",
                    trailing = { TextAction("Get links", actions.onHiddifyTelegram, enabled = idle) }
                )
            }
        }

        if (ui.logs != null) LogCard(ui.logs.ifEmpty { listOf("No log lines yet.") }, initiallyOpen = true, title = ui.logsTitle)

        if (ui.advice.isNotEmpty()) {
            SectionLabel("Suggested")
            GroupCard { Rows(ui.advice, dividerStart = 62.dp) { a -> AdviceRow(a) { actions.onInstall(a.toolId) } } }
        }

        SectionLabel("This phone")
        GroupCard {
            ListRow(
                "Forget this server",
                subtitle = "Removes its sign-in from this phone. Nothing on the server changes.",
                titleColor = Sv.Red,
                onClick = actions.onRemove
            )
        }
    }
}

/** Status line plus a four-column strip: processor, memory, disk, uptime. */
@Composable
private fun OverviewCard(ui: ServerDetailUi) {
    GroupCard {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ui.row.health, 8.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (ui.row.health) {
                        Health.ONLINE -> "Online" + (ui.row.latencyMs?.let { " · $it ms" } ?: "")
                        Health.OFFLINE -> "Not reachable"
                        Health.CHECKING -> "Checking"
                        Health.SIGN_IN -> "Needs sign-in"
                    },
                    color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
                val line = listOf(ui.os, locationName(ui.row.location)).filter { it.isNotBlank() }.joinToString(" · ")
                if (line.isNotBlank()) Text(line, color = Sv.Muted, fontSize = 13.sp)
            }
            if (ui.checkedText.isNotBlank()) Text(ui.checkedText, color = Sv.Dim, fontSize = 12.sp)
        }
        if (ui.hasFacts || ui.checking) {
            RowDivider(0.dp)
            val loading = ui.checking && !ui.hasFacts
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Metric("CPU", if (ui.cpus > 0) "${ui.cpus} cores" else "—", "load ${ui.load.ifBlank { "—" }}", null, loading, Modifier.weight(1f))
                VDivider()
                Metric("Memory", "${ui.ramPercent}%", ui.ramText, ui.ramPercent, loading, Modifier.weight(1f))
                VDivider()
                Metric("Disk", "${ui.diskPercent}%", ui.diskText, ui.diskPercent, loading, Modifier.weight(1f))
                VDivider()
                Metric("Uptime", ui.uptime.ifBlank { "—" }, "", null, loading, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(Sv.Divider))

@Composable
private fun Metric(label: String, value: String, sub: String, percent: Int?, loading: Boolean, modifier: Modifier) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Sv.Dim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        if (loading) {
            Shimmer(Modifier.width(44.dp).height(14.dp)); Shimmer(Modifier.width(56.dp).height(8.dp))
        } else {
            Text(value, color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (percent != null) Meter(percent)
            if (sub.isNotBlank()) Text(sub, color = Sv.Muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** [menu] keeps an action in the ••• menu even when there is room in the bar. */
private data class ToolAction(val label: String, val icon: ImageVector, val color: Color = Sv.TextSoft, val menu: Boolean = false, val run: () -> Unit)

/** An installed tool: one row, an optional test result, and a compact action bar with an overflow menu. */
@Composable
private fun ToolBlock(t: ToolRowUi, actions: ServerDetailActions, idle: Boolean) {
    Column {
        ListRow(
            t.title,
            subtitle = t.detail,
            subtitleMono = t.detail.any(Char::isDigit),
            leading = { ToolBadge(t.id) },
            trailing = { Badge(t.status, if (t.healthy) Tone.GOOD else if (t.warn) Tone.WARN else Tone.BAD, dot = true) }
        )
        if (t.stats.isNotEmpty()) {
            Row(Modifier.padding(start = 62.dp, end = 14.dp, bottom = 6.dp)) {
                t.stats.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, color = Sv.Muted, fontSize = 12.sp)
                        Text(v, color = Sv.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        if (t.warning != null) {
            Box(Modifier.padding(start = 62.dp, end = 14.dp, bottom = 8.dp)) { Notice(t.warning, NoticeKind.WARN) }
        }
        if (t.testResult != null) {
            Box(Modifier.padding(start = 62.dp, end = 14.dp, bottom = 8.dp)) {
                Notice(t.testResult, if (t.testOk == true) NoticeKind.OK else NoticeKind.WARN)
            }
        }
        val all = buildList {
            if (t.canTest) add(ToolAction("Test", Icons.Rounded.NetworkCheck) { actions.onTest(t.id) })
            if (t.canOpenPanel) add(ToolAction("Open panel", Icons.AutoMirrored.Rounded.OpenInNew) { actions.onOpenPanel(t.id) })
            if (t.canOpenTunnel) add(ToolAction("Open tunnel", Icons.AutoMirrored.Rounded.OpenInNew) { actions.onOpenTunnel() })
            if (t.link != null) add(ToolAction("Copy link", Icons.Rounded.ContentCopy) { actions.onCopy(t.link) })
            if (t.canLogs && !t.healthy) add(ToolAction("Diagnostics", Icons.AutoMirrored.Rounded.Subject) { actions.onLogs(t.id) })
            // With services down, fixing comes before backing up or updating.
            // 3X-UI keeps its bar; its backup sits in the menu.
            if (t.canBackup && t.warning == null) add(ToolAction("Back up", Icons.Rounded.Download, menu = t.id == ServerToolCatalog.XUI) { actions.onBackup(t.id) })
            if (t.canRestart) add(ToolAction("Restart", Icons.Rounded.RestartAlt) { actions.onRestart(t.id) })
            if (t.canLogs && t.healthy && t.warning != null) add(ToolAction("Logs", Icons.AutoMirrored.Rounded.Subject) { actions.onLogs(t.id) })
            if (t.canUpdate) add(ToolAction("Update", Icons.Rounded.Upgrade) { actions.onUpdate(t.id) })
            if (t.canBackup && t.warning != null) add(ToolAction("Back up", Icons.Rounded.Download) { actions.onBackup(t.id) })
            if (t.canLogs && t.healthy && t.warning == null) add(ToolAction("Logs", Icons.AutoMirrored.Rounded.Subject) { actions.onLogs(t.id) })
            if (t.canStart) add(ToolAction("Start", Icons.Rounded.PlayArrow) { actions.onStart(t.id) })
            if (t.canStop) add(ToolAction("Stop", Icons.Rounded.Stop) { actions.onStop(t.id) })
            if (t.canRotatePort) add(ToolAction("New port", Icons.Rounded.SwapHoriz) { actions.onRotatePort(t.id) })
            if (t.canRotateKey) add(ToolAction("New keys", Icons.Rounded.VpnKey) { actions.onRotateKey(t.id) })
            if (t.canUninstall) add(ToolAction("Uninstall", Icons.Rounded.Delete, Sv.Red) { actions.onUninstall(t.id) })
        }
        if (all.isEmpty()) return@Column
        val shown = all.filter { it.color != Sv.Red && !it.menu }.take(3)
        val more = all - shown.toSet()
        Row(Modifier.fillMaxWidth().padding(start = 54.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            shown.forEach { a -> TextAction(a.label, a.run, Sv.Blue, enabled = idle, icon = a.icon) }
            Spacer(Modifier.weight(1f))
            if (more.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }, enabled = idle, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.MoreHoriz, "More", tint = Sv.Muted)
                    }
                    DropdownMenu(open, { open = false }, modifier = Modifier.background(Sv.Raised)) {
                        more.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.label, color = if (a.color == Sv.Red) Sv.Red else Sv.Text, fontSize = 14.sp) },
                                leadingIcon = { Icon(a.icon, null, tint = a.color, modifier = Modifier.size(18.dp)) },
                                onClick = { open = false; a.run() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A Hiddify that is on the server but not added on this phone. Adding it changes nothing there. */
@Composable
private fun FoundHiddifyCard(summary: String, actions: ServerDetailActions, idle: Boolean) {
    GroupCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolBadge(ServerToolCatalog.HIDDIFY, 34.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hiddify files detected", color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(summary, color = Sv.Muted, fontSize = 13.sp)
                }
            }
            Notice("A folder alone does not prove a complete installation. Add it for diagnostics, inspect logs, or remove an incomplete installation. Adding changes nothing on the server.", NoticeKind.WARN)
            PrimaryButton("Add Hiddify", actions.onImportHiddify, Modifier.fillMaxWidth(), enabled = idle)
            GhostButton("Diagnostics", { actions.onLogs(ServerToolCatalog.HIDDIFY) }, Modifier.fillMaxWidth(), enabled = idle)
            GhostButton("Remove Hiddify", { actions.onUninstall(ServerToolCatalog.HIDDIFY) }, Modifier.fillMaxWidth(), enabled = idle)
            GhostButton("Not now", actions.onDismissFound, Modifier.fillMaxWidth())
        }
    }
}

internal fun toolTitle(id: String): String = ServerToolCatalog.byId(id)?.title ?: id
