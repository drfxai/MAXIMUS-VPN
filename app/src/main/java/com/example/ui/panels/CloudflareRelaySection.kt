package com.example.ui.panels

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.panels.cloudflare.TokenTemplate
import com.example.panels.cloudflare.relay.CloudflareRelay
import com.example.panels.cloudflare.relay.CloudflareRelay.Phase
import kotlinx.coroutines.delay

/**
 * Build 181 Phase 7: Panels → Cloudflare → "Connect Cloudflare" (three steps), the relay's active card, and the error
 * checklist. Colours are the Cloudflare tab's own ([Cf]); the active accent is the Panels screen's selected-tab blue.
 */
@Composable
internal fun CloudflareRelaySection(vm: CloudflareRelayViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showWhy by remember { mutableStateOf(false) }
    if (showWhy) CloudflareRelayPromptSheet(onConnect = { showWhy = false }, onLater = { showWhy = false })
    val phase = ui.state.phase
    when (phase) {
        Phase.ACTIVE, Phase.ROTATING, Phase.REMOVING -> ui.relay?.let { RelayActive(ui, vm) }
        else -> RelaySteps(ui, vm, onWhy = { showWhy = true })
    }
    LaunchedEffect(phase) { if (phase == Phase.ACTIVE) vm.refreshQuota() }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, context.getString(R.string.ui_cannot_open, url), Toast.LENGTH_SHORT).show() }
}

/** The clipboard's text when the user asked for it (Paste key); null when it is not text. Never logged. */
private fun clipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (cm?.primaryClipDescription?.hasMimeType("text/*") != true) null
    else cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
}.getOrNull()

