package com.example.ui.home

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.theme.AppTheme
import com.example.vpn.unlock.ServerHealth
import com.example.vpn.unlock.UnlockDns
import com.example.vpn.unlock.UnlockDnsHealth
import com.example.vpn.unlock.UnlockDnsList
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Unlock mode's mark: a shield with a key inside, at [size]. */
@Composable
internal fun UnlockKeyShieldIcon(tint: Color, size: Dp = 18.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Shield, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        Icon(Icons.Rounded.Key, contentDescription = null, tint = tint,
            modifier = Modifier.size(size * 0.46f).offset(y = -(size * 0.02f)))
    }
}

/** The green "DNS 143.20.64.55 • 38 ms" pill under "Unlocked". */
@Composable
internal fun UnlockStatusPill(server: String, latencyMs: Long?, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val green = c.statusConnected
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(green.copy(alpha = if (c.isDark) 0.14f else 0.10f))
            .border(1.dp, green.copy(alpha = 0.38f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .testTag("unlock_status_pill"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(green))
        Spacer(Modifier.width(7.dp))
        Text(
            text = latencyMs?.let { stringResource(R.string.unlock_pill, server, it) } ?: stringResource(R.string.unlock_pill_no_ms, server),
            color = green,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

private fun UnlockDnsHealth.Service.icon(): ImageVector = when (this) {
    UnlockDnsHealth.Service.GEMINI -> Icons.Rounded.AutoAwesome
    UnlockDnsHealth.Service.CHATGPT -> Icons.Outlined.ChatBubbleOutline
    UnlockDnsHealth.Service.AI_STUDIO -> Icons.Rounded.AutoFixHigh
    UnlockDnsHealth.Service.ANDROID_DEV -> Icons.Rounded.Android
}

@Composable
private fun UnlockDnsHealth.Service.label(): String = when (this) {
    UnlockDnsHealth.Service.GEMINI -> stringResource(R.string.unlock_service_gemini)
    UnlockDnsHealth.Service.CHATGPT -> stringResource(R.string.unlock_service_chatgpt)
    UnlockDnsHealth.Service.AI_STUDIO -> stringResource(R.string.unlock_service_ai_studio)
    UnlockDnsHealth.Service.ANDROID_DEV -> stringResource(R.string.unlock_service_android_dev)
}

private fun UnlockDnsHealth.Service.tint(): Color = when (this) {
    UnlockDnsHealth.Service.GEMINI -> Color(0xFF8AB4F8)
    UnlockDnsHealth.Service.CHATGPT -> Color(0xFFE2E4EE)
    UnlockDnsHealth.Service.AI_STUDIO -> Color(0xFFD0BCFF)
    UnlockDnsHealth.Service.ANDROID_DEV -> Color(0xFF3DDC84)
}

/**
 * The verified services orbiting the left side of the Earth while Unlock mode is connected. Each badge's
 * mark shows the quick check through the active server: a green check once it resolved and answered on 443,
 * a muted dot while checking, a red cross when it did not.
 */
@Composable
internal fun BoxScope.UnlockServiceOrbit(services: Map<UnlockDnsHealth.Service, Boolean?>) {
    if (services.isEmpty()) return
    BoxWithConstraints(Modifier.matchParentSize()) {
        val side = minOf(maxWidth, maxHeight)
        val radius = side * 0.47f
        // Badges sit on an arc from upper-left to lower-left, clear of the power button (lower right).
        val angles = listOf(130f, 158f, 186f, 214f)
        UnlockDnsHealth.Service.entries.forEachIndexed { i, service ->
            val a = Math.toRadians(angles[i % angles.size].toDouble())
            val x = (maxWidth / 2) + radius * cos(a).toFloat()
            val y = (maxHeight / 2) - radius * sin(a).toFloat()
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { shown = true }
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(350, delayMillis = 90 * i)) + scaleIn(tween(350, delayMillis = 90 * i), initialScale = 0.8f),
                modifier = Modifier.offset(x = x - BADGE_SLOT / 2, y = y - BADGE / 2)
            ) {
                ServiceBadge(service, services[service])
            }
        }
    }
}

private val BADGE = 40.dp
/** Wide enough for the longest label ("Android Dev") under a badge. */
private val BADGE_SLOT = 78.dp

@Composable
private fun ServiceBadge(service: UnlockDnsHealth.Service, ok: Boolean?) {
    val c = AppTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(BADGE_SLOT).testTag("unlock_badge_${service.name.lowercase()}")) {
        Box(Modifier.size(BADGE)) {
            Box(
                Modifier
                    .size(BADGE)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceElevated.copy(alpha = 0.92f))
                    .border(1.dp, c.borderMedium, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(service.icon(), contentDescription = null, tint = service.tint(), modifier = Modifier.size(20.dp))
            }
            val (mark, color) = when (ok) {
                true -> Icons.Rounded.Check to c.statusConnected
                false -> Icons.Rounded.Close to c.statusError
                null -> null to c.textMuted
            }
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).size(15.dp).clip(CircleShape)
                    .background(if (mark == null) c.surfacePill else color),
                contentAlignment = Alignment.Center
            ) {
                if (mark != null) Icon(mark, contentDescription = null, tint = c.background, modifier = Modifier.size(11.dp))
            }
        }
        Text(service.label(), color = c.textLight, fontSize = 10.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(top = 3.dp), textAlign = TextAlign.Center)
    }
}

