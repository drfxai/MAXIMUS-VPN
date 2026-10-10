package com.example.ui.panels.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.ServerTool
import com.example.panels.servers.ServerToolCatalog
import com.example.panels.servers.ToolGroup

/** One server as the lists show it. */
data class ServerRowUi(
    val id: String,
    val name: String,
    val host: String,
    val location: ServerLocation,
    val health: Health,
    val latencyMs: Long? = null,
    val tools: List<String> = emptyList(),
    val system: String = ""
)

/** A suggestion: install [toolId] on [serverName] because [reason]. */
data class AdviceUi(val toolId: String, val serverId: String, val serverName: String, val location: ServerLocation, val reason: String)

// ------------------------------------------------------------------ My Servers

@Composable
internal fun MyServersTab(
    rows: List<ServerRowUi>,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onInstallCenter: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ServersOverview(rows, onAdd, onInstallCenter)
        if (rows.isEmpty()) {
            EmptyServers(onAdd)
            return@Column
        }
        for (location in listOf(ServerLocation.IRAN, ServerLocation.ABROAD)) {
            val group = rows.filter { it.location == location }
            SectionLabel(if (location == ServerLocation.IRAN) "Servers in Iran" else "Servers abroad") {
                FlagIcon(location, height = 11.dp)
                Spacer(Modifier.width(6.dp))
                Text(group.size.toString(), color = Sv.Dim, fontSize = 11.sp)
            }
            if (group.isEmpty()) {
                EmptyGroup(location, onAdd)
            } else {
                group.forEach { ServerCard(it) { onOpen(it.id) } }
            }
        }
        if (rows.any { it.location == ServerLocation.IRAN } && rows.any { it.location == ServerLocation.ABROAD }) {
            TunnelTeaser()
        }
    }
}

@Composable
private fun ServersOverview(rows: List<ServerRowUi>, onAdd: () -> Unit, onInstallCenter: () -> Unit) {
    SvCard(padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("My Servers", color = Sv.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    val online = rows.count { it.health == Health.ONLINE }
                    val tools = rows.sumOf { it.tools.size }
                    Text(
                        if (rows.isEmpty()) "Add a server once, then install anything on it with one tap"
                        else "$online of ${rows.size} online · $tools tools installed",
                        color = Sv.Muted, fontSize = 12.sp
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (location in listOf(ServerLocation.IRAN, ServerLocation.ABROAD)) {
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Sv.Inset).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FlagIcon(location, height = 18.dp)
                        Column {
                            Text(rows.count { it.location == location }.toString(), color = Sv.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(if (location == ServerLocation.IRAN) "In Iran" else "Abroad", color = Sv.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Add server", onAdd, Modifier.weight(1f), Icons.Rounded.Add)
                GhostButton("Install Center", onInstallCenter, Modifier.weight(1f), Icons.Rounded.Widgets)
            }
        }
    }
}

@Composable
internal fun ServerCard(row: ServerRowUi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp), color = Sv.Card, border = BorderStroke(1.dp, Sv.CardBorder)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagIcon(row.location, height = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.name, color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(row.host, row.system).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Sv.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(row.health)
                    Text(
                        when (row.health) {
                            Health.ONLINE -> row.latencyMs?.let { "$it ms" } ?: "Online"
                            Health.OFFLINE -> "Offline"
                            Health.CHECKING -> "Checking"
                            Health.SIGN_IN -> "Sign in"
                        },
                        color = when (row.health) {
                            Health.ONLINE -> Sv.Green
                            Health.OFFLINE -> Sv.Red
                            Health.CHECKING -> Sv.Muted
                            Health.SIGN_IN -> Sv.Amber
                        },
                        fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Sv.Dim, modifier = Modifier.size(18.dp))
                }
            }
            if (row.tools.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.tools.take(3).forEach { id ->
                        Chip(ServerToolCatalog.byId(id)?.title?.substringBefore(" (") ?: id, leading = {
                            Icon(toolIcon(id), null, tint = toolTint(id), modifier = Modifier.size(12.dp))
                        })
                    }
                    if (row.tools.size > 3) Chip("+${row.tools.size - 3}")
                }
            } else {
                Text("Nothing installed yet", color = Sv.Dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyServers(onAdd: () -> Unit) {
    SvCard(padding = 22.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkedFlags()
            Text("Add your first server", color = Sv.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "A server in Iran gets the Iranian flag, a server abroad the German flag. " +
                    "You enter its details once; every tool then installs with one tap.",
                color = Sv.Muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            PrimaryButton("Add server", onAdd, icon = Icons.Rounded.Add)
        }
    }
}

@Composable
private fun EmptyGroup(location: ServerLocation, onAdd: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd),
        shape = RoundedCornerShape(14.dp), color = Sv.Inset, border = BorderStroke(1.dp, Sv.CardBorder)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            FlagIcon(location, Modifier.alpha(0.6f), height = 14.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (location == ServerLocation.IRAN) "No server in Iran yet. It will be the inside end of Maximus Tunnel."
                else "No server abroad yet. Abroad is where your traffic leaves.",
                color = Sv.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Rounded.Add, null, tint = Sv.Dim, modifier = Modifier.size(18.dp))
        }
    }
}

