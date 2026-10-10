package com.example.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
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
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.ui.protocols.labColors
import com.example.vpn.lab.AnalysisReport
import com.example.vpn.lab.AutomationLevel
import com.example.vpn.lab.ConfigRevival
import com.example.vpn.lab.ConfigTransaction
import com.example.vpn.lab.ExperimentState
import com.example.vpn.lab.LabDiscovery
import com.example.vpn.lab.LabExperiment
import com.example.vpn.lab.LabPick
import com.example.vpn.lab.LiveAnalysis
import com.example.vpn.lab.LabSnapshot
import com.example.vpn.lab.NetworkState
import com.example.vpn.lab.LabStep
import com.example.vpn.lab.LabStore
import com.example.vpn.lab.LivePath
import com.example.vpn.lab.PathStatus
import com.example.vpn.lab.RankedMethod
import com.example.vpn.lab.PromotionState
import com.example.vpn.lab.VerifiedNetworkProfile

/** The LAB's pages: a minimal home and five detail sections (spec sections 40-46), plus Research. */
enum class LabSection(val title: String, val icon: ImageVector) {
    REPORT("Analysis report", Icons.Rounded.Insights),
    NETWORKS("Networks", Icons.Rounded.CellTower),
    LIVE("Live Tests", Icons.Rounded.MonitorHeart),
    EXPERIMENTS("Experiments", Icons.Rounded.Science),
    DISCOVERIES("Discoveries", Icons.Rounded.Lightbulb),
    VERIFIED("Verified Profiles", Icons.Rounded.Verified),
    REVIVE("Revive configs", Icons.Rounded.Healing),
    RESEARCH("Research", Icons.Rounded.TravelExplore)
}

/** A saved config the user can run an experiment on. Names only; no address or credential reaches the screen. */
data class LabConfigOption(val id: String, val name: String, val detail: String)

/** A research card (Phase 7). Never shown as verified until LAB measured it on this phone. */
data class LabResearchItem(val id: String, val title: String, val state: String, val source: String, val compatibility: String, val at: Long)

/** A change the LAB Agent suggested; testing it goes through the LAB's allowlist and security gate. */
data class LabSuggestionUi(val field: String, val value: String, val why: String)

data class NetworkLabUiState(
    val snapshot: LabSnapshot = LabSnapshot(),
    /** True when an AI provider is set up; the LAB works the same without one. */
    val aiReady: Boolean = false,
    val advising: Boolean = false,
    val suggestions: List<LabSuggestionUi> = emptyList(),
    val configs: List<LabConfigOption> = emptyList(),
    val research: List<LabResearchItem> = emptyList(),
    val section: LabSection? = null,
    val picking: Boolean = false,
    val vpnOn: Boolean = false,
    val revival: RevivalUi = RevivalUi()
)

/** Everything the LAB screens can ask for; the screens hold no state of their own beyond expanded cards. */
class NetworkLabActions(
    val onOpen: (LabSection?) -> Unit = {},
    val onPick: (Boolean) -> Unit = {},
    val onRun: (String) -> Unit = {},
    val onFullAnalysis: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onAutomation: (AutomationLevel) -> Unit = {},
    val onRefreshNetwork: () -> Unit = {},
    val onRetest: (String) -> Unit = {},
    val onDisable: (String, Boolean) -> Unit = { _, _ -> },
    val onRetire: (String) -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onResearchRefresh: () -> Unit = {},
    val onAskAgent: () -> Unit = {},
    val onTestSuggestion: (LabSuggestionUi) -> Unit = {},
    /** Selects a saved config (id, name) as the one to connect with. */
    val onUseRecommended: (String, String) -> Unit = { _, _ -> },
    val onRevive: () -> Unit = {},
    val onStopRevive: () -> Unit = {},
    val onFirewall: (com.example.vpn.connectivity.NetworkFirewalls.Firewall?) -> Unit = {}
)

@Composable
fun NetworkLabContent(state: NetworkLabUiState, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    val c = labColors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        when (val s = state.section) {
            null -> LabHome(c, state, actions, relativeTime)
            else -> Column(Modifier.fillMaxSize()) {
                TitleBar(c, s.title, sectionSubtitle(s, state), Icons.AutoMirrored.Rounded.ArrowBack, { actions.onOpen(null) })
                when (s) {
                    LabSection.REPORT -> ReportPage(c, state, actions, relativeTime)
                    LabSection.NETWORKS -> NetworksPage(c, state.snapshot, relativeTime)
                    LabSection.LIVE -> LivePage(c, state, actions)
                    LabSection.EXPERIMENTS -> ExperimentsPage(c, state.snapshot.experiments, relativeTime)
                    LabSection.DISCOVERIES -> DiscoveriesPage(c, state, actions, relativeTime)
                    LabSection.VERIFIED -> VerifiedPage(c, state.snapshot, actions, relativeTime)
                    LabSection.RESEARCH -> ResearchPage(c, state.research, actions, relativeTime)
                    LabSection.REVIVE -> RevivePage(c, state.revival, state.vpnOn, state.snapshot.running != null || state.snapshot.analysisRunning,
                        actions.onRevive, actions.onStopRevive, relativeTime, actions.onFirewall)
                }
            }
        }
        if (state.picking) ConfigPicker(c, state.configs, state.vpnOn, actions)
    }
}