/**
 * Home's Unlock card under the Earth: the Private DNS warning when it applies, the servers line that opens the
 * sheet, and the one-line reminder of what Unlock does not do.
 */
@Composable
internal fun UnlockDnsCard(state: UnlockDns.State, onOpen: () -> Unit) {
    val c = AppTheme.colors
    val usable = state.rows.count { it.check?.usable == true }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.privateDnsStrict) PrivateDnsWarning()
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen).testTag("unlock_dns_card"),
            shape = RoundedCornerShape(16.dp),
            color = c.surfaceCard,
            border = BorderStroke(1.dp, c.borderSubtle)
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Dns, contentDescription = null, tint = c.primary, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.unlock_sheet_title), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            state.checking -> stringResource(R.string.unlock_health_checking)
                            state.rows.none { it.check != null } -> stringResource(R.string.unlock_servers_count, state.rows.size)
                            else -> stringResource(R.string.unlock_status_summary, usable, state.rows.size)
                        } + " • " + stringResource(if (state.autoPick) R.string.unlock_auto_short else R.string.unlock_manual_short),
                        color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.textMuted)
            }
        }
        InfoChip()
    }
}

@Composable
private fun InfoChip() {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceElevated.copy(alpha = 0.7f))
            .border(1.dp, c.borderSubtle, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.unlock_info_only_dns), color = c.textSecondary, fontSize = 11.5.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun PrivateDnsWarning() {
    val c = AppTheme.colors
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("unlock_private_dns_warning"),
        shape = RoundedCornerShape(14.dp),
        color = if (c.isDark) Color(0xFF2A2116) else Color(0xFFFFF8E8),
        border = BorderStroke(1.dp, c.statusWarning.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = c.statusWarning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.unlock_private_dns_warning), color = c.textPrimary, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(end = 6.dp))
            }
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) { Text(stringResource(R.string.unlock_private_dns_open), color = c.statusWarning, fontSize = 12.sp) }
        }
    }
}

/** What the sheet can ask for; [UnlockDnsSheet] wires it to [UnlockDns], screenshot tests to nothing. */
internal interface UnlockSheetActions {
    fun setAutoPick(on: Boolean) {}
    fun setShaping(on: Boolean) {}
    fun select(ip: String) {}
    fun addCustom(input: String): UnlockDnsList.CustomResult = UnlockDnsList.CustomResult.Invalid
    fun removeCustom(ip: String) {}
    fun recheck() {}
    fun refreshList() {}
}

