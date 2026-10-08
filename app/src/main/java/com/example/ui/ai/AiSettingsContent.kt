package com.example.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.gateway.AiGatewaySettings
import com.example.ai.gateway.AiModelDescriptor
import com.example.ai.gateway.AiProviderConfig
import com.example.ai.gateway.AiProviderKind
import com.example.ai.gateway.AiRoutingMode
import com.example.ai.gateway.MetadataSource
import com.example.ai.gateway.ModelSource
import com.example.ai.gateway.ProviderHealth
import com.example.ai.gateway.ProviderHealthState
import com.example.ai.gateway.RouteChoice
import com.example.ui.lab.Badge
import com.example.ui.lab.Footnote
import com.example.ui.lab.GroupLabel
import com.example.ui.lab.Gutter
import com.example.ui.lab.Hairline
import com.example.ui.lab.KeyValue
import com.example.ui.lab.Panel
import com.example.ui.lab.PrimaryButton
import com.example.ui.lab.SecondaryButton
import com.example.ui.lab.Segmented
import com.example.ui.lab.TextAction
import com.example.ui.lab.TitleBar
import com.example.ui.protocols.InterFamily
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.ui.protocols.labColors

/** One provider as the settings screen sees it: never the key itself, only its masked form. */
data class AiProviderUi(
    val config: AiProviderConfig,
    val keyMasked: String?,
    val health: ProviderHealth?,
    val models: List<AiModelDescriptor>,
    val modelsRefreshedAt: Long? = null
) {
    val id: String get() = config.id
    val name: String get() = config.kind.displayName
}

data class AiSettingsUiState(
    val settings: AiGatewaySettings = AiGatewaySettings(),
    val providers: List<AiProviderUi> = emptyList(),
    val manage: Boolean = false,
    /** The provider whose card is open on the Manage screen. */
    val open: String? = null,
    /** What is running now ("Testing 9Router…"), or null. */
    val busy: String? = null,
    val message: String? = null
) {
    val primary: AiProviderUi? get() = settings.primary?.let { p -> providers.firstOrNull { it.id == p.providerId } }
}

class AiSettingsActions(
    val onBack: () -> Unit = {},
    val onManage: (Boolean) -> Unit = {},
    val onOpen: (String?) -> Unit = {},
    val onMode: (AiRoutingMode) -> Unit = {},
    val onAddProvider: (AiProviderKind) -> Unit = {},
    val onRemoveProvider: (String) -> Unit = {},
    /** The key goes straight to the vault; the screen forgets it right after. */
    val onSaveKey: (String, String) -> Unit = { _, _ -> },
    val onRemoveKey: (String) -> Unit = {},
    val onTest: (String) -> Unit = {},
    val onRefreshModels: (String) -> Unit = {},
    val onPrimary: (RouteChoice) -> Unit = {},
    val onAddFallback: (RouteChoice) -> Unit = {},
    val onRemoveFallback: (Int) -> Unit = {},
    val onMoveFallbackUp: (Int) -> Unit = {},
    val onEndpoint: (String, String) -> Unit = { _, _ -> },
    val onTimeout: (String, Long) -> Unit = { _, _ -> },
    val onCustomModel: (String, String) -> Unit = { _, _ -> },
    val onDismissMessage: () -> Unit = {}
)

@Composable
fun AiSettingsContent(state: AiSettingsUiState, actions: AiSettingsActions, relativeTime: (Long) -> String) {
    val c = labColors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (state.manage) ManagePage(c, state, actions, relativeTime) else SimplePage(c, state, actions)
    }
}

private fun modeWord(m: AiRoutingMode) = m.name.lowercase().replaceFirstChar { it.uppercase() }

private fun modeDetail(m: AiRoutingMode) = when (m) {
    AiRoutingMode.MANUAL -> "Uses exactly the provider and model you pick, then your fallbacks in order."
    AiRoutingMode.AUTO -> "Picks a capable model for each task from your providers, in your order."
    AiRoutingMode.SMART -> "Ranks your providers per task by capability, health, speed and recent failures."
}