private fun sectionSubtitle(s: LabSection, state: NetworkLabUiState): String = when (s) {
    LabSection.REPORT -> state.snapshot.analysis?.let { "${it.networkLabel} · ${it.state}" } ?: "No full analysis on this network yet"
    LabSection.NETWORKS -> "Only the current network is measured live"
    LabSection.LIVE -> state.snapshot.running?.let { "${it.experimentId} · ${it.networkLabel}" }
        ?: if (state.snapshot.analysisRunning) "Full analysis running" else "No experiment running"
    LabSection.EXPERIMENTS -> "${state.snapshot.experiments.size} recorded on this phone"
    LabSection.DISCOVERIES -> "Measurements and AI readings, labelled"
    LabSection.VERIFIED -> "Strategies measured to work, per network"
    LabSection.RESEARCH -> "Public sources, checked against the core"
    LabSection.REVIVE -> "Brings dead Cloudflare configs back"
}

// ---------------------------------------------------------------- shared wording

private fun health(s: LabSnapshot): Int? = s.network?.let { n -> s.networks.firstOrNull { it.contextKey == n.contextKey }?.health }

private fun stabilityWord(health: Int?) = when {
    health == null -> "Not measured"
    health >= 75 -> "Stable"
    health >= 50 -> "Degraded"
    else -> "Restricted"
}

private fun transactionWord(state: ConfigTransaction.State) = when (state) {
    ConfigTransaction.State.PROPOSED -> "Proposed"
    ConfigTransaction.State.REFUSED -> "Refused"
    ConfigTransaction.State.STAGED -> "Staged"
    ConfigTransaction.State.COMMITTED -> "In use"
    ConfigTransaction.State.ROLLED_BACK -> "Rolled back"
    ConfigTransaction.State.EXPIRED -> "Expired"
}

private fun transactionColor(c: LabColors, state: ConfigTransaction.State) = when (state) {
    ConfigTransaction.State.COMMITTED -> c.good
    ConfigTransaction.State.STAGED, ConfigTransaction.State.PROPOSED -> c.okay
    ConfigTransaction.State.REFUSED, ConfigTransaction.State.ROLLED_BACK -> c.bad
    ConfigTransaction.State.EXPIRED -> c.text3
}

private fun stateColor(c: LabColors, state: NetworkState) = when (state) {
    NetworkState.NORMAL -> c.good
    NetworkState.UNKNOWN -> c.text3
    NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY, NetworkState.NO_VERIFIED_EGRESS, NetworkState.SEVERE_FILTERING,
    NetworkState.DOMESTIC_ONLY, NetworkState.TLS_PATH_FAILURE -> c.bad
    else -> c.okay
}

private fun pathColor(c: LabColors, s: PathStatus) = when (s) {
    PathStatus.VERIFIED, PathStatus.AVAILABLE, PathStatus.SUPPORTED -> c.good
    PathStatus.CANDIDATE, PathStatus.DEGRADED, PathStatus.EXPERIMENTAL, PathStatus.BLOCKED_SUSPECTED, PathStatus.UNRESPONSIVE -> c.okay
    PathStatus.TESTING, PathStatus.QUEUED -> c.accent
    PathStatus.FAILED, PathStatus.BLOCKED, PathStatus.SECURITY_REJECTED, PathStatus.RECENTLY_FAILED -> c.bad
    PathStatus.NOT_TESTED, PathStatus.UNSUPPORTED, PathStatus.EXPIRED, PathStatus.NOT_REQUIRED, PathStatus.NOT_CONFIGURED -> c.text3
}

private fun stabilityColor(c: LabColors, health: Int?) = when {
    health == null -> c.text3
    health >= 75 -> c.good
    health >= 50 -> c.okay
    else -> c.bad
}

private fun title(s: Enum<*>) = s.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun pct(v: Double) = "${(v * 100).toInt()}%"

// ---------------------------------------------------------------- home

