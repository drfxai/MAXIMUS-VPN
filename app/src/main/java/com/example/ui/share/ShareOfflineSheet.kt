package com.example.ui.share

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.ui.protocols.InterFamily
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.labColors
import com.example.vless.VlessParser
import com.example.vpn.share.ShareCode
import com.example.vpn.stealth.ConnectionKind
import kotlinx.coroutines.delay

private const val WORKING_WINDOW_MS = 24 * 60 * 60 * 1000L
private const val CYCLE_MS = 1500L

/** True when this server carried traffic in a test during the last day. */
fun VlessProfile.workedRecently(now: Long = System.currentTimeMillis()): Boolean =
    lastLatencyMs != null && (lastTestedTimestamp ?: 0L) > now - WORKING_WINDOW_MS

/** The text "Copy link" puts on the clipboard: a standard link where one exists, otherwise a share code. */
fun shareText(profile: VlessProfile): String =
    if (profile.protocolType == ProtocolType.VLESS && profile.rawConfig.isBlank()) VlessParser.toUri(profile) else ShareCode.encode(profile)

private enum class Group { WORKING, FAVORITES, ALL }

/**
 * "Share offline": a server as a QR code another phone running MAXIMUS reads with its camera, with no
 * internet on either phone. "Share working servers" shows several codes one after another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareOfflineSheet(
    profile: VlessProfile,
    allProfiles: List<VlessProfile>,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val c = labColors
    var bundle by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.card,
        scrimColor = Color.Black.copy(alpha = if (c.dark) 0.6f else 0.35f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 22.dp).navigationBarsPadding()) {
            if (bundle) BundleContent(c, allProfiles, onDone = onDismiss)
            else SingleContent(c, profile, onCopy = { onCopy(shareText(profile)) }, onShareWorking = { bundle = true })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleContent(c: LabColors, profile: VlessProfile, onCopy: () -> Unit, onShareWorking: () -> Unit) {
    val code = remember(profile) { ShareCode.encode(profile) }
    Title(c, "Share offline")
    Subtitle(c, "Scan this with MAXIMUS on the other phone. No internet needed on either phone.")
    QrCard(code, 300)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (profile.workedRecently()) {
            val ago = DateUtils.getRelativeTimeSpanString(profile.lastTestedTimestamp ?: 0L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            Chip(c, "● Worked $ago", good = true)
        }
        Chip(c, kindLabel(profile))
        Chip(c, profile.name.take(24))
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(16.dp)).background(c.cardAlt).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Outlined.GppGood, contentDescription = null, tint = c.okay, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Only show it to people you trust. Anyone who scans this code can use this server.",
            color = c.text2, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = InterFamily
        )
    }
    Buttons(c, "Copy link", onCopy, "Share working servers", onShareWorking)
}

@Composable
private fun BundleContent(c: LabColors, all: List<VlessProfile>, onDone: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val groups = remember(all) {
        mapOf(
            Group.WORKING to all.filter { it.workedRecently(now) }.sortedBy { it.lastLatencyMs ?: Long.MAX_VALUE },
            Group.FAVORITES to all.filter { it.isFavorite },
            Group.ALL to all
        )
    }
    var group by remember { mutableStateOf(if (groups.getValue(Group.WORKING).isNotEmpty()) Group.WORKING else Group.ALL) }
    val codes = remember(group, groups) {
        val list = groups.getValue(group)
        list.mapIndexed { i, p -> p to ShareCode.encode(p, i + 1, list.size) }.filter { it.second.length <= ShareCode.MAX_CHARS }
    }
    var index by remember(codes) { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(codes, paused) {
        while (!paused && codes.size > 1) {
            delay(CYCLE_MS)
            index = (index + 1) % codes.size
        }
    }

    Title(c, "Share working servers")
    Subtitle(c, "The codes change by themselves. Keep the other phone pointed at the screen until it says all ${codes.size} are received.")
    Row(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(14.dp)).background(c.cardAlt).padding(4.dp)) {
        listOf(
            Group.WORKING to "Working (${groups.getValue(Group.WORKING).size})",
            Group.FAVORITES to "Favorites (${groups.getValue(Group.FAVORITES).size})",
            Group.ALL to "All (${all.size})"
        ).forEach { (g, label) ->
            val on = g == group
            Text(
                label,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .then(if (on) Modifier.background(c.card).border(1.dp, c.stroke, RoundedCornerShape(11.dp)) else Modifier)
                    .clickable { group = g }.padding(vertical = 9.dp),
                color = if (on) c.text else c.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1, fontFamily = InterFamily
            )
        }
    }
    if (codes.isEmpty()) {
        Text(
            "No servers here yet.", color = c.text2, fontSize = 14.sp, fontFamily = InterFamily,
            modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp), textAlign = TextAlign.Center
        )
        Buttons(c, null, {}, "Done", onDone)
        return
    }
    val (current, code) = codes[index.coerceIn(0, codes.lastIndex)]
    QrCard(code, 270)
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
        codes.indices.take(12).forEach { i ->
            Box(
                Modifier.padding(horizontal = 3.dp).height(7.dp).width(if (i == index) 22.dp else 7.dp)
                    .clip(RoundedCornerShape(4.dp)).background(if (i == index) c.accent else c.stroke)
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(c.cardAlt).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(current.name, color = c.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = InterFamily)
            Text("Code ${index + 1} of ${codes.size} · ${kindLabel(current)}", color = c.text2, fontSize = 12.5.sp, fontFamily = InterFamily)
        }
        current.lastLatencyMs?.let {
            Text(
                "$it ms", color = c.good, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = InterFamily,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(c.good.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
    Buttons(c, if (paused) "Resume" else "Pause", { paused = !paused }, "Done", onDone)
}

fun kindLabel(profile: VlessProfile): String {
    val transport = profile.transport.uppercase().takeIf { it.isNotBlank() && it != "TCP" } ?: "TCP"
    return when (ConnectionKind.of(profile)) {
        "REALITY" -> "REALITY · $transport"
        else -> "${profile.protocolType.displayName} · ${if (profile.protocolType in setOf(ProtocolType.HYSTERIA2, ProtocolType.WIREGUARD)) "UDP" else transport}"
    }
}

@Composable
private fun Title(c: LabColors, text: String) =
    Text(text, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = InterFamily)

@Composable
private fun Subtitle(c: LabColors, text: String) =
    Text(text, color = c.text2, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = InterFamily, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun QrCard(code: String, sizeDp: Int) {
    Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(sizeDp.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            QrCodeImage(code, Modifier.fillMaxWidth().height((sizeDp - 40).dp))
        }
    }
}

@Composable
private fun Chip(c: LabColors, text: String, good: Boolean = false) =
    Text(
        text, maxLines = 1, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = InterFamily,
        color = if (good) c.good else c.text2,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (good) c.good.copy(alpha = 0.12f) else c.cardAlt)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    )

@Composable
private fun Buttons(c: LabColors, secondary: String?, onSecondary: () -> Unit, primary: String, onPrimary: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (secondary != null) {
            Box(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(16.dp)).background(c.cardAlt).clickable(onClick = onSecondary),
                contentAlignment = Alignment.Center
            ) { Text(secondary, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, fontFamily = InterFamily, maxLines = 1) }
        }
        Box(
            Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(c.accent, c.accent2))).clickable(onClick = onPrimary),
            contentAlignment = Alignment.Center
        ) { Text(primary, color = c.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, fontFamily = InterFamily, maxLines = 1) }
    }
}
