package com.example.ui.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.vpn.connections.BuiltinConnections
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.engine.RuntimeCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    modifier: Modifier = Modifier,
    onConfigureConnectionEngines: () -> Unit = {},
    onConfigureAether: () -> Unit = {},
    onConfigureChain: () -> Unit = {},
    onOpenAnalytics: () -> Unit = {},
    onOpenCloudflareRelay: () -> Unit = {}
) {
    val context = LocalContext.current
    val connectionState by vpnViewModel.connectionState.collectAsStateWithLifecycle()
    // Build 181 Phase 7: once, after the first verified connection, users without a server of their own and without a
    // relay are offered their own free Cloudflare relay (bottom sheet; also reachable from Panels → Cloudflare).
    var showRelayPrompt by rememberSaveable { mutableStateOf(false) }
    val connectedNow = connectionState.status == ConnectionStatus.CONNECTED
    LaunchedEffect(connectedNow) {
        if (!connectedNow) return@LaunchedEffect
        val show = withContext(Dispatchers.IO) {
            runCatching {
                val store = com.example.panels.cloudflare.relay.CloudflareRelayStore(context)
                val ownServer = com.example.panels.servers.ServerStore(context).load().isNotEmpty() ||
                    com.example.panels.PanelStore(context).load().isNotEmpty()
                val ok = com.example.panels.cloudflare.relay.CloudflareRelay.shouldPrompt(store.promptShown, connectedVerified = true,
                    hasOwnServer = ownServer, hasRelay = store.relay() != null)
                if (ok) store.promptShown = true
                ok
            }.getOrDefault(false)
        }
        if (show) showRelayPrompt = true
    }
    if (showRelayPrompt) com.example.ui.panels.CloudflareRelayPromptSheet(
        onConnect = { showRelayPrompt = false; onOpenCloudflareRelay() },
        onLater = { showRelayPrompt = false }
    )
    val selectedProfile by vpnViewModel.selectedProfile.collectAsStateWithLifecycle()
    val activeProfile = connectionState.activeProfile ?: selectedProfile
    val smartRecommendation by vpnViewModel.smartRecommendation.collectAsStateWithLifecycle()
    val topNodes by vpnViewModel.topNodes.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val unlockMode = settings.operationalMode == com.example.data.model.OperationalMode.UNLOCK
    val unlock by com.example.vpn.unlock.UnlockDns.state.collectAsStateWithLifecycle()
    var showUnlockSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(unlockMode) { if (unlockMode) withContext(Dispatchers.IO) { com.example.vpn.unlock.UnlockDns.load(context) } }

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
                        contentDescription = stringResource(R.string.a11y_ai_copilot),
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
                val isUnlock = settings.operationalMode == com.example.data.model.OperationalMode.UNLOCK
                val accent = when {
                    isGodMode -> Color(0xFFFF5252)
                    isGhost -> Color(0xFF7C5CFF)
                    else -> AppTheme.colors.primary
                }
                val setMode = { nextMode: com.example.data.model.OperationalMode ->
                    val changed = com.example.vpn.godmode.GodModeLogOnce.changes(settings.operationalMode, nextMode)
                    settingsViewModel.updateSettings(settings.copy(operationalMode = nextMode))
                    // B16: a re-tap of the selected mode starts or stops nothing (and logs nothing).
                    if (changed) {
                        if (nextMode == com.example.data.model.OperationalMode.GOD_MODE) {
                            com.example.vpn.godmode.PsiphonConduitBridge.enableGodModeBridges()
                            com.example.vpn.godmode.MaximusMeshManager.startMesh()
                        } else {
                            com.example.vpn.godmode.PsiphonConduitBridge.disableGodModeBridges()
                            com.example.vpn.godmode.MaximusMeshManager.stopMesh()
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        // Unlock mode: the card opens the Unlock DNS sheet.
                        .clickable(enabled = isUnlock, onClickLabel = stringResource(R.string.unlock_sheet_title)) { showUnlockSheet = true }
                        .testTag("mode_card_header"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isUnlock) {
                            UnlockKeyShieldIcon(tint = accent, size = 19.dp)
                        } else Icon(
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
                            text = stringResource(com.example.ui.language.UiText.modeTitle(settings.operationalMode)),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = AppTheme.colors.textPrimary
                        )
                        Text(
                            text = stringResource(com.example.ui.language.UiText.modeSubtitle(settings.operationalMode)),
                            fontSize = 10.5.sp,
                            color = AppTheme.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isUnlock) {
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppTheme.colors.textMuted, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                // Mode selector, segmented control: Daily / Unlock / GOD / Trade / Ghost.
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
                        com.example.data.model.OperationalMode.UNLOCK to "Unlock",
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
                                .padding(vertical = 8.dp)
                                .testTag("mode_chip_${chipMode.name.lowercase()}"),
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
                                    text = stringResource(R.string.ui_cascade_failover_ladder),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF8A80),
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    // B16: bridges and the LAN mesh do not run in this build; "0/0 | 0 Peers" read as a fault.
                                    text = if (bridges.isEmpty() && meshPeers.isEmpty()) stringResource(R.string.god_bridges_mesh_unavailable)
                                        else stringResource(R.string.ui_bridges_mesh_peers, verifiedBridgesCount, bridges.size, meshPeers.size),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFCC80)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                // B16: the real order (build 177 showed REALITY ➔ CDN ➔ Hysteria2 ➔ WireGuard and connected via Lantern).
                                text = stringResource(R.string.god_ladder_steps),
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
                            Text(stringResource(R.string.god_secret_chat_preview), fontSize = 11.sp)
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
                            Text(stringResource(R.string.ui_hardened_browser), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // The Earth: shows the connection's state and the route; the power button sits on its edge.
        val earthMode = when {
            connectionState.isConnected -> EarthMode.ON
            // Build 179 device test (N1): a tunnel that is up without traffic holds the phone's traffic; it was shown as
            // "Not connected" while every request was blocked.
            connectionState.isBusy || HomeFailure.holdsTraffic(connectionState) -> EarthMode.CONNECTING
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
        EarthHero(mode = earthMode, route = if (unlockMode) route.copy(to = null, toTitle = "", toSub = "") else route) {
            if (unlockMode && connectionState.isConnected) UnlockServiceOrbit(unlock.services)
            GlassPowerButton(
                accent = earthAccent,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp),
                onClick = { vpnViewModel.toggleConnection(context) { onRequestVpnPermission() } }
            )
        }
        val blockNotices = listOf(stringResource(R.string.connect_failed_block_released),
            stringResource(R.string.connect_failed_block_kill_switch), stringResource(R.string.connect_failed_block_kept))
        // Build 179 device test (N10): Unlock's failure was only below the fold; the header now says it.
        val failureReason = if (unlockMode) HomeFailure.unlockReason(connectionState) else HomeFailure.reason(connectionState, blockNotices)
        Text(
            text = when (earthMode) {
                EarthMode.ON -> stringResource(if (unlockMode) R.string.unlock_state_unlocked else R.string.ui_connected_2)
                EarthMode.CONNECTING -> stringResource(if (unlockMode) R.string.unlock_state_checking
                    else if (HomeFailure.holdsTraffic(connectionState)) R.string.home_no_traffic_yet else R.string.ui_finding_a_path)
                EarthMode.OFF -> stringResource(if (failureReason != null && unlockMode) R.string.unlock_failed_header
                    else if (failureReason != null) R.string.home_failed_title else R.string.ui_not_connected)
            },
            color = if (earthMode == EarthMode.OFF && failureReason != null) AppTheme.colors.statusError
                else if (earthMode == EarthMode.OFF) AppTheme.colors.textPrimary else earthAccent,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (earthMode == EarthMode.ON && unlockMode) {
            val server = unlock.active
            if (server != null) UnlockStatusPill(server, unlock.activeMs ?: connectionState.pingMs)
        } else if (earthMode == EarthMode.OFF && unlockMode && failureReason == null) {
            Text(
                text = stringResource(R.string.unlock_tap_to_connect),
                color = AppTheme.colors.textSecondary,
                fontSize = 13.sp
            )
        } else if (earthMode == EarthMode.ON) {
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
        } else if (earthMode == EarthMode.OFF && failureReason != null) {
            // B7: the specific reason right under the headline (the full text stays in the error card below).
            Text(
                text = failureReason,
                color = AppTheme.colors.textSecondary,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp).testTag("home_failure_reason")
            )
        } else {
            Text(
                text = if (earthMode == EarthMode.CONNECTING) stringResource(R.string.ui_tap_the_button_to_stop, stringResource(com.example.ui.language.UiText.status(connectionState))) else stringResource(R.string.ui_tap_the_button_to_connect),
                color = AppTheme.colors.textSecondary,
                fontSize = 13.sp
            )
        }

        val connectedProfile = connectionState.activeProfile ?: activeProfile
        val directRoute = connectedProfile?.let { com.example.vpn.sidecar.ServerlessSidecar.handles(it) &&
            com.example.vpn.engine.ProfileExtras.read(it).optString("exitNode").isBlank() } == true
        if (connectionState.isConnected && !unlockMode) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ui_active_connection_path), color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                    Text(com.example.data.model.ConnectionRoute.withExitCountry(
                        connectionState.activeRoute ?: "Route not reported", exitCode), color = AppTheme.colors.primary,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (directRoute) stringResource(R.string.ui_device_direct_dpi_bypass_internet)
                        else stringResource(R.string.ui_device_android_vpn_internet, connectionState.activeRoute ?: "selected route"),
                        color = AppTheme.colors.textPrimary, fontSize = 12.sp)
                    Text(when (connectionState.verification) {
                            com.example.vpn.verify.RouteVerification.ENGINE_PROXY_VERIFIED -> stringResource(R.string.route_engine_proxy_unconfirmed)
                            com.example.vpn.verify.RouteVerification.DEVICE_TRAFFIC_VERIFIED -> stringResource(R.string.route_device_traffic_verified)
                            else -> connectionState.verification.title
                        },
                        color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    if (connectionState.isConnected && connectionState.filteredSitesPartial) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB74D),
                                modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.filtered_sites_partial), color = AppTheme.colors.textPrimary,
                                fontSize = 12.sp, modifier = Modifier.weight(1f).testTag("filtered_sites_partial"))
                        }
                    }
                    if (connectionState.bulkLimited) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB74D),
                                modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.connection_limited_bulk), color = AppTheme.colors.textPrimary,
                                fontSize = 12.sp, modifier = Modifier.weight(1f).testTag("connection_limited_bulk"))
                            TextButton(onClick = { com.example.vpn.VpnController.tryAnotherServer(context) }) {
                                Text(stringResource(R.string.connection_try_another_server), fontSize = 12.sp)
                            }
                        }
                        // A pinned session (power button) never moves by itself; say what the button does there.
                        if (!connectionState.smartSession) {
                            Text(stringResource(R.string.connection_limited_bulk_pinned_hint),
                                color = AppTheme.colors.textSecondary, fontSize = 11.sp,
                                modifier = Modifier.testTag("connection_limited_bulk_pinned_hint"))
                        }
                    }
                }
            }
        }
        TextButton(onClick = onOpenAnalytics, modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) {
            Icon(Icons.Filled.BarChart, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ui_connection_analytics))
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(16.dp))
        }
        // Android's own kill switch: suggested only while connected and only when it is measured off.
        // Not in Unlock mode: it needs "Block connections without VPN" off, and has no tunnel to leak from.
        LeakProtectionCard(connected = connectionState.isConnected && !unlockMode)
        if (connectionState.isConnected && (directRoute || (connectedProfile?.let {
            !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) &&
                (it.security.isBlank() || it.security.equals("none", ignoreCase = true)) } == true))) {
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
                        text = if (directRoute) stringResource(R.string.ui_direct_bypass_keeps_your_isp) else stringResource(R.string.ui_this_profile_has_no_tls),
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
                        com.example.vpn.VpnController.stopVpn(context, com.example.vpn.StopOrigin.SMART_SHEET)
                    },
                    onDismiss = { showSearch = false }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Smart Connect Hero Card — or, in Trade mode, the fixed-exit card, or the Ghost Rider dashboard.
        if (unlockMode) {
            UnlockDnsCard(state = unlock, onOpen = { showUnlockSheet = true })
            Spacer(modifier = Modifier.height(16.dp))
        } else if (settings.operationalMode == com.example.data.model.OperationalMode.GHOST_RIDER) {
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
                    vpnViewModel.toggleConnection(context, com.example.vpn.StopOrigin.TRADE_CARD) { onRequestVpnPermission() }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else ActiveCardModel.of(connectionState, selectedProfile, result = { p ->
            // Build 179 (N4): this connection's last result on the current network, from the 24 h engine ledger.
            com.example.vpn.engine.registry.NetworkResultsReader.current(context)
                .firstOrNull { it.path == com.example.vpn.diagnostics.EngineStages.engineOf(p) }
        })?.let { model ->
            ActiveConnectionCard(
                model = model,
                onClear = { vpnViewModel.clearSelection() },
                downloadBps = connectionState.downloadSpeedBps,
                uploadBps = connectionState.uploadSpeedBps,
                buttonState = SmartButtonState.of(connectionState),
                onSmartConnect = {
                    vpnViewModel.connectSmart(context) {
                        onRequestVpnPermission()
                    }
                },
                onStop = { com.example.vpn.VpnController.stopVpn(context, com.example.vpn.StopOrigin.SMART_BUTTON) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (settings.operationalMode != com.example.data.model.OperationalMode.TRADE && !unlockMode) {
            ConfigFreeConnectButton(
                state = connectionState,
                onAutoConnect = {
                    vpnViewModel.startOrRequestPermission(context, BuiltinConnections.resolve(context, ConfigFreeMethod.AETHER_WARP.id) ?: ConfigFreeMethod.AETHER_WARP.profile(), smart = true) {
                        onRequestVpnPermission()
                    }
                },
                onStop = { com.example.vpn.VpnController.stopVpn(context, com.example.vpn.StopOrigin.CONFIG_FREE_BUTTON) },
                onConfigureEngines = onConfigureConnectionEngines,
                onConfigureAether = onConfigureAether,
                onConfigureChain = onConfigureChain,
                onConnect = { method ->
                    vpnViewModel.startOrRequestPermission(context, BuiltinConnections.resolve(context, method.id) ?: method.profile(), exact = true) {
                        onRequestVpnPermission()
                    }
                }
            )
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
                                Text(stringResource(R.string.ui_connect_anyway), color = AppTheme.colors.statusError, fontSize = 12.sp)
                            }
                        }
                        // The user's own Aether pick failed fast here: one tap to the connection verified on this network.
                        val suggestedId = connectionState.suggestedProfileId
                        val suggestedName = connectionState.suggestedProfileName
                        if (suggestedId != null && suggestedName != null) {
                            TextButton(
                                onClick = { vpnViewModel.connectSuggested(context, suggestedId) { onRequestVpnPermission() } }
                            ) {
                                Text(stringResource(R.string.ui_connect_with_verified, suggestedName),
                                    color = AppTheme.colors.statusError, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // The power button's own config stopped carrying traffic and is being restarted (never switched silently):
        // say so, and offer Smart Connect or the path verified on this network with one tap.
        val pinnedRestartOf = connectionState.pinnedRestartOf
        AnimatedVisibility(
            visible = pinnedRestartOf != null && connectionState.status != ConnectionStatus.FAILED &&
                connectionState.status != ConnectionStatus.DISCONNECTED,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag("pinned_restart_card"),
                colors = CardDefaults.cardColors(
                    containerColor = if (AppTheme.colors.isDark) Color(0xFF2A2116) else Color(0xFFFFF8E8)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ui_pinned_stalled_reconnecting, pinnedRestartOf.orEmpty()),
                            color = AppTheme.colors.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Row {
                            TextButton(onClick = { vpnViewModel.connectSmart(context) { onRequestVpnPermission() } }) {
                                Text(stringResource(R.string.ui_smart_connect), fontSize = 12.sp)
                            }
                            val restartSuggestedId = connectionState.suggestedProfileId
                            val restartSuggestedName = connectionState.suggestedProfileName
                            if (restartSuggestedId != null && restartSuggestedName != null) {
                                TextButton(onClick = { vpnViewModel.connectSuggested(context, restartSuggestedId) { onRequestVpnPermission() } }) {
                                    Text(stringResource(R.string.ui_connect_with_verified, restartSuggestedName), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showUnlockSheet) {
            UnlockDnsSheet(onDismiss = { showUnlockSheet = false })
        }

        // Active Server Card (exit country and ping are shown inside it while connected); Unlock uses none.
        if (!unlockMode) ServerSelectorCard(
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
                    text = stringResource(R.string.ui_trade_fixed_exit),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                    color = c.textSecondary
                )
                Spacer(modifier = Modifier.weight(1f))
                if (isConnected) {
                    StatusDot(c.statusConnected, 6.dp)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(stringResource(R.string.ui_locked), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.statusConnected)
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
                text = stringResource(R.string.ui_your_exit_ip_location_and),
                fontSize = 12.sp,
                color = c.textSecondary,
                lineHeight = 17.sp
            )
            if (tradeSet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HairlineDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.ui_steadiest_exits_to_pin),
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
                    text = if (isConnected) stringResource(R.string.ui_exit_locked) else stringResource(R.string.ui_connect_fixed_exit),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * The main card, compact: flag, exit country and exit IP, ping with signal bars and status, then a live
 * line of the last 30 s of download and upload speed. Off, it is one row with the config selected in Nodes.
 * While traffic is verified a ring pulses out from the flag.
 */
@Composable
private fun ActiveConnectionCard(
    model: ActiveCardModel,
    downloadBps: Long,
    uploadBps: Long,
    buttonState: SmartButtonState,
    onSmartConnect: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit = {}
) {
    val c = AppTheme.colors
    val toneColor = when (model.tone) {
        ActiveCardModel.Tone.OK -> c.statusConnected
        ActiveCardModel.Tone.WARN -> c.statusWarning
        ActiveCardModel.Tone.MUTED -> c.textMuted
    }
    Column(modifier = Modifier.fillMaxWidth().testTag("smart_connect_card")) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = c.surfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.borderSubtle)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FlagRing(model.flag, if (model.live) toneColor else c.textMuted, pulse = model.live && model.tone == ActiveCardModel.Tone.OK)
                Spacer(modifier = Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = if (model.smartSearch) stringResource(R.string.home_card_smart_title) else model.title,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val searching = if (model.smartSearch) stringResource(R.string.home_card_smart_searching) else null
                    Text(
                        text = when {
                            model.live -> model.exitIp
                            searching != null -> searching
                            model.lastTried -> stringResource(R.string.home_last_tried) + " · " +
                                model.subtitle.replaceFirstChar { it.lowercase() }
                            else -> ActiveCardModel.offLine(model)
                        },
                        fontSize = 12.5.sp, color = c.textSecondary,
                        fontFamily = if (model.live) FontFamily.Monospace else FontFamily.Default,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (model.live) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(text = model.ping, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = c.textPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            SignalBars(model.bars, toneColor, c.textMuted)
                        }
                        Text(text = model.status, fontSize = 11.5.sp, color = toneColor)
                    }
                } else {
                    // Build 179 device test (N9): the selection (or the last tried connection) can be cleared back to
                    // "No server selected".
                    if (model.clearable && !model.smartSearch) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.home_clear_selection_desc),
                            tint = c.textMuted,
                            modifier = Modifier.padding(end = 6.dp).size(28.dp).clip(CircleShape)
                                .clickable(onClick = onClear).padding(6.dp).testTag("home_card_clear")
                        )
                    }
                    Text(
                        text = if (model.smartSearch) stringResource(R.string.home_card_smart_status) else model.status,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.textMuted,
                        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(c.surfaceCardHighlight).padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
            if (model.live) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeedLine(
                        live = model.download != ActiveCardModel.DASH,
                        downloadBps = downloadBps, uploadBps = uploadBps,
                        modifier = Modifier.weight(1f).height(34.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(text = "↓ ${model.download}", fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = c.textPrimary, maxLines = 1)
                        Text(text = "↑ ${model.upload}", fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = c.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
    SmartConnectButton(
        state = buttonState,
        onClick = { if (buttonState.tapStops) onStop() else onSmartConnect() }
    )
    }
}

/** The flag in a ring of the state's colour; while [pulse], a second ring grows and fades every 2 s. */
@Composable
private fun FlagRing(flag: String, color: Color, pulse: Boolean) {
    val c = AppTheme.colors
    val t = if (pulse) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "flagPulse").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(2000, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
            ),
            label = "flagPulseT"
        ).value
    } else 0f
    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(44.dp)) {
            val r = size.minDimension / 2f - 2.dp.toPx()
            if (pulse) drawCircle(color.copy(alpha = 0.45f * (1f - t)), radius = r * (0.9f + 0.25f * t),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
            drawCircle(color.copy(alpha = 0.6f), radius = r * 0.9f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        }
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(c.surfaceCardHighlight),
            contentAlignment = Alignment.Center
        ) { Text(text = flag, fontSize = 17.sp) }
    }
}

/** Four bars; the first [filled] in [on], the rest in [off]. */
@Composable
private fun SignalBars(filled: Int, on: Color, off: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(bottom = 3.dp)) {
        listOf(4, 7, 10, 13).forEachIndexed { i, h ->
            Box(
                modifier = Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(1.dp))
                    .background(if (i < filled) on else off.copy(alpha = 0.35f))
            )
        }
    }
}

/**
 * The last [SPEED_SAMPLES] seconds of measured speed, sampled once a second: download as a filled
 * line in the accent colour, upload as a thin blue line. Flat while traffic is not verified.
 */
@Composable
private fun SpeedLine(live: Boolean, downloadBps: Long, uploadBps: Long, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val down = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    val up = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    val latestDown = androidx.compose.runtime.rememberUpdatedState(downloadBps)
    val latestUp = androidx.compose.runtime.rememberUpdatedState(uploadBps)
    androidx.compose.runtime.LaunchedEffect(live) {
        down.clear(); up.clear()
        while (live) {
            down.add(latestDown.value.toFloat()); up.add(latestUp.value.toFloat())
            while (down.size > SPEED_SAMPLES) down.removeAt(0)
            while (up.size > SPEED_SAMPLES) up.removeAt(0)
            kotlinx.coroutines.delay(1000)
        }
    }
    val accent = c.primary
    val upColor = Color(0xFF7AA7F7)
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val peak = maxOf(down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f, 1f)
        fun pathOf(values: List<Float>): androidx.compose.ui.graphics.Path = androidx.compose.ui.graphics.Path().apply {
            val step = w / (SPEED_SAMPLES - 1)
            val start = SPEED_SAMPLES - values.size
            values.forEachIndexed { i, v ->
                val x = (start + i) * step
                val y = h - 2.dp.toPx() - (v / peak) * (h - 6.dp.toPx())
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        if (down.size < 2) {
            drawLine(c.borderSubtle, androidx.compose.ui.geometry.Offset(0f, h - 2.dp.toPx()),
                androidx.compose.ui.geometry.Offset(w, h - 2.dp.toPx()), strokeWidth = 1.dp.toPx())
            return@Canvas
        }
        val line = pathOf(down)
        val firstX = (SPEED_SAMPLES - down.size) * (w / (SPEED_SAMPLES - 1))
        val fill = androidx.compose.ui.graphics.Path().apply {
            addPath(line); lineTo(w, h); lineTo(firstX, h); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0f))))
        drawPath(line, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx()))
        drawPath(pathOf(up), upColor.copy(alpha = 0.8f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()))
        val lastY = h - 2.dp.toPx() - (down.last() / peak) * (h - 6.dp.toPx())
        drawCircle(accent, radius = 2.6.dp.toPx(), center = androidx.compose.ui.geometry.Offset(w, lastY))
    }
}

private const val SPEED_SAMPLES = 30

/**
 * Smart connect as a three-state toggle in the main power button's colours: lavender when off, a
 * pulsing gold while connecting, green when on. While connecting or on, a tap stops the connection.
 */
@Composable
private fun SmartConnectButton(state: SmartButtonState, onClick: () -> Unit) {
    val dark = AppTheme.colors.isDark
    val target = when (state) {
        SmartButtonState.OFF -> if (dark) Color(0xFFD0BCFF) else Color(0xFF6D28D9)
        SmartButtonState.CONNECTING, SmartButtonState.MANUAL -> if (dark) Color(0xFFFFB703) else Color(0xFFD97706)
        SmartButtonState.ON -> if (dark) Color(0xFF00F59B) else Color(0xFF059669)
    }
    val content = when (state) {
        SmartButtonState.OFF -> if (dark) Color(0xFF21134A) else Color.White
        SmartButtonState.CONNECTING, SmartButtonState.MANUAL -> if (dark) Color(0xFF2A1A00) else Color.White
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
                SmartButtonState.ON, SmartButtonState.MANUAL -> Box(Modifier.size(8.dp).clip(CircleShape).background(content))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when (state) {
                    SmartButtonState.OFF -> stringResource(R.string.ui_auto_connect)
                    SmartButtonState.CONNECTING -> stringResource(R.string.ui_connecting_2)
                    SmartButtonState.ON -> stringResource(R.string.ui_auto_connect_on)
                    SmartButtonState.MANUAL -> stringResource(R.string.ui_your_pick_running)
                },
                color = content,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (state.tapStops) {
                Text(
                    text = if (state == SmartButtonState.CONNECTING) stringResource(R.string.ui_tap_to_stop) else stringResource(R.string.ui_tap_to_disconnect),
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
                        contentDescription = stringResource(R.string.ui_server),
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.name ?: stringResource(R.string.home_no_server_selected),
                    color = AppTheme.colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        exitCountryCode,
                        profile?.displaySubtitle ?: stringResource(R.string.home_tap_to_choose_server)
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
                contentDescription = stringResource(R.string.a11y_change_server),
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


@Composable
private fun ConfigFreeConnectButton(
    state: com.example.data.model.ConnectionState,
    onAutoConnect: () -> Unit,
    onStop: () -> Unit,
    onConfigureEngines: () -> Unit,
    onConfigureAether: () -> Unit,
    onConfigureChain: () -> Unit,
    onConnect: (ConfigFreeMethod) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var requested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        if (state.status == ConnectionStatus.DISCONNECTED) requested = false
    }
    val enabled = !state.isTunnelUp && !state.isBusy
    val active = requested && (state.isTunnelUp || state.isBusy)
    val dark = AppTheme.colors.isDark
    val accent = if (dark) Color(0xFF70EFF3) else Color(0xFF00646D)
    val status = ConfigFreeStatusModel.of(requested, state)
    val target = when (status) {
        ConfigFreeStatus.READY -> accent
        ConfigFreeStatus.CHECKING, ConfigFreeStatus.LIMITED, ConfigFreeStatus.NO_TRAFFIC -> if (dark) Color(0xFFFFC568) else Color(0xFF805000)
        ConfigFreeStatus.RECONNECTING -> if (dark) Color(0xFFC8AAFF) else Color(0xFF6533AA)
        ConfigFreeStatus.VERIFIED -> if (dark) Color(0xFF6DF5BC) else Color(0xFF006B4C)
        ConfigFreeStatus.INTERRUPTED, ConfigFreeStatus.FAILED -> if (dark) Color(0xFFFF8888) else Color(0xFFA02336)
        ConfigFreeStatus.STOPPING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val glow by androidx.compose.animation.animateColorAsState(target, label = "configFreeStatus")
    val shape = RoundedCornerShape(24.dp)
    OutlinedButton(
        onClick = {
            if (active) onStop()
            else {
                requested = true
                onAutoConnect()
            }
        },
        enabled = status != ConfigFreeStatus.STOPPING && (enabled || active),
        shape = shape,
        border = BorderStroke(1.dp, glow),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = glow),
        modifier = Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(glow.copy(alpha = .15f),
                MaterialTheme.colorScheme.surface, glow.copy(alpha = .08f))))
            .testTag("config_free_auto_connect")
    ) {
        if (status == ConfigFreeStatus.CHECKING || status == ConfigFreeStatus.RECONNECTING || status == ConfigFreeStatus.NO_TRAFFIC) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(16.dp), color = glow, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(when {
            status == ConfigFreeStatus.STOPPING -> stringResource(R.string.ui_disconnecting)
            active && state.status == ConnectionStatus.CONNECTED -> stringResource(R.string.ui_disconnect_2)
            active -> stringResource(R.string.ui_cancel)
            status == ConfigFreeStatus.INTERRUPTED || status == ConfigFreeStatus.FAILED -> stringResource(R.string.ui_retry_without_a_config)
            else -> stringResource(R.string.ui_connect_without_a_config)
        }, fontWeight = FontWeight.SemiBold)
    }
    status.detail?.let { detail ->
        Text(stringResource(detail), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }
    TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ui_advanced_options))
    }

    if (open) {
        ConfigFreeMethodPicker(
            accent = accent,
            onDismiss = { open = false },
            onSetup = { open = false; onConfigureEngines() },
            onAetherSettings = { open = false; onConfigureAether() },
            onChain = { open = false; onConfigureChain() },
            onSelect = { method -> open = false; requested = true; onConnect(method) }
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConfigFreeMethodPicker(
    accent: Color,
    onDismiss: () -> Unit,
    onSetup: () -> Unit,
    onAetherSettings: () -> Unit,
    onChain: () -> Unit,
    onSelect: (ConfigFreeMethod) -> Unit
) {
    val context = LocalContext.current
    var transports by rememberSaveable { mutableStateOf(false) }
    var availability by remember { mutableStateOf<Map<ConfigFreeMethod, String?>?>(null) }
    // Build 178: cf=0 already known on this network: Aether rows say so before a pick starts a scan.
    var cloudflareFiltered by remember { mutableStateOf(false) }
    var shirokhorshidSettings by remember { mutableStateOf(false) }
    if (shirokhorshidSettings) com.example.ui.connections.ShirokhorshidSettingsDialog(onDismiss = { shirokhorshidSettings = false })
    LaunchedEffect(Unit) {
        cloudflareFiltered = withContext(Dispatchers.IO) {
            com.example.vpn.connections.AetherCloudflareNotice.knownFiltered(context)
        }
        availability = withContext(Dispatchers.IO) {
            ConfigFreeMethod.entries.associateWith { method ->
                runCatching {
                    val profile = BuiltinConnections.resolve(context, method.id) ?: method.profile()
                    RuntimeCapabilities.unsupportedReason(profile) ?: method.missingDependency()
                }.getOrElse { "Check this method’s settings." }
            }
        }
    }
    val c = AppTheme.colors
    val dark = c.isDark
    // Counts follow the catalogue: every method row, plus the two setup cards on the Aether & chains tab.
    val methodCount = ConfigFreePickerCounts.methods()
    val chainCount = ConfigFreePickerCounts.chains()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        scrimColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.35f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(
            accent.copy(alpha = if (dark) .10f else .06f), Color.Transparent))).testTag("config_free_picker")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.ui_connect_without_a_config), color = c.textPrimary, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 27.sp)
                    Text(if (transports) stringResource(R.string.ui_select_a_warp_transport_or) else stringResource(R.string.ui_choose_a_built_in_connection),
                        color = c.textSecondary, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp).clip(CircleShape).border(1.dp, c.borderSubtle, CircleShape)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ui_close),
                        tint = c.textPrimary, modifier = Modifier.size(20.dp))
                }
            }
            // In-palette segmented tabs with counts.
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceCard)
                .border(1.dp, c.borderSubtle, RoundedCornerShape(16.dp)).padding(4.dp)) {
                PickerTab(Modifier.weight(1f), Icons.Rounded.GridView,
                    stringResource(R.string.ui_methods), methodCount, !transports) { transports = false }
                PickerTab(Modifier.weight(1f), Icons.Filled.Link,
                    stringResource(R.string.ui_aether_chains), chainCount, transports) { transports = true }
            }
            androidx.compose.runtime.key(transports) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    val methods = availability
                    if (transports) {
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SetupCard(Modifier.weight(1f), Icons.Filled.Link, stringResource(R.string.ui_build_a_connection_chain),
                                stringResource(R.string.ui_combine_saved_nodes_and_built), accent, highlighted = true, onClick = onChain)
                            SetupCard(Modifier.weight(1f), Icons.Filled.Tune, stringResource(R.string.ui_aether_settings),
                                stringResource(R.string.ui_configure_transport_and_registration), accent, highlighted = false, onClick = onAetherSettings)
                        }
                    }
                    if (methods == null) {
                        Text(stringResource(R.string.ui_checking_installed_engines), color = c.textSecondary, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        val groups = if (transports) listOf(
                            Triple(R.string.ui_picker_transports, R.string.ui_picker_transports_hint,
                                listOf(ConfigFreeMethod.AETHER_H2, ConfigFreeMethod.AETHER_H3, ConfigFreeMethod.AETHER_WG)),
                            Triple(R.string.ui_picker_nested, R.string.ui_picker_nested_hint,
                                listOf(ConfigFreeMethod.AETHER_GOOL, ConfigFreeMethod.AETHER_GOOL_CLASSIC, ConfigFreeMethod.AETHER_MIM)),
                            Triple(R.string.ui_picker_chains, R.string.ui_picker_chains_hint,
                                listOf(ConfigFreeMethod.AETHER_PSIPHON, ConfigFreeMethod.AETHER_TOR, ConfigFreeMethod.AETHER_LANTERN))
                        ) else listOf(
                            Triple(R.string.ui_picker_direct_engines, R.string.ui_picker_direct_engines_hint,
                                listOf(ConfigFreeMethod.AETHER_WARP, ConfigFreeMethod.SERVERLESS, ConfigFreeMethod.SERVERLESS_LANTERN,
                                    ConfigFreeMethod.PERSONAL_ECH)),
                            Triple(R.string.ui_picker_relay_networks, R.string.ui_picker_relay_networks_hint,
                                ConfigFreeMethod.entries.filter { it.aetherTransport == null &&
                                    it != ConfigFreeMethod.AETHER_WARP && it != ConfigFreeMethod.SERVERLESS &&
                                    it != ConfigFreeMethod.SERVERLESS_LANTERN && it != ConfigFreeMethod.PERSONAL_ECH })
                        )
                        // Anything in the catalogue that no group names still gets a row, at the end of its tab.
                        val grouped = groups.flatMap { it.third }.toSet()
                        val rest = ConfigFreeMethod.entries.filter { (it.aetherTransport != null) == transports && it !in grouped }
                        val shown = if (rest.isEmpty()) groups else groups + Triple(0, 0, rest)
                        shown.forEach { (label, hint, entries) ->
                            if (label != 0) PickerGroupLabel(stringResource(label), stringResource(hint))
                            else Spacer(Modifier.height(12.dp))
                            PickerGroupCard {
                                entries.forEachIndexed { index, method ->
                                    val problem = methods[method]
                                    if (index > 0) PickerDivider()
                                    val cfWarning = problem == null && cloudflareFiltered &&
                                        com.example.vpn.connections.AetherCloudflareNotice.appliesTo(method)
                                    ConfigMethodRow(method.title, problem ?: if (cfWarning) stringResource(R.string.picker_aether_cf_filtered,
                                            (com.example.vpn.connections.ConnectionEngineRunner.CF_FILTERED_MANUAL_BUDGET_MS / 1000).toInt())
                                        else stringResource(ConfigFreeMethodText.description(method)), method.pickerIcon(), accent,
                                        if (problem == null) ({ onSelect(method) }) else null,
                                        badge = method.pickerBadge(), chip = when (method) {
                                            ConfigFreeMethod.AETHER_H2 -> "H2"
                                            ConfigFreeMethod.AETHER_H3 -> "H3"
                                            ConfigFreeMethod.AETHER_WG -> "WG"
                                            else -> null
                                        })
                                    if (method == ConfigFreeMethod.SHIROKHORSHID && problem == null) {
                                        PickerDivider()
                                        ConfigMethodRow(stringResource(R.string.ui_shirokhorshid_settings),
                                            stringResource(R.string.ui_shirokhorshid_settings_detail), Icons.Filled.Tune, accent,
                                            { shirokhorshidSettings = true }, indented = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (availability?.get(ConfigFreeMethod.PSIPHON) != null) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onSetup).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, null, tint = c.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_psiphon_setup), color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

private fun ConfigFreeMethod.pickerIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    ConfigFreeMethod.AETHER_WARP -> Icons.Filled.Cloud
    ConfigFreeMethod.SERVERLESS -> Icons.Filled.Bolt
    ConfigFreeMethod.TOR_SNOWFLAKE -> Icons.Rounded.Opacity
    ConfigFreeMethod.PSIPHON -> Icons.Filled.Shield
    ConfigFreeMethod.SHIROKHORSHID -> Icons.Filled.WbSunny
    ConfigFreeMethod.PSIPHON_RADIANCE, ConfigFreeMethod.SHIROKHORSHID_RADIANCE, ConfigFreeMethod.AETHER_LANTERN,
    ConfigFreeMethod.SERVERLESS_LANTERN -> Icons.Rounded.Layers
    ConfigFreeMethod.RADIANCE -> Icons.Filled.Lightbulb
    ConfigFreeMethod.PERSONAL_ECH -> Icons.Filled.Shield
    ConfigFreeMethod.CLOUDFLARE_RELAY -> Icons.Filled.Cloud
    ConfigFreeMethod.AETHER_GOOL -> Icons.Rounded.CropFree
    ConfigFreeMethod.AETHER_GOOL_CLASSIC, ConfigFreeMethod.AETHER_MIM -> Icons.Rounded.Layers
    ConfigFreeMethod.AETHER_PSIPHON -> Icons.Filled.Shield
    ConfigFreeMethod.AETHER_TOR -> Icons.Rounded.Opacity
    else -> Icons.Filled.Cloud
}

@Composable
private fun ConfigFreeMethod.pickerBadge(): Pair<String, Boolean>? = when (this) {
    ConfigFreeMethod.SHIROKHORSHID -> stringResource(R.string.ui_picker_new) to true
    ConfigFreeMethod.RADIANCE -> stringResource(R.string.ui_picker_engine) to false
    else -> null
}

@Composable
private fun PickerTab(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int,
                      selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val tint = if (selected) c.onPrimaryContainer else c.textSecondary
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) c.primaryContainer else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        // The label gives way (ellipsis) so the count is never cut: "Aether & chains 10" read "1" (2026-11-25 test).
        Text(label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(5.dp))
        Text(count.toString(), color = tint.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun PickerGroupLabel(text: String, hint: String) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = c.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f), maxLines = 1)
        Text(hint, color = c.textMuted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun PickerGroupCard(content: @Composable () -> Unit) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surfaceCard).border(1.dp, c.borderSubtle, shape)) { content() }
}

@Composable
private fun PickerDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.borderSubtle))

