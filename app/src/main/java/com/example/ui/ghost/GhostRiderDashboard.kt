package com.example.ui.ghost

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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpn.safety.GhostRiderView

/** The one futuristic surface in the app: Ghost Rider's dark privacy dashboard. */
private object GR {
    val accent = Color(0xFF7C5CFF)
    val accent2 = Color(0xFF22D3EE)
    val good = Color(0xFF34E0A1)
    val bad = Color(0xFFFF3D6A)
    val text = Color(0xFFEAF0FF)
    val text2 = Color(0xFF9AA3C4)
    val text3 = Color(0xFF636B8C)
    val tile = Color(0xFF141826)
    val tileHi = Color(0xFF1C2134)
    val stroke = Color(0x24A6B0FF)
    val strokeHi = Color(0x40A6B0FF)
    val mono = FontFamily.Monospace
}

/**
 * Draws the Ghost Rider dashboard from a [GhostRiderView.Model] built off real connection state. It
 * only draws; the two actions are passed in. [model.onTor] decides whether the primary action offers to
 * route through Tor or reports that it already is.
 */
@Composable
fun GhostRiderDashboard(
    model: GhostRiderView.Model,
    onRouteThroughTor: () -> Unit,
    onPanic: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF11152A), Color(0xFF0A0C14))))
            .border(1.dp, GR.stroke, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(GR.accent, Color(0xFF4330A8)))),
                contentAlignment = Alignment.Center
            ) { Text("👻", fontSize = 19.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ghost Rider", color = GR.text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(7.dp))
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(5.dp))
                            .border(1.dp, GR.strokeHi, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                    ) { Text("ABOVE GOD", color = GR.accent2, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = GR.mono) }
                }
                Text("Maximum privacy · Always fails closed", color = GR.text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Hero
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(92.dp).clip(CircleShape)
                        .background(if (model.connected) GR.accent.copy(alpha = 0.14f) else GR.tile)
                        .border(1.5.dp, if (model.connected) GR.strokeHi else GR.stroke, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = if (model.connected) GR.accent2 else GR.text3, modifier = Modifier.size(38.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(model.heroTitle, color = GR.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(model.exitLine, color = GR.text2, fontSize = 11.5.sp, fontFamily = GR.mono)
            }
        }

        // Traffic path
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(GR.tile)
                .border(1.dp, GR.stroke, RoundedCornerShape(15.dp)).padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("TRAFFIC PATH", color = GR.text3, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                if (model.connected) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(GR.good))
                    Spacer(Modifier.width(5.dp))
                    Text("LIVE", color = GR.good, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            model.hops.forEachIndexed { i, hop ->
                HopRow(hop)
                if (i != model.hops.lastIndex) {
                    Box(Modifier.padding(start = 16.dp).width(2.dp).height(14.dp).background(GR.strokeHi))
                }
            }
        }

        // Protections
        Spacer(Modifier.height(11.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ProtectionTile(Modifier.weight(1f), "Kill switch", "No traffic escapes if the tunnel drops.", model.killSwitch)
            ProtectionTile(Modifier.weight(1f), "Fails closed", "Only Disconnect ends protection.", model.failsClosed)
        }
        Spacer(Modifier.height(9.dp))
        ProtectionTile(Modifier.fillMaxWidth(), "Leak protection", "IPv6 blackholed, DNS over HTTPS only, WebRTC held inside the tunnel.", model.leakBlocked, badge = "IPv6 · DNS · WebRTC")

        // Primary action
        Spacer(Modifier.height(13.dp))
        if (model.onTor) {
            Row(
                modifier = Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(13.dp))
                    .background(GR.accent.copy(alpha = 0.14f)).border(1.dp, GR.strokeHi, RoundedCornerShape(13.dp)),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = GR.accent2, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Routed through Tor", color = GR.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(GR.accent, Color(0xFF5B3FE0)))).clickable { onRouteThroughTor() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Route through Tor", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Panic lock
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFFF3D6A), Color(0xFFB91342)))).clickable { onPanic() },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Panic lock", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text("Disconnect · wipe secret chats · new identity", color = Color.White.copy(alpha = 0.85f), fontSize = 9.5.sp)
            }
        }
    }
}

@Composable
private fun HopRow(hop: GhostRiderView.Hop) {
    val tag = when (hop.role) {
        GhostRiderView.Role.DEVICE -> "DEVICE" to GR.text3
        GhostRiderView.Role.ENTRY -> "ENTRY" to GR.accent2
        GhostRiderView.Role.MIDDLE -> "RELAY" to GR.accent2
        GhostRiderView.Role.EXIT -> "EXIT" to GR.accent
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(GR.tileHi).border(1.dp, GR.strokeHi, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) { Box(Modifier.size(8.dp).clip(CircleShape).background(tag.second)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hop.label, color = GR.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(hop.detail, color = GR.text3, fontSize = 10.5.sp, fontFamily = GR.mono)
        }
        Box(
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tag.second.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 3.dp)
        ) { Text(tag.first, color = tag.second, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) }
    }
}

@Composable
private fun ProtectionTile(modifier: Modifier, title: String, detail: String, on: Boolean, badge: String? = null) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(13.dp)).background(GR.tile).border(1.dp, GR.stroke, RoundedCornerShape(13.dp)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = if (on) GR.good else GR.text3, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(title, color = GR.text2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(badge ?: if (on) "ON" else "OFF", color = if (on) GR.good else GR.text3, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text(detail, color = GR.text3, fontSize = 10.5.sp)
    }
}
