package com.example.ui.freeconfigs

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.ui.protocols.labCard
import com.example.vpn.hub.FreeConfigList

/** Everything the Free Configs screen can ask for; the screen itself holds no state. */
class FreeConfigsActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onTestAll: () -> Unit = {},
    val onStopTest: () -> Unit = {},
    val onTestOne: (FreeNode) -> Unit = {},
    val onSite: (String?) -> Unit = {},
    val onSort: (FreeSort) -> Unit = {},
    val onProtocol: (String?) -> Unit = {},
    val onToggleHidden: () -> Unit = {},
    val onConnect: (FreeNode) -> Unit = {},
    val onDismissMessage: () -> Unit = {}
)

@Composable
fun FreeConfigsContent(
    state: FreeConfigsUiState,
    c: LabColors,
    actions: FreeConfigsActions,
    /** "2 h ago", "in 4 h": formatted by the caller so this stays free of Android calls. */
    ago: (Long) -> String,
    until: (Long) -> String
) {
    Box(
        Modifier.fillMaxSize().background(c.bg)
            .background(Brush.radialGradient(listOf(c.glow.copy(alpha = if (c.dark) 0.45f else 0.5f), Color.Transparent), radius = 900f,
                center = androidx.compose.ui.geometry.Offset(950f, -120f)))
    ) {
        val best = state.best
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (best != null) 120.dp else 32.dp)
        ) {
            item { AppBar(c, state, actions) }
            item { Header(c) }
            when {
                !state.available -> item { EmptyCard(c, "Not in this build", "This build cannot check the list's signature, so the free list stays off.") }
                !state.enabled -> item {
                    EmptyCard(c, "Free configs are off", "The free list is not downloaded, tested or shown. Turn on Free configs in Settings to use it.")
                }
                state.total == 0 && (state.syncing || state.loading) -> item { EmptyCard(c, "Getting the list", "Downloading and checking the signed list…", busy = true) }
                state.total == 0 -> item {
                    EmptyCard(c, "No list yet", state.syncError?.let { "The list could not be downloaded: $it" } ?: "Pull the list to see free servers.",
                        action = "Get the list", onAction = actions.onRefresh)
                }
                else -> {
                    item { Summary(c, state, ago, until) }
                    item { Segments(c, state.sort, actions.onSort) }
                    item { Chips(c, state, actions.onProtocol) }
                    if (state.siteCounts.isNotEmpty()) item { SiteChips(c, state, actions.onSite) }
                    state.message?.let { m -> item { Message(c, m, actions.onDismissMessage) } }
                    val rows = state.visible
                    item {
                        SectionHead(c, "SERVERS · ${rows.size}",
                            if (state.testing) "Stop · ${state.testDone}/${state.testTotal}" else "Test all",
                            busy = state.testing,
                            onAction = if (state.testing) actions.onStopTest else actions.onTestAll)
                    }
                    if (rows.isEmpty()) item {
                        EmptyCard(c, "Nothing to show", "No server matches, or none answered on your network. Try another filter or test again later.")
                    } else {
                        // One lazy item per server: only the rows on screen are built, and a finished test
                        // batch redraws just the rows that changed instead of the whole card.
                        items(rows, key = { it.profile.id }) { node ->
                            Box(Modifier.padding(bottom = 8.dp).labCard(c, radius = 16.dp)) {
                                NodeRow(c, node, best = node == best, last = true, onTest = { actions.onTestOne(node) }) { actions.onConnect(node) }
                            }
                        }
                    }
                    item { Hidden(c, state, actions.onToggleHidden) }
                    if (state.showHidden) {
                        val offline = state.offline
                        items(offline, key = { "off-" + it.profile.id }) { node ->
                            Box(Modifier.padding(top = 8.dp).labCard(c, radius = 16.dp)) {
                                NodeRow(c, node, best = false, last = true, onTest = { actions.onTestOne(node) }) { actions.onConnect(node) }
                            }
                        }
                    }
                    item { Note(c) }
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
private fun AppBar(c: LabColors, state: FreeConfigsUiState, actions: FreeConfigsActions) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        SquareButton(c, Icons.AutoMirrored.Rounded.ArrowBack, onClick = actions.onBack)
        Spacer(Modifier.weight(1f))
        if (state.available) SquareButton(c, Icons.Rounded.Refresh, busy = state.syncing, onClick = actions.onRefresh)
    }
}

@Composable
private fun Header(c: LabColors) {
    Column(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 18.dp)) {
        LabText("Free Configs", c.text, 28.sp, FontWeight.Bold, letterSpacing = (-0.6).sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        LabText("Public servers, filtered and tested for your network", c.text2, 14.sp)
    }
}

@Composable
private fun Summary(c: LabColors, s: FreeConfigsUiState, ago: (Long) -> String, until: (Long) -> String) {
    Column(Modifier.labCard(c).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(c.accent, c.accent2))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                LabText("MAXIMUS Free", c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                LabText("Public GitHub lists", c.text3, 12.sp, maxLines = 1)
            }
            if (s.verified) Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(c.good.copy(alpha = 0.12f)).padding(start = 7.dp, end = 9.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Check, null, tint = c.good, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                LabText("Verified", c.good, 11.5.sp, FontWeight.SemiBold, maxLines = 1)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            LabText("${s.online.size}", c.text, 44.sp, FontWeight.Bold, letterSpacing = (-1.5).sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            LabText(
                if (s.tested == 0) "of ${s.total} not tested yet" else "online of ${s.tested} tested",
                c.text2, 14.sp, maxLines = 1, modifier = Modifier.padding(bottom = 7.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        HealthBar(c, s)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(c, c.good, "Fast", s.fast)
            Legend(c, c.okay, "Slow", s.slow)
            Legend(c, c.bad, "Offline", s.offline.size)
            Legend(c, idle(c), "Not tested", s.queued)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, null, tint = c.text2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            LabText(if (s.lastUpdated == 0L) "Not updated yet" else "Updated ${ago(s.lastUpdated)}", c.text2, 12.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (s.nextUpdate > 0L) LabText("Next update ${until(s.nextUpdate)}", c.text2, 12.5.sp, maxLines = 1)
        }
    }
}

private fun idle(c: LabColors) = if (c.dark) Color(0xFF4B4E5E) else Color(0xFFCBD0DC)

@Composable
private fun HealthBar(c: LabColors, s: FreeConfigsUiState) {
    val parts = listOf(s.fast to c.good, s.slow to c.okay, s.offline.size to c.bad, s.queued to idle(c)).filter { it.first > 0 }
    Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (parts.isEmpty()) Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(3.dp)).background(idle(c)))
        parts.forEach { (count, color) ->
            Box(Modifier.weight(count.toFloat()).height(8.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

@Composable
private fun Legend(c: LabColors, color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        LabText(label, c.text2, 12.sp, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        LabText("$count", c.text, 12.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Segments(c: LabColors, sort: FreeSort, onSort: (FreeSort) -> Unit) {
    Row(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cardAlt).padding(3.dp)) {
        FreeSort.values().forEach { option ->
            val on = option == sort
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (on) c.card else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, c.stroke, RoundedCornerShape(9.dp)) else Modifier)
                    .clickable { onSort(option) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) { LabText(option.label, if (on) c.text else c.text2, 13.sp, if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1) }
        }
    }
}

@Composable
private fun Chips(c: LabColors, s: FreeConfigsUiState, onProtocol: (String?) -> Unit) {
    val chips = listOf<Pair<String?, Int>>(null to s.shown.size) + s.protocolCounts
    LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(chips) { (protocol, count) ->
            val on = protocol == s.protocol
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (on) c.accentSoft else Color.Transparent)
                    .border(1.dp, if (on) Color.Transparent else c.stroke, RoundedCornerShape(8.dp))
                    .clickable { onProtocol(protocol) }.padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                LabText(protocol ?: "All", if (on) c.accent else c.text2, 12.5.sp, if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                LabText("$count", if (on) c.accent.copy(alpha = 0.7f) else c.text3, 12.5.sp, maxLines = 1)
            }
        }
    }
}

/** The brand colour of each site tag, so the filter and the row badges read at a glance. */
private fun siteColor(c: LabColors, tag: String): Color = when (tag) {
    "YT" -> Color(0xFFE5484D)
    "TG" -> Color(0xFF2AABEE)
    else -> c.text
}

@Composable
private fun SiteChips(c: LabColors, s: FreeConfigsUiState, onSite: (String?) -> Unit) {
    val names = FreeConfigsUiState.SITES.toMap()
    LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(s.siteCounts) { (tag, count) ->
            val on = tag == s.site
            val color = siteColor(c, tag)
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (on) color.copy(alpha = 0.14f) else Color.Transparent)
                    .border(1.dp, if (on) color.copy(alpha = 0.5f) else c.stroke, RoundedCornerShape(8.dp))
                    .clickable { onSite(if (on) null else tag) }.padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                LabText("Opens ${names[tag] ?: tag}", if (on) c.text else c.text2, 12.5.sp, if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                LabText("$count", c.text3, 12.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionHead(c: LabColors, title: String, action: String, busy: Boolean, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(title, c.text3, 12.sp, FontWeight.SemiBold, letterSpacing = 0.2.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Row(Modifier.clickable(onClick = onAction), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(12.dp), color = c.accent, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
            }
            LabText(action, c.accent, 12.sp, FontWeight.SemiBold, maxLines = 1)
            if (!busy) Icon(Icons.Rounded.ChevronRight, null, tint = c.accent, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun Flag(c: LabColors, country: String?) {
    val flag = FreeConfigsUiState.flagOf(country)
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(c.cardAlt).border(1.dp, c.stroke, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (flag != null) Text(flag, fontSize = 30.sp, textAlign = TextAlign.Center, modifier = Modifier.clip(CircleShape))
        else Icon(Icons.Rounded.Shield, null, tint = c.text3, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NodeRow(c: LabColors, node: FreeNode, best: Boolean, last: Boolean, onTest: () -> Unit = {}, onClick: () -> Unit) {
    val healthColor = when (node.health) {
        NodeHealth.FAST -> c.good
        NodeHealth.SLOW -> c.okay
        NodeHealth.OFFLINE -> c.bad
        NodeHealth.QUEUED -> idle(c)
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
                    LabText(FreeConfigsUiState.countryName(node.country) ?: "Unknown location", c.text, 15.sp, FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false))
                    if (best) {
                        Spacer(Modifier.width(7.dp))
                        Box(Modifier.clip(RoundedCornerShape(5.dp)).background(c.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            LabText("BEST", c.accent, 10.sp, FontWeight.Bold, letterSpacing = 0.3.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // The sites it opened first, in their colours, then protocol and transport.
                    FreeConfigList.SITE_TAGS.filter { it in node.sites }.forEach { tag ->
                        val color = siteColor(c, tag)
                        Box(Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            LabText(tag, color, 10.5.sp, FontWeight.Bold, letterSpacing = 0.2.sp, maxLines = 1)
                        }
                    }
                    (listOf(node.protocol) + node.tags).take(if (node.sites.size >= 3) 2 else 3).forEach { tag ->
                        Box(Modifier.clip(RoundedCornerShape(5.dp)).background(c.cardAlt).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            LabText(tag, c.text2, 10.5.sp, FontWeight.SemiBold, letterSpacing = 0.2.sp, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // Tapping the result tests this one server again.
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTest).padding(4.dp), horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(healthColor))
                    Spacer(Modifier.width(6.dp))
                    LabText(node.latencyMs?.let { "$it ms" } ?: "Ping", if (node.latencyMs == null) c.accent else c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                }
                Spacer(Modifier.height(3.dp))
                LabText(
                    when (node.health) {
                        NodeHealth.FAST -> "Fast"; NodeHealth.SLOW -> "Slow"; NodeHealth.OFFLINE -> "Offline"; NodeHealth.QUEUED -> "Not tested"
                    }, c.text3, 11.5.sp, maxLines = 1
                )
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

@Composable
private fun Hidden(c: LabColors, s: FreeConfigsUiState, onToggle: () -> Unit) {
    val count = s.offline.size
    if (count == 0) return
    Row(
        Modifier.padding(top = 12.dp).labCard(c).clickable(onClick = onToggle).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.cardAlt), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.VisibilityOff, null, tint = c.text2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            LabText("$count hidden", c.text, 14.5.sp, FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            LabText("Did not answer on your network", c.text2, 12.5.sp, maxLines = 1)
        }
        Icon(if (s.showHidden) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null, tint = c.text3, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Note(c: LabColors) {
    Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp)) {
        Icon(Icons.Rounded.Info, null, tint = c.text3, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(8.dp))
        LabText(
            "Free servers are run by third parties. Use them for browsing and messaging, not for banking or account logins.",
            c.text3, 12.sp, maxLines = 4, lineHeight = 18.sp
        )
    }
}

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
        LabText(body, c.text2, 13.sp, maxLines = 4, modifier = Modifier.fillMaxWidth(), lineHeight = 19.sp)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(c.accent, c.accent2)))
                    .clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 10.dp)
            ) { LabText(action, Color.White, 14.sp, FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

@Composable
private fun Dock(c: LabColors, best: FreeNode, modifier: Modifier, onConnect: () -> Unit) {
    Box(
        modifier.fillMaxWidth().background(c.bg.copy(alpha = 0.92f)).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(c.accent, c.accent2))).clickable(onClick = onConnect)
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                LabText("Connect to fastest", Color.White, 15.5.sp, FontWeight.SemiBold, maxLines = 1)
                LabText(
                    listOfNotNull(FreeConfigsUiState.countryName(best.country), "${best.protocol} ${best.tags.firstOrNull().orEmpty()}".trim(),
                        best.latencyMs?.let { "$it ms" }).joinToString(" · "),
                    Color.White.copy(alpha = 0.82f), 12.sp, maxLines = 1
                )
            }
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