private fun healthWord(h: ProviderHealth?, hasKey: Boolean): String = when {
    !hasKey -> "No key"
    h == null -> "Not tested"
    else -> when (h.state) {
        ProviderHealthState.HEALTHY -> if (h.lastSuccessAt != null) "Connected" else "Not tested"
        ProviderHealthState.DEGRADED -> "Degraded"
        ProviderHealthState.RATE_LIMITED -> "Rate limited"
        ProviderHealthState.QUOTA_EXHAUSTED -> "Quota used up"
        ProviderHealthState.AUTH_FAILED -> "Key refused"
        ProviderHealthState.UNREACHABLE -> "Unreachable"
        ProviderHealthState.TEMPORARY_ERROR -> "Temporary error"
        ProviderHealthState.DISABLED -> "Off"
    }
}

private fun healthColor(c: LabColors, h: ProviderHealth?, hasKey: Boolean): Color = when {
    !hasKey || h == null -> c.text3
    h.state == ProviderHealthState.HEALTHY -> if (h.lastSuccessAt != null) c.good else c.text3
    h.state in setOf(ProviderHealthState.AUTH_FAILED, ProviderHealthState.UNREACHABLE) -> c.bad
    else -> c.okay
}

// ---------------------------------------------------------------- simple

@Composable
private fun SimplePage(c: LabColors, state: AiSettingsUiState, actions: AiSettingsActions) {
    val p = state.primary
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { TitleBar(c, "AI", "Your providers, your keys", Icons.AutoMirrored.Rounded.ArrowBack, actions.onBack) }
        state.message?.let { m -> item { Banner(c, m, actions.onDismissMessage) } }
        item { GroupLabel(c, "Active route") }
        item {
            val hasKey = p?.keyMasked != null
            Panel(c) {
                KeyValue(c, "Mode", modeWord(state.settings.mode))
                Hairline(c, 14.dp)
                KeyValue(c, "Provider", p?.name ?: "Not set")
                Hairline(c, 14.dp)
                KeyValue(c, "Model", state.settings.primary?.modelId ?: "Auto")
                Hairline(c, 14.dp)
                KeyValue(c, "Status", "") { Badge(healthWord(p?.health, hasKey), healthColor(c, p?.health, hasKey)) }
                Hairline(c, 14.dp)
                KeyValue(c, "API key", p?.keyMasked ?: "Not set", if (hasKey) c.text2 else c.text3)
            }
        }
        if (p != null && p.keyMasked != null) item {
            Row(Modifier.padding(start = Gutter, end = Gutter, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(c, if (state.busy?.startsWith("Testing") == true) "Testing…" else "Test connection", Icons.Rounded.NetworkCheck, Modifier.weight(1f), state.busy == null) { actions.onTest(p.id) }
                SecondaryButton(c, if (state.busy?.startsWith("Refreshing") == true) "Refreshing…" else "Refresh models", Icons.Rounded.Refresh, Modifier.weight(1f), state.busy == null) { actions.onRefreshModels(p.id) }
            }
        }
        item {
            PrimaryButton(c, "Manage providers", Icons.Rounded.Tune, Modifier.padding(start = Gutter, end = Gutter, top = 10.dp).fillMaxWidth()) { actions.onManage(true) }
        }
        item {
            Footnote(c, "Keys are stored encrypted on this phone and are sent only to the provider they belong to. Maximus works fully without AI; " +
                "AI only explains and suggests, it never controls the VPN.")
        }
    }
}

// ---------------------------------------------------------------- manage

