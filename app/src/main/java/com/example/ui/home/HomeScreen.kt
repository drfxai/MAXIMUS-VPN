package com.example.ui.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.ConnectionStatus
import com.example.data.model.TrafficStats
import com.example.data.model.VlessProfile
import com.example.ui.components.ConnectionButton
import com.example.ui.components.LatencyPill
import com.example.ui.components.StatusBadge
import com.example.ui.components.ThemeToggleSwitch
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.VpnViewModel
import com.example.vpn.smart.SmartConnect
import java.util.Locale

@Composable
fun HomeScreen(
    vpnViewModel: VpnViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateToServers: () -> Unit,
    onRequestVpnPermission: () -> Unit,
    onNavigateToSecretChat: () -> Unit = {},
    onNavigateToGodBrowser: () -> Unit = {},
    onNavigateToAiAgent: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionState by vpnViewModel.connectionState.collectAsStateWithLifecycle()
    val selectedProfile by vpnViewModel.selectedProfile.collectAsStateWithLifecycle()
    val activeProfile = connectionState.activeProfile ?: selectedProfile
    val smartRecommendation by vpnViewModel.smartRecommendation.collectAsStateWithLifecycle()
    val topNodes by vpnViewModel.topNodes.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    var showLandingTourDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Top Bar: Branding on Left, Theme Toggle Switch on Right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D0E15))
                        .border(1.dp, AppTheme.colors.borderMedium, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_maximus_logo),
                        contentDescription = "Maximus Spartan App Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Maximus VPN",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "DrFXAi • Maximus VPN Core",
                        color = AppTheme.colors.primary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onNavigateToAiAgent,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.primary.copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Maximus AI Co-Pilot",
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Top Sun/Moon Interactive Theme Toggle
                ThemeToggleSwitch(
                    isDark = settings.darkTheme,
                    onThemeChange = { isDark ->
                        settingsViewModel.setDarkTheme(isDark)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Landing & Architecture Hero Banner
        LandingHeroBanner(
            onExploreArchitecture = { showLandingTourDialog = true }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Professional Mode Switcher Card (Daily Mode vs GOD Mode)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE) {
                    Color(0xFF2E0F16)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE) Color(0xFFE53935).copy(alpha = 0.6f) else AppTheme.colors.borderSubtle
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                val isGodMode = settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE
                val isTrade = settings.operationalMode == com.example.data.model.OperationalMode.TRADE
                val isGhost = settings.operationalMode == com.example.data.model.OperationalMode.GHOST_RIDER
                val accent = when {
                    isGodMode -> Color(0xFFFF5252)
                    isGhost -> Color(0xFF7C5CFF)
                    else -> AppTheme.colors.primary
                }
                val setMode = { nextMode: com.example.data.model.OperationalMode ->
                    settingsViewModel.updateSettings(settings.copy(operationalMode = nextMode))
                    if (nextMode == com.example.data.model.OperationalMode.GOD_MODE) {
                        com.example.vpn.godmode.PsiphonConduitBridge.enableGodModeBridges()
                        com.example.vpn.godmode.MaximusMeshManager.startMesh()
                    } else {
                        com.example.vpn.godmode.PsiphonConduitBridge.disableGodModeBridges()
                        com.example.vpn.godmode.MaximusMeshManager.stopMesh()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isGodMode -> Icons.Default.Shield
                                isGhost -> Icons.Default.Shield
                                isTrade -> Icons.Default.Lock
                                else -> Icons.Default.Bolt
                            },
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = settings.operationalMode.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = AppTheme.colors.textPrimary
                        )
                        Text(
                            text = settings.operationalMode.subtitle,
                            fontSize = 10.5.sp,
                            color = AppTheme.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                // Three-way mode selector, segmented control: Daily / GOD / Trade.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(AppTheme.colors.surfaceInput)
                        .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(11.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val modes = listOf(
                        com.example.data.model.OperationalMode.DAILY to "Daily",
                        com.example.data.model.OperationalMode.GOD_MODE to "GOD",
                        com.example.data.model.OperationalMode.TRADE to "Trade",
                        com.example.data.model.OperationalMode.GHOST_RIDER to "Ghost"
                    )
                    modes.forEach { (chipMode, label) ->
                        val selected = settings.operationalMode == chipMode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AppTheme.colors.surfaceCardHighlight else Color.Transparent)
                                .clickable(enabled = !selected) { setMode(chipMode) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) AppTheme.colors.textPrimary else AppTheme.colors.textMuted,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Quick Tools & Cascade Path when GOD MODE is Active
                if (settings.operationalMode == com.example.data.model.OperationalMode.GOD_MODE) {
                    val bridges by com.example.vpn.godmode.PsiphonConduitBridge.bridgesStateFlow.collectAsStateWithLifecycle()
                    val meshPeers by com.example.vpn.godmode.MaximusMeshManager.peersFlow.collectAsStateWithLifecycle()
                    val verifiedBridgesCount = bridges.count { it.isVerified || it.latencyMs != null }

                    Spacer(modifier = Modifier.height(10.dp))

                    // God Mode Cascade Path & Live Network Mesh Indicator
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1A0A0F),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "CASCADE FAILOVER LADDER",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF8A80),
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "Bridges: $verifiedBridgesCount/${bridges.size} | Mesh: ${meshPeers.size} Peers",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFCC80)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "1. REALITY ➔ 2. CDN / TLS ➔ 3. Hysteria2 ➔ 4. WireGuard",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE0E0E0)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onNavigateToSecretChat,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Secret Chat", fontSize = 11.sp)
                        }

                        Button(
                            onClick = onNavigateToGodBrowser,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Hardened Browser", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Connection State Pill / Badge Sub-header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(status = connectionState.status)
            LatencyPill(latencyMs = connectionState.pingMs ?: activeProfile?.lastLatencyMs)
        }

        val connectedProfile = connectionState.activeProfile ?: activeProfile
        if (connectionState.isConnected && connectedProfile?.security?.let { it.isBlank() || it.equals("none", ignoreCase = true) } == true) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (AppTheme.colors.isDark) Color(0xFF2A2116) else Color(0xFFFFF8E8)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (AppTheme.colors.isDark) Color(0xFFFFB74D).copy(alpha = 0.45f) else Color(0xFFE6A23C).copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB74D))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "This profile has no TLS/REALITY encryption between this device and the VPN server. Use a TLS or REALITY profile for a confidential connection.",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Smart Connect Hero Card — or, in Trade mode, the fixed-exit card, or the Ghost Rider dashboard.
        if (settings.operationalMode == com.example.data.model.OperationalMode.GHOST_RIDER) {
            val chainIds = connectionState.activeProfile?.let { com.example.vpn.sidecar.ChainRunner.readChain(it) }?.hops?.map { it.engineId }
            com.example.ui.ghost.GhostRiderDashboard(
                model = com.example.vpn.safety.GhostRiderView.of(connectionState, chainIds),
                onRouteThroughTor = {
                    if (com.example.vpn.VpnController.isPermissionGranted(context)) com.example.vpn.ghost.GhostRiderController.routeThroughTor(context)
                    else onRequestVpnPermission()
                },
                onPanic = { com.example.vpn.ghost.GhostRiderController.panic(context) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else if (settings.operationalMode == com.example.data.model.OperationalMode.TRADE) {
            TradeModeCard(
                pinned = activeProfile,
                isConnected = connectionState.isConnected,
                tradeSet = topNodes,
                onConnect = {
                    vpnViewModel.toggleConnection(context) { onRequestVpnPermission() }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else smartRecommendation?.let { smart ->
            SmartRecommendationCard(
                smart = smart,
                buttonState = SmartButtonState.of(connectionState),
                runnersUp = topNodes.drop(1),
                onSmartConnect = {
                    vpnViewModel.connectSmart(context) {
                        onRequestVpnPermission()
                    }
                },
                onStop = { com.example.vpn.VpnController.stopVpn(context) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Error message banner if failed
        AnimatedVisibility(
            visible = connectionState.status == ConnectionStatus.FAILED && connectionState.errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (AppTheme.colors.isDark) Color(0xFF241416) else Color(0xFFFEF2F2)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.statusError.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = AppTheme.colors.statusError,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = connectionState.errorMessage ?: "Connection failed",
                            color = AppTheme.colors.statusError,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        // Only by the user's explicit choice: the server failed its real test here.
                        if (connectionState.canConnectAnyway && selectedProfile != null) {
                            TextButton(
                                onClick = { vpnViewModel.connectAnyway(context) { onRequestVpnPermission() } }
                            ) {
                                Text("Connect anyway", color = AppTheme.colors.statusError, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Connection Timer & Status HUD Pill
        val durationFormatted = formatDuration(connectionState.connectedDurationSeconds)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (connectionState.isConnected) {
                    AppTheme.colors.statusConnected.copy(alpha = 0.12f)
                } else {
                    AppTheme.colors.surfaceElevated.copy(alpha = 0.5f)
                },
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (connectionState.isConnected) {
                        AppTheme.colors.statusConnected.copy(alpha = 0.35f)
                    } else {
                        AppTheme.colors.borderSubtle
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (connectionState.isConnected) AppTheme.colors.statusConnected
                                else AppTheme.colors.textMuted
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = connectionState.statusLabel,
                        color = if (connectionState.isConnected) AppTheme.colors.statusConnected else AppTheme.colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (connectionState.isConnected) durationFormatted else "00:00:00",
                color = if (connectionState.isConnected) AppTheme.colors.textPrimary else AppTheme.colors.textMuted,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.5.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Central Power Button
        ConnectionButton(
            status = connectionState.status,
            onClick = {
                vpnViewModel.toggleConnection(context) {
                    onRequestVpnPermission()
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Active Server Card (exit country and ping are shown inside it while connected)
        ServerSelectorCard(
            profile = activeProfile,
            pingMs = connectionState.pingMs,
            exitCountryCode = connectionState.exitCountryCode
                ?.uppercase(Locale.US)
                ?.takeIf { connectionState.isConnected && it.length == 2 },
            onClick = onNavigateToServers
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Real-Time Traffic Dashboard Grid
        TrafficDashboardCard(
            uploadBytes = connectionState.uploadBytes,
            downloadBytes = connectionState.downloadBytes,
            uploadSpeedBps = connectionState.uploadSpeedBps,
            downloadSpeedBps = connectionState.downloadSpeedBps,
            vpnIp = connectionState.vpnIp ?: "172.19.0.1",
            isConnected = connectionState.isConnected,
            activeProfile = activeProfile
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Promotional Features & Architecture Showcase Carousel
        PromotionalFeatureShowcase(
            onNavigateToServers = onNavigateToServers,
            onNavigateToSecretChat = onNavigateToSecretChat,
            onNavigateToGodBrowser = onNavigateToGodBrowser,
            onNavigateToAiAgent = onNavigateToAiAgent
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showLandingTourDialog) {
        LandingTourDialog(
            onDismiss = { showLandingTourDialog = false },
            onNavigateToServers = onNavigateToServers
        )
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.borderSubtle)
    )
}

@Composable
private fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 7.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun TradeModeCard(
    pinned: com.example.data.model.VlessProfile?,
    isConnected: Boolean,
    tradeSet: List<SmartConnect.SmartSelection>,
    onConnect: () -> Unit
) {
    val c = AppTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("trade_mode_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = c.surfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.borderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Trade · fixed exit",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                    color = c.textSecondary
                )
                Spacer(modifier = Modifier.weight(1f))
                if (isConnected) {
                    StatusDot(c.statusConnected, 6.dp)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Locked", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.statusConnected)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = pinned?.name ?: "No server selected",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = pinned?.address ?: "Pick a server to pin as your fixed exit",
                fontSize = 12.5.sp,
                fontFamily = if (pinned != null) FontFamily.Monospace else FontFamily.Default,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Your exit IP, location and configuration stay the same every time. No switching, failover or recovery runs while Trade is on.",
                fontSize = 12.sp,
                color = c.textSecondary,
                lineHeight = 17.sp
            )
            if (tradeSet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HairlineDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Steadiest exits to pin",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                    color = c.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                tradeSet.take(3).forEachIndexed { index, node ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HairlineDivider()
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = node.profile.name,
                            fontSize = 14.sp,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = node.reasonStability,
                            fontSize = 12.sp,
                            color = c.textSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("trade_connect_button"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
            ) {
                Icon(imageVector = Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = if (isConnected) "Exit locked" else "Connect — fixed exit",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SmartRecommendationCard(
    smart: SmartConnect.SmartSelection,
    buttonState: SmartButtonState,
    runnersUp: List<SmartConnect.SmartSelection> = emptyList(),
    onSmartConnect: () -> Unit,
    onStop: () -> Unit
) {
    val c = AppTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_connect_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = c.surfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.borderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = c.primary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Optimal node",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                    color = c.textSecondary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Score ${"%.0f".format(smart.overallScore)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = c.textMuted,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary node: name + metrics on the left, measured ping with a live dot on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = smart.profile.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${smart.reasonDownload} · ${smart.reasonStability}",
                        fontSize = 12.sp,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(c.statusConnected, 6.dp)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = smart.profile.lastLatencyMs?.let { "$it ms" } ?: "—",
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = c.textPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (runnersUp.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HairlineDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "If it drops, switches to",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                    color = c.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                runnersUp.forEachIndexed { index, node ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HairlineDivider()
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 2}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = c.textMuted,
                            modifier = Modifier.width(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = node.profile.name,
                            fontSize = 14.sp,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "%.0f".format(node.overallScore),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = c.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SmartConnectButton(
                state = buttonState,
                onClick = { if (buttonState.tapStops) onStop() else onSmartConnect() }
            )
        }
    }
}

/**
 * Smart connect as a three-state toggle in the main power button's colours: lavender when off, a
 * pulsing gold while connecting, green when on. While connecting or on, a tap stops the connection.
 */
@Composable
private fun SmartConnectButton(state: SmartButtonState, onClick: () -> Unit) {
    val dark = AppTheme.colors.isDark
    val target = when (state) {
        SmartButtonState.OFF -> if (dark) Color(0xFFD0BCFF) else Color(0xFF6D28D9)
        SmartButtonState.CONNECTING -> if (dark) Color(0xFFFFB703) else Color(0xFFD97706)
        SmartButtonState.ON -> if (dark) Color(0xFF00F59B) else Color(0xFF059669)
    }
    val content = when (state) {
        SmartButtonState.OFF -> if (dark) Color(0xFF21134A) else Color.White
        SmartButtonState.CONNECTING -> if (dark) Color(0xFF2A1A00) else Color.White
        SmartButtonState.ON -> if (dark) Color(0xFF00281A) else Color.White
    }
    val fill by androidx.compose.animation.animateColorAsState(target, label = "smartFill")
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "smartPulse")
    val glow by pulse.animateFloat(
        initialValue = 0.18f,
        targetValue = if (state == SmartButtonState.CONNECTING) 0.45f else 0.18f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "smartGlow"
    )
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (state == SmartButtonState.OFF) Modifier
                else Modifier.border(3.dp, fill.copy(alpha = glow), shape)
            )
            .padding(if (state == SmartButtonState.OFF) 0.dp else 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(shape)
                .background(fill)
                .clickable(onClick = onClick)
                .testTag("smart_connect_button"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                SmartButtonState.CONNECTING -> androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(14.dp), color = content, strokeWidth = 2.dp,
                    trackColor = content.copy(alpha = 0.25f)
                )
                SmartButtonState.OFF -> Icon(imageVector = Icons.Default.Bolt, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
                SmartButtonState.ON -> Box(Modifier.size(8.dp).clip(CircleShape).background(content))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when (state) {
                    SmartButtonState.OFF -> "Smart connect"
                    SmartButtonState.CONNECTING -> "Connecting…"
                    SmartButtonState.ON -> "Smart connect on"
                },
                color = content,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (state.tapStops) {
                Text(
                    text = if (state == SmartButtonState.CONNECTING) "  · tap to stop" else "  · tap to disconnect",
                    color = content.copy(alpha = 0.78f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ServerSelectorCard(
    profile: VlessProfile?,
    pingMs: Long?,
    exitCountryCode: String?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("selected_server_card"),
        shape = RoundedCornerShape(18.dp),
        color = AppTheme.colors.surfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                if (exitCountryCode != null) {
                    Text(text = countryFlag(exitCountryCode), fontSize = 18.sp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = "Server",
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.name ?: "No Server Selected",
                    color = AppTheme.colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        exitCountryCode,
                        profile?.displaySubtitle ?: "Tap to choose a proxy server"
                    ).joinToString(" • "),
                    color = AppTheme.colors.textMuted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            LatencyPill(latencyMs = pingMs)
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Change Server",
                tint = AppTheme.colors.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun TrafficDashboardCard(
    uploadBytes: Long,
    downloadBytes: Long,
    uploadSpeedBps: Long,
    downloadSpeedBps: Long,
    vpnIp: String,
    isConnected: Boolean,
    activeProfile: VlessProfile?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = AppTheme.colors.surfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrafficMetric(
                    icon = Icons.Default.ArrowDownward,
                    tint = AppTheme.colors.metricDownload,
                    label = "Download",
                    speed = if (isConnected) TrafficStats.formatSpeed(downloadSpeedBps) else "0 B/s",
                    total = TrafficStats.formatBytes(downloadBytes),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(AppTheme.colors.borderSubtle)
                )
                Spacer(modifier = Modifier.width(12.dp))
                TrafficMetric(
                    icon = Icons.Default.ArrowUpward,
                    tint = AppTheme.colors.metricUpload,
                    label = "Upload",
                    speed = if (isConnected) TrafficStats.formatSpeed(uploadSpeedBps) else "0 B/s",
                    total = TrafficStats.formatBytes(uploadBytes),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Protocol and tunnel address
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppTheme.colors.surfaceElevated.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryDetail(
                    icon = Icons.Default.Lock,
                    tint = AppTheme.colors.primary,
                    text = activeProfile?.protocolType?.displayName ?: "VLESS"
                )
                TelemetryDetail(
                    icon = Icons.Default.Dns,
                    tint = AppTheme.colors.metricDownload,
                    text = vpnIp
                )
            }
        }
    }
}

@Composable
private fun TrafficMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    speed: String,
    total: String,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = speed,
                color = AppTheme.colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            Text(
                text = "$label • $total",
                color = AppTheme.colors.textMuted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TelemetryDetail(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    text: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = text, color = AppTheme.colors.textSecondary, fontSize = 10.5.sp, maxLines = 1)
    }
}

private fun countryFlag(code: String): String {
    if (code.length != 2 || code == "--") return "🌐"
    val upper = code.uppercase(Locale.US)
    return upper.map { ch ->
        String(Character.toChars(0x1F1E6 + (ch.code - 'A'.code)))
    }.joinToString("")
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
}
