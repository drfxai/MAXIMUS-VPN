package com.example.ui.protocols

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.ManagedPanel
import com.example.vpn.lab.LabFamily
import com.example.vpn.lab.LabPriority
import com.example.vpn.lab.LabResult

/** Everything the Protocols screen can ask for; the screen itself holds no state. */
class ProtocolsActions(
    val onBack: () -> Unit = {},
    val onRun: () -> Unit = {},
    val onPriority: (LabPriority) -> Unit = {},
    val onSelect: (LabFamily) -> Unit = {},
    val onOpenSetup: () -> Unit = {},
    val onOpenCustomize: () -> Unit = {},
    val onPickSource: () -> Unit = {},
    val onAddServer: () -> Unit = {},
    val onAutoFailover: (Boolean) -> Unit = {},
    val onDismissError: () -> Unit = {}
)

// ---------------------------------------------------------------- building blocks

@Composable
internal fun LabText(
    text: String, color: Color, size: TextUnit, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier,
    letterSpacing: TextUnit = 0.sp, maxLines: Int = 2, lineHeight: TextUnit = TextUnit.Unspecified
) = Text(
    text, modifier = modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    style = TextStyle(fontFamily = InterFamily, fontSize = size, fontWeight = weight, letterSpacing = letterSpacing, lineHeight = lineHeight)
)

internal fun Modifier.labCard(c: LabColors, radius: Dp = 20.dp) =
    this.clip(RoundedCornerShape(radius)).background(c.card).border(1.dp, c.stroke, RoundedCornerShape(radius))

private fun accentBrush(c: LabColors) = Brush.linearGradient(listOf(c.accent, c.accent2))

internal fun LabFamily.icon(): ImageVector = when (this) {
    LabFamily.REALITY -> Icons.Rounded.Shield
    LabFamily.XHTTP -> Icons.Rounded.Speed
    LabFamily.HYSTERIA2 -> Icons.Rounded.Bolt
    LabFamily.WIREGUARD -> Icons.Rounded.VpnKey
    LabFamily.HTTPUPGRADE -> Icons.Rounded.SyncAlt
    LabFamily.WEBSOCKET -> Icons.Rounded.Language
    LabFamily.CDN -> Icons.Rounded.Cloud
    LabFamily.OTHER -> Icons.Rounded.Dns
}

internal fun LabFamily.tint(c: LabColors): Color = when (this) {
    LabFamily.HYSTERIA2 -> c.okay
    LabFamily.XHTTP, LabFamily.REALITY -> c.accent
    LabFamily.WEBSOCKET, LabFamily.HTTPUPGRADE -> c.info
    LabFamily.WIREGUARD, LabFamily.CDN -> c.good
    LabFamily.OTHER -> c.text2
}

/** Short name shown in tiles and rows. */
internal val LabFamily.shortName: String get() = if (this == LabFamily.XHTTP) "XHTTP" else displayName

internal val LabFamily.network: String get() = if (this == LabFamily.HYSTERIA2 || this == LabFamily.WIREGUARD) "UDP" else "TCP"

internal val LabFamily.blurb: String get() = when (this) {
    LabFamily.REALITY -> "Looks like a real website"
    LabFamily.XHTTP -> "REALITY · split HTTP"
    LabFamily.HYSTERIA2 -> "QUIC · fast on lossy links"
    LabFamily.WIREGUARD -> "Classic VPN tunnel"
    LabFamily.HTTPUPGRADE -> "CDN ready · lightweight"
    LabFamily.WEBSOCKET -> "CDN ready"
    LabFamily.CDN -> "TLS through Cloudflare"
    LabFamily.OTHER -> "Plain TCP"
}

internal fun scoreColor(c: LabColors, score: Int) = when {
    score >= 75 -> c.good
    score >= 50 -> c.okay
    else -> c.bad
}

internal fun scoreWord(score: Int) = when {
    score >= 85 -> "Excellent"
    score >= 70 -> "Good"
    score >= 50 -> "Fair"
    else -> "Weak"
}

