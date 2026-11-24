package com.example.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.ui.theme.AppTheme

/*
 * Building blocks for the Aether WARP and Serverless Direct workspaces (redesign v4). They draw only with the
 * existing Maximus tokens: primaryContainer for the selected state, the status colours for levels.
 */

/** "Transport  Auto" inside the hero card: a quiet label and a bold value. */
@Composable
internal fun HeroChip(c: LabColors, label: String, value: String? = null) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(AppTheme.colors.background.copy(alpha = 0.35f))
        .border(1.dp, c.stroke, shape).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LabText(label, c.text, 12.sp, FontWeight.Medium, maxLines = 1)
        if (value != null) {
            Spacer(Modifier.width(5.dp))
            LabText(value, c.text, 12.sp, FontWeight.Bold, maxLines = 1)
        }
    }
}

/** A selectable chip: filled with primaryContainer and a check when selected. */
@Composable
internal fun SelectChip(c: LabColors, text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.clip(shape)
        .background(if (selected) colors.primaryContainer else Color.Transparent)
        .border(1.dp, if (selected) colors.primaryContainer else colors.borderMedium, shape)
        .clickable(enabled = enabled, onClick = onClick)
        .alpha(if (enabled || selected) 1f else 0.55f)
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (selected) {
            Icon(Icons.Rounded.Check, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        LabText(text, if (selected) colors.onPrimaryContainer else c.text, 13.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChipGroup(c: LabColors, items: List<T>, selected: T, enabled: Boolean, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item -> SelectChip(c, label(item), item == selected, enabled) { onSelect(item) } }
    }
}

/** An in-palette segmented control; the selected segment is filled with primaryContainer. */
@Composable
internal fun <T> PillSegmented(
    c: LabColors, items: List<T>, selected: T, enabled: Boolean, label: (T) -> String,
    modifier: Modifier = Modifier, trailing: ((T) -> String?)? = null, onSelect: (T) -> Unit
) {
    val colors = AppTheme.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bg.copy(alpha = 0.4f))
        .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp)).padding(4.dp)) {
        items.forEach { item ->
            val on = item == selected
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .background(if (on) colors.primaryContainer else Color.Transparent)
                .clickable(enabled = enabled && !on) { onSelect(item) }
                .alpha(if (enabled || on) 1f else 0.55f)
                .heightIn(min = 36.dp).padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                LabText(label(item), if (on) colors.onPrimaryContainer else c.text2, 13.sp,
                    if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                trailing?.invoke(item)?.let {
                    Spacer(Modifier.width(5.dp))
                    LabText(it, if (on) colors.onPrimaryContainer.copy(alpha = 0.7f) else c.text3, 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/** A titled block inside a panel: title, optional detail, then its control. */
@Composable
internal fun PanelBlock(c: LabColors, title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabText(title, c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
        content()
        note?.let { LabText(it, c.text3, 12.sp, maxLines = 4, lineHeight = 16.sp) }
    }
}

/** A small uppercase label inside a panel ("API", "TLS fragment"). */
@Composable
internal fun SubLabel(c: LabColors, text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(text, c.text2, 12.5.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
        trailing?.invoke()
    }
}

/** Section label with an optional icon action on the right ("LOG   Copy"). */
@Composable
internal fun SectionHeader(c: LabColors, text: String, actionIcon: ImageVector? = null, action: String? = null,
                           enabled: Boolean = true, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = Gutter + 4.dp, end = Gutter, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LabText(text.uppercase(), c.text3, 11.sp, FontWeight.SemiBold, Modifier.weight(1f), letterSpacing = 0.8.sp, maxLines = 1)
        if (action != null) {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onAction)
                .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val tint = if (enabled) c.accent else c.text3
                actionIcon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)) }
                LabText(action, tint, 12.5.sp, FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** A collapsible settings card that shows its current values in [summary] while closed. */
@Composable
internal fun CollapsibleSection(
    c: LabColors, icon: ImageVector, title: String, summary: @Composable () -> Unit,
    expanded: Boolean, onToggle: () -> Unit, tag: String,
    badge: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(shape).background(c.card)
        .border(1.dp, if (expanded) colors.borderMedium else c.stroke, shape).testTag(tag)) {
        // Not clickable(): that would merge the summary into one node, and UI automation finds "Outbound" by its own text.
        val toggle by rememberUpdatedState(onToggle)
        Row(Modifier.fillMaxWidth()
            .semantics { role = Role.Button; onClick { toggle(); true } }
            .pointerInput(Unit) { detectTapGestures { toggle() } }
            .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(colors.surfacePill), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.text2, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                LabText(title, c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                summary()
            }
            badge?.let { Spacer(Modifier.width(8.dp)); it() }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.text2,
                modifier = Modifier.padding(start = 6.dp).size(20.dp))
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), content = content)
    }
}

/** Summary text under a section title: plain parts with bold values, e.g. "Psiphon **Off** · Tor **Off**". */
@Composable
internal fun SummaryText(c: LabColors, parts: List<Pair<String, String?>>) {
    val text = buildAnnotatedString {
        parts.forEachIndexed { i, (plain, bold) ->
            if (i > 0) append(" · ")
            append(plain)
            if (bold != null) {
                append(" ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(bold) }
            }
        }
    }
    Text(text, color = c.text2, fontSize = 12.5.sp, lineHeight = 16.sp, maxLines = 3)
}

/** A compact rounded status chip with a dot ("● 3/6", "● Pending"). */
@Composable
internal fun StatusChip(text: String, color: Color, background: Color = color.copy(alpha = 0.14f)) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A label on the left and the current value with a dropdown on the right, for grouped option lists. */
@Composable
internal fun InlineDropdownRow(c: LabColors, title: String, selected: String, options: List<String>, enabled: Boolean,
                               label: (String) -> String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = true }
            .padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            LabText(title, if (enabled) c.text else c.text3, 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
            LabText(label(selected), if (enabled) c.text else c.text3, 14.sp, FontWeight.SemiBold,
                Modifier.widthIn(max = 170.dp), maxLines = 1)
            Icon(Icons.Rounded.ExpandMore, null, tint = c.text2, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.align(Alignment.CenterEnd)) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

/** A bordered inner group inside a panel (the grouped API dropdowns). */
@Composable
internal fun InnerGroup(c: LabColors, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(shape).border(1.dp, AppTheme.colors.borderMedium, shape), content = content)
}

/** Two status tiles: the last run and the issue counts, both read from the real log and checks. */
@Composable
internal fun StatusTiles(c: LabColors, summary: BuiltinLog.Summary, checksPassed: Int) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(c, Modifier.weight(1f), stringResource(R.string.bc_last_run)) {
            LabText(summary.lastTime ?: "—", c.text, 18.sp, FontWeight.Bold, maxLines = 1)
            LabText(stringResource(R.string.bc_events_checks, summary.lines.size, checksPassed), c.text2, 12.sp, maxLines = 1)
        }
        Tile(c, Modifier.weight(1f), stringResource(R.string.bc_issues)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(stringResource(R.string.bc_warn_count, summary.warn), if (summary.warn > 0) colors.statusWarning else c.text3)
                StatusChip(stringResource(R.string.bc_error_count, summary.error), if (summary.error > 0) colors.statusError else c.text3)
            }
        }
    }
}