@Composable
private fun ManagePage(c: LabColors, state: AiSettingsUiState, actions: AiSettingsActions, relativeTime: (Long) -> String) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { TitleBar(c, "Manage AI", "Providers, models and fallbacks", Icons.AutoMirrored.Rounded.ArrowBack, { actions.onManage(false) }) }
        state.message?.let { m -> item { Banner(c, m, actions.onDismissMessage) } }
        item { GroupLabel(c, "Routing mode") }
        item {
            Panel(c) {
                Column(Modifier.padding(10.dp)) {
                    Segmented(c, AiRoutingMode.entries, state.settings.mode, ::modeWord, actions.onMode)
                    LabText(modeDetail(state.settings.mode), c.text2, 12.sp, modifier = Modifier.padding(start = 4.dp, top = 9.dp, bottom = 2.dp), maxLines = 2)
                }
            }
        }
        item { GroupLabel(c, "Providers") }
        state.providers.forEach { p ->
            item(key = p.id) { ProviderCard(c, state, p, state.open == p.id, actions, relativeTime) }
        }
        item { AddProviderRow(c, state, actions) }
        item { GroupLabel(c, "Fallback order") }
        item { FallbackList(c, state, actions) }
    }
}

@Composable
private fun ProviderCard(c: LabColors, state: AiSettingsUiState, p: AiProviderUi, open: Boolean, actions: AiSettingsActions, relativeTime: (Long) -> String) {
    val hasKey = p.keyMasked != null
    val isPrimary = state.settings.primary?.providerId == p.id
    Panel(c, Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().clickable { actions.onOpen(if (open) null else p.id) }.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(c.cardAlt), contentAlignment = Alignment.Center) {
                LabText(p.name.take(1), c.text2, 15.sp, FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabText(p.name, c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                    if (isPrimary) LabText("  ·  Primary", c.accent, 12.sp, FontWeight.SemiBold, maxLines = 1)
                }
                LabText(listOfNotNull(if (p.models.isNotEmpty()) "${p.models.size} models" else null,
                    p.health?.recentLatencyMs?.let { "$it ms" }, if (hasKey) "key saved" else "no key").joinToString(" · "), c.text3, 12.sp, maxLines = 1)
            }
            Badge(healthWord(p.health, hasKey), healthColor(c, p.health, hasKey))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
        if (open) { Hairline(c); ProviderEditor(c, state, p, actions, relativeTime) }
    }
}

@Composable
private fun ProviderEditor(c: LabColors, state: AiSettingsUiState, p: AiProviderUi, actions: AiSettingsActions, relativeTime: (Long) -> String) {
    var key by remember(p.id) { mutableStateOf("") }
    var advanced by remember(p.id) { mutableStateOf(false) }
    var modelsOpen by remember(p.id) { mutableStateOf(false) }
    val chosen = state.settings.primary?.takeIf { it.providerId == p.id }?.modelId
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
        LabText("API key", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(c, key, { key = it }, p.keyMasked ?: p.config.kind.keyHint, Modifier.weight(1f), secret = true)
            Spacer(Modifier.width(8.dp))
            SmallButton(c, "Save", enabled = key.isNotBlank()) { actions.onSaveKey(p.id, key); key = "" }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(c, "Test", Icons.Rounded.NetworkCheck, Modifier.weight(1f), state.busy == null && p.keyMasked != null) { actions.onTest(p.id) }
            SecondaryButton(c, "Refresh models", Icons.Rounded.Refresh, Modifier.weight(1f), state.busy == null && p.keyMasked != null) { actions.onRefreshModels(p.id) }
        }
        p.modelsRefreshedAt?.let { LabText("Models updated ${relativeTime(it)}", c.text3, 11.5.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1) }
        Spacer(Modifier.height(12.dp))
        LabText("Model", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, c.stroke, RoundedCornerShape(10.dp)).clickable { modelsOpen = !modelsOpen }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LabText(chosen ?: "Auto", c.text, 14.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
            Icon(if (modelsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.text3)
        }
        if (modelsOpen) {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 4.dp)) {
                ModelRow(c, "Auto", "Maximus picks per task" + if (p.models.any { it.source == ModelSource.PROVIDER_ROUTER }) "; provider routing available" else "", chosen == null) {
                    actions.onPrimary(RouteChoice(p.id, null)); modelsOpen = false
                }
                p.models.take(30).forEach { m ->
                    ModelRow(c, m.displayName, modelFacts(m), chosen == m.modelId) { actions.onPrimary(RouteChoice(p.id, m.modelId)); modelsOpen = false }
                }
                if (p.models.size > 30) LabText("${p.models.size - 30} more · type an ID under Advanced", c.text3, 11.5.sp, modifier = Modifier.padding(8.dp), maxLines = 1)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { advanced = !advanced }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            LabText("Advanced", c.text2, 13.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.size(20.dp))
        }
        if (advanced) AdvancedSection(c, p, actions)
    }
    Hairline(c)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.settings.primary?.providerId != p.id) TextAction(c, "Make primary") { actions.onPrimary(RouteChoice(p.id, null)) }
        TextAction(c, "Add as fallback") { actions.onAddFallback(RouteChoice(p.id, null)) }
        Spacer(Modifier.weight(1f))
        if (p.keyMasked != null) TextAction(c, "Remove key", color = c.bad) { actions.onRemoveKey(p.id) }
        TextAction(c, "Remove", color = c.bad) { actions.onRemoveProvider(p.id) }
    }
}

