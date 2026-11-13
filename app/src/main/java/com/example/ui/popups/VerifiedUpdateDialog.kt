package com.example.ui.popups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.update.AppUpdateChecker
import com.example.update.UpdateProgressModel
import com.example.update.UpdateProgressModel.Phase
import com.example.update.UpdateProgressModel.Step
import com.example.ui.theme.AppTheme

/**
 * Shared verified-update sheet for launch and Settings. Everything it shows comes from the updater's real
 * status (UpdateProgressModel): no fake progress, speed or step, and nothing is installed without the user's
 * tap and Android's own approval. Layouts mirror for right-to-left languages.
 */
@Composable
fun VerifiedUpdateDialog(
    release: AppUpdateChecker.ReleaseInfo,
    installedBuild: Int,
    downloading: Boolean,
    progress: Int,
    verified: Boolean,
    error: String?,
    downloadStatus: String? = null,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    onOfficialLink: () -> Unit
) {
    val colors = AppTheme.colors
    val accent = colors.primary
    val accentDeep = colors.primaryDark
    val state = UpdateProgressModel.state(downloading, verified, error, downloadStatus, progress, release.apk?.size)

    // Speed and time left from a short window of real byte counts (never extrapolated before bytes arrive).
    val samples = remember { mutableStateListOf<Pair<Long, Long>>() }
    LaunchedEffect(state.receivedBytes, state.phase) {
        val bytes = state.receivedBytes
        if (state.phase != Phase.DOWNLOADING || bytes == null) { samples.clear(); return@LaunchedEffect }
        samples.add(System.currentTimeMillis() to bytes)
        while (samples.size > 2 && samples.last().first - samples.first().first > 6_000) samples.removeAt(0)
    }
    val rate = UpdateProgressModel.bytesPerSecond(samples.toList())

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 460.dp).testTag("verified_update_sheet"),
                shape = RoundedCornerShape(30.dp),
                color = colors.surfaceCard,
                border = BorderStroke(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.08f)))),
                shadowElevation = 28.dp
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).animateContentSize()) {
                    Hero(state, accent, accentDeep)
                    Column(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        VersionChips(release, installedBuild, accent)
                        if (release.notes.isNotEmpty()) WhatsNew(release.notes, accent)
                        StepIndicator(state, accent)
                        AnimatedVisibility(
                            visible = state.busy,
                            enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()
                        ) { ProgressCard(state, rate, accent, accentDeep) }
                        StatusLine(state, release, error)
                        PrimaryButton(state, release, accent, accentDeep, onPrimary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onDismiss, modifier = Modifier.testTag("update_dismiss")) {
                                Text(if (state.busy) "Cancel download" else "Later", color = colors.textSecondary, fontSize = 13.sp)
                            }
                            TextButton(onClick = onOfficialLink, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("GitHub release", color = colors.textMuted, fontSize = 11.sp)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = colors.textMuted,
                                    modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(state: UpdateProgressModel.State, accent: Color, accentDeep: Color) {
    val colors = AppTheme.colors
    val glow = rememberInfiniteTransition(label = "update-glow")
    val pulse by glow.animateFloat(0.82f, 1.08f,
        infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val glowAlpha by glow.animateFloat(0.35f, 0.7f,
        infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accentDeep.copy(alpha = 0.10f), Color.Transparent)))
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                // Soft animated halo behind the Spartan emblem; calmer once the package is verified.
                Box(Modifier.size(84.dp).scale(if (state.phase == Phase.READY) 1f else pulse)
                    .alpha(if (state.phase == Phase.READY) 0.55f else glowAlpha)
                    .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.9f), Color.Transparent)), CircleShape))
                Image(
                    painter = painterResource(R.drawable.img_maximus_emblem), contentDescription = "Maximus VPN",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(62.dp).clip(RoundedCornerShape(20.dp))
                        .border(1.5.dp, Brush.linearGradient(listOf(accent, accentDeep.copy(alpha = 0.4f))), RoundedCornerShape(20.dp))
                )
                if (state.phase == Phase.READY) {
                    Box(Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircleShape).background(colors.surfaceCard).padding(2.dp)) {
                        Icon(Icons.Default.Verified, contentDescription = "Verified", tint = colors.statusConnected, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("MAXIMUS VPN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 1.6.sp)
                Text(if (state.phase == Phase.READY) "Ready to install" else "Secure update",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null,
                        tint = if (state.phase == Phase.READY) colors.statusConnected else colors.textSecondary, modifier = Modifier.size(14.dp))
                    Text(if (state.phase == Phase.READY) "Signature and SHA-256 verified" else "Signed by the Maximus publisher",
                        fontSize = 12.sp, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun VersionChips(release: AppUpdateChecker.ReleaseInfo, installedBuild: Int, accent: Color) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Installed", if (installedBuild > 0) "Build $installedBuild" else "Local build",
                container = colors.surfacePill, content = colors.textSecondary, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Chip("New", "${release.version} · Build ${release.build}",
                container = accent.copy(alpha = 0.16f), content = accent, border = accent.copy(alpha = 0.45f), modifier = Modifier.weight(1.3f))
        }
        release.apk?.let {
            Text("Download size ${UpdateProgressModel.megabytes(it.size)} · from the official GitHub release",
                fontSize = 11.sp, color = colors.textMuted)
        }
    }
}

@Composable
private fun Chip(label: String, value: String, container: Color, content: Color, modifier: Modifier = Modifier, border: Color? = null) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = container,
        border = border?.let { BorderStroke(1.dp, it) }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label.uppercase(), fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold, color = content.copy(alpha = 0.75f))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WhatsNew(notes: List<String>, accent: Color) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) notes else notes.take(3)
    Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceElevated, border = BorderStroke(1.dp, colors.borderSubtle)) {
        Column(Modifier.fillMaxWidth().padding(14.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("What's new", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            shown.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(accent))
                    Text(line, fontSize = 12.sp, color = colors.textSecondary, lineHeight = 17.sp)
                }
            }
            if (notes.size > 3) {
                Text(if (expanded) "Show less" else "Show all ${notes.size}", fontSize = 12.sp, color = accent,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun StepIndicator(state: UpdateProgressModel.State, accent: Color) {
    val steps = listOf(Step.DOWNLOAD to "Download", Step.VERIFY to "Verify signature", Step.INSTALL to "Install")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { index, (step, label) ->
            val done = state.isDone(step)
            val active = state.activeStep == step && state.phase != Phase.IDLE && state.phase != Phase.FAILED
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepConnector(visible = index > 0, lit = done || active, accent = accent, modifier = Modifier.weight(1f))
                    StepDot(index + 1, done, active, state.phase == Phase.FAILED && step == Step.DOWNLOAD, state.busy && active, accent)
                    StepConnector(visible = index < steps.lastIndex, lit = done, accent = accent, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (active || done) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active || done) AppTheme.colors.textPrimary else AppTheme.colors.textMuted)
            }
        }
    }
}

