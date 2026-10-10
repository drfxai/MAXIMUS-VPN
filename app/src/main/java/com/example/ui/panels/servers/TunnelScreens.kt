package com.example.ui.panels.servers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.servers.CheckItem
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.tunnel.TunnelSpec
import com.example.panels.servers.tunnel.TunnelTransport

// ------------------------------------------------------------------ models

/** One tunnel as the Tunnel tab lists it. */
data class TunnelCardUi(
    val iranId: String,
    val iranName: String,
    val iranHost: String,
    val abroadName: String,
    val abroadHost: String,
    val health: Health,
    val latencyMs: Long?,
    val transports: List<TunnelTransport>,
    val rotationText: String,
    val working: Int? = null,
    val total: Int? = null
)

/** One way across, as the tunnel page shows it. */
data class TunnelPathUi(
    val transport: TunnelTransport,
    val port: Int?,
    val previous: Boolean,
    val latencyMs: Long?,
    val tested: Boolean,
    val best: Boolean
)

data class TunnelTabUi(
    val tunnels: List<TunnelCardUi>,
    val hasIran: Boolean,
    val hasAbroad: Boolean,
    /** A server pair without a tunnel yet. */
    val canAddMore: Boolean
)

enum class TunnelStage(val index: Int) { PICK(0), CHECK(1), OPTIONS(2), RUNNING(3), FAILED(3), DONE(4) }

/** Everything the tunnel setup shows. */
data class TunnelSetupUi(
    val iranRows: List<ServerRowUi>,
    val abroadRows: List<ServerRowUi>,
    val iranId: String? = null,
    val abroadId: String? = null,
    val stage: TunnelStage = TunnelStage.PICK,
    /** null while the check runs. */
    val checks: List<CheckItem>? = null,
    val transports: Set<TunnelTransport> = TunnelTransport.entries.toSet(),
    val rotationHours: Int = 24,
    val entrySni: String = "",
    val exitSni: String = "",
    val iranSites: List<String> = emptyList(),
    val abroadSites: List<String> = emptyList(),
    val update: Boolean = false,
    val renewKeys: Boolean = false,
    val steps: List<String> = emptyList(),
    val currentStep: Int = 0,
    val log: List<String> = emptyList(),
    val paths: List<TunnelPathUi> = emptyList(),
    val profileName: String? = null,
    val link: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val error: String = ""
) {
    val iran: ServerRowUi? get() = iranRows.firstOrNull { it.id == iranId }
    val abroad: ServerRowUi? get() = abroadRows.firstOrNull { it.id == abroadId }
    val checksPass: Boolean get() = checks != null && checks.none { it.blocking }
}

data class TunnelDetailUi(
    val card: TunnelCardUi,
    val paths: List<TunnelPathUi>,
    val checking: Boolean,
    val checkedText: String,
    val entryPort: Int,
    val rotationHours: Int,
    val nextChangeText: String,
    val entrySni: String,
    val exitSni: String,
    val profileName: String,
    val link: String?,
    val busy: String,
    val error: String,
    val testing: Boolean,
    val testResult: String?,
    val testOk: Boolean?,
    val logs: List<String>?
)

data class TunnelActions(
    val onBack: () -> Unit = {},
    val onSetup: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onAddServer: () -> Unit = {},
    val onPickIran: (String) -> Unit = {},
    val onPickAbroad: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onChange: (TunnelSetupUi) -> Unit = {},
    val onInstall: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onOpenTunnel: () -> Unit = {},
    val onCheck: () -> Unit = {},
    val onTest: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onReseed: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onLogs: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onRemove: () -> Unit = {}
)

internal fun transportShort(t: TunnelTransport) = when (t) {
    TunnelTransport.REALITY -> "REALITY"
    TunnelTransport.XHTTP -> "XHTTP"
    TunnelTransport.HYSTERIA2 -> "Hysteria2"
}

internal fun rotationLabel(hours: Int) = if (hours <= 0) "Fixed ports" else "Ports change every $hours h"

// ------------------------------------------------------------------ shared pieces