@Composable
private fun RelayHeader(title: String, subtitle: String, chip: @Composable () -> Unit) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Cf.Orange.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Cloud, contentDescription = null, tint = Cf.Orange, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Cf.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Cf.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        chip()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelaySteps(ui: RelayUi, vm: CloudflareRelayViewModel, onWhy: () -> Unit) {
    val context = LocalContext.current
    val phase = ui.state.phase
    val step = CloudflareRelay.step(phase)
    val busy = phase == Phase.VERIFYING || phase == Phase.DEPLOYING
    val failed = phase == Phase.FAILED
    var detectedHint by remember { mutableStateOf<String?>(null) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    // Back from Cloudflare's page on step 3: a copied key is picked up and checked. Only after a return to the
    // foreground (never on first open), only on this step, and only text with the shape of a Cloudflare key.
    LaunchedEffect(resumes, phase) {
        if (!CloudflareRelay.mayReadClipboard(phase, resumedFromBackground = resumes > 1)) return@LaunchedEffect
        delay(300) // Android 10+ hands the clipboard to the app only once its window has focus.
        val copied = copiedToken(context)
        if (copied != null && CloudflareRelay.usableToken(copied)) {
            detectedHint = TokenTemplate.masked(copied)
            vm.submitKey(copied)
        }
    }

    CfCard(borderColor = if (failed) Cf.Red.copy(alpha = 0.45f) else Cf.CardBorder) {
        RelayHeader(stringResource(R.string.ui_cfr_title), stringResource(R.string.ui_cfr_subtitle)) {
            if (failed) StatusChip(stringResource(R.string.ui_cfr_error), Cf.Red)
            else StatusChip(stringResource(R.string.ui_cfr_step_of, step), PanelColors.PrimaryBlue)
        }
        HorizontalDivider(color = Cf.CardBorder)

        // 1. Sign in or create account.
        StepBlock(1, done = step > 1, active = step == 1, title = stringResource(R.string.ui_cfr_step1),
            hint = if (step > 1) stringResource(R.string.ui_cfr_signed_in) else stringResource(R.string.ui_cfr_step1_hint)) {
            if (step == 1) CfOrangeButton(stringResource(R.string.ui_cfr_open_cloudflare), Icons.AutoMirrored.Filled.OpenInNew, filled = true) {
                vm.openedCloudflare()
                open(context, CloudflareRelay.DASHBOARD_URL)
            }
        }
        HorizontalDivider(color = Cf.CardBorder, modifier = Modifier.padding(start = 60.dp))

        // 2. Create access key (Cloudflare's template link, everything filled in).
        StepBlock(2, done = step > 2, active = step == 2, title = stringResource(R.string.ui_cfr_step2),
            hint = if (step > 2 && !failed) stringResource(R.string.ui_cfr_key_copied) else stringResource(R.string.ui_cfr_step2_hint)) {
            if (step <= 2 || failed) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.setWithDomain(!ui.withDomain) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ui.withDomain, onCheckedChange = { vm.setWithDomain(it) },
                        colors = CheckboxDefaults.colors(checkedColor = PanelColors.PrimaryBlue, uncheckedColor = Cf.Muted))
                    Text(stringResource(R.string.ui_cfr_with_domain), color = Cf.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                }
                TokenChips(CloudflareRelay.TOKEN_NAME,
                    (if (ui.withDomain) CloudflareRelay.DOMAIN_PERMISSIONS else CloudflareRelay.RELAY_PERMISSIONS).map { it.label },
                    StepColors(Cf.Text, Cf.Muted, Cf.Dim, Cf.Orange, Cf.Orange.copy(alpha = 0.14f), Cf.Inset))
                CfOrangeButton(stringResource(R.string.ui_cfr_create_key), Icons.Default.Key, filled = step == 2 || failed) {
                    open(context, vm.keyPageUrl())
                }
            }
        }
        HorizontalDivider(color = Cf.CardBorder, modifier = Modifier.padding(start = 60.dp))

        // 3. Back to app: clipboard pick-up, Paste key, verification and the checklist.
        StepBlock(3, done = false, active = step == 3, failedStep = failed, title = stringResource(R.string.ui_cfr_step3),
            hint = if (failed) ui.state.message ?: stringResource(R.string.ui_cfr_missing_permission) else stringResource(R.string.ui_cfr_step3_hint)) {
            if (detectedHint != null && (busy || failed)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Cf.CardBorder, RoundedCornerShape(12.dp))
                    .background(Cf.Inset).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = Cf.Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.ui_cfr_key_detected), color = Cf.Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(detectedHint.orEmpty(), color = Cf.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (failed) Checklist(ui.state.checklist)
            if (step == 3) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = PanelColors.PrimaryBlue)
                    Spacer(Modifier.width(10.dp))
                    Text(ui.progress ?: stringResource(R.string.ui_cfr_verifying), color = Cf.Text, fontSize = 13.sp,
                        modifier = Modifier.weight(1f), maxLines = 2)
                } else Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        val text = clipboardText(context)
                        if (text != null && CloudflareRelay.tokenKind(text) != CloudflareRelay.TokenKind.NONE) {
                            detectedHint = TokenTemplate.masked(text)
                            vm.submitKey(text)
                        } else Toast.makeText(context, context.getString(R.string.ui_cfr_no_key_on_clipboard), Toast.LENGTH_SHORT).show()
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Cf.CardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cf.Text)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = Cf.Muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ui_cfr_paste_key), fontSize = 13.5.sp)
                }
            }
        }
    }

    if (failed) {
        CfCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Cf.Red, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.ui_cfr_cant_use_title), color = Cf.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ui_cfr_cant_use_body), color = Cf.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { open(context, vm.keyPageUrl()) }, modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Cf.CardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cf.Text)) {
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_cfr_create_key), maxLines = 1)
            }
            Button(onClick = { vm.retry() }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue, contentColor = Color.White)) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_cfr_try_again), fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    } else {
        KeyCanDo(ui.withDomain)
        TextButton(onClick = onWhy) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Cf.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ui_cfr_why), color = Cf.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StepBlock(number: Int, done: Boolean, active: Boolean, title: String, hint: String, failedStep: Boolean = false,
                      content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        if (failedStep) Box(Modifier.size(28.dp).clip(CircleShape).background(Cf.Red.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Cf.Red, modifier = Modifier.size(15.dp))
        } else StepBadge(number = number, done = done, active = active)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Cf.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (done) { Spacer(Modifier.width(8.dp)); StatusChip(stringResource(R.string.ui_cfr_done), Cf.Green) }
                if (failedStep) { Spacer(Modifier.width(8.dp)); StatusChip(stringResource(R.string.ui_cfr_failed), Cf.Red) }
            }
            Text(hint, color = Cf.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            content()
        }
    }
}

