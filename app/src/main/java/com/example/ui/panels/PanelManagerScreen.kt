package com.example.ui.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.panels.servers.InstallTabHost
import com.example.ui.panels.servers.ServersEffects
import com.example.ui.panels.servers.ServersOverlay
import com.example.ui.panels.servers.ServersTabHost
import com.example.ui.panels.servers.ServersViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.panels.CleanIpOptimizer
import com.example.panels.CloudflareTokenHelper
import com.example.panels.ManagedPanel
import com.example.panels.PanelCatalog
import com.example.panels.PanelStore
import com.example.panels.PanelType

private object PanelColors {
    val Background = Color(0xFF070D18)
    val CardBg = Color(0xFF0C192E)
    val CardBorder = Color(0xFF1B2E4B)
    val PrimaryBlue = Color(0xFF1A6CFF)
    val CyanAccent = Color(0xFF38BDF8)
    val SuccessGreen = Color(0xFF00E676)
    val SuccessGreenBg = Color(0xFF062B1D)
    val SuccessGreenBorder = Color(0xFF0E5C3C)
    val TextMuted = Color(0xFF8E9BAE)
    val TextDim = Color(0xFF64748B)
    val TerminalBg = Color(0xFF050D1A)
}

data class CleanIpDisplay(
    val ip: String,
    val country: String,
    val flagEmoji: String,
    val provider: String = "Cloudflare",
    val operatorTag: String = "MCI / Irancell",
    val qualityGrade: String = "A+",
    val latencyMs: Long,
    val jitterMs: Long = 4L,
    val lossPercent: Double = 0.0,
    val isClean: Boolean = true,
    val downloadSpeedKbps: Long = 0L,
    val formattedSpeed: String = "--",
    val verificationStatus: String = "Verified",
    val originVerified: Boolean = false
)

