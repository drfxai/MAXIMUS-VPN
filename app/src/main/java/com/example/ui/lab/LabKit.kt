package com.example.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText

/*
 * A small, calm component set for the LAB and AI settings screens: flat panels with hairline borders, compact
 * rows, thin meters and small badges. No gradients, no boxes inside boxes.
 */

internal val Gutter = 16.dp

/** A flat panel: one surface, a hairline border, 14 dp corners. */
@Composable
internal fun Panel(c: LabColors, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = Gutter).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)
            .border(1.dp, c.stroke, RoundedCornerShape(14.dp)),
        content = content
    )
}

@Composable
internal fun Hairline(c: LabColors, start: Dp = 0.dp) = Box(Modifier.padding(start = start).fillMaxWidth().height(1.dp).background(c.divider))

/** Small uppercase label above a group. */
@Composable
internal fun GroupLabel(c: LabColors, text: String, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = Gutter + 4.dp, end = Gutter + 4.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(text.uppercase(), c.text3, 11.sp, FontWeight.SemiBold, Modifier.weight(1f), letterSpacing = 0.8.sp, maxLines = 1)
        trailing?.let { LabText(it, c.accent, 12.sp, FontWeight.SemiBold, Modifier.clickable(onClick = onTrailing), maxLines = 1) }
    }
}

/** A small rectangular status badge. */
@Composable
internal fun Badge(text: String, color: Color) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        LabText(text, color, 11.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** A thin horizontal meter, 0..1. */
@Composable
internal fun Meter(c: LabColors, value: Float?, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.cardAlt)) {
        if (value != null && value > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

/** One figure in a metric strip: small label, value, optional meter. */
@Composable
internal fun RowScope.Metric(c: LabColors, label: String, value: String, meter: Float? = null, meterColor: Color = c.accent) {
    Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
        LabText(label, c.text3, 11.5.sp, FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        LabText(value, c.text, 17.sp, FontWeight.SemiBold, maxLines = 1)
        if (meter != null) {
            Spacer(Modifier.height(7.dp))
            Meter(c, meter, meterColor, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun VDivider(c: LabColors) = Box(Modifier.padding(vertical = 12.dp).width(1.dp).height(44.dp).background(c.divider))

/** A label/value line inside a panel. */
@Composable
internal fun KeyValue(c: LabColors, key: String, value: String, valueColor: Color? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(key, c.text2, 13.5.sp, modifier = Modifier.weight(1f), maxLines = 1)
        if (trailing != null) trailing() else LabText(value, valueColor ?: c.text, 13.5.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

/** A navigable row: icon, title, optional detail and trailing text, chevron. */
@Composable
internal fun NavRow(c: LabColors, icon: ImageVector, title: String, detail: String? = null, trailing: String? = null, trailingColor: Color? = null, chevron: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.text2, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            LabText(title, c.text, 14.5.sp, FontWeight.Medium, maxLines = 1)
            detail?.let { LabText(it, c.text3, 12.sp, maxLines = 1) }
        }
        trailing?.let { LabText(it, trailingColor ?: c.text3, 12.5.sp, FontWeight.Medium, maxLines = 1) }
        chevron?.let { Icon(it, null, tint = c.text3, modifier = Modifier.padding(start = 4.dp).size(18.dp)) }
    }
}

/** Solid accent button, compact. */
@Composable
internal fun PrimaryButton(c: LabColors, text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) c.accent else c.cardAlt).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = if (enabled) c.onAccent else c.text3, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)) }
        LabText(text, if (enabled) c.onAccent else c.text3, 14.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

/** Outlined neutral button, compact. */
@Composable
internal fun SecondaryButton(c: LabColors, text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    val tint = if (!enabled) c.text3 else color ?: c.text
    Row(modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, if (color != null && enabled) color.copy(alpha = 0.35f) else c.stroke, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        LabText(text, tint, 13.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

/** Text-only action used in a row of actions under an item. */
@Composable
internal fun TextAction(c: LabColors, text: String, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    LabText(text, if (enabled) color ?: c.accent else c.text3.copy(alpha = 0.6f), 13.sp, FontWeight.SemiBold,
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp), maxLines = 1)
}

/** A compact segmented control. */
@Composable
internal fun <T> Segmented(c: LabColors, items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.cardAlt).padding(3.dp)) {
        items.forEach { item ->
            val on = item == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) c.card else Color.Transparent)
                .then(if (on) Modifier.border(1.dp, c.stroke, RoundedCornerShape(8.dp)) else Modifier)
                .clickable { onSelect(item) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                LabText(label(item), if (on) c.text else c.text2, 12.5.sp, if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Screen title bar: optional back arrow, title, subtitle, optional trailing action. */
@Composable
internal fun TitleBar(c: LabColors, title: String, subtitle: String?, back: ImageVector?, onBack: () -> Unit = {}, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = if (back != null) 6.dp else Gutter + 4.dp, end = Gutter, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) {
            Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) { Icon(back, "Back", tint = c.text) }
            Spacer(Modifier.width(2.dp))
        }
        Column(Modifier.weight(1f)) {
            LabText(title, c.text, 21.sp, FontWeight.Bold, letterSpacing = (-0.3).sp, maxLines = 1)
            subtitle?.let { LabText(it, c.text2, 12.5.sp, maxLines = 1) }
        }
        trailing?.invoke()
    }
}

/** A quiet note under a group. */
@Composable
internal fun Footnote(c: LabColors, text: String, inPanel: Boolean = false) =
    LabText(text, c.text3, 11.5.sp, modifier = Modifier.padding(horizontal = if (inPanel) 14.dp else Gutter + 4.dp, vertical = 10.dp), maxLines = 5, lineHeight = 16.sp)
