package com.example.ui.panels.servers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private fun shortTitle(id: String) = ServerToolCatalog.byId(id)?.title?.substringBefore(" (") ?: id

// ------------------------------------------------------------------ My Servers

@Composable
internal fun MyServersTab(
    rows: List<ServerRowUi>,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onInstallCenter: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val online = rows.count { it.health == Health.ONLINE }
        PageHeader(
            "Servers",
            if (rows.isEmpty()) "Your own servers, in Iran and abroad"
            else "$online of ${rows.size} online · ${rows.sumOf { it.tools.size }} tools installed"
        ) {
            if (rows.isNotEmpty()) PrimaryButton("Add", onAdd, icon = Icons.Rounded.Add, compact = true)
        }
        if (rows.isEmpty()) {
            EmptyServers(onAdd, onInstallCenter)
            return@Column
        }
        LocationSummary(rows)
        for (location in listOf(ServerLocation.IRAN, ServerLocation.ABROAD)) {
            val group = rows.filter { it.location == location }
            SectionLabel(if (location == ServerLocation.IRAN) "In Iran" else "Abroad")
            GroupCard {
                if (group.isEmpty()) {
                    ListRow(
                        if (location == ServerLocation.IRAN) "No server in Iran" else "No server abroad",
                        subtitle = if (location == ServerLocation.IRAN) "The inside end of Maximus Tunnel" else "Where your traffic leaves",
                        leading = { FlagIcon(location, height = 14.dp) },
                        trailing = { TextAction("Add", onAdd) }
                    )
                } else {
                    Rows(group, dividerStart = 50.dp) { ServerRow(it) { onOpen(it.id) } }
                }
            }
        }
        if (rows.any { it.location == ServerLocation.IRAN } && rows.any { it.location == ServerLocation.ABROAD }) {
            SectionLabel("Coming next")
            GroupCard { TunnelRow() }
        }
    }
}

/** Two halves: Iran and abroad, each with its flag, count and how many answer. */
@Composable
private fun LocationSummary(rows: List<ServerRowUi>) {
    GroupCard {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            listOf(ServerLocation.IRAN, ServerLocation.ABROAD).forEachIndexed { i, location ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 12.dp).background(Sv.Divider))
                val inGroup = rows.filter { it.location == location }
                Row(Modifier.weight(1f).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlagIcon(location, height = 16.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(locationName(location), color = Sv.Muted, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(inGroup.size.toString(), color = Sv.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Text("${inGroup.count { it.health == Health.ONLINE }} online", color = Sv.Dim, fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ServerRow(row: ServerRowUi, onClick: () -> Unit) {
    ListRow(
        title = row.name,
        subtitle = row.host,
        subtitleMono = true,
        caption = {
            Text(
                listOf(row.system, if (row.tools.isEmpty()) "Nothing installed" else row.tools.joinToString(", ") { shortTitle(it) })
                    .filter { it.isNotBlank() }.joinToString(" · "),
                color = if (row.tools.isEmpty()) Sv.Dim else Sv.TextSoft, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
            )
        },
        leading = { FlagIcon(row.location, height = 16.dp) },
        trailing = {
            HealthLabel(row.health, row.latencyMs)
            Chevron()
        },
        onClick = onClick
    )
}

/** Old name kept for the previews. */
@Composable
internal fun ServerCard(row: ServerRowUi, onClick: () -> Unit) = GroupCard { ServerRow(row, onClick) }

@Composable
private fun EmptyServers(onAdd: () -> Unit, onInstallCenter: () -> Unit) {
    SvCard(padding = 24.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkedFlags()
            Spacer(Modifier.height(2.dp))
            Text("Add your first server", color = Sv.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Servers in Iran show the Iranian flag, servers abroad the German flag. " +
                    "Enter a server once; tools then install from the Install tab.",
                color = Sv.Muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Browse tools", onInstallCenter, compact = true)
                PrimaryButton("Add server", onAdd, icon = Icons.Rounded.Add, compact = true)
            }
        }
    }
}

/** Two flags joined by a dashed line: the picture of an Iran ↔ abroad pair. */
@Composable
private fun LinkedFlags() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlagIcon(ServerLocation.IRAN, height = 20.dp)
        Canvas(Modifier.width(44.dp).height(8.dp)) {
            drawLine(Sv.Dim, Offset(6f, size.height / 2), Offset(size.width - 6f, size.height / 2),
                strokeWidth = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f)))
        }
        FlagIcon(ServerLocation.ABROAD, height = 20.dp)
    }
}

@Composable
private fun TunnelRow() {
    ListRow(
        "Maximus Tunnel",
        subtitle = "Links your server in Iran with one abroad",
        leading = { IconTile(Icons.Rounded.SyncAlt) },
        trailing = { Badge("Soon", Tone.ACCENT) }
    )
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("Install", "Panels, tunnels and protection for your servers")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (text in listOf("Verified downloads", "Undo on failure", "Removable")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Check, null, tint = Sv.Green, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(text, color = Sv.Muted, fontSize = 12.sp)
                }
            }
        }
        if (advice.isNotEmpty()) {
            SectionLabel("Suggested")
            GroupCard { Rows(advice.take(3), dividerStart = 62.dp) { a -> AdviceRow(a) { onAdvice(a) } } }
        }
        for (group in ToolGroup.values()) {
            val inGroup = tools.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            SectionLabel(group.title)
            GroupCard { Rows(inGroup, dividerStart = 62.dp) { tool -> ToolRow(tool, installedOn[tool.id] ?: 0) { onOpen(tool.id) } } }
        }
    }
}

@Composable
internal fun AdviceRow(a: AdviceUi, onClick: () -> Unit) {
    ListRow(
        title = shortTitle(a.toolId),
        subtitle = a.reason,
        caption = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                FlagIcon(a.location, height = 9.dp)
                Spacer(Modifier.width(5.dp))
                Text(a.serverName, color = Sv.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        leading = { IconTile(Icons.Rounded.AutoAwesome, accent = true) },
        trailing = { TextAction("Install", onClick) },
        onClick = onClick
    )
}

/** Old name kept for the server page. */
@Composable
internal fun AdviceCard(a: AdviceUi, onClick: () -> Unit) = GroupCard { AdviceRow(a, onClick) }

@Composable
private fun ToolRow(tool: ServerTool, installedOn: Int, onClick: () -> Unit) {
    val where = when {
        tool.fits.size == 2 -> "Iran and abroad"
        ServerLocation.ABROAD in tool.fits -> "Abroad"
        else -> "Iran"
    } + if (tool.needsDomain) " · needs a domain" else ""
    ListRow(
        title = tool.title,
        subtitle = tool.tagline,
        caption = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                tool.fits.sortedBy { it.ordinal }.forEach {
                    FlagIcon(it, height = 9.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(2.dp))
                Text(where, color = Sv.Dim, fontSize = 12.sp)
            }
        },
        leading = { ToolBadge(tool.id) },
        trailing = {
            when {
                tool.comingLater -> Badge("Soon", Tone.ACCENT)
                installedOn > 0 -> Badge("Installed · $installedOn", Tone.GOOD)
                else -> Chevron()
            }
        },
        enabled = !tool.comingLater,
        onClick = onClick
    )
}
