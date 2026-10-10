package com.example.ui.panels.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.servers.CheckItem
import com.example.panels.servers.DnsDelegation
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.ServerTool
import com.example.panels.servers.ServerToolCatalog

enum class WizardStage(val title: String) {
    SERVER("Server"), CHECK("Check"), SETTINGS("Settings"), SUMMARY("Summary"), RUNNING("Install"), DONE("Done"), FAILED("Install")
}

/** Everything the install wizard shows. */
data class WizardUi(
    val tool: ServerTool,
    val servers: List<ServerRowUi>,
    val serverId: String? = null,
    val stage: WizardStage = WizardStage.SERVER,
    /** null while the check runs. */
    val checks: List<CheckItem>? = null,
    val reinstall: Boolean = false,
    val baseDomain: String = "",
    val tunnelLabel: String = "t",
    val port: String = "",
    val renew: Boolean = false,
    val records: List<DnsDelegation.Record> = emptyList(),
    val dnsChecks: List<DnsDelegation.Check>? = null,
    val dnsChecking: Boolean = false,
    val steps: List<String> = emptyList(),
    val currentStep: Int = 0,
    val log: List<String> = emptyList(),
    val summary: String = "",
    val link: String? = null,
    val profileName: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val error: String = ""
) {
    val server: ServerRowUi? get() = servers.firstOrNull { it.id == serverId }
    val checksPass: Boolean get() = checks != null && checks.none { it.blocking }
    val settingsValid: Boolean get() = when (tool.id) {
        ServerToolCatalog.DNSTT -> DnsDelegation.problem(baseDomain, tunnelLabel) == null
        ServerToolCatalog.HYSTERIA2 -> port.isBlank() || port.toIntOrNull() in 1..65535
        else -> true
    }
}

data class WizardActions(
    val onBack: () -> Unit,
    val onPickServer: (String) -> Unit,
    val onChange: (WizardUi) -> Unit,
    val onNext: () -> Unit,
    val onRandomPort: () -> Unit,
    val onCheckDns: () -> Unit,
    val onInstall: () -> Unit,
    val onTest: () -> Unit,
    val onCopy: (String) -> Unit,
    val onOpenServer: () -> Unit,
    val onDone: () -> Unit,
    val onRetry: () -> Unit
)

@Composable
internal fun InstallWizardScreen(ui: WizardUi, actions: WizardActions) {
    val stageIndex = when (ui.stage) {
        WizardStage.SERVER -> 0
        WizardStage.CHECK -> 0
        WizardStage.SETTINGS -> 1
        WizardStage.SUMMARY -> 2
        WizardStage.RUNNING, WizardStage.FAILED -> 3
        WizardStage.DONE -> 4
    }
    ScreenFrame(
        title = (if (ui.reinstall) "Update " else "Install ") + ui.tool.title,
        subtitle = ui.server?.let { "on ${it.name}" } ?: ui.tool.tagline,
        onBack = actions.onBack,
        leading = { ToolBadge(ui.tool.id, 36.dp) },
        bottomBar = { WizardBottomBar(ui, actions) }
    ) {
        StageBar(stageIndex, failed = ui.stage == WizardStage.FAILED)
        when (ui.stage) {
            WizardStage.SERVER -> PickServer(ui, actions)
            WizardStage.CHECK -> CheckStage(ui)
            WizardStage.SETTINGS -> SettingsStage(ui, actions)
            WizardStage.SUMMARY -> SummaryStage(ui)
            WizardStage.RUNNING, WizardStage.FAILED -> RunningStage(ui)
            WizardStage.DONE -> DoneStage(ui, actions)
        }
    }
}

