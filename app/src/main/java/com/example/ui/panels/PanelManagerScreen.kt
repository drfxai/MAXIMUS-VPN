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
    var showServerSetupDialog by remember { mutableStateOf(false) }
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

    // Forms
    var host by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var sshUser by remember { mutableStateOf("root") }
    var sshPassword by remember { mutableStateOf("") }
    var hostFingerprint by remember { mutableStateOf("") }
    var cfToken by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var cfEmail by remember { mutableStateOf("") }
    var cfPassword by remember { mutableStateOf("") }
    var parentToken by remember { mutableStateOf("") }

    val copyWithToast: (String, String) -> Unit = { text, label ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearToken() }
    }

    // Determine current panel for detail view (Screen 4)
    val activePanel = state.panels.firstOrNull { it.id == selectedPanelId }

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
                    text = "Server + Cloudflare control center",
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tabs = listOf("Servers", "Cloudflare", "Clean IP")
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
                            fontSize = 13.sp
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
                // STAT CARDS (3 COLUMNS)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (selectedTab) {
                        0 -> {
                            val serverCount = state.panels.count { it.type == PanelType.XUI }
                            val workerCount = state.panels.count { it.type == PanelType.BPB_WORKER }
                            val totalActive = serverCount + workerCount
                            StatCard(Icons.Default.Dns, PanelColors.CyanAccent, serverCount.toString(), "Servers", Modifier.weight(1f))
                            StatCard(Icons.Default.Cloud, PanelColors.CyanAccent, workerCount.toString(), "Workers", Modifier.weight(1f))
                            StatCard(Icons.Default.Shield, if (totalActive > 0) PanelColors.SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant, if (totalActive > 0) "Online" else "Standby", "Status", Modifier.weight(1f))
                        }
                        1 -> {
                            val workerCount = state.panels.count { it.type == PanelType.BPB_WORKER }
                            StatCard(Icons.Default.Cloud, PanelColors.CyanAccent, workerCount.toString(), "Workers", Modifier.weight(1f))
                            StatCard(Icons.Default.Description, PanelColors.CyanAccent, "0", "Pages", Modifier.weight(1f))
                            StatCard(Icons.Default.Storage, PanelColors.CyanAccent, "0", "KV Storage", Modifier.weight(1f))
                        }
                        else -> {
                            val scanningCount = if (state.busy) state.tested else 0
                            val cleanCount = state.edges.size
                            StatCard(Icons.Default.Language, PanelColors.CyanAccent, scanningCount.toString(), "Tested", Modifier.weight(1f))
                            StatCard(Icons.Default.Security, PanelColors.CyanAccent, state.reachable.toString(), "Reachable", Modifier.weight(1f))
                            StatCard(Icons.Default.CheckCircle, PanelColors.SuccessGreen, cleanCount.toString(), "Clean IPs", Modifier.weight(1f))
                        }
                    }
                }

                // TAB 0: SERVERS
                if (selectedTab == 0) {
                    // LIVE SERVER INSTALLATION STATUS & LOGS BANNER (If installing, failed, or newly installed)
                    if (state.installingServer || state.newlyInstalledServerPanel != null || (state.error.isNotBlank() && selectedTab == 0)) {
                        LiveServerInstallationCard(
                            installing = state.installingServer,
                            target = state.installServerTarget,
                            newPanel = state.newlyInstalledServerPanel,
                            error = state.error,
                            logs = state.logs,
                            onDismiss = { viewModel.dismissNewlyInstalledServer() },
                            onOpenUrl = { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure {
                                    Toast.makeText(context, "Cannot open browser: $url", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onCopy = copyWithToast,
                            onImportToVpn = { panel ->
                                viewModel.importServerToProfiles(panel)
                                Toast.makeText(context, "3X-UI server imported to VPN profiles!", Toast.LENGTH_SHORT).show()
                            },
                            onRetry = { showServerSetupDialog = true }
                        )
                    }

                    // Action button: + Install a new server panel
                    Button(
                        onClick = {
                            viewModel.clearServerProbe()
                            showServerSetupDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Install a new server panel",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    // Available Panels Section (2x2 Grid)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Available Panels",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "View All >",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                viewModel.clearServerProbe()
                                showServerSetupDialog = true
                            }
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AvailablePanelCard(
                                title = "3X-UI Panel",
                                subtitle = "Xray multi-protocol\n(VLESS, Trojan, etc.)",
                                logoType = "3X-UI",
                                isActive = true,
                                onInstall = {
                                    val existingXui = state.panels.firstOrNull { it.type == PanelType.XUI }
                                    if (existingXui != null) {
                                        selectedPanelId = existingXui.id
                                    } else {
                                        viewModel.clearServerProbe()
                                        showServerSetupDialog = true
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AvailablePanelCard(
                                title = "x-ui Panel",
                                subtitle = "Simple & Lightweight\nXray panel",
                                logoType = "x-ui",
                                isActive = false,
                                onInstall = {},
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AvailablePanelCard(
                                title = "Hiddify Manager",
                                subtitle = "Advanced Xray panel\nwith smart routing",
                                logoType = "hiddify",
                                isActive = false,
                                onInstall = {},
                                modifier = Modifier.weight(1f)
                            )
                            AvailablePanelCard(
                                title = "Other Panels",
                                subtitle = "Marzban, Sing-box\nand more...",
                                logoType = "other",
                                isActive = false,
                                onInstall = {},
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Managed Servers Section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val serverList = state.panels.filter { it.type == PanelType.XUI }
                        Text(
                            text = "Managed Servers (${serverList.size})",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (serverList.isNotEmpty()) {
                            Text(
                                text = "Add Server +",
                                color = PanelColors.CyanAccent,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    viewModel.clearServerProbe()
                                    showServerSetupDialog = true
                                }
                            )
                        }
                    }

                    val serverList = state.panels.filter { it.type == PanelType.XUI }

                    if (serverList.isEmpty()) {
                        EmptyServersCard(onInstallClick = {
                            viewModel.clearServerProbe()
                            showServerSetupDialog = true
                        })
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            serverList.forEach { server ->
                                val hostStr = server.host.ifBlank { runCatching { Uri.parse(server.url).host.orEmpty() }.getOrDefault("") }
                                val flag = if (server.name.startsWith("DE", ignoreCase = true)) "🇩🇪"
                                    else if (server.name.startsWith("IR", ignoreCase = true) || hostStr.contains(".ir")) "🇮🇷"
                                    else if (server.name.startsWith("US", ignoreCase = true)) "🇺🇸"
                                    else if (server.name.startsWith("NL", ignoreCase = true)) "🇳🇱"
                                    else "🌐"
                                ManagedServerItemCard(
                                    name = server.name.ifBlank { "3X-UI • $hostStr" },
                                    host = hostStr,
                                    flag = flag,
                                    onClick = {
                                        selectedPanelId = server.id
                                    },
                                    onMenuClick = {
                                        confirmRemovePanel = server
                                    }
                                )
                            }
                        }
                    }
                }

                // TAB 1: CLOUDFLARE
                if (selectedTab == 1) {
                    CloudflareQuickActions(
                        onSignUp = { CloudflareTokenHelper.openSignUpUrl(context) },
                        onCreateToken = { CloudflareTokenHelper.openOneClickTokenUrl(context) }
                    )
                    BpbWizardCard(
                        token = cfToken,
                        onTokenChange = { cfToken = it },
                        account = account,
                        onAccountChange = { account = it },
                        isBusy = state.busy,
                        statusText = state.status,
                        logs = state.logs,
                        newlyDeployedPanel = state.newlyDeployedPanel,
                        onDismissNewPanel = { viewModel.dismissNewlyDeployedPanel() },
                        onInstall = { token, accountId, email, password ->
                            viewModel.deployBpb(token, accountId, email, password)
                        },
                        onImportToVpn = { panel -> viewModel.importBpbToProfiles(panel) },
                        onFixBpb = { panel -> viewModel.fixBpbProfiles(panel) }
                    )
                    SavedBpbPanelsCard(
                        panels = state.panels.filter { it.type == PanelType.BPB_WORKER },
                        busy = state.busy,
                        onImportToVpn = { panel -> viewModel.importBpbToProfiles(panel) },
                        onFixBpb = { panel -> viewModel.fixBpbProfiles(panel) }
                    )
                }

                // TAB 2: CLEAN IP SCANNER
                if (selectedTab == 2) {
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
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // MODAL DIALOGS
    if (showServerSetupDialog) {
        AlertDialog(
            onDismissRequest = { showServerSetupDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelBrandLogo("3X-UI", Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Install 3X-UI Panel", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Deploy a secure Xray panel to your VPS via SSH with real-time status telemetry.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = {
                            host = it.trim()
                            if (state.serverProbeStatus != null) viewModel.clearServerProbe()
                        },
                        label = { Text("Server IP / Host") },
                        placeholder = { Text("e.g. 194.163.140.22") },
                        singleLine = true,
                        colors = outlinedColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sshPort,
                            onValueChange = {
                                sshPort = it.filter(Char::isDigit)
                                if (state.serverProbeStatus != null) viewModel.clearServerProbe()
                            },
                            label = { Text("Port") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = outlinedColors()
                        )
                        OutlinedTextField(
                            value = sshUser,
                            onValueChange = { sshUser = it.trim() },
                            label = { Text("User") },
                            modifier = Modifier.weight(1.5f),
                            singleLine = true,
                            colors = outlinedColors()
                        )
                    }

                    // Live Pre-flight Probe Button & Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connection Status",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(
                            onClick = {
                                viewModel.probeServer(host, sshPort.toIntOrNull() ?: 22)
                            },
                            enabled = host.isNotBlank() && !state.serverProbeBusy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            if (state.serverProbeBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = PanelColors.CyanAccent
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Probing...", fontSize = 11.sp, color = PanelColors.CyanAccent)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = PanelColors.CyanAccent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test Connection", fontSize = 11.sp, color = PanelColors.CyanAccent)
                            }
                        }
                    }

                    state.serverProbeStatus?.let { probe ->
                        if (probe.reachable && probe.hostKeySha256.isNotBlank() && hostFingerprint != probe.hostKeySha256) {
                            hostFingerprint = probe.hostKeySha256
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = if (probe.reachable) PanelColors.SuccessGreenBg else Color(0xFF331118),
                            border = BorderStroke(1.dp, if (probe.reachable) PanelColors.SuccessGreenBorder else Color(0xFF7F1D1D))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (probe.reachable) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (probe.reachable) PanelColors.SuccessGreen else Color(0xFFEF4444),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = probe.message,
                                    color = if (probe.reachable) PanelColors.SuccessGreen else Color(0xFFFCA5A5),
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = sshPassword,
                        onValueChange = { sshPassword = it },
                        label = { Text("SSH Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = outlinedColors()
                    )
                    OutlinedTextField(
                        value = hostFingerprint,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("SSH Host Key SHA256") },
                        placeholder = { Text("Tap Test Connection to discover securely") },
                        supportingText = {
                            Text(
                                if (hostFingerprint.isBlank()) "Required: discover and confirm the server key before installation."
                                else "Confirm this fingerprint matches your VPS provider/console before installing."
                            )
                        },
                        singleLine = true,
                        colors = outlinedColors()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val effectiveHost = host.trim()
                        val effectivePass = sshPassword
                        if (effectiveHost.isBlank() || effectivePass.isBlank()) {
                            Toast.makeText(context, "Please enter server IP/Host and SSH password", Toast.LENGTH_SHORT).show()
                        } else if (hostFingerprint.isBlank() || state.serverProbeStatus?.reachable != true) {
                            Toast.makeText(context, "Tap Test Connection and confirm the SSH fingerprint first", Toast.LENGTH_LONG).show()
                        } else {
                            viewModel.installXui(
                                host = effectiveHost,
                                port = sshPort.toIntOrNull() ?: 22,
                                username = sshUser.ifBlank { "root" }.trim(),
                                password = effectivePass,
                                fingerprint = hostFingerprint.trim()
                            )
                            showServerSetupDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue)
                ) {
                    Text("Install Panel")
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerSetupDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }



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
private fun AvailablePanelCard(
    title: String,
    subtitle: String,
    logoType: String,
    isActive: Boolean = true,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (isActive) MaterialTheme.colorScheme.outlineVariant else Color(0xFF16243A)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                PanelBrandLogo(logoType, Modifier.size(36.dp))
                if (!isActive) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF0F233D), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Soon",
                            color = Color(0xFF60A5FA),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Text(
                text = title,
                color = if (isActive) Color.White else Color(0xFFE2E8F0),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                minLines = 2,
                maxLines = 2
            )
            if (isActive) {
                Button(
                    onClick = onInstall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Install", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0D1B2E),
                    border = BorderStroke(1.dp, Color(0xFF162842))
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Soon",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagedServerItemCard(
    name: String,
    host: String,
    flag: String,
    onClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(PanelColors.SuccessGreen, CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            CountryFlag(flag)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp
                )
                Text(
                    text = host,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Menu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CloudflareQuickActions(
    onSignUp: () -> Unit,
    onCreateToken: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = PanelColors.CyanAccent,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Cloudflare",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Account & API Token",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Button(
                onClick = onSignUp,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Up", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onCreateToken,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, PanelColors.CyanAccent)
            ) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    tint = PanelColors.CyanAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Create Token",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Create Token opens Cloudflare with Workers Scripts and Workers KV permissions pre-filled automatically.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun BpbWizardCard(
    token: String,
    onTokenChange: (String) -> Unit,
    account: String,
    onAccountChange: (String) -> Unit,
    isBusy: Boolean,
    statusText: String,
    logs: List<String>,
    newlyDeployedPanel: ManagedPanel?,
    onDismissNewPanel: () -> Unit,
    onInstall: (token: String, account: String, email: String, password: String) -> Unit,
    onImportToVpn: (panel: ManagedPanel) -> Unit,
    onFixBpb: (panel: ManagedPanel) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var emailInput by remember { mutableStateOf("") }
    var panelPassInput by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var passVisible by remember { mutableStateOf(false) }
    var selectedMethod by remember { mutableStateOf("Cloudflare Workers") }
    var methodDropdownExpanded by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: BPB Wizard icon + title + GitHub link
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF0C2B4E), CircleShape)
                            .border(1.dp, Color(0xFF1E6BFF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "BPB Wizard",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Install BPB Panel efficiently",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // GitHub / Docs badge
                Surface(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bia-pain-bache/BPB-Worker-Panel")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure { Toast.makeText(context, "Cannot open documentation", Toast.LENGTH_SHORT).show() }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, Color(0xFF24395A))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "GitHub",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Docs", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // 4 Steps instructions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF081224), RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF152642), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Step 1
                Row(verticalAlignment = Alignment.Top) {
                    Text("1. ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sign up",
                            color = Color(0xFF60A5FA),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { CloudflareTokenHelper.openSignUpUrl(context) }
                        )
                        Text(
                            text = " a Cloudflare account and verify it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }

                // Step 2 with 1-Click action
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("2. ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Create a token",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        CloudflareTokenHelper.openOneClickTokenUrl(context)
                                    }
                                )
                                Text(
                                    text = " , you should ",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Continue to summary",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = ",",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = "Create Token and copy it.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // 1-Click button for step 2
                    Surface(
                        onClick = { CloudflareTokenHelper.openOneClickTokenUrl(context) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "1-Click: Open scoped Worker/KV token setup",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Step 3
                Row(verticalAlignment = Alignment.Top) {
                    Text("3. ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Paste your token below and click Install. A username and password are created for you (you may set your own).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Step 4
                Row(verticalAlignment = Alignment.Top) {
                    Text("4. ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "A private login link, username and password are generated automatically and shown here, ready to copy.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Input: Cloudflare API Token
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Cloudflare API Token", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), fontSize = 13.sp)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = PanelColors.CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle visibility",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = {
                            val clip = clipboard.getText()?.text.orEmpty().trim()
                            if (clip.isNotBlank()) onTokenChange(clip)
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Paste",
                                tint = PanelColors.CyanAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = Color(0xFF1E6BFF),
                    unfocusedBorderColor = Color(0xFF1B2E4B),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            // Input: Panel Admin Password
            OutlinedTextField(
                value = panelPassInput,
                onValueChange = { panelPassInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Panel password (optional • auto-generated)", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), fontSize = 13.sp)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { passVisible = !passVisible }) {
                        Icon(
                            imageVector = if (passVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = Color(0xFF00E676),
                    unfocusedBorderColor = Color(0xFF1B2E4B),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            // Input: Cloudflare Email (Optional)
            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Panel username (optional • auto-generated)", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), fontSize = 12.5.sp)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = PanelColors.CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = Color(0xFF1E6BFF),
                    unfocusedBorderColor = Color(0xFF1B2E4B),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            // Method Dropdown (Cloudflare Workers / Cloudflare Pages)
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    onClick = { methodDropdownExpanded = !methodDropdownExpanded },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, Color(0xFF1B2E4B)),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Router,
                                contentDescription = null,
                                tint = PanelColors.CyanAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = selectedMethod,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = methodDropdownExpanded,
                    onDismissRequest = { methodDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Cloudflare Workers") },
                        onClick = {
                            selectedMethod = "Cloudflare Workers"
                            methodDropdownExpanded = false
                        }
                    )
                    // Cloudflare Pages deployment is not implemented by the BPB worker flow.
                }
            }

            // Optional Advanced Accordion (Account ID Override)
            Column {
                Row(
                    modifier = Modifier
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showAdvanced) "Hide Advanced Settings ▴" else "Advanced: Account ID (Auto-discovered) ▾",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        fontSize = 11.sp
                    )
                }

                AnimatedVisibility(visible = showAdvanced) {
                    OutlinedTextField(
                        value = account,
                        onValueChange = onAccountChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        placeholder = {
                            Text("Account ID (Leave blank to auto-detect)", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), fontSize = 12.sp)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedBorderColor = Color(0xFF1E6BFF),
                            unfocusedBorderColor = Color(0xFF1B2E4B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            }

            // Live deployment stages — mirrors the 3X-UI installer while using BPB-specific phases.
            val stage = when {
                newlyDeployedPanel != null -> 4
                logs.any { it.contains("Uploading Worker", ignoreCase = true) || it.contains("[BPB]", ignoreCase = true) } -> 3
                logs.any { it.contains("KV namespace", ignoreCase = true) } -> 2
                logs.any { it.contains("Verifying API token", ignoreCase = true) || it.contains("Connecting to Cloudflare", ignoreCase = true) } -> 1
                else -> 0
            }
            if (isBusy || logs.isNotEmpty() || newlyDeployedPanel != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp))
                        .border(1.dp, PanelColors.CyanAccent, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (newlyDeployedPanel != null) "BPB deployment complete" else "Deploying BPB Panel",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("Token", "KV", "Worker", "Ready").forEachIndexed { index, label ->
                            val completed = stage > index || newlyDeployedPanel != null
                            val active = stage == index + 1 && isBusy
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        if (completed) Color(0xFF063B2A) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        CircleShape
                                    )
                                    .border(
                                        1.dp,
                                        if (completed || active) Color(0xFF00E676) else MaterialTheme.colorScheme.outlineVariant,
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (completed) "✓" else (index + 1).toString(), fontSize = 10.sp,
                                    color = if (completed) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(label, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface)
                            if (index < 3) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 5.dp)
                                        .height(1.dp)
                                        .weight(1f)
                                        .background(if (stage > index + 1) Color(0xFF00E676) else MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                        }
                    }
                    if (logs.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 110.dp, max = 190.dp)
                                .background(Color(0xFF090A10), RoundedCornerShape(10.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            logs.takeLast(30).forEach { line ->
                                Text(
                                    text = line,
                                    color = if (line.contains("ERROR", true) || line.contains("FAIL", true)) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // Install Button
            Button(
                onClick = { onInstall(token.trim(), account.trim(), emailInput.trim(), panelPassInput.trim()) },
                enabled = !isBusy && token.trim().isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color(0xFF122238)
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = if (!isBusy && token.trim().isNotBlank()) {
                                Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF1D4ED8)))
                            } else {
                                Brush.horizontalGradient(listOf(Color(0xFF132238), Color(0xFF132238)))
                            },
                            shape = RoundedCornerShape(24.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            tint = if (!isBusy && token.trim().isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isBusy) "Installing BPB Panel..." else "Install",
                            color = if (!isBusy && token.trim().isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Standby / Progress box
            if (isBusy) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF081C38), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF1D4ED8), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = PanelColors.CyanAccent,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Deploying BPB Worker...",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = PanelColors.CyanAccent,
                        trackColor = Color(0xFF0F2B57)
                    )
                    Text(
                        text = statusText.ifBlank { logs.lastOrNull().orEmpty() },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (newlyDeployedPanel != null) {
                // Success Result Card
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "BPB Panel Deployed!",
                                color = Color(0xFF00E676),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        IconButton(
                            onClick = onDismissNewPanel,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    PanelCredentialsCard(
                        panelName = newlyDeployedPanel.name,
                        link = newlyDeployedPanel.url,
                        username = newlyDeployedPanel.username,
                        password = newlyDeployedPanel.password,
                        healthNote = newlyDeployedPanel.healthNote
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(newlyDeployedPanel.url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E6BFF))
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open Panel", fontSize = 11.5.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(newlyDeployedPanel.url))
                                Toast.makeText(context, "Private link copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFF244434))
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Link", fontSize = 11.5.sp)
                        }
                    }

                    Button(
                        onClick = { onImportToVpn(newlyDeployedPanel) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E5C3C))
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add to Maximus VPN Profiles", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                    }

                    FixBpbButton(onClick = { onFixBpb(newlyDeployedPanel) })
                }
            } else {
                // Standby Card matching photo 1
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF061122), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF12223C), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "•••  Standby",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
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

// LIVE SERVER INSTALLATION STATUS & TELEMETRY CARD
@Composable
private fun LiveServerInstallationCard(
    installing: Boolean,
    target: String,
    newPanel: ManagedPanel?,
    error: String,
    logs: List<String>,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopy: (String, String) -> Unit,
    onImportToVpn: (ManagedPanel) -> Unit,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (error.isNotBlank()) Color(0xFFEF4444)
            else if (newPanel != null) PanelColors.SuccessGreen
            else PanelColors.CyanAccent
        ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF091424))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (installing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = PanelColors.CyanAccent
                        )
                    } else if (error.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    } else if (newPanel != null) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = PanelColors.SuccessGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (installing) "Deploying 3X-UI on $target"
                            else if (error.isNotBlank()) "Server Installation Error"
                            else if (newPanel != null) "3X-UI Deployed Successfully!"
                            else "Installation Telemetry",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                }

                if (!installing) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Step Progress Track (5 stages)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val hasLogs = logs.isNotEmpty()
                val isDone = newPanel != null
                val isFailed = error.isNotBlank()

                StepCheckItem("SSH", hasLogs)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (hasLogs) PanelColors.SuccessGreen else MaterialTheme.colorScheme.outlineVariant)
                )
                StepCheckItem("Auth", hasLogs && logs.any { it.contains("session", ignoreCase = true) || it.contains("verified", ignoreCase = true) || isDone })
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (logs.any { it.contains("Installing", ignoreCase = true) || isDone }) PanelColors.SuccessGreen else MaterialTheme.colorScheme.outlineVariant)
                )
                StepCheckItem("Install", logs.any { it.contains("Installing", ignoreCase = true) || isDone })
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (isDone) PanelColors.SuccessGreen else MaterialTheme.colorScheme.outlineVariant)
                )
                StepCheckItem("Ready", isDone)
            }

            // Live Terminal Console
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (logs.isEmpty()) {
                        TerminalLine("Initiating encrypted SSH handshake...")
                    } else {
                        logs.takeLast(6).forEach { logLine ->
                            val isSuccess = logLine.contains("complete", ignoreCase = true) || logLine.contains("success", ignoreCase = true)
                            val isErr = logLine.contains("error", ignoreCase = true) || logLine.contains("fail", ignoreCase = true)
                            Row {
                                Text(
                                    text = if (isErr) "[FAIL] " else if (isSuccess) "[OK] " else "[SSH] ",
                                    color = if (isErr) Color(0xFFEF4444) else if (isSuccess) PanelColors.SuccessGreen else PanelColors.CyanAccent,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = logLine.removePrefix("[SSH] ").removePrefix("[3X-UI] ").removePrefix("[ERROR] "),
                                    color = if (isErr) Color(0xFFFCA5A5) else if (isSuccess) PanelColors.SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Error Diagnosis Box
            if (error.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF331118),
                    border = BorderStroke(1.dp, Color(0xFF7F1D1D))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Cause: $error",
                            color = Color(0xFFFCA5A5),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        val diagnosis = when {
                            error.contains("login failed", ignoreCase = true) || error.contains("Auth fail", ignoreCase = true) ->
                                "Wrong SSH username or password, or password login is disabled for this user. Check the root credentials from your VPS provider."
                            error.contains("fingerprint", ignoreCase = true) || error.contains("host key", ignoreCase = true) ->
                                "Re-open Install, tap Test Connection, verify the discovered SHA256 fingerprint against your VPS console/provider, then install."
                            error.contains("plain HTTP", ignoreCase = true) ->
                                "Tap Retry Installation and enter the SSH details again. The existing panel is kept; only its HTTPS is repaired."
                            error.contains("not reachable", ignoreCase = true) || error.contains("Cannot resolve", ignoreCase = true) ->
                                "This device cannot open the SSH port. Check the IP address, the SSH port, and that the cloud firewall allows inbound TCP on it."
                            error.contains("did not complete", ignoreCase = true) || error.contains("timed out", ignoreCase = true) ->
                                "The server answers on the SSH port but the login stalls. Make sure password login is enabled for this user, wait a minute (rate limiting / fail2ban) and retry; Retry reuses an existing installation."
                            error.contains("refused", ignoreCase = true) -> "SSH service connection refused. Confirm SSH daemon is running and port is correct."
                            else -> "Check VPS internet access, SSH credentials, sudo privileges, and supported operating system."
                        }
                        Text(
                            text = "💡 Remediation: $diagnosis",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f).height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Retry Installation", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }

            // Succeeded Actions Box
            if (newPanel != null) {
                PanelCredentialsCard(
                    panelName = newPanel.name,
                    link = newPanel.url,
                    username = newPanel.username,
                    password = newPanel.password,
                    healthNote = newPanel.healthNote
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onOpenUrl(newPanel.url) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open Panel", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onImportToVpn(newPanel) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PanelColors.SuccessGreenBg),
                        border = BorderStroke(1.dp, PanelColors.SuccessGreenBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = PanelColors.SuccessGreen, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add to VPN", color = PanelColors.SuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// EMPTY SERVERS COMPONENT
@Composable
private fun EmptyServersCard(
    onInstallClick: () -> Unit
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
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = PanelColors.CyanAccent,
                    modifier = Modifier.size(26.dp)
                )
            }

            Text(
                text = "No Server Panels Installed",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )

            Text(
                text = "Connect or install a 3X-UI / Xray panel on your Ubuntu or Debian VPS with automated SSH provisioning.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )

            Button(
                onClick = onInstallClick,
                colors = ButtonDefaults.buttonColors(containerColor = PanelColors.PrimaryBlue),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Install 3X-UI Server Panel", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
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
private fun FixBpbButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFFFFB300)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB300))
    ) {
        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("FIX BPB", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Saved BPB workers, so "Add to VPN" and "FIX BPB" stay available after the install card is closed. */
@Composable
private fun SavedBpbPanelsCard(
    panels: List<ManagedPanel>,
    busy: Boolean,
    onImportToVpn: (ManagedPanel) -> Unit,
    onFixBpb: (ManagedPanel) -> Unit
) {
    if (panels.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Your BPB panels",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = "FIX BPB adds TLS fragmentation, a plain-TLS fingerprint, HTTP/1.1 ALPN and a tuned cipher list to the configs of a panel. Use it when BPB configs do not connect.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            panels.forEach { panel ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = panel.host.ifBlank { panel.name },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onImportToVpn(panel) },
                                enabled = !busy,
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E5C3C))
                            ) {
                                Text("Add to VPN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                            }
                            FixBpbButton(onClick = { onFixBpb(panel) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
