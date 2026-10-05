package com.example.ui.vip

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.freeconfigs.FreeConfigsUiState
import com.example.ui.freeconfigs.FreeNode
import com.example.ui.freeconfigs.NodeHealth
import com.example.ui.protocols.InterFamily
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.ui.protocols.labCard

/** Gold on black: the VIP section's own palette, in the shape the lab screens share. */
val DarkVipColors = LabColors(
    true, Color(0xFF07070A), Color(0xFF5A4318), Color(0xFF121217), Color(0xFF1E1E26), Color(0x14FFFFFF), Color(0x0FFFFFFF),
    Color(0xFFF6F3EC), Color(0xFFA19D94), Color(0xFF6B6860),
    Color(0xFFD4A651), Color(0xFF9C7230), Color(0xFF1A1205), Color(0x26D4A651),
    Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFF87171), Color(0xFF60A5FA)
)

val LightVipColors = LabColors(
    false, Color(0xFFFBF8F1), Color(0xFFF0DDB0), Color(0xFFFFFFFF), Color(0xFFF5EEDF), Color(0x1F8A6220), Color(0x148A6220),
    Color(0xFF1D1A14), Color(0xFF6E675A), Color(0xFFA39A88),
    Color(0xFFB8862F), Color(0xFF8A6220), Color(0xFF1A1205), Color(0x1FB8862F),
    Color(0xFF16A34A), Color(0xFFD97706), Color(0xFFDC2626), Color(0xFF2563EB)
)

private fun shine(c: LabColors) = if (c.dark) Color(0xFFF7DFA0) else Color(0xFFE9C46A)
private fun gold(c: LabColors) = Brush.linearGradient(listOf(shine(c), c.accent, c.accent2))

/** Everything the VIP screen can ask for; the screen itself holds no state. */
class VipActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onTestAll: () -> Unit = {},
    val onConnect: (FreeNode) -> Unit = {},
    val onDismissMessage: () -> Unit = {}
)

@Composable
fun VipContent(state: VipUiState, c: LabColors, actions: VipActions) {
    Box(
        Modifier.fillMaxSize().background(c.bg)
            .background(Brush.radialGradient(listOf(c.glow.copy(alpha = if (c.dark) 0.55f else 0.6f), Color.Transparent), radius = 950f,
                center = Offset(540f, -160f)))
    ) {
        val best = state.best
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (best != null) 120.dp else 32.dp)
        ) {
            item { AppBar(c, state, actions) }
            item { Header(c) }
            when {
                state.total == 0 && (state.syncing || state.loading) -> item { EmptyCard(c, "Opening VIP", "Getting the VIP servers…", busy = true) }
                state.total == 0 -> item {
                    EmptyCard(c, "No VIP servers yet", state.syncError?.let { "The VIP list could not be downloaded: $it" }
                        ?: "VIP servers appear here as soon as they are published.", action = "Try again", onAction = actions.onRefresh)
                }
                else -> {
                    item { Hero(c, state) }
                    item { Features(c) }
                    state.message?.let { m -> item { Message(c, m, actions.onDismissMessage) } }
                    item { SectionHead(c, "VIP SERVERS · ${state.total}", state.testing, actions.onTestAll) }
                    item {
                        val rows = state.rows
                        Column(Modifier.labCard(c)) {
                            rows.forEachIndexed { i, node -> NodeRow(c, node, best = node == best, last = i == rows.lastIndex) { actions.onConnect(node) } }
                        }
                    }
                }
            }
        }
        if (best != null) Dock(c, best, Modifier.align(Alignment.BottomCenter)) { actions.onConnect(best) }
    }
}

// ---------------------------------------------------------------- parts