@Composable
private fun LabHome(c: LabColors, state: NetworkLabUiState, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    val s = state.snapshot
    val h = health(s)
    val best = s.network?.let { n ->
        s.verified.filter { it.contextKey == n.contextKey && !it.disabled && it.state == PromotionState.VERIFIED }.maxByOrNull { it.confidence }
    }
    val mode = when (s.automation) {
        AutomationLevel.OBSERVE -> "Observing"
        AutomationLevel.RECOMMEND -> "Recommending"
        AutomationLevel.AUTO_LAB, AutomationLevel.AUTO_APPLY -> "Autonomous"
    }
    val activity = if (s.running != null) "Testing" else if (s.network != null) "Active" else "Idle"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TitleBar(c, "Maximus LAB", "Network Intelligence · $mode · $activity", null, trailing = {
                Box(Modifier.size(38.dp).clip(CircleShape).border(1.dp, c.stroke, CircleShape).clickable(onClick = actions.onRefreshNetwork),
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Refresh, "Measure the network again", tint = c.text2, modifier = Modifier.size(18.dp)) }
            })
        }
        s.message?.let { m -> item { Notice(c, m, actions.onDismissMessage) } }
        item { GroupLabel(c, "Current network") }
        item {
            Panel(c) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(c.cardAlt), contentAlignment = Alignment.Center) {
                        Icon(if (s.network?.networkKey?.startsWith("wifi") == true) Icons.Rounded.Wifi else Icons.Rounded.CellTower, null, tint = c.text2, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        LabText(s.network?.label ?: "No network", c.text, 16.sp, FontWeight.SemiBold, maxLines = 1)
                        LabText(s.network?.let { "${it.families} · measured ${relativeTime(s.capability?.measuredAt ?: it.startedAt)}" } ?: "LAB measures only the network the phone is on",
                            c.text3, 12.sp, maxLines = 1)
                    }
                    Badge(stabilityWord(h), stabilityColor(c, h))
                }
                Hairline(c)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Metric(c, "Network health", h?.let { "$it%" } ?: "—", (h ?: 0) / 100f, stabilityColor(c, h))
                    VDivider(c)
                    val today = s.experiments.count { it.startTime > System.currentTimeMillis() - 86_400_000L }
                    Metric(c, "Experiments", if (s.running != null) "1 running" else "$today today")
                    VDivider(c)
                    val verified = s.verified.count { it.state == PromotionState.VERIFIED && !it.disabled }
                    Metric(c, "Verified", if (verified == 1) "1 profile" else "$verified profiles")
                }
                s.networkState?.let { st ->
                    Hairline(c)
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LabText("Network state", c.text2, 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Badge(st.primary.title, stateColor(c, st.primary))
                        }
                        val extra = st.restrictions - st.primary
                        LabText(if (extra.isEmpty()) st.primary.detail else extra.joinToString(" · ") { it.title }, c.text, 13.sp, maxLines = 2,
                            modifier = Modifier.padding(top = 6.dp))
                        val confidence = if (st.primary == NetworkState.UNKNOWN) "" else "${(st.confidence * 100).toInt()}% confidence · "
                        LabText(confidence + if (st.notTested.isEmpty()) "every check measured" else "${st.notTested.size} checks not tested", c.text3, 12.sp, maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        item { GroupLabel(c, "Best verified strategy", if (s.verified.isNotEmpty()) "View all" else null) { actions.onOpen(LabSection.VERIFIED) } }
        item {
            Panel(c) {
                if (best != null) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            LabText(best.strategy, c.text, 16.sp, FontWeight.SemiBold, maxLines = 1)
                            LabText("${best.profileId} · verified ${relativeTime(best.lastVerifiedAt)}", c.text3, 12.sp, maxLines = 1)
                        }
                        Badge("Verified", c.good)
                    }
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabText("Confidence", c.text2, 12.sp, modifier = Modifier.width(78.dp), maxLines = 1)
                        Meter(c, best.confidence.toFloat(), c.good, Modifier.weight(1f))
                        LabText(pct(best.confidence), c.text, 12.5.sp, FontWeight.SemiBold, Modifier.padding(start = 10.dp), maxLines = 1)
                    }
                } else {
                    Column(Modifier.padding(14.dp)) {
                        LabText("Nothing verified on this network yet", c.text, 14.5.sp, FontWeight.Medium, maxLines = 1)
                        LabText(s.plan?.reason ?: "Run an experiment when a config stops working.", c.text3, 12.sp, maxLines = 2)
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = Gutter, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val busy = s.running != null || s.analysisRunning
                PrimaryButton(c, if (s.analysisRunning) "Analysing…" else "Start full analysis", Icons.Rounded.Insights, Modifier.weight(1f), !busy, onClick = actions.onFullAnalysis)
                if (busy) SecondaryButton(c, "Live", Icons.Rounded.MonitorHeart) { actions.onOpen(LabSection.LIVE) }
                else SecondaryButton(c, "One config", Icons.Rounded.PlayArrow) { actions.onPick(true) }
            }
        }
        bestNow(s)?.let { b -> item { BestConnectionCard(c, b, actions) } }
        s.analysis?.let { report ->
            item { GroupLabel(c, "Live connectivity paths", "Full report") { actions.onOpen(LabSection.REPORT) } }
            item {
                Panel(c) {
                    ReportHeadline(c, report, relativeTime)
                    report.paths.sortedBy { it.status.ordinal }.filter { it.status != PathStatus.NOT_TESTED }.take(6).forEach { p ->
                        Hairline(c)
                        PathRow(c, p)
                    }
                    val untested = report.paths.count { it.status == PathStatus.NOT_TESTED }
                    if (untested > 0) {
                        Hairline(c)
                        LabText("$untested paths not tested · see the full report", c.text3, 12.sp, modifier = Modifier.padding(14.dp), maxLines = 1)
                    }
                }
            }
        }
        if (s.transactions.isNotEmpty()) {
            item { GroupLabel(c, "Config changes") }
            item {
                Panel(c) {
                    s.transactions.take(3).forEachIndexed { i, tx ->
                        if (i > 0) Hairline(c)
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                LabText("${tx.id} · ${tx.profileKey.substringBefore('@')}", c.text, 14.sp, FontWeight.Medium, maxLines = 1)
                                LabText(tx.note.ifBlank { tx.reason }, c.text3, 12.sp, maxLines = 2)
                            }
                            Spacer(Modifier.width(10.dp))
                            Badge(transactionWord(tx.state), transactionColor(c, tx.state))
                        }
                    }
                }
            }
        }
        item { GroupLabel(c, "Lab") }
        item {
            Panel(c) {
                val rows = listOf(
                    Triple(LabSection.NETWORKS, "${s.networks.size} seen", null),
                    Triple(LabSection.LIVE, if (s.running != null) "Running" else "Idle", if (s.running != null) c.okay else null),
                    Triple(LabSection.EXPERIMENTS, "${s.experiments.size}", null),
                    Triple(LabSection.DISCOVERIES, "${s.discoveries.size}", null),
                    Triple(LabSection.VERIFIED, "${s.verified.count { it.state != PromotionState.RETIRED }}", null),
                    state.revival.let { r ->
                        val revived = r.results.count { it.outcome == ConfigRevival.Outcome.REVIVED }
                        when {
                            r.running -> Triple(LabSection.REVIVE, "Running", c.okay)
                            revived > 0 -> Triple(LabSection.REVIVE, "$revived revived", c.good)
                            else -> Triple(LabSection.REVIVE, "${r.eligible}", null)
                        }
                    },
                    Triple(LabSection.RESEARCH, "${state.research.size}", null)
                )
                rows.forEachIndexed { i, (sec, trailing, color) ->
                    if (i > 0) Hairline(c, 47.dp)
                    NavRow(c, sec.icon, sec.title, trailing = trailing, trailingColor = color, chevron = Icons.AutoMirrored.Rounded.KeyboardArrowRight) { actions.onOpen(sec) }
                }
            }
        }
        item { GroupLabel(c, "Automation") }
        item {
            Panel(c) {
                Column(Modifier.padding(10.dp)) {
                    Segmented(c, AutomationLevel.entries, s.automation, { it.title }, actions.onAutomation)
                    LabText(s.automation.detail + ".", c.text2, 12.sp, modifier = Modifier.padding(start = 4.dp, top = 9.dp, bottom = 2.dp), maxLines = 2)
                }
            }
        }
        item {
            Footnote(c, "LAB never changes saved configs, the kill switch or DNS. It tests derived copies with real requests while the VPN is off, and reports only what this phone measured.")
        }
    }
}