/** Two flags joined by a dashed line: the picture of an Iran ↔ abroad pair. */
@Composable
private fun LinkedFlags() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlagIcon(ServerLocation.IRAN, height = 24.dp)
        Canvas(Modifier.width(56.dp).height(10.dp)) {
            drawLine(Sv.Blue.copy(alpha = 0.7f), Offset(6f, size.height / 2), Offset(size.width - 6f, size.height / 2),
                strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        FlagIcon(ServerLocation.ABROAD, height = 24.dp)
    }
}

@Composable
private fun TunnelTeaser() {
    Surface(shape = RoundedCornerShape(14.dp), color = Sv.AccentSoft.copy(alpha = 0.5f), border = BorderStroke(1.dp, Sv.CardBorder)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LinkedFlags()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Maximus Tunnel", color = Sv.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Will link your Iranian and foreign servers. Coming after the other tools.", color = Sv.Muted, fontSize = 11.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ Install Center

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InstallCenterTab(
    tools: List<ServerTool>,
    installedOn: Map<String, Int>,
    advice: List<AdviceUi>,
    onOpen: (String) -> Unit,
    onAdvice: (AdviceUi) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SvCard(padding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Install Center", color = Sv.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Pick a tool, pick a server, tap Install. It runs over your pinned SSH connection and shows every step.",
                    color = Sv.Muted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Checksum-verified", Sv.Green, Sv.GreenSoft, leading = { Icon(Icons.Rounded.Verified, null, tint = Sv.Green, modifier = Modifier.size(12.dp)) })
                    Chip("Auto-undo", Sv.Blue, Sv.AccentSoft, leading = { Icon(Icons.Rounded.Undo, null, tint = Sv.Blue, modifier = Modifier.size(12.dp)) })
                    Chip("Removable", Sv.Muted, Sv.Inset, leading = { Icon(Icons.Rounded.CheckCircle, null, tint = Sv.Muted, modifier = Modifier.size(12.dp)) })
                }
            }
        }
        if (advice.isNotEmpty()) {
            SectionLabel("Suggested for your servers")
            advice.take(3).forEach { a -> AdviceCard(a) { onAdvice(a) } }
        }
        for (group in ToolGroup.values()) {
            val inGroup = tools.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            SectionLabel(group.title)
            inGroup.forEach { tool -> ToolCard(tool, installedOn[tool.id] ?: 0) { onOpen(tool.id) } }
        }
    }
}

@Composable
internal fun AdviceCard(a: AdviceUi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = Sv.AccentSoft.copy(alpha = 0.45f), border = BorderStroke(1.dp, Sv.Accent.copy(alpha = 0.35f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Sv.Blue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ServerToolCatalog.byId(a.toolId)?.title ?: a.toolId, color = Sv.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    FlagIcon(a.location, height = 10.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(a.serverName, color = Sv.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(a.reason, color = Sv.Muted, fontSize = 12.sp)
            }
            Text("Install", color = Sv.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ToolCard(tool: ServerTool, installedOn: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !tool.comingLater, onClick = onClick),
        shape = RoundedCornerShape(16.dp), color = Sv.Card, border = BorderStroke(1.dp, Sv.CardBorder)
    ) {
        Row(Modifier.padding(14.dp).alpha(if (tool.comingLater) 0.6f else 1f), verticalAlignment = Alignment.CenterVertically) {
            ToolBadge(tool.id)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tool.title, color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(tool.tagline, color = Sv.Muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tool.fits.sortedBy { it.ordinal }.forEach { FlagIcon(it, height = 10.dp) }
                    Text(
                        when {
                            tool.fits.size == 2 -> "Iran and abroad"
                            ServerLocation.ABROAD in tool.fits -> "Abroad only"
                            else -> "Iran only"
                        } + if (tool.needsDomain) " · needs a domain" else "",
                        color = Sv.Dim, fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                tool.comingLater -> Chip("Later", Sv.Muted)
                installedOn > 0 -> Chip("On $installedOn", Sv.Green, Sv.GreenSoft)
                else -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Sv.Dim)
            }
        }
    }
}
