package com.example.ui.analytics

import androidx.compose.ui.res.stringResource
import com.example.R
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.TrafficStats
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.ui.protocols.labCard
import com.example.ui.protocols.labColors
import java.util.Locale

internal data class AnalyticsSample(
    val atMs: Long,
    val status: ConnectionStatus,
    val latencyMs: Long?,
    val downloadBps: Long?,
    val uploadBps: Long?,
    val downloaded: Long,
    val uploaded: Long,
    val durationSeconds: Long
)

internal class ConnectionAnalytics {
    var samples by mutableStateOf(emptyList<AnalyticsSample>())
        private set
    private var session: String? = null

    fun record(state: ConnectionState, elapsedMs: Long = SystemClock.elapsedRealtime(), wallMs: Long = System.currentTimeMillis()) {
        if (state.sessionId == null ||
            state.status == ConnectionStatus.DISCONNECTED && samples.lastOrNull()?.status == ConnectionStatus.DISCONNECTED) return
        if (session != state.sessionId) {
            session = state.sessionId
            samples = emptyList()
        }
        val previous = samples.lastOrNull()
        val active = state.isTunnelUp && state.verifiedAt != null
        val freshPing = state.pingCheckedAt?.let { wallMs - it in 0..120_000L } == true
        val sample = AnalyticsSample(elapsedMs, state.status, state.pingMs?.takeIf { active && freshPing && it >= 0 },
            state.downloadSpeedBps.takeIf { active && it >= 0 }, state.uploadSpeedBps.takeIf { active && it >= 0 },
            state.downloadBytes.takeIf { state.isTunnelUp } ?: previous?.downloaded ?: 0,
            state.uploadBytes.takeIf { state.isTunnelUp } ?: previous?.uploaded ?: 0,
            state.connectedDurationSeconds.takeIf { state.isTunnelUp } ?: previous?.durationSeconds ?: 0)
        samples = (samples + sample).filter { elapsedMs - it.atMs <= 3_600_000L }.takeLast(721)
    }
}

