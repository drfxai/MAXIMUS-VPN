package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppTheme

/**
 * One shared shape, padding and type scale for the small info cards (home, nodes, AI setup),
 * so they line up as a set instead of each card picking its own size.
 */
object CompactCardDefaults {
    val Shape = RoundedCornerShape(14.dp)
    val ContentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
    val MinHeight = 52.dp
    val TileSize = 30.dp
    val TileShape = RoundedCornerShape(9.dp)
    val ControlHeight = 28.dp
    val ControlShape = RoundedCornerShape(8.dp)
    val Gap = 8.dp
}

@Composable
fun CompactCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = AppTheme.colors.borderSubtle,
    containerColor: Color = AppTheme.colors.surfaceCard,
    content: @Composable ColumnScope.() -> Unit
) {
    val clickModifier = if (onClick != null) {
        Modifier.clip(CompactCardDefaults.Shape).clickable(onClick = onClick)
    } else Modifier
    Surface(
        modifier = modifier.fillMaxWidth().then(clickModifier),
        shape = CompactCardDefaults.Shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = CompactCardDefaults.MinHeight)
                .padding(CompactCardDefaults.ContentPadding),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            content = content
        )
    }
}

/** Leading tile + title/subtitle + trailing controls: the standard compact row. */
@Composable
fun CompactRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    titleBadge: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = AppTheme.colors.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (titleBadge != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    titleBadge()
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = AppTheme.colors.textMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        trailing()
    }
}

@Composable
fun CompactIconTile(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(CompactCardDefaults.TileSize)
            .clip(CompactCardDefaults.TileShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** Small trailing action. Filled for the primary call to action, tonal otherwise. */
@Composable
fun CompactActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = AppTheme.colors.primary,
    filled: Boolean = false,
    enabled: Boolean = true
) {
    // clickable instead of Surface(onClick): the latter pads itself to a 48dp touch target
    // and would make every compact row taller than the card's 52dp.
    Surface(
        modifier = modifier
            .height(CompactCardDefaults.ControlHeight)
            .clip(CompactCardDefaults.ControlShape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CompactCardDefaults.ControlShape,
        color = if (filled) accent else accent.copy(alpha = 0.12f),
        border = if (filled) null else BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val contentColor = if (filled) AppTheme.colors.onPrimary else accent
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(text = text, color = contentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** 34x20 switch sized to the compact rows; the Material switch is 52x32 plus touch padding. */
@Composable
fun CompactSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier
) {
    val track by animateColorAsState(
        if (checked) AppTheme.colors.primary else AppTheme.colors.surfaceElevated,
        label = "compact_switch_track"
    )
    val thumbOffset by animateDpAsState(if (checked) 16.dp else 2.dp, label = "compact_switch_thumb")
    Box(
        modifier = modifier
            .size(width = 34.dp, height = 20.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, if (checked) track else AppTheme.colors.borderMedium, CircleShape),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(16.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else AppTheme.colors.textMuted)
        )
    }
}