@Composable
private fun StepConnector(visible: Boolean, lit: Boolean, accent: Color, modifier: Modifier) {
    Box(modifier.height(2.dp).clip(CircleShape).background(
        when { !visible -> Color.Transparent; lit -> accent.copy(alpha = 0.8f); else -> AppTheme.colors.borderMedium }))
}

@Composable
private fun StepDot(number: Int, done: Boolean, active: Boolean, failed: Boolean, spinning: Boolean, accent: Color) {
    val colors = AppTheme.colors
    val size: Dp = 30.dp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(CircleShape).background(
        when {
            failed -> colors.statusError.copy(alpha = 0.16f)
            done -> colors.statusConnected.copy(alpha = 0.18f)
            active -> accent.copy(alpha = 0.18f)
            else -> colors.surfacePill
        }).border(1.dp, when {
            failed -> colors.statusError
            done -> colors.statusConnected
            active -> accent
            else -> colors.borderMedium
        }, CircleShape)) {
        when {
            failed -> Icon(Icons.Default.ErrorOutline, contentDescription = "Failed", tint = colors.statusError, modifier = Modifier.size(17.dp))
            done -> Icon(Icons.Default.Check, contentDescription = "Done", tint = colors.statusConnected, modifier = Modifier.size(17.dp))
            spinning -> CircularProgressIndicator(Modifier.size(16.dp), color = accent, strokeWidth = 2.dp)
            else -> Text("$number", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) accent else colors.textMuted)
        }
    }
}