@Composable
fun PanelManagerScreen(
    viewModel: PanelManagerViewModel,
    onNavigateBack: () -> Unit,
    onOpenSettings: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savedProfiles by viewModel.savedProfiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedPanelId by remember { mutableStateOf<String?>(null) }
    var selectedWorkerTemplate by remember { mutableStateOf(PanelCatalog.cloudflareTemplates.first().id) }
    var selectedRegion by remember { mutableStateOf("Iran 🇮🇷 (All Operators)") }
    var regionDropdownExpanded by remember { mutableStateOf(false) }
    var scanPort by remember { mutableStateOf("443") }
    var accessInfoExpanded by remember { mutableStateOf(true) }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmRemovePanel by remember { mutableStateOf<ManagedPanel?>(null) }
    var showReviveDialogForEdge by remember { mutableStateOf<CleanIpOptimizer.Result?>(null) }
    var selectedReviveTargetProfileId by remember { mutableStateOf<String?>(null) }
    var reviveAsClone by remember { mutableStateOf(true) }
    var cleanIpSortOption by remember { mutableIntStateOf(0) } // 0: Quality, 1: Latency, 2: Jitter
    // Tunnel tab is UI-only for now. Nothing here is persisted, installed or executed.
    var tunnelIranHost by remember { mutableStateOf("") }
    var tunnelIranPort by remember { mutableStateOf("22") }
    var tunnelIranPassword by remember { mutableStateOf("") }
    var tunnelPasswordVisible by remember { mutableStateOf(false) }

    // Forms
    var cfToken by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var cfEmail by remember { mutableStateOf("") }
    var cfPassword by remember { mutableStateOf("") }
    var parentToken by remember { mutableStateOf("") }

    val copyWithToast: (String, String) -> Unit = { text, label ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(context, "Cannot open $url", Toast.LENGTH_SHORT).show() }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearToken() }
    }

    // Determine current panel for detail view (Screen 4)
    val activePanel = state.panels.firstOrNull { it.id == selectedPanelId }

    // My Servers, Install Center and their full-screen pages (Add server, install wizard, server page).
    val serversVm: ServersViewModel = viewModel()
    val serversState by serversVm.state.collectAsStateWithLifecycle()
    ServersEffects(serversVm) { viewModel.reloadPanels() }
    if (ServersOverlay(
            serversVm, serversState,
            onOpenPanel = { id -> viewModel.reloadPanels(); selectedTab = 0; selectedPanelId = id },
            onInstallCenter = { selectedPanelId = null; selectedTab = 1 }
        )
    ) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // TOP APP BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (selectedPanelId != null) {
                    selectedPanelId = null
                } else {
                    onNavigateBack()
                }
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Panels",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Server + Cloudflare + Tunnel control center",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                onOpenSettings?.invoke() ?: run {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/drfxai/MAXIMUS-VPN/blob/main/docs/PANELS.md"))
                    )
                }
            }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // SEGMENTED TABS (Always visible at the top, just like in screenshot 4)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tabs = listOf("Servers", "Install", "Cloudflare", "Clean IP", "Tunnel")
            tabs.forEachIndexed { index, title ->
                val isSelected = (selectedPanelId != null && index == 0) || (selectedPanelId == null && selectedTab == index)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clickable {
                            selectedPanelId = null
                            selectedTab = index
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) PanelColors.PrimaryBlue else MaterialTheme.colorScheme.surface,
                    border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // SCROLLABLE CONTENT BODY
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // IF DETAIL VIEW IS SELECTED -> SCREEN 4
            if (selectedPanelId != null && activePanel != null) {
                InstalledPanelDetailView(
                    panel = activePanel,
                    accessInfoExpanded = accessInfoExpanded,
                    onToggleAccessInfo = { accessInfoExpanded = !accessInfoExpanded },
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    onCopy = copyWithToast,
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            Toast.makeText(context, "Cannot open browser: $url", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onUpdatePort = { newPort ->
                        viewModel.updatePanelPort(activePanel, newPort)
                        Toast.makeText(context, "Panel port updated to $newPort", Toast.LENGTH_SHORT).show()
                    },
                    onImportToVpn = {
                        if (activePanel.type == PanelType.BPB_WORKER) viewModel.importBpbToProfiles(activePanel)
                        else viewModel.importServerToProfiles(activePanel)
                    },
                    onOneClickReality = {
                        if (activePanel.type == PanelType.BPB_WORKER) viewModel.fixBpbProfiles(activePanel)
                        else viewModel.createQuickConfig(activePanel)
                    },
                    onDelete = { confirmRemovePanel = activePanel },
                    busy = state.busy,
                    statusText = state.status,
                    errorText = state.error
                )
            } else {
                // STAT CARDS (3 COLUMNS). Cloudflare carries its own header.
                if (selectedTab == 3 || selectedTab == 4) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (selectedTab) {
                        3 -> {
                            val scanningCount = if (state.busy) state.tested else 0
                            val cleanCount = state.edges.size
                            StatCard(Icons.Default.Language, PanelColors.CyanAccent, scanningCount.toString(), "Tested", Modifier.weight(1f))
                            StatCard(Icons.Default.Security, PanelColors.CyanAccent, state.reachable.toString(), "Reachable", Modifier.weight(1f))
                            StatCard(Icons.Default.CheckCircle, PanelColors.SuccessGreen, cleanCount.toString(), "Clean IPs", Modifier.weight(1f))
                        }
                        else -> {
                            StatCard(Icons.Default.Router, PanelColors.CyanAccent, if (tunnelIranHost.isBlank()) "Not set" else "Ready", "Iran server", Modifier.weight(1f))
                            StatCard(Icons.Default.Language, PanelColors.TextMuted, "Later", "Foreign server", Modifier.weight(1f))
                            StatCard(Icons.Default.Link, PanelColors.TextMuted, "UI only", "Tunnel", Modifier.weight(1f))
                        }
                    }
                }

                // TAB 0: MY SERVERS (Iran and abroad, with flags)
                if (selectedTab == 0) {
                    ServersTabHost(serversVm, serversState, onInstallCenter = { selectedTab = 1 })
                }

                // TAB 1: INSTALL CENTER
                if (selectedTab == 1) {
                    InstallTabHost(serversVm, serversState)
                }

                // TAB 2: CLOUDFLARE
                if (selectedTab == 2) {
                    CloudflareTab(
                        token = cfToken,
                        onTokenChange = { cfToken = it },
                        account = account,
                        onAccountChange = { account = it },
                        panels = state.panels.filter { it.type == PanelType.BPB_WORKER },
                        isBusy = state.busy,
                        statusText = state.status,
                        logs = state.logs,
                        newlyDeployedPanel = state.newlyDeployedPanel,
                        onDismissNewPanel = { viewModel.dismissNewlyDeployedPanel() },
                        onSignUp = { CloudflareTokenHelper.openSignUpUrl(context) },
                        onCreateToken = { CloudflareTokenHelper.openOneClickTokenUrl(context) },
                        onOpenDocs = { openUrl("https://github.com/bia-pain-bache/BPB-Worker-Panel") },
                        onPasteToken = { clipboard.getText()?.text?.trim()?.takeIf { it.isNotBlank() } },
                        onInstall = { token, accountId, username, password ->
                            viewModel.deployBpb(token, accountId, username, password)
                        },
                        onImportToVpn = { panel -> viewModel.importBpbToProfiles(panel) },
                        onFixBpb = { panel -> viewModel.fixBpbProfiles(panel) },
                        onOpenUrl = openUrl,
                        onCopy = { label, value -> copyWithToast(value, label) }
                    )
                }

                // TAB 3: CLEAN IP SCANNER
                if (selectedTab == 3) {
                    CleanIpScannerCard(
                        scanning = state.busy,
                        scanPaused = state.scanPaused,
                        tested = state.tested,
                        scanTotal = state.scanTotal,
                        currentIp = state.currentScanIp,
                        savedProfiles = savedProfiles,
                        selectedProfileId = state.selectedTargetProfileId,
                        onSelectProfile = { viewModel.setSelectedTargetProfileId(it) },
                        originHealth = state.originHealth,
                        originChecking = state.originChecking,
                        onCheckOrigin = { p -> viewModel.checkOriginHealth(p) },
                        selectedRegion = selectedRegion,
                        regionDropdownExpanded = regionDropdownExpanded,
                        onRegionDropdownToggle = { regionDropdownExpanded = !regionDropdownExpanded },
                        onSelectRegion = {
                            selectedRegion = it
                            regionDropdownExpanded = false
                        },
                        customSubnet = state.customSubnet,
                        onCustomSubnetChange = { viewModel.setCustomSubnet(it) },
                        speedTestEnabled = state.speedTestEnabled,
                        onSpeedTestToggle = { viewModel.setSpeedTestEnabled(it) },
                        scanPort = scanPort,
                        onScanPortChange = { scanPort = it.filter(Char::isDigit) },
                        onStartScan = {
                            viewModel.scanEdges(selectedRegion, scanPort.toIntOrNull() ?: 443, state.customSubnet, state.speedTestEnabled)
                        },
                        onPauseToggle = { viewModel.toggleScanPause() },
                        onStopScan = { viewModel.cancel() }
                    )

                    // Auto-Optimize Compatible Configurations Banner
                    if (state.edges.isNotEmpty() && savedProfiles.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, PanelColors.CyanAccent.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Bolt,
                                            contentDescription = null,
                                            tint = Color(0xFF38BDF8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Optimize Compatible Configurations",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Distribute ${state.edges.size} verified clean Cloudflare IPs across your active CDN configurations to bypass ISP throttling. (Incompatible & offline configs protected).",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Button(
                                    onClick = { viewModel.batchReviveAllBlockedProfiles() },
                                    colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Optimize All ⚡", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Clean IP Results (${state.edges.size})",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (state.edges.isNotEmpty()) {
                                Text(
                                    text = "Sort by",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (state.edges.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOf("Quality", "Speed", "Latency", "Jitter").forEachIndexed { index, label ->
                                    val isSelected = cleanIpSortOption == index
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { cleanIpSortOption = index },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) PanelColors.PrimaryBlue.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(1.dp, if (isSelected) PanelColors.CyanAccent else MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isSelected) PanelColors.CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                softWrap = false,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.edges.isEmpty()) {
                        EmptyCleanIpCard(
                            isScanning = state.busy,
                            onStartScan = { viewModel.scanEdges(selectedRegion, scanPort.toIntOrNull() ?: 443, state.customSubnet, state.speedTestEnabled) }
                        )
                    } else {
                        val sortedEdges = when (cleanIpSortOption) {
                            1 -> state.edges.sortedByDescending { it.downloadSpeedKbps }
                            2 -> state.edges.sortedBy { it.medianMs }
                            3 -> state.edges.sortedBy { it.jitterMs }
                            else -> state.edges.sortedBy { it.score }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sortedEdges.forEach { edge ->
                                val item = CleanIpDisplay(
                                    ip = edge.ip,
                                    country = edge.country,
                                    flagEmoji = edge.flagEmoji,
                                    operatorTag = edge.operatorTag,
                                    qualityGrade = edge.qualityGrade,
                                    latencyMs = edge.medianMs,
                                    jitterMs = edge.jitterMs,
                                    lossPercent = edge.lossPercent,
                                    downloadSpeedKbps = edge.downloadSpeedKbps,
                                    formattedSpeed = edge.formattedSpeed,
                                    verificationStatus = edge.verificationStatus,
                                    originVerified = edge.originVerified
                                )
                                CleanIpResultItemCard(
                                    item = item,
                                    onRevive = {
                                        selectedReviveTargetProfileId = state.selectedTargetProfileId ?: savedProfiles.firstOrNull()?.id
                                        showReviveDialogForEdge = edge
                                    },
                                    onSave = {
                                        viewModel.applyEdge(edge, false)
                                        Toast.makeText(context, "Clean IP ${edge.ip} saved to configurations", Toast.LENGTH_SHORT).show()
                                    },
                                    onCopy = {
                                        copyWithToast(edge.ip, "Clean IP")
                                    }
                                )
                            }
                        }
                    }
                }

                // TAB 4: TUNNEL — UI scaffold only. Real tunnel provisioning will be implemented later.
                if (selectedTab == 4) {
                    TunnelSetupCard(
                        iranHost = tunnelIranHost,
                        onIranHostChange = { tunnelIranHost = it.trim() },
                        iranPort = tunnelIranPort,
                        onIranPortChange = { tunnelIranPort = it.filter(Char::isDigit).take(5) },
                        iranPassword = tunnelIranPassword,
                        onIranPasswordChange = { tunnelIranPassword = it },
                        passwordVisible = tunnelPasswordVisible,
                        onTogglePasswordVisibility = { tunnelPasswordVisible = !tunnelPasswordVisible }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // MODAL DIALOGS
    confirmRemovePanel?.let { panel ->
        AlertDialog(
            onDismissRequest = { confirmRemovePanel = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Remove panel?", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("Remove saved configuration for ${panel.name}?", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(panel)
                    if (selectedPanelId == panel.id) selectedPanelId = null
                    confirmRemovePanel = null
                }) { Text("Remove", color = Color(0xFFFF5252)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemovePanel = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        )
    }

    state.generatedConfig?.let { gen ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissGeneratedConfig() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = PanelColors.SuccessGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Quick Config Created", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "VLESS Reality inbound created, verified on your server and imported to VPN Profiles:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    if (gen.note.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = gen.note,
                            color = if (gen.reachable) PanelColors.SuccessGreen else Color(0xFFFFB300),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("VLESS URI", gen.configUri))
                                Toast.makeText(context, "Copied VLESS URI to clipboard", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Text(
                            text = gen.configUri,
                            color = PanelColors.CyanAccent,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("VLESS URI", gen.configUri))
                        Toast.makeText(context, "Copied VLESS URI to clipboard", Toast.LENGTH_SHORT).show()
                        viewModel.dismissGeneratedConfig()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue)
                ) {
                    Text("Copy URI")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissGeneratedConfig() }) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // OPTIMIZE CONFIGURATION MODAL DIALOG
    showReviveDialogForEdge?.let { edge ->
        var selectedProfileId by remember(edge.ip) {
            mutableStateOf(selectedReviveTargetProfileId ?: savedProfiles.firstOrNull()?.id.orEmpty())
        }
        var profileDropdownOpen by remember { mutableStateOf(false) }

        val targetProfile = savedProfiles.firstOrNull { it.id == selectedProfileId }
            ?: savedProfiles.firstOrNull()

        AlertDialog(
            onDismissRequest = { showReviveDialogForEdge = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF0C2B4E), CircleShape)
                            .border(1.dp, PanelColors.CyanAccent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Optimize Configuration",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Bind verified Cloudflare edge to bypass ISP blocks",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Clean IP Info Banner
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF081C33),
                        border = BorderStroke(1.dp, Color(0xFF1E3A5F))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Clean IP: ${edge.ip}",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "${edge.operatorTag} • Speed: ${edge.formattedSpeed}",
                                    color = PanelColors.CyanAccent,
                                    fontSize = 11.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (edge.qualityGrade == "A+" || edge.qualityGrade == "A") PanelColors.SuccessGreenBg else Color(0xFF2A1C0A),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (edge.qualityGrade == "A+" || edge.qualityGrade == "A") PanelColors.SuccessGreenBorder else Color(0xFF854D0E),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Grade ${edge.qualityGrade}",
                                        color = if (edge.qualityGrade == "A+" || edge.qualityGrade == "A") PanelColors.SuccessGreen else Color(0xFFFBBF24),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${edge.medianMs} ms",
                                    color = PanelColors.SuccessGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }

                    if (savedProfiles.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF201215),
                            border = BorderStroke(1.dp, Color(0xFF5A222B))
                        ) {
                            Text(
                                text = "No VPN profiles found yet. Saving will create a new direct Clean IP profile for you.",
                                color = Color(0xFFFCA5A5),
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "Select VPN Profile to Optimize:",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Target Profile Dropdown
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .clickable { profileDropdownOpen = true },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = targetProfile?.name ?: "Select profile...",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = profileDropdownOpen,
                                onDismissRequest = { profileDropdownOpen = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            ) {
                                savedProfiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(p.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                                Text("${p.protocolType.name} • ${p.address}:${p.port}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp)
                                            }
                                        },
                                        onClick = {
                                            selectedProfileId = p.id
                                            viewModel.setSelectedTargetProfileId(p.id)
                                            profileDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        // Origin Health Warning if Down
                        if (state.originHealth is CleanIpOptimizer.OriginHealth.Down) {
                            val down = state.originHealth as CleanIpOptimizer.OriginHealth.Down
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF2C1014),
                                border = BorderStroke(1.dp, Color(0xFF7F1D1D))
                            ) {
                                Text(
                                    text = "⚠️ Origin Server Offline (HTTP ${down.httpCode ?: "Down"})\nThe backend for this configuration is not reachable. A clean IP cannot fix a shut down origin server or expired subscription.",
                                    color = Color(0xFFFCA5A5),
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        // Clone vs Overwrite selector
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { reviveAsClone = !reviveAsClone }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(if (reviveAsClone) PanelColors.PrimaryBlue else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))
                                    .border(1.dp, if (reviveAsClone) PanelColors.CyanAccent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (reviveAsClone) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(12.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Save as new clone: ⚡ [CF-Optimized] ${targetProfile?.name?.take(16) ?: ""}",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Copy Link Button
                    if (targetProfile != null) {
                        Surface(
                            onClick = {
                                val uri = viewModel.copyRevivedUri(targetProfile, edge)
                                copyWithToast(uri, "Optimized vless:// URL")
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0D2138),
                            border = BorderStroke(1.dp, Color(0xFF1D4ED8)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = PanelColors.CyanAccent, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy Optimized vless:// Config Link", color = PanelColors.CyanAccent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            if (targetProfile != null) {
                                viewModel.reviveProfile(targetProfile, edge, asClone = reviveAsClone, connectNow = true)
                            } else {
                                viewModel.applyEdge(edge, connect = true)
                            }
                            showReviveDialogForEdge = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Optimize & Connect", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            if (targetProfile != null) {
                                viewModel.reviveProfile(targetProfile, edge, asClone = reviveAsClone, connectNow = false)
                            } else {
                                viewModel.applyEdge(edge, connect = false)
                            }
                            showReviveDialogForEdge = null
                        }
                    ) {
                        Text("Save Only", color = PanelColors.CyanAccent, fontSize = 11.5.sp)
                    }
                    TextButton(onClick = { showReviveDialogForEdge = null }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
                    }
                }
            }
        )
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    iconColor: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(86.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SupportedAppCard(
    title: String,
    subtitle: String,
    logoType: String,
    isSelected: Boolean,
    hasCheckBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(115.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isSelected) PanelColors.CyanAccent else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                PanelBrandLogo(logoType, Modifier.size(32.dp))
                if (hasCheckBadge) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(PanelColors.SuccessGreenBg, CircleShape)
                            .border(1.dp, PanelColors.SuccessGreen, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = PanelColors.SuccessGreen,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            Column {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CleanIpScannerCard(
    scanning: Boolean,
    scanPaused: Boolean,
    tested: Int,
    scanTotal: Int,
    currentIp: String,
    savedProfiles: List<com.example.data.model.VlessProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String?) -> Unit,
    originHealth: CleanIpOptimizer.OriginHealth,
    originChecking: Boolean,
    onCheckOrigin: (com.example.data.model.VlessProfile) -> Unit,
    selectedRegion: String,
    regionDropdownExpanded: Boolean,
    onRegionDropdownToggle: () -> Unit,
    onSelectRegion: (String) -> Unit,
    customSubnet: String,
    onCustomSubnetChange: (String) -> Unit,
    speedTestEnabled: Boolean,
    onSpeedTestToggle: (Boolean) -> Unit,
    scanPort: String,
    onScanPortChange: (String) -> Unit,
    onStartScan: () -> Unit,
    onPauseToggle: () -> Unit,
    onStopScan: () -> Unit
) {
    var profileDropdownOpen by remember { mutableStateOf(false) }
    val currentSelectedProfile = savedProfiles.firstOrNull { it.id == selectedProfileId }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Title + Cyber Globe Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CFScanner Clean IP",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF0F2B48), RoundedCornerShape(4.dp))
                                .border(1.dp, PanelColors.CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v2ray / Xray",
                                color = PanelColors.CyanAccent,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Edge Subnet Scanner",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Scan Cloudflare edge subnets for unblocked Anycast IPs with low ping & real download throughput.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CyberGlobeCanvas(Modifier.size(72.dp))
            }

            // Architecture Notice (CFScanner principles)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF081B30),
                border = BorderStroke(1.dp, Color(0xFF163255))
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = PanelColors.CyanAccent,
                        modifier = Modifier.size(16.dp).padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clean IPs replace entry IPs to bypass ISP firewall blocks on active Cloudflare CDN/Worker backends. Note: Clean IPs cannot revive dead origin servers (HTTP 521), deleted Workers, or expired accounts.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // Target Profile Selector Row
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Target Configuration to Optimize:",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clickable { profileDropdownOpen = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = null,
                                    tint = PanelColors.CyanAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = currentSelectedProfile?.name ?: "🌐 General Cloudflare CDN Scan (No specific profile)",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = profileDropdownOpen,
                        onDismissRequest = { profileDropdownOpen = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "🌐 General Cloudflare CDN Scan (No profile)",
                                    color = PanelColors.CyanAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            },
                            onClick = {
                                onSelectProfile(null)
                                profileDropdownOpen = false
                            }
                        )
                        savedProfiles.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(p.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${p.protocolType.name} • ${p.transport.uppercase()} • ${p.address}:${p.port}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                    }
                                },
                                onClick = {
                                    onSelectProfile(p.id)
                                    profileDropdownOpen = false
                                }
                            )
                        }
                    }
                }
            }

            // Origin Health Status Card (if a profile is selected)
            if (currentSelectedProfile != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = when (originHealth) {
                        is CleanIpOptimizer.OriginHealth.Online -> Color(0xFF052B1E)
                        is CleanIpOptimizer.OriginHealth.Down -> Color(0xFF2C1014)
                        is CleanIpOptimizer.OriginHealth.Incompatible -> Color(0xFF2D1F08)
                        CleanIpOptimizer.OriginHealth.Unknown -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        1.dp,
                        when (originHealth) {
                            is CleanIpOptimizer.OriginHealth.Online -> Color(0xFF0E5C3C)
                            is CleanIpOptimizer.OriginHealth.Down -> Color(0xFF7F1D1D)
                            is CleanIpOptimizer.OriginHealth.Incompatible -> Color(0xFF78350F)
                            CleanIpOptimizer.OriginHealth.Unknown -> MaterialTheme.colorScheme.outlineVariant
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (originChecking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = PanelColors.CyanAccent
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Checking origin backend...", color = PanelColors.CyanAccent, fontSize = 11.5.sp)
                                } else {
                                    val (badgeText, badgeColor) = when (originHealth) {
                                        is CleanIpOptimizer.OriginHealth.Online -> "Origin Online" to PanelColors.SuccessGreen
                                        is CleanIpOptimizer.OriginHealth.Down -> "Origin Offline" to Color(0xFFEF4444)
                                        is CleanIpOptimizer.OriginHealth.Incompatible -> "Incompatible" to Color(0xFFFBBF24)
                                        CleanIpOptimizer.OriginHealth.Unknown -> "Origin Not Tested" to MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        text = badgeText,
                                        color = badgeColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            val descText = when (originHealth) {
                                is CleanIpOptimizer.OriginHealth.Online ->
                                    "${originHealth.message} (latency ${originHealth.latencyMs}ms)"
                                is CleanIpOptimizer.OriginHealth.Down ->
                                    "${originHealth.reason} Clean IPs cannot revive this server."
                                is CleanIpOptimizer.OriginHealth.Incompatible ->
                                    originHealth.reason
                                CleanIpOptimizer.OriginHealth.Unknown ->
                                    "Tap 'Check Origin' to verify if this server is alive before scanning."
                            }
                            Text(
                                text = descText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            )
                        }
                        IconButton(
                            onClick = { onCheckOrigin(currentSelectedProfile) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Recheck Origin",
                                tint = PanelColors.CyanAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Region & Port selectors row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Region Dropdown
                Box(modifier = Modifier.weight(1.3f)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clickable(onClick = onRegionDropdownToggle),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = PanelColors.CyanAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = selectedRegion,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = regionDropdownExpanded,
                        onDismissRequest = onRegionDropdownToggle,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
                        listOf(
                            "Iran 🇮🇷 (All Operators)",
                            "Iran 📱 MCI (Hamrah-e Aval)",
                            "Iran 🟡 MTN Irancell",
                            "Iran 🟣 Rightel",
                            "Iran ☎️ TCI / Mokhaberat",
                            "Iran ⚡ Shatel / FCP",
                            "Iran 🌐 Mobinnet / Zitel",
                            "🎯 Custom CIDR / Subnet (CFScanner)",
                            "All Regions 🌐",
                            "Germany 🇩🇪",
                            "United States 🇺🇸",
                            "Netherlands 🇳🇱",
                            "Singapore 🇸🇬",
                            "France 🇫🇷",
                            "United Kingdom 🇬🇧"
                        ).forEach { region ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        region,
                                        color = if (region.startsWith("Iran") || region.contains("Custom")) PanelColors.SuccessGreen else Color.White,
                                        fontWeight = if (region.startsWith("Iran") || region.contains("Custom")) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.5.sp
                                    )
                                },
                                onClick = { onSelectRegion(region) }
                            )
                        }
                    }
                }

                // Port Input (Editable)
                Surface(
                    modifier = Modifier
                        .weight(0.7f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Port", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = scanPort,
                                onValueChange = { newVal ->
                                    if (newVal.length <= 5 && newVal.all { it.isDigit() }) {
                                        onScanPortChange(newVal)
                                    }
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.End
                                ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Custom Subnet Input field (if Custom selected)
            if (selectedRegion.contains("Custom")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Custom Subnet / CIDR (CFScanner format):", color = PanelColors.CyanAccent, fontSize = 11.sp)
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (customSubnet.isBlank()) {
                                Text("e.g. 104.16.12.0/24, 162.159.130.0/24, 172.67.140", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), fontSize = 12.sp)
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = customSubnet,
                                onValueChange = onCustomSubnetChange,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Common Port Quick Chips & Speedtest Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ports:", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), fontSize = 10.5.sp)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(listOf("443", "8443", "2053", "2083", "2096", "80")) { p ->
                            val isSelected = scanPort == p
                            Surface(
                                modifier = Modifier
                                    .height(24.dp)
                                    .clickable { onScanPortChange(p) },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) PanelColors.PrimaryBlue.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, if (isSelected) PanelColors.CyanAccent else MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = p,
                                        color = if (isSelected) PanelColors.CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Speedtest toggle with guaranteed single line
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onSpeedTestToggle(!speedTestEnabled) }
                        .padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(if (speedTestEnabled) PanelColors.PrimaryBlue else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))
                            .border(1.dp, if (speedTestEnabled) PanelColors.CyanAccent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (speedTestEnabled) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(11.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Speedtest",
                        color = if (speedTestEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        fontSize = 11.sp,
                        fontWeight = if (speedTestEnabled) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Scanning progress indicator
            if (scanning) {
                val progress = if (scanTotal > 0) (tested.toFloat() / scanTotal.toFloat()).coerceIn(0f, 1f) else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (scanPaused) "Paused" else "Scanning... ($tested / $scanTotal)",
                            color = PanelColors.CyanAccent,
                            fontSize = 11.5.sp
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = PanelColors.PrimaryBlue,
                        trackColor = Color(0xFF1B2E4B)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPauseToggle,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (scanPaused) "Resume" else "Pause", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onStopScan,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Stop", fontSize = 12.sp)
                    }
                }
            } else {
                Button(
                    onClick = onStartScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Start CFScanner",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanIpResultItemCard(
    item: CleanIpDisplay,
    onRevive: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Flag + IP + Operator tag + Grade & Latency
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    CountryFlag(item.flagEmoji)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = item.ip,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = item.operatorTag,
                            color = PanelColors.CyanAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Quality Grade Badge
                    val gradeColor = when (item.qualityGrade) {
                        "A+" -> PanelColors.SuccessGreen
                        "A" -> Color(0xFF38BDF8)
                        "B" -> Color(0xFFFBBF24)
                        else -> Color(0xFFEF4444)
                    }
                    val gradeBg = when (item.qualityGrade) {
                        "A+" -> PanelColors.SuccessGreenBg
                        "A" -> Color(0xFF0B2545)
                        "B" -> Color(0xFF2E1C07)
                        else -> Color(0xFF331118)
                    }
                    val gradeBorder = when (item.qualityGrade) {
                        "A+" -> PanelColors.SuccessGreenBorder
                        "A" -> Color(0xFF1E4E8C)
                        "B" -> Color(0xFF78350F)
                        else -> Color(0xFF7F1D1D)
                    }
                    Box(
                        modifier = Modifier
                            .background(gradeBg, RoundedCornerShape(6.dp))
                            .border(1.dp, gradeBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.qualityGrade,
                            color = gradeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    // Latency Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SignalBars(latencyMs = item.latencyMs)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${item.latencyMs} ms",
                                color = if (item.latencyMs <= 120) PanelColors.SuccessGreen else if (item.latencyMs <= 200) Color(0xFF38BDF8) else Color(0xFFFBBF24),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }

            // Middle Row: Metrics (Speed, Status, Jitter, Packet Loss)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF081220), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.downloadSpeedKbps > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = PanelColors.CyanAccent, modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = item.formattedSpeed,
                            color = PanelColors.CyanAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = item.verificationStatus,
                    color = if (item.originVerified) PanelColors.SuccessGreen else Color(0xFF94A3B8),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Jitter: ±${item.jitterMs}ms",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.5.sp
                )
                Text(
                    text = "Loss: ${item.lossPercent.toInt()}%",
                    color = if (item.lossPercent == 0.0) PanelColors.SuccessGreen else Color(0xFFF87171),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Bottom Actions Row: ⚡ Optimize Config | 💾 Save Profile | 📋 Copy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Optimize Config (Primary Action)
                Button(
                    onClick = onRevive,
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1.3f).height(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Optimize Config",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 2. Save Direct Profile
                OutlinedButton(
                    onClick = onSave,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = PanelColors.CyanAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Save",
                        color = PanelColors.CyanAccent,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 3. Copy IP
                Surface(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(onClick = onCopy),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy IP",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

// SCREEN 4: INSTALLED PANEL DETAIL VIEW
@Composable
private fun InstalledPanelDetailView(
    panel: ManagedPanel,
    accessInfoExpanded: Boolean,
    onToggleAccessInfo: () -> Unit,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    onCopy: (String, String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onUpdatePort: ((Int) -> Unit)? = null,
    onImportToVpn: (() -> Unit)? = null,
    onOneClickReality: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean = false,
    statusText: String = "",
    errorText: String = ""
) {
    val panelUri = Uri.parse(panel.url)
    val panelPort = panelUri.port.takeIf { it > 0 } ?: 2053
    val panelPath = if (panel.hostKeySha256.startsWith("/")) panel.hostKeySha256 else (panelUri.path?.ifBlank { "/panel" } ?: "/panel")
    val panelUsername = panel.username.ifBlank { "admin" }
    val panelPassword = panel.password.ifBlank { "••••••••" }
    val panelProtocol = panelUri.scheme ?: "http"
    val panelHost = panel.host.ifBlank { panelUri.host.orEmpty() }

    var showEditPortDialog by remember { mutableStateOf(false) }
    var editedPortText by remember { mutableStateOf(panelPort.toString()) }

    if (showEditPortDialog) {
        AlertDialog(
            onDismissRequest = { showEditPortDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Edit Panel Port",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Specify the listening port configured for your 3X-UI panel on $panelHost.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = editedPortText,
                        onValueChange = { editedPortText = it.filter(Char::isDigit) },
                        label = { Text("Port (1 - 65535)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = outlinedColors()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = editedPortText.toIntOrNull()
                        if (p != null && p in 1..65535) {
                            onUpdatePort?.invoke(p)
                            showEditPortDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue)
                ) {
                    Text("Save Port")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditPortDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // PANEL HEADER CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelBrandLogo("3X-UI", Modifier.size(44.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = panel.name.ifBlank { "3X-UI • $panelHost" },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Box(
                            modifier = Modifier
                                .background(PanelColors.SuccessGreenBg, RoundedCornerShape(12.dp))
                                .border(1.dp, PanelColors.SuccessGreenBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "● Active",
                                color = PanelColors.SuccessGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Xray server panel • Host: $panelHost",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // ACCESS INFORMATION CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleAccessInfo),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Access Information",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Icon(
                        imageVector = if (accessInfoExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = accessInfoExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AccessField(
                                label = "Panel URL",
                                value = panel.url.ifBlank { "http://$panelHost:$panelPort$panelPath" },
                                icon = Icons.Default.Language,
                                iconColor = PanelColors.SuccessGreen,
                                onCopy = { onCopy(panel.url.ifBlank { "http://$panelHost:$panelPort$panelPath" }, "Panel URL") },
                                modifier = Modifier.weight(1f)
                            )
                            AccessField(
                                label = "Username",
                                value = panelUsername,
                                icon = Icons.Default.Person,
                                iconColor = PanelColors.CyanAccent,
                                onCopy = { onCopy(panelUsername, "Username") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AccessField(
                                label = "Password",
                                value = if (passwordVisible) panelPassword else "••••••••",
                                icon = Icons.Default.Lock,
                                iconColor = Color(0xFFFFB300),
                                trailingEye = true,
                                eyeVisible = passwordVisible,
                                onToggleEye = onTogglePassword,
                                onCopy = { onCopy(panelPassword, "Password") },
                                modifier = Modifier.weight(1f)
                            )
                            AccessField(
                                label = "Port",
                                value = panelPort.toString(),
                                icon = Icons.Default.Router,
                                iconColor = PanelColors.CyanAccent,
                                onEdit = {
                                    editedPortText = panelPort.toString()
                                    showEditPortDialog = true
                                },
                                onCopy = { onCopy(panelPort.toString(), "Port") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AccessField(
                                label = "Path",
                                value = panelPath,
                                icon = Icons.Default.Description,
                                iconColor = Color(0xFFA78BFA),
                                onCopy = { onCopy(panelPath, "Path") },
                                modifier = Modifier.weight(1f)
                            )
                            AccessField(
                                label = "Protocol",
                                value = panelProtocol.uppercase(),
                                icon = Icons.Default.Shield,
                                iconColor = Color(0xFFC084FC),
                                onCopy = { onCopy(panelProtocol, "Protocol") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Action Buttons: Open Panel & Copy All
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onOpenUrl(panel.url.ifBlank { "http://$panelHost:$panelPort$panelPath" }) },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            brush = Brush.horizontalGradient(
                                                listOf(Color(0xFF6366F1), Color(0xFF2563EB))
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Open Panel",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.OpenInBrowser,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clickable {
                                        val allInfo = """
                                            Panel: ${panel.name}
                                            URL: ${panel.url.ifBlank { "http://$panelHost:$panelPort$panelPath" }}
                                            Host: $panelHost
                                            Port: $panelPort
                                            Username: $panelUsername
                                            Password: $panelPassword
                                            Path: $panelPath
                                        """.trimIndent()
                                        onCopy(allInfo, "All panel credentials")
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Copy All",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        // 1-Click Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onImportToVpn?.invoke() },
                                enabled = !busy,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PanelColors.SuccessGreenBg),
                                border = BorderStroke(1.dp, PanelColors.SuccessGreenBorder),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = PanelColors.SuccessGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Add to VPN",
                                    color = PanelColors.SuccessGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            OutlinedButton(
                                onClick = onOneClickReality,
                                enabled = !busy,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, PanelColors.CyanAccent),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = if (panel.type == PanelType.BPB_WORKER) Icons.Default.Security else Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = PanelColors.CyanAccent,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (panel.type == PanelType.BPB_WORKER) "FIX BPB" else "Quick Config",
                                    color = PanelColors.CyanAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        // Progress and errors of the actions above; this view hides the
                        // list-level status banner, so without this a failure looks like nothing happened.
                        if (busy) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = PanelColors.CyanAccent
                                )
                                if (statusText.isNotBlank()) {
                                    Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
                                }
                            }
                        } else if (errorText.isNotBlank()) {
                            Text(
                                text = errorText,
                                color = Color(0xFFFF5252),
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )
                        }

                        // Delete option
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Remove from device", color = Color(0xFFFF5252), fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }
    }
}

// EMPTY CLEAN IP COMPONENT
@Composable
private fun EmptyCleanIpCard(
    isScanning: Boolean,
    onStartScan: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(Color(0xFF0F243E), CircleShape)
                    .border(1.dp, PanelColors.CyanAccent.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = PanelColors.CyanAccent,
                    modifier = Modifier.size(26.dp)
                )
            }

            Text(
                text = if (isScanning) "Scanning Clean IPs..." else "No Clean IPs Scanned Yet",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )

            Text(
                text = if (isScanning) "Testing low-latency Cloudflare edges against your ISP route..."
                    else "Iranian ISPs (MCI, Irancell, Rightel, TCI) throttle many Cloudflare ranges. Scan our curated clean IP subnets to find unthrottled endpoints.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )

            if (!isScanning) {
                Button(
                    onClick = onStartScan,
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Start Clean IP Scan", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TerminalLine(
    message: String,
    isSuccess: Boolean = false
) {
    Row {
        Text(
            text = "[INFO] ",
            color = PanelColors.SuccessGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = message,
            color = if (isSuccess) PanelColors.SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = if (isSuccess) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun StepCheckItem(
    label: String,
    checked: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(if (checked) PanelColors.SuccessGreenBg else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                .border(1.dp, if (checked) PanelColors.SuccessGreen else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = PanelColors.SuccessGreen,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = if (checked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AccessField(
    label: String,
    value: String,
    icon: ImageVector,
    iconColor: Color,
    trailingEye: Boolean = false,
    eyeVisible: Boolean = false,
    onToggleEye: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.5.sp
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onEdit != null) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit $label",
                        tint = PanelColors.CyanAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (trailingEye && onToggleEye != null) {
                IconButton(
                    onClick = onToggleEye,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (eyeVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle visibility",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun outlinedColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PanelColors.PrimaryBlue,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedLabelColor = PanelColors.CyanAccent,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)



@Composable
private fun TunnelSetupCard(
    iranHost: String,
    onIranHostChange: (String) -> Unit,
    iranPort: String,
    onIranPortChange: (String) -> Unit,
    iranPassword: String,
    onIranPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisibility: () -> Unit
) {
    val portValue = iranPort.toIntOrNull()
    val formReady = iranHost.isNotBlank() && portValue != null && portValue in 1..65535 && iranPassword.isNotBlank()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, PanelColors.CyanAccent.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(
                            listOf(
                                PanelColors.PrimaryBlue.copy(alpha = 0.18f),
                                PanelColors.CyanAccent.copy(alpha = 0.06f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(13.dp),
                        color = PanelColors.PrimaryBlue.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, PanelColors.CyanAccent.copy(alpha = 0.4f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = PanelColors.CyanAccent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Iran ↔ International Tunnel",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            text = "Prepare a secure two-server tunnel path",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.5.sp
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = PanelColors.CyanAccent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, PanelColors.CyanAccent.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "UI PREVIEW",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            color = PanelColors.CyanAccent,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Enter the Iran server access details now. Tunnel protocol selection, foreign-server provisioning, SSH actions and real tunnel creation will be implemented later.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, if (formReady) PanelColors.SuccessGreen.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🇮🇷  IRAN SERVER", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (iranHost.isBlank()) "Waiting for details" else iranHost,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = PanelColors.CyanAccent)
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🌍  FOREIGN SERVER", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text("Configured later", color = PanelColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Router, null, tint = PanelColors.CyanAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "Iran Server",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedTextField(
                    value = iranHost,
                    onValueChange = onIranHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Iran Server IP / Host") },
                    placeholder = { Text("e.g. 185.120.10.25") },
                    leadingIcon = { Icon(Icons.Default.Dns, null) },
                    singleLine = true,
                    colors = outlinedColors()
                )

                OutlinedTextField(
                    value = iranPort,
                    onValueChange = onIranPortChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SSH Port") },
                    placeholder = { Text("22") },
                    leadingIcon = { Icon(Icons.Default.Terminal, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    supportingText = {
                        if (iranPort.isNotBlank() && (portValue == null || portValue !in 1..65535)) {
                            Text("Enter a port from 1 to 65535.")
                        }
                    },
                    colors = outlinedColors()
                )

                OutlinedTextField(
                    value = iranPassword,
                    onValueChange = onIranPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SSH Password") },
                    placeholder = { Text("Server password") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    trailingIcon = {
                        IconButton(onClick = onTogglePasswordVisibility) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    colors = outlinedColors()
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Security, null, tint = PanelColors.CyanAccent, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Tunnel engine not selected yet",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "No SSH login, package installation, server modification or network tunnel is performed by this screen.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Bolt, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Create Tunnel — Coming soon", fontWeight = FontWeight.Bold)
                }

                Text(
                    text = if (formReady)
                        "Iran server details are ready in this screen. They are not stored or transmitted yet."
                    else
                        "Complete the Iran server IP/host, SSH port and password to preview the future workflow.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = if (formReady) PanelColors.SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.5.sp
                )
            }
        }
    }
}
