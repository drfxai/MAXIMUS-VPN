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
import androidx.compose.material.icons.rounded.Cancel
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
import androidx.compose.ui.graphics.Color
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
    /** DNS tunnel backup domains as typed (comma, space or line separated). */
    val backupDomains: String = "",
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
    val backups: List<String> get() = DnsDelegation.parseBackups(backupDomains)
    val checksPass: Boolean get() = checks != null && checks.none { it.blocking }
    val settingsValid: Boolean get() = when (tool.id) {
        ServerToolCatalog.DNSTT -> DnsDelegation.problem(baseDomain, tunnelLabel) == null &&
            DnsDelegation.backupProblem(baseDomain, backups) == null
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
        leading = { ToolBadge(ui.tool.id, 34.dp, accent = true) },
        bottomBar = if (ui.stage == WizardStage.SERVER) null else ({ WizardBottomBar(ui, actions) })
    ) {
        Stepper(stageIndex, failed = ui.stage == WizardStage.FAILED)
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

/** Numbered steps joined by lines: done steps show a tick, the current one is filled. */
@Composable
private fun Stepper(index: Int, failed: Boolean) {
    val names = listOf("Check", "Settings", "Review", "Install", "Done")
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        names.forEachIndexed { i, name ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(if (i == 0) Color.Transparent else if (i <= index) Sv.Accent else Sv.CardBorder))
                    val fill by animateColorAsState(
                        when {
                            failed && i == index -> Sv.Red
                            i < index -> Sv.Accent
                            i == index -> Sv.Accent
                            else -> Sv.Card
                        }, label = "step"
                    )
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(fill)
                            .border(1.dp, if (i > index) Sv.CardBorder else fill, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            failed && i == index -> Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(13.dp))
                            i < index -> Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                            else -> Text("${i + 1}", color = if (i == index) Color.White else Sv.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Box(Modifier.weight(1f).height(1.dp).background(if (i == names.lastIndex) Color.Transparent else if (i < index) Sv.Accent else Sv.CardBorder))
                }
                Spacer(Modifier.height(6.dp))
                Text(name, color = if (i == index) Sv.Text else Sv.Dim, fontSize = 11.sp,
                    fontWeight = if (i == index) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PickServer(ui: WizardUi, actions: WizardActions) {
    Text(ui.tool.description, color = Sv.Muted, fontSize = 14.sp, lineHeight = 20.sp)
    SectionLabel("Choose a server")
    if (ui.servers.isEmpty()) {
        Notice("Add a server first in the Servers tab.", NoticeKind.WARN)
        return
    }
    GroupCard {
        Rows(ui.servers, dividerStart = 50.dp) { row ->
            val fits = ui.tool.fitsOn(row.location)
            ListRow(
                row.name,
                subtitle = when {
                    !fits -> "${ui.tool.title} only helps on a server abroad"
                    row.health == Health.SIGN_IN -> "Sign in to this server first"
                    ui.tool.id in row.tools -> "Installed · tap to update"
                    else -> row.host
                },
                subtitleMono = fits && row.health != Health.SIGN_IN && ui.tool.id !in row.tools,
                leading = { FlagIcon(row.location, height = 16.dp) },
                trailing = { HealthLabel(row.health, row.latencyMs); Chevron() },
                enabled = fits && row.health != Health.SIGN_IN,
                onClick = { actions.onPickServer(row.id) }
            )
        }
    }
}

@Composable
private fun CheckStage(ui: WizardUi) {
    SectionLabel("Checking ${ui.server?.name.orEmpty()}")
    GroupCard {
        val checks = ui.checks
        if (checks == null) {
            repeat(5) {
                if (it > 0) RowDivider(46.dp)
                Row(Modifier.padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Shimmer(Modifier.size(18.dp))
                    Spacer(Modifier.width(14.dp))
                    Shimmer(Modifier.width((110 + it * 24).dp).height(10.dp))
                }
            }
        } else {
            Rows(checks, dividerStart = 46.dp) { c -> CheckRow(c) }
        }
    }
    if (ui.checks != null && !ui.checksPass) Notice("Fix the items marked red, then go back and check again.", NoticeKind.ERROR)
    if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
}

@Composable
private fun CheckRow(c: CheckItem) {
    val (icon, tint) = when {
        c.ok -> Icons.Rounded.CheckCircle to Sv.Green
        c.blocking -> Icons.Rounded.Cancel to Sv.Red
        else -> Icons.Rounded.WarningAmber to Sv.Amber
    }
    ListRow(
        c.label,
        subtitle = c.detail,
        leading = { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
    )
}

@Composable
private fun SettingsStage(ui: WizardUi, actions: WizardActions) {
    when (ui.tool.id) {
        ServerToolCatalog.DNSTT -> {
            SectionLabel("Domain")
            SvField(ui.baseDomain, { actions.onChange(ui.copy(baseDomain = it.trim(), dnsChecks = null)) }, "Your domain",
                placeholder = "example.com", keyboard = KeyboardType.Uri, mono = true,
                supporting = "Any domain you own. The tunnel uses one name under it.")
            SvField(ui.tunnelLabel, { actions.onChange(ui.copy(tunnelLabel = it.trim().lowercase().take(22), dnsChecks = null)) },
                "Tunnel name", mono = true, supporting = "Short is faster: every DNS query carries it.")
            SectionLabel("Backup domains")
            val backupProblem = DnsDelegation.backupProblem(ui.baseDomain, ui.backups)
            SvField(ui.backupDomains, { actions.onChange(ui.copy(backupDomains = it.take(300), dnsChecks = null)) }, "Backup domains (optional)",
                placeholder = "example.net, example.org", keyboard = KeyboardType.Uri, mono = true,
                supporting = backupProblem ?: "Other domains you own, up to ${DnsDelegation.MAX_BACKUPS}. If the main one is blocked, " +
                    "the app moves to the next without dropping the connection.")
            if (ui.records.isNotEmpty()) DnsRecordsCard(ui, actions)
        }
        ServerToolCatalog.HYSTERIA2 -> {
            SectionLabel("UDP port")
            SvField(ui.port, { actions.onChange(ui.copy(port = it.filter(Char::isDigit).take(5))) }, "Port",
                placeholder = "random", keyboard = KeyboardType.Number, mono = true,
                supporting = "A random high port is harder to block than 443. Empty keeps the current one.",
                trailing = {
                    androidx.compose.material3.IconButton(onClick = actions.onRandomPort) {
                        Icon(Icons.Rounded.Casino, "Random port", tint = Sv.Muted)
                    }
                })
        }
        else -> Notice("No settings needed. Safe defaults are used.", NoticeKind.OK)
    }
    if (ui.reinstall && ui.tool.id in setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.HYSTERIA2)) {
        GroupCard {
            ListRow(
                "Create new keys",
                subtitle = "Old configs stop working. The app updates its own.",
                trailing = {
                    Switch(ui.renew, { actions.onChange(ui.copy(renew = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Sv.Accent, uncheckedTrackColor = Sv.Raised, uncheckedBorderColor = Sv.CardBorder))
                }
            )
        }
    }
}

@Composable
private fun DnsRecordsCard(ui: WizardUi, actions: WizardActions) {
    SectionLabel("Add these records at your DNS provider")
    GroupCard {
        Rows(ui.records.withIndex().toList()) { (i, r) ->
            val check = ui.dnsChecks?.getOrNull(i)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(r.type, Tone.ACCENT)
                    Spacer(Modifier.weight(1f))
                    if (check != null) {
                        when (check.status) {
                            DnsDelegation.Status.OK -> Badge("Live", Tone.GOOD, dot = true)
                            DnsDelegation.Status.WRONG -> Badge("Wrong", Tone.BAD, dot = true)
                            DnsDelegation.Status.MISSING -> Badge("Not yet", Tone.WARN, dot = true)
                            DnsDelegation.Status.UNKNOWN -> Badge("Unknown", Tone.NEUTRAL, dot = true)
                        }
                    }
                }
                Text(r.note, color = Sv.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                CopyLine("Name", r.name) { actions.onCopy(r.name) }
                CopyLine("Value", r.value) { actions.onCopy(r.value) }
                if (check != null && check.status != DnsDelegation.Status.OK && check.detail.isNotBlank()) {
                    Text(check.detail, color = Sv.Muted, fontSize = 12.sp)
                }
            }
        }
    }
    GhostButton(if (ui.dnsChecking) "Checking…" else "Check DNS", actions.onCheckDns, Modifier.fillMaxWidth(),
        Icons.Rounded.NetworkCheck, enabled = !ui.dnsChecking)
    Text("You can install before the records are live. The tunnel starts working once they are.", color = Sv.Dim, fontSize = 12.sp)
}

@Composable
private fun CopyLine(label: String, value: String, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Sv.Inset).border(1.dp, Sv.Divider, RoundedCornerShape(8.dp))
            .clickable(onClick = onCopy).padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Sv.Dim, fontSize = 11.sp, modifier = Modifier.width(42.dp))
        Text(value, color = Sv.Text, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Rounded.ContentCopy, "Copy $label", tint = Sv.Muted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun SummaryStage(ui: WizardUi) {
    SectionLabel("Review")
    GroupCard {
        ui.server?.let { s ->
            ListRow(s.name, subtitle = s.host, subtitleMono = true, leading = { FlagIcon(s.location, height = 16.dp) })
            RowDivider(0.dp)
        }
        when (ui.tool.id) {
            ServerToolCatalog.DNSTT -> KeyValue("Tunnel name", DnsDelegation.tunnelDomain(ui.baseDomain, ui.tunnelLabel))
            ServerToolCatalog.HYSTERIA2 -> KeyValue("UDP port", ui.port.ifBlank { "random" })
        }
        if (ui.renew) KeyValue("Keys", "new")
    }
    SectionLabel("What changes on the server")
    GroupCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ui.tool.changes.forEach { line ->
                Row {
                    Icon(Icons.Rounded.Check, null, tint = Sv.Muted, modifier = Modifier.padding(top = 2.dp).size(15.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(line, color = Sv.TextSoft, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
    Notice("If the first install fails, its changes are undone. You can uninstall it any time from the server page.")
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, color = Sv.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Sv.Text, fontFamily = Mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RunningStage(ui: WizardUi) {
    SectionLabel(if (ui.stage == WizardStage.FAILED) "Stopped" else "Installing")
    GroupCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
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
    if (ui.stage == WizardStage.FAILED) Notice(ui.error.ifBlank { "The install stopped." }, NoticeKind.ERROR)
    if (ui.log.isNotEmpty()) LogCard(ui.log, initiallyOpen = ui.stage == WizardStage.FAILED)
}

/** state: 0 pending, 1 running, 2 done, 3 failed. */
@Composable
private fun StepRow(text: String, state: Int, last: Boolean) {
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(
                    when (state) { 2 -> Sv.Accent; 3 -> Sv.Red; else -> Sv.Card }
                ).border(1.dp, when (state) { 2 -> Sv.Accent; 3 -> Sv.Red; 1 -> Sv.Accent; else -> Sv.CardBorder }, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    2 -> Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    3 -> Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    1 -> CircularProgressIndicator(Modifier.size(12.dp), color = Sv.Blue, strokeWidth = 1.5.dp)
                }
            }
            if (!last) Box(Modifier.width(1.dp).height(20.dp).background(if (state == 2) Sv.Accent.copy(alpha = 0.6f) else Sv.CardBorder))
        }
        Spacer(Modifier.width(12.dp))
        val alpha by animateFloatAsState(if (state == 0) 0.55f else 1f, label = "step")
        Text(text, Modifier.padding(top = 1.dp).alpha(alpha),
            color = when (state) { 3 -> Sv.Red; 0 -> Sv.Muted; else -> Sv.Text },
            fontSize = 14.sp, fontWeight = if (state == 1) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
internal fun LogCard(lines: List<String>, initiallyOpen: Boolean = false, title: String = "Details") {
    var open by remember { mutableStateOf(initiallyOpen) }
    Surface(shape = RoundedCornerShape(12.dp), color = Sv.Terminal, border = BorderStroke(1.dp, Sv.CardBorder)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Sv.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(if (open) "Hide" else "Show ${lines.size} lines", color = Sv.Muted, fontSize = 12.sp)
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    lines.takeLast(40).forEach {
                        Text(it, color = TerminalText, fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}

private val TerminalText = Color(0xFF9AA6B8)

@Composable
private fun DoneStage(ui: WizardUi, actions: WizardActions) {
    GroupCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Sv.GreenSoft).border(1.dp, Sv.Green.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Sv.Green, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.summary.ifBlank { "${ui.tool.title} is installed" }, color = Sv.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                ui.server?.let { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        FlagIcon(s.location, height = 10.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(s.name, color = Sv.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    if (ui.profileName != null) {
        SectionLabel("Added to your configs")
        GroupCard {
            ListRow(ui.profileName, subtitle = "Ready in the server list", leading = { ToolBadge(ui.tool.id, 32.dp) },
                trailing = { if (ui.link != null) TextAction("Copy link", { actions.onCopy(ui.link) }, icon = Icons.Rounded.ContentCopy) })
            RowDivider(0.dp)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    ui.testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Sv.Blue, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Sending a real request through it…", color = Sv.Muted, fontSize = 13.sp)
                    }
                    ui.testResult != null -> Notice(ui.testResult, if (ui.testOk == true) NoticeKind.OK else NoticeKind.WARN)
                }
                GhostButton("Test from this phone", actions.onTest, Modifier.fillMaxWidth(), Icons.Rounded.NetworkCheck, enabled = !ui.testing, compact = true)
            }
        }
    }
    if (ui.tool.id == ServerToolCatalog.DNSTT && ui.records.isNotEmpty()) {
        Notice("The tunnel works once your DNS records are live. The first test can fail while DNS updates.")
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
            Modifier.fillMaxWidth())
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
