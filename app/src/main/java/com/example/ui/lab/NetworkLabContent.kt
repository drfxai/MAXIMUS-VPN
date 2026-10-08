package com.example.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.protocols.Gauge
import com.example.ui.protocols.GradientButton
import com.example.ui.protocols.IconTile
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.ui.protocols.Pill
import com.example.ui.protocols.SectionLabel
import com.example.ui.protocols.Stat
import com.example.ui.protocols.labCard
import com.example.ui.protocols.labColors
import com.example.vpn.lab.AutomationLevel
import com.example.vpn.lab.ExperimentState
import com.example.vpn.lab.LabDiscovery
import com.example.vpn.lab.LabExperiment
import com.example.vpn.lab.LabSnapshot
import com.example.vpn.lab.LabStep
import com.example.vpn.lab.LabStore
import com.example.vpn.lab.PromotionState
import com.example.vpn.lab.VerifiedNetworkProfile

/** The LAB's pages: a minimal home and five detail sections (spec sections 40-46), plus Research. */
enum class LabSection(val title: String, val icon: ImageVector) {
    NETWORKS("Networks", Icons.Rounded.CellTower),
    LIVE("Live Tests", Icons.Rounded.MonitorHeart),
    EXPERIMENTS("Experiments", Icons.Rounded.Science),
    DISCOVERIES("Discoveries", Icons.Rounded.Lightbulb),
    VERIFIED("Verified Profiles", Icons.Rounded.Verified),
    RESEARCH("Research", Icons.Rounded.TravelExplore)
}

/** A saved config the user can run an experiment on. Names only; no address or credential reaches the screen. */
data class LabConfigOption(val id: String, val name: String, val detail: String)

/** A research card (Phase 7). Never shown as verified until LAB measured it on this phone. */
data class LabResearchItem(val id: String, val title: String, val state: String, val source: String, val compatibility: String, val at: Long)

data class NetworkLabUiState(
    val snapshot: LabSnapshot = LabSnapshot(),
    val configs: List<LabConfigOption> = emptyList(),
    val research: List<LabResearchItem> = emptyList(),
    val section: LabSection? = null,
    val picking: Boolean = false,
    val vpnOn: Boolean = false
)

/** Everything the LAB screens can ask for; the screens hold no state of their own beyond expanded cards. */
class NetworkLabActions(
    val onOpen: (LabSection?) -> Unit = {},
    val onPick: (Boolean) -> Unit = {},
    val onRun: (String) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onAutomation: (AutomationLevel) -> Unit = {},
    val onRefreshNetwork: () -> Unit = {},
    val onRetest: (String) -> Unit = {},
    val onDisable: (String, Boolean) -> Unit = { _, _ -> },
    val onRetire: (String) -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onResearchRefresh: () -> Unit = {}
)

@Composable
fun NetworkLabContent(state: NetworkLabUiState, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    val c = labColors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        when (val s = state.section) {
            null -> LabHome(c, state, actions, relativeTime)
            else -> Column(Modifier.fillMaxSize()) {
                SectionHeader(c, s.title) { actions.onOpen(null) }
                when (s) {
                    LabSection.NETWORKS -> NetworksPage(c, state.snapshot, relativeTime)
                    LabSection.LIVE -> LivePage(c, state, actions)
                    LabSection.EXPERIMENTS -> ExperimentsPage(c, state.snapshot.experiments, relativeTime)
                    LabSection.DISCOVERIES -> DiscoveriesPage(c, state.snapshot.discoveries, relativeTime)
                    LabSection.VERIFIED -> VerifiedPage(c, state.snapshot, actions, relativeTime)
                    LabSection.RESEARCH -> ResearchPage(c, state.research, actions, relativeTime)
                }
            }
        }
        if (state.picking) ConfigPicker(c, state.configs, state.vpnOn, actions)
    }
}

// ---------------------------------------------------------------- home

private fun health(s: LabSnapshot): Int? = s.network?.let { n -> s.networks.firstOrNull { it.contextKey == n.contextKey }?.health }

private fun stabilityWord(health: Int?) = when {
    health == null -> "Not measured"
    health >= 75 -> "Stable"
    health >= 50 -> "Degraded"
    else -> "Restricted"
}

private fun stabilityColor(c: LabColors, health: Int?) = when {
    health == null -> c.text3
    health >= 75 -> c.good
    health >= 50 -> c.okay
    else -> c.bad
}

