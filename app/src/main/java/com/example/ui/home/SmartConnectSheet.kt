package com.example.ui.home

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.RayApplication
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.ui.lab.Badge
import com.example.ui.lab.Footnote
import com.example.ui.lab.GroupLabel
import com.example.ui.lab.Gutter
import com.example.ui.lab.Hairline
import com.example.ui.lab.KeyValue
import com.example.ui.lab.Meter
import com.example.ui.lab.Panel
import com.example.ui.lab.SecondaryButton
import com.example.ui.lab.TitleBar
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.ui.protocols.labColors
import com.example.ui.theme.AppTheme
import com.example.vpn.RayVpnService
import com.example.vpn.smart.NetworkIdentity
import com.example.vpn.smart.NetworkNames
import com.example.vpn.smart.SearchProgress
import com.example.vpn.verify.TrafficCheck
import com.example.xray.RealDelayProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which view of the search the state supports: still searching, or connected on what it found. */
internal enum class SmartConnectMode { SEARCHING, CONNECTED }

/** The view for [state], or null when this attempt has no search to show. */
internal fun smartConnectMode(state: ConnectionState): SmartConnectMode? {
    val search = state.search?.takeIf { it.attemptId == state.attemptId } ?: return null
    return when {
        state.status == ConnectionStatus.CONNECTED -> SmartConnectMode.CONNECTED
        state.isBusy && search.stages.isNotEmpty() -> SmartConnectMode.SEARCHING
        else -> null
    }
}

private val limits = SmartConnectRows.Limits(
    roundSize = RealDelayProbe.MAX_BATCH,
    recoverySec = RayVpnService.FAST_RECOVERY_MS / 1000,
    revivalSec = RayVpnService.CONFIG_REVIVE_MS / 1000
)

/** True when the path in use carried traffic both ways in this attempt, on this network. */
private fun bothWays(state: ConnectionState): Boolean =
    state.trafficEvidence?.takeIf { it.appliesTo(state) }?.bidirectional == true

/** The Home entry to the sheet: where the search stands, in one line. */
@Composable
internal fun SmartConnectEntry(state: ConnectionState, mode: SmartConnectMode, onOpen: () -> Unit) {
    val search = state.search ?: return
    val (title, detail) = when (mode) {
        SmartConnectMode.SEARCHING -> Pair(
            SmartConnectRows.headline(search)?.let { SmartConnectRows.heading(it.stage) } ?: "Smart Connect",
            "${search.tested} tested · ${search.verified} verified · ${search.failed} failed"
        )
        SmartConnectMode.CONNECTED -> Pair(
            SmartConnectRows.foundLine(search)?.removeSuffix(".") ?: "Smart Connect",
            if (bothWays(state)) "Both directions verified" else "Traffic verified"
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppTheme.colors.surfaceCard)
            .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Smart Connect", color = AppTheme.colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(title, color = AppTheme.colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(detail, color = AppTheme.colors.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = "Show the search", tint = AppTheme.colors.textMuted, modifier = Modifier.size(20.dp))
    }
}