@Composable
private fun Notice(c: LabColors, message: String, onDismiss: () -> Unit) {
    Row(Modifier.padding(horizontal = Gutter, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)
        .border(1.dp, c.stroke, RoundedCornerShape(12.dp))) {
        Box(Modifier.width(3.dp).height(52.dp).background(c.info))
        Row(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LabText(message, c.text, 12.5.sp, modifier = Modifier.weight(1f), maxLines = 3)
            Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Dismiss", tint = c.text3, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ConfigPicker(c: LabColors, configs: List<LabConfigOption>, vpnOn: Boolean, actions: NetworkLabActions) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.dark) 0.6f else 0.35f)).clickable { actions.onPick(false) }, contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(c.card).clickable(enabled = false) {}
            .padding(top = 10.dp, bottom = 16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.stroke))
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                LabText("Run an experiment", c.text, 17.sp, FontWeight.SemiBold, maxLines = 1)
                LabText("Pick the config that stopped working. LAB measures it as it is, then tests up to 5 safe copies. The saved config is not changed.",
                    c.text2, 12.5.sp, maxLines = 3, lineHeight = 17.sp)
                if (vpnOn) {
                    Spacer(Modifier.height(8.dp))
                    Badge("Disconnect the VPN first", c.okay)
                }
            }
            Hairline(c)
            if (configs.isEmpty()) LabText("No saved configs yet.", c.text3, 13.sp, modifier = Modifier.padding(18.dp))
            configs.take(8).forEachIndexed { i, cfg ->
                if (i > 0) Hairline(c, 18.dp)
                Row(Modifier.fillMaxWidth().clickable(enabled = !vpnOn) { actions.onRun(cfg.id) }.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LabText(cfg.name, if (vpnOn) c.text3 else c.text, 14.5.sp, FontWeight.Medium, maxLines = 1)
                        LabText(cfg.detail, c.text3, 12.sp, maxLines = 1)
                    }
                    Icon(Icons.Rounded.PlayArrow, null, tint = if (vpnOn) c.text3 else c.accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- networks

@Composable
private fun NetworksPage(c: LabColors, s: LabSnapshot, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (s.networks.isEmpty()) item { Empty(c, "No network measured yet", "LAB measures the network the phone is on when you open it or the network changes.") }
        else item {
            Panel(c) {
                s.networks.forEachIndexed { i, n ->
                    if (i > 0) Hairline(c, 14.dp)
                    NetworkRow(c, n, n.contextKey == s.network?.contextKey, relativeTime)
                }
            }
        }
        item { Footnote(c, "Other networks show their last measurement and its age. They are never shown as current.") }
    }
}

@Composable
private fun NetworkRow(c: LabColors, n: LabStore.NetworkSeen, current: Boolean, relativeTime: (Long) -> String) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (n.contextKey.startsWith("wifi")) Icons.Rounded.Wifi else Icons.Rounded.CellTower, null, tint = if (current) c.accent else c.text3, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            LabText(n.label, c.text, 14.5.sp, FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            LabText(n.contextKey.substringAfter('|'), c.text3, 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Badge(stabilityWord(n.health), stabilityColor(c, n.health))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabText(if (current) "Current · measured now" else "Last tested ${relativeTime(n.lastMeasuredAt)}", if (current) c.good else c.text2, 12.sp, FontWeight.Medium,
                Modifier.width(150.dp), maxLines = 1)
            Meter(c, n.health?.let { it / 100f }, stabilityColor(c, n.health), Modifier.weight(1f))
            LabText(n.health?.let { "$it%" } ?: "—", c.text2, 12.sp, FontWeight.Medium, Modifier.padding(start = 8.dp), maxLines = 1)
        }
        if (n.summary.isNotBlank()) LabText(n.summary, c.text3, 11.5.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 2, lineHeight = 15.sp)
    }
}

// ---------------------------------------------------------------- live tests

@Composable
private fun LivePage(c: LabColors, state: NetworkLabUiState, actions: NetworkLabActions) {
    val s = state.snapshot
    var advanced by remember { mutableStateOf(false) }
    var technical by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        s.live?.let { live ->
            bestNow(s)?.let { b -> item { BestConnectionCard(c, b, actions) } }
            item { LiveSummary(c, live) }
            item { GroupLabel(c, "Live connectivity paths", if (technical) "Simple view" else "Technical view") { technical = !technical } }
            item {
                Panel(c) {
                    val rows = if (technical) live.paths else live.paths.filter { it.key.startsWith("family-") }
                    rows.forEachIndexed { i, p -> if (i > 0) Hairline(c); PathRow(c, p, technical) }
                }
            }
        }
        if (s.steps.isEmpty()) item { Empty(c, "Nothing running", "Run an experiment from the LAB home to follow it here.") }
        else item {
            Panel(c) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    s.steps.forEachIndexed { i, step -> StepRow(c, step, first = i == 0, last = i == s.steps.lastIndex) }
                }
            }
        }
        if (s.running != null || s.analysisRunning) item {
            Row(Modifier.padding(horizontal = Gutter, vertical = 12.dp)) {
                SecondaryButton(c, if (s.analysisRunning) "Stop analysis" else "Stop experiment", Icons.Rounded.Stop, Modifier.fillMaxWidth(), color = c.bad, onClick = actions.onCancel)
            }
        }
        s.message?.let { m -> item { Notice(c, m, actions.onDismissMessage) } }
        item { GroupLabel(c, "Advanced", if (advanced) "Hide log" else "Show log") { advanced = !advanced } }
        if (advanced) item {
            Panel(c) {
                Column(Modifier.background(c.cardAlt).padding(12.dp)) {
                    if (s.log.isEmpty()) LabText("No log yet.", c.text3, 12.sp)
                    s.log.takeLast(40).forEach { LabText(it, c.text2, 11.sp, maxLines = 3, lineHeight = 15.sp) }
                }
            }
        }
    }
}