/** The "Unlock DNS" bottom sheet opened from the Unlock mode card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnlockDnsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by UnlockDns.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        UnlockDns.load(context)
        val fresh = UnlockDns.state.value.checks.maxOfOrNull { it.checkedAt } ?: 0L
        if (System.currentTimeMillis() - fresh > RECHECK_AFTER_MS) UnlockDns.recheck(context)
    }
    val actions = remember(context) {
        object : UnlockSheetActions {
            override fun setAutoPick(on: Boolean) = UnlockDns.setAutoPick(context, on)
            override fun setShaping(on: Boolean) {
                UnlockDns.setShaping(context, on)
                scope.launch { UnlockDns.recheck(context) }
            }
            override fun select(ip: String) = UnlockDns.select(context, ip)
            override fun addCustom(input: String): UnlockDnsList.CustomResult {
                val result = UnlockDns.addCustom(context, input)
                if (result is UnlockDnsList.CustomResult.Added) scope.launch { UnlockDns.recheck(context) }
                return result
            }
            override fun removeCustom(ip: String) = UnlockDns.removeCustom(context, ip)
            override fun recheck() { scope.launch { UnlockDns.recheck(context) } }
            override fun refreshList() {
                scope.launch { if (UnlockDns.refreshList(context) == UnlockDns.ListResult.UPDATED) UnlockDns.recheck(context) }
            }
        }
    }
    val c = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        scrimColor = Color.Black.copy(alpha = if (c.isDark) 0.6f else 0.35f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            UnlockDnsSheetContent(state, actions)
        }
    }
}

private const val RECHECK_AFTER_MS = 5 * 60_000L

/** The sheet's content, stateless so a screenshot test can draw it from a fixed [state]. */
@Composable
internal fun UnlockDnsSheetContent(state: UnlockDns.State, actions: UnlockSheetActions) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("unlock_dns_sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Header: title and subtitle on the left, Re-check as an icon button on the right.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.unlock_sheet_title), color = c.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.unlock_sheet_subtitle), color = c.textSecondary, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).border(1.dp, c.borderMedium, CircleShape)
                    .clickable(enabled = !state.checking, onClickLabel = stringResource(R.string.unlock_recheck), onClick = actions::recheck)
                    .testTag("unlock_recheck"),
                contentAlignment = Alignment.Center
            ) {
                if (state.checking) CircularProgressIndicator(Modifier.size(16.dp), color = c.primary, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.unlock_recheck), tint = c.textPrimary,
                    modifier = Modifier.size(20.dp))
            }
        }
        if (state.privateDnsStrict) PrivateDnsWarning()

        val inUse = state.active ?: if (state.autoPick) UnlockDnsHealth.pick(state.checks, true, null) else state.manual
        val checked = state.rows.mapNotNull { it.check }
        // One health shared by every checked server is said once, in the summary, instead of on every row.
        val sharedHealth = checked.map { it.health }.distinct().singleOrNull()
            ?.takeIf { checked.size > 1 && checked.size == state.rows.size && it != ServerHealth.UNKNOWN }
        InUseCard(state, inUse, sharedHealth)

        // Servers
        val reachable = checked.count { it.usable }
        SheetLabel(
            text = if (checked.isEmpty()) stringResource(R.string.unlock_servers_count, state.rows.size)
                else stringResource(R.string.unlock_servers_reachable, reachable),
            trailing = if (checked.isEmpty()) null else stringResource(R.string.unlock_sorted_by_latency)
        )
        val latencies = state.rows.mapNotNull { r -> r.check?.takeIf { it.usable }?.latencyMs }
        val fastest = state.rows.firstOrNull { it.check?.usable == true }?.ip
        SheetCard {
            state.rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                ServerRow(row, rank = index + 1, selected = row.ip == inUse, active = row.ip == state.active, checking = state.checking,
                    selectable = !state.autoPick, fastest = row.ip == fastest, showHealth = sharedHealth == null,
                    minMs = latencies.minOrNull(), maxMs = latencies.maxOrNull(),
                    onSelect = { actions.select(row.ip) }, onRemove = { actions.removeCustom(row.ip) })
            }
            if (state.rows.isNotEmpty()) RowDivider()
            AddCustomDns(actions, enabled = state.custom.size < UnlockDnsList.MAX_CUSTOM)
        }

        // Options: auto-pick, query shaping (CircumVolve, CCS 2026) and the GitHub list together.
        SheetLabel(stringResource(R.string.unlock_options), null)
        SheetCard {
            OptionSwitchRow(
                title = stringResource(R.string.unlock_auto_pick),
                detail = stringResource(if (state.autoPick) R.string.unlock_auto_pick_hint else R.string.unlock_manual_hint),
                checked = state.autoPick, tag = "unlock_auto_pick", onChange = actions::setAutoPick
            )
            RowDivider()
            OptionSwitchRow(
                title = stringResource(R.string.unlock_shaping),
                detail = stringResource(if (state.shaping) R.string.unlock_shaping_hint_on else R.string.unlock_shaping_hint_off),
                checked = state.shaping, tag = "unlock_query_shaping", onChange = actions::setShaping
            )
            RowDivider()
            Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.surfacePill), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Code, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.unlock_list_title), color = c.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    val updated = state.listUpdatedAt
                    Text(
                        text = when {
                            state.listBusy -> stringResource(R.string.unlock_list_refreshing)
                            state.listFromPack && updated != null -> stringResource(R.string.unlock_list_updated,
                                DateUtils.getRelativeTimeSpanString(updated, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString())
                            else -> stringResource(R.string.unlock_list_builtin)
                        },
                        color = c.textSecondary, fontSize = 12.sp
                    )
                    when (state.listResult) {
                        UnlockDns.ListResult.NOT_PUBLISHED -> Text(stringResource(R.string.unlock_list_not_published), color = c.textSecondary, fontSize = 11.sp)
                        UnlockDns.ListResult.FAILED -> Text(stringResource(R.string.unlock_list_failed), color = c.statusError, fontSize = 11.sp)
                        else -> Unit
                    }
                }
                IconButton(onClick = actions::refreshList, enabled = !state.listBusy) {
                    if (state.listBusy) CircularProgressIndicator(Modifier.size(16.dp), color = c.primary, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.unlock_list_refresh), tint = c.primary,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = c.textMuted, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.unlock_info_only_dns), color = c.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun healthStyle(health: ServerHealth, checking: Boolean, hasCheck: Boolean): Pair<Color, String> {
    val c = AppTheme.colors
    return when {
        checking && !hasCheck -> c.textMuted to stringResource(R.string.unlock_health_checking)
        health == ServerHealth.HEALTHY -> c.statusConnected to stringResource(R.string.unlock_health_healthy)
        health == ServerHealth.SLOW -> c.statusWarning to stringResource(R.string.unlock_health_slow)
        health == ServerHealth.BLOCKED -> c.statusError to stringResource(R.string.unlock_health_blocked)
        else -> c.textMuted to stringResource(R.string.unlock_health_unknown)
    }
}

