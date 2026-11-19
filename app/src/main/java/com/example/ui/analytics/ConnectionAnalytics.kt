package com.example.ui.analytics

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
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 620.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.text) }
                    Column {
                        LabText("Analytics", c.text, 27.sp, FontWeight.Bold)
                        LabText("Connection performance · up to 60 minutes", c.text2, 12.sp)
                    }
                }
            }
            item {
                AnalyticsCard(c, state.statusLabel, state.activeRoute ?: state.activeProfile?.name ?: "Connection") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(c, "Latency", samples.lastOrNull()?.latencyMs?.takeIf { state.isConnected }?.let { "$it ms" }, Modifier.weight(1f))
                        Metric(c, "Download", state.downloadSpeedBps.takeIf { state.isConnected }?.toDouble()?.let(::speed), Modifier.weight(1f))
                        Metric(c, "Upload", state.uploadSpeedBps.takeIf { state.isConnected }?.toDouble()?.let(::speed), Modifier.weight(1f))
                    }
                }
            }
            item {
                AnalyticsCard(c, "Connection latency", "Recent latency measurements") {
                    AnalyticsPlot(c, samples, { it.latencyMs }, unit = "ms")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(c, "Average", latency.takeIf { it.isNotEmpty() }?.average()?.let { "${it.toInt()} ms" }, Modifier.weight(1f))
                        Metric(c, "Minimum", latency.minOrNull()?.let { "$it ms" }, Modifier.weight(1f))
                        Metric(c, "Maximum", latency.maxOrNull()?.let { "$it ms" }, Modifier.weight(1f))
                    }
                }
            }
            item {
                AnalyticsCard(c, "Transfer speed", "Observed traffic rate through the app") {
                    TransferBars(c, samples)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(c, "Avg. download", down.takeIf { it.isNotEmpty() }?.average()?.let(::speed), Modifier.weight(1f))
                        Metric(c, "Avg. upload", up.takeIf { it.isNotEmpty() }?.average()?.let(::speed), Modifier.weight(1f), c.info)
                    }
                }
            }
            item {
                AnalyticsCard(c, "Connection stability", "Share of recorded samples with verified traffic") {
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
                        Metric(c, "Verified", samples.takeIf { it.isNotEmpty() }?.let { "${stable * 100 / it.size}%" }, Modifier.weight(1f))
                        Metric(c, "Reconnecting", samples.takeIf { it.isNotEmpty() }?.let { "$reconnecting samples" },
                            Modifier.weight(1f), c.okay)
                    }
                }
            }
            item {
                AnalyticsCard(c, "Session summary", "Latest tunnel counters on this device") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(c, "Downloaded", tail?.let { TrafficStats.formatBytes(it.downloaded) }, Modifier.weight(1f))
                        Metric(c, "Uploaded", tail?.let { TrafficStats.formatBytes(it.uploaded) }, Modifier.weight(1f))
                        Metric(c, "Duration", tail?.let { "${it.durationSeconds / 60}m ${it.durationSeconds % 60}s" }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

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
        LabText("Latency measurements are not available yet.", c.text2, 13.sp, modifier = Modifier.padding(vertical = 12.dp))
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
        LabText("Transfer samples appear after traffic is verified.", c.text2, 13.sp)
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
        LabText("Earlier", c.text2, 10.sp)
        LabText("Latest sample", c.text2, 10.sp)
    }
}

private fun speed(bytesPerSecond: Double): String =
    String.format(Locale.US, "%.1f Mbps", bytesPerSecond * 8 / 1_000_000)