private fun statusLine(s: LabSnapshot): String {
    val mode = when (s.automation) {
        AutomationLevel.OBSERVE -> "Observing"
        AutomationLevel.RECOMMEND -> "Recommending"
        AutomationLevel.AUTO_LAB, AutomationLevel.AUTO_APPLY -> "Autonomous"
    }
    return "$mode • " + if (s.running != null) "Testing" else if (s.network != null) "Active" else "Waiting for a network"
}

@Composable
private fun LabHome(c: LabColors, state: NetworkLabUiState, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    val s = state.snapshot
    val h = health(s)
    val best = s.network?.let { n ->
        s.verified.filter { it.contextKey == n.contextKey && !it.disabled && it.state == PromotionState.VERIFIED }.maxByOrNull { it.confidence }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LabText("MAXIMUS LAB", c.text, 26.sp, FontWeight.Bold, letterSpacing = (-0.5).sp, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (s.running != null) c.okay else if (s.network != null) c.good else c.text3))
                            Spacer(Modifier.width(7.dp))
                            LabText(statusLine(s), c.text2, 13.sp, FontWeight.Medium, maxLines = 1)
                        }
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).background(c.card).border(1.dp, c.stroke, CircleShape).clickable(onClick = actions.onRefreshNetwork),
                        contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Refresh, "Measure the network again", tint = c.text2, modifier = Modifier.size(20.dp)) }
                }
            }
        }
        s.message?.let { m -> item { MessageBanner(c, m, actions.onDismissMessage) } }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().labCard(c).padding(20.dp)) {
                LabText("Current Network", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (s.network?.networkKey == "wifi") Icons.Rounded.Wifi else Icons.Rounded.CellTower, stabilityColor(c, h), 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        LabText(s.network?.label ?: "No network", c.text, 22.sp, FontWeight.Bold, maxLines = 1)
                        LabText(s.network?.let { "${it.families} · measured ${relativeTime(s.capability?.measuredAt ?: it.startedAt)}" } ?: "LAB measures only the network the phone is on.",
                            c.text2, 12.5.sp, maxLines = 2)
                    }
                    Pill(stabilityWord(h), stabilityColor(c, h))
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).labCard(c).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Gauge(c, h, 104.dp, h?.let { "$it%" } ?: "—", "Health")
                }
                Column(Modifier.weight(1f).labCard(c).padding(18.dp)) {
                    LabText("Active LAB", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    val today = s.experiments.count { it.startTime > System.currentTimeMillis() - 86_400_000L }
                    LabText(if (s.running != null) "1 running" else "$today today", c.text, 22.sp, FontWeight.Bold, maxLines = 1)
                    LabText("experiments", c.text2, 12.5.sp, maxLines = 1)
                    Spacer(Modifier.height(10.dp))
                    LabText("${s.verified.count { it.state == PromotionState.VERIFIED && !it.disabled }} verified profiles", c.accent, 12.5.sp, FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().labCard(c).clickable { actions.onOpen(LabSection.VERIFIED) }.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LabText("Best Verified Strategy", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    if (best != null) {
                        LabText("${best.strategy} · ${best.profileId}", c.text, 18.sp, FontWeight.Bold, maxLines = 1)
                        LabText("${(best.confidence * 100).toInt()}% confidence · verified ${relativeTime(best.lastVerifiedAt)}", c.text2, 12.5.sp, maxLines = 1)
                    } else {
                        LabText("Nothing verified here yet", c.text, 17.sp, FontWeight.SemiBold, maxLines = 1)
                        LabText(s.plan?.reason ?: "Run an experiment when a config stops working.", c.text2, 12.5.sp, maxLines = 2)
                    }
                }
                if (best != null) Pill("Verified", c.good)
            }
        }
        item {
            GradientButton(c, if (s.running != null) "Experiment running…" else "Run experiment", Icons.Rounded.PlayArrow,
                enabled = s.running == null, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) { actions.onPick(true) }
        }
        item { SectionLabel(c, "Open LAB") }
        item {
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().labCard(c)) {
                val counts = mapOf(
                    LabSection.NETWORKS to "${s.networks.size}",
                    LabSection.LIVE to if (s.running != null) "Running" else "",
                    LabSection.EXPERIMENTS to "${s.experiments.size}",
                    LabSection.DISCOVERIES to "${s.discoveries.size}",
                    LabSection.VERIFIED to "${s.verified.count { it.state != PromotionState.RETIRED }}",
                    LabSection.RESEARCH to "${state.research.size}"
                )
                LabSection.entries.forEachIndexed { i, sec ->
                    if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(c.divider))
                    Row(Modifier.fillMaxWidth().clickable { actions.onOpen(sec) }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(sec.icon, c.accent, 34.dp)
                        Spacer(Modifier.width(14.dp))
                        LabText(sec.title, c.text, 15.sp, FontWeight.Medium, Modifier.weight(1f), maxLines = 1)
                        counts[sec]?.takeIf { it.isNotEmpty() }?.let { LabText(it, if (it == "Running") c.okay else c.text3, 13.sp, FontWeight.Medium, maxLines = 1) }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.text3, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        item { SectionLabel(c, "Automation") }
        item { AutomationPicker(c, s.automation, actions.onAutomation) }
        item {
            LabText("LAB never changes your saved configs, the kill switch or DNS. It tests derived copies with real requests while the VPN is off, " +
                "and only reports what it measured on this phone.", c.text3, 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp), maxLines = 4)
        }
    }
}

