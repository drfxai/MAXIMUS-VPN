package com.example.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.CompactActionPill
import com.example.ui.components.CompactCard
import com.example.ui.components.CompactCardDefaults
import com.example.ui.components.CompactIconTile
import com.example.ui.components.CompactRow
import com.example.ui.components.LatencyPill
import com.example.ui.theme.AppTheme

private val GodModeRed = Color(0xFFFF5252)

/** Brand row: logo, name, version and the three guarantees on one line. Tap opens the architecture tour. */
@Composable
fun CompactHeroCard(
    logo: Painter,
    versionName: String,
    onExploreArchitecture: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompactCard(
        modifier = modifier.testTag("landing_hero_banner"),
        onClick = onExploreArchitecture,
        borderColor = AppTheme.colors.primary.copy(alpha = 0.35f)
    ) {
        CompactRow(
            title = "Maximus VPN",
            subtitle = "Zero Logs • REALITY • No DNS Leaks",
            leading = {
                Image(
                    painter = logo,
                    contentDescription = "Maximus Spartan VPN Emblem",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(CompactCardDefaults.TileSize)
                        .clip(CompactCardDefaults.TileShape)
                        .border(1.dp, AppTheme.colors.primary.copy(alpha = 0.6f), CompactCardDefaults.TileShape)
                )
            },
            titleBadge = {
                Text(
                    text = "v$versionName PRO",
                    color = AppTheme.colors.primary,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(AppTheme.colors.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp)
                )
            }
        ) {
            CompactActionPill(
                text = "Tour",
                icon = Icons.Default.Info,
                onClick = onExploreArchitecture,
                modifier = Modifier.testTag("explore_features_button")
            )
        }
    }
}

/** Daily / GOD mode row. GOD-mode extras (failover ladder, tools) go in [extraContent]. */
@Composable
fun CompactModeCard(
    title: String,
    subtitle: String,
    isGodMode: Boolean,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier,
    extraContent: @Composable ColumnScope.() -> Unit = {}
) {
    val accent = if (isGodMode) GodModeRed else AppTheme.colors.primary
    CompactCard(
        modifier = modifier,
        borderColor = if (isGodMode) GodModeRed.copy(alpha = 0.45f) else AppTheme.colors.borderSubtle,
        containerColor = when {
            !isGodMode -> AppTheme.colors.surfaceCard
            AppTheme.colors.isDark -> Color(0xFF2E0F16)
            else -> Color(0xFFFDEDED)
        }
    ) {
        CompactRow(
            title = title,
            subtitle = subtitle,
            leading = { CompactIconTile(icon = if (isGodMode) Icons.Default.Shield else Icons.Default.Bolt, tint = accent) }
        ) {
            CompactActionPill(
                text = if (isGodMode) "Daily Mode" else "GOD Mode",
                accent = accent,
                onClick = onToggleMode
            )
        }
        extraContent()
    }
}

/** Selected node: flag or globe, name, host, latency. */
@Composable
fun CompactServerCard(
    name: String,
    subtitle: String,
    flag: String?,
    pingMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompactCard(modifier = modifier.testTag("selected_server_card"), onClick = onClick) {
        CompactRow(
            title = name,
            subtitle = subtitle,
            leading = {
                if (flag != null) {
                    Box(
                        modifier = Modifier
                            .size(CompactCardDefaults.TileSize)
                            .clip(CompactCardDefaults.TileShape)
                            .background(AppTheme.colors.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = flag, fontSize = 16.sp)
                    }
                } else {
                    CompactIconTile(icon = Icons.Default.Public, tint = AppTheme.colors.primary, contentDescription = "Server")
                }
            }
        ) {
            LatencyPill(latencyMs = pingMs)
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Change Server",
                tint = AppTheme.colors.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Download, upload and tunnel in three equal cells on one row. */
@Composable
fun CompactTrafficCard(
    downloadSpeed: String,
    downloadTotal: String,
    uploadSpeed: String,
    uploadTotal: String,
    protocol: String,
    tunnelIp: String,
    modifier: Modifier = Modifier
) {
    CompactCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TrafficCell(AppTheme.colors.metricDownload, downloadSpeed, "↓ $downloadTotal", Modifier.weight(1f))
            CellDivider()
            TrafficCell(AppTheme.colors.metricUpload, uploadSpeed, "↑ $uploadTotal", Modifier.weight(1f))
            CellDivider()
            TrafficCell(AppTheme.colors.primary, protocol, tunnelIp, Modifier.weight(1f), monospace = false)
        }
    }
}

@Composable
private fun TrafficCell(
    tint: Color,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = true
) {
    Column(modifier = modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = value,
                color = AppTheme.colors.textPrimary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = caption,
            color = AppTheme.colors.textMuted,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CellDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(AppTheme.colors.borderSubtle)
    )
}