/** The "In use" summary: the server answering now, its latency and health. */
@Composable
private fun InUseCard(state: UnlockDns.State, inUse: String?, sharedHealth: ServerHealth?) {
    val c = AppTheme.colors
    val row = state.rows.firstOrNull { it.ip == inUse }
    val check = row?.check
    val ms = (if (inUse != null && inUse == state.active) state.activeMs else null) ?: check?.latencyMs?.takeIf { check.usable }
    val (healthColor, healthText) = healthStyle(check?.health ?: ServerHealth.UNKNOWN, state.checking, check != null)
    Surface(shape = RoundedCornerShape(20.dp), color = c.surfaceCard, border = BorderStroke(1.dp, c.borderSubtle),
        modifier = Modifier.fillMaxWidth().testTag("unlock_in_use_card")) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(c.surfacePill), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.unlock_in_use_mode,
                        stringResource(if (state.autoPick) R.string.unlock_auto_short else R.string.unlock_manual_short)).uppercase(),
                    color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, maxLines = 1
                )
                Text(inUse ?: "—", color = c.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (ms != null) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold)) { append(ms.toString()) }
                            withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)) { append(" ms") }
                        },
                        color = healthColor, maxLines = 1
                    )
                }
                if (check != null || state.checking) Text(
                    if (sharedHealth != null) stringResource(R.string.unlock_all_servers, healthText) else healthText,
                    color = c.textSecondary, fontSize = 12.sp, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String, trailing: String?) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = c.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f), maxLines = 1)
        trailing?.let { Text(it, color = c.textMuted, fontSize = 12.sp, maxLines = 1) }
    }
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    val c = AppTheme.colors
    Surface(shape = RoundedCornerShape(20.dp), color = c.surfaceCard, border = BorderStroke(1.dp, c.borderSubtle),
        modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.borderSubtle))

@Composable
private fun OptionSwitchRow(title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = c.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary, checkedTrackColor = c.primary, checkedBorderColor = c.primary,
                uncheckedThumbColor = c.textMuted, uncheckedTrackColor = c.surfaceElevated, uncheckedBorderColor = c.borderMedium
            )
        )
    }
}