@Composable
private fun AutomationPicker(c: LabColors, selected: AutomationLevel, onSelect: (AutomationLevel) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().labCard(c).padding(6.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.cardAlt).padding(4.dp)) {
            AutomationLevel.entries.forEach { level ->
                val on = level == selected
                Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) c.accent else Color.Transparent)
                    .clickable { onSelect(level) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                    LabText(level.title, if (on) c.onAccent else c.text2, 12.sp, FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        LabText(selected.detail + ".", c.text2, 12.5.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp), maxLines = 2)
    }
}

@Composable
private fun MessageBanner(c: LabColors, message: String, onDismiss: () -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.info.copy(alpha = 0.12f))
        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(message, c.text, 13.sp, modifier = Modifier.weight(1f), maxLines = 3)
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Dismiss", tint = c.text2, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SectionHeader(c: LabColors, title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.text)
        }
        Spacer(Modifier.width(4.dp))
        LabText(title, c.text, 22.sp, FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ConfigPicker(c: LabColors, configs: List<LabConfigOption>, vpnOn: Boolean, actions: NetworkLabActions) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { actions.onPick(false) }, contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(c.card).clickable(enabled = false) {}.padding(20.dp)) {
            LabText("Which config stopped working?", c.text, 18.sp, FontWeight.Bold, maxLines = 1)
            LabText("LAB measures it as it is, then tests up to 5 safe copies. The saved config is not changed.", c.text2, 13.sp, maxLines = 3)
            if (vpnOn) {
                Spacer(Modifier.height(10.dp))
                LabText("Disconnect the VPN first: the test core cannot run beside the VPN's.", c.okay, 13.sp, FontWeight.Medium, maxLines = 2)
            }
            Spacer(Modifier.height(12.dp))
            if (configs.isEmpty()) LabText("No saved configs yet.", c.text3, 14.sp)
            configs.take(8).forEach { cfg ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !vpnOn) { actions.onRun(cfg.id) }.padding(vertical = 11.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LabText(cfg.name, if (vpnOn) c.text3 else c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                        LabText(cfg.detail, c.text3, 12.sp, maxLines = 1)
                    }
                    Icon(Icons.Rounded.PlayArrow, null, tint = if (vpnOn) c.text3 else c.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- networks

@Composable
private fun NetworksPage(c: LabColors, s: LabSnapshot, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.networks.isEmpty()) item { Empty(c, "No network measured yet", "LAB measures the network the phone is on, when you open it or the network changes.") }
        items(s.networks, key = { it.contextKey }) { n -> NetworkRow(c, n, n.contextKey == s.network?.contextKey, relativeTime) }
        item {
            LabText("Only the network the phone is on is measured now. Others show their last measurement and are never shown as current.",
                c.text3, 12.sp, modifier = Modifier.padding(4.dp), maxLines = 3)
        }
    }
}

@Composable
private fun NetworkRow(c: LabColors, n: LabStore.NetworkSeen, current: Boolean, relativeTime: (Long) -> String) {
    Row(Modifier.fillMaxWidth().labCard(c, 18.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(if (n.contextKey.startsWith("wifi")) Icons.Rounded.Wifi else Icons.Rounded.CellTower, if (current) c.accent else c.text3)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabText(n.label, c.text, 16.sp, FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                LabText(n.contextKey.substringAfter('|'), c.text3, 12.sp, maxLines = 1)
            }
            LabText(if (current) "Current · verified now" else "Last tested ${relativeTime(n.lastMeasuredAt)}", if (current) c.good else c.text2, 12.5.sp, maxLines = 1)
            if (n.summary.isNotBlank()) LabText(n.summary, c.text3, 11.5.sp, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Pill(stabilityWord(n.health), stabilityColor(c, n.health), filled = current)
    }
}

// ---------------------------------------------------------------- live tests

@Composable
private fun LivePage(c: LabColors, state: NetworkLabUiState, actions: NetworkLabActions) {
    val s = state.snapshot
    var advanced by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.steps.isEmpty()) item { Empty(c, "Nothing running", "Run an experiment from the LAB home to watch it here.") }
        else item {
            Column(Modifier.fillMaxWidth().labCard(c).padding(vertical = 10.dp)) {
                s.running?.let { LabText("${it.experimentId} · ${it.networkLabel}", c.text3, 12.sp, FontWeight.Medium, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), maxLines = 1) }
                s.steps.forEach { StepRow(c, it) }
            }
        }
        if (s.running != null) item {
            Row(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.bad.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable(onClick = actions.onCancel), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cancel, null, tint = c.bad, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                LabText("Cancel experiment", c.bad, 15.sp, FontWeight.SemiBold, maxLines = 1)
            }
        }
        s.message?.let { m -> item { LabText(m, c.text2, 13.sp, modifier = Modifier.padding(4.dp), maxLines = 4) } }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { advanced = !advanced }.padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LabText("Advanced: raw log", c.text2, 13.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
                Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.text3)
            }
        }
        if (advanced) item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cardAlt).padding(12.dp)) {
                if (s.log.isEmpty()) LabText("No log yet.", c.text3, 12.sp)
                s.log.takeLast(40).forEach { LabText(it, c.text2, 11.sp, maxLines = 3) }
            }
        }
    }
}