@Composable
internal fun ConnectionAnalyticsScreen(model: ConnectionAnalytics, state: ConnectionState, onBack: () -> Unit) {
    val palette = labColors
    val c = palette.copy(accent = if (palette.dark) Color(0xFF36E8F0) else Color(0xFF007580))
    val samples = model.samples
    val tail = samples.lastOrNull()
    val latency = samples.mapNotNull { it.latencyMs }
    val down = samples.mapNotNull { it.downloadBps }
    val up = samples.mapNotNull { it.uploadBps }
    val stable = samples.count { it.status == ConnectionStatus.CONNECTED }
    val reconnecting = samples.count { it.status in recoveryStatuses }
    val stages by com.example.vpn.diagnostics.EngineStages.entries.collectAsState()
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 620.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), tint = c.text) }
                    Column {
                        LabText(stringResource(R.string.ui_analytics), c.text, 27.sp, FontWeight.Bold)
                        LabText(stringResource(R.string.ui_connection_performance_up_to_60), c.text2, 12.sp)
                    }
                }
            }
            item {
                AnalyticsCard(c, stringResource(com.example.ui.language.UiText.status(state)), state.activeRoute ?: state.activeProfile?.name ?: stringResource(R.string.ui_connection)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(c, stringResource(R.string.ui_latency), samples.lastOrNull()?.latencyMs?.takeIf { state.isConnected }?.let { "$it ms" }, Modifier.weight(1f))
                        Metric(c, stringResource(R.string.analytics_download), state.downloadSpeedBps.takeIf { state.isConnected }?.toDouble()?.let(::speed), Modifier.weight(1f))
                        Metric(c, stringResource(R.string.analytics_upload), state.uploadSpeedBps.takeIf { state.isConnected }?.toDouble()?.let(::speed), Modifier.weight(1f))
                    }
                }
            }
            item {
                // 2026-11-25 engine test: while connecting this screen said only "CONNECTING". Each engine's stages and
                // errors (EngineStages) are listed here, newest last.
                AnalyticsCard(c, stringResource(R.string.ui_connection_stages), stringResource(R.string.ui_connection_stages_hint)) {
                    if (stages.isEmpty()) {
                        LabText(stringResource(R.string.ui_connection_stages_empty), c.text2, 13.sp)
                    } else stages.takeLast(STAGES_SHOWN).forEach { entry ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabText(stageTime(entry.at), c.text3, 11.sp)
                            Column(Modifier.weight(1f)) {
                                LabText("${entry.engine} · ${entry.stage}", if (entry.error) c.bad else c.text, 13.sp,
                                    if (entry.error) FontWeight.SemiBold else FontWeight.Normal)
                                entry.detail?.let { LabText(it, c.text2, 11.sp, maxLines = 3) }
                            }
                        }
                    }
                }
            }
            item { FirewallTypeCard(c, state.status) }
            item {
                AnalyticsCard(c, stringResource(R.string.ui_connection_latency), stringResource(R.string.ui_recent_latency_measurements)) {
                    AnalyticsPlot(c, samples, { it.latencyMs }, unit = "ms")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(c, stringResource(R.string.analytics_average), latency.takeIf { it.isNotEmpty() }?.average()?.let { "${it.toInt()} ms" }, Modifier.weight(1f))
                        Metric(c, stringResource(R.string.analytics_minimum), latency.minOrNull()?.let { "$it ms" }, Modifier.weight(1f))
                        Metric(c, stringResource(R.string.analytics_maximum), latency.maxOrNull()?.let { "$it ms" }, Modifier.weight(1f))
                    }
                }
            }
            item {
                AnalyticsCard(c, stringResource(R.string.ui_transfer_speed), stringResource(R.string.ui_observed_traffic_rate_through_the)) {
                    TransferBars(c, samples)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(c, stringResource(R.string.analytics_avg_download), down.takeIf { it.isNotEmpty() }?.average()?.let(::speed), Modifier.weight(1f))
                        Metric(c, stringResource(R.string.analytics_avg_upload), up.takeIf { it.isNotEmpty() }?.average()?.let(::speed), Modifier.weight(1f), c.info)
                    }
                }
            }
            item {
                AnalyticsCard(c, stringResource(R.string.ui_connection_stability), stringResource(R.string.ui_share_of_recorded_samples_with)) {
                    if (samples.isNotEmpty()) Canvas(Modifier.fillMaxWidth().height(11.dp)) {
                        val step = size.width / samples.size
                        samples.forEachIndexed { i, sample ->
                            val tint = when (sample.status) {
                                ConnectionStatus.CONNECTED -> c.accent
                                in recoveryStatuses -> c.okay
                                else -> c.stroke
                            }
                            drawLine(tint, Offset(i * step, center.y), Offset((i + 1) * step, center.y), strokeWidth = size.height)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(c, stringResource(R.string.ui_verified), samples.takeIf { it.isNotEmpty() }?.let { "${stable * 100 / it.size}%" }, Modifier.weight(1f))
                        Metric(c, stringResource(R.string.analytics_reconnecting), samples.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.analytics_samples, reconnecting) },
                            Modifier.weight(1f), c.okay)
                    }
                }
            }
            item {
                AnalyticsCard(c, stringResource(R.string.ui_session_summary), stringResource(R.string.ui_latest_tunnel_counters_on_this)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(c, stringResource(R.string.analytics_downloaded), tail?.let { TrafficStats.formatBytes(it.downloaded) }, Modifier.weight(1f))
                        Metric(c, stringResource(R.string.analytics_uploaded), tail?.let { TrafficStats.formatBytes(it.uploaded) }, Modifier.weight(1f))
                        Metric(c, stringResource(R.string.analytics_duration), tail?.let { "${it.durationSeconds / 60}m ${it.durationSeconds % 60}s" }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private const val STAGES_SHOWN = 14

/**
 * Which firewall type the app is using on this network and where that came from: the behaviour probe
 * ([com.example.vpn.connectivity.FirewallProbe], only when confident) or the mobile network code.
 */
@Composable
private fun FirewallTypeCard(c: LabColors, status: ConnectionStatus) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.runtime.remember(status) {
        val key = runCatching { com.example.vpn.smart.NetworkKey.current(context) }.getOrNull()
        val identity = runCatching { com.example.vpn.smart.NetworkIdentity.current(context) }.getOrNull()
        val detection = com.example.vpn.connectivity.NetworkFirewalls.detection(key)
        val probe = com.example.vpn.connectivity.FirewallProbe.store?.latest?.takeIf { it.identity == identity }
        detection to probe
    }
    val (detection, probe) = view
    val firewall = detection.firewall
    val detected = when (firewall) {
        com.example.vpn.connectivity.NetworkFirewalls.Firewall.IRANCELL -> stringResource(R.string.ui_firewall_irancell_type)
        com.example.vpn.connectivity.NetworkFirewalls.Firewall.MCI -> stringResource(R.string.ui_firewall_mci_type)
        com.example.vpn.connectivity.NetworkFirewalls.Firewall.RIGHTEL -> firewall.title
        com.example.vpn.connectivity.NetworkFirewalls.Firewall.OTHER -> stringResource(R.string.ui_firewall_not_known)
    }
    val source = when (detection.source) {
        com.example.vpn.connectivity.NetworkFirewalls.Source.BEHAVIOUR -> stringResource(R.string.ui_firewall_by_behaviour)
        com.example.vpn.connectivity.NetworkFirewalls.Source.NETWORK_CODE -> stringResource(R.string.ui_firewall_by_network_code)
        com.example.vpn.connectivity.NetworkFirewalls.Source.NONE -> stringResource(R.string.ui_firewall_no_source)
    }
    val probeText = when {
        probe == null -> stringResource(R.string.ui_firewall_probe_none)
        probe.verdict == com.example.vpn.connectivity.FirewallBehaviour.Verdict.IRANCELL_LIKE -> stringResource(R.string.ui_firewall_irancell_type)
        probe.verdict == com.example.vpn.connectivity.FirewallBehaviour.Verdict.MCI_LIKE -> stringResource(R.string.ui_firewall_mci_type)
        else -> stringResource(R.string.ui_firewall_probe_unknown)
    }
    AnalyticsCard(c, stringResource(R.string.ui_firewall_type), stringResource(R.string.ui_firewall_type_hint)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(c, stringResource(R.string.ui_firewall_detected), detected, Modifier.weight(1f))
            Metric(c, stringResource(R.string.ui_firewall_source), source, Modifier.weight(1f), c.info)
            Metric(c, stringResource(R.string.ui_firewall_probe), probeText, Modifier.weight(1f), c.okay)
        }
        if (detection.source == com.example.vpn.connectivity.NetworkFirewalls.Source.BEHAVIOUR &&
            detection.byCode != com.example.vpn.connectivity.NetworkFirewalls.Firewall.OTHER && detection.byCode != firewall) {
            LabText(stringResource(R.string.ui_firewall_code_differs, detection.byCode.title), c.text2, 12.sp, maxLines = 2)
        }
        probe?.let { LabText("${stageTime(it.measuredAt)} · ${it.summary()}", c.text3, 11.sp, maxLines = 2) }
        LabText(stringResource(R.string.ui_firewall_unverified), c.text3, 11.sp, maxLines = 3)
    }
}

private fun stageTime(at: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(at))

private val recoveryStatuses = setOf(ConnectionStatus.RECONNECTING, ConnectionStatus.SWITCHING, ConnectionStatus.NETWORK_CHANGED)

@Composable
private fun AnalyticsCard(c: LabColors, title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().labCard(c, 16.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        LabText(title, c.text, 18.sp, FontWeight.Bold)
        LabText(subtitle, c.text2, 12.sp, maxLines = 2)
        content()
    }
}

@Composable
private fun Metric(c: LabColors, label: String, value: String?, modifier: Modifier, tint: Color = c.accent) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabText(label, c.text2, 11.sp)
        LabText(value ?: "—", tint, 15.sp, FontWeight.SemiBold)
    }
}

