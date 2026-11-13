package com.example.ui.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpn.safety.MaximusVpnSupervisor.Lockdown
import com.example.ui.theme.AppTheme

/**
 * When to suggest Android's own leak protection. Inside the tunnel Maximus already captures every IPv4 and
 * IPv6 route and serves DNS itself, so nothing leaks while it runs; only Android's "Block connections without
 * VPN" also covers the seconds when Android restarts the app or the tunnel reconnects.
 */
object LeakProtectionAdvice {
    const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

    fun shouldShow(lockdown: Lockdown, connected: Boolean, snoozedUntil: Long, now: Long): Boolean =
        connected && (lockdown == Lockdown.OFF || lockdown == Lockdown.ALWAYS_ON_ONLY) && now >= snoozedUntil

    fun message(lockdown: Lockdown): String = when (lockdown) {
        Lockdown.ALWAYS_ON_ONLY -> "Always-on is set, but traffic still goes direct while the tunnel restarts. " +
            "Turn on \u201cBlock connections without VPN\u201d for Maximus."
        else -> "If Android stops Maximus or the network switches, apps could briefly reach the internet without the VPN. " +
            "Turn on Always-on VPN and \u201cBlock connections without VPN\u201d for Maximus."
    }
}

private fun prefs(context: Context) = context.getSharedPreferences("leak_protection", Context.MODE_PRIVATE)

@Composable
fun LeakProtectionCard(connected: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lockdown by com.example.vpn.RayVpnService.lockdown.collectAsState()
    var snoozedUntil by remember { mutableStateOf(prefs(context).getLong("snoozed_until", 0L)) }
    if (!LeakProtectionAdvice.shouldShow(lockdown, connected, snoozedUntil, System.currentTimeMillis())) return
    val colors = AppTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp).testTag("leak_protection_card"),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(colors.primary.copy(alpha = 0.55f), colors.statusWarning.copy(alpha = 0.35f))))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(colors.primaryDark, colors.primary))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(18.dp))
                }
                Text("Close the last leak", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            }
            Text(LeakProtectionAdvice.message(lockdown), fontSize = 12.sp, color = colors.textSecondary, lineHeight = 17.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val until = System.currentTimeMillis() + LeakProtectionAdvice.SNOOZE_MS
                    prefs(context).edit().putLong("snoozed_until", until).apply()
                    snoozedUntil = until
                }) { Text("Not now", color = colors.textMuted, fontSize = 12.sp) }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Open VPN settings", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