@Composable
private fun StepRow(c: LabColors, step: LabStep, first: Boolean, last: Boolean) {
    val color = when (step.state) {
        LabStep.State.DONE -> c.good
        LabStep.State.FAILED -> c.bad
        LabStep.State.RUNNING -> c.accent
        LabStep.State.SKIPPED, LabStep.State.PENDING -> c.text3.copy(alpha = 0.55f)
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 14.dp)) {
        Column(Modifier.width(18.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(1.5.dp).height(10.dp).background(if (first) Color.Transparent else c.divider))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (step.state == LabStep.State.PENDING) Color.Transparent else color.copy(alpha = 0.18f))
                .border(1.5.dp, color, CircleShape), contentAlignment = Alignment.Center) {
                if (step.state == LabStep.State.DONE || step.state == LabStep.State.RUNNING || step.state == LabStep.State.FAILED) Dot(color, 5.dp)
            }
            Box(Modifier.width(1.5.dp).weight(1f).background(if (last) Color.Transparent else c.divider))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabText(step.title, if (step.state == LabStep.State.PENDING || step.state == LabStep.State.SKIPPED) c.text3 else c.text, 13.5.sp,
                    if (step.state == LabStep.State.RUNNING) FontWeight.SemiBold else FontWeight.Medium, Modifier.weight(1f), maxLines = 1)
                if (step.state == LabStep.State.RUNNING) Badge("Running", c.accent)
                if (step.state == LabStep.State.SKIPPED) LabText("Skipped", c.text3, 11.5.sp, maxLines = 1)
            }
            if (step.detail.isNotBlank()) LabText(step.detail, c.text2, 12.sp, maxLines = 2, lineHeight = 16.sp)
        }
    }
}

// ---------------------------------------------------------------- full analysis

/** BEST CONNECTION NOW: the running analysis's best path, else the last report's, saved configs only. */
private data class BestNow(val pick: LabPick?, val status: PathStatus, val reason: String, val running: Boolean)

private fun bestNow(s: LabSnapshot): BestNow? {
    s.live?.let { live ->
        val b = live.best ?: return BestNow(null, PathStatus.TESTING, live.hypothesis, true)
        return BestNow(b, b.status, when (b.status) {
            PathStatus.VERIFIED -> "Verified by repeated real requests on ${live.networkLabel}."
            PathStatus.DEGRADED -> "Passed once, then failed a recheck; still looking for better."
            else -> "Passed one real request; checking stability and alternatives."
        }, true)
    }
    val r = s.analysis ?: return null
    if (s.network?.contextKey != r.contextKey) return null
    val m = r.ranked.firstOrNull { it.stage.carriesTraffic && it.savedConfig }
        ?: return BestNow(null, PathStatus.FAILED, r.note, false)
    return BestNow(LabPick(m.profileId, m.name, m.family, m.status, m.latencyMs), m.status, m.why, false)
}

@Composable
private fun BestConnectionCard(c: LabColors, b: BestNow, actions: NetworkLabActions) {
    Panel(c) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            LabText("BEST CONNECTION NOW", c.text3, 11.5.sp, FontWeight.SemiBold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                LabText(b.pick?.name ?: if (b.running) "Searching…" else "No working path", c.text, 16.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
                Badge(b.status.title, pathColor(c, b.status))
            }
            b.pick?.let { p -> LabText(listOfNotNull(p.family.title, p.latencyMs?.let { "$it ms" }).joinToString(" · "), c.text2, 12.5.sp, maxLines = 1) }
            LabText(b.reason, c.text3, 12.sp, maxLines = 3, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            b.pick?.let { p ->
                PrimaryButton(c, "Use recommended", Icons.Rounded.CheckCircle, Modifier.fillMaxWidth().padding(top = 10.dp)) { actions.onUseRecommended(p.profileId, p.name) }
            }
        }
    }
}

/** Current network, state, phase, progress, hypothesis and the test running now. */
@Composable
private fun LiveSummary(c: LabColors, live: LiveAnalysis) {
    Panel(c) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabText("${live.networkLabel} · ${live.state}", c.text, 14.5.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
                LabText("${live.testsDone}/${live.budget} tests", c.text3, 12.sp, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.divider)) {
                Box(Modifier.fillMaxWidth((live.testsDone.toFloat() / live.budget.coerceAtLeast(1)).coerceIn(0f, 1f)).height(4.dp).background(c.accent))
            }
            LabText(live.phase.title, c.text2, 12.5.sp, FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            LabText(live.hypothesis, c.text2, 12.5.sp, maxLines = 3, lineHeight = 17.sp)
            live.currentTest?.let { LabText("Testing now: $it", c.accent, 12.5.sp, FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
            live.firstWorking?.let { LabText("First working path: ${it.name} (${it.family.title})", c.good, 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp)) }
            live.events.takeLast(3).forEach { LabText("• $it", c.text3, 11.5.sp, maxLines = 2, lineHeight = 15.sp) }
        }
    }
}