@Composable
private fun Checklist(c: CloudflareRelay.Checklist) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cf.Red.copy(alpha = 0.06f))
        .border(1.dp, Cf.Red.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CheckRow(stringResource(R.string.ui_cfr_check_valid), c.valid)
        CheckRow(stringResource(R.string.ui_cfr_check_account), c.account)
        CheckRow(stringResource(R.string.ui_cfr_check_workers), c.workers)
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (ok) {
            true -> Icon(Icons.Default.Check, contentDescription = null, tint = Cf.Green, modifier = Modifier.size(16.dp))
            false -> Icon(Icons.Default.Close, contentDescription = null, tint = Cf.Red, modifier = Modifier.size(16.dp))
            null -> Box(Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label + if (ok == false) " · " + stringResource(R.string.ui_cfr_not_allowed) else "",
            color = if (ok == null) Cf.Dim else Cf.Text, fontSize = 13.sp)
    }
}

@Composable
private fun KeyCanDo(withDomain: Boolean) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ui_cfr_key_can_title).uppercase(), color = Cf.Muted, fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.ui_cfr_limited), color = Cf.Dim, fontSize = 11.5.sp)
    }
    CfCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CanRow(true, stringResource(R.string.ui_cfr_can_workers))
            if (withDomain) CanRow(true, stringResource(R.string.ui_cfr_can_domain))
            CanRow(false, stringResource(R.string.ui_cfr_cannot))
            CanRow(null, stringResource(R.string.ui_cfr_revoke))
        }
    }
}