@Composable
private fun StageBar(index: Int, failed: Boolean) {
    val names = listOf("Check", "Settings", "Summary", "Install", "Done")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            names.forEachIndexed { i, _ ->
                val color by animateColorAsState(
                    when {
                        failed && i == index -> Sv.Red
                        i < index -> Sv.Green
                        i == index -> Sv.Accent
                        else -> Sv.CardBorder
                    }, label = "stage"
                )
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
            }
        }
        Row {
            names.forEachIndexed { i, n ->
                Text(n, Modifier.weight(1f), color = if (i == index) Sv.Text else Sv.Dim, fontSize = 10.sp,
                    fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PickServer(ui: WizardUi, actions: WizardActions) {
    Text(ui.tool.description, color = Sv.Muted, fontSize = 13.sp, lineHeight = 18.sp)
    SectionLabel("Choose a server")
    if (ui.servers.isEmpty()) Notice("Add a server first (Panels › My Servers › Add server).", NoticeKind.WARN)
    ui.servers.forEach { row ->
        val fits = ui.tool.fitsOn(row.location)
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(enabled = fits && row.health != Health.SIGN_IN) { actions.onPickServer(row.id) },
            shape = RoundedCornerShape(14.dp), color = Sv.Card, border = BorderStroke(1.dp, Sv.CardBorder)
        ) {
            Row(Modifier.padding(14.dp).alpha(if (fits) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
                FlagIcon(row.location, height = 18.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.name, color = Sv.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            !fits -> "${ui.tool.title} only helps on a server abroad"
                            row.health == Health.SIGN_IN -> "Sign in to this server first"
                            ui.tool.id in row.tools -> "Installed · tap to update"
                            else -> row.host
                        },
                        color = Sv.Dim, fontSize = 12.sp
                    )
                }
                StatusDot(row.health)
            }
        }
    }
}

@Composable
private fun CheckStage(ui: WizardUi) {
    SvCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Checking ${ui.server?.name.orEmpty()}", color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val checks = ui.checks
            if (checks == null) {
                repeat(4) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Shimmer(Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Shimmer(Modifier.width((120 + it * 30).dp).height(10.dp))
                    }
                }
            } else {
                checks.forEach { c -> CheckRow(c) }
            }
        }
    }
    if (ui.checks != null && !ui.checksPass) Notice("Fix the items marked red, then check again.", NoticeKind.ERROR)
    if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
}

@Composable
private fun CheckRow(c: CheckItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = when {
            c.ok -> Icons.Rounded.CheckCircle to Sv.Green
            c.blocking -> Icons.Rounded.Close to Sv.Red
            else -> Icons.Rounded.WarningAmber to Sv.Amber
        }
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.label, color = Sv.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (c.detail.isNotBlank()) Text(c.detail, color = Sv.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsStage(ui: WizardUi, actions: WizardActions) {
    when (ui.tool.id) {
        ServerToolCatalog.DNSTT -> {
            SvCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your domain", color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Any domain you own works. The tunnel uses one name under it.", color = Sv.Muted, fontSize = 12.sp)
                    SvField(ui.baseDomain, { actions.onChange(ui.copy(baseDomain = it.trim(), dnsChecks = null)) }, "Domain",
                        placeholder = "example.com", keyboard = KeyboardType.Uri)
                    SvField(ui.tunnelLabel, { actions.onChange(ui.copy(tunnelLabel = it.trim().lowercase().take(22), dnsChecks = null)) },
                        "Tunnel name", supporting = "Short is faster: every query carries it.")
                }
            }
            if (ui.records.isNotEmpty()) DnsRecordsCard(ui, actions)
        }
        ServerToolCatalog.HYSTERIA2 -> SvCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("UDP port", color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("A random high port is harder to block than 443. Leave it empty to keep the current one.", color = Sv.Muted, fontSize = 12.sp)
                SvField(ui.port, { actions.onChange(ui.copy(port = it.filter(Char::isDigit).take(5))) }, "Port",
                    placeholder = "random", keyboard = KeyboardType.Number,
                    trailing = {
                        androidx.compose.material3.IconButton(onClick = actions.onRandomPort) {
                            Icon(Icons.Rounded.Casino, "Random port", tint = Sv.Blue)
                        }
                    })
            }
        }
        else -> Notice("No settings needed: safe defaults are used.", NoticeKind.OK)
    }
    if (ui.reinstall && ui.tool.id in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.HYSTERIA2)) {
        SvCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Create new keys", color = Sv.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Old configs stop working; the app updates its own.", color = Sv.Muted, fontSize = 12.sp)
                }
                Switch(ui.renew, { actions.onChange(ui.copy(renew = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Sv.Accent))
            }
        }
    }
}

