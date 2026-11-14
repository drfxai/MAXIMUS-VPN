package com.example.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppTheme

/*
 * The Settings design system: one family of rounded groups, rows, chips and labels so every
 * Settings page reads the same. Spacing is 16 dp, groups use a 20 dp corner, and the theme's
 * primary colour is the only accent.
 */

internal val SettingsGroupShape = RoundedCornerShape(20.dp)
internal val SettingsGutter = 16.dp

/** A page body: scrollable column with the standard gutter and section rhythm. */
@Composable
internal fun SettingsPageColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsGutter)
            .padding(bottom = 32.dp),
        content = content
    )
}

/** Top bar for a Settings sub-page: back arrow (mirrored in RTL) and a calm title. */
@Composable
internal fun SettingsTopBar(title: String, subtitle: String? = null, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = AppTheme.colors.textPrimary
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = AppTheme.colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, color = AppTheme.colors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Small sentence-case label above a group. */
@Composable
internal fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier, first: Boolean = false) {
    Text(
        text = text,
        color = AppTheme.colors.textSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = if (first) 4.dp else 24.dp, bottom = 8.dp)
    )
}

/** Muted helper text under a group. */
@Composable
internal fun SettingsFootnote(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.textMuted) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)
    )
}

/** A rounded surface that holds rows. Borderless in dark; a hairline only in light for separation. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SettingsGroupShape,
        color = AppTheme.colors.surfaceCard,
        border = if (AppTheme.colors.isDark) null else BorderStroke(1.dp, AppTheme.colors.borderSubtle)
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** Hairline divider inset to the text column so the icon column stays clean. */
@Composable
internal fun SettingsDivider(inset: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (inset) 68.dp else 16.dp, end = 16.dp)
            .height(1.dp)
            .background(AppTheme.colors.borderSubtle.copy(alpha = if (AppTheme.colors.isDark) 0.7f else 1f))
    )
}

/** Icon in a tinted rounded square. */
@Composable
internal fun SettingsIconTile(icon: ImageVector, tint: Color = AppTheme.colors.primary, size: Int = 36) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = if (AppTheme.colors.isDark) 0.14f else 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
private fun RowText(title: String, subtitle: String?, titleColor: Color, modifier: Modifier) {
    Column(modifier) {
        Text(
            title,
            color = titleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = AppTheme.colors.textMuted,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SettingsChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = AppTheme.colors.textMuted,
        modifier = Modifier.size(22.dp)
    )
}

/** A tappable row: leading icon tile, title, one-line value/subtitle, trailing chevron (or custom). */
@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    iconTint: Color = AppTheme.colors.primary,
    titleColor: Color = AppTheme.colors.textPrimary,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = { SettingsChevron() },
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(16.dp)) }
            icon != null -> { SettingsIconTile(icon, iconTint); Spacer(Modifier.width(16.dp)) }
        }
        RowText(title, subtitle, if (enabled) titleColor else AppTheme.colors.textMuted, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** A row with a switch; the whole row toggles. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            SettingsIconTile(icon)
            Spacer(Modifier.width(16.dp))
        }
        RowText(title, subtitle, AppTheme.colors.textPrimary, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppTheme.colors.onPrimary,
                checkedTrackColor = AppTheme.colors.primary,
                checkedBorderColor = AppTheme.colors.primary,
                uncheckedThumbColor = AppTheme.colors.textMuted,
                uncheckedTrackColor = AppTheme.colors.surfaceElevated,
                uncheckedBorderColor = AppTheme.colors.borderMedium
            )
        )
    }
}

/** One choice in a single-choice list: title, short description and a trailing check. */
@Composable
internal fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            SettingsIconTile(icon, if (selected) AppTheme.colors.primary else AppTheme.colors.textSecondary)
            Spacer(Modifier.width(16.dp))
        }
        RowText(title, subtitle, AppTheme.colors.textPrimary, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(AppTheme.colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = AppTheme.colors.onPrimary, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/** Compact single-choice pills, wrapping onto a second line when needed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> SettingsChips(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            SettingsChip(
                text = label(option),
                selected = option == selected,
                icon = icon?.invoke(option),
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
internal fun SettingsChip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val container = if (selected) AppTheme.colors.primary.copy(alpha = if (AppTheme.colors.isDark) 0.20f else 0.12f)
        else AppTheme.colors.surfaceElevated
    val content = if (selected) AppTheme.colors.primary else AppTheme.colors.textSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = content, fontSize = 13.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/** A collapsed "Advanced" area for rarely used or expert options. */
@Composable
internal fun SettingsAdvanced(
    title: String = "Advanced",
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "advancedChevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = AppTheme.colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = AppTheme.colors.textMuted,
                modifier = Modifier.size(20.dp).rotate(rotation)
            )
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** Small status pill with a dot, used for update state. */
@Composable
internal fun SettingsStatusChip(label: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** A label/value line used in the About card. */
@Composable
internal fun SettingsInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = AppTheme.colors.textMuted, fontSize = 13.sp, modifier = Modifier.width(120.dp))
        Text(value, color = AppTheme.colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}