@Composable
private fun CanRow(yes: Boolean?, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(when (yes) { true -> Icons.Default.Check; false -> Icons.Default.Close; null -> Icons.Default.LinkOff },
            contentDescription = null, tint = when (yes) { true -> Cf.Green; false -> Cf.Red; null -> Cf.Muted },
            modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Cf.Text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelayActive(ui: RelayUi, vm: CloudflareRelayViewModel) {
    val relay = ui.relay ?: return
    val busy = ui.state.phase == Phase.ROTATING || ui.state.phase == Phase.REMOVING
    var confirmDisconnect by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text(stringResource(R.string.ui_cfr_disconnect_confirm_title)) },
        text = { Text(stringResource(R.string.ui_cfr_disconnect_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; vm.disconnect() }) {
            Text(stringResource(R.string.ui_cfr_disconnect), color = Cf.Red) } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.ui_cfr_cancel)) } }
    )

    CfCard(borderColor = PanelColors.PrimaryBlue.copy(alpha = 0.55f)) {
        RelayHeader(stringResource(R.string.ui_cfr_relay_title), relay.scriptName) {
            StatusChip(stringResource(R.string.ui_cfr_active), Cf.Green)
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoTile(stringResource(R.string.ui_cfr_best_clean_ip), ui.bestEdgeIp ?: stringResource(R.string.ui_cfr_no_scan),
                stringResource(R.string.ui_cfr_clean_ip_hint), Modifier.weight(1f))
            InfoTile(stringResource(R.string.ui_cfr_address), relay.host,
                if (relay.customHost != null) stringResource(R.string.ui_cfr_your_domain) else "workers.dev", Modifier.weight(1f))
        }
        if (ui.reachable == false) Text(stringResource(R.string.ui_cfr_propagating), color = Cf.Amber, fontSize = 12.sp,
            lineHeight = 16.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    }

    // Free quota today.
    val quota = CloudflareRelay.current(ui.quota, now)
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ui_cfr_quota_title).uppercase(), color = Cf.Muted, fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.ui_cfr_quota_resets), color = Cf.Dim, fontSize = 11.5.sp)
    }
    CfCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val used = quota?.used ?: 0L
            val warn = CloudflareRelay.warn(quota, now)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_cfr_quota_of, "%,d".format(used), "%,d".format(CloudflareRelay.FREE_DAILY_REQUESTS)),
                    color = Cf.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${quota?.percent ?: 0}%", color = if (warn) Cf.Amber else Cf.Muted, fontSize = 13.sp)
            }
            LinearProgressIndicator(progress = { (quota?.fraction ?: 0.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = if (warn) Cf.Amber else PanelColors.PrimaryBlue, trackColor = Cf.Inset)
            if (quota?.estimate != false) Text(stringResource(R.string.ui_cfr_quota_estimate), color = Cf.Dim, fontSize = 11.5.sp, lineHeight = 15.sp)
            if (warn) Text(stringResource(R.string.ui_cfr_quota_warn), color = Cf.Amber, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }

    CfCard {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Default.Person)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_cfr_account), color = Cf.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(relay.accountName.ifBlank { relay.accountId.take(6) + "…" }, color = Cf.Muted, fontSize = 12.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = Cf.CardBorder)
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Default.AutoAwesome)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_cfr_smart_use), color = Cf.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.ui_cfr_smart_use_hint), color = Cf.Muted, fontSize = 12.5.sp)
            }
            Switch(checked = relay.smartUse, onCheckedChange = { vm.setSmartUse(it) }, enabled = !busy,
                colors = SwitchDefaults.colors(checkedTrackColor = PanelColors.PrimaryBlue, checkedThumbColor = Color.White))
        }
        // Custom domain: only with the domain key; workers.dev is often filtered in Iran.
        if (relay.withDomainKey || ui.zones.isNotEmpty()) {
            HorizontalDivider(color = Cf.CardBorder)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Default.Language)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.ui_cfr_use_domain), color = Cf.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    if (ui.zones.isEmpty()) TextButton(onClick = { vm.loadZones() }) { Text(stringResource(R.string.ui_cfr_find_domains), color = PanelColors.PrimaryBlue) }
                }
                Text(stringResource(R.string.ui_cfr_use_domain_hint), color = Cf.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.zones.forEach { z ->
                        val on = relay.zoneId == z.id
                        Text(z.name, color = if (on) Color.White else Cf.Text, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (on) PanelColors.PrimaryBlue else Cf.Inset)
                                .clickable(enabled = !on && !busy) { vm.useDomain(z) }
                                .padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
            }
        }
    }

    if (ui.progress != null && busy) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = PanelColors.PrimaryBlue)
        Spacer(Modifier.width(8.dp))
        Text(ui.progress, color = Cf.Muted, fontSize = 12.5.sp)
    }
    ui.state.message?.let { Text(it, color = Cf.Red, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp)) }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.rotate() }, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Cf.CardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cf.Text)) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ui_cfr_rotate), maxLines = 1)
        }
        OutlinedButton(onClick = { confirmDisconnect = true }, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Cf.Red.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cf.Red)) {
            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ui_cfr_disconnect), maxLines = 1)
        }
    }
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, contentDescription = null, tint = Cf.Dim, modifier = Modifier.size(15.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ui_cfr_info) + " " + stringResource(R.string.ui_cfr_limit_note),
            color = Cf.Dim, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun InfoTile(label: String, value: String, sub: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Cf.Inset).border(1.dp, Cf.CardBorder, RoundedCornerShape(12.dp))
        .padding(12.dp)) {
        Text(label.uppercase(), color = Cf.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Cf.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Cf.Dim, fontSize = 11.5.sp, maxLines = 1)
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Cf.Inset), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Cf.Muted, modifier = Modifier.size(18.dp))
    }
}