@Composable
private fun DnsRecordsCard(ui: WizardUi, actions: WizardActions) {
    SvCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add these 2 records at your DNS provider", color = Sv.Text, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            ui.records.forEachIndexed { i, r ->
                val check = ui.dnsChecks?.getOrNull(i)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sv.Inset).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chip(r.type, Sv.Blue, Sv.AccentSoft)
                        Spacer(Modifier.width(8.dp))
                        Text(r.name, color = Sv.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ContentCopy, "Copy", tint = Sv.Dim, modifier = Modifier.size(16.dp).clickable { actions.onCopy(r.name) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("→ ", color = Sv.Dim, fontSize = 12.sp)
                        Text(r.value, color = Sv.Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ContentCopy, "Copy", tint = Sv.Dim, modifier = Modifier.size(16.dp).clickable { actions.onCopy(r.value) })
                    }
                    Text(r.note, color = Sv.Dim, fontSize = 11.sp)
                    if (check != null) {
                        val (color, label) = when (check.status) {
                            DnsDelegation.Status.OK -> Sv.Green to "✓ ${check.detail}"
                            DnsDelegation.Status.WRONG -> Sv.Red to "✗ ${check.detail}"
                            DnsDelegation.Status.MISSING -> Sv.Amber to "… ${check.detail}"
                            DnsDelegation.Status.UNKNOWN -> Sv.Muted to "? ${check.detail}"
                        }
                        Text(label, color = color, fontSize = 12.sp)
                    }
                }
            }
            GhostButton(if (ui.dnsChecking) "Checking…" else "Check DNS", actions.onCheckDns, Modifier.fillMaxWidth(),
                Icons.Rounded.NetworkCheck, enabled = !ui.dnsChecking)
            Text("You can install before the records are ready; the tunnel starts working once they are.", color = Sv.Dim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SummaryStage(ui: WizardUi) {
    SvCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.server?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlagIcon(s.location, height = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(s.name, color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(s.host, color = Sv.Dim, fontSize = 12.sp)
                    }
                }
            }
            Text("What will change on the server", color = Sv.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            ui.tool.changes.forEach { line ->
                Row {
                    Box(Modifier.padding(top = 6.dp).size(5.dp).clip(CircleShape).background(Sv.Blue))
                    Spacer(Modifier.width(10.dp))
                    Text(line, color = Sv.Text, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            when (ui.tool.id) {
                ServerToolCatalog.DNSTT -> Text("Tunnel name: ${DnsDelegation.tunnelDomain(ui.baseDomain, ui.tunnelLabel)}", color = Sv.Blue, fontSize = 12.sp)
                ServerToolCatalog.HYSTERIA2 -> Text("UDP port: ${ui.port.ifBlank { "random" }}", color = Sv.Blue, fontSize = 12.sp)
            }
        }
    }
    Notice("If the first install fails, it undoes its own changes. You can uninstall it any time from the server page.")
}

@Composable
private fun RunningStage(ui: WizardUi) {
    SvCard {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            ui.steps.forEachIndexed { i, step ->
                val n = i + 1
                val state = when {
                    ui.stage == WizardStage.FAILED && n == ui.currentStep.coerceAtLeast(1) -> 3
                    n < ui.currentStep -> 2
                    n == ui.currentStep -> 1
                    else -> 0
                }
                StepRow(step, state, last = i == ui.steps.lastIndex)
            }
        }
    }
    if (ui.stage == WizardStage.FAILED) {
        Notice(ui.error.ifBlank { "The install stopped." }, NoticeKind.ERROR)
    }
    if (ui.log.isNotEmpty()) LogCard(ui.log, initiallyOpen = ui.stage == WizardStage.FAILED)
}

/** state: 0 pending, 1 running, 2 done, 3 failed. */
@Composable
private fun StepRow(text: String, state: Int, last: Boolean) {
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(
                    when (state) { 2 -> Sv.GreenSoft; 3 -> Sv.RedSoft; 1 -> Sv.AccentSoft; else -> Sv.Inset }
                ).border(1.dp, when (state) { 2 -> Sv.Green; 3 -> Sv.Red; 1 -> Sv.Accent; else -> Sv.CardBorder }, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    2 -> Icon(Icons.Rounded.Check, null, tint = Sv.Green, modifier = Modifier.size(14.dp))
                    3 -> Icon(Icons.Rounded.Close, null, tint = Sv.Red, modifier = Modifier.size(14.dp))
                    1 -> CircularProgressIndicator(Modifier.size(14.dp), color = Sv.Blue, strokeWidth = 2.dp)
                }
            }
            if (!last) Box(Modifier.width(2.dp).height(18.dp).background(if (state == 2) Sv.Green.copy(alpha = 0.5f) else Sv.CardBorder))
        }
        Spacer(Modifier.width(12.dp))
        val alpha by animateFloatAsState(if (state == 0) 0.5f else 1f, label = "step")
        Text(text, Modifier.padding(top = 3.dp).alpha(alpha),
            color = when (state) { 3 -> Sv.Red; 0 -> Sv.Muted; else -> Sv.Text },
            fontSize = 14.sp, fontWeight = if (state == 1) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
internal fun LogCard(lines: List<String>, initiallyOpen: Boolean = false, title: String = "Details") {
    var open by remember { mutableStateOf(initiallyOpen) }
    Surface(shape = RoundedCornerShape(14.dp), color = Sv.Terminal, border = BorderStroke(1.dp, Sv.CardBorder)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Sv.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (open) "Hide" else "Show (${lines.size})", color = Sv.Blue, fontSize = 12.sp)
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    lines.takeLast(40).forEach {
                        Text(it, color = Color8.Terminal, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

private object Color8 { val Terminal = androidx.compose.ui.graphics.Color(0xFF9FB3C8) }

@Composable
private fun DoneStage(ui: WizardUi, actions: WizardActions) {
    SvCard(padding = 20.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Sv.GreenSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Sv.Green, modifier = Modifier.size(32.dp))
            }
            Text(ui.summary.ifBlank { "${ui.tool.title} is installed" }, color = Sv.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            ui.server?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlagIcon(s.location, height = 12.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(s.name, color = Sv.Muted, fontSize = 12.sp)
                }
            }
        }
    }
    if (ui.profileName != null) {
        SvCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Added to your configs", color = Sv.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(ui.profileName, color = Sv.Muted, fontSize = 12.sp)
                when {
                    ui.testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Sv.Blue, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Sending a real request through it…", color = Sv.Muted, fontSize = 12.sp)
                    }
                    ui.testResult != null -> Notice(ui.testResult, if (ui.testOk == true) NoticeKind.OK else NoticeKind.WARN)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Test from this phone", actions.onTest, Modifier.weight(1f), Icons.Rounded.NetworkCheck, enabled = !ui.testing)
                    if (ui.link != null) GhostButton("Copy link", { actions.onCopy(ui.link) }, Modifier.weight(0.7f), Icons.Rounded.ContentCopy)
                }
            }
        }
    }
    if (ui.tool.id == ServerToolCatalog.DNSTT && ui.records.isNotEmpty()) {
        Notice("Remember the two DNS records (${ui.records.joinToString(" and ") { it.type }}). The tunnel works once they are live; " +
            "the first test can fail while DNS updates.", NoticeKind.INFO)
    }
    if (ui.log.isNotEmpty()) LogCard(ui.log)
}