@Composable
private fun StepRow(c: LabColors, step: LabStep) {
    val (icon, tint) = when (step.state) {
        LabStep.State.DONE -> Icons.Rounded.CheckCircle to c.good
        LabStep.State.FAILED -> Icons.Rounded.ErrorOutline to c.bad
        LabStep.State.RUNNING -> Icons.Rounded.Sync to c.accent
        LabStep.State.SKIPPED -> Icons.Rounded.RemoveCircleOutline to c.text3
        LabStep.State.PENDING -> Icons.Rounded.RadioButtonUnchecked to c.text3.copy(alpha = 0.6f)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            LabText(step.title, if (step.state == LabStep.State.PENDING) c.text3 else c.text, 14.sp,
                if (step.state == LabStep.State.RUNNING) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            if (step.detail.isNotBlank()) LabText(step.detail, c.text2, 12.sp, maxLines = 2)
        }
    }
}

// ---------------------------------------------------------------- experiments

private fun stateColor(c: LabColors, s: ExperimentState) = when (s) {
    ExperimentState.VERIFIED -> c.good
    ExperimentState.CANDIDATE, ExperimentState.TESTING, ExperimentState.VERIFYING, ExperimentState.QUEUED, ExperimentState.CREATED -> c.okay
    ExperimentState.CANCELLED -> c.text3
    else -> c.bad
}

private fun title(s: Enum<*>) = s.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun ExperimentsPage(c: LabColors, experiments: List<LabExperiment>, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (experiments.isEmpty()) item { Empty(c, "No experiments yet", "Experiments start when you run one, or automatically at Auto LAB.") }
        items(experiments, key = { it.experimentId }) { ExperimentCard(c, it, relativeTime) }
    }
}