@Composable
private fun Gauge(c: LabColors, value: Int?, size: Dp, label: String, sub: String) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = size.toPx() * 0.075f
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            drawArc(
                c.text3.copy(alpha = if (c.dark) 0.18f else 0.14f), 135f, 270f, false, Offset(inset, inset), arcSize,
                style = Stroke(sw, cap = StrokeCap.Round)
            )
            if (value != null && value > 0) drawArc(
                Brush.sweepGradient(listOf(c.accent2, c.accent, c.good, c.accent2)), 135f, 270f * value.coerceAtMost(100) / 100f, false,
                Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LabText(label, c.text, (size.value * 0.28f).sp, FontWeight.Bold, letterSpacing = (-1).sp, maxLines = 1)
            LabText(sub, c.text2, 11.sp, FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
internal fun GradientButton(c: LabColors, text: String, icon: ImageVector, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) accentBrush(c) else Brush.linearGradient(listOf(c.cardAlt, c.cardAlt)))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) c.onAccent else c.text3, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        LabText(text, if (enabled) c.onAccent else c.text3, 16.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, color: Color, filled: Boolean = true) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (filled) color.copy(alpha = 0.14f) else Color.Transparent)
            .border(if (filled) 0.dp else 1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) { LabText(text, color, 10.5.sp, FontWeight.SemiBold, letterSpacing = 0.3.sp, maxLines = 1) }
}

@Composable
internal fun IconTile(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun SectionLabel(c: LabColors, text: String, trailing: String = "", onTrailing: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(text.uppercase(), c.text3, 11.5.sp, FontWeight.SemiBold, Modifier.weight(1f), letterSpacing = 0.9.sp, maxLines = 1)
        if (trailing.isNotEmpty()) LabText(trailing, c.accent, 12.5.sp, FontWeight.SemiBold, Modifier.clickable(onClick = onTrailing), maxLines = 1)
    }
}

@Composable
private fun Segmented(c: LabColors, selected: LabPriority, onSelect: (LabPriority) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.cardAlt).padding(4.dp)) {
        LabPriority.values().forEach { p ->
            val on = p == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) c.card else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, c.stroke, RoundedCornerShape(11.dp)) else Modifier)
                    .clickable { onSelect(p) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { LabText(p.title, if (on) c.text else c.text2, 13.sp, if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1) }
        }
    }
}

@Composable
private fun SignalBars(c: LabColors, level: Int, color: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..4).forEach { i ->
            Box(
                Modifier.width(4.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.5.dp))
                    .background(if (i <= level) color else c.text3.copy(alpha = if (c.dark) 0.25f else 0.2f))
            )
        }
    }
}

@Composable
internal fun LabSwitch(c: LabColors, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked, onChange,
    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent)
)

@Composable
internal fun ToggleRow(c: LabColors, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LabText(title, c.text, 14.sp, FontWeight.SemiBold, maxLines = 1)
            LabText(sub, c.text2, 12.sp, maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        LabSwitch(c, on, onChange)
    }
}