@Composable
private fun ReportHeadline(c: LabColors, r: AnalysisReport, relativeTime: (Long) -> String) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabText(r.state, c.text, 15.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
            r.best?.let { Badge(it.status.title, pathColor(c, it.status)) } ?: Badge("No path", c.bad)
        }
        LabText(r.note, c.text2, 12.5.sp, maxLines = 3, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        LabText("${r.mode} · ${relativeTime(r.finishedAt)}", c.text3, 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PathRow(c: LabColors, p: LivePath, technical: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(pathColor(c, p.status))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            LabText(p.title + (p.latencyMs?.let { " · $it ms" } ?: ""), c.text, 13.5.sp, FontWeight.Medium, maxLines = 1)
            if (p.reason.isNotBlank()) LabText(p.reason, c.text3, 12.sp, maxLines = 2, lineHeight = 16.sp)
            if (technical) LabText(listOfNotNull(
                p.stage.title.takeIf { p.stage != com.example.vpn.lab.ConnectionStage.NOT_TESTED },
                p.confidence?.let { "confidence ${pct(it)}" },
                "${p.attempts} request(s)".takeIf { p.attempts > 0 },
                "${p.passed}/${p.tried} configs passed".takeIf { p.tried > 0 },
                p.sessionId?.let { "session ${it.takeLast(6)}" }
            ).joinToString(" · "), c.text3, 11.sp, maxLines = 2, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        Badge(p.status.title, pathColor(c, p.status))
    }
}

@Composable
private fun MethodRow(c: LabColors, rank: Int, m: RankedMethod) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText("$rank", c.text3, 13.sp, FontWeight.SemiBold, Modifier.width(22.dp), maxLines = 1)
        Column(Modifier.weight(1f)) {
            LabText(m.name, c.text, 13.5.sp, FontWeight.Medium, maxLines = 1)
            LabText(listOfNotNull(m.family.title, m.latencyMs?.let { "$it ms" }, "${m.passes}/${m.attempts} passed".takeIf { m.attempts > 0 }, m.why).joinToString(" · "),
                c.text3, 12.sp, maxLines = 2, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Badge(m.status.title, pathColor(c, m.status))
    }
}

@Composable
private fun ReportPage(c: LabColors, state: NetworkLabUiState, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    val r = state.snapshot.analysis
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (r == null) {
            item { Empty(c, "No analysis yet", "Tap Start full analysis on the LAB home. It measures this network, tests your saved configs outside the VPN and ranks what works.") }
            return@LazyColumn
        }
        item { Panel(c) { ReportHeadline(c, r, relativeTime) } }
        bestNow(state.snapshot)?.let { b -> item { BestConnectionCard(c, b, actions) } }
        item { GroupLabel(c, "Why this plan") }
        item {
            Panel(c) {
                Column(Modifier.padding(14.dp)) {
                    (r.restrictions.takeIf { it.isNotEmpty() }?.let { listOf("Measured: " + it.joinToString(", ")) } ?: emptyList<String>())
                        .plus(r.planReasons).forEach { LabText("• $it", c.text2, 12.5.sp, maxLines = 4, lineHeight = 17.sp) }
                }
            }
        }
        if (r.events.isNotEmpty() || r.firstWorking != null || r.stopReason != null) {
            item { GroupLabel(c, "How the plan changed") }
            item {
                Panel(c) {
                    Column(Modifier.padding(14.dp)) {
                        (r.events + listOfNotNull(r.firstWorking?.let { "First working path: $it." }, r.stopReason?.let { "Stopped: $it." }))
                            .forEach { LabText("• $it", c.text2, 12.5.sp, maxLines = 4, lineHeight = 17.sp) }
                    }
                }
            }
        }
        item { GroupLabel(c, "Live connectivity paths") }
        item {
            Panel(c) {
                r.paths.sortedBy { it.status.ordinal }.forEachIndexed { i, p -> if (i > 0) Hairline(c); PathRow(c, p, technical = true) }
            }
        }
        item { GroupLabel(c, "Ranked methods") }
        item {
            Panel(c) {
                if (r.ranked.isEmpty()) LabText("No saved config was tested in this run.", c.text3, 12.5.sp, modifier = Modifier.padding(14.dp))
                r.ranked.forEachIndexed { i, m -> if (i > 0) Hairline(c); MethodRow(c, i + 1, m) }
            }
        }
        item { GroupLabel(c, "Not tested in this run") }
        item {
            Panel(c) {
                Column(Modifier.padding(14.dp)) {
                    r.untested.forEach { LabText("• $it", c.text3, 12.sp, maxLines = 3, lineHeight = 16.sp) }
                }
            }
        }
        if (!state.aiReady) item { Footnote(c, "AI analysis unavailable: no AI provider is set up. Every result above comes from measurements on this phone.") }
        item { Footnote(c, "Saved configs are never changed. Old results only change what is tried first; every status above was measured in this run or says it was not tested.") }
    }
}

// ---------------------------------------------------------------- experiments

private fun stateColor(c: LabColors, s: ExperimentState) = when (s) {
    ExperimentState.VERIFIED -> c.good
    ExperimentState.CANDIDATE, ExperimentState.TESTING, ExperimentState.VERIFYING, ExperimentState.QUEUED, ExperimentState.CREATED -> c.okay
    ExperimentState.CANCELLED -> c.text3
    else -> c.bad
}

@Composable
private fun ExperimentsPage(c: LabColors, experiments: List<LabExperiment>, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (experiments.isEmpty()) item { Empty(c, "No experiments yet", "Experiments start when you run one, or by themselves at Auto LAB.") }
        items(experiments, key = { it.experimentId }) { ExperimentCard(c, it, relativeTime) }
    }
}

