package com.example.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.servers.ServerFacts

internal val StudioInk = Color(0xFF080F18)
internal val StudioPanel = Color(0xFF101C2A)
internal val StudioLine = Color(0xFF29394B)
internal val StudioGold = Color(0xFFF3CD86)
internal val StudioBlue = Color(0xFF84C8FF)
internal val StudioMuted = Color(0xFFAAB9CA)

@Composable
internal fun StudioHeading(onBack: () -> Unit, onCloudflare: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("‹  M", color = StudioGold, fontSize = 21.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Text("MAXIMUS", color = Color.White, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text(" VPN", color = StudioMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCloudflare) { Icon(Icons.Default.Cloud, "Cloudflare", tint = StudioMuted) }
        }
        HorizontalDivider(color = StudioLine)
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Layers, null, Modifier.size(31.dp), tint = StudioGold)
            Spacer(Modifier.width(12.dp))
            VerticalDivider(Modifier.height(36.dp), color = StudioLine)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Smart Manager", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("One workspace. Every server.", color = StudioMuted, fontSize = 12.sp)
            }
            Text("NETWORK\nSTUDIO", color = StudioMuted, fontSize = 8.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
internal fun StudioTab(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (selected) StudioGold else StudioMuted
    Box(modifier.heightIn(min = 48.dp)
        .background(if (selected) StudioLine.copy(alpha = .38f) else Color.Transparent)
        .selectable(selected, role = Role.Tab, onClick = onClick)) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(15.dp), tint = tint)
            Spacer(Modifier.width(5.dp))
            Text(label, color = tint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(StudioGold))
    }
}

@Composable
internal fun StudioHero(titles: List<String>, icons: List<ImageVector>) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(124.dp)) {
        val labelWidth = maxWidth * .26f
        Canvas(Modifier.matchParentSize()) {
            val x = size.width / 2
            val y = size.height * .84f
            val w = size.width * .22f
            drawRect(Brush.radialGradient(listOf(Color(0xFF183858), StudioInk), Offset(x, y), size.width * .54f))
            for (side in listOf(-1f, 1f)) {
                for (i in 0..1) {
                    val start = Offset(x + side * w * .55f, size.height * (.45f + i * .09f))
                    val end = Offset(x + side * size.width * .24f, size.height * (.24f + i * .44f))
                    val wire = Path().apply {
                        moveTo(start.x, start.y)
                        cubicTo(start.x + side * w * .36f, start.y, end.x - side * w * .35f, end.y, end.x, end.y)
                    }
                    drawPath(wire, StudioBlue.copy(alpha = .68f), style = Stroke(1.dp.toPx()))
                    drawCircle(StudioGold, 2.dp.toPx(), end)
                    drawCircle(StudioGold, 1.5.dp.toPx(), start)
                }
            }
            drawOval(StudioGold.copy(alpha = .18f), Offset(x - w * .76f, y - 8.dp.toPx()), Size(w * 1.52f, 19.dp.toPx()))
            drawOval(StudioGold, Offset(x - w * .76f, y - 8.dp.toPx()), Size(w * 1.52f, 19.dp.toPx()), style = Stroke(1.dp.toPx()))
            val h = size.height * .21f
            repeat(3) { rack ->
                val top = Offset(x - w / 2, y - (3 - rack) * h)
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF467296), Color(0xFF121D2B), Color(0xFF060D16)),
                    top.y, top.y + h), top, Size(w, h - 2.dp.toPx()), CornerRadius(4.dp.toPx()))
                drawRoundRect(StudioBlue.copy(alpha = .65f), top, Size(w, h - 2.dp.toPx()),
                    CornerRadius(4.dp.toPx()), style = Stroke(.8.dp.toPx()))
                repeat(4) { vent ->
                    drawLine(StudioInk, Offset(top.x + w * .17f, top.y + h * (.34f + vent * .09f)),
                        Offset(top.x + w * .47f, top.y + h * (.34f + vent * .09f)), 1.dp.toPx())
                }
                repeat(3) { led ->
                    drawCircle(if (rack == 0) StudioGold else StudioBlue, 1.6.dp.toPx(),
                        Offset(top.x + w * (.66f + led * .08f), top.y + h * .53f))
                }
            }
        }
        titles.forEachIndexed { i, title ->
            val shape = RoundedCornerShape(9.dp)
            Row(Modifier.align(if (i % 2 == 0) Alignment.TopStart else Alignment.TopEnd)
                .padding(top = if (i < 2) 12.dp else 67.dp).width(labelWidth)
                .background(StudioPanel, shape).border(1.dp, Color(0xFF365C7D), shape)
                .padding(horizontal = 5.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Icon(icons[i], null, Modifier.size(14.dp), tint = StudioBlue)
                Spacer(Modifier.width(4.dp))
                Text(title, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun StudioResources(facts: ServerFacts?) {
    val cpu = facts?.cpuUsedPercent
    val ram = facts?.takeIf { it.ramMb > 0 }?.ramUsedPercent
    val disk = facts?.takeIf { it.diskMb > 0 }?.diskUsedPercent
    Surface(color = StudioPanel, shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, StudioLine)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val values = listOf(cpu?.let { "$it%" }, ram?.let { "$it%" }, disk?.let { "$it%" })
            val fractions = listOf(cpu?.div(100f), ram?.div(100f), disk?.div(100f))
            listOf("CPU", "RAM", "Disk").forEachIndexed { i, label ->
                if (i > 0) VerticalDivider(Modifier.height(28.dp), color = StudioLine)
                Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(label, color = StudioMuted, fontSize = 10.sp)
                        Text(values[i] ?: "—", color = StudioBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { fractions[i]?.coerceIn(0f, 1f) ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp), color = StudioBlue, trackColor = StudioLine)
                }
            }
        }
    }
}

@Composable
internal fun StudioTableHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp)) {
        Text("Service / Engine", Modifier.weight(1.2f), color = StudioMuted, fontSize = 11.sp)
        Text("Status", Modifier.weight(1f), color = StudioMuted, fontSize = 11.sp)
        Text("Action", Modifier.width(68.dp), color = StudioMuted, fontSize = 11.sp)
    }
    HorizontalDivider(color = StudioLine)
}