@Composable
private fun ProgressCard(state: UpdateProgressModel.State, rate: Double?, accent: Color, accentDeep: Color) {
    val colors = AppTheme.colors
    val animated by animateFloatAsState(state.percent / 100f, tween(450, easing = FastOutSlowInEasing), label = "update-progress")
    val shimmer = rememberInfiniteTransition(label = "update-shimmer")
    val offset by shimmer.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "shimmer")
    val verifying = state.activeStep == Step.VERIFY
    Surface(shape = RoundedCornerShape(18.dp), color = accent.copy(alpha = 0.07f), border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when (state.phase) {
                    Phase.CONNECTING -> "Connecting…"
                    Phase.CHECKING_DIGEST -> "Checking SHA-256…"
                    Phase.CHECKING_SIGNATURE -> "Verifying signature…"
                    else -> "${state.percent}%"
                }, fontSize = if (verifying || state.phase == Phase.CONNECTING) 16.sp else 26.sp,
                    fontWeight = FontWeight.Bold, color = colors.textPrimary)
                val received = state.receivedBytes
                val total = state.totalBytes
                if (received != null && total != null && !verifying) {
                    Text("${UpdateProgressModel.megabytes(received)} of ${UpdateProgressModel.megabytes(total)}",
                        fontSize = 12.sp, color = colors.textSecondary)
                }
            }
            // Thick gradient bar with a moving sheen; indeterminate sheen only while connecting/verifying.
            Box(
                Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f))
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(state.percent / 100f, 0f..1f) }
            ) {
                val fill = if (state.phase == Phase.CONNECTING) 0.12f + 0.2f * offset else if (verifying) 1f else animated
                Box(Modifier.fillMaxWidth(fill.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(accentDeep, accent, colors.primaryLight))))
                Box(Modifier.fillMaxWidth(fill.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape)
                    .background(Brush.horizontalGradient(
                        0f to Color.Transparent, (offset - 0.15f).coerceIn(0f, 1f) to Color.Transparent,
                        offset to Color.White.copy(alpha = 0.35f), (offset + 0.15f).coerceIn(0f, 1f) to Color.Transparent,
                        1f to Color.Transparent)))
            }
            val left = state.totalBytes?.let { t -> state.receivedBytes?.let { t - it } }
            val detail = when {
                verifying -> "The download is complete; the package is checked before Android sees it."
                state.phase == Phase.CONNECTING -> "Reaching the official GitHub release…"
                rate != null -> listOfNotNull(UpdateProgressModel.speed(rate), left?.let { UpdateProgressModel.eta(it, rate) }).joinToString(" · ")
                else -> "Measuring speed…"
            }
            Text(detail, fontSize = 12.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun StatusLine(state: UpdateProgressModel.State, release: AppUpdateChecker.ReleaseInfo, error: String?) {
    val colors = AppTheme.colors
    when {
        error != null && state.phase == Phase.FAILED -> Surface(shape = RoundedCornerShape(14.dp), color = colors.statusError.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, colors.statusError.copy(alpha = 0.35f))) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = colors.statusError, modifier = Modifier.size(18.dp))
                Text(error, fontSize = 12.sp, color = colors.textPrimary)
            }
        }
        error != null -> Text(error, fontSize = 12.sp, color = colors.statusError)
        release.apk == null -> Text("Verified APK metadata is unavailable. Use the official GitHub release.",
            fontSize = 12.sp, color = colors.statusWarning)
        state.phase == Phase.READY -> Text("SHA-256 and the installed publisher's signing certificate match. Android will ask you to approve the installation.",
            fontSize = 12.sp, color = colors.textSecondary)
        state.phase == Phase.IDLE -> Text("The APK is checked against the official SHA-256 digest and your installed app's signing certificate before installation.",
            fontSize = 12.sp, color = colors.textSecondary)
        else -> Unit
    }
}

@Composable
private fun PrimaryButton(
    state: UpdateProgressModel.State, release: AppUpdateChecker.ReleaseInfo,
    accent: Color, accentDeep: Color, onPrimary: () -> Unit
) {
    val colors = AppTheme.colors
    // While a download or check runs, the button *is* the progress: it stays in brand colour and fills up
    // instead of sitting greyed out as a disabled "Download securely".
    val busy = state.busy
    val ready = state.phase == Phase.READY
    val (icon: ImageVector, label: String) = when {
        ready -> Icons.Default.InstallMobile to "Install now"
        release.apk == null -> Icons.AutoMirrored.Filled.OpenInNew to "View official release"
        busy && state.activeStep == Step.VERIFY -> Icons.Default.VerifiedUser to "Verifying…"
        busy -> Icons.Default.CloudDownload to (if (state.phase == Phase.CONNECTING) "Connecting…" else "Downloading… ${state.percent}%")
        state.phase == Phase.FAILED -> Icons.Default.Refresh to "Retry download"
        else -> Icons.Default.CloudDownload to (release.apk?.size?.let { "Download securely · ${UpdateProgressModel.megabytes(it)}" }
            ?: "Download securely")
    }
    val gradient = if (ready) Brush.horizontalGradient(listOf(colors.statusConnected.copy(alpha = 0.95f), accent))
        else Brush.horizontalGradient(listOf(accentDeep, accent))
    val fill by animateFloatAsState(if (busy) (if (state.activeStep == Step.VERIFY) 1f else state.percent / 100f) else 1f,
        tween(450), label = "button-fill")
    Box(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp))
            .background(if (busy) accent.copy(alpha = 0.22f) else Color.Transparent)
            .then(if (busy) Modifier else Modifier.clickable(onClick = onPrimary))
            .semantics { if (busy) progressBarRangeInfo = ProgressBarRangeInfo(state.percent / 100f, 0f..1f) }
            .testTag("update_primary"),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(fill.coerceIn(0f, 1f))
            .background(gradient, RoundedCornerShape(16.dp)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (busy && state.activeStep == Step.VERIFY) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
