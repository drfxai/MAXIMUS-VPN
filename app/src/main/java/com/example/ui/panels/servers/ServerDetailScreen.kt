package com.example.ui.panels.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val onInstall: (String) -> Unit,
    val onInstallCenter: () -> Unit,
    val onRemove: () -> Unit
)

@Composable
internal fun ServerDetailScreen(ui: ServerDetailUi, actions: ServerDetailActions) {
    ScreenFrame(
        title = ui.row.name,
        subtitle = "${ui.user}@${ui.row.host}:${ui.sshPort}",
        onBack = actions.onBack,
        leading = { FlagIcon(ui.row.location, height = 20.dp) },
        bottomBar = if (ui.busy.isNotBlank()) ({
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Sv.Blue, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(ui.busy, color = Sv.Muted, fontSize = 13.sp)
            }
        }) else null
    ) {
        HeaderCard(ui, actions)
        if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
        if (ui.needsSignIn) {
            Notice("This server was added before My Servers existed. Sign in once to manage it here.", NoticeKind.WARN)
            PrimaryButton("Sign in", actions.onSignIn, Modifier.fillMaxWidth())
        }
        if (ui.hasFacts || ui.checking) HealthGrid(ui)
        SecurityCard(ui, actions)

        SectionLabel("Installed") {
            Text("+ Install", color = Sv.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = actions.onInstallCenter))
        }
        if (ui.tools.isEmpty()) {
            Surface(shape = RoundedCornerShape(14.dp), color = Sv.Inset, border = BorderStroke(1.dp, Sv.CardBorder)) {
                Text("Nothing installed yet. Open the Install Center to add a panel, a tunnel or protection.",
                    color = Sv.Dim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(14.dp))
            }
        }
        ui.tools.forEach { ToolCard(it, actions) }

        if (ui.logs != null) LogCard(ui.logs.ifEmpty { listOf("No log lines yet.") }, initiallyOpen = true, title = ui.logsTitle)

        if (ui.advice.isNotEmpty()) {
            SectionLabel("Suggested")
            ui.advice.forEach { a -> AdviceCard(a) { actions.onInstall(a.toolId) } }
        }

        SectionLabel("This phone")
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = actions.onRemove),
            shape = RoundedCornerShape(14.dp), color = Sv.Card, border = BorderStroke(1.dp, Sv.CardBorder)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Remove from this phone", color = Sv.Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Forgets the server and its sign-in. Nothing on the server changes.", color = Sv.Dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HeaderCard(ui: ServerDetailUi, actions: ServerDetailActions) {
    SvCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(ui.row.health)
                    Text(
                        when (ui.row.health) {
                            Health.ONLINE -> "Online" + (ui.row.latencyMs?.let { " · $it ms" } ?: "")
                            Health.OFFLINE -> "Not reachable"
                            Health.CHECKING -> "Checking"
                            Health.SIGN_IN -> "Needs sign-in"
                        },
                        color = Sv.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Text(listOf(ui.os, if (ui.row.location == com.example.panels.servers.ServerLocation.IRAN) "In Iran" else "Abroad")
                    .filter { it.isNotBlank() }.joinToString(" · "), color = Sv.Muted, fontSize = 12.sp)
                if (ui.checkedText.isNotBlank()) Text(ui.checkedText, color = Sv.Dim, fontSize = 11.sp)
            }
            IconButton(onClick = actions.onRefresh, enabled = !ui.checking && ui.busy.isBlank()) {
                if (ui.checking) CircularProgressIndicator(Modifier.size(18.dp), color = Sv.Blue, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, "Check again", tint = Sv.Blue)
            }
        }
    }
}

@Composable
private fun HealthGrid(ui: ServerDetailUi) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Processor", if (ui.cpus > 0) "${ui.cpus} cores" else "", "load ${ui.load}", null, ui.checking && !ui.hasFacts, Modifier.weight(1f))
            Tile("Uptime", ui.uptime, "since last reboot", null, ui.checking && !ui.hasFacts, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Memory", "${ui.ramPercent}%", ui.ramText, ui.ramPercent, ui.checking && !ui.hasFacts, Modifier.weight(1f))
            Tile("Disk", "${ui.diskPercent}%", ui.diskText, ui.diskPercent, ui.checking && !ui.hasFacts, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String, percent: Int?, loading: Boolean, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Sv.Card, border = BorderStroke(1.dp, Sv.CardBorder)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = Sv.Dim, fontSize = 11.sp)
            if (loading) {
                Shimmer(Modifier.width(60.dp).height(16.dp)); Shimmer(Modifier.width(90.dp).height(8.dp))
            } else {
                Text(value.ifBlank { "—" }, color = Sv.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (percent != null) Meter(percent)
                Text(sub, color = Sv.Muted, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SecurityCard(ui: ServerDetailUi, actions: ServerDetailActions) {
    SvCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = if (ui.passwordLoginOff) Sv.Green else Sv.Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign-in security", color = Sv.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            SecurityLine(Icons.Rounded.Key, "Key login", if (ui.keyLogin) "On · this phone's key" else "Off", ui.keyLogin)
            SecurityLine(Icons.Rounded.LockOpen, "Password login", if (ui.passwordLoginOff) "Off" else "On", ui.passwordLoginOff)
            when {
                ui.needsSignIn -> Unit
                !ui.keyLogin -> PrimaryButton("Add key login", actions.onKeyLogin, Modifier.fillMaxWidth(), Icons.Rounded.Key, enabled = ui.busy.isBlank())
                !ui.passwordLoginOff -> {
                    Text("Key login works. Turning password login off stops password guessing for good; only this phone can sign in.",
                        color = Sv.Muted, fontSize = 12.sp)
                    PrimaryButton("Turn password login off", { actions.onPasswordLogin(true) }, Modifier.fillMaxWidth(), enabled = ui.busy.isBlank())
                }
                else -> SmallAction("Turn password login back on", { actions.onPasswordLogin(false) }, Sv.Muted, enabled = ui.busy.isBlank())
            }
        }
    }
}

@Composable
private fun SecurityLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, good: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Sv.Dim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Sv.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (good) Sv.Green else Sv.Amber, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolCard(t: ToolRowUi, actions: ServerDetailActions) {
    SvCard(padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolBadge(t.id, 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Sv.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (t.detail.isNotBlank()) Text(t.detail, color = Sv.Dim, fontSize = 12.sp, maxLines = 1)
                }
                Chip(t.status, if (t.healthy) Sv.Green else Sv.Red, if (t.healthy) Sv.GreenSoft else Sv.RedSoft)
            }
            if (t.testResult != null) Notice(t.testResult, if (t.testOk == true) NoticeKind.OK else NoticeKind.WARN)
            val items = buildList<Pair<String, () -> Unit>> {
                if (t.canTest) add("Test" to { actions.onTest(t.id) })
                if (t.canOpenPanel) add("Open panel" to { actions.onOpenPanel(t.id) })
                if (t.link != null) add("Copy link" to { actions.onCopy(t.link) })
                if (t.canRestart) add("Restart" to { actions.onRestart(t.id) })
                if (t.canLogs) add("Logs" to { actions.onLogs(t.id) })
                if (t.canRotatePort) add("New port" to { actions.onRotatePort(t.id) })
                if (t.canRotateKey) add("New key" to { actions.onRotateKey(t.id) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { (label, onClick) -> SmallAction(label, onClick) }
                if (t.canUninstall) SmallAction("Uninstall", { actions.onUninstall(t.id) }, Sv.Red)
            }
        }
    }
}

internal fun toolTitle(id: String): String = ServerToolCatalog.byId(id)?.title ?: id