@Composable
internal fun StudioServiceRow(name: String, icon: ImageVector, status: String, tint: Color, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 47.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = StudioBlue)
            Column(Modifier.padding(start = 7.dp)) {
                Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Engine: $name", color = StudioMuted, fontSize = 9.sp)
            }
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (tint == Mx.Green) Icons.Default.CheckCircleOutline else Icons.Default.Info,
                null, Modifier.size(14.dp), tint = tint)
            Text(status, Modifier.padding(start = 5.dp), color = Color.White, fontSize = 10.sp)
        }
        TextButton(onClick, Modifier.width(68.dp), contentPadding = PaddingValues(2.dp)) {
            Text(action, color = StudioBlue, fontSize = 11.sp)
            Icon(Icons.Default.ChevronRight, null, Modifier.size(13.dp), tint = StudioMuted)
        }
    }
    HorizontalDivider(color = StudioLine)
}

@Composable
internal fun StudioReadiness(udp53: Boolean?, onReview: () -> Unit) {
    Surface(color = StudioPanel, shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, StudioLine)) {
        Row(Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, null, Modifier.size(18.dp), tint = StudioBlue)
            Text(when (udp53) { true -> "UDP 53 available"; false -> "UDP 53 occupied"; null -> "UDP 53 needs inspection" },
                Modifier.weight(1f).padding(horizontal = 6.dp), color = Color.White, fontSize = 11.sp)
            Button(onReview, shape = RoundedCornerShape(7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StudioGold, contentColor = StudioInk),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                Text("Review install plan", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun StudioActivity(message: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Terminal, null, Modifier.size(17.dp), tint = StudioBlue)
        Text("Last operation", Modifier.padding(horizontal = 7.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(message, color = StudioMuted, fontSize = 10.sp)
    }
}

@Composable
internal fun StudioNavigation(onHome: () -> Unit, onServers: () -> Unit, onNodes: () -> Unit, onSettings: () -> Unit) {
    HorizontalDivider(color = StudioLine)
    Row(Modifier.fillMaxWidth().background(StudioInk)) {
        val labels = listOf("Home", "Panels", "Servers", "Nodes", "Settings")
        val icons = listOf(Icons.Default.Home, Icons.Default.Layers, Icons.Default.Storage,
            Icons.Default.Hub, Icons.Default.Settings)
        val actions = listOf(onHome, {}, onServers, onNodes, onSettings)
        labels.forEachIndexed { index, label ->
            val selected = index == 1
            val tint = if (selected) StudioGold else StudioMuted
            Column(Modifier.weight(1f).heightIn(min = 55.dp)
                .selectable(selected, role = Role.Tab, onClick = actions[index])
                .padding(top = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icons[index], null, Modifier.size(19.dp), tint = tint)
                Spacer(Modifier.height(3.dp))
                Text(label, color = tint, fontSize = 10.sp, maxLines = 1)
                Spacer(Modifier.height(5.dp))
                if (selected) Box(Modifier.fillMaxWidth(.58f).height(2.dp).background(StudioGold))
            }
        }
    }
}
