package com.example.ui.popups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.update.AppUpdateChecker
import com.example.ui.theme.AppTheme

/** Shared verified-update experience for launch and Settings; no fake progress or silent install. */
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
    val accent = AppTheme.colors.primary
    val percent = progress.coerceIn(0, 100)
    val ready = verified && !downloading
    val message = when {
        ready -> "Verified and ready for Android installation"
        error != null -> "Download interrupted"
        downloading && percent == 0 -> "Connecting to the official GitHub release…"
        downloading -> "Downloading and verifying the signed package…"
        else -> "Signed update available"
    }
    Dialog(onDismissRequest = { onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 440.dp),
            shape = RoundedCornerShape(28.dp),
            color = AppTheme.colors.surfaceCard,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(17.dp))
                            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.42f)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Column {
                        Text("MAXIMUS VPN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 1.4.sp)
                        Text("Secure update", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                    }
                }
                Text("Version ${release.version} • Build ${release.build}",
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary)
                Text("Installed build $installedBuild  →  Release build ${release.build}",
                    fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = accent.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.15f))
                ) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ready) Icons.Default.CheckCircle else if (error != null) Icons.Default.ErrorOutline
                            else Icons.Default.Security, contentDescription = null,
                            tint = if (error == null) accent else AppTheme.colors.statusError, modifier = Modifier.size(20.dp))
                        Text(message, fontSize = 12.sp, color = AppTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f))
                    }
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                        color = accent, trackColor = accent.copy(alpha = 0.16f)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(downloadStatus ?: if (percent == 0) "Waiting for the first bytes…" else "$percent% downloaded",
                            fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                        Text(if (percent == 0) "Connecting" else "$percent%",
                            fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
                    }
                }
                if (ready) {
                    Text("SHA-256 and the installed publisher's signing certificate match. Android will ask for installation approval.",
                        fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                } else if (!downloading) {
                    Text("The APK is checked against the official SHA-256 digest and your installed app's signing certificate before installation.",
                        fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                }
                if (error != null) Text(error, color = AppTheme.colors.statusError, fontSize = 12.sp)
                if (release.apk == null) Text("Verified APK metadata is unavailable. Use the official GitHub release.",
                    fontSize = 12.sp, color = AppTheme.colors.statusWarning)
                Button(
                    onClick = onPrimary,
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Icon(if (ready) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                        contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(if (ready) "Install verified APK" else if (release.apk == null) "View official release"
                        else if (error != null) "Retry download" else "Download securely",
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text(if (downloading) "Cancel download" else "Later", color = AppTheme.colors.textSecondary)
                    }
                    TextButton(onClick = onOfficialLink) {
                        Text("GitHub release", color = accent, fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}