@Composable
private fun WizardBottomBar(ui: WizardUi, actions: WizardActions) {
    when (ui.stage) {
        WizardStage.SERVER -> Unit
        WizardStage.CHECK -> PrimaryButton(if (ui.checks == null) "Checking…" else "Continue", actions.onNext, Modifier.fillMaxWidth(),
            enabled = ui.checksPass)
        WizardStage.SETTINGS -> PrimaryButton("Continue", actions.onNext, Modifier.fillMaxWidth(), enabled = ui.settingsValid)
        WizardStage.SUMMARY -> PrimaryButton(if (ui.reinstall) "Update now" else "Install now", actions.onInstall,
            Modifier.fillMaxWidth(), color = Sv.Success)
        WizardStage.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Sv.Blue, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(ui.steps.getOrNull(ui.currentStep - 1)?.let { "$it…" } ?: "Connecting…", color = Sv.Muted, fontSize = 13.sp)
        }
        WizardStage.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Close", actions.onDone, Modifier.weight(1f))
            PrimaryButton("Try again", actions.onRetry, Modifier.weight(1f), Icons.Rounded.Refresh)
        }
        WizardStage.DONE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Open server", actions.onOpenServer, Modifier.weight(1f))
            PrimaryButton("Done", actions.onDone, Modifier.weight(1f))
        }
    }
}