@Composable
private fun SquareButton(c: LabColors, icon: ImageVector, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.card).border(1.dp, c.stroke, RoundedCornerShape(12.dp))
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
        else Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AppBar(c: LabColors, state: VipUiState, actions: VipActions) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        SquareButton(c, Icons.AutoMirrored.Rounded.ArrowBack, onClick = actions.onBack)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(CircleShape).background(gold(c)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.WorkspacePremium, null, tint = c.onAccent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            LabText("VIP", c.onAccent, 12.sp, FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        SquareButton(c, Icons.Rounded.Refresh, busy = state.syncing, onClick = actions.onRefresh)
    }
}

@Composable
private fun Header(c: LabColors) {
    Column(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 18.dp)) {
        Text(
            "MAXIMUS VIP", maxLines = 1,
            style = TextStyle(brush = gold(c), fontFamily = InterFamily, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp)
        )
        Spacer(Modifier.height(4.dp))
        LabText("Private servers, tested on your network", c.text2, 14.sp)
    }
}

@Composable
private fun Hero(c: LabColors, s: VipUiState) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(c.cardAlt, c.card)))
            .border(1.dp, c.accent.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(gold(c)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Verified, null, tint = c.onAccent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                LabText("Private network", c.text, 16.sp, FontWeight.Bold, maxLines = 1)
                LabText("${s.total} hand-picked server${if (s.total == 1) "" else "s"}", c.text2, 12.5.sp, maxLines = 1)
            }
            // Green only while the VPN really runs through a VIP server.
            val tint = if (s.connectedHere) c.good else c.accent
            Row(
                Modifier.clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.13f)).padding(start = 7.dp, end = 9.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (s.connectedHere) Icons.Rounded.Check else Icons.Rounded.Shield, null, tint = tint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                LabText(if (s.connectedHere) "Protected" else "Private", tint, 11.5.sp, FontWeight.SemiBold, maxLines = 1)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Spacer(Modifier.height(14.dp))
        Row {
            Stat(c, s.best?.latencyMs?.toString() ?: "—", if (s.best != null) "ms" else "", "Best ping", Modifier.weight(1f))
            Stat(c, "${s.online.size}", "/ ${s.total}", "Online", Modifier.weight(1f))
            Stat(c, "${s.encrypted}", "/ ${s.total}", "Encrypted", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(c: LabColors, value: String, unit: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            LabText(value, c.text, 22.sp, FontWeight.ExtraBold, letterSpacing = (-0.5).sp, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                LabText(unit, c.text2, 12.sp, FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        LabText(label, c.text2, 12.sp, maxLines = 1)
    }
}

@Composable
private fun Features(c: LabColors) {
    Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Feature(c, Icons.Rounded.Lock, "Curated", "By MAXIMUS", Modifier.weight(1f))
        Feature(c, Icons.Rounded.Bolt, "Live test", "Your network", Modifier.weight(1f))
        Feature(c, Icons.Rounded.Sync, "Auto sync", "Every 6 h", Modifier.weight(1f))
    }
}

@Composable
private fun Feature(c: LabColors, icon: ImageVector, title: String, body: String, modifier: Modifier) {
    Row(modifier.labCard(c, 14.dp).padding(horizontal = 9.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Column {
            LabText(title, c.text, 12.sp, FontWeight.SemiBold, maxLines = 1)
            LabText(body, c.text2, 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SectionHead(c: LabColors, title: String, testing: Boolean, onTest: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(title, c.text2, 12.sp, FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (!testing) Row(Modifier.clickable(onClick = onTest), verticalAlignment = Alignment.CenterVertically) {
            LabText("Test all", c.accent, 13.sp, FontWeight.SemiBold, maxLines = 1)
            Icon(Icons.Rounded.ChevronRight, null, tint = c.accent, modifier = Modifier.size(15.dp))
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), color = c.accent, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
            LabText("Testing", c.accent, 13.sp, FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun Flag(c: LabColors, country: String?) {
    val flag = FreeConfigsUiState.flagOf(country)
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(c.cardAlt).border(1.5.dp, c.accent.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (flag != null) Text(flag, fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.clip(CircleShape))
        else Icon(Icons.Rounded.WorkspacePremium, null, tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Tag(text: String, fg: Color, bg: Color, icon: ImageVector? = null) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
        }
        LabText(text, fg, 10.5.sp, FontWeight.SemiBold, letterSpacing = 0.2.sp, maxLines = 1)
    }
}

@Composable
private fun NodeRow(c: LabColors, node: FreeNode, best: Boolean, last: Boolean, onClick: () -> Unit) {
    val healthColor = when (node.health) {
        NodeHealth.FAST -> c.good
        NodeHealth.SLOW -> c.okay
        NodeHealth.OFFLINE -> c.bad
        NodeHealth.QUEUED -> c.text3
    }
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(if (best) Brush.horizontalGradient(listOf(c.accentSoft, Color.Transparent)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Flag(c, node.country)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabText(FreeConfigsUiState.countryName(node.country) ?: node.profile.name.ifBlank { "VIP server" }, c.text, 15.5.sp, FontWeight.SemiBold,
                        maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (best) {
                        Spacer(Modifier.width(7.dp))
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(gold(c)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            LabText("BEST", c.onAccent, 10.sp, FontWeight.ExtraBold, letterSpacing = 0.8.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val secure = node.secureLabel()
                    if (secure != null) Tag(secure, c.good, c.good.copy(alpha = 0.13f), Icons.Rounded.Lock)
                    else Tag("Unencrypted", c.okay, c.okay.copy(alpha = 0.14f), Icons.Rounded.WarningAmber)
                    (listOf(node.protocol) + node.tags).filter { it != secure }.distinct().take(2).forEach { Tag(it, c.text2, c.cardAlt) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(healthColor))
                    Spacer(Modifier.width(6.dp))
                    LabText(node.latencyMs?.let { "$it ms" } ?: "—", c.text, 15.sp, FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.height(3.dp))
                LabText(
                    when (node.health) {
                        NodeHealth.FAST -> if ((node.latencyMs ?: Long.MAX_VALUE) <= EXCELLENT_MS) "Excellent" else "Fast"
                        NodeHealth.SLOW -> "Slow"; NodeHealth.OFFLINE -> "Offline"; NodeHealth.QUEUED -> "Queued"
                    }, c.text2, 11.5.sp, maxLines = 1
                )
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

private const val EXCELLENT_MS = 150L

@Composable
private fun Message(c: LabColors, text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.okay.copy(alpha = 0.12f))
            .clickable(onClick = onDismiss).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.WarningAmber, null, tint = c.okay, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        LabText(text, c.text, 12.5.sp, maxLines = 3)
    }
}

@Composable
private fun EmptyCard(c: LabColors, title: String, body: String, busy: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.padding(top = 4.dp).fillMaxWidth().labCard(c).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.5.dp)
            Spacer(Modifier.height(12.dp))
        }
        LabText(title, c.text, 16.sp, FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        LabText(body, c.text2, 13.sp, maxLines = 4, lineHeight = 19.sp)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(gold(c)).clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 10.dp)) {
                LabText(action, c.onAccent, 14.sp, FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Dock(c: LabColors, best: FreeNode, modifier: Modifier, onConnect: () -> Unit) {
    Box(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg, c.bg))).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp)).background(gold(c)).clickable(onClick = onConnect)
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                LabText("Connect VIP", c.onAccent, 16.sp, FontWeight.ExtraBold, maxLines = 1)
                LabText(
                    listOfNotNull(FreeConfigsUiState.countryName(best.country), "${best.protocol} ${best.secureLabel().orEmpty()}".trim(),
                        best.latencyMs?.let { "$it ms" }).joinToString(" · "),
                    c.onAccent.copy(alpha = 0.72f), 12.sp, maxLines = 1
                )
            }
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(c.onAccent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
            }
        }
    }
}
