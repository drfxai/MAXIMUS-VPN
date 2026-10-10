package com.example.ui.panels.servers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.ServerToolCatalog

/** Server screens palette: the Panels navy with a calm blue accent (same family as the Cloudflare tab). */
internal object Sv {
    val Accent = Color(0xFF3B82F6)
    val AccentSoft = Color(0xFF16284A)
    val Blue = Color(0xFF38BDF8)
    val Card = Color(0xFF0C1526)
    val CardBorder = Color(0xFF1B2A44)
    val Inset = Color(0xFF070E1B)
    val Text = Color(0xFFF1F5F9)
    val Muted = Color(0xFF94A3B8)
    val Dim = Color(0xFF64748B)
    val Green = Color(0xFF34D399)
    val GreenSoft = Color(0xFF0B2A22)
    val Success = Color(0xFF10B981)
    val Amber = Color(0xFFFBBF24)
    val AmberSoft = Color(0xFF2B2310)
    val Red = Color(0xFFF87171)
    val RedSoft = Color(0xFF2E1418)
    val Terminal = Color(0xFF050B16)
    val Background = Color(0xFF070D18)
}

/**
 * Small vector flags: Iran for Iranian servers, Germany for servers abroad. Drawn, not emoji, so
 * they look the same on every phone (some show emoji flags as two letters).
 */
@Composable
internal fun FlagIcon(location: ServerLocation, modifier: Modifier = Modifier, height: Dp = 16.dp) {
    Canvas(
        modifier
            .size(width = height * 1.5f, height = height)
            .clip(RoundedCornerShape(3.dp))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(3.dp))
    ) {
        val w = size.width
        val band = size.height / 3f
        when (location) {
            ServerLocation.ABROAD -> {
                drawRect(Color(0xFF000000), Offset(0f, 0f), Size(w, band))
                drawRect(Color(0xFFDD0000), Offset(0f, band), Size(w, band))
                drawRect(Color(0xFFFFCE00), Offset(0f, band * 2), Size(w, size.height - band * 2))
            }
            ServerLocation.IRAN -> {
                val green = Color(0xFF239F40)
                val red = Color(0xFFDA0000)
                drawRect(green, Offset(0f, 0f), Size(w, band))
                drawRect(Color.White, Offset(0f, band), Size(w, band))
                drawRect(red, Offset(0f, band * 2), Size(w, size.height - band * 2))
                // The takbir borders, as fine dashes along the white band.
                val dash = w / 22f
                var x = dash / 2
                while (x < w) {
                    drawRect(green, Offset(x, band), Size(dash * 0.55f, band * 0.09f))
                    drawRect(red, Offset(x, band * 2 - band * 0.09f), Size(dash * 0.55f, band * 0.09f))
                    x += dash
                }
                // The emblem, simplified: two crescents and a central stem.
                val c = Offset(w / 2, band * 1.5f)
                val r = band * 0.34f
                val stroke = Stroke(width = band * 0.09f)
                drawArc(red, 110f, 140f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = stroke)
                drawArc(red, -70f, 140f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = stroke)
                val stem = Path().apply {
                    moveTo(c.x, c.y - r * 1.05f)
                    lineTo(c.x + r * 0.16f, c.y + r * 0.2f)
                    lineTo(c.x, c.y + r * 1.05f)
                    lineTo(c.x - r * 0.16f, c.y + r * 0.2f)
                    close()
                }
                drawPath(stem, red)
            }
        }
    }
}

internal fun toolIcon(id: String): ImageVector = when (id) {
    ServerToolCatalog.XUI -> Icons.Rounded.Dashboard
    ServerToolCatalog.DNSTT -> Icons.Rounded.Dns
    ServerToolCatalog.MAXIMUS_TUNNEL -> Icons.Rounded.SyncAlt
    ServerToolCatalog.HYSTERIA2 -> Icons.Rounded.Bolt
    ServerToolCatalog.KEY_LOGIN -> Icons.Rounded.Key
    ServerToolCatalog.FIREWALL -> Icons.Rounded.Shield
    ServerToolCatalog.FAIL2BAN -> Icons.Rounded.Block
    ServerToolCatalog.AUTO_UPDATES -> Icons.Rounded.SystemUpdateAlt
    ServerToolCatalog.BBR -> Icons.Rounded.Speed
    else -> Icons.Rounded.Memory
}

internal fun toolTint(id: String): Color = when (id) {
    ServerToolCatalog.XUI -> Color(0xFF60A5FA)
    ServerToolCatalog.DNSTT -> Color(0xFF2DD4BF)
    ServerToolCatalog.MAXIMUS_TUNNEL -> Color(0xFFC084FC)
    ServerToolCatalog.HYSTERIA2 -> Color(0xFFFBBF24)
    ServerToolCatalog.KEY_LOGIN, ServerToolCatalog.FIREWALL, ServerToolCatalog.FAIL2BAN, ServerToolCatalog.AUTO_UPDATES -> Color(0xFF34D399)
    else -> Color(0xFF38BDF8)
}

/** A rounded square holding a tool's icon in its colour. */
@Composable
internal fun ToolBadge(id: String, size: Dp = 40.dp) {
    val tint = toolTint(id)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.6f)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(toolIcon(id), null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
internal fun SvCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Sv.Card,
        border = BorderStroke(1.dp, Sv.CardBorder)
    ) { Box(Modifier.padding(padding)) { content() } }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = Sv.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