@Composable
private fun Tile(c: LabColors, modifier: Modifier, label: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(c.card).border(1.dp, c.stroke, shape).heightIn(min = 84.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabText(label.uppercase(), c.text2, 11.5.sp, FontWeight.SemiBold, letterSpacing = 0.4.sp, maxLines = 1)
        content()
    }
}

private enum class LogFilter { ALL, INFO, WARN, ERR }

/** How many lines the log shows before "Show all". */
internal const val LOG_COLLAPSED_LINES = 4

/**
 * The monospace log: filter chips with counts, a colour per level, the latest lines while collapsed with
 * "Show all N lines", and an expand icon. [empty] is shown when nothing was recorded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogViewer(c: LabColors, summary: BuiltinLog.Summary, empty: String) {
    val colors = AppTheme.colors
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(shape).background(colors.consoleBackground)
        .border(1.dp, c.stroke, shape).testTag("builtin_log")) {
        if (summary.lines.isEmpty()) {
            Text(empty, Modifier.padding(14.dp), color = c.text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill(stringResource(R.string.bc_filter_all, summary.lines.size), filter == LogFilter.ALL, c.text) { filter = LogFilter.ALL }
                FilterPill(stringResource(R.string.bc_filter_info, summary.info), filter == LogFilter.INFO, c.text) { filter = LogFilter.INFO }
                FilterPill(stringResource(R.string.bc_filter_warn, summary.warn), filter == LogFilter.WARN, colors.statusWarning) { filter = LogFilter.WARN }
                FilterPill(stringResource(R.string.bc_filter_error, summary.error), filter == LogFilter.ERR, colors.statusError) { filter = LogFilter.ERR }
            }
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { expanded = !expanded }, contentAlignment = Alignment.Center) {
                Icon(if (expanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    stringResource(if (expanded) R.string.bc_show_fewer else R.string.bc_expand_log), tint = c.text2, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.stroke))
        val shown = summary.lines.filter {
            when (filter) {
                LogFilter.ALL -> true
                LogFilter.INFO -> it.level == BuiltinLog.Level.INFO || it.level == BuiltinLog.Level.OK
                LogFilter.WARN -> it.level == BuiltinLog.Level.WARN
                LogFilter.ERR -> it.level == BuiltinLog.Level.ERR
            }
        }
        val visible = if (expanded) shown else shown.takeLast(LOG_COLLAPSED_LINES)
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (visible.isEmpty()) Text("—", color = c.text3, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            visible.forEach { line -> LogLineRow(c, line) }
        }
        if (shown.size > LOG_COLLAPSED_LINES) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.stroke))
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                LabText(if (expanded) stringResource(R.string.bc_show_fewer) else stringResource(R.string.bc_show_all_lines, shown.size),
                    c.accent, 13.sp, FontWeight.SemiBold, maxLines = 1)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.accent,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp))
            }
        }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, tint: Color, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.clip(shape).background(if (selected) colors.primaryContainer else Color.Transparent)
        .border(1.dp, if (selected) colors.primaryContainer else colors.borderMedium, shape)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, color = if (selected) colors.onPrimaryContainer else tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun LogLineRow(c: LabColors, line: BuiltinLog.Line) {
    val colors = AppTheme.colors
    val (tag, tint) = when (line.level) {
        BuiltinLog.Level.INFO -> "INFO" to colors.metricDownload
        BuiltinLog.Level.OK -> "OK" to colors.statusConnected
        BuiltinLog.Level.WARN -> "WARN" to colors.statusWarning
        BuiltinLog.Level.ERR -> "ERR" to colors.statusError
    }
    Row(Modifier.fillMaxWidth()) {
        if (line.time.isNotEmpty()) {
            Text(line.time, color = c.text3, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        Text(tag, Modifier.width(34.dp), color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(line.text, Modifier.weight(1f), color = colors.textLight, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
    }
}

/** One access check with its status chip. */
@Composable
internal fun CheckRow(c: LabColors, title: String, value: Boolean?) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(title, c.text, 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
        when (value) {
            true -> StatusChip(stringResource(R.string.bc_passed), colors.statusConnected)
            false -> StatusChip(stringResource(R.string.bc_failed), colors.statusError)
            null -> StatusChip(stringResource(R.string.bc_pending), c.text2, colors.surfacePill)
        }
    }
}