@Composable
private fun ExperimentCard(c: LabColors, e: LabExperiment, relativeTime: (Long) -> String) {
    var open by remember { mutableStateOf(false) }
    val best = e.best
    Panel(c) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LabText(e.experimentId, c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                LabText("${e.networkLabel} · ${relativeTime(e.startTime)}", c.text3, 12.sp, maxLines = 1)
            }
            Badge(title(e.state), stateColor(c, e.state))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Metric(c, "Candidates", "${e.candidates.size}")
            VDivider(c)
            Metric(c, "Best", best?.candidateId?.removePrefix("CAND-") ?: "—")
            VDivider(c)
            Metric(c, "Success", best?.let { pct(it.stats.successRate) } ?: "—", best?.stats?.successRate?.toFloat(), c.good)
        }
        if (open) {
            Hairline(c)
            Column(Modifier.padding(14.dp)) {
                e.baselineFailure?.let { LabText("Baseline failure: ${title(it)}", c.text2, 12.5.sp, FontWeight.Medium, maxLines = 1) }
                e.note?.let { LabText(it, c.text3, 12.sp, maxLines = 3, lineHeight = 16.sp) }
            }
            e.candidates.forEachIndexed { i, cand ->
                Hairline(c, 14.dp)
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabText(cand.candidateId, c.text, 13.sp, FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        LabText(cand.mutationProfileId.substringBefore('@'), c.text3, 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        LabText(title(cand.state), if (cand.state == PromotionState.VERIFIED) c.good else c.text2, 12.sp, FontWeight.Medium, maxLines = 1)
                    }
                    LabText(cand.changes.joinToString(" · ") { "${it.field}: ${it.to.ifBlank { "off" }}" }.ifBlank { "no field change" }, c.text3, 11.5.sp, maxLines = 2)
                    LabText(
                        if (!cand.securityPassed) "Refused by the security gate: ${cand.securityReason ?: "unsafe change"}"
                        else "${cand.stats.successes} of ${cand.stats.attempts} requests passed" + (cand.stats.medianLatencyMs?.let { " · median $it ms" } ?: ""),
                        if (cand.securityPassed) c.text2 else c.bad, 11.5.sp, maxLines = 2
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- discoveries

private fun LabDiscovery.Kind.label(): String = when (this) {
    LabDiscovery.Kind.PROFILE_VERIFIED -> "Profile verified"
    LabDiscovery.Kind.PROFILE_DEGRADED -> "Profile degraded"
    LabDiscovery.Kind.TRANSPORT_DEGRADED -> "Transport degraded"
    LabDiscovery.Kind.RESEARCH_CANDIDATE -> "Research"
    LabDiscovery.Kind.CORE_CAPABILITY -> "Core capability"
    LabDiscovery.Kind.PROVIDER_MODEL -> "AI provider"
    LabDiscovery.Kind.NETWORK_BEHAVIOR -> "Network behavior"
}

@Composable
private fun DiscoveriesPage(c: LabColors, state: NetworkLabUiState, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    val items = state.snapshot.discoveries
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.aiReady) item {
            Row(Modifier.padding(horizontal = Gutter)) {
                SecondaryButton(c, if (state.advising) "LAB Agent is reading…" else "Ask LAB Agent to explain", Icons.Rounded.Lightbulb, Modifier.fillMaxWidth(), !state.advising, onClick = actions.onAskAgent)
            }
        }
        if (state.suggestions.isNotEmpty()) item {
            Panel(c) {
                Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LabText("Suggested changes", c.text, 14.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    Badge("AI · not tested", c.okay)
                }
                state.suggestions.forEachIndexed { i, sug ->
                    if (i > 0) Hairline(c, 14.dp)
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            LabText("${sug.field} → ${sug.value}", c.text, 13.sp, FontWeight.SemiBold, maxLines = 1)
                            if (sug.why.isNotBlank()) LabText(sug.why, c.text3, 12.sp, maxLines = 2)
                        }
                        TextAction(c, "Test", enabled = state.snapshot.running == null && !state.vpnOn) { actions.onTestSuggestion(sug) }
                    }
                }
                Footnote(c, "A test runs only if the change passes the LAB allowlist and security gate.", inPanel = true)
            }
        }
        if (items.isEmpty()) item { Empty(c, "No discoveries yet", "Findings from experiments and research appear here.") }
        items(items, key = { it.id }) { d -> DiscoveryRow(c, d, relativeTime) }
    }
}