internal fun Chip(text: String, color: Color = Sv.Muted, background: Color = Sv.Inset, leading: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        leading?.invoke()
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

enum class Health { ONLINE, OFFLINE, CHECKING, SIGN_IN }

@Composable
internal fun StatusDot(health: Health, size: Dp = 8.dp) {
    val color = when (health) {
        Health.ONLINE -> Sv.Green
        Health.OFFLINE -> Sv.Red
        Health.CHECKING -> Sv.Blue
        Health.SIGN_IN -> Sv.Amber
    }
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse"
    )
    Box(Modifier.size(size).alpha(if (health == Health.CHECKING) pulse else 1f).clip(CircleShape).background(color))
}

/** A soft moving placeholder bar for values that are loading. */
@Composable
internal fun Shimmer(modifier: Modifier = Modifier.width(80.dp).height(10.dp)) {
    val a by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a"
    )
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Sv.CardBorder.copy(alpha = a)))
}

/** A thin progress bar for a percentage (RAM, disk). */
@Composable
internal fun Meter(percent: Int, modifier: Modifier = Modifier) {
    val color = when {
        percent >= 90 -> Sv.Red
        percent >= 75 -> Sv.Amber
        else -> Sv.Green
    }
    Box(modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Sv.Inset)) {
        Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** "2 d 4 h", "35 min". */
internal fun uptimeText(sec: Long): String = when {
    sec <= 0 -> "—"
    sec < 3600 -> "${sec / 60} min"
    sec < 86_400 -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    else -> "${sec / 86_400} d ${(sec % 86_400) / 3600} h"
}

@Composable
internal fun LabeledColumn(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** The main action of a card: accent fill, white label, one line. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = Sv.Accent
) {
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = color, contentColor = Color.White,
            disabledContainerColor = Sv.CardBorder, disabledContentColor = Sv.Dim
        )
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** A secondary action: outline, light label, one line. */
@Composable
internal fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Sv.Text,
    enabled: Boolean = true
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        border = BorderStroke(1.dp, Sv.CardBorder)
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(18.dp), tint = if (tint == Sv.Text) Sv.Blue else tint)
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (enabled) tint else Sv.Dim, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** A small text action inside a card ("Restart", "Logs"...). */
@Composable
internal fun SmallAction(text: String, onClick: () -> Unit, color: Color = Sv.Blue, enabled: Boolean = true) {
    Box(
        Modifier.clip(RoundedCornerShape(9.dp)).background(Sv.Inset)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) { Text(text, color = if (enabled) color else Sv.Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}


/** A full-screen page over Panels: back arrow, title, and a scrolling column. */
@Composable
internal fun ScreenFrame(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Sv.Background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Sv.Text)
            }
            if (leading != null) { leading(); androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Sv.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (subtitle.isNotBlank()) Text(subtitle, color = Sv.Muted, fontSize = 12.sp, maxLines = 1)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        }
        if (bottomBar != null) {
            Box(Modifier.fillMaxWidth().background(Sv.Background).padding(horizontal = 16.dp, vertical = 12.dp)) { bottomBar() }
        }
    }
}

/** A text field in the server screens' style. */
@Composable
internal fun SvField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    secret: Boolean = false,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    var visible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true,
        placeholder = if (placeholder.isNotBlank()) ({ Text(placeholder, color = Sv.Dim) }) else null,
        supportingText = supporting?.let { { Text(it, color = Sv.Dim, fontSize = 11.sp) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else keyboard,
            autoCorrect = false
        ),
        visualTransformation = if (secret && !visible) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = when {
            trailing != null -> trailing
            secret -> ({
                androidx.compose.material3.IconButton(onClick = { visible = !visible }) {
                    androidx.compose.material3.Icon(
                        if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (visible) "Hide" else "Show", tint = Sv.Muted
                    )
                }
            })
            else -> null
        },
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = Sv.Text, unfocusedTextColor = Sv.Text,
            focusedBorderColor = Sv.Accent, unfocusedBorderColor = Sv.CardBorder,
            focusedLabelColor = Sv.Blue, unfocusedLabelColor = Sv.Muted,
            cursorColor = Sv.Blue, focusedContainerColor = Sv.Inset, unfocusedContainerColor = Sv.Inset
        )
    )
}

/** A message line: blue info, amber warning or red error. */
@Composable
internal fun Notice(text: String, kind: NoticeKind = NoticeKind.INFO) {
    val (fg, bg, icon) = when (kind) {
        NoticeKind.INFO -> Triple(Sv.Blue, Sv.AccentSoft, Icons.Rounded.Info)
        NoticeKind.WARN -> Triple(Sv.Amber, Sv.AmberSoft, Icons.Rounded.WarningAmber)
        NoticeKind.ERROR -> Triple(Sv.Red, Sv.RedSoft, Icons.Rounded.ErrorOutline)
        NoticeKind.OK -> Triple(Sv.Green, Sv.GreenSoft, Icons.Rounded.CheckCircle)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg.copy(alpha = 0.7f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        androidx.compose.material3.Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, color = Sv.Text.copy(alpha = 0.92f), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

enum class NoticeKind { INFO, WARN, ERROR, OK }
