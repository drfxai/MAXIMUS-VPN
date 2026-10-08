package com.example.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
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
import com.example.ui.protocols.GradientButton
import com.example.ui.protocols.IconTile
import com.example.ui.protocols.InterFamily
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.ui.protocols.Pill
import com.example.ui.protocols.SectionLabel
import com.example.ui.protocols.labCard
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
        item { Header(c, "AI", "Your providers, your keys", actions.onBack) }
        state.message?.let { m -> item { Banner(c, m, actions.onDismissMessage) } }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().labCard(c)) {
                InfoRow(c, "Mode", modeWord(state.settings.mode))
                InfoRow(c, "Provider", p?.name ?: "Not set")
                InfoRow(c, "Model", state.settings.primary?.modelId ?: "Auto")
                InfoRow(c, "Status", healthWord(p?.health, p?.keyMasked != null), healthColor(c, p?.health, p?.keyMasked != null))
                InfoRow(c, "API Key", p?.keyMasked ?: "Not set", last = true)
            }
        }
        if (p != null && p.keyMasked != null) item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton(c, if (state.busy?.startsWith("Testing") == true) "Testing…" else "Test Connection", Icons.Rounded.NetworkCheck, Modifier.weight(1f), state.busy == null) { actions.onTest(p.id) }
                OutlineButton(c, if (state.busy?.startsWith("Refreshing") == true) "Refreshing…" else "Refresh Models", Icons.Rounded.Refresh, Modifier.weight(1f), state.busy == null) { actions.onRefreshModels(p.id) }
            }
        }
        item {
            GradientButton(c, "Manage", Icons.Rounded.Tune, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) { actions.onManage(true) }
        }
        item {
            LabText("Keys are stored encrypted on this phone and are sent only to the provider they belong to. Maximus works fully without AI; " +
                "AI only explains and suggests, it never controls the VPN.", c.text3, 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 4)
        }
    }
}

@Composable
private fun InfoRow(c: LabColors, label: String, value: String, valueColor: Color? = null, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(label, c.text2, 14.sp, FontWeight.Medium, Modifier.weight(1f), maxLines = 1)
        LabText(value, valueColor ?: c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
    }
    if (!last) Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).background(c.divider))
}

// ---------------------------------------------------------------- manage

@Composable
private fun ManagePage(c: LabColors, state: AiSettingsUiState, actions: AiSettingsActions, relativeTime: (Long) -> String) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { Header(c, "Manage AI", "Providers, models and fallbacks") { actions.onManage(false) } }
        state.message?.let { m -> item { Banner(c, m, actions.onDismissMessage) } }
        item { SectionLabel(c, "Mode") }
        item {
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().labCard(c).padding(6.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.cardAlt).padding(4.dp)) {
                    AiRoutingMode.entries.forEach { m ->
                        val on = m == state.settings.mode
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) c.accent else Color.Transparent)
                            .clickable { actions.onMode(m) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            LabText(modeWord(m), if (on) c.onAccent else c.text2, 13.sp, FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
                LabText(modeDetail(state.settings.mode), c.text2, 12.5.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp), maxLines = 2)
            }
        }
        item { SectionLabel(c, "Providers") }
        state.providers.forEach { p ->
            item(key = p.id) { ProviderCard(c, state, p, state.open == p.id, actions, relativeTime) }
        }
        item { AddProviderRow(c, state, actions) }
        item { SectionLabel(c, "Fallbacks") }
        item { FallbackList(c, state, actions) }
    }
}

@Composable
private fun ProviderCard(c: LabColors, state: AiSettingsUiState, p: AiProviderUi, open: Boolean, actions: AiSettingsActions, relativeTime: (Long) -> String) {
    val hasKey = p.keyMasked != null
    val isPrimary = state.settings.primary?.providerId == p.id
    Column(Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth().labCard(c, 18.dp)) {
        Row(Modifier.fillMaxWidth().clickable { actions.onOpen(if (open) null else p.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Key, healthColor(c, p.health, hasKey), 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabText(p.name, c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                    if (isPrimary) { Spacer(Modifier.width(6.dp)); Pill("Primary", c.accent) }
                }
                LabText(listOfNotNull(p.keyMasked ?: "No key", if (p.models.isNotEmpty()) "${p.models.size} models" else null,
                    p.health?.recentLatencyMs?.let { "$it ms" }).joinToString(" · "), c.text2, 12.sp, maxLines = 1)
            }
            Pill(healthWord(p.health, hasKey), healthColor(c, p.health, hasKey))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.padding(start = 4.dp))
        }
        if (open) ProviderEditor(c, state, p, actions, relativeTime)
    }
}