@Composable
private fun SmallTag(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
private fun ServerRow(
    row: UnlockDns.Row,
    rank: Int,
    selected: Boolean,
    active: Boolean,
    checking: Boolean,
    selectable: Boolean,
    fastest: Boolean,
    showHealth: Boolean,
    minMs: Long?,
    maxMs: Long?,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val c = AppTheme.colors
    val health = row.check?.health ?: ServerHealth.UNKNOWN
    val blocked = health == ServerHealth.BLOCKED
    val dim by animateFloatAsState(if (blocked && !selected) 0.5f else 1f, label = "unlock_row_alpha")
    val (statusColor, statusText) = healthStyle(health, checking, row.check != null)
    val latency = row.check?.latencyMs?.takeIf { !blocked }
    Row(
        Modifier.fillMaxWidth().alpha(dim)
            .background(if (selected) c.primary.copy(alpha = if (c.isDark) 0.06f else 0.05f) else Color.Transparent)
            .then(if (selectable) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(start = 12.dp, end = if (row.custom) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp)
            .testTag("unlock_server_${row.ip}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // A check tile for the server in use, its rank for the rest.
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(if (selected) c.primaryContainer.copy(alpha = 0.6f) else c.surfaceElevated),
            contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = c.primary, modifier = Modifier.size(19.dp))
            else Text(rank.toString(), color = c.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.ip, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (row.custom) { Spacer(Modifier.width(6.dp)); SmallTag(stringResource(R.string.unlock_custom_badge), c.primary) }
                if (row.check?.shape != null && !blocked) { Spacer(Modifier.width(6.dp)); SmallTag(stringResource(R.string.unlock_shaped_badge), c.statusWarning) }
            }
            Text(
                text = when {
                    latency != null && fastest -> stringResource(R.string.unlock_latency_fastest, latency)
                    latency != null -> stringResource(R.string.unlock_latency_ms, latency)
                    else -> "—"
                },
                color = c.textSecondary,
                fontSize = 12.sp
            )
        }
        if (active) {
            Text(stringResource(R.string.unlock_in_use), color = c.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surfacePill).padding(horizontal = 10.dp, vertical = 4.dp))
            Spacer(Modifier.width(10.dp))
        }
        if (showHealth || blocked || row.check == null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(6.dp))
            Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (latency != null) Spacer(Modifier.width(10.dp))
        }
        if (latency != null) {
            // Relative speed: the fastest checked server fills most of the bar, the slowest the least.
            val span = (maxMs ?: latency) - (minMs ?: latency)
            val fraction = if (span <= 0L) 0.6f else 0.2f + 0.6f * ((maxMs ?: latency) - latency).toFloat() / span
            Box(Modifier.width(48.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.surfacePill)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(3.dp))
                    .background(if (selected) c.primary else statusColor))
            }
        }
        if (row.custom) {
            IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.unlock_remove, row.ip), tint = c.textMuted,
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun AddCustomDns(actions: UnlockSheetActions, enabled: Boolean) {
    val c = AppTheme.colors
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.unlock_custom_invalid)
    val duplicate = stringResource(R.string.unlock_custom_duplicate)
    val full = stringResource(R.string.unlock_custom_full, UnlockDnsList.MAX_CUSTOM)
    val shownError = error
    if (!editing) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled) { editing = true; error = null }
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .testTag("unlock_add_custom"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).border(1.dp, if (enabled) c.borderMedium else c.borderSubtle, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = if (enabled) c.primary else c.textMuted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(if (enabled) stringResource(R.string.unlock_add_custom) else full,
                color = if (enabled) c.primary else c.textMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.filter { ch -> ch.isDigit() || ch == '.' }.take(15); error = null },
            placeholder = { Text(stringResource(R.string.unlock_custom_hint), fontSize = 13.sp) },
            isError = error != null,
            supportingText = shownError?.let { message -> @Composable { Text(message, fontSize = 11.sp) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.primary, unfocusedBorderColor = c.borderMedium,
                focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, cursorColor = c.primary
            ),
            modifier = Modifier.fillMaxWidth().testTag("unlock_custom_input")
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { editing = false; input = ""; error = null }) {
                Text(stringResource(R.string.unlock_cancel), color = c.textSecondary)
            }
            TextButton(onClick = {
                when (actions.addCustom(input)) {
                    is UnlockDnsList.CustomResult.Added -> { editing = false; input = ""; error = null }
                    UnlockDnsList.CustomResult.Invalid -> error = invalid
                    UnlockDnsList.CustomResult.Duplicate -> error = duplicate
                    UnlockDnsList.CustomResult.Full -> error = full
                }
            }, enabled = input.isNotBlank()) {
                Text(stringResource(R.string.unlock_custom_add), color = c.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