private fun modelFacts(m: AiModelDescriptor): String {
    val facts = listOfNotNull(
        m.contextWindow?.let { if (it >= 1000) "${it / 1000}K context" else "$it context" },
        if (m.supportsVision == true) "vision" else null,
        if (m.supportsReasoning == true) "reasoning" else null,
        if (m.source == ModelSource.PROVIDER_ROUTER) "provider routing" else null,
        if (m.source == ModelSource.CUSTOM) "custom ID" else null
    )
    val origin = when (m.metadata) { MetadataSource.DECLARED -> "from provider"; MetadataSource.INFERRED -> "inferred from name"; MetadataSource.UNKNOWN -> "unknown" }
    return (facts + origin).joinToString(" · ")
}

@Composable
private fun ModelRow(c: LabColors, name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) c.accentSoft else Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LabText(name, if (selected) c.accent else c.text, 13.5.sp, FontWeight.SemiBold, maxLines = 1)
            if (detail.isNotBlank()) LabText(detail, c.text3, 11.5.sp, maxLines = 1)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AdvancedSection(c: LabColors, p: AiProviderUi, actions: AiSettingsActions) {
    var endpoint by remember(p.id) { mutableStateOf(p.config.endpoint) }
    var custom by remember(p.id) { mutableStateOf(p.config.customModelId.orEmpty()) }
    var timeout by remember(p.id) { mutableStateOf((p.config.timeoutMs / 1000).toString()) }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        LabText("Endpoint", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(c, endpoint, { endpoint = it }, p.config.kind.defaultEndpoint ?: "https://… or http://localhost:20128/v1", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SmallButton(c, "Set", enabled = endpoint != p.config.endpoint) { actions.onEndpoint(p.id, endpoint.trim()) }
        }
        LabText("HTTPS only; plain HTTP is allowed for this phone (localhost) only.", c.text3, 11.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp), maxLines = 2)
        LabText("Custom model ID", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(c, custom, { custom = it }, "e.g. provider/model-name", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SmallButton(c, "Use", enabled = custom.isNotBlank()) { actions.onCustomModel(p.id, custom.trim()) }
        }
        Spacer(Modifier.height(8.dp))
        LabText("Timeout (seconds)", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(c, timeout, { timeout = it.filter(Char::isDigit).take(3) }, "60", Modifier.weight(1f), number = true)
            Spacer(Modifier.width(8.dp))
            SmallButton(c, "Set", enabled = timeout.toLongOrNull()?.let { it in 5..180 } == true) { actions.onTimeout(p.id, timeout.toLong() * 1000) }
        }
        p.health?.let { h ->
            Spacer(Modifier.height(10.dp))
            LabText("Health: ${h.state.name.lowercase().replace('_', ' ')} · ${h.recentErrors} recent errors" +
                (h.retryAt?.let { " · paused until a retry" } ?: ""), c.text2, 12.sp, maxLines = 2)
        }
    }
}

