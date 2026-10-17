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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
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

        // The Earth: shows the connection's state and the route; the power button sits on its edge.
        val earthMode = when {
            connectionState.isConnected -> EarthMode.ON
            connectionState.isBusy -> EarthMode.CONNECTING
            else -> EarthMode.OFF
        }
        val earthAccent = when (earthMode) {
            EarthMode.OFF -> AppTheme.colors.primary
            EarthMode.CONNECTING -> AppTheme.colors.statusWarning
            EarthMode.ON -> AppTheme.colors.statusConnected
        }
        val phoneCountry = remember { phoneCountry(context) }
        val exitCode = connectionState.exitCountryCode?.uppercase(Locale.US)?.takeIf { connectionState.isConnected && it.length == 2 }
        val route = EarthRoute(
            from = phoneCountry,
            fromTitle = phoneCountry?.let { "${ActiveCardModel.flag(it)} ${ActiveCardModel.countryName(it)}" } ?: "",
            fromSub = "You",
            to = exitCode,
            toTitle = exitCode?.let { "${ActiveCardModel.flag(it)} ${ActiveCardModel.countryName(it)}" } ?: "",
            toSub = connectionState.pingMs?.takeIf { it > 0 }?.let { "Exit · $it ms" } ?: "Exit"
        )
        EarthHero(mode = earthMode, route = route) {
            GlassPowerButton(
                accent = earthAccent,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp),
                onClick = { vpnViewModel.toggleConnection(context) { onRequestVpnPermission() } }
            )
        }
        Text(
            text = when (earthMode) {
                EarthMode.ON -> "Protected"
                EarthMode.CONNECTING -> "Finding a path…"
                EarthMode.OFF -> "Not connected"
            },
            color = if (earthMode == EarthMode.OFF) AppTheme.colors.textPrimary else earthAccent,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (earthMode == EarthMode.ON) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDuration(connectionState.connectedDurationSeconds),
                    color = AppTheme.colors.textSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(10.dp))
                LatencyPill(latencyMs = connectionState.pingMs ?: activeProfile?.lastLatencyMs)
            }
        } else {
            Text(
                text = if (earthMode == EarthMode.CONNECTING) "${connectionState.statusLabel} · tap the button to stop" else "Tap the button to connect",
                color = AppTheme.colors.textSecondary,
                fontSize = 13.sp
            )
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

        // Smart Connect's search for this attempt: where it stands, and the sheet with every stage.
        val smartMode = smartConnectMode(connectionState)
        var showSearch by remember { mutableStateOf(false) }
        if (smartMode != null) {
            SmartConnectEntry(connectionState, smartMode, onOpen = { showSearch = true })
            if (showSearch) {
                SmartConnectSheet(
                    state = connectionState,
                    mode = smartMode,
                    selectedName = selectedProfile?.takeIf { it.id == connectionState.selectedProfileId }?.name,
                    onStop = {
                        showSearch = false
                        com.example.vpn.VpnController.stopVpn(context)
                    },
                    onDismiss = { showSearch = false }
                )
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
        } else ActiveCardModel.of(connectionState, selectedProfile)?.let { model ->
            ActiveConnectionCard(
                model = model,
                buttonState = SmartButtonState.of(connectionState),
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

        // Active Server Card (exit country and ping are shown inside it while connected)
        ServerSelectorCard(
            profile = activeProfile,
            pingMs = connectionState.pingMs,
            exitCountryCode = connectionState.exitCountryCode
                ?.uppercase(Locale.US)
                ?.takeIf { connectionState.isConnected && it.length == 2 },
            onClick = onNavigateToServers
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** The power button over the Earth: frosted glass with a ring in the state's colour. */
@Composable
private fun GlassPowerButton(accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(AppTheme.colors.surfaceCard.copy(alpha = 0.78f))
            .border(1.5.dp, accent.copy(alpha = 0.8f), CircleShape)
            .clickable(onClickLabel = "Connect or disconnect", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.PowerSettingsNew, contentDescription = "Connect or disconnect", tint = accent, modifier = Modifier.size(28.dp))
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

/** The main card: the exit country, exit IP, ping and speeds while connected; the Nodes choice otherwise. */
@Composable
private fun ActiveConnectionCard(
    model: ActiveCardModel,
    buttonState: SmartButtonState,
    onSmartConnect: () -> Unit,
    onStop: () -> Unit
) {
    val c = AppTheme.colors
    val toneColor = when (model.tone) {
        ActiveCardModel.Tone.OK -> c.statusConnected
        ActiveCardModel.Tone.WARN -> c.statusWarning
        ActiveCardModel.Tone.MUTED -> c.textMuted
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_connect_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = c.surfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.borderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (model.live) toneColor else c.textMuted, 6.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = model.label, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp, color = c.textSecondary)
                Spacer(modifier = Modifier.weight(1f))
                model.configName?.let {
                    Text(
                        text = it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(c.surfaceCardHighlight),
                    contentAlignment = Alignment.Center
                ) { Text(text = model.flag, fontSize = 22.sp) }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = model.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = model.subtitle, fontSize = 12.sp, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            val valueColor = if (model.live) c.textPrimary else c.textMuted
            MetricCell("Exit IP", model.exitIp, valueColor, mono = model.live, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell("Ping", model.ping, valueColor, mono = true, modifier = Modifier.weight(1f))
                MetricCell("Status", model.status, if (model.live) toneColor else c.textMuted, mono = false, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell("↓ Download", model.download, valueColor, mono = true, modifier = Modifier.weight(1f))
                MetricCell("↑ Upload", model.upload, valueColor, mono = true, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(14.dp))
            SmartConnectButton(
                state = buttonState,
                onClick = { if (buttonState.tapStops) onStop() else onSmartConnect() }
            )
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, valueColor: Color, mono: Boolean, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(c.surfaceCardHighlight)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = c.textSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = valueColor,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
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

/** The country of the mobile network the phone is on, else its SIM's; null on Wi-Fi-only devices. */
private fun phoneCountry(context: Context): String? = runCatching {
    val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
    listOf(tm?.networkCountryIso, tm?.simCountryIso).firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.uppercase(Locale.US)
}.getOrNull()

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