/**
 * Iran flag, a line, the flag abroad. The dashes drift from Iran towards abroad while [moving];
 * [color] says how the link is doing.
 */
@Composable
internal fun RouteLine(modifier: Modifier = Modifier, color: Color = Sv.Blue, moving: Boolean = true, dashed: Boolean = true) {
    val phase by rememberInfiniteTransition(label = "route").animateFloat(
        initialValue = 0f, targetValue = 30f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "phase"
    )
    Canvas(modifier.height(10.dp)) {
        val y = size.height / 2
        drawLine(
            color, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 20f), if (moving) -phase else 0f) else null
        )
        drawCircle(color, 3.dp.toPx(), Offset(size.width - 2.dp.toPx(), y))
    }
}

/** One stop on the route: a tile with the flag (or the phone), the name and the address under it. */
@Composable
private fun RouteStop(name: String, host: String?, width: Dp, tile: @Composable () -> Unit) {
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Sv.Raised).border(1.dp, Sv.CardBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) { tile() }
        Text(name, color = Sv.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (host != null) Text(host, color = Sv.Dim, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The whole route in one card: phone → Iran server → server abroad. */
@Composable
private fun RouteCard(card: TunnelCardUi, status: @Composable () -> Unit) {
    val color = when (card.health) {
        Health.ONLINE -> Sv.Green
        Health.OFFLINE -> Sv.Red
        Health.SIGN_IN -> Sv.Amber
        Health.CHECKING -> Sv.Blue
    }
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // Lines sit level with the middle of the 36 dp tiles.
                val line = Modifier.padding(top = 13.dp)
                RouteStop("Phone", null, 52.dp) { Icon(Icons.Rounded.Smartphone, null, tint = Sv.TextSoft, modifier = Modifier.size(18.dp)) }
                RouteLine(line.weight(0.6f), Sv.Dim, moving = false)
                RouteStop(card.iranName, card.iranHost, 100.dp) { FlagIcon(ServerLocation.IRAN, height = 16.dp) }
                RouteLine(line.weight(1f), color, moving = card.health == Health.ONLINE || card.health == Health.CHECKING)
                RouteStop(card.abroadName, card.abroadHost, 100.dp) { FlagIcon(ServerLocation.ABROAD, height = 16.dp) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Sv.Divider))
            status()
        }
    }
}