@Composable
private fun AddProviderRow(c: LabColors, state: AiSettingsUiState, actions: AiSettingsActions) {
    val missing = AiProviderKind.entries.filter { k -> k == AiProviderKind.OPENAI_COMPATIBLE || state.providers.none { it.config.kind == k } }
    if (missing.isEmpty()) return
    Panel(c) {
        missing.forEachIndexed { i, k ->
            if (i > 0) Hairline(c, 48.dp)
            Row(Modifier.fillMaxWidth().clickable { actions.onAddProvider(k) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                LabText("Add ${k.displayName}", c.text, 14.sp, FontWeight.Medium, Modifier.weight(1f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun FallbackList(c: LabColors, state: AiSettingsUiState, actions: AiSettingsActions) {
    val names = state.providers.associate { it.id to it.name }
    Panel(c) {
        state.settings.primary?.let { p ->
            ChainRow(c, "1", names[p.providerId] ?: p.providerId, p.modelId ?: "Auto", "Primary", null, null)
        }
        state.settings.fallbacks.forEachIndexed { i, f ->
            Hairline(c, 52.dp)
            ChainRow(c, "${i + 2}", names[f.providerId] ?: f.providerId, f.modelId ?: "Auto", null,
                if (i > 0) ({ actions.onMoveFallbackUp(i) }) else null, { actions.onRemoveFallback(i) })
        }
        if (state.settings.fallbacks.isEmpty()) {
            LabText("No fallbacks. Open a provider and tap \"Add as fallback\"; if the primary fails, Maximus tries them in order.",
                c.text3, 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), maxLines = 3)
        }
    }
}

@Composable
private fun ChainRow(c: LabColors, n: String, provider: String, model: String, badge: String?, onUp: (() -> Unit)?, onRemove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).border(1.dp, c.stroke, CircleShape), contentAlignment = Alignment.Center) { LabText(n, c.text2, 12.sp, FontWeight.Bold, maxLines = 1) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            LabText(provider, c.text, 14.sp, FontWeight.SemiBold, maxLines = 1)
            LabText(model, c.text3, 12.sp, maxLines = 1)
        }
        badge?.let { Badge(it, c.accent) }
        onUp?.let { Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = it), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ArrowUpward, "Move up", tint = c.text2, modifier = Modifier.size(18.dp)) } }
        onRemove?.let { Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = it), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Remove", tint = c.text2, modifier = Modifier.size(18.dp)) } }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Banner(c: LabColors, message: String, onDismiss: () -> Unit) {
    Row(Modifier.padding(horizontal = Gutter, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)
        .border(1.dp, c.stroke, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c.info))
        Spacer(Modifier.width(10.dp))
        LabText(message, c.text, 12.5.sp, modifier = Modifier.weight(1f), maxLines = 3)
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Dismiss", tint = c.text2, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SmallButton(c: LabColors, text: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val color = when { !enabled -> c.text3; danger -> c.bad; else -> c.accent }
    Box(modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, if (enabled) color.copy(alpha = 0.35f) else c.stroke, RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        LabText(text, color, 13.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Field(c: LabColors, value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, secret: Boolean = false, number: Boolean = false) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(c.cardAlt).border(1.dp, c.stroke, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) LabText(hint, c.text3, 13.sp, maxLines = 1)
        BasicTextField(
            value, onChange, singleLine = true, cursorBrush = SolidColor(c.accent),
            textStyle = TextStyle(color = c.text, fontSize = 13.5.sp, fontFamily = InterFamily),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when { secret -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Uri }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