/**
 * Smart Connect, stage by stage: while searching, what each stage tried and how it went, with Stop; once
 * connected, the path that was found, the evidence that it carries traffic, and how it was found.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartConnectSheet(state: ConnectionState, mode: SmartConnectMode, selectedName: String?, onStop: () -> Unit, onDismiss: () -> Unit) {
    val c = labColors
    val search = state.search ?: return
    val context = LocalContext.current
    // The network the phone is on, by key only: a carrier is named, a Wi-Fi network never is.
    val networkKey by produceState<String?>(null, state.attemptId, state.networkGeneration) {
        value = withContext(Dispatchers.IO) { runCatching { NetworkIdentity.current(context) }.getOrNull() }
    }
    // Whether the route in use is now remembered for this network (it is recorded once it carried traffic).
    val profileId = state.activeProfile?.id
    val remembered by produceState(false, networkKey, profileId, state.verifiedAt, state.trafficEvidence) {
        val key = networkKey
        value = key != null && profileId != null && withContext(Dispatchers.IO) {
            runCatching { RayApplication.instance.networkMemory.routes.forNetwork(key).any { it.profileId == profileId } }.getOrDefault(false)
        }
    }
    val network = NetworkNames.short(networkKey)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg,
        scrimColor = Color.Black.copy(alpha = if (c.dark) 0.6f else 0.35f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            val phrase = if (mode == SmartConnectMode.SEARCHING) "searching for a route that carries traffic" else "connected"
            TitleBar(c, "Smart Connect", network?.let { "$it · $phrase" } ?: phrase.replaceFirstChar { it.uppercase() }, back = null)
            Spacer(Modifier.height(10.dp))
            when (mode) {
                SmartConnectMode.SEARCHING -> Searching(c, search, selectedName, onStop)
                SmartConnectMode.CONNECTED -> Connected(c, state, search, remembered)
            }
        }
    }
}

@Composable
private fun Searching(c: LabColors, search: SearchProgress, selectedName: String?, onStop: () -> Unit) {
    Panel(c) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            SmartConnectRows.headline(search)?.let { s ->
                LabText(SmartConnectRows.heading(s.stage), c.text, 15.sp, FontWeight.SemiBold, maxLines = 2)
                SmartConnectRows.headlineLine(s, limits)?.let {
                    LabText(it, c.text2, 12.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 3, lineHeight = 17.sp)
                }
                Meter(c, SmartConnectRows.position(s.stage), c.accent, Modifier.padding(top = 12.dp).fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Total(c, search.tested, "Tested", Modifier.weight(1f))
                Total(c, search.verified, "Verified", Modifier.weight(1f))
                Total(c, search.failed, "Failed", Modifier.weight(1f))
            }
        }
    }
    GroupLabel(c, "Stages")
    Stages(c, SmartConnectRows.stageRows(search, limits, selectedName))
    SecondaryButton(c, "Stop", Icons.Rounded.Stop, Modifier.padding(horizontal = Gutter).padding(top = 12.dp).fillMaxWidth(), onClick = onStop)
    Footnote(c, "Traffic stays blocked while the search runs. Counts are real requests made on this network.")
}

@Composable
private fun Connected(c: LabColors, state: ConnectionState, search: SearchProgress, remembered: Boolean) {
    val evidence = state.trafficEvidence?.takeIf { it.appliesTo(state) }
    Panel(c) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabText(state.activeProfile?.name?.ifBlank { null } ?: "Server in use", c.text, 15.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 2)
                Spacer(Modifier.width(10.dp))
                Badge(if (evidence?.bidirectional == true) "Both directions verified" else "Traffic verified", c.good)
            }
            val found = listOfNotNull(
                SmartConnectRows.foundLine(search),
                "Remembered for this network and tried first next time.".takeIf { remembered }
            ).joinToString(" ")
            if (found.isNotEmpty()) LabText(found, c.text2, 12.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 3, lineHeight = 17.sp)
        }
        evidence?.let { e ->
            KeyValue(c, "Download check", SmartConnectRows.legValue(e.download, upload = false))
            KeyValue(c, "Upload check", SmartConnectRows.legValue(e.upload, upload = true))
        }
        state.pingMs?.let { KeyValue(c, "Latency", "$it ms") }
        evidence?.let { KeyValue(c, "Checked", SmartConnectRows.ago(it.checkedAt, System.currentTimeMillis())) }
        Spacer(Modifier.height(4.dp))
    }
    GroupLabel(c, "How it was found")
    Stages(c, SmartConnectRows.stageRows(search, limits, summary = true))
    Footnote(c, "\"Both directions verified\" means a ${TrafficCheck.DOWNLOAD_BYTES / 1024} KB download and a " +
        "${TrafficCheck.UPLOAD_BYTES / 1024} KB upload went through the tunnel. A single small request shows as \"Traffic verified\".")
}

@Composable
private fun Total(c: LabColors, value: Int, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(c.cardAlt).padding(horizontal = 12.dp, vertical = 10.dp)) {
        LabText("$value", c.text, 19.sp, FontWeight.SemiBold, maxLines = 1)
        LabText(label, c.text2, 12.sp, maxLines = 1)
    }
}

@Composable
private fun Stages(c: LabColors, rows: List<StageRow>) {
    Panel(c) {
        rows.forEachIndexed { i, r ->
            if (i > 0) Hairline(c)
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val tint = when {
                    r.current -> c.accent
                    r.badge == StageBadge.VERIFIED -> c.good
                    else -> null
                }
                Box(
                    Modifier.widthIn(min = 28.dp).height(28.dp).clip(RoundedCornerShape(7.dp))
                        .background(tint?.copy(alpha = 0.16f) ?: c.cardAlt).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LabText(r.label, tint ?: c.text2, 12.sp, FontWeight.SemiBold, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    LabText(r.title, c.text, 14.sp, FontWeight.Medium, maxLines = 2)
                    LabText(r.detail, c.text3, 12.sp, maxLines = 3, lineHeight = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Badge(r.badge.text, badgeColor(c, r.badge))
            }
        }
    }
}

private fun badgeColor(c: LabColors, b: StageBadge): Color = when (b) {
    StageBadge.TESTING -> c.accent
    StageBadge.VERIFIED -> c.good
    StageBadge.FAILED -> c.bad
    StageBadge.CHOSEN -> c.info
    StageBadge.WAITING, StageBadge.SKIPPED, StageBadge.NOT_REACHED -> c.text3
}