@Composable
private fun ProviderEditor(c: LabColors, state: AiSettingsUiState, p: AiProviderUi, actions: AiSettingsActions, relativeTime: (Long) -> String) {
    var key by remember(p.id) { mutableStateOf("") }
    var advanced by remember(p.id) { mutableStateOf(false) }
    var modelsOpen by remember(p.id) { mutableStateOf(false) }
    val chosen = state.settings.primary?.takeIf { it.providerId == p.id }?.modelId
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        LabText("API Key", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(c, key, { key = it }, p.keyMasked ?: p.config.kind.keyHint, Modifier.weight(1f), secret = true)
            Spacer(Modifier.width(8.dp))
            SmallButton(c, "Save", enabled = key.isNotBlank()) { actions.onSaveKey(p.id, key); key = "" }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton(c, "Test", Icons.Rounded.NetworkCheck, Modifier.weight(1f), state.busy == null && p.keyMasked != null) { actions.onTest(p.id) }
            OutlineButton(c, "Refresh Models", Icons.Rounded.Refresh, Modifier.weight(1f), state.busy == null && p.keyMasked != null) { actions.onRefreshModels(p.id) }
        }
        p.modelsRefreshedAt?.let { LabText("Models updated ${relativeTime(it)}", c.text3, 11.5.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1) }
        Spacer(Modifier.height(12.dp))
        LabText("Model", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cardAlt).clickable { modelsOpen = !modelsOpen }.padding(12.dp),
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
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.settings.primary?.providerId != p.id) SmallButton(c, "Make primary", Modifier.weight(1f)) { actions.onPrimary(RouteChoice(p.id, null)) }
            SmallButton(c, "Add as fallback", Modifier.weight(1f)) { actions.onAddFallback(RouteChoice(p.id, null)) }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { advanced = !advanced }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LabText("Advanced", c.text2, 13.sp, FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.text3)
        }
        if (advanced) AdvancedSection(c, p, actions)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.keyMasked != null) SmallButton(c, "Remove key", Modifier.weight(1f), danger = true) { actions.onRemoveKey(p.id) }
            SmallButton(c, "Remove provider", Modifier.weight(1f), danger = true) { actions.onRemoveProvider(p.id) }
        }
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
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        LabText("Add a provider", c.text3, 12.sp, FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            missing.forEach { k ->
                Row(Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.stroke, RoundedCornerShape(50)).clickable { actions.onAddProvider(k) }
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    LabText(k.displayName, c.text, 13.sp, FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FallbackList(c: LabColors, state: AiSettingsUiState, actions: AiSettingsActions) {
    val names = state.providers.associate { it.id to it.name }
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().labCard(c).padding(vertical = 6.dp)) {
        state.settings.primary?.let { p ->
            ChainRow(c, "1", names[p.providerId] ?: p.providerId, p.modelId ?: "Auto", "Primary", null, null)
        }
        state.settings.fallbacks.forEachIndexed { i, f ->
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(c.cardAlt), contentAlignment = Alignment.Center) { LabText(n, c.text2, 12.sp, FontWeight.Bold, maxLines = 1) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            LabText(provider, c.text, 14.sp, FontWeight.SemiBold, maxLines = 1)
            LabText(model, c.text2, 12.sp, maxLines = 1)
        }
        badge?.let { Pill(it, c.accent) }
        onUp?.let { Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = it), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ArrowUpward, "Move up", tint = c.text2, modifier = Modifier.size(18.dp)) } }
        onRemove?.let { Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = it), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Remove", tint = c.text2, modifier = Modifier.size(18.dp)) } }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Header(c: LabColors, title: String, sub: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.text)
        }
        Spacer(Modifier.width(4.dp))
        Column {
            LabText(title, c.text, 24.sp, FontWeight.Bold, maxLines = 1)
            LabText(sub, c.text2, 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Banner(c: LabColors, message: String, onDismiss: () -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.info.copy(alpha = 0.12f))
        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LabText(message, c.text, 13.sp, modifier = Modifier.weight(1f), maxLines = 3)
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Dismiss", tint = c.text2, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun OutlineButton(c: LabColors, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) c.accent else c.text3
    Row(modifier.height(46.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
        .clickable(enabled = enabled, onClick = onClick), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        LabText(text, color, 13.5.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SmallButton(c: LabColors, text: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val color = when { !enabled -> c.text3; danger -> c.bad; else -> c.accent }
    Box(modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        LabText(text, color, 13.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Field(c: LabColors, value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, secret: Boolean = false, number: Boolean = false) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.cardAlt).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
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
