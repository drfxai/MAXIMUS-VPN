package com.example.ui.servers

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.InboundGenerationResult
import com.example.panels.ManagedPanel
import com.example.panels.ThreeXUiProtocol
import com.example.panels.ThreeXUiSecurity
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.ServerViewModel

/**
 * Mobile-optimized 3X-UI generator interface displayed in the Servers screen.
 * Provides:
 * 1. An elegant status bar identifying detected 3X-UI panel(s).
 * 2. Rapid VLESS TCP configuration button for instant 1-tap addition.
 * 3. Smooth animated dropdown menu/panel for custom transmission & security inbound generation.
 * 4. Dialog showing complete inbound JSON specification and client VLESS URI.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThreeXUiGeneratorCard(
    serverViewModel: ServerViewModel,
    panels: List<ManagedPanel>,
    selectedPanel: ManagedPanel?,
    isGenerating: Boolean,
    lastResult: InboundGenerationResult?,
    showResultDialog: Boolean,
    onConnectProfile: (String) -> Unit,
    onNavigateToPanels: () -> Unit = {},
    generationError: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var isMenuExpanded by remember { mutableStateOf(false) }
    var selectedProtocol by remember { mutableStateOf(ThreeXUiProtocol.REALITY) }
    var selectedSecurity by remember { mutableStateOf(ThreeXUiSecurity.REALITY) }
    var customPortText by remember { mutableStateOf("") }
    var showPanelSelectorDropdown by remember { mutableStateOf(false) }

    val hasInstalledPanel = panels.any { it.host.isNotBlank() && (it.password.isNotBlank() || it.apiToken.isNotBlank()) }
    val activePanel = selectedPanel?.takeIf { it.host.isNotBlank() && (it.password.isNotBlank() || it.apiToken.isNotBlank()) }
        ?: panels.firstOrNull { it.host.isNotBlank() && (it.password.isNotBlank() || it.apiToken.isNotBlank()) }

    // If no 3X-UI panel has been installed or configured yet, the generator section is INACTIVE.
    // Dummy configurations cannot be generated without an active server.
    if (!hasInstalledPanel || activePanel == null) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .testTag("3xui_generator_card_inactive"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = AppTheme.colors.surfaceCard
            ),
            border = BorderStroke(1.dp, AppTheme.colors.statusWarning.copy(alpha = 0.35f))
        ) {
            // One compact row: status, one-line explanation and the install action.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppTheme.colors.statusWarning.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        tint = AppTheme.colors.statusWarning,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "3X-UI panel not installed",
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Install it to create configs automatically",
                        color = AppTheme.colors.textMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onNavigateToPanels,
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("install_3xui_panel_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.colors.primary,
                        contentColor = AppTheme.colors.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Install",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        return
    }

    // Smooth dropdown rotation animation
    val arrowRotation by animateFloatAsState(
        targetValue = if (isMenuExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "arrow_rotation"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("3xui_generator_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = AppTheme.colors.surfaceCard
        ),
        border = BorderStroke(
            1.dp,
            if (isMenuExpanded) AppTheme.colors.primary.copy(alpha = 0.5f) else AppTheme.colors.borderSubtle
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            // Header Row: 3X-UI Status Badge & Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        AppTheme.colors.primary.copy(alpha = 0.85f),
                                        AppTheme.colors.primary.copy(alpha = 0.35f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "3X-UI INBOUND ENGINE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.primary,
                                letterSpacing = 1.1.sp,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AppTheme.colors.statusConnected)
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        val panelDisplayName = if (activePanel.name.isNotBlank()) {
                            if (activePanel.name.contains(activePanel.host)) {
                                activePanel.name
                            } else {
                                "${activePanel.name} • ${activePanel.host}"
                            }
                        } else {
                            "3X-UI • ${activePanel.host}"
                        }

                        Text(
                            text = panelDisplayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.5.sp
                        )
                    }
                }

                // Panel Switcher (if multiple panels exist)
                if (panels.size > 1) {
                    Box {
                        TextButton(
                            onClick = { showPanelSelectorDropdown = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("switch_panel_button")
                        ) {
                            Text(
                                text = "Switch",
                                fontSize = 11.sp,
                                color = AppTheme.colors.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        DropdownMenu(
                            expanded = showPanelSelectorDropdown,
                            onDismissRequest = { showPanelSelectorDropdown = false },
                            modifier = Modifier
                                .background(AppTheme.colors.surfaceElevated)
                                .border(1.dp, AppTheme.colors.borderSubtle)
                        ) {
                            panels.forEach { panel ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                panel.name.ifBlank { panel.host },
                                                fontWeight = FontWeight.Bold,
                                                color = AppTheme.colors.textPrimary,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                panel.host,
                                                color = AppTheme.colors.textSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        serverViewModel.setSelected3xuiPanel(panel)
                                        showPanelSelectorDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (generationError.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AppTheme.colors.statusError.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AppTheme.colors.statusError.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .testTag("3xui_generation_error")
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = AppTheme.colors.statusError,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = generationError,
                            color = AppTheme.colors.textPrimary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { serverViewModel.dismissGeneration3xuiError() },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = AppTheme.colors.textSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Action Row: Rapid VLESS TCP Button & Dropdown Trigger
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // QUICK CONFIG VLESS TCP BUTTON (Immediate 1-Click addition to Maximus VPN)
                Button(
                    onClick = {
                        serverViewModel.quickRecommendedConfig(
                            panel = activePanel,
                            onComplete = {
                                Toast.makeText(
                                    context,
                                    "⚡ Reality config created, verified and added to Maximus VPN!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    },
                    enabled = !isGenerating,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("quick_config_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (AppTheme.colors.isDark) Color(0xFF1E3A2F) else Color(0xFFD1FAE5),
                        contentColor = if (AppTheme.colors.isDark) Color(0xFF34D399) else Color(0xFF065F46)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AppTheme.colors.statusConnected
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Quick Config",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // CUSTOM GENERATION DROPDOWN TRIGGER
                OutlinedButton(
                    onClick = { isMenuExpanded = !isMenuExpanded },
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("toggle_3xui_menu_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isMenuExpanded) AppTheme.colors.primary else AppTheme.colors.borderSubtle
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isMenuExpanded) AppTheme.colors.primary.copy(alpha = 0.1f) else AppTheme.colors.surfaceElevated
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = if (isMenuExpanded) AppTheme.colors.primary else AppTheme.colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Custom Inbound",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMenuExpanded) AppTheme.colors.primary else AppTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(arrowRotation),
                        tint = if (isMenuExpanded) AppTheme.colors.primary else AppTheme.colors.textSecondary
                    )
                }
            }

            // SMOOTH ANIMATED EXPANDABLE MENU
            AnimatedVisibility(
                visible = isMenuExpanded,
                enter = expandVertically(animationSpec = tween(280)) + fadeIn(animationSpec = tween(280)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .background(
                            AppTheme.colors.surfaceElevated.copy(alpha = 0.6f),
                            RoundedCornerShape(12.dp)
                        )
                        .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    // SECTION 1: TRANSMISSION PROTOCOL SELECTOR
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TRANSMISSION PROTOCOL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textSecondary,
                            letterSpacing = 1.sp,
                            fontSize = 10.5.sp
                        )

                        Text(
                            text = selectedProtocol.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.primary,
                            fontSize = 11.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ThreeXUiProtocol.values().forEach { proto ->
                            val isSelected = selectedProtocol == proto
                            ProtocolSelectChip(
                                title = proto.displayName,
                                tag = proto.tag,
                                isSelected = isSelected,
                                icon = when (proto) {
                                    ThreeXUiProtocol.WEBSOCKET -> Icons.Default.Http
                                    ThreeXUiProtocol.XHTTP -> Icons.Default.Speed
                                    ThreeXUiProtocol.REALITY -> Icons.Default.Shield
                                    ThreeXUiProtocol.RAW -> Icons.Default.Dns
                                },
                                onClick = {
                                    selectedProtocol = proto
                                    if (proto == ThreeXUiProtocol.REALITY) {
                                        selectedSecurity = ThreeXUiSecurity.REALITY
                                    } else if (selectedSecurity == ThreeXUiSecurity.REALITY) {
                                        selectedSecurity = ThreeXUiSecurity.NONE
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedProtocol.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.textMuted,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // SECTION 2: SECURITY TYPE SELECTOR
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SECURITY TYPE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textSecondary,
                            letterSpacing = 1.sp,
                            fontSize = 10.5.sp
                        )

                        Text(
                            text = selectedSecurity.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.primary,
                            fontSize = 11.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ThreeXUiSecurity.values().forEach { sec ->
                            val isSelected = selectedSecurity == sec
                            SecuritySelectChip(
                                title = sec.displayName,
                                isSelected = isSelected,
                                icon = when (sec) {
                                    ThreeXUiSecurity.NONE -> Icons.Default.Close
                                    ThreeXUiSecurity.TLS -> Icons.Default.Security
                                    ThreeXUiSecurity.REALITY -> Icons.Default.Shield
                                },
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    selectedSecurity = sec
                                    if (sec == ThreeXUiSecurity.REALITY && selectedProtocol != ThreeXUiProtocol.REALITY) {
                                        selectedProtocol = ThreeXUiProtocol.REALITY
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedSecurity.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.textMuted,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Optional Port Input
                    OutlinedTextField(
                        value = customPortText,
                        onValueChange = { if (it.length <= 5 && it.all { c -> c.isDigit() }) customPortText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_inbound_port_input"),
                        label = { Text("Listening Port (Optional, auto if blank)", fontSize = 11.sp) },
                        placeholder = { Text("e.g. 24890", fontSize = 11.sp, color = AppTheme.colors.textMuted) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppTheme.colors.surfaceCard,
                            unfocusedContainerColor = AppTheme.colors.surfaceCard,
                            focusedBorderColor = AppTheme.colors.primary,
                            unfocusedBorderColor = AppTheme.colors.borderSubtle,
                            focusedTextColor = AppTheme.colors.textPrimary,
                            unfocusedTextColor = AppTheme.colors.textPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // ONE-CLICK GENERATE & ADD BUTTON
                    Button(
                        onClick = {
                            val parsedPort = customPortText.toIntOrNull()?.takeIf { it in 1..65535 }
                            serverViewModel.generateAndAdd3xuiConfig(
                                panel = activePanel,
                                protocol = selectedProtocol,
                                security = selectedSecurity,
                                customPort = parsedPort,
                                onComplete = {
                                    isMenuExpanded = false
                                    Toast.makeText(
                                        context,
                                        "Configuration created and added to Maximus VPN!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        },
                        enabled = !isGenerating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("generate_and_add_inbound_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.colors.primary,
                            contentColor = AppTheme.colors.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = AppTheme.colors.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Generating Inbound & Client Settings...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "1-Click Generate & Add to VPN",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }

    // GENERATION RESULT & CODE PREVIEW DIALOG
    if (showResultDialog && lastResult != null) {
        InboundResultDialog(
            result = lastResult,
            onDismiss = { serverViewModel.dismissGeneratedDialog() },
            onConnect = {
                onConnectProfile(lastResult.profile.id)
                serverViewModel.dismissGeneratedDialog()
            }
        )
    }
}

@Composable
private fun ProtocolSelectChip(
    title: String,
    tag: String,
    isSelected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) AppTheme.colors.primary.copy(alpha = 0.15f) else AppTheme.colors.surfaceCard,
        border = BorderStroke(
            1.dp,
            if (isSelected) AppTheme.colors.primary else AppTheme.colors.borderSubtle
        ),
        modifier = Modifier.testTag("protocol_chip_$tag")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) AppTheme.colors.primary else AppTheme.colors.textSecondary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) AppTheme.colors.primary else AppTheme.colors.textPrimary
            )
            if (isSelected) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AppTheme.colors.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun SecuritySelectChip(
    title: String,
    isSelected: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) AppTheme.colors.primary.copy(alpha = 0.15f) else AppTheme.colors.surfaceCard,
        border = BorderStroke(
            1.dp,
            if (isSelected) AppTheme.colors.primary else AppTheme.colors.borderSubtle
        ),
        modifier = modifier.testTag("security_chip_$title")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) AppTheme.colors.primary else AppTheme.colors.textSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) AppTheme.colors.primary else AppTheme.colors.textPrimary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun InboundResultDialog(
    result: InboundGenerationResult,
    onDismiss: () -> Unit,
    onConnect: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showInboundJson by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = AppTheme.colors.surfaceElevated,
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.statusConnected.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AppTheme.colors.statusConnected,
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Configuration Added!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Added to Maximus VPN Proxy Nodes",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Node Summary Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
                    border = BorderStroke(1.dp, AppTheme.colors.borderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = result.serverRemark,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary,
                                fontSize = 13.5.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AppTheme.colors.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "${result.protocol.tag} • ${result.security.displayName}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Endpoint: ${result.panelHost}:${result.port}",
                            fontSize = 11.5.sp,
                            color = AppTheme.colors.textSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "UUID: ${result.uuid}",
                            fontSize = 10.5.sp,
                            color = AppTheme.colors.textMuted,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = result.statusMessage,
                            fontSize = 11.sp,
                            color = if (result.publishedToPanel) AppTheme.colors.statusConnected else AppTheme.colors.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Client VLESS URI with Copy Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CLIENT VLESS URI",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textSecondary,
                        fontSize = 10.sp
                    )

                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(result.clientUri))
                            Toast.makeText(context, "VLESS URI copied!", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("copy_client_uri_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = AppTheme.colors.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy URI", fontSize = 11.sp, color = AppTheme.colors.primary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppTheme.colors.surfaceCard, RoundedCornerShape(8.dp))
                        .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = result.clientUri,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppTheme.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Toggle Inbound Settings JSON
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showInboundJson = !showInboundJson }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showInboundJson) "Hide 3X-UI Inbound JSON" else "View 3X-UI Inbound Settings JSON",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.primary
                    )
                    Icon(
                        imageVector = if (showInboundJson) Icons.Default.Close else Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = AppTheme.colors.primary
                    )
                }

                if (showInboundJson) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                            .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = result.inboundJson,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF93C5FD)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("connect_generated_profile_button")
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Connect Now", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Close", fontSize = 12.5.sp, color = AppTheme.colors.textSecondary)
            }
        }
    )
}
