package com.example.ui.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ai.AiAgentConfig
import com.example.ai.GeminiModelCatalog
import com.example.ai.ModelInfo
import com.example.ui.theme.AppTheme

private val Ok = Color(0xFF34D399)
private val Bad = Color(0xFFF87171)

@Composable
fun AiAgentSetupDialog(
    config: AiAgentConfig,
    isTestingKey: Boolean,
    testKeyStatus: String?,
    onSaveApiKey: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleAutoVoice: (Boolean) -> Unit,
    onTestKey: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKeyInput by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val c = AppTheme.colors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .wrapContentHeight()
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = c.surfaceCard),
            border = BorderStroke(1.dp, c.borderSubtle)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI Agent", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.textPrimary)
                        Text("Gemini co-pilot settings", fontSize = 12.sp, color = c.textSecondary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = c.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(color = c.borderSubtle)

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionHeader("API key")
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste your AI Studio key (AIza…)", fontSize = 13.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp),
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Show key",
                                        tint = c.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { clipboardManager.getText()?.text?.let { apiKeyInput = it.trim() } }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = c.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = c.primary,
                            unfocusedBorderColor = c.borderSubtle,
                            focusedContainerColor = c.surfaceInput,
                            unfocusedContainerColor = c.surfaceInput
                        ),
                        singleLine = true
                    )
                    KeyStatusRow(
                        isTesting = isTestingKey,
                        status = testKeyStatus,
                        hasSavedKey = config.apiKey.isNotBlank(),
                        canTest = apiKeyInput.isNotBlank() && !isTestingKey,
                        onTest = { onTestKey(apiKeyInput) }
                    )

                    Spacer(Modifier.height(6.dp))
                    SectionHeader("Model")
                    val (flash, pro) = GeminiModelCatalog.AVAILABLE_MODELS.partition { !it.isPro }
                    GroupLabel("Flash · fastest replies")
                    flash.forEach { ModelOption(it, config.model == it.id) { onSelectModel(it.id) } }
                    GroupLabel("Pro · deeper reasoning")
                    pro.forEach { ModelOption(it, config.model == it.id) { onSelectModel(it.id) } }

                    Spacer(Modifier.height(6.dp))
                    SectionHeader("Voice")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, c.borderSubtle, RoundedCornerShape(14.dp))
                            .toggleable(
                                value = config.autoVoiceEnabled,
                                role = Role.Switch,
                                onValueChange = onToggleAutoVoice
                            )
                            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Read replies aloud", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
                            Text("Speaks each answer when it arrives", fontSize = 11.5.sp, color = c.textMuted)
                        }
                        // The row handles the click.
                        Switch(
                            checked = config.autoVoiceEnabled,
                            onCheckedChange = null,
                            modifier = Modifier.scale(0.85f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = c.primary,
                                checkedBorderColor = c.primary,
                                uncheckedThumbColor = c.textMuted,
                                uncheckedTrackColor = c.surfaceCard,
                                uncheckedBorderColor = c.borderSubtle
                            )
                        )
                    }
                }

                HorizontalDivider(color = c.borderSubtle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, c.borderSubtle)
                    ) {
                        Text("Cancel", color = c.textPrimary, fontWeight = FontWeight.Medium)
                    }
                    Button(
                        onClick = {
                            onSaveApiKey(apiKeyInput)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1.6f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
                    ) {
                        Text("Save and activate", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = AppTheme.colors.textSecondary
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, fontSize = 12.sp, color = AppTheme.colors.textMuted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun KeyStatusRow(
    isTesting: Boolean,
    status: String?,
    hasSavedKey: Boolean,
    canTest: Boolean,
    onTest: () -> Unit
) {
    val c = AppTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val ok = status?.startsWith("✅") == true
        val (color, text) = when {
            isTesting -> c.textSecondary to "Checking key…"
            status != null -> (if (ok) Ok else Bad) to status.removePrefix("✅").removePrefix("❌").trim()
            hasSavedKey -> Ok to "Key saved"
            else -> c.textMuted to "No key yet"
        }
        if (isTesting) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.weight(1f))
        TextButton(onClick = onTest, enabled = canTest) {
            Text("Test key", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModelOption(model: ModelInfo, selected: Boolean, onSelect: () -> Unit) {
    val c = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.primary.copy(alpha = 0.08f) else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.primary else c.borderSubtle, RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.displayName, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = c.textPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = model.badge,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.surfacePill)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
            Spacer(Modifier.weight(1f))
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = c.primary, modifier = Modifier.size(20.dp))
            } else {
                Box(Modifier.size(20.dp).border(1.5.dp, c.borderMedium, CircleShape))
            }
        }
        Text(model.description, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ScoreMeter("Speed", model.speed)
            ScoreMeter("Reasoning", model.reasoning)
        }
    }
}

@Composable
private fun ScoreMeter(label: String, score: Int) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = c.textMuted)
        Spacer(Modifier.width(6.dp))
        repeat(5) { i ->
            Box(
                Modifier
                    .padding(end = 2.dp)
                    .size(width = 10.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(if (i < score) c.primary else c.borderMedium)
            )
        }
    }
}