@Composable
private fun TunnelHealthLine(card: TunnelCardUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HealthLabel(card.health, card.latencyMs)
        if (card.working != null && card.total != null) {
            Text("  ·  ${card.working} of ${card.total} paths open", color = Sv.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(card.rotationText, color = Sv.Muted, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ Tunnel tab

@Composable
internal fun TunnelTab(ui: TunnelTabUi, actions: TunnelActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("Tunnel", if (ui.tunnels.isEmpty()) "Your Iran server, linked to one abroad" else "${ui.tunnels.size} linked") {
            if (ui.tunnels.isNotEmpty() && ui.canAddMore) PrimaryButton("New", actions.onSetup, icon = Icons.Rounded.Add, compact = true)
        }
        if (ui.tunnels.isEmpty()) {
            TunnelIntro(ui, actions)
        } else {
            ui.tunnels.forEach { card ->
                Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { actions.onOpen(card.iranId) }) {
                    RouteCard(card) { TunnelHealthLine(card) }
                }
            }
            Text("Tap a tunnel for its paths, ports and config.", color = Sv.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Composable
private fun TunnelIntro(ui: TunnelTabUi, actions: TunnelActions) {
    GroupCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FlagIcon(ServerLocation.IRAN, height = 24.dp)
                RouteLine(Modifier.weight(1f).padding(horizontal = 10.dp), Sv.Blue)
                IconTile(Icons.Rounded.SyncAlt, 34.dp, accent = true)
                RouteLine(Modifier.weight(1f).padding(horizontal = 10.dp), Sv.Blue)
                FlagIcon(ServerLocation.ABROAD, height = 24.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Maximus Tunnel", color = Sv.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Your phone connects to your server in Iran, which carries the traffic to your server abroad. " +
                    "Inside Iran the connection stays local, so it is fast and hard to block.",
                    color = Sv.Muted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        RowDivider(0.dp)
        FeatureRow(Icons.Rounded.Route, "Three ways across", "REALITY, XHTTP and Hysteria2 run side by side")
        RowDivider(50.dp)
        FeatureRow(Icons.Rounded.Autorenew, "Switches by itself", "The fastest open path is used; a blocked one is skipped")
        RowDivider(50.dp)
        FeatureRow(Icons.Rounded.Shuffle, "Ports that move", "Both servers change ports on a schedule, no phone needed")
        RowDivider(50.dp)
        FeatureRow(Icons.Rounded.VerifiedUser, "Keys stay on your servers", "Built on Xray and Hysteria2, nothing homemade")
    }
    SectionLabel("You need")
    GroupCard {
        NeedRow(ServerLocation.IRAN, "A server in Iran", "The entrance, close to you", ui.hasIran, actions.onAddServer)
        RowDivider(50.dp)
        NeedRow(ServerLocation.ABROAD, "A server abroad", "Where your traffic leaves", ui.hasAbroad, actions.onAddServer)
    }
    PrimaryButton("Set up tunnel", actions.onSetup, Modifier.fillMaxWidth(), Icons.Rounded.SyncAlt, enabled = ui.hasIran && ui.hasAbroad)
    if (!ui.hasIran || !ui.hasAbroad) {
        Text("Add both servers in the Servers tab first.", color = Sv.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    ListRow(title, subtitle = text, leading = { IconTile(icon, 32.dp) })
}

@Composable
private fun NeedRow(location: ServerLocation, title: String, text: String, ok: Boolean, onAdd: () -> Unit) {
    ListRow(
        title, subtitle = text,
        leading = { Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { FlagIcon(location, height = 16.dp) } },
        trailing = {
            if (ok) Icon(Icons.Rounded.CheckCircle, "Ready", tint = Sv.Green, modifier = Modifier.size(20.dp))
            else TextAction("Add", onAdd)
        }
    )
}

// ------------------------------------------------------------------ setup

@Composable
internal fun TunnelSetupScreen(ui: TunnelSetupUi, actions: TunnelActions) {
    ScreenFrame(
        title = if (ui.update) "Update tunnel" else "New tunnel",
        subtitle = if (ui.iran != null && ui.abroad != null) "${ui.iran!!.name} → ${ui.abroad!!.name}" else "Maximus Tunnel",
        onBack = actions.onBack,
        leading = { IconTile(Icons.Rounded.SyncAlt, 34.dp, accent = true) },
        bottomBar = { SetupBottomBar(ui, actions) }
    ) {
        TunnelStepper(ui.stage.index, failed = ui.stage == TunnelStage.FAILED)
        when (ui.stage) {
            TunnelStage.PICK -> PickStage(ui, actions)
            TunnelStage.CHECK -> TunnelCheckStage(ui)
            TunnelStage.OPTIONS -> OptionsStage(ui, actions)
            TunnelStage.RUNNING, TunnelStage.FAILED -> TunnelRunningStage(ui)
            TunnelStage.DONE -> TunnelDoneStage(ui, actions)
        }
    }
}

@Composable
private fun TunnelStepper(index: Int, failed: Boolean) {
    val names = listOf("Servers", "Check", "Options", "Set up", "Done")
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        names.forEachIndexed { i, name ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(if (i == 0) Color.Transparent else if (i <= index) Sv.Accent else Sv.CardBorder))
                    val fill = when {
                        failed && i == index -> Sv.Red
                        i <= index -> Sv.Accent
                        else -> Sv.Card
                    }
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(fill).border(1.dp, if (i > index) Sv.CardBorder else fill, CircleShape),
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
                Text(name, color = if (i == index) Sv.Text else Sv.Dim, fontSize = 11.sp, fontWeight = if (i == index) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PickStage(ui: TunnelSetupUi, actions: TunnelActions) {
    Text("Pick the two ends. The app sets up both servers over SSH and adds the config to this phone.",
        color = Sv.Muted, fontSize = 14.sp, lineHeight = 20.sp)
    PickGroup("Entrance in Iran", ServerLocation.IRAN, ui.iranRows, ui.iranId, actions.onPickIran)
    PickGroup("Exit abroad", ServerLocation.ABROAD, ui.abroadRows, ui.abroadId, actions.onPickAbroad)
}

@Composable
private fun PickGroup(title: String, location: ServerLocation, rows: List<ServerRowUi>, selected: String?, onPick: (String) -> Unit) {
    SectionLabel(title)
    GroupCard {
        if (rows.isEmpty()) {
            ListRow(if (location == ServerLocation.IRAN) "No server in Iran" else "No server abroad",
                subtitle = "Add one in the Servers tab", leading = { FlagIcon(location, height = 14.dp) })
        }
        Rows(rows, dividerStart = 50.dp) { row ->
            val usable = row.health != Health.SIGN_IN
            ListRow(
                row.name,
                subtitle = if (usable) row.host else "Sign in to this server first",
                subtitleMono = usable,
                leading = { Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { FlagIcon(location, height = 14.dp) } },
                trailing = { Radio(row.id == selected) },
                enabled = usable,
                onClick = { onPick(row.id) }
            )
        }
    }
}

@Composable
private fun Radio(on: Boolean) {
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(if (on) Sv.Accent else Color.Transparent)
            .border(1.5.dp, if (on) Sv.Accent else Sv.CardBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
}

@Composable
private fun TunnelCheckStage(ui: TunnelSetupUi) {
    SectionLabel("Checking both servers")
    GroupCard {
        val checks = ui.checks
        if (checks == null) {
            repeat(6) {
                if (it > 0) RowDivider(46.dp)
                Row(Modifier.padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Shimmer(Modifier.size(18.dp))
                    Spacer(Modifier.width(14.dp))
                    Shimmer(Modifier.width((100 + it * 22).dp).height(10.dp))
                }
            }
        } else {
            Rows(checks, dividerStart = 46.dp) { c ->
                val (icon, tint) = when {
                    c.ok -> Icons.Rounded.CheckCircle to Sv.Green
                    c.blocking -> Icons.Rounded.Cancel to Sv.Red
                    else -> Icons.Rounded.WarningAmber to Sv.Amber
                }
                ListRow(c.label, subtitle = c.detail, leading = { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) })
            }
        }
    }
    if (ui.checks != null && !ui.checksPass) Notice("Fix the items marked red, then go back and check again.", NoticeKind.ERROR)
    if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsStage(ui: TunnelSetupUi, actions: TunnelActions) {
    SectionLabel("Ways across")
    GroupCard {
        Rows(TunnelTransport.entries.toList(), dividerStart = 14.dp) { t ->
            val on = t in ui.transports
            ListRow(
                transportShort(t), subtitle = t.detail,
                trailing = {
                    Switch(on, { v ->
                        val next = if (v) ui.transports + t else ui.transports - t
                        if (next.isNotEmpty()) actions.onChange(ui.copy(transports = next))
                    }, colors = SwitchDefaults.colors(checkedTrackColor = Sv.Accent, uncheckedTrackColor = Sv.Raised, uncheckedBorderColor = Sv.CardBorder))
                }
            )
        }
    }
    Text("Keep all three: the tunnel uses the fastest one that is open and moves on when one is blocked.",
        color = Sv.Dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 2.dp))

    SectionLabel("Port rotation")
    GroupCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(TunnelSpec.ROTATION_CHOICES, ui.rotationHours, { if (it == 0) "Off" else "$it h" }) { actions.onChange(ui.copy(rotationHours = it)) }
            Text(
                if (ui.rotationHours == 0) "Ports stay the same until you move them by hand."
                else "Every ${ui.rotationHours} h both servers switch REALITY and XHTTP to new ports on their own. " +
                    "The old ports keep working for one more period, so nothing drops.",
                color = Sv.Muted, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
    }

    SectionLabel("Camouflage sites")
    GroupCard {
        SniPicker("Phone → Iran server", ui.entrySni, ui.iranSites) { actions.onChange(ui.copy(entrySni = it)) }
        RowDivider(0.dp)
        SniPicker("Iran server → abroad", ui.exitSni, ui.abroadSites) { actions.onChange(ui.copy(exitSni = it)) }
    }

    if (ui.update) {
        GroupCard {
            ListRow("Create new keys", subtitle = "Your tunnel config stays the same; only the link between the servers changes.",
                trailing = {
                    Switch(ui.renewKeys, { actions.onChange(ui.copy(renewKeys = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Sv.Accent, uncheckedTrackColor = Sv.Raised, uncheckedBorderColor = Sv.CardBorder))
                })
        }
    }

    SectionLabel("What changes on the servers")
    GroupCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                ServerLocation.ABROAD to "Xray${if (TunnelTransport.HYSTERIA2 in ui.transports) " and Hysteria2" else ""} under a locked user, new keys made on the server, ${if (ui.rotationHours > 0) "rotating TCP ports" else "two fixed TCP ports"}${if (TunnelTransport.HYSTERIA2 in ui.transports) " and one UDP port" else ""} opened",
                ServerLocation.IRAN to "Xray under a locked user with one TCP port for your phone; it only forwards abroad, never to the internet directly",
                null to "Programs are the pinned builds from this app's own releases; a failed first setup is undone"
            ).forEach { (loc, line) ->
                Row {
                    Box(Modifier.width(24.dp).padding(top = 3.dp)) {
                        if (loc != null) FlagIcon(loc, height = 11.dp)
                        else Icon(Icons.Rounded.VerifiedUser, null, tint = Sv.Muted, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(line, color = Sv.TextSoft, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
    Notice("If the server abroad has a cloud firewall, allow TCP 20000–59999${if (TunnelTransport.HYSTERIA2 in ui.transports) " and the Hysteria2 UDP port" else ""} there too.")
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Sv.Inset).border(1.dp, Sv.CardBorder, RoundedCornerShape(10.dp)).padding(3.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Sv.Raised else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, Sv.CardBorder, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onSelect(o) },
                contentAlignment = Alignment.Center
            ) {
                Text(label(o), color = if (on) Sv.Text else Sv.Muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SniPicker(title: String, value: String, found: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Sv.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = Sv.Text, fontFamily = Mono, fontSize = 13.sp, maxLines = 1)
        }
        if (found.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                found.forEach { s ->
                    val on = s == value
                    Text(
                        s, color = if (on) Sv.Blue else Sv.Muted, fontSize = 12.sp, fontFamily = Mono,
                        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (on) Sv.AccentSoft else Sv.Raised)
                            .border(1.dp, if (on) Sv.Accent.copy(alpha = 0.4f) else Sv.CardBorder, RoundedCornerShape(7.dp))
                            .clickable { onPick(s) }.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TunnelRunningStage(ui: TunnelSetupUi) {
    val failed = ui.stage == TunnelStage.FAILED
    GroupCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FlagIcon(ServerLocation.IRAN, height = 20.dp)
            RouteLine(Modifier.weight(1f).padding(horizontal = 10.dp), if (failed) Sv.Red else Sv.Blue, moving = !failed)
            FlagIcon(ServerLocation.ABROAD, height = 20.dp)
        }
    }
    SectionLabel(if (failed) "Stopped" else "Setting up")
    GroupCard {
        Column(Modifier.padding(14.dp)) {
            ui.steps.forEachIndexed { i, step ->
                val n = i + 1
                val state = when {
                    failed && n == ui.currentStep.coerceAtLeast(1) -> 3
                    n < ui.currentStep -> 2
                    n == ui.currentStep -> 1
                    else -> 0
                }
                TimelineRow(step, state, last = i == ui.steps.lastIndex)
            }
        }
    }
    if (failed) Notice(ui.error.ifBlank { "The setup stopped." }, NoticeKind.ERROR)
    if (ui.log.isNotEmpty()) LogCard(ui.log, initiallyOpen = failed)
}

/** state: 0 pending, 1 running, 2 done, 3 failed. */
@Composable
private fun TimelineRow(text: String, state: Int, last: Boolean) {
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(when (state) { 2 -> Sv.Accent; 3 -> Sv.Red; else -> Sv.Card })
                    .border(1.dp, when (state) { 2, 1 -> Sv.Accent; 3 -> Sv.Red; else -> Sv.CardBorder }, CircleShape),
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
        Text(text, Modifier.padding(top = 1.dp).alpha(if (state == 0) 0.55f else 1f),
            color = when (state) { 3 -> Sv.Red; 0 -> Sv.Muted; else -> Sv.Text },
            fontSize = 14.sp, fontWeight = if (state == 1) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun TunnelDoneStage(ui: TunnelSetupUi, actions: TunnelActions) {
    GroupCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Sv.GreenSoft).border(1.dp, Sv.Green.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Sv.Green, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Tunnel is up", color = Sv.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    FlagIcon(ServerLocation.IRAN, height = 10.dp)
                    Text("  ${ui.iran?.name.orEmpty()}  →  ", color = Sv.Muted, fontSize = 13.sp)
                    FlagIcon(ServerLocation.ABROAD, height = 10.dp)
                    Text("  ${ui.abroad?.name.orEmpty()}", color = Sv.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (ui.paths.isNotEmpty()) {
        SectionLabel("Paths between the servers")
        PathsCard(ui.paths)
    }
    if (ui.profileName != null) ConfigCard(ui.profileName, ui.link, ui.testing, ui.testResult, ui.testOk, actions)
    if (ui.log.isNotEmpty()) LogCard(ui.log)
}

@Composable
private fun PathsCard(paths: List<TunnelPathUi>) {
    GroupCard {
        Rows(paths, dividerStart = 14.dp) { p ->
            ListRow(
                transportShort(p.transport) + if (p.previous) " · previous port" else "",
                subtitle = when (p.transport) {
                    TunnelTransport.HYSTERIA2 -> "UDP ${p.port ?: "—"}"
                    else -> "TCP ${p.port ?: "—"}"
                },
                subtitleMono = true,
                titleColor = if (p.previous) Sv.TextSoft else Sv.Text,
                trailing = {
                    if (p.best) { Badge("In use", Tone.ACCENT); Spacer(Modifier.width(8.dp)) }
                    when {
                        !p.tested -> Text("—", color = Sv.Dim, fontSize = 12.sp)
                        p.latencyMs != null -> HealthLabel(Health.ONLINE, p.latencyMs)
                        else -> Badge("Blocked", Tone.BAD, dot = true)
                    }
                }
            )
        }
    }
}

@Composable
private fun ConfigCard(name: String, link: String?, testing: Boolean, result: String?, ok: Boolean?, actions: TunnelActions) {
    SectionLabel("Your config")
    GroupCard {
        ListRow(name, subtitle = "In your server list · VLESS REALITY to the Iran server",
            leading = { IconTile(Icons.Rounded.SyncAlt, 32.dp) },
            trailing = { if (link != null) TextAction("Copy link", { actions.onCopy(link) }, icon = Icons.Rounded.ContentCopy) })
        RowDivider(0.dp)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Sv.Blue, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Sending a real request through the tunnel…", color = Sv.Muted, fontSize = 13.sp)
                }
                result != null -> Notice(result, if (ok == true) NoticeKind.OK else NoticeKind.WARN)
            }
            GhostButton("Test from this phone", actions.onTest, Modifier.fillMaxWidth(), Icons.Rounded.NetworkCheck, enabled = !testing, compact = true)
        }
    }
}

@Composable
private fun SetupBottomBar(ui: TunnelSetupUi, actions: TunnelActions) {
    when (ui.stage) {
        TunnelStage.PICK -> PrimaryButton("Check servers", actions.onNext, Modifier.fillMaxWidth(), enabled = ui.iranId != null && ui.abroadId != null)
        TunnelStage.CHECK -> PrimaryButton(if (ui.checks == null) "Checking…" else "Continue", actions.onNext, Modifier.fillMaxWidth(), enabled = ui.checksPass)
        TunnelStage.OPTIONS -> PrimaryButton(if (ui.update) "Update tunnel" else "Set up tunnel", actions.onInstall, Modifier.fillMaxWidth(), Icons.Rounded.SyncAlt,
            enabled = ui.transports.isNotEmpty())
        TunnelStage.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Sv.Blue, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(ui.steps.getOrNull(ui.currentStep - 1)?.let { "$it…" } ?: "Connecting…", color = Sv.Muted, fontSize = 13.sp)
        }
        TunnelStage.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Close", actions.onDone, Modifier.weight(1f))
            PrimaryButton("Try again", actions.onRetry, Modifier.weight(1f), Icons.Rounded.Refresh)
        }
        TunnelStage.DONE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Open tunnel", actions.onOpenTunnel, Modifier.weight(1f))
            PrimaryButton("Done", actions.onDone, Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------------ tunnel page

@Composable
internal fun TunnelDetailScreen(ui: TunnelDetailUi, actions: TunnelActions) {
    ScreenFrame(
        title = "Maximus Tunnel",
        subtitle = "${ui.card.iranName} → ${ui.card.abroadName}",
        onBack = actions.onBack,
        leading = { IconTile(Icons.Rounded.SyncAlt, 34.dp, accent = true) },
        actions = {
            if (ui.checking) CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), color = Sv.Blue, strokeWidth = 2.dp)
            else androidx.compose.material3.IconButton(onClick = actions.onCheck) { Icon(Icons.Rounded.Refresh, "Check paths", tint = Sv.TextSoft) }
        }
    ) {
        RouteCard(ui.card) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TunnelHealthLine(ui.card)
                if (ui.checkedText.isNotBlank()) Text(ui.checkedText, color = Sv.Dim, fontSize = 11.sp)
            }
        }
        if (ui.busy.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Sv.Blue, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(ui.busy, color = Sv.Muted, fontSize = 13.sp)
        }
        if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)

        SectionLabel("Paths abroad") { TextAction("Check now", actions.onCheck, enabled = !ui.checking, icon = Icons.Rounded.NetworkCheck) }
        PathsCard(ui.paths)

        SectionLabel("Ports")
        GroupCard {
            ListRow(rotationLabel(ui.rotationHours), subtitle = ui.nextChangeText,
                leading = { IconTile(Icons.Rounded.Shuffle, 32.dp) },
                trailing = { TextAction("Move now", actions.onReseed, enabled = ui.busy.isBlank()) })
            RowDivider(58.dp)
            ListRow("Phone port on the Iran server", subtitle = "TCP ${ui.entryPort}", subtitleMono = true,
                leading = { IconTile(Icons.Rounded.Smartphone, 32.dp) })
            RowDivider(58.dp)
            ListRow("Camouflage", subtitle = "${ui.entrySni}  ·  ${ui.exitSni}", subtitleMono = true,
                leading = { IconTile(Icons.Rounded.VerifiedUser, 32.dp) })
        }

        ConfigCard(ui.profileName, ui.link, ui.testing, ui.testResult, ui.testOk, actions)

        SectionLabel("Manage")
        GroupCard {
            ManageRow(Icons.Rounded.Tune, "Change settings", "Ways across, rotation, camouflage, new keys", actions.onEdit)
            RowDivider(58.dp)
            ManageRow(Icons.Rounded.RestartAlt, "Restart", "Both servers", actions.onRestart)
            RowDivider(58.dp)
            ManageRow(Icons.AutoMirrored.Rounded.Subject, "Logs", "Iran server, last 40 lines", actions.onLogs)
            RowDivider(58.dp)
            ListRow("Remove tunnel", subtitle = "Stops it on both servers and deletes its config", titleColor = Sv.Red,
                leading = { IconTile(Icons.Rounded.Delete, 32.dp) }, onClick = actions.onRemove, enabled = ui.busy.isBlank())
        }
        ui.logs?.let { LogCard(it, initiallyOpen = true, title = "Logs") }
    }
}

@Composable
private fun ManageRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, onClick: () -> Unit) {
    ListRow(title, subtitle = text, leading = { IconTile(icon, 32.dp) }, trailing = { Chevron() }, onClick = onClick)
}
