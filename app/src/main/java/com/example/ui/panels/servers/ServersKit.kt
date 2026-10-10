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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.ServerToolCatalog

/**
 * Server screens palette: graphite surfaces with hairline borders and one blue accent. Colour is
 * kept for meaning (status, flags); everything else stays neutral.
 */
internal object Sv {
    val Background = Color(0xFF090A0F)
    val Card = Color(0xFF11141B)
    val CardBorder = Color(0xFF1F2430)
    val Divider = Color(0xFF1A1F29)
    val Inset = Color(0xFF0C0E14)
    val Raised = Color(0xFF171B24)
    val Accent = Color(0xFF3B82F6)
    val AccentSoft = Color(0xFF14213A)
    val Blue = Color(0xFF60A5FA)
    val Text = Color(0xFFF4F6FA)
    val TextSoft = Color(0xFFC9D1DD)
    val Muted = Color(0xFF8B95A5)
    val Dim = Color(0xFF5E6878)
    val Green = Color(0xFF34D399)
    val GreenSoft = Color(0xFF0E2A22)
    val Success = Color(0xFF10B981)
    val Amber = Color(0xFFF5B83D)
    val AmberSoft = Color(0xFF2A2210)
    val Red = Color(0xFFF87171)
    val RedSoft = Color(0xFF2C1517)
    val Terminal = Color(0xFF07080C)
}

internal val Mono = FontFamily.Monospace

// ------------------------------------------------------------------ flags and icons

/**
 * Small vector flags: Iran for Iranian servers, Germany for servers abroad. Drawn, not emoji, so
 * they look the same on every phone (some show emoji flags as two letters).
 */
@Composable
internal fun FlagIcon(location: ServerLocation, modifier: Modifier = Modifier, height: Dp = 16.dp) {
    Canvas(
        modifier
            .size(width = height * 1.5f, height = height)
            .clip(RoundedCornerShape(2.5.dp))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(2.5.dp))
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

internal fun locationName(location: ServerLocation) = if (location == ServerLocation.IRAN) "Iran" else "Abroad"

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

/** A tool's icon in a neutral rounded square. [accent] tints it for the one item in focus. */
@Composable
internal fun ToolBadge(id: String, size: Dp = 36.dp, accent: Boolean = false) =
    IconTile(toolIcon(id), size, accent)

@Composable
internal fun IconTile(icon: ImageVector, size: Dp = 36.dp, accent: Boolean = false) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(if (accent) Sv.AccentSoft else Sv.Raised)
            .border(1.dp, if (accent) Sv.Accent.copy(alpha = 0.35f) else Sv.CardBorder, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (accent) Sv.Blue else Sv.TextSoft, modifier = Modifier.size(size * 0.5f))
    }
}

// ------------------------------------------------------------------ surfaces and lists

/** A flat card with a hairline border. Rows inside pad themselves. */
@Composable
internal fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Sv.Card,
        border = BorderStroke(1.dp, Sv.CardBorder)
    ) { Column { content() } }
}

/** A padded card for free content. */
@Composable
internal fun SvCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    GroupCard(modifier) { Box(Modifier.padding(padding)) { content() } }
}

/** One line of a list card: leading visual, title and subtitle, trailing content. */
@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    subtitleMono: Boolean = false,
    caption: (@Composable () -> Unit)? = null,
    titleColor: Color = Sv.Text,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = titleColor, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Sv.Muted, fontSize = if (subtitleMono) 12.sp else 13.sp,
                    fontFamily = if (subtitleMono) Mono else null, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
            }
            caption?.invoke()
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
internal fun Chevron() = Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Sv.Dim, modifier = Modifier.size(20.dp))

/** A hairline between rows, inset to line up with row text. */
@Composable
internal fun RowDivider(start: Dp = 14.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(1.dp).background(Sv.Divider))
}

/** Rows separated by hairlines. */
@Composable
internal fun <T> Rows(items: List<T>, dividerStart: Dp = 14.dp, row: @Composable (T) -> Unit) {
    items.forEachIndexed { i, item ->
        if (i > 0) RowDivider(dividerStart)
        row(item)
    }
}

/** A screen heading: large title, one-line summary, optional action on the right. */
@Composable
internal fun PageHeader(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Sv.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Sv.Muted, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

/** A small heading above a card. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Sv.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

enum class Tone { NEUTRAL, GOOD, WARN, BAD, ACCENT }

private fun toneColor(t: Tone) = when (t) {
    Tone.NEUTRAL -> Sv.Muted
    Tone.GOOD -> Sv.Green
    Tone.WARN -> Sv.Amber
    Tone.BAD -> Sv.Red
    Tone.ACCENT -> Sv.Blue
}

/** A compact status label ("Running", "Installed · 2"). */
@Composable
internal fun Badge(text: String, tone: Tone = Tone.NEUTRAL, dot: Boolean = false) {
    val c = toneColor(tone)
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = if (tone == Tone.NEUTRAL) 0.10f else 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = c, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Kept for small neutral labels. */
@Composable
internal fun Chip(text: String, color: Color = Sv.Muted, background: Color = Sv.Raised, leading: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        leading?.invoke()
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

enum class Health { ONLINE, OFFLINE, CHECKING, SIGN_IN }

internal fun healthColor(h: Health) = when (h) {
    Health.ONLINE -> Sv.Green
    Health.OFFLINE -> Sv.Red
    Health.CHECKING -> Sv.Dim
    Health.SIGN_IN -> Sv.Amber
}

@Composable
internal fun StatusDot(health: Health, size: Dp = 7.dp) {
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse"
    )
    Box(Modifier.size(size).alpha(if (health == Health.CHECKING) pulse else 1f).clip(CircleShape).background(healthColor(health)))
}

/** Dot plus "96 ms" / "Offline" / "Sign in". */
@Composable
internal fun HealthLabel(health: Health, latencyMs: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(health)
        Spacer(Modifier.width(6.dp))
        Text(
            when (health) {
                Health.ONLINE -> latencyMs?.let { "$it ms" } ?: "Online"
                Health.OFFLINE -> "Offline"
                Health.CHECKING -> "Checking"
                Health.SIGN_IN -> "Sign in"
            },
            color = if (health == Health.ONLINE) Sv.TextSoft else healthColor(health),
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            fontFamily = if (health == Health.ONLINE && latencyMs != null) Mono else null
        )
    }
}

/** A soft moving placeholder bar for values that are loading. */
@Composable
internal fun Shimmer(modifier: Modifier = Modifier.width(80.dp).height(10.dp)) {
    val a by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a"
    )
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Sv.CardBorder.copy(alpha = a)))
}

