package com.example.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.ChatMessage
import com.example.ai.MessageSender
import com.example.ai.ToolExecutionResult
import com.example.ai.UsageMode
import com.example.ui.theme.AppTheme
import java.util.Locale

enum class AiPageTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CHAT("Co-Pilot", Icons.Default.ChatBubble),
    MODES("Modes", Icons.Default.Tune),
    DIAGNOSTICS("Diagnostics", Icons.Default.Troubleshoot)
}

@Composable
fun AiAgentScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: com.example.ai.AiAgentViewModel = viewModel()
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(AiPageTab.CHAT) }

    // Dialogs
    if (uiState.showSetupDialog) {
        AiAgentSetupDialog(
            config = config,
            isTestingKey = uiState.isTestingKey,
            testKeyStatus = uiState.testKeyStatus,
            onSaveApiKey = { viewModel.setApiKey(it) },
            onSelectModel = { viewModel.setModel(it) },
            onToggleAutoVoice = { viewModel.setAutoVoice(it) },
            onTestKey = { viewModel.testApiKey(it) },
            onDismiss = { viewModel.openSetupDialog(false) }
        )
    }

    if (uiState.showTokenStatsModal) {
        AiTokenTrackerModal(
            config = config,
            onResetTokens = { viewModel.resetTokens() },
            onDismiss = { viewModel.openTokenStatsModal(false) }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
    ) {
        // Top Bar Header (No nested scaffold - avoids double insets and gaps)
        Surface(
            color = AppTheme.colors.surfaceCard,
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, AppTheme.colors.borderSubtle)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Mobile-Optimized Top Bar Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Identity
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            AppTheme.colors.primary.copy(alpha = 0.25f),
                                            AppTheme.colors.primary.copy(alpha = 0.08f)
                                        )
                                    )
                                )
                                .border(1.dp, AppTheme.colors.primary.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Maximus AI",
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Maximus AI",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = AppTheme.colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Surface(
                                    color = if (config.apiKey.isNotBlank()) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = if (config.apiKey.isNotBlank()) "ACTIVE" else "NO KEY",
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Autonomous Co-Pilot",
                                fontSize = 10.5.sp,
                                color = AppTheme.colors.textSecondary,
                                maxLines = 1
                            )
                        }
                    }

                    // Right Controls: Sleek Token Pill & Settings
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        // Sleek Token Pill with Bolt icon and clear text
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { viewModel.openTokenStatsModal(true) },
                            color = AppTheme.colors.primary.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.primary.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Tokens",
                                    tint = AppTheme.colors.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${formatCompactTokens(config.lifetimeTokens)} Tkn",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = { viewModel.openSetupDialog(true) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "AI Settings",
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Mobile-Optimized Tab Row
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = AppTheme.colors.primary,
                    divider = {},
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                            color = AppTheme.colors.primary,
                            height = 2.5.dp
                        )
                    }
                ) {
                    AiPageTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Tab(
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.title,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isSelected) AppTheme.colors.primary else AppTheme.colors.textSecondary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = tab.title,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AppTheme.colors.primary else AppTheme.colors.textSecondary
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        // Tab Body Content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                AiPageTab.CHAT -> AiChatTabContent(
                    viewModel = viewModel,
                    uiState = uiState,
                    config = config,
                    isSpeaking = isSpeaking,
                    isListening = isListening,
                    onOpenSetup = { viewModel.openSetupDialog(true) }
                )
                AiPageTab.MODES -> AiModesTabContent(
                    config = config,
                    onApplyMode = { mode ->
                        viewModel.applyUsageMode(mode)
                        selectedTab = AiPageTab.CHAT
                    }
                )
                AiPageTab.DIAGNOSTICS -> AiDiagnosticsTabContent(
                    onRunDiagnostics = {
                        viewModel.runDiagnostics()
                        selectedTab = AiPageTab.CHAT
                    },
                    onReviveCleanIp = { isp ->
                        viewModel.sendMessage("Revive my active configuration using a Clean IP for $isp operator.")
                        selectedTab = AiPageTab.CHAT
                    }
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// TAB 1: AI CHAT CONTENT (GAP-FREE, CLEAN DOCK)
// ------------------------------------------------------------------------------------------------
@Composable
fun AiChatTabContent(
    viewModel: com.example.ai.AiAgentViewModel,
    uiState: com.example.ai.AiAgentUiState,
    config: com.example.ai.AiAgentConfig,
    isSpeaking: Boolean,
    isListening: Boolean,
    onOpenSetup: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var textInput by remember { mutableStateOf("") }
    val hasAttachment = uiState.attachedBitmap != null || uiState.attachedFileContent != null

    // Auto-scroll on new message
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.attachImage(context, it) }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.attachFile(context, it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Warning Banner if API Key is missing
        if (config.apiKey.isBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenSetup() },
                color = Color(0xFF331414),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD32F2F))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Key",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Gemini API Key Required",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Tap here to configure key & unlock autonomous control",
                                fontSize = 10.sp,
                                color = Color(0xFFFFCDD2)
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFFD32F2F),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "SET KEY",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Suggested Quick Action Prompt Chips (Horizontally Scrollable)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val suggestions = listOf(
                "🎮 Gaming Setup" to "Configure my VPN for Gaming Mode with lowest ping and UDP direct.",
                "🎬 4K Cinema" to "Optimize my connection for 4K streaming and unblock Netflix/YouTube.",
                "🔍 Deep Diagnostic" to "Diagnose my current connection: check real logs, ping latency, and DNS health.",
                "🛡️ Clean IP Revival" to "Revive my active configuration using a Clean IP to bypass filtering.",
                "🤖 AI Tasks" to "Configure AI Tasks mode for direct OpenAI and Gemini access."
            )
            suggestions.forEach { (label, prompt) ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppTheme.colors.surfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
                    modifier = Modifier.clickable(enabled = !uiState.isProcessing) { viewModel.sendMessage(prompt) }
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.5.dp)
                    )
                }
            }
        }

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                ChatMessageBubble(
                    message = message,
                    isSpeaking = isSpeaking,
                    onToggleVoice = { viewModel.toggleVoiceSpeaking(message.text) },
                    onCopyText = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Maximus AI", message.text))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            if (uiState.isProcessing) {
                item {
                    AgentTypingIndicator()
                }
            }
        }

        // Bottom Input Dock (Directly aligned above bottom bar / keyboard with 0 gap)
        Surface(
            color = AppTheme.colors.surfaceCard,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, AppTheme.colors.borderSubtle)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                // Attachment preview if any
                AnimatedVisibility(visible = hasAttachment) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        color = AppTheme.colors.surfaceElevated
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                if (uiState.attachedBitmap != null) {
                                    Image(
                                        bitmap = uiState.attachedBitmap!!.asImageBitmap(),
                                        contentDescription = "Attached Image",
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = "File",
                                        tint = AppTheme.colors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = uiState.attachedFileName ?: "Attached Image",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AppTheme.colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { viewModel.clearAttachment() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = AppTheme.colors.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Controls Row: Image, Mic, Text Field, Send
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment (Image Picker)
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Attach Image",
                            tint = AppTheme.colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Voice Dictation Button
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = if (isListening) 1.2f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "scale"
                    )

                    IconButton(
                        onClick = {
                            if (isListening) {
                                viewModel.stopVoiceInput()
                            } else {
                                viewModel.startVoiceInput()
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .scale(if (isListening) scale else 1f)
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = if (isListening) Color(0xFFE53935) else AppTheme.colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Text Field
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        placeholder = { Text("Ask Maximus AI...", fontSize = 12.5.sp) },
                        maxLines = 3,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.colors.primary,
                            unfocusedBorderColor = AppTheme.colors.borderSubtle
                        )
                    )

                    // Send Button
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank() || uiState.attachedBitmap != null || uiState.attachedFileContent != null) {
                                viewModel.sendMessage(textInput)
                                textInput = ""
                            }
                        },
                        enabled = !uiState.isProcessing && (textInput.isNotBlank() || uiState.attachedBitmap != null || uiState.attachedFileContent != null),
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (textInput.isNotBlank() || hasAttachment) AppTheme.colors.primary else AppTheme.colors.surfaceElevated
                            )
                    ) {
                        if (uiState.isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (textInput.isNotBlank() || hasAttachment) Color.White else AppTheme.colors.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// TAB 2: AI WORKLOAD MODES (MOBILE OPTIMIZED)
// ------------------------------------------------------------------------------------------------
@Composable
fun AiModesTabContent(
    config: com.example.ai.AiAgentConfig,
    onApplyMode: (UsageMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section Header
        Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
            Text(
                text = "Autonomous Workload Tuning",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.textPrimary
            )
            Text(
                text = "One-tap network optimization for gaming, streaming, AI, and downloads.",
                fontSize = 11.5.sp,
                color = AppTheme.colors.textSecondary
            )
        }

        UsageMode.entries.forEach { mode ->
            val isActive = config.activeUsageMode == mode
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) AppTheme.colors.primary.copy(alpha = 0.12f) else AppTheme.colors.surfaceCard
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isActive) AppTheme.colors.primary else AppTheme.colors.borderSubtle
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(mode.emoji, fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = mode.displayName,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.textPrimary
                                )
                                if (isActive) {
                                    Text(
                                        text = "● ACTIVE NOW",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.colors.primary
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = { onApplyMode(mode) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isActive) AppTheme.colors.surfaceElevated else AppTheme.colors.primary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = if (isActive) "Re-Tune" else "Activate",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isActive) AppTheme.colors.textPrimary else Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = mode.description,
                        fontSize = 11.sp,
                        color = AppTheme.colors.textSecondary,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Mode parameter chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        when (mode) {
                            UsageMode.GAMING -> {
                                ModeParamChip("MTU: 1400")
                                ModeParamChip("DNS: 1.1.1.1")
                                ModeParamChip("UDP: Direct")
                                ModeParamChip("Ping: <60ms")
                            }
                            UsageMode.STREAMING -> {
                                ModeParamChip("Buffer: 16KB")
                                ModeParamChip("DNS: Google 8.8.8.8")
                                ModeParamChip("Desync: Balanced")
                                ModeParamChip("4K Unblocked")
                            }
                            UsageMode.AI_TASKS -> {
                                ModeParamChip("AI services")
                                ModeParamChip("Zero Packet Loss")
                                ModeParamChip("Direct TLS")
                                ModeParamChip("MTU: 1480")
                            }
                            UsageMode.YOUTUBE -> {
                                ModeParamChip("QUIC / UDP Stream")
                                ModeParamChip("Google CDN Edge")
                                ModeParamChip("Fast Buffering")
                            }
                            UsageMode.DOWNLOADING -> {
                                ModeParamChip("Multi-TCP Window")
                                ModeParamChip("Max Throughput")
                                ModeParamChip("MTU: 1500")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModeParamChip(label: String) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = AppTheme.colors.surfaceElevated
    ) {
        Text(
            text = label,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.5.dp)
        )
    }
}

// ------------------------------------------------------------------------------------------------
// TAB 3: AI DIAGNOSTICS & LOG INSPECTOR (MOBILE OPTIMIZED)
// ------------------------------------------------------------------------------------------------
@Composable
fun AiDiagnosticsTabContent(
    onRunDiagnostics: () -> Unit,
    onReviveCleanIp: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Hero Scan Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.primary.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI Autonomous Diagnostic",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Real-time log analysis, DNS latency, and censorship bypass evaluation.",
                            fontSize = 11.sp,
                            color = AppTheme.colors.textSecondary,
                            lineHeight = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AppTheme.colors.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Troubleshoot,
                            contentDescription = "Diagnostics",
                            tint = AppTheme.colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onRunDiagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = "Run", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "⚡ Execute Diagnostic Scan",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // ISP Clean IP Revival Shortcuts
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Clean IP",
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Anti-Censorship Clean IP Revival",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "If your node is filtered on mobile operators, tap to revive with a clean edge IP:",
                    fontSize = 11.sp,
                    color = AppTheme.colors.textSecondary,
                    lineHeight = 14.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                val operators = listOf(
                    "📱 Hamrah-e Aval (MCI)" to "MCI",
                    "🟡 MTN Irancell" to "IRANCELL",
                    "🟣 Rightel" to "RIGHTEL",
                    "🏢 Mokhaberat / TCI" to "TCI",
                    "🌐 Global Anycast" to "GLOBAL"
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    operators.forEach { (label, ispCode) ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onReviveCleanIp(ispCode) },
                            color = AppTheme.colors.surfaceElevated
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppTheme.colors.textPrimary
                                )
                                Text(
                                    text = "Revive Node ➔",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// CHAT BUBBLE WITH RICH MARKDOWN & CLEAN RENDERING
// ------------------------------------------------------------------------------------------------
@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    isSpeaking: Boolean,
    onToggleVoice: () -> Unit,
    onCopyText: () -> Unit
) {
    val isUser = message.sender == MessageSender.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Agent",
                    tint = AppTheme.colors.primary,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.86f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 14.dp,
                    topEnd = 14.dp,
                    bottomStart = if (isUser) 14.dp else 3.dp,
                    bottomEnd = if (isUser) 3.dp else 14.dp
                ),
                color = if (isUser) AppTheme.colors.primary else AppTheme.colors.surfaceCard,
                border = if (!isUser) androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle) else null
            ) {
                Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
                    // Render image if present
                    if (message.imageBitmap != null) {
                        Image(
                            bitmap = message.imageBitmap.asImageBitmap(),
                            contentDescription = "Uploaded image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Render attached file badge if present
                    if (message.fileName != null) {
                        Surface(
                            color = if (isUser) Color.White.copy(alpha = 0.15f) else AppTheme.colors.surfaceElevated,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = "File",
                                    tint = if (isUser) Color.White else AppTheme.colors.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = message.fileName,
                                    fontSize = 10.sp,
                                    color = if (isUser) Color.White else AppTheme.colors.textPrimary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Render tool action execution cards if any
                    if (message.toolExecutions.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            message.toolExecutions.forEach { tool ->
                                ToolExecutionCard(tool)
                            }
                        }
                    }

                    // Main Text with Rich Markdown (bold, clean formatting)
                    FormattedChatMessageText(
                        text = message.text,
                        isUser = isUser
                    )
                }
            }

            // Action row beneath Agent Message
            if (!isUser) {
                Row(
                    modifier = Modifier.padding(top = 2.dp, start = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleVoice,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = "Speak Text",
                            tint = if (isSpeaking) AppTheme.colors.primary else AppTheme.colors.textSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    IconButton(
                        onClick = onCopyText,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = AppTheme.colors.textSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    if (message.promptTokens > 0 || message.candidateTokens > 0) {
                        Text(
                            text = "⚡ ${message.promptTokens + message.candidateTokens} tokens",
                            fontSize = 9.sp,
                            color = AppTheme.colors.textMuted,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FormattedChatMessageText(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    val annotatedString = remember(text) {
        buildAnnotatedString {
            val lines = text.trimIndent().split("\n")
            lines.forEachIndexed { lineIdx, rawLine ->
                var currentIndex = 0
                val regex = Regex("""\*\*(.*?)\*\*""")
                val matches = regex.findAll(rawLine)

                for (match in matches) {
                    val start = match.range.first
                    val end = match.range.last + 1
                    val boldText = match.groupValues[1]

                    if (start > currentIndex) {
                        append(rawLine.substring(currentIndex, start))
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(boldText)
                    }
                    currentIndex = end
                }
                if (currentIndex < rawLine.length) {
                    append(rawLine.substring(currentIndex))
                }
                if (lineIdx < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }

    Text(
        text = annotatedString,
        color = if (isUser) Color.White else AppTheme.colors.textPrimary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = modifier
    )
}

@Composable
fun ToolExecutionCard(tool: ToolExecutionResult) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp)),
        color = if (tool.success) Color(0xFF132A13) else Color(0xFF331010),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (tool.success) Color(0xFF2E7D32) else Color(0xFFC62828)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (tool.success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = "Tool Status",
                tint = if (tool.success) Color(0xFF81C784) else Color(0xFFE57373),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = tool.summary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun AgentTypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Agent",
                tint = AppTheme.colors.primary,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AppTheme.colors.surfaceCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = AppTheme.colors.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Analyzing network & executing actions...",
                    fontSize = 11.5.sp,
                    color = AppTheme.colors.textSecondary
                )
            }
        }
    }
}

private fun formatCompactTokens(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
        else -> tokens.toString()
    }
}
