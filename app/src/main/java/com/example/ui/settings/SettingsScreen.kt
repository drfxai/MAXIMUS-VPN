package com.example.ui.settings

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.data.model.EngineType
import com.example.data.model.RoutingMode
import com.example.data.model.ScoringProfile
import com.example.ui.components.ThemeToggleSwitch
import com.example.ui.popups.CommunityLinks
import com.example.ui.popups.GitHubLogo
import com.example.ui.popups.TelegramLogo
import com.example.ui.popups.YouTubeLogo
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.SettingsViewModel
import com.example.update.AppUpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToAiAgent: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsClipboard = LocalClipboardManager.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var showConfigPreview by remember { mutableStateOf(false) }
    var previewJson by remember { mutableStateOf("") }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showLearnedNetworks by remember { mutableStateOf(false) }
    val learnedNetworks by viewModel.learnedNetworks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshLearnedNetworks() }
    var dnsDropdownExpanded by remember { mutableStateOf(false) }

    // APP UPDATE section: looks up public release info only. Nothing is downloaded or installed here;
    // "Download" simply opens the GitHub release page in the browser.
    val uriHandler = LocalUriHandler.current
    var updateChecking by remember { mutableStateOf(false) }
    var updateChecked by remember { mutableStateOf(false) }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    var showCryptoSupport by remember { mutableStateOf(false) }
    val performUpdateCheck: suspend () -> Unit = {
        updateChecking = true
        val latest = runCatching { AppUpdateChecker.fetchLatest() }.getOrNull()
        updateUrl = latest?.takeIf {
            AppUpdateChecker.shouldShow(
                it, BuildConfig.VERSION_NAME, BuildConfig.RELEASE_BUILD, 0, 0L, System.currentTimeMillis()
            )
        }?.pageUrl
        updateChecking = false
        updateChecked = true
    }
    LaunchedEffect(Unit) { performUpdateCheck() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("settings_screen")
    ) {
        // Screen Header with Title & Theme Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Maximus VPN Settings", color = AppTheme.colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Routing, DNS, AI Co-Pilot, and Security preferences", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }

            ThemeToggleSwitch(
                isDark = settings.darkTheme,
                onThemeChange = { isDark ->
                    viewModel.setDarkTheme(isDark)
                }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: GEMINI AI AGENT
        SectionHeader("AUTONOMOUS AI AGENT & CO-PILOT")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToAiAgent() },
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.primary.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(AppTheme.colors.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Agent",
                            tint = AppTheme.colors.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Maximus Gemini AI Agent",
                            color = AppTheme.colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Autonomous log diagnosis, usage mode tuning, voice & smart token tracker",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Button(
                    onClick = onNavigateToAiAgent,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("OPEN", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: APPEARANCE & THEME
        SectionHeader("APPEARANCE & THEME")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppTheme.colors.surfaceElevated)
                                .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (settings.darkTheme) Icons.Default.DarkMode else Icons.Default.WbSunny,
                                contentDescription = null,
                                tint = if (settings.darkTheme) AppTheme.colors.primary else Color(0xFFD97706),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (settings.darkTheme) "Dark Theme Active" else "Light Theme Active",
                                color = AppTheme.colors.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Swipe or tap the Sun/Moon switch to change appearance",
                                color = AppTheme.colors.textSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    ThemeToggleSwitch(
                        isDark = settings.darkTheme,
                        onThemeChange = { isDark ->
                            viewModel.setDarkTheme(isDark)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: SMART CONNECT & SCORING PROFILES
        SectionHeader("SMART CONNECT & SCORING WEIGHTS")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                ScoringProfile.values().forEach { profile ->
                    RoutingOptionItem(
                        title = "${profile.title} Mode",
                        subtitle = profile.description,
                        isSelected = settings.scoringProfile == profile,
                        onSelect = { viewModel.setScoringProfile(profile) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(6.dp))

                SettingToggleItem(
                    icon = Icons.Default.AutoAwesome,
                    title = "Automated Failover Watchdog",
                    subtitle = "Automatically switches to the next best node if packet loss > 30% or latency > 800ms",
                    checked = settings.autoFailoverEnabled,
                    onCheckedChange = { viewModel.setAutoFailover(it) }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // What Smart Connect remembers per network, and the way to forget it.
                SettingsRow(
                    title = "Learned network data",
                    subtitle = when (val routes = learnedNetworks.sumOf { it.routes }) {
                        0 -> "Nothing learned yet"
                        else -> "${if (routes == 1) "1 route" else "$routes routes"} on " +
                            if (learnedNetworks.size == 1) "1 network" else "${learnedNetworks.size} networks"
                    },
                    leading = { SettingsIconBox(Icons.Default.Route, AppTheme.colors.primary) },
                    onClick = { viewModel.refreshLearnedNetworks(); showLearnedNetworks = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: CORE PROXY ENGINE SELECTION
        SectionHeader("PROXY CORE ENGINE")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                RoutingOptionItem(
                    title = "Auto (Protocol Smart Select)",
                    subtitle = "Automatically selects the best engine (Xray for VLESS/XTLS Reality, Mihomo for YAML & proxy groups)",
                    isSelected = settings.preferredEngine == EngineType.AUTO,
                    onSelect = { viewModel.setPreferredEngine(EngineType.AUTO) }
                )
                Spacer(modifier = Modifier.height(6.dp))
                RoutingOptionItem(
                    title = "Mihomo / Clash Meta Core",
                    subtitle = "High-performance rule matching, URL-test latency failover & proxy groups",
                    isSelected = settings.preferredEngine == EngineType.MIHOMO,
                    onSelect = { viewModel.setPreferredEngine(EngineType.MIHOMO) }
                )
                Spacer(modifier = Modifier.height(6.dp))
                RoutingOptionItem(
                    title = "Xray-Core",
                    subtitle = "Full VLESS + XTLS Vision + Reality kernel with direct TCP/gRPC routing",
                    isSelected = settings.preferredEngine == EngineType.XRAY,
                    onSelect = { viewModel.setPreferredEngine(EngineType.XRAY) }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: ROUTING MODE
        SectionHeader("ROUTING RULES")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                RoutingOptionItem(
                    title = "Bypass LAN & Direct Local",
                    subtitle = "Routes LAN & private IPs directly; tunnels everything else",
                    isSelected = settings.routingMode == RoutingMode.RULE_BYPASS_LAN,
                    onSelect = { viewModel.setRoutingMode(RoutingMode.RULE_BYPASS_LAN) }
                )
                Spacer(modifier = Modifier.height(6.dp))
                RoutingOptionItem(
                    title = "Global Proxy (All Traffic)",
                    subtitle = "Routes all traffic strictly through the VLESS tunnel",
                    isSelected = settings.routingMode == RoutingMode.GLOBAL,
                    onSelect = { viewModel.setRoutingMode(RoutingMode.GLOBAL) }
                )
                Spacer(modifier = Modifier.height(6.dp))
                RoutingOptionItem(
                    title = "Custom Bypass List",
                    subtitle = "Bypasses specified custom domain & IP patterns",
                    isSelected = settings.routingMode == RoutingMode.BYPASS_SELECTED,
                    onSelect = { viewModel.setRoutingMode(RoutingMode.BYPASS_SELECTED) }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: DNS CONFIGURATION
        SectionHeader("DNS & DOH CONFIGURATION")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Upstream DNS / DoH Provider", color = AppTheme.colors.textSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = dnsDropdownExpanded,
                    onExpandedChange = { dnsDropdownExpanded = !dnsDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = getDnsLabel(settings.dnsServer),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dnsDropdownExpanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppTheme.colors.surfaceElevated,
                            unfocusedContainerColor = AppTheme.colors.surfaceElevated,
                            focusedBorderColor = AppTheme.colors.primary,
                            unfocusedBorderColor = AppTheme.colors.borderSubtle,
                            focusedTextColor = AppTheme.colors.textPrimary,
                            unfocusedTextColor = AppTheme.colors.textPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = dnsDropdownExpanded,
                        onDismissRequest = { dnsDropdownExpanded = false },
                        modifier = Modifier
                            .background(AppTheme.colors.surfaceCard)
                            .border(1.dp, AppTheme.colors.borderSubtle)
                    ) {
                        listOf(
                            "https://1.1.1.1/dns-query" to "Cloudflare DoH (Encrypted)",
                            "https://8.8.8.8/dns-query" to "Google DoH (Encrypted)",
                            "https://9.9.9.9/dns-query" to "Quad9 DoH (Secure)",
                            "1.1.1.1" to "Cloudflare UDP (1.1.1.1)",
                            "8.8.8.8" to "Google DNS UDP (8.8.8.8)",
                            "9.9.9.9" to "Quad9 Secure UDP (9.9.9.9)"
                        ).forEach { (ip, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = AppTheme.colors.textPrimary) },
                                onClick = {
                                    viewModel.setDnsServer(ip)
                                    dnsDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: DPI DESYNC & ANTI-CENSORSHIP
        SectionHeader("DPI DESYNC & BYPASS ENGINE")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingToggleItem(
                    icon = Icons.Default.AutoAwesome,
                    title = "Enable Desync Packet Mutation",
                    subtitle = "Splits, disorders, and injects fake SNI to bypass deep packet inspection filters",
                    checked = settings.defaultDesyncConfig.enabled,
                    onCheckedChange = { isEnabled ->
                        viewModel.updateSettings(settings.copy(defaultDesyncConfig = settings.defaultDesyncConfig.copy(enabled = isEnabled)))
                    }
                )

                if (settings.defaultDesyncConfig.enabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "DESYNC PRESET PROFILE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            com.example.data.model.DesyncProfile.LIGHT to "Light",
                            com.example.data.model.DesyncProfile.BALANCED to "Balanced",
                            com.example.data.model.DesyncProfile.SEVERE to "Severe",
                            com.example.data.model.DesyncProfile.ADAPTIVE to "Adaptive"
                        ).forEach { (profile, label) ->
                            val isSel = settings.defaultDesyncConfig.profile == profile
                            Button(
                                onClick = {
                                    val method = when (profile) {
                                        com.example.data.model.DesyncProfile.LIGHT -> com.example.data.model.DesyncMethod.SPLIT
                                        com.example.data.model.DesyncProfile.BALANCED -> com.example.data.model.DesyncMethod.DISORDER
                                        com.example.data.model.DesyncProfile.SEVERE -> com.example.data.model.DesyncMethod.DISORDER_OOB
                                        com.example.data.model.DesyncProfile.ADAPTIVE -> com.example.data.model.DesyncMethod.FAKE_SNI
                                        else -> settings.defaultDesyncConfig.method
                                    }
                                    viewModel.updateSettings(
                                        settings.copy(
                                            defaultDesyncConfig = settings.defaultDesyncConfig.copy(
                                                profile = profile,
                                                method = method
                                            )
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) AppTheme.colors.primary else AppTheme.colors.surfaceElevated
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Color.White else AppTheme.colors.textPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "MULTI-DOMAIN FAKE SNI POOL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = settings.defaultDesyncConfig.fakeSniPool,
                        onValueChange = { newPool: String ->
                            viewModel.updateSettings(settings.copy(defaultDesyncConfig = settings.defaultDesyncConfig.copy(fakeSniPool = newPool)))
                        },
                        placeholder = { Text("www.google.com, www.microsoft.com, speedtest.net", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppTheme.colors.surfaceElevated,
                            unfocusedContainerColor = AppTheme.colors.surfaceElevated,
                            focusedBorderColor = AppTheme.colors.primary,
                            unfocusedBorderColor = AppTheme.colors.borderSubtle,
                            focusedTextColor = AppTheme.colors.textPrimary,
                            unfocusedTextColor = AppTheme.colors.textPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: FREE CONFIGS
        SectionHeader("FREE CONFIGS")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingToggleItem(
                    icon = Icons.Default.CloudDownload,
                    title = "Free configs",
                    subtitle = "On: downloads the free public server list and shows it in Servers and Free Configs. Off: nothing is downloaded, tested or shown.",
                    checked = settings.freeConfigsEnabled,
                    onCheckedChange = { viewModel.setFreeConfigs(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: SHARE OVER HOTSPOT
        SectionHeader("SHARE OVER HOTSPOT")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingToggleItem(
                    icon = Icons.Default.WifiTethering,
                    title = "Share VPN over hotspot",
                    subtitle = "Opens a password-protected proxy on your hotspot so other devices route through this tunnel. Off by default; works only while connected.",
                    checked = settings.hotspotShareEnabled,
                    onCheckedChange = { viewModel.setHotspotShare(it) }
                )
                if (settings.hotspotShareEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val share = remember(
                        settings.hotspotShareUser,
                        settings.hotspotSharePassword,
                        settings.hotspotSharePort,
                        settings.hotspotShareEnabled
                    ) { com.example.vpn.share.HotspotShare.clientInstructions(settings) }
                    val copy: (String, String) -> Unit = { value, label ->
                        settingsClipboard.setText(androidx.compose.ui.text.AnnotatedString(value))
                        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = AppTheme.colors.surfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (share == null) {
                                Text(
                                    "Turn on your phone's hotspot, then connect the VPN. The address to use on other devices will appear here.",
                                    color = AppTheme.colors.textSecondary,
                                    fontSize = 11.5.sp
                                )
                            } else {
                                Text(
                                    "On the other device, set this phone as a SOCKS5 (or HTTP) proxy:",
                                    color = AppTheme.colors.textSecondary,
                                    fontSize = 11.5.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                HotspotCopyRow("SOCKS5 proxy", share.socksEndpoint) { copy(share.socksEndpoint, "SOCKS5 address") }
                                HotspotCopyRow("HTTP proxy", share.httpEndpoint) { copy(share.httpEndpoint, "HTTP address") }
                                HotspotCopyRow("Username", share.user) { copy(share.user, "Username") }
                                HotspotCopyRow("Password", share.password) { copy(share.password, "Password") }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "Only devices on your hotspot can connect, and only with these credentials. Changing them or the switch takes effect on the next connect.",
                                color = AppTheme.colors.textMuted,
                                fontSize = 10.5.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.material3.OutlinedButton(
                                onClick = { viewModel.regenerateHotspotCredentials() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Generate new password", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: SECURITY & NETWORK TOGGLES
        SectionHeader("SECURITY & TUNNELING")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingToggleItem(
                    icon = Icons.Default.Lock,
                    title = "In-Tunnel Packet Guard",
                    subtitle = "For system-wide blocking during disconnects, enable Android Always-on VPN and Block connections without VPN",
                    checked = settings.killSwitchEnabled,
                    onCheckedChange = { viewModel.setKillSwitch(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                // The packet guard works inside the tunnel. Only Android's own lockdown also covers the moment
                // Android stops or restarts the app, so the user is taken straight to that setting.
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Android VPN settings (Always-on, Block connections without VPN)", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                SettingToggleItem(
                    icon = Icons.Default.Route,
                    title = "Route IPv6 through VPN",
                    subtitle = "Native Xray routes IPv6 when enabled; IPv6 is blocked on the IPv4-only Kotlin compatibility tunnel",
                    checked = settings.ipv6Enabled,
                    onCheckedChange = { viewModel.setIpv6(it) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingToggleItem(
                    icon = Icons.Default.Refresh,
                    title = "Auto-Reconnect on Network Switch",
                    subtitle = "Automatically re-establishes tunnel on WiFi/Cellular transition",
                    checked = settings.autoReconnect,
                    onCheckedChange = { viewModel.setAutoReconnect(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: ADVANCED & DIAGNOSTICS
        SectionHeader("ENGINE DIAGNOSTICS")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Live Diagnostics and Logs Button
                Button(
                    onClick = onNavigateToDiagnostics,
                    modifier = Modifier.fillMaxWidth().testTag("view_diagnostics_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceElevated),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = AppTheme.colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Live Logs & Telemetry", color = AppTheme.colors.primary, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Xray JSON Preview Button
                Button(
                    onClick = {
                        scope.launch {
                            previewJson = viewModel.getPreviewConfigJson()
                            showConfigPreview = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("view_xray_config_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceElevated),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = AppTheme.colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Raw Engine JSON Config", color = AppTheme.colors.primary, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Reset Settings Button
                Button(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (AppTheme.colors.isDark) Color(0xFF241416) else Color(0xFFFEF2F2)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, tint = AppTheme.colors.statusError, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset All Settings to Defaults", color = AppTheme.colors.statusError, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: DEVELOPER & ABOUT
        SectionHeader("ABOUT & DEVELOPER")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF030818))
                            .border(1.dp, AppTheme.colors.borderMedium, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_maximus_logo),
                            contentDescription = "Maximus App Logo",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Maximus VPN",
                                color = AppTheme.colors.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppTheme.colors.primary.copy(alpha = 0.15f))
                                    .border(1.dp, AppTheme.colors.primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "v${BuildConfig.VERSION_NAME}",
                                    color = AppTheme.colors.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Enterprise VLESS & Universal Proxy Client",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.borderSubtle))
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Developer / Organization", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, AppTheme.colors.primary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_drfx_logo),
                                contentDescription = "DrFXAi Logo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Text("DrFXAi", color = AppTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Core Proxy Engines", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    Text("Xray 1.8.24 • Mihomo Meta", color = AppTheme.colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Universal Protocols Supported", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "VLESS • VMess • Trojan • Shadowsocks • Hysteria2 • TUIC • SOCKS5 • HTTP",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: APP UPDATE
        SectionHeader("APP UPDATE")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(4.dp)) {
                SettingsRow(
                    title = "Check for updates",
                    subtitle = "Get the latest version of Maximus VPN",
                    leading = { SettingsIconBox(Icons.Default.SystemUpdate, AppTheme.colors.primary) },
                    trailing = {
                        if (updateChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppTheme.colors.primary
                            )
                        } else {
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppTheme.colors.textMuted.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        scope.launch {
                            if (!updateChecking) {
                                performUpdateCheck()
                                val url = updateUrl
                                if (url != null) uriHandler.openUri(url)
                                else Toast.makeText(context, "You're on the latest version.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                SettingsRowDivider()
                SettingsRow(
                    title = "View changelog",
                    subtitle = "See what's new in recent versions",
                    leading = { SettingsIconBox(Icons.Default.Article, AppTheme.colors.textSecondary) },
                    onClick = { uriHandler.openUri(AppUpdateChecker.RELEASES_URL) }
                )
                SettingsRowDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsIconBox(Icons.Default.Info, AppTheme.colors.textSecondary)
                    Spacer(modifier = Modifier.width(13.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Current version", color = AppTheme.colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("v${BuildConfig.VERSION_NAME} · stable channel", color = AppTheme.colors.textMuted, fontSize = 11.5.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    UpdateStatusChip(checking = updateChecking, checked = updateChecked, hasUpdate = updateUrl != null)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: COMMUNITY & LINKS
        SectionHeader("COMMUNITY & LINKS")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(4.dp)) {
                SettingsRow(
                    title = "Telegram channel",
                    subtitle = "News, updates and community",
                    leading = { SettingsLogoTile { TelegramLogo(size = 19.dp) } },
                    trailing = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = AppTheme.colors.textMuted.copy(alpha = 0.7f), modifier = Modifier.size(15.dp)) },
                    onClick = { uriHandler.openUri(CommunityLinks.TELEGRAM) }
                )
                SettingsRowDivider()
                SettingsRow(
                    title = "YouTube channel",
                    subtitle = "Tutorials, guides and more",
                    leading = { SettingsLogoTile { YouTubeLogo(size = 19.dp) } },
                    trailing = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = AppTheme.colors.textMuted.copy(alpha = 0.7f), modifier = Modifier.size(15.dp)) },
                    onClick = { uriHandler.openUri(CommunityLinks.YOUTUBE) }
                )
                SettingsRowDivider()
                SettingsRow(
                    title = "GitHub repository",
                    subtitle = "Source code, issues and contributions",
                    leading = { SettingsLogoTile { GitHubLogo(size = 19.dp) } },
                    trailing = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = AppTheme.colors.textMuted.copy(alpha = 0.7f), modifier = Modifier.size(15.dp)) },
                    onClick = { uriHandler.openUri(CommunityLinks.GITHUB) }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION: SUPPORT MAXIMUS
        SectionHeader("SUPPORT MAXIMUS")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(4.dp)) {
                SettingsRow(
                    title = "Support with crypto",
                    subtitle = if (com.example.ui.popups.SupportLinks.CRYPTO.isNotEmpty()) "USDT · Tron (TRC20)" else "Help support development",
                    leading = { SettingsIconBox(Icons.Default.CurrencyBitcoin, Color(0xFFE0A94A)) },
                    onClick = {
                        if (com.example.ui.popups.SupportLinks.CRYPTO.isEmpty()) {
                            Toast.makeText(context, "Crypto support details coming soon.", Toast.LENGTH_SHORT).show()
                        } else {
                            showCryptoSupport = true
                        }
                    }
                )
                SettingsRowDivider()
                SettingsRow(
                    title = "Buy a coffee",
                    subtitle = "Support the developer",
                    leading = { SettingsIconBox(Icons.Default.LocalCafe, AppTheme.colors.textSecondary) },
                    onClick = {
                        val url = com.example.ui.popups.SupportLinks.COFFEE
                        if (url.isBlank()) Toast.makeText(context, "Coming soon.", Toast.LENGTH_SHORT).show()
                        else uriHandler.openUri(url)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showConfigPreview) {
        ConfigPreviewDialog(
            configJson = previewJson,
            onDismiss = { showConfigPreview = false }
        )
    }

    if (showLearnedNetworks) {
        LearnedNetworkPage(
            rows = learnedNetworks,
            onClear = {
                viewModel.clearLearnedNetworks()
                Toast.makeText(context, "Learned network data cleared", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showLearnedNetworks = false }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Settings", color = AppTheme.colors.textPrimary) },
            text = { Text("Are you sure you want to reset all routing and engine settings to factory defaults?", color = AppTheme.colors.textSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetToDefaults()
                        Toast.makeText(context, "Settings restored to defaults", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Reset", color = AppTheme.colors.statusError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = AppTheme.colors.textSecondary)
                }
            },
            containerColor = AppTheme.colors.surfaceCard
        )
    }

    if (showCryptoSupport) {
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showCryptoSupport = false },
            title = { Text("Support with Crypto", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Thank you for supporting Maximus VPN. Tap an address to copy it.",
                        color = AppTheme.colors.textSecondary, fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    com.example.ui.popups.SupportLinks.CRYPTO.forEach { (coin, address) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppTheme.colors.surfaceElevated)
                                .clickable {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(address))
                                    Toast.makeText(context, "$coin address copied", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(coin, color = AppTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(address, color = AppTheme.colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCryptoSupport = false }) {
                    Text("Close", color = AppTheme.colors.primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = AppTheme.colors.surfaceCard
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = AppTheme.colors.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/** A calm, uniform graphite tile for the bottom Settings rows, so the list reads as one family. */
@Composable
private fun SettingsIconBox(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(AppTheme.colors.surfaceElevated)
            .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** The same tile, framing a community brand logo. */
@Composable
private fun SettingsLogoTile(logo: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(AppTheme.colors.surfaceElevated)
            .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        logo()
    }
}

/** A tappable settings row: a leading tile/logo, a title and subtitle, and a trailing indicator. */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppTheme.colors.textMuted, modifier = Modifier.size(20.dp))
    }
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(modifier = Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AppTheme.colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = AppTheme.colors.textMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        trailing()
    }
}

/** Hairline divider inset to line up under the row text. */
@Composable
private fun SettingsRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 10.dp)
            .height(1.dp)
            .background(AppTheme.colors.borderSubtle)
    )
}

/** The small status pill on the current-version row. */
@Composable
private fun UpdateStatusChip(checking: Boolean, checked: Boolean, hasUpdate: Boolean) {
    val (label, color) = when {
        checking -> "Checking…" to AppTheme.colors.textSecondary
        hasUpdate -> "Update ready" to AppTheme.colors.statusWarning
        checked -> "Up to date" to AppTheme.colors.statusConnected
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RoutingOptionItem(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = AppTheme.colors.primary,
                unselectedColor = AppTheme.colors.textMuted
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AppTheme.colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HotspotCopyRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = AppTheme.colors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.width(96.dp)
        )
        Text(
            value,
            color = AppTheme.colors.textPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = "Copy $label",
            tint = AppTheme.colors.primary,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun SettingToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppTheme.colors.surfaceElevated)
                    .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AppTheme.colors.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, color = AppTheme.colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppTheme.colors.onPrimary,
                checkedTrackColor = AppTheme.colors.primary,
                uncheckedThumbColor = AppTheme.colors.textMuted,
                uncheckedTrackColor = AppTheme.colors.surfaceElevated
            )
        )
    }
}

private fun getDnsLabel(dns: String): String {
    return when (dns) {
        "1.1.1.1" -> "Cloudflare UDP (1.1.1.1)"
        "8.8.8.8" -> "Google DNS UDP (8.8.8.8)"
        "9.9.9.9" -> "Quad9 Secure UDP (9.9.9.9)"
        "https://1.1.1.1/dns-query" -> "Cloudflare DoH (Encrypted)"
        "https://8.8.8.8/dns-query", "https://dns.google/dns-query" -> "Google DoH (Encrypted)"
        "https://9.9.9.9/dns-query", "https://dns.quad9.net/dns-query" -> "Quad9 DoH (Secure)"
        else -> dns
    }
}
