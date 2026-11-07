package com.example.ui.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.example.ui.theme.AppTheme
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

internal val StudioInk @Composable get() = AppTheme.colors.background
internal val StudioPanel @Composable get() = AppTheme.colors.surfaceCard
internal val StudioLine @Composable get() = AppTheme.colors.borderSubtle
internal val StudioGold @Composable get() = AppTheme.colors.primary
internal val StudioBlue @Composable get() = AppTheme.colors.primaryLight
internal val StudioMuted @Composable get() = AppTheme.colors.textSecondary
internal val StudioText @Composable get() = AppTheme.colors.textPrimary

@Composable
internal fun StudioHeading(onBack: () -> Unit, onCloudflare: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = StudioGold)
            }
            Spacer(Modifier.width(10.dp))
            Text("MAXIMUS", color = StudioText, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
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
                Text("Smart Manager", color = StudioText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("One workspace. Every server.", color = StudioMuted, fontSize = 12.sp)
            }

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
    val ink = StudioInk
    val gold = StudioGold
    val blue = StudioBlue
    val panel = StudioPanel
    val glow = AppTheme.colors.primaryContainer
    BoxWithConstraints(Modifier.fillMaxWidth().height(124.dp)) {
        val labelWidth = maxWidth * .26f
        Canvas(Modifier.matchParentSize()) {
            val x = size.width / 2
            val y = size.height * .84f
            val w = size.width * .22f
            drawRect(Brush.radialGradient(listOf(glow, ink), Offset(x, y), size.width * .54f))
            for (side in listOf(-1f, 1f)) {
                for (i in 0..1) {
                    val start = Offset(x + side * w * .55f, size.height * (.45f + i * .09f))
                    val end = Offset(x + side * size.width * .24f, size.height * (.24f + i * .44f))
                    val wire = Path().apply {
                        moveTo(start.x, start.y)
                        cubicTo(start.x + side * w * .36f, start.y, end.x - side * w * .35f, end.y, end.x, end.y)
                    }
                    drawPath(wire, blue.copy(alpha = .68f), style = Stroke(1.dp.toPx()))
                    drawCircle(gold, 2.dp.toPx(), end)
                    drawCircle(gold, 1.5.dp.toPx(), start)
                }
            }
            drawOval(gold.copy(alpha = .18f), Offset(x - w * .76f, y - 8.dp.toPx()), Size(w * 1.52f, 19.dp.toPx()))
            drawOval(gold, Offset(x - w * .76f, y - 8.dp.toPx()), Size(w * 1.52f, 19.dp.toPx()), style = Stroke(1.dp.toPx()))
            val h = size.height * .21f
            repeat(3) { rack ->
                val top = Offset(x - w / 2, y - (3 - rack) * h)
                drawRoundRect(Brush.verticalGradient(listOf(glow, panel, ink),
                    top.y, top.y + h), top, Size(w, h - 2.dp.toPx()), CornerRadius(4.dp.toPx()))
                drawRoundRect(blue.copy(alpha = .65f), top, Size(w, h - 2.dp.toPx()),
                    CornerRadius(4.dp.toPx()), style = Stroke(.8.dp.toPx()))
                repeat(4) { vent ->
                    drawLine(ink, Offset(top.x + w * .17f, top.y + h * (.34f + vent * .09f)),
                        Offset(top.x + w * .47f, top.y + h * (.34f + vent * .09f)), 1.dp.toPx())
                }
                repeat(3) { led ->
                    drawCircle(if (rack == 0) gold else blue, 1.6.dp.toPx(),
                        Offset(top.x + w * (.66f + led * .08f), top.y + h * .53f))
                }
            }
        }
        titles.forEachIndexed { i, title ->
            val shape = RoundedCornerShape(9.dp)
            Row(Modifier.align(if (i % 2 == 0) Alignment.TopStart else Alignment.TopEnd)
                .padding(top = if (i < 2) 12.dp else 67.dp).width(labelWidth)
                .background(StudioPanel, shape).border(1.dp, StudioLine, shape)
                .padding(horizontal = 5.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Icon(icons[i], null, Modifier.size(14.dp), tint = StudioBlue)
                Spacer(Modifier.width(4.dp))
                Text(title, color = StudioText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun StudioResources(facts: ServerFacts?) {
    val cpu = facts?.cpuUsedPercent
    val ram = facts?.takeIf { it.ramMb > 0 }?.ramUsedPercent
    val disk = facts?.takeIf { it.diskMb > 0 }?.diskUsedPercent
    Surface(color = StudioPanel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, StudioLine)) {
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
    Text("SERVICES", Modifier.padding(vertical = 6.dp), color = StudioMuted,
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
internal fun StudioServiceRow(name: String, icon: ImageVector, status: String, tint: Color,
    action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = StudioPanel, border = BorderStroke(1.dp, StudioLine)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = StudioGold.copy(alpha = .12f)) {
                    Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = StudioGold)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(name, color = StudioText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Managed service", color = StudioMuted, fontSize = 11.sp)
                }
                TextButton(onClick, enabled = enabled) {
                    Text(action, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (status == "Traffic verified") Icons.Default.CheckCircleOutline else Icons.Default.Info,
                    null, Modifier.size(16.dp), tint = tint)
                Text(status, Modifier.padding(start = 7.dp), color = tint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun StudioReadiness(udp53: Boolean?, dnsVerified: Boolean, onReview: () -> Unit) {
    Surface(color = StudioPanel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, StudioLine)) {
        Row(Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, null, Modifier.size(18.dp), tint = StudioBlue)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(if (dnsVerified) "DNS traffic verified" else "DNS verification pending",
                    color = if (dnsVerified) Mx.Green else StudioMuted, fontSize = 10.sp)
                Text(when (udp53) {
                    true -> "UDP 53 available"
                    false -> "UDP 53 occupied"
                    null -> "UDP 53 needs inspection"
                }, color = StudioText, fontSize = 11.sp)
            }
            Button(onReview, shape = RoundedCornerShape(7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StudioGold, contentColor = AppTheme.colors.onPrimary),
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
        Text("Last operation", Modifier.padding(horizontal = 7.dp), color = StudioText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(message, color = StudioMuted, fontSize = 10.sp)
    }
}
