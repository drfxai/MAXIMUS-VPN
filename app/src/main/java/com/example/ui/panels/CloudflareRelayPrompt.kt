package com.example.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AppTheme

/**
 * Build 181 Phase 7: "Get a free private relay", a bottom sheet in the style of "Connect without a config". Shown once
 * after the first verified connection when the user has no server of their own and no relay
 * ([com.example.panels.cloudflare.relay.CloudflareRelay.shouldPrompt]); opened again from Panels → Cloudflare.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudflareRelayPromptSheet(onConnect: () -> Unit, onLater: () -> Unit) {
    val c = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onLater,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        scrimColor = Color.Black.copy(alpha = if (c.isDark) 0.6f else 0.35f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Cf.Orange.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Cloud, contentDescription = null, tint = Cf.Orange, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onLater, modifier = Modifier.size(44.dp).clip(CircleShape).border(1.dp, c.borderSubtle, CircleShape)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ui_close), tint = c.textPrimary, modifier = Modifier.size(20.dp))
                }
            }
            Text(stringResource(R.string.ui_cfr_prompt_title), color = c.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp)
            Text(stringResource(R.string.ui_cfr_prompt_body), color = c.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            Benefit(Icons.Filled.Dns, stringResource(R.string.ui_cfr_benefit1_t), stringResource(R.string.ui_cfr_benefit1_d))
            Benefit(Icons.Filled.Shield, stringResource(R.string.ui_cfr_benefit2_t), stringResource(R.string.ui_cfr_benefit2_d))
            Benefit(Icons.Filled.Schedule, stringResource(R.string.ui_cfr_benefit3_t), stringResource(R.string.ui_cfr_benefit3_d))
            Spacer(Modifier.height(4.dp))
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)) {
                Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_cfr_connect), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ui_cfr_later), color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_cfr_tip), color = c.textMuted, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun Benefit(icon: ImageVector, title: String, detail: String) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceCard), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = c.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