@Composable
private fun SetupCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String,
                      accent: Color, highlighted: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(if (highlighted) c.primary.copy(alpha = if (c.isDark) .08f else .05f) else c.surfaceCard)
        .border(1.dp, if (highlighted) c.primary.copy(alpha = .45f) else c.borderSubtle, shape)
        .clickable(onClick = onClick).heightIn(min = 112.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
            }
        }
        Text(detail, color = c.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun ConfigMethodRow(
    title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color,
    onClick: (() -> Unit)?, badge: Pair<String, Boolean>? = null, chip: String? = null, indented: Boolean = false
) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth()
        .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
        .heightIn(min = 60.dp).padding(start = if (indented) 28.dp else 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (indented) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            }
        } else {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .13f)),
                contentAlignment = Alignment.Center) {
                if (chip != null) Text(chip, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
                else Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, color = if (onClick == null) c.textSecondary else c.textPrimary,
                    fontSize = if (indented) 14.sp else 15.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                badge?.let { (text, highlight) ->
                    Spacer(Modifier.width(8.dp))
                    Text(text, color = if (highlight) accent else c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (highlight) accent.copy(alpha = .14f) else c.surfacePill)
                            .padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Text(detail, color = c.textSecondary, fontSize = 12.5.sp, lineHeight = 16.sp)
        }
        if (onClick != null) Icon(Icons.Filled.ChevronRight, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
    }
}