@Composable
private fun ExperimentCard(c: LabColors, e: LabExperiment, relativeTime: (Long) -> String) {
    var open by remember { mutableStateOf(false) }
    val best = e.best
    Column(Modifier.fillMaxWidth().labCard(c, 18.dp).clickable { open = !open }.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabText(e.experimentId, c.text, 17.sp, FontWeight.Bold, Modifier.weight(1f), maxLines = 1)
            Pill(title(e.state), stateColor(c, e.state))
        }
        LabText("${e.networkLabel} · ${relativeTime(e.startTime)}", c.text2, 12.5.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(c, "Candidates", "${e.candidates.size}", Modifier.weight(1f))
            Stat(c, "Best", best?.candidateId?.removePrefix("CAND-") ?: "—", Modifier.weight(1f))
            Stat(c, "Success", best?.let { "${(it.stats.successRate * 100).toInt()}%" } ?: "—", Modifier.weight(1f))
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            e.baselineFailure?.let { LabText("Baseline: ${title(it)}", c.text2, 12.5.sp, FontWeight.Medium, maxLines = 1) }
            e.note?.let { LabText(it, c.text3, 12.sp, maxLines = 3) }
            e.candidates.forEach { cand ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cardAlt).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabText(cand.candidateId, c.text, 13.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
                        LabText(title(cand.state), if (cand.state == PromotionState.VERIFIED) c.good else c.text2, 12.sp, FontWeight.Medium, maxLines = 1)
                    }
                    LabText(cand.mutationProfileId.substringBefore('@'), c.text2, 12.sp, maxLines = 1)
                    LabText(cand.changes.joinToString(" · ") { "${it.field}: ${it.to.ifBlank { "off" }}" }.ifBlank { "no field change" }, c.text3, 11.5.sp, maxLines = 2)
                    LabText(
                        if (!cand.securityPassed) "Refused by the security gate: ${cand.securityReason ?: "unsafe change"}"
                        else "${cand.stats.successes}/${cand.stats.attempts} passed" + (cand.stats.medianLatencyMs?.let { " · $it ms" } ?: ""),
                        if (cand.securityPassed) c.text2 else c.bad, 11.5.sp, maxLines = 2
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- discoveries

private fun LabDiscovery.Kind.icon(): ImageVector = when (this) {
    LabDiscovery.Kind.PROFILE_VERIFIED -> Icons.Rounded.Verified
    LabDiscovery.Kind.PROFILE_DEGRADED, LabDiscovery.Kind.TRANSPORT_DEGRADED -> Icons.Rounded.ErrorOutline
    LabDiscovery.Kind.RESEARCH_CANDIDATE -> Icons.Rounded.TravelExplore
    LabDiscovery.Kind.CORE_CAPABILITY -> Icons.Rounded.Shield
    LabDiscovery.Kind.PROVIDER_MODEL -> Icons.Rounded.Lightbulb
    LabDiscovery.Kind.NETWORK_BEHAVIOR -> Icons.Rounded.CellTower
}

@Composable
private fun DiscoveriesPage(c: LabColors, items: List<LabDiscovery>, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (items.isEmpty()) item { Empty(c, "No discoveries yet", "Findings from experiments and research appear here.") }
        items(items, key = { it.id }) { d ->
            Row(Modifier.fillMaxWidth().labCard(c, 18.dp).padding(16.dp)) {
                val tint = when (d.kind) {
                    LabDiscovery.Kind.PROFILE_VERIFIED -> c.good
                    LabDiscovery.Kind.PROFILE_DEGRADED, LabDiscovery.Kind.TRANSPORT_DEGRADED -> c.bad
                    else -> c.info
                }
                IconTile(d.kind.icon(), tint, 38.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    LabText(d.title, c.text, 15.sp, FontWeight.SemiBold, maxLines = 2)
                    LabText(d.detail, c.text2, 12.5.sp, maxLines = 3)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (d.verified) "Verified on this phone" else "Unverified", if (d.verified) c.good else c.okay)
                        Spacer(Modifier.width(6.dp))
                        Pill("${(d.confidence * 100).toInt()}% confidence", c.text2, filled = false)
                    }
                    Spacer(Modifier.height(6.dp))
                    LabText("${d.source} · ${relativeTime(d.at)}", c.text3, 11.5.sp, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- verified profiles

@Composable
private fun VerifiedPage(c: LabColors, s: LabSnapshot, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    val list = s.verified.sortedWith(compareBy<VerifiedNetworkProfile> { it.state == PromotionState.RETIRED }.thenByDescending { it.confidence })
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (list.isEmpty()) item { Empty(c, "No verified profiles", "A copy becomes verified after it passes at least 3 real requests on a network.") }
        items(list, key = { it.profileId }) { VerifiedCard(c, it, it.contextKey == s.network?.contextKey, s.running == null, actions, relativeTime) }
    }
}

private fun promotionColor(c: LabColors, p: PromotionState) = when (p) {
    PromotionState.VERIFIED -> c.good
    PromotionState.CANDIDATE, PromotionState.EXPERIMENTAL -> c.okay
    PromotionState.DEGRADED -> c.okay
    PromotionState.RETIRED, PromotionState.REJECTED -> c.text3
}

@Composable
private fun VerifiedCard(c: LabColors, v: VerifiedNetworkProfile, here: Boolean, idle: Boolean, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    var open by remember { mutableStateOf(false) }
    val retired = v.state == PromotionState.RETIRED
    Column(Modifier.fillMaxWidth().labCard(c, 18.dp).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LabText(v.profileId, c.text, 17.sp, FontWeight.Bold, maxLines = 1)
                LabText("${v.strategy} · ${v.networkLabel}", c.text2, 12.5.sp, maxLines = 1)
            }
            Pill(if (v.disabled) "Disabled" else title(v.state), if (v.disabled) c.text3 else promotionColor(c, v.state))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(c, "Success", "${(v.stats.successRate * 100).toInt()}%", Modifier.weight(1f))
            Stat(c, "Confidence", "${(v.confidence * 100).toInt()}%", Modifier.weight(1f))
            Stat(c, "Latency", v.stats.medianLatencyMs?.let { "$it ms" } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        LabText(
            "Stability: " + (v.stats.jitterMs?.let { "±$it ms" } ?: "not enough samples") +
                (if (v.stats.consecutiveFailures > 0) " · ${v.stats.consecutiveFailures} recent failure(s)" else "") +
                " · verified ${relativeTime(v.lastVerifiedAt)}",
            c.text2, 12.sp, maxLines = 2
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = if (v.securityPassed) c.good else c.bad, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            LabText(if (v.securityPassed) "Security gate passed" else "Security gate refused", if (v.securityPassed) c.good else c.bad, 12.sp, FontWeight.Medium, maxLines = 1)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cardAlt).padding(10.dp)) {
                LabText("Mutation: ${v.mutationProfileId}", c.text2, 12.sp, maxLines = 1)
                LabText("Network: ${v.contextKey}", c.text2, 12.sp, maxLines = 1)
                v.endpoint?.let { LabText("Edge address: $it", c.text2, 12.sp, maxLines = 1) }
                LabText("${v.stats.successes} of ${v.stats.attempts} real requests passed", c.text2, 12.sp, maxLines = 1)
                LabText("Rollback: the original config is used unchanged", c.text3, 12.sp, maxLines = 2)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(c, "Retest", enabled = here && idle && !retired && !v.disabled, Modifier.weight(1f)) { actions.onRetest(v.profileId) }
            ActionChip(c, if (v.disabled) "Enable" else "Disable", enabled = !retired, Modifier.weight(1f)) { actions.onDisable(v.profileId, !v.disabled) }
            ActionChip(c, "Retire", enabled = !retired, Modifier.weight(1f), danger = true) { actions.onRetire(v.profileId) }
            ActionChip(c, if (open) "Less" else "Details", enabled = true, Modifier.weight(1f)) { open = !open }
        }
    }
}

@Composable
private fun ActionChip(c: LabColors, text: String, enabled: Boolean, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> c.text3.copy(alpha = 0.6f)
        danger -> c.bad
        else -> c.accent
    }
    Box(modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        LabText(text, color, 12.5.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

// ---------------------------------------------------------------- research

@Composable
private fun ResearchPage(c: LabColors, items: List<LabResearchItem>, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            LabText("Research reads public release notes and reviewed sources. Nothing found here runs by itself: an idea becomes a LAB experiment " +
                "only through the same allowlist and security gate, and is verified only by real requests on this phone.", c.text3, 12.sp, modifier = Modifier.padding(4.dp), maxLines = 5)
        }
        item {
            Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp))
                .clickable(onClick = actions.onResearchRefresh), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Refresh, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                LabText("Check sources", c.accent, 14.sp, FontWeight.SemiBold, maxLines = 1)
            }
        }
        if (items.isEmpty()) item { Empty(c, "No research yet", "Check sources to look for new core releases and transport ideas.") }
        items(items, key = { it.id }) { r ->
            Column(Modifier.fillMaxWidth().labCard(c, 18.dp).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabText(r.title, c.text, 15.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 2)
                    Spacer(Modifier.width(8.dp))
                    Pill(r.state, if (r.state == "Verified") c.good else if (r.state == "Rejected" || r.state == "Expired") c.text3 else c.okay)
                }
                LabText(r.compatibility, c.text2, 12.5.sp, maxLines = 3)
                Spacer(Modifier.height(6.dp))
                LabText("${r.source} · ${relativeTime(r.at)}", c.text3, 11.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Empty(c: LabColors, title: String, body: String) {
    Column(Modifier.fillMaxWidth().labCard(c, 18.dp).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LabText(title, c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        LabText(body, c.text2, 12.5.sp, maxLines = 3)
    }
}