/** A thin bar for a percentage (RAM, disk). */
@Composable
internal fun Meter(percent: Int, modifier: Modifier = Modifier) {
    val color = when {
        percent >= 90 -> Sv.Red
        percent >= 75 -> Sv.Amber
        else -> Sv.Blue
    }
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Sv.Divider)) {
        Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

/** "2 d 4 h", "35 min". */
internal fun uptimeText(sec: Long): String = when {
    sec <= 0 -> "—"
    sec < 3600 -> "${sec / 60} min"
    sec < 86_400 -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    else -> "${sec / 86_400} d ${(sec % 86_400) / 3600} h"
}

// ------------------------------------------------------------------ buttons

/** The main action: accent fill, white label, one line. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = Sv.Accent,
    compact: Boolean = false
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(if (compact) 36.dp else 46.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = Color.White,
            disabledContainerColor = Sv.Raised, disabledContentColor = Sv.Dim
        )
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(if (compact) 16.dp else 18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
    }
}

/** A secondary action: raised neutral surface, hairline border. */
@Composable
internal fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Sv.Text,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(if (compact) 36.dp else 46.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp),
        border = BorderStroke(1.dp, Sv.CardBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Sv.Raised)
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(if (compact) 16.dp else 18.dp), tint = if (enabled) tint.copy(alpha = 0.85f) else Sv.Dim)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (enabled) tint else Sv.Dim, fontWeight = FontWeight.Medium, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
    }
}

/** A text-only action ("Install", "Turn off"). */
@Composable
internal fun TextAction(text: String, onClick: () -> Unit, color: Color = Sv.Blue, enabled: Boolean = true, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) color else Sv.Dim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = if (enabled) color else Sv.Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Old name for [TextAction]. */
@Composable
internal fun SmallAction(text: String, onClick: () -> Unit, color: Color = Sv.Blue, enabled: Boolean = true) =
    TextAction(text, onClick, color, enabled)

// ------------------------------------------------------------------ page frame and inputs

/** A full-screen page over Panels: back arrow, title, scrolling content and an optional bottom bar. */
@Composable
internal fun ScreenFrame(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    subtitleMono: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Sv.Background)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Sv.Text) }
            if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Sv.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = Sv.Muted, fontSize = 12.sp, maxLines = 1,
                    fontFamily = if (subtitleMono) Mono else null, overflow = TextOverflow.Ellipsis)
            }
            actions?.invoke()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Sv.Divider))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
            Spacer(Modifier.height(20.dp))
        }
        if (bottomBar != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Sv.Divider))
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
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    mono: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontFamily = if (mono) Mono else null),
        placeholder = if (placeholder.isNotBlank()) ({ Text(placeholder, color = Sv.Dim, fontFamily = if (mono) Mono else null) }) else null,
        supportingText = supporting?.let { { Text(it, color = Sv.Dim, fontSize = 12.sp) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboard,
            autoCorrect = false
        ),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = when {
            trailing != null -> trailing
            secret -> ({
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide" else "Show", tint = Sv.Muted)
                }
            })
            else -> null
        },
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Sv.Text, unfocusedTextColor = Sv.Text,
            focusedBorderColor = Sv.Accent, unfocusedBorderColor = Sv.CardBorder,
            focusedLabelColor = Sv.Blue, unfocusedLabelColor = Sv.Muted,
            cursorColor = Sv.Blue, focusedContainerColor = Sv.Card, unfocusedContainerColor = Sv.Card
        )
    )
}

/** A message line: info, warning, error or success. */
@Composable
internal fun Notice(text: String, kind: NoticeKind = NoticeKind.INFO) {
    val (fg, icon) = when (kind) {
        NoticeKind.INFO -> Sv.Muted to Icons.Rounded.Info
        NoticeKind.WARN -> Sv.Amber to Icons.Rounded.WarningAmber
        NoticeKind.ERROR -> Sv.Red to Icons.Rounded.ErrorOutline
        NoticeKind.OK -> Sv.Green to Icons.Rounded.CheckCircle
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (kind == NoticeKind.INFO) Sv.Card else fg.copy(alpha = 0.07f))
            .border(1.dp, if (kind == NoticeKind.INFO) Sv.CardBorder else fg.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(text, color = Sv.TextSoft, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

enum class NoticeKind { INFO, WARN, ERROR, OK }
