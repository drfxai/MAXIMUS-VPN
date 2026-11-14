package com.example.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.RoutingMode
import com.example.data.model.ScoringProfile
import com.example.ui.popups.CommunityLinks
import com.example.ui.popups.GitHubLogo
import com.example.ui.popups.TelegramLogo
import com.example.ui.popups.YouTubeLogo
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.SettingsViewModel
import com.example.update.AppUpdateChecker
import com.example.update.CleanIpMaintenance
import com.example.update.InAppUpdater
import com.example.update.ResolverPackUpdater
import com.example.update.UpdateNetworkPolicy
import com.example.vpn.diagnostics.DnsPathTest
import com.example.vpn.safety.DnsResolvers
import kotlinx.coroutines.launch

/**
 * Settings: a short hub of categories, each opening its own page inside the Settings tab. Every
 * preference is still written through [SettingsViewModel] exactly as before; only the layout changed.
 */
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
    val localProxy by com.example.vpn.share.LocalProxyService.state.collectAsStateWithLifecycle()

    var showConfigPreview by remember { mutableStateOf(false) }
    var previewJson by remember { mutableStateOf("") }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showLearnedNetworks by remember { mutableStateOf(false) }
    val learnedNetworks by viewModel.learnedNetworks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshLearnedNetworks() }
    var customDns by remember { mutableStateOf<String?>(null) }
    var dnsTestRunning by remember { mutableStateOf(false) }
    var dnsTestResult by remember(settings.dnsServer) { mutableStateOf<String?>(null) }

    customDns?.let { address ->
        val usable = DnsResolvers.isUsable(address)
        AlertDialog(
            onDismissRequest = { customDns = null },
            title = { Text("Custom DNS") },
            text = {
                OutlinedTextField(
                    value = address,
                    onValueChange = { customDns = it },
                    label = { Text("IP address or DNS-over-HTTPS URL") },
                    supportingText = { Text("Example: https://1.1.1.1/dns-query") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = usable,
                    onClick = {
                        viewModel.setDnsServer(address.trim())
                        customDns = null
                    }
                ) { Text("Apply DNS") }
            },
            dismissButton = { TextButton(onClick = { customDns = null }) { Text("Cancel") } }
        )
    }

    // Official release metadata contains the APK SHA-256 and size; the platform installer
    // still requires consent. No browser handoff is necessary when trusted APK metadata exists.
    val uriHandler = LocalUriHandler.current
    var updateChecking by remember { mutableStateOf(false) }
    var updateChecked by remember { mutableStateOf(false) }
    var updateRelease by remember { mutableStateOf<AppUpdateChecker.ReleaseInfo?>(null) }
    var latestPublished by remember { mutableStateOf<AppUpdateChecker.ReleaseInfo?>(null) }
    var resolverProgress by remember { mutableStateOf(-1) }
    var resolverBusy by remember { mutableStateOf(false) }
    var cleanIpBusy by remember { mutableStateOf(false) }
    var cleanIpStatus by remember { mutableStateOf<String?>(null) }
    var resolverStatus by remember { mutableStateOf<String?>(null) }
    var vpnOnlyUpdates by remember { mutableStateOf(UpdateNetworkPolicy.vpnOnly(context)) }
    var showCoreInventory by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var updateProgress by remember { mutableStateOf(0) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var verifiedApk by remember { mutableStateOf<java.io.File?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var showCryptoSupport by remember { mutableStateOf(false) }
    val performUpdateCheck: suspend () -> Unit = {
        updateChecking = true
        val latest = runCatching { UpdateNetworkPolicy.requireAllowed(context); AppUpdateChecker.fetchLatest() }.getOrNull()
        latestPublished = latest
        val newer = latest?.takeIf {
            AppUpdateChecker.shouldShow(
                it, BuildConfig.VERSION_NAME, BuildConfig.RELEASE_BUILD, 0, 0L, System.currentTimeMillis()
            )
        }
        if (updateRelease?.version != newer?.version || updateRelease?.build != newer?.build) verifiedApk = null
        updateRelease = newer
        updateChecking = false
        updateChecked = true
    }
    LaunchedEffect(Unit) { performUpdateCheck() }

    if (showCoreInventory) {
        AlertDialog(
            onDismissRequest = { showCoreInventory = false },
            title = { Text("Core & resource manager") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(com.example.vpn.lab.CoreCapabilityRegistry.CORE + " • inside the signed APK")
                    com.example.vpn.sidecar.Sidecars.ENGINES
                        .filter { it.id in setOf("sing-box", "mihomo", "aether", "psiphon", "tor") }
                        .forEach { engine ->
                            val present = com.example.vpn.sidecar.Sidecars.isBundled(engine)
                            Text("${engine.id}: " + if (present) "Installed in APK" else "Not present on this device")
                        }
                    Text("Executable engine upgrades are released as signed APKs, not remotely executable code.")
                    Text("Psiphon fetches and validates its own server lists using Psiphon's configured signing key when it runs.")
                }
            },
            confirmButton = { TextButton(onClick = { showCoreInventory = false }) { Text("Close") } }
        )
    }

    if (showUpdateDialog && updateRelease != null) {
        val release = updateRelease!!
        com.example.ui.popups.VerifiedUpdateDialog(
            release = release,
            installedBuild = BuildConfig.RELEASE_BUILD,
            downloading = updateDownloading,
            progress = updateProgress,
            downloadStatus = updateStatus,
            verified = verifiedApk?.isFile == true,
            error = updateError,
            onDismiss = {
                updateJob?.cancel()
                InAppUpdater.cancelCurrentDownload()
                updateDownloading = false
                showUpdateDialog = false
            },
            onOfficialLink = { uriHandler.openUri(release.pageUrl) },
            onPrimary = {
                val saved = verifiedApk?.takeIf { it.isFile }
                when {
                    saved != null -> runCatching { InAppUpdater.install(context, saved) }
                        .onSuccess { allowed ->
                            if (!allowed) updateError = "Allow app installs for Maximus in Android Settings, then return and tap Install."
                        }
                        .onFailure { updateError = it.message ?: "Could not start Android installer" }
                    release.apk != null -> {
                        updateDownloading = true
                        updateProgress = 0
                        updateStatus = null
                        updateError = null
                        updateJob = scope.launch {
                            runCatching { InAppUpdater.download(context, release.apk, onStatus = { updateStatus = it }) { updateProgress = it } }
                                .onSuccess { verifiedApk = it }
                                .onFailure {
                                    if (it !is kotlinx.coroutines.CancellationException)
                                        updateError = it.message ?: "Download or verification failed"
                                }
                            updateDownloading = false
                        }
                    }
                    else -> uriHandler.openUri(release.pageUrl)
                }
            }
        )
    }


    val actions = remember(viewModel) { ViewModelSettingsActions(viewModel) }
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pageName?.let { name -> SettingsPage.values().firstOrNull { it.name == name } }
    BackHandler(enabled = page != null) { pageName = null }
    val open: (SettingsPage) -> Unit = { pageName = it.name }
    val back: () -> Unit = { pageName = null }

    val updateUi = UpdateUi(
        checking = updateChecking,
        checked = updateChecked,
        hasUpdate = updateRelease != null,
        latestVersion = updateRelease?.version
    )
    val checkForUpdates: () -> Unit = {
        scope.launch {
            if (!updateChecking) {
                performUpdateCheck()
                if (updateRelease != null) showUpdateDialog = true
                else Toast.makeText(context, "No newer release available (or update check unavailable).", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val versionName = BuildConfig.VERSION_NAME
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    AnimatedContent(
        targetState = page,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val forward = targetState != null
            val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
            (slideInHorizontally(tween(260)) { full -> sign * full / 5 } + fadeIn(tween(220)))
                .togetherWith(slideOutHorizontally(tween(260)) { full -> -sign * full / 8 } + fadeOut(tween(160)))
        },
        label = "settingsPage"
    ) { current ->
        when (current) {
            null -> SettingsHub(
                settings = settings,
                versionName = versionName,
                update = updateUi,
                onOpen = open,
                onOpenAiAgent = onNavigateToAiAgent,
                onUpdateChip = checkForUpdates
            )

            SettingsPage.CONNECTION -> ConnectionSettingsPage(
                settings = settings,
                actions = actions,
                learnedSummary = when (val routes = learnedNetworks.sumOf { it.routes }) {
                    0 -> "Nothing learned yet"
                    else -> "${if (routes == 1) "1 route" else "$routes routes"} on " +
                        if (learnedNetworks.size == 1) "1 network" else "${learnedNetworks.size} networks"
                },
                localProxy = LocalProxyUi(
                    running = localProxy.running,
                    starting = localProxy.starting,
                    port = localProxy.port,
                    user = localProxy.user,
                    password = localProxy.password,
                    error = localProxy.error,
                    canStart = settings.selectedProfileId != null
                ),
                onOpenLearnedNetworks = { viewModel.refreshLearnedNetworks(); showLearnedNetworks = true },
                onToggleLocalProxy = {
                    val intent = android.content.Intent(context, com.example.vpn.share.LocalProxyService::class.java)
                    if (localProxy.running || localProxy.starting) context.stopService(intent)
                    else androidx.core.content.ContextCompat.startForegroundService(context, intent)
                },
                onBack = back
            )

            SettingsPage.ROUTING_DNS -> RoutingDnsSettingsPage(
                settings = settings,
                actions = actions,
                dnsTestRunning = dnsTestRunning,
                dnsTestResult = dnsTestResult,
                onCustomDns = { customDns = settings.dnsServer },
                onTestVpnDns = {
                    dnsTestRunning = true
                    dnsTestResult = null
                    scope.launch {
                        try {
                            val result = DnsPathTest.run(context)
                            dnsTestResult = if (result.completed) {
                                "VPN DNS responded in ${result.latencyMs} ms."
                            } else {
                                "No VPN DNS result. Connect Maximus and try again, or check Diagnostics."
                            }
                        } finally {
                            dnsTestRunning = false
                        }
                    }
                },
                onTestResolver = {
                    dnsTestRunning = true
                    dnsTestResult = null
                    scope.launch {
                        try {
                            dnsTestResult = DnsPathTest.testResolver(context, settings.dnsServer).summary
                        } finally {
                            dnsTestRunning = false
                        }
                    }
                },
                onOpenResolverData = { open(SettingsPage.UPDATES) },
                onBack = back
            )

            SettingsPage.ANTI_CENSORSHIP -> AntiCensorshipSettingsPage(settings = settings, actions = actions, onBack = back)

            SettingsPage.PRIVACY -> PrivacySettingsPage(
                settings = settings,
                actions = actions,
                onOpenAndroidVpnSettings = {
                    // The packet guard works inside the tunnel. Only Android's own lockdown also covers the moment
                    // Android stops or restarts the app, so the user is taken straight to that setting.
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onBack = back
            )

            SettingsPage.SHARING -> {
                val share = remember(
                    settings.hotspotShareUser,
                    settings.hotspotSharePassword,
                    settings.hotspotSharePort,
                    settings.hotspotShareEnabled
                ) { com.example.vpn.share.HotspotShare.clientInstructions(settings) }
                SharingSettingsPage(
                    settings = settings,
                    actions = actions,
                    share = share?.let { HotspotShareUi(it.socksEndpoint, it.httpEndpoint, it.user, it.password) },
                    onCopy = { value, label ->
                        settingsClipboard.setText(androidx.compose.ui.text.AnnotatedString(value))
                        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
                    },
                    onBack = back
                )
            }

            SettingsPage.APPEARANCE -> AppearanceSettingsPage(settings = settings, actions = actions, onBack = back)

            SettingsPage.UPDATES -> UpdatesSettingsPage(
                versionName = versionName,
                update = updateUi,
                data = UpdatesDataUi(
                    resolverBusy = resolverBusy,
                    resolverProgress = resolverProgress,
                    resolverInstalled = ResolverPackUpdater.installed(context),
                    resolverStatus = resolverStatus,
                    cleanIpBusy = cleanIpBusy,
                    cleanIpStatus = cleanIpStatus,
                    vpnOnlyUpdates = vpnOnlyUpdates
                ),
                onCheckForUpdates = checkForUpdates,
                onCoreManager = { showCoreInventory = true },
                onUpdateResolvers = {
                    if (!resolverBusy) {
                        resolverBusy = true
                        resolverStatus = null
                        scope.launch {
                            val latest = latestPublished ?: runCatching { UpdateNetworkPolicy.requireAllowed(context); AppUpdateChecker.fetchLatest() }.getOrNull()
                            latestPublished = latest
                            val pack = latest?.resolverPack
                            if (pack == null) {
                                resolverStatus = "No verified resolver data pack is available in the official release."
                            } else {
                                runCatching { ResolverPackUpdater.refresh(context, pack) { resolverProgress = it } }
                                    .onSuccess { resolverStatus = "Updated and validated $it resolver lists." }
                                    .onFailure { resolverStatus = it.message ?: "Resolver database update failed." }
                            }
                            resolverBusy = false
                        }
                    }
                },
                onRollbackResolvers = {
                    resolverStatus = if (ResolverPackUpdater.rollback(context))
                        "Previous resolver pack restored." else "No previous verified pack available."
                },
                onRefreshCleanIps = {
                    if (!cleanIpBusy) {
                        cleanIpBusy = true
                        cleanIpStatus = null
                        scope.launch {
                            runCatching { CleanIpMaintenance.refresh(context) { done, total ->
                                cleanIpStatus = "Tested $done / $total Cloudflare candidates…"
                            } }.onSuccess {
                                cleanIpStatus = "${it.validated} of ${it.tested} addresses verified against the selected origin."
                            }.onFailure {
                                cleanIpStatus = it.message ?: "Clean-IP scan failed."
                            }
                            cleanIpBusy = false
                        }
                    }
                },
                onVpnOnlyUpdates = { value ->
                    UpdateNetworkPolicy.setVpnOnly(context, value)
                    vpnOnlyUpdates = value
                },
                onChangelog = { uriHandler.openUri(AppUpdateChecker.RELEASES_URL) },
                onBack = back
            )

            SettingsPage.DIAGNOSTICS -> DiagnosticsSettingsPage(
                onViewLogs = onNavigateToDiagnostics,
                onViewConfig = {
                    scope.launch {
                        previewJson = viewModel.getPreviewConfigJson()
                        showConfigPreview = true
                    }
                },
                onReset = { showResetConfirm = true },
                onBack = back
            )

            SettingsPage.ABOUT -> AboutSettingsPage(
                versionName = versionName,
                coreSummary = "${com.example.vpn.lab.CoreCapabilityRegistry.CORE} · Mihomo ${com.example.vpn.sidecar.MihomoSidecar.VERSION}",
                cryptoSubtitle = if (com.example.ui.popups.SupportLinks.CRYPTO.isNotEmpty()) "USDT · Tron (TRC20)" else "Help support development",
                onTelegram = { uriHandler.openUri(CommunityLinks.TELEGRAM) },
                onYouTube = { uriHandler.openUri(CommunityLinks.YOUTUBE) },
                onGitHub = { uriHandler.openUri(CommunityLinks.GITHUB) },
                onCrypto = {
                    if (com.example.ui.popups.SupportLinks.CRYPTO.isEmpty()) {
                        Toast.makeText(context, "Crypto support details coming soon.", Toast.LENGTH_SHORT).show()
                    } else {
                        showCryptoSupport = true
                    }
                },
                onCoffee = {
                    val url = com.example.ui.popups.SupportLinks.COFFEE
                    if (url.isBlank()) Toast.makeText(context, "Coming soon.", Toast.LENGTH_SHORT).show()
                    else uriHandler.openUri(url)
                },
                telegramLogo = { TelegramLogo(size = 20.dp) },
                youTubeLogo = { YouTubeLogo(size = 20.dp) },
                gitHubLogo = { GitHubLogo(size = 20.dp) },
                onBack = back
            )
        }
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

}

/** Wires the pages to the view model one-to-one, so preference keys and side effects are unchanged. */
private class ViewModelSettingsActions(private val vm: SettingsViewModel) : SettingsActions {
    override fun setDarkTheme(dark: Boolean) = vm.setDarkTheme(dark)
    override fun setScoringProfile(profile: ScoringProfile) = vm.setScoringProfile(profile)
    override fun setAutoFailover(enabled: Boolean) = vm.setAutoFailover(enabled)
    override fun setPowerButtonFallback(enabled: Boolean) = vm.setPowerButtonFallback(enabled)
    override fun setPreferredEngine(engine: EngineType) = vm.setPreferredEngine(engine)
    override fun setRoutingMode(mode: RoutingMode) = vm.setRoutingMode(mode)
    override fun setDnsServer(dns: String) = vm.setDnsServer(dns)
    override fun setFreeConfigs(enabled: Boolean) = vm.setFreeConfigs(enabled)
    override fun setKillSwitch(enabled: Boolean) = vm.setKillSwitch(enabled)
    override fun setHotspotShare(enabled: Boolean) = vm.setHotspotShare(enabled)
    override fun regenerateHotspotCredentials() = vm.regenerateHotspotCredentials()
    override fun setIpv6(enabled: Boolean) = vm.setIpv6(enabled)
    override fun setAutoReconnect(enabled: Boolean) = vm.setAutoReconnect(enabled)
    override fun updateSettings(settings: AppSettings) = vm.updateSettings(settings)
}