@Composable
private fun Stat(c: LabColors, label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.cardAlt).padding(horizontal = 12.dp, vertical = 10.dp)) {
        LabText(label, c.text3, 11.sp, FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        LabText(value, c.text, 16.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Divider(c: LabColors, start: Dp) = Box(Modifier.padding(start = start).fillMaxWidth().height(1.dp).background(c.divider))

// ---------------------------------------------------------------- screen

/** Where the test runs: the chosen server, or the saved configs when there is none. */
internal fun sourceName(state: ProtocolsUiState) = state.panel?.let { it.name.ifBlank { it.host } } ?: "Saved configs"

@Composable
fun ProtocolsContent(state: ProtocolsUiState, actions: ProtocolsActions, relativeTime: (Long) -> String = { "" }) {
    val c = labColors
    val showDock = state.phase is LabPhase.Done && state.best != null
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(
            Modifier.fillMaxWidth().height(420.dp).background(
                Brush.radialGradient(
                    listOf(c.glow.copy(alpha = if (c.dark) 0.55f else 0.7f), Color.Transparent),
                    center = Offset(900f, -60f), radius = 1300f
                )
            )
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (showDock) 170.dp else 28.dp)) {
            item { Header(c, state, actions, relativeTime) }
            if (state.error.isNotBlank()) item { ErrorBanner(c, state.error, actions.onDismissError) }
            when (val phase = state.phase) {
                is LabPhase.Running -> item { RunningCard(c, state, phase) }
                LabPhase.Done -> if (state.best != null) item { BestCard(c, state) } else item { NothingWorkedCard(c, state, actions) }
                LabPhase.Idle -> item { StartCard(c, state, actions) }
            }
            if (state.phase is LabPhase.Done && state.results.isNotEmpty()) {
                item { SectionLabel(c, "Ranking", "Retest", actions.onRun) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp).labCard(c, 20.dp)) {
                        state.results.forEachIndexed { i, r ->
                            RankRow(c, i + 1, r, r.works && r === state.chosen) { if (r.works) actions.onSelect(r.family) }
                            if (i < state.results.lastIndex) Divider(c, 70.dp)
                        }
                    }
                }
            } else {
                item { SectionLabel(c, "Supported protocols", if (state.panel != null && state.phase !is LabPhase.Running) "Customize" else "", actions.onOpenCustomize) }
                item { Catalog(c, state, actions) }
            }
        }
        if (showDock) ActionDock(c, state, actions, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Header(c: LabColors, state: ProtocolsUiState, actions: ProtocolsActions, relativeTime: (Long) -> String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = actions.onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            LabText("Protocols", c.text, 30.sp, FontWeight.Bold, letterSpacing = (-0.6).sp, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            val sub = when {
                state.phase is LabPhase.Running -> "Testing your network"
                state.phase is LabPhase.Done && state.testedAt > 0 -> "Tested ${relativeTime(state.testedAt)}"
                else -> "Test your network"
            }
            LabText(sub, c.text2, 13.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.clip(CircleShape).background(c.cardAlt).border(1.dp, c.stroke, CircleShape)
                .clickable(enabled = state.phase !is LabPhase.Running, onClick = actions.onPickSource)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.panel != null) Box(Modifier.size(7.dp).clip(CircleShape).background(c.good))
            else Icon(Icons.Rounded.Folder, null, tint = c.text2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            LabText(sourceName(state), c.text, 12.sp, FontWeight.SemiBold, Modifier.padding(end = 2.dp).widthIn(max = 110.dp), maxLines = 1)
            Icon(Icons.Rounded.ExpandMore, null, tint = c.text2, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ErrorBanner(c: LabColors, message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(c.bad.copy(alpha = 0.12f)).border(1.dp, c.bad.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = c.bad, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        LabText(message, c.text, 13.sp, FontWeight.Medium, Modifier.weight(1f), maxLines = 4, lineHeight = 18.sp)
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Dismiss", tint = c.text2, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StartCard(c: LabColors, state: ProtocolsUiState, actions: ProtocolsActions) {
    val count = if (state.panel != null) state.families.size else LabFamily.values().size - 1
    Column(Modifier.padding(horizontal = 16.dp).labCard(c, 24.dp).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Gauge(c, null, 96.dp, "$count", "protocols")
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                LabText("Network check", c.text, 19.sp, FontWeight.Bold, letterSpacing = (-0.3).sp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                val body = if (state.panel != null) {
                    "We set up a test connection for each protocol on your server and rank the ones that work best here."
                } else {
                    "We test the configs saved in the app, grouped by protocol, and rank the ones that work best here."
                }
                LabText(body, c.text2, 13.sp, lineHeight = 18.sp, maxLines = 4)
            }
        }
        Spacer(Modifier.height(18.dp))
        Segmented(c, state.priority, actions.onPriority)
        Spacer(Modifier.height(14.dp))
        GradientButton(c, "Run network test", Icons.Rounded.PlayArrow, onClick = actions.onRun)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val third = if (state.panel != null) Icons.Rounded.CleaningServices to "Auto cleanup" else Icons.Rounded.Cloud to "No server needed"
            listOf(Icons.Rounded.Timer to "About 1 min", Icons.Rounded.Repeat to "3 rounds", third).forEach { (icon, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = c.text3, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    LabText(label, c.text2, 12.sp, FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RunningCard(c: LabColors, state: ProtocolsUiState, phase: LabPhase.Running) {
    val percent = if (phase.total <= 0) 0 else (phase.done * 100 / phase.total).coerceIn(2, 100)
    Column(Modifier.padding(horizontal = 16.dp).labCard(c, 24.dp).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Gauge(c, percent, 112.dp, "$percent%", "testing")
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                LabText("IN PROGRESS", c.accent, 11.sp, FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                LabText(phase.step, c.text, 17.sp, FontWeight.SemiBold, maxLines = 3, lineHeight = 22.sp)
                Spacer(Modifier.height(4.dp))
                LabText("Keep this screen open. Each protocol is tried 3 times with real requests.", c.text2, 13.sp, lineHeight = 18.sp, maxLines = 3)
            }
        }
        Spacer(Modifier.height(16.dp))
        GradientButton(c, "Testing…", Icons.Rounded.Timer, enabled = false) {}
    }
}

@Composable
private fun BestCard(c: LabColors, state: ProtocolsUiState) {
    val r = state.chosen ?: return
    val blockedNames = state.blocked.map { it.family.shortName }
    val summary = buildString {
        append("${state.working.size} of ${state.results.size} protocols reachable.")
        when (blockedNames.size) {
            0 -> append(" Nothing is blocked on this network.")
            1 -> append(" ${blockedNames[0]} is blocked on this network.")
            else -> append(" ${blockedNames.dropLast(1).joinToString()} and ${blockedNames.last()} are blocked here.")
        }
    }
    Column(Modifier.padding(horizontal = 16.dp).labCard(c, 24.dp).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Gauge(c, r.score, 112.dp, "${r.score}", scoreWord(r.score))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                LabText(if (r === state.best) "BEST MATCH" else "YOUR CHOICE", c.accent, 11.sp, FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                LabText(r.family.shortName, c.text, 24.sp, FontWeight.Bold, letterSpacing = (-0.5).sp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                LabText(summary, c.text2, 13.sp, lineHeight = 18.sp, maxLines = 3)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(c, "Latency", r.medianMs?.let { "$it ms" } ?: "–", Modifier.weight(1f))
            Stat(c, "Jitter", r.jitterMs?.let { "±$it ms" } ?: "–", Modifier.weight(1f))
            Stat(c, "Success", "${r.successes} / ${r.latenciesMs.size}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun NothingWorkedCard(c: LabColors, state: ProtocolsUiState, actions: ProtocolsActions) {
    Column(Modifier.padding(horizontal = 16.dp).labCard(c, 24.dp).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Block, c.bad, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                LabText("Nothing got through", c.text, 19.sp, FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                val hint = if (state.serverRun) "Your network blocks every protocol tested on this server. Try a CDN config or another server."
                else "None of your saved configs answered. Add a server or a fresh subscription, then test again."
                LabText(hint, c.text2, 13.sp, lineHeight = 18.sp, maxLines = 4)
            }
        }
        Spacer(Modifier.height(16.dp))
        GradientButton(c, "Test again", Icons.Rounded.Refresh, onClick = actions.onRun)
    }
}

@Composable
private fun Catalog(c: LabColors, state: ProtocolsUiState, actions: ProtocolsActions) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LabFamily.serverFamilies.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { f ->
                    val off = state.panel != null && f !in state.families
                    ProtoTile(c, f, off, Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().labCard(c, 18.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Cloud, c.good)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                LabText("CDN & Workers", c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                val sub = if (state.savedCdnCount > 0) "${state.savedCdnCount} saved · no server needed" else "From your saved configs · no server needed"
                LabText(sub, c.text2, 12.sp, maxLines = 1)
            }
            Pill("FREE", c.good)
        }
        if (state.panels.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .clickable(onClick = actions.onAddServer).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(Icons.Rounded.Add, c.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    LabText("Add your server", c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                    LabText("Install 3X-UI on a VPS to test and create every protocol", c.text2, 12.sp, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun ProtoTile(c: LabColors, f: LabFamily, off: Boolean, modifier: Modifier) {
    Column(modifier.labCard(c, 18.dp).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(f.icon(), if (off) c.text3 else f.tint(c), 36.dp)
            Spacer(Modifier.weight(1f))
            Pill(if (off) "OFF" else f.network, c.text2, filled = false)
        }
        Spacer(Modifier.height(12.dp))
        LabText(f.shortName, if (off) c.text3 else c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        LabText(f.blurb, c.text2, 12.sp, maxLines = 1)
    }
}

@Composable
private fun RankRow(c: LabColors, rank: Int, r: LabResult, selected: Boolean, onClick: () -> Unit) {
    val sc = if (r.works) scoreColor(c, r.score) else c.bad
    Row(
        Modifier.fillMaxWidth().background(if (selected) c.accentSoft else Color.Transparent)
            .clickable(enabled = r.works, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(18.dp)) { LabText(if (r.works) "$rank" else "–", if (selected) c.accent else c.text3, 14.sp, FontWeight.Bold, maxLines = 1) }
        Spacer(Modifier.width(4.dp))
        IconTile(r.family.icon(), if (r.works) r.family.tint(c) else c.text3, 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabText(r.family.shortName, if (r.works) c.text else c.text2, 15.sp, FontWeight.SemiBold, maxLines = 1)
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            val rounds = r.latenciesMs.size
            val detail = when {
                !r.works -> r.note.ifBlank { "Blocked on this network" }
                r.successes == rounds -> "${r.medianMs} ms · ±${r.jitterMs ?: 0} ms · ${r.family.network}"
                else -> "${r.medianMs} ms · ${r.successes} of $rounds answered · ${r.family.network}"
            }
            LabText(detail, c.text2, 12.sp, maxLines = 1)
        }
        if (r.works) {
            SignalBars(c, when { r.score >= 85 -> 4; r.score >= 70 -> 3; r.score >= 50 -> 2; else -> 1 }, sc)
            Spacer(Modifier.width(14.dp))
        }
        Box(
            Modifier.width(44.dp).clip(RoundedCornerShape(10.dp)).background(sc.copy(alpha = 0.14f)).padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) { LabText(if (r.works) "${r.score}" else "OFF", sc, if (r.works) 14.sp else 11.sp, FontWeight.Bold, maxLines = 1) }
    }
}

@Composable
private fun ActionDock(c: LabColors, state: ProtocolsUiState, actions: ProtocolsActions, modifier: Modifier) {
    val chosen = state.chosen ?: return
    val backups = state.working.filter { it !== chosen }.map { it.family.shortName }
    val failoverSub = when {
        !state.autoFailover -> "Off · stays on ${chosen.family.shortName}"
        backups.isEmpty() -> "No other protocol works here yet"
        backups.size == 1 -> "Switches to ${backups[0]} if blocked"
        else -> "Switches to ${backups[0]}, then ${backups[1]} if blocked"
    }
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, c.bg, c.bg), startY = 0f, endY = 120f))
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 22.dp)
    ) {
        Row(Modifier.fillMaxWidth().labCard(c, 16.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.AltRoute, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                LabText("Smart failover", c.text, 14.sp, FontWeight.SemiBold, maxLines = 1)
                LabText(failoverSub, c.text2, 12.sp, maxLines = 1)
            }
            LabSwitch(c, state.autoFailover, actions.onAutoFailover)
        }
        Spacer(Modifier.height(10.dp))
        GradientButton(c, "Connect with ${chosen.family.shortName}", Icons.Rounded.PowerSettingsNew, onClick = actions.onOpenSetup)
    }
}

// ---------------------------------------------------------------- sheets

@Composable
fun SetupSheetContent(
    state: ProtocolsUiState,
    keepBackups: Boolean,
    cleanUp: Boolean,
    onKeepBackups: (Boolean) -> Unit,
    onCleanUp: (Boolean) -> Unit,
    onConfirm: () -> Unit
) {
    val c = labColors
    val r = state.chosen ?: return
    val p = r.profile ?: return
    val backups = state.working.filter { it !== r }.take(2).map { it.family.shortName }
    val removable = state.results.count { it.generated != null } - 1 - (if (keepBackups) backups.size else 0)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(r.family.icon(), r.family.tint(c), 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                LabText("Set up ${r.family.shortName}", c.text, 21.sp, FontWeight.Bold, letterSpacing = (-0.4).sp, maxLines = 1)
                LabText("${if (r === state.best) "Best match for this network" else "Your choice"} · score ${r.score}", c.text2, 13.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(18.dp))
        val security = when {
            r.family == LabFamily.HYSTERIA2 -> "TLS · certificate pinned"
            r.family == LabFamily.WIREGUARD -> "WireGuard keys"
            p.security.equals("reality", true) -> "REALITY"
            p.security.equals("tls", true) -> "TLS"
            p.encryption.isNotBlank() && p.encryption != "none" -> "VLESS encryption"
            else -> "None"
        }
        val rows = listOf(
            Triple(Icons.Rounded.Dns, "Server", if (state.serverRun) sourceName(state) else p.address),
            Triple(Icons.Rounded.SettingsEthernet, "Port", "${p.port} / ${r.family.network}"),
            Triple(Icons.Rounded.Lock, "Security", security),
            Triple(Icons.AutoMirrored.Rounded.Label, "Name", p.name)
        )
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(c.cardAlt)) {
            rows.forEachIndexed { i, (icon, k, v) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = c.text3, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    LabText(k, c.text2, 14.sp, FontWeight.Medium, Modifier.weight(1f), maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    LabText(v, c.text, 14.sp, FontWeight.SemiBold, maxLines = 1)
                }
                if (i < rows.lastIndex) Divider(c, 44.dp)
            }
        }
        if (state.serverRun) {
            Spacer(Modifier.height(14.dp))
            ToggleRow(
                c, "Keep backups for failover",
                if (backups.isEmpty()) "No other protocol works here" else "${backups.joinToString(" and ")} stay ready on your server",
                keepBackups && backups.isNotEmpty(), onKeepBackups
            )
            ToggleRow(
                c, "Clean up test configs",
                if (removable <= 0) "Nothing else to remove" else "Removes the $removable ${if (removable == 1) "config" else "configs"} you don’t need",
                cleanUp, onCleanUp
            )
        }
        Spacer(Modifier.height(16.dp))
        GradientButton(
            c, if (state.applying) "Saving…" else if (state.serverRun) "Create & connect" else "Connect",
            Icons.Rounded.PowerSettingsNew, enabled = !state.applying, onClick = onConfirm
        )
    }
}

@Composable
fun SourceSheetContent(state: ProtocolsUiState, onPick: (ManagedPanel?) -> Unit, onAddServer: () -> Unit) {
    val c = labColors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 22.dp)) {
        LabText("Test on", c.text, 21.sp, FontWeight.Bold, letterSpacing = (-0.4).sp, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(c.cardAlt)) {
            state.panels.forEach { p ->
                SourceRow(c, Icons.Rounded.Dns, p.name.ifBlank { p.host }, "Creates a test config per protocol", state.panel?.id == p.id) { onPick(p) }
                Divider(c, 58.dp)
            }
            SourceRow(c, Icons.Rounded.Folder, "Saved configs", "Tests what is already in the app", state.panel == null) { onPick(null) }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onAddServer).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            LabText("Add a server", c.accent, 13.sp, FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun SourceRow(c: LabColors, icon: ImageVector, title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, if (on) c.accent else c.text2, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            LabText(title, c.text, 14.sp, FontWeight.SemiBold, maxLines = 1)
            LabText(sub, c.text2, 12.sp, maxLines = 1)
        }
        if (on) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun CustomizeSheetContent(state: ProtocolsUiState, onToggle: (LabFamily) -> Unit) {
    val c = labColors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 22.dp)) {
        LabText("Protocols to test", c.text, 21.sp, FontWeight.Bold, letterSpacing = (-0.4).sp, maxLines = 1)
        LabText("Fewer protocols make the test quicker.", c.text2, 13.sp, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        LabFamily.serverFamilies.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(f.icon(), f.tint(c), 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    LabText(f.shortName, c.text, 14.sp, FontWeight.SemiBold, maxLines = 1)
                    LabText("${f.network} · ${f.blurb}", c.text2, 12.sp, maxLines = 1)
                }
                LabSwitch(c, f in state.families) { onToggle(f) }
            }
        }
    }
}
