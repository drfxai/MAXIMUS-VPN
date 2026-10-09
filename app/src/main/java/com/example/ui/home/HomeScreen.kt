package com.example.ui.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
                val accent = if (isGodMode) Color(0xFFFF5252) else AppTheme.colors.primary
                val toggleMode = {
                    val nextMode = if (isGodMode) {
                        com.example.data.model.OperationalMode.DAILY
                    } else {
                        com.example.data.model.OperationalMode.GOD_MODE
                    }
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
                            imageVector = if (isGodMode) Icons.Default.Shield else Icons.Default.Bolt,
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
                    Spacer(modifier = Modifier.width(8.dp))
                    // Mode switch
                    Surface(
                        onClick = toggleMode,
                        shape = RoundedCornerShape(10.dp),
                        color = accent.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = if (isGodMode) "Daily Mode" else "GOD Mode",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isGodMode) Color(0xFFFF8A80) else AppTheme.colors.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
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

        // Smart Connect Hero Card
        smartRecommendation?.let { smart ->
            SmartRecommendationCard(
                smart = smart,
                isConnected = connectionState.isConnected,
                onSmartConnect = {
                    vpnViewModel.connectSmart(context) {
                        onRequestVpnPermission()
                    }
                }
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
private fun SmartRecommendationCard(
    smart: SmartConnect.SmartSelection,
    isConnected: Boolean,
    onSmartConnect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_connect_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SMART RECOMMENDATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "Score: %.1f".format(smart.overallScore),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = smart.profile.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Ping: ${smart.reasonPing}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Speed: ${smart.reasonDownload}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSmartConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .testTag("smart_connect_button"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "Switch to Optimal Node" else "Smart Connect (Recommended)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
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