@Composable
private fun AnalyticsPlot(c: LabColors, samples: List<AnalyticsSample>, primary: (AnalyticsSample) -> Long?, unit: String) {
    val maximum = samples.maxOfOrNull { primary(it) ?: 0 } ?: 0
    if (samples.none { primary(it) != null }) {
        LabText(stringResource(R.string.ui_latency_measurements_are_not_available), c.text2, 13.sp, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    LabText("$maximum $unit", c.text2, 10.sp)
    Canvas(Modifier.fillMaxWidth().height(104.dp)) {
        val first = samples.first().atMs
        val span = (samples.last().atMs - first).coerceAtLeast(1L)
        val scale = maximum.coerceAtLeast(1L)
        for (i in 0..3) {
            val y = 5f + (size.height - 10f) * i / 3
            drawLine(c.stroke, Offset(0f, y), Offset(size.width, y))
        }
        var last: Offset? = null
        samples.forEach { sample ->
            val point = primary(sample)?.let { value ->
                Offset((sample.atMs - first).toFloat() / span * size.width,
                    size.height - 5f - value.toFloat() / scale * (size.height - 10f))
            }
            if (point != null) {
                val from = last
                if (from == null) drawCircle(c.accent, radius = 2.5.dp.toPx(), center = point)
                else drawLine(c.accent, from, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            last = point
        }
    }
    ChartTime(c)
}

@Composable
private fun TransferBars(c: LabColors, samples: List<AnalyticsSample>) {
    if (samples.none { it.downloadBps != null || it.uploadBps != null }) {
        LabText(stringResource(R.string.ui_transfer_samples_appear_after_traffic), c.text2, 13.sp)
        return
    }
    val groups = samples.chunked(((samples.size + 11) / 12).coerceAtLeast(1)).map { group ->
        group.mapNotNull { it.downloadBps }.average() to group.mapNotNull { it.uploadBps }.average()
    }
    val max = groups.flatMap { listOf(it.first, it.second) }.filter { it.isFinite() }.maxOrNull() ?: 0.0
    LabText(speed(max), c.text2, 10.sp)
    Canvas(Modifier.fillMaxWidth().height(104.dp)) {
        val cell = size.width / groups.size
        val bar = cell * .30f
        for (i in 0..3) {
            val y = size.height * i / 3
            drawLine(c.stroke, Offset(0f, y), Offset(size.width, y))
        }
        groups.forEachIndexed { index, (down, up) ->
            for ((offset, value, tint) in listOf(Triple(.16f, down, c.accent), Triple(.52f, up, c.info))) {
                if (!value.isFinite() || value <= 0) continue
                val height = (value / max.coerceAtLeast(1.0) * size.height).toFloat()
                drawRect(tint, Offset(cell * (index + offset), size.height - height), Size(bar, height))
            }
        }
    }
    ChartTime(c)
}

@Composable
private fun ChartTime(c: LabColors) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        LabText(stringResource(R.string.ui_earlier), c.text2, 10.sp)
        LabText(stringResource(R.string.ui_latest_sample), c.text2, 10.sp)
    }
}

private fun speed(bytesPerSecond: Double): String =
    String.format(Locale.US, "%.1f Mbps", bytesPerSecond * 8 / 1_000_000)