@Composable
private fun DiscoveryRow(c: LabColors, d: LabDiscovery, relativeTime: (Long) -> String) {
    val tint = when (d.kind) {
        LabDiscovery.Kind.PROFILE_VERIFIED -> c.good
        LabDiscovery.Kind.PROFILE_DEGRADED, LabDiscovery.Kind.TRANSPORT_DEGRADED -> c.bad
        else -> c.info
    }
    Panel(c) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(tint, 7.dp)
                Spacer(Modifier.width(7.dp))
                LabText(d.kind.label().uppercase(), c.text3, 10.5.sp, FontWeight.SemiBold, Modifier.weight(1f), letterSpacing = 0.6.sp, maxLines = 1)
                Badge(if (d.verified) "Measured" else "Unverified", if (d.verified) c.good else c.okay)
            }
            Spacer(Modifier.height(8.dp))
            LabText(d.title, c.text, 14.5.sp, FontWeight.SemiBold, maxLines = 2)
            LabText(d.detail, c.text2, 12.5.sp, maxLines = 3, lineHeight = 17.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabText("Confidence", c.text3, 11.5.sp, modifier = Modifier.width(72.dp), maxLines = 1)
                Meter(c, d.confidence.toFloat(), if (d.verified) c.good else c.okay, Modifier.width(80.dp))
                LabText(pct(d.confidence), c.text2, 11.5.sp, FontWeight.Medium, Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
            }
            LabText("${d.source} · ${relativeTime(d.at)}", c.text3, 11.5.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- verified profiles

@Composable
private fun VerifiedPage(c: LabColors, s: LabSnapshot, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    val list = s.verified.sortedWith(compareBy<VerifiedNetworkProfile> { it.state == PromotionState.RETIRED }.thenByDescending { it.confidence })
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (list.isEmpty()) item { Empty(c, "No verified profiles", "A copy becomes verified after at least 3 passing real requests on a network.") }
        items(list, key = { it.profileId }) { VerifiedCard(c, it, it.contextKey == s.network?.contextKey, s.running == null, actions, relativeTime) }
    }
}

private fun promotionColor(c: LabColors, p: PromotionState) = when (p) {
    PromotionState.VERIFIED -> c.good
    PromotionState.CANDIDATE, PromotionState.EXPERIMENTAL, PromotionState.DEGRADED -> c.okay
    PromotionState.RETIRED, PromotionState.REJECTED -> c.text3
}

@Composable
private fun VerifiedCard(c: LabColors, v: VerifiedNetworkProfile, here: Boolean, idle: Boolean, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    var open by remember { mutableStateOf(false) }
    val retired = v.state == PromotionState.RETIRED
    Panel(c) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LabText(v.strategy, c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                LabText("${v.profileId} · ${v.networkLabel}", c.text3, 12.sp, maxLines = 1)
            }
            Badge(if (v.disabled) "Disabled" else title(v.state), if (v.disabled) c.text3 else promotionColor(c, v.state))
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            MeterLine(c, "Success", v.stats.successRate, c.good)
            MeterLine(c, "Confidence", v.confidence, c.accent)
        }
        Hairline(c, 14.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Metric(c, "Latency", v.stats.medianLatencyMs?.let { "$it ms" } ?: "—")
            VDivider(c)
            Metric(c, "Stability", v.stats.jitterMs?.let { "±$it ms" } ?: "—")
            VDivider(c)
            Metric(c, "Verified", relativeTime(v.lastVerifiedAt))
        }
        Hairline(c, 14.dp)
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = if (v.securityPassed) c.good else c.bad, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            LabText(if (v.securityPassed) "Security gate passed" else "Security gate refused", c.text2, 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            TextAction(c, "Retest", here && idle && !retired && !v.disabled) { actions.onRetest(v.profileId) }
            TextAction(c, if (v.disabled) "Enable" else "Disable", !retired) { actions.onDisable(v.profileId, !v.disabled) }
            TextAction(c, "Retire", !retired, c.bad) { actions.onRetire(v.profileId) }
            TextAction(c, if (open) "Less" else "Details") { open = !open }
        }
        if (open) {
            Hairline(c)
            Column(Modifier.background(c.cardAlt).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                LabText("Mutation  ${v.mutationProfileId}", c.text2, 12.sp, maxLines = 1)
                LabText("Network  ${v.contextKey}", c.text2, 12.sp, maxLines = 1)
                v.endpoint?.let { LabText("Edge address  $it", c.text2, 12.sp, maxLines = 1) }
                LabText("Requests  ${v.stats.successes} of ${v.stats.attempts} passed" + if (v.stats.consecutiveFailures > 0) " · ${v.stats.consecutiveFailures} recent failure(s)" else "", c.text2, 12.sp, maxLines = 1)
                LabText("Rollback  the original config is used unchanged", c.text3, 12.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun MeterLine(c: LabColors, label: String, value: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LabText(label, c.text2, 12.sp, modifier = Modifier.width(78.dp), maxLines = 1)
        Meter(c, value.toFloat(), color, Modifier.weight(1f))
        LabText(pct(value), c.text, 12.5.sp, FontWeight.SemiBold, Modifier.padding(start = 10.dp).width(38.dp), maxLines = 1)
    }
}

// ---------------------------------------------------------------- research

@Composable
private fun ResearchPage(c: LabColors, items: List<LabResearchItem>, actions: NetworkLabActions, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.padding(horizontal = Gutter)) {
                SecondaryButton(c, "Check sources", Icons.Rounded.Refresh, Modifier.fillMaxWidth(), onClick = actions.onResearchRefresh)
            }
        }
        if (items.isEmpty()) item { Empty(c, "No research yet", "Check sources to look for new core releases and transport ideas.") }
        else item {
            Panel(c) {
                items.forEachIndexed { i, r ->
                    if (i > 0) Hairline(c, 14.dp)
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LabText(r.title, c.text, 14.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
                            Spacer(Modifier.width(8.dp))
                            Badge(r.state, when (r.state) { "Verified", "Compatible" -> c.good; "Rejected", "Expired" -> c.text3; else -> c.okay })
                        }
                        LabText(r.compatibility, c.text2, 12.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 3, lineHeight = 17.sp)
                        LabText("${r.source} · ${relativeTime(r.at)}", c.text3, 11.5.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                    }
                }
            }
        }
        item {
            Footnote(c, "Research reads public release notes. Nothing found here runs by itself: an idea becomes an experiment only through the LAB allowlist and security gate, and is verified only by real requests on this phone.")
        }
    }
}

@Composable
private fun Empty(c: LabColors, title: String, body: String) {
    Panel(c) {
        Column(Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LabText(title, c.text, 14.5.sp, FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            LabText(body, c.text3, 12.5.sp, maxLines = 3, lineHeight = 17.sp)
        }
    }
}
