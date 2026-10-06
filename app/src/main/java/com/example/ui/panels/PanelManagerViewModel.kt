package com.example.ui.panels

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.panels.CleanIpOptimizer
import com.example.panels.CloudflareInstallRequest
import com.example.panels.ManagedPanel
import com.example.panels.PanelDashboardApi
import com.example.panels.PanelProvisioner
import com.example.panels.PanelSnapshot
import com.example.panels.PanelStore
import com.example.panels.PanelType
import com.example.panels.QuickConfigResult
import com.example.panels.XuiInstallRequest
import com.example.vpn.VpnController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ServerProbeResult(
    val reachable: Boolean,
    val latencyMs: Long,
    val message: String,
    val existingPanelDetected: Boolean = false,
    val detectedPort: Int? = null,
    val hostKeySha256: String = ""
)

data class PanelManagerUiState(
    val panels: List<ManagedPanel> = emptyList(),
    val busy: Boolean = false,
    val status: String = "",
    val error: String = "",
    val generatedConfig: QuickConfigResult? = null,
    val snapshots: Map<String, PanelSnapshot> = emptyMap(),
    val generatedToken: String = "",
    val generatedTokenAccount: String = "",
    val edges: List<CleanIpOptimizer.Result> = emptyList(),
    val tested: Int = 0,
    val reachable: Int = 0,
    val scanTotal: Int = 100,
    val scanProfileName: String = "",
    val currentScanIp: String = "",
    val scanPaused: Boolean = false,
    val scanStartedAt: Long = 0L,
    val logs: List<String> = emptyList(),
    val newlyDeployedPanel: ManagedPanel? = null,
    val installingServer: Boolean = false,
    val installServerTarget: String = "",
    val newlyInstalledServerPanel: ManagedPanel? = null,
    val serverProbeStatus: ServerProbeResult? = null,
    val serverProbeBusy: Boolean = false,
    val originHealth: CleanIpOptimizer.OriginHealth = CleanIpOptimizer.OriginHealth.Unknown,
    val originChecking: Boolean = false,
    val selectedTargetProfileId: String? = null,
    val customSubnet: String = "",
    val speedTestEnabled: Boolean = true
)

class PanelManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PanelStore(app)
    private val provisioner = PanelProvisioner()
    private val dashboard = PanelDashboardApi()
    private val _state = MutableStateFlow(PanelManagerUiState(panels = store.load()))
    val state: StateFlow<PanelManagerUiState> = _state.asStateFlow()

    val savedProfiles: StateFlow<List<com.example.data.model.VlessProfile>> =
        RayApplication.instance.serverRepository.allProfiles
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null
    private var scannedProfile: com.example.data.model.VlessProfile? = null
    private var scannedAt = 0L
    private var scannedNetwork: android.net.Network? = null
    private val connectivity =
        requireNotNull(app.getSystemService(android.net.ConnectivityManager::class.java))

    fun clearToken() {
        _state.update { it.copy(generatedToken = "", generatedTokenAccount = "") }
    }

    fun generateToken(parent: String, account: String) = runJob("Creating scoped Cloudflare token…") {
        appendLog("[TOKEN] Requesting least-privilege 30-day deployment token")
        val token = dashboard.createDeploymentToken(parent, account)
        appendLog("[TOKEN] Token created for account ${token.accountId.take(6)}…")
        _state.value.copy(
            generatedToken = token.value,
            generatedTokenAccount = token.accountId,
            status = "Token ready. It is shown only in this session until used or copied."
        )
    }

    fun smartDeployBpb(parent: String, account: String) =
        runJob("Creating token and deploying BPB Worker…") {
            resetLogs("[WIZARD] Starting Cloudflare smart install")
            val token = dashboard.createDeploymentToken(parent, account)
            appendLog("[TOKEN] Scoped deployment token created")
            val panel = provisioner.deployBpbWorker(
                CloudflareInstallRequest(token.value, accountId = token.accountId),
                ::appendLog
            )
            store.save(panel)
            _state.value.copy(
                panels = store.load(),
                generatedToken = token.value,
                generatedTokenAccount = token.accountId,
                status = "BPB Worker installed. Token and panel metadata are encrypted with Android Keystore.",
                generatedConfig = null,
                newlyDeployedPanel = panel
            )
        }

    fun refresh(panel: ManagedPanel, account: String = "", token: String = "") =
        runJob("Refreshing dashboard…") {
            val snapshot = if (panel.type == PanelType.XUI) {
                dashboard.serverSnapshot(panel)
            } else {
                val effectiveToken = panel.apiToken.ifBlank { token }
                val effectiveAccount = panel.accountId.ifBlank { account }
                require(effectiveToken.isNotBlank()) { "Enter or reinstall with a Cloudflare management token" }
                dashboard.workerSnapshot(panel, effectiveAccount, effectiveToken)
            }
            _state.value.copy(
                snapshots = _state.value.snapshots + (panel.id to snapshot),
                status = "Dashboard refreshed"
            )
        }

    fun publishWorker(
        panel: ManagedPanel,
        account: String = "",
        token: String = "",
        enabled: Boolean
    ) = runJob("Updating Worker publication…") {
        val effectiveToken = panel.apiToken.ifBlank { token }
        val effectiveAccount = panel.accountId.ifBlank { account }
        require(effectiveToken.isNotBlank()) { "Cloudflare management token is required" }
        dashboard.setWorkerEnabled(panel, effectiveAccount, effectiveToken, enabled)
        _state.value.copy(
            snapshots = _state.value.snapshots - panel.id,
            status = "Worker ${if (enabled) "published" else "disabled"}"
        )
    }

    private fun assertScanNetwork() {
        if (VpnController.connectionState.value.isVpnInterfaceActive) {
            appendLog("[WARN] Active VPN detected • measuring exit route")
        }
        val current = connectivity.activeNetwork
        if (current != null) {
            scannedNetwork = current
        }
    }

    fun clearServerProbe() {
        _state.update { it.copy(serverProbeStatus = null, serverProbeBusy = false) }
    }

    fun probeServer(host: String, port: Int) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            clearServerProbe()
            return
        }
        val targetPort = if (port in 1..65535) port else 22
        _state.update { it.copy(serverProbeBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            var reachable = false
            var latency = 0L
            var errMessage = ""
            try {
                java.net.Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(java.net.InetSocketAddress(cleanHost, targetPort), 2500)
                    latency = (System.currentTimeMillis() - start).coerceAtLeast(1)
                    reachable = true
                }
            } catch (e: Exception) {
                errMessage = e.message ?: "Connection timed out"
            }

            // Probe if 3X-UI default port (2053) is already open and responding
            var existingPanel = false
            var panelPort: Int? = null
            if (reachable) {
                for (checkPort in listOf(2053, 2052, 2096, 443, 8443)) {
                    if (checkPort == targetPort) continue
                    try {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress(cleanHost, checkPort), 1000)
                            existingPanel = true
                            panelPort = checkPort
                        }
                        if (existingPanel) break
                    } catch (_: Exception) {}
                }
            }

            val result = if (reachable) {
                val hostKey = runCatching {
                    provisioner.discoverSshHostKey(cleanHost, targetPort)
                }.getOrElse { e ->
                    errMessage = "SSH is reachable but host key discovery failed: ${e.message ?: "unknown error"}"
                    ""
                }
                if (hostKey.isBlank()) {
                    ServerProbeResult(false, latency, errMessage)
                } else {
                    val msg = if (existingPanel) {
                        "SSH verified (${latency}ms) • host key ready • panel detected on port $panelPort"
                    } else {
                        "SSH verified (${latency}ms) • host key ready for confirmation"
                    }
                    ServerProbeResult(true, latency, msg, existingPanel, panelPort, hostKey)
                }
            } else {
                ServerProbeResult(false, 0L, "Unreachable: $errMessage")
            }

            _state.update { it.copy(serverProbeStatus = result, serverProbeBusy = false) }
        }
    }

    private suspend fun scanCheckpoint() {
        while (_state.value.scanPaused) {
            currentCoroutineContext().ensureActive()
            delay(200)
        }
        currentCoroutineContext().ensureActive()
        assertScanNetwork()
    }

    fun setSelectedTargetProfileId(id: String?) {
        _state.update { it.copy(selectedTargetProfileId = id) }
        val profile = savedProfiles.value.firstOrNull { it.id == id }
        if (profile != null) {
            checkOriginHealth(profile)
        } else {
            _state.update { it.copy(originHealth = CleanIpOptimizer.OriginHealth.Unknown) }
        }
    }

    fun checkOriginHealth(profile: com.example.data.model.VlessProfile) {
        viewModelScope.launch {
            _state.update { it.copy(originChecking = true) }
            val health = CleanIpOptimizer().checkOriginHealth(profile)
            if (_state.value.selectedTargetProfileId != profile.id) return@launch
            _state.update { it.copy(originHealth = health, originChecking = false) }
            when (health) {
                is CleanIpOptimizer.OriginHealth.Online ->
                    appendLog("[ORIGIN] '${profile.name}' is ONLINE (${health.message})")
                is CleanIpOptimizer.OriginHealth.Down ->
                    appendLog("[ORIGIN] ⚠️ '${profile.name}' origin is DOWN: ${health.reason}")
                is CleanIpOptimizer.OriginHealth.Incompatible ->
                    appendLog("[ORIGIN] ℹ️ '${profile.name}' is incompatible: ${health.reason}")
                CleanIpOptimizer.OriginHealth.Unknown -> Unit
            }
        }
    }

    fun setCustomSubnet(subnet: String) {
        _state.update { it.copy(customSubnet = subnet) }
    }

    fun setSpeedTestEnabled(enabled: Boolean) {
        _state.update { it.copy(speedTestEnabled = enabled) }
    }

    fun scanEdges(
        region: String = "All Regions",
        port: Int = 443,
        customSubnet: String? = null,
        speedTest: Boolean = true
    ) = runJob("Scanning Cloudflare clean IPs ($region)…") {
        val app = RayApplication.instance
        val profiles = app.serverRepository.getAllProfilesOnce()
        val targetId = _state.value.selectedTargetProfileId
            ?: app.settingsRepository.settingsFlow.value.selectedProfileId
        val profile = profiles.firstOrNull { it.id == targetId }
            ?: profiles.firstOrNull()

        scannedNetwork = connectivity.activeNetwork
        scannedProfile = profile
        val profileLabel = profile?.name ?: "General Cloudflare CDN"
        resetLogs("[CF_SCANNER_START] region=$region port=$port target=$profileLabel speedTest=$speedTest")
        _state.update {
            it.copy(
                edges = emptyList(),
                tested = 0,
                reachable = 0,
                scanTotal = 100,
                scanProfileName = profileLabel,
                currentScanIp = "",
                scanPaused = false,
                scanStartedAt = android.os.SystemClock.elapsedRealtime()
            )
        }

        val edges = CleanIpOptimizer().scan(
            p = profile,
            targetPort = port,
            region = region,
            customSubnet = customSubnet?.ifBlank { null } ?: _state.value.customSubnet.ifBlank { null },
            enableSpeedTest = speedTest,
            candidateCount = 100,
            assertNetwork = ::assertScanNetwork,
            checkpoint = ::scanCheckpoint
        ) { progress ->
            _state.update {
                it.copy(
                    tested = progress.tested,
                    reachable = progress.reachable,
                    scanTotal = progress.totalCandidates,
                    currentScanIp = progress.currentIp
                )
            }
            if (progress.tested > 0 && progress.tested % 10 == 0) {
                appendLog(
                    "[SCAN] tested=${progress.tested}/${progress.totalCandidates} " +
                        "reachable=${progress.reachable} current=${progress.currentIp}"
                )
            }
        }

        scannedProfile = profile
        scannedAt = android.os.SystemClock.elapsedRealtime()
        appendLog("[CF_SCANNER_DONE] clean candidates found=${edges.size}")
        _state.value.copy(
            edges = edges,
            status = "Found ${edges.size} verified clean Cloudflare IPs for $region.",
            scanPaused = false
        )
    }

    fun toggleScanPause() {
        if (!_state.value.busy) return
        _state.update {
            val paused = !it.scanPaused
            it.copy(
                scanPaused = paused,
                status = if (paused) "IP scan paused" else "IP scan resumed"
            )
        }
        appendLog(if (_state.value.scanPaused) "[CONTROL] paused" else "[CONTROL] resumed")
    }

    fun applyEdge(edge: CleanIpOptimizer.Result, connect: Boolean) =
        runJob(if (connect) "Saving clean IP and connecting…" else "Saving clean IP…") {
            val app = RayApplication.instance
            val profile = scannedProfile
                ?: app.settingsRepository.settingsFlow.value.selectedProfileId?.let { id ->
                    app.serverRepository.getAllProfilesOnce().firstOrNull { it.id == id }
                }
                ?: app.serverRepository.getAllProfilesOnce().firstOrNull()
                ?: com.example.data.model.VlessProfile(
                    name = "Clean IP ${edge.ip}",
                    address = edge.ip,
                    port = 443,
                    uuid = java.util.UUID.randomUUID().toString(),
                    sni = "cloudflare.com",
                    host = "cloudflare.com"
                )

            val variant = CleanIpOptimizer.variant(profile, edge)
            val (inserted, duplicates) = app.serverRepository.insertAllWithDeduplication(listOf(variant))
            val saved = inserted.firstOrNull()
                ?: duplicates.firstOrNull()?.let {
                    app.serverRepository.getProfileByFingerprint(it.effectiveFingerprint)
                }
                ?: error("Unable to save clean-IP profile")

            app.settingsRepository.setSelectedProfileId(saved.id)
            val originState = _state.value.originHealth
            val warn = if (originState is CleanIpOptimizer.OriginHealth.Down) " ⚠️ Warning: origin server is offline." else ""
            var message = "Clean IP ${edge.ip} (${edge.formattedSpeed}) saved and selected.$warn"
            if (connect) {
                if (VpnService.prepare(getApplication()) == null) {
                    VpnController.startVpn(getApplication(), saved)
                    message = "Clean IP ${edge.ip} saved, selected and connected.$warn"
                } else {
                    message = "Clean IP ${edge.ip} saved. Connect from Home once VPN permission is granted.$warn"
                }
            }
            _state.value.copy(status = message)
        }

    fun reviveProfile(
        targetProfile: com.example.data.model.VlessProfile,
        edge: CleanIpOptimizer.Result,
        asClone: Boolean,
        connectNow: Boolean
    ) = runJob(if (connectNow) "Optimizing configuration and connecting…" else "Optimizing configuration…") {
        if (targetProfile.security.equals("reality", ignoreCase = true)) {
            return@runJob _state.value.copy(
                status = "Reality nodes cannot use Cloudflare Clean IPs. Reality endpoints require direct VPS connection. Use DPI Desync or God Mode."
            )
        }
        val app = RayApplication.instance
        val revived = CleanIpOptimizer.reviveProfile(targetProfile, edge, asClone)

        val finalProfile = if (asClone) {
            val (inserted, duplicates) = app.serverRepository.insertAllWithDeduplication(listOf(revived))
            inserted.firstOrNull() ?: duplicates.firstOrNull()?.let {
                app.serverRepository.getProfileByFingerprint(it.effectiveFingerprint)
            } ?: revived
        } else {
            app.serverRepository.update(revived)
            revived
        }

        app.settingsRepository.setSelectedProfileId(finalProfile.id)
        val originState = _state.value.originHealth
        val originWarning = if (originState is CleanIpOptimizer.OriginHealth.Down) {
            " ⚠️ Note: Origin backend was detected offline (HTTP ${originState.httpCode ?: "Down"}). Clean IP is bound, but connectivity will fail until the backend server is back online."
        } else ""

        var msg = "Configuration '${targetProfile.name}' optimized with Clean IP ${edge.ip} (${edge.qualityGrade} • ${edge.medianMs}ms • ${edge.formattedSpeed}).$originWarning"
        if (connectNow) {
            if (VpnService.prepare(getApplication()) == null) {
                VpnController.startVpn(getApplication(), finalProfile)
                msg = "Configuration '${targetProfile.name}' optimized with ${edge.ip} and connected!$originWarning"
            }
        }
        _state.value.copy(status = msg)
    }

    fun batchReviveAllBlockedProfiles() = runJob("Optimizing compatible Cloudflare configurations with clean IPs…") {
        val app = RayApplication.instance
        val all = app.serverRepository.getAllProfilesOnce()
        val edges = _state.value.edges
        check(edges.isNotEmpty()) { "No clean IPs available. Run a scan first." }
        check(all.isNotEmpty()) { "No configurations to optimize. Import or create a configuration first." }

        val eligible = all.filter { CleanIpOptimizer.isCloudflareCompatible(it) }
        check(eligible.isNotEmpty()) {
            "No compatible Cloudflare CDN configurations found. Reality, Direct TCP, and WireGuard configurations require direct endpoints and cannot be routed through Cloudflare clean IPs."
        }

        val revivedList = eligible.mapIndexed { index, profile ->
            val edge = edges[index % edges.size]
            CleanIpOptimizer.reviveProfile(profile, edge, asClone = true)
        }

        val (inserted, duplicates) = app.serverRepository.insertAllWithDeduplication(revivedList)
        val totalRevived = inserted.size + duplicates.size

        inserted.firstOrNull()?.let { app.settingsRepository.setSelectedProfileId(it.id) }

        _state.value.copy(
            status = "Successfully optimized $totalRevived compatible Cloudflare configurations with verified clean IPs!"
        )
    }

    fun copyRevivedUri(profile: com.example.data.model.VlessProfile, edge: CleanIpOptimizer.Result): String {
        val revived = CleanIpOptimizer.reviveProfile(profile, edge, asClone = false)
        return com.example.vless.VlessParser.toUri(revived)
    }

    fun applyEdges() = runJob("Saving ranked clean-IP configurations…") {
        val app = RayApplication.instance
        val profile = scannedProfile
            ?: app.settingsRepository.settingsFlow.value.selectedProfileId?.let { id ->
                app.serverRepository.getAllProfilesOnce().firstOrNull { it.id == id }
            }
            ?: app.serverRepository.getAllProfilesOnce().firstOrNull()
            ?: com.example.data.model.VlessProfile(
                name = "Cloudflare Clean IP",
                address = "104.16.0.1",
                port = 443,
                uuid = java.util.UUID.randomUUID().toString(),
                sni = "cloudflare.com",
                host = "cloudflare.com"
            )

        val edges = _state.value.edges
        check(edges.isNotEmpty()) { "No validated edges" }
        val variants = edges.map { CleanIpOptimizer.variant(profile, it) }
        val (inserted, duplicates) = app.serverRepository.insertAllWithDeduplication(variants)
        val first = inserted.firstOrNull { it.address == edges.first().ip }
            ?: duplicates.firstOrNull { it.address == edges.first().ip }
                ?.let { app.serverRepository.getProfileByFingerprint(it.effectiveFingerprint) }
        check(first != null) { "Unable to select the best edge" }
        app.settingsRepository.setSelectedProfileId(first.id)
        _state.value.copy(
            status = "Saved ${variants.size} ranked edge profiles. Best selected for connection."
        )
    }

    fun cancel() {
        job?.cancel()
        _state.update {
            it.copy(
                busy = false,
                scanPaused = false,
                status = "Cancelled. Remote resources already created may remain active."
            )
        }
        appendLog("[CONTROL] stopped")
    }

    fun installXui(
        host: String,
        port: Int,
        username: String,
        password: String,
        fingerprint: String
    ) {
        val target = "${username.ifBlank { "root" }}@$host:$port"
        _state.update {
            it.copy(
                installingServer = true,
                installServerTarget = target,
                newlyInstalledServerPanel = null
            )
        }
        runJob("Deploying 3X-UI on $target…") {
            resetLogs("[SSH] Establishing encrypted connection to $target")
            val panel = provisioner.installXui(
                XuiInstallRequest(host, port, username, password, fingerprint),
                ::appendLog
            )
            // A retry on the same server replaces the earlier record instead of adding a duplicate.
            store.load()
                .filter { it.type == PanelType.XUI && it.host.equals(panel.host, ignoreCase = true) }
                .forEach { store.delete(it.id) }
            store.save(panel)
            _state.value.copy(
                panels = store.load(),
                status = "3X-UI panel deployed successfully on $host!",
                generatedConfig = null,
                installingServer = false,
                newlyInstalledServerPanel = panel
            )
        }
    }

    fun dismissNewlyInstalledServer() {
        _state.update { it.copy(newlyInstalledServerPanel = null, installingServer = false) }
    }

    fun updatePanelPort(panel: ManagedPanel, newPort: Int) {
        val rewritten = runCatching {
            val u = java.net.URI(panel.url)
            java.net.URI(u.scheme, u.userInfo, u.host, newPort, u.path, u.query, u.fragment).toString()
        }.getOrDefault(panel.url)
        val updated = panel.copy(url = rewritten)
        store.save(updated)
        _state.update {
            it.copy(
                panels = store.load(),
                status = "Panel port updated to $newPort"
            )
        }
    }

    /** "Add to VPN" for a 3X-UI server: creates a real, verified inbound and imports its link. */
    fun importServerToProfiles(panel: ManagedPanel) = createQuickConfig(panel)

    fun deployBpb(token: String, account: String = "", email: String = "", password: String = "") {
        runJob("Deploying BPB Worker directly to Cloudflare…") {
            resetLogs("[INSTALL] Connecting to Cloudflare v4 API")
            val panel = provisioner.deployBpbWorker(
                CloudflareInstallRequest(
                    apiToken = token.trim(),
                    accountId = account.trim(),
                    accountEmail = email.trim(),
                    panelPassword = password.trim()
                ),
                ::appendLog
            )
            store.save(panel)
            _state.value.copy(
                panels = store.load(),
                status = "BPB Worker deployed successfully! Private link ready.",
                generatedConfig = null,
                newlyDeployedPanel = panel
            )
        }
    }

    fun dismissNewlyDeployedPanel() {
        _state.update { it.copy(newlyDeployedPanel = null) }
    }

    fun importBpbToProfiles(panel: ManagedPanel) {
        runJob("Importing BPB configuration to VPN profiles…") {
            val app = RayApplication.instance
            val host = panel.host.ifBlank { android.net.Uri.parse(panel.url).host.orEmpty() }
            check(host.isNotBlank()) { "The panel has no host name" }
            check(panel.vlessUuid.isNotBlank()) {
                "This panel was created by an older version and its VLESS identity was not saved. " +
                    "Delete it and deploy BPB again."
            }
            val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
            val path = "/vl/" + (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val name = panel.name.ifBlank { "BPB Worker" }
            // Same shape BPB itself exports for Xray clients: WebSocket over TLS with early data.
            val uri = "vless://${panel.vlessUuid}@$host:443?encryption=none&host=${enc(host)}&type=ws" +
                "&security=tls&path=${enc("$path?ed=2560")}&sni=${enc(host)}&fp=chrome&alpn=${enc("http/1.1")}" +
                "#${enc(name)}"
            val profile = when (val parsed = com.example.vless.VlessParser.parse(uri)) {
                is com.example.core.AppResult.Success -> parsed.data
                is com.example.core.AppResult.Error -> error(parsed.userFriendlyMessage)
            }
            val (inserted, duplicates) = app.serverRepository.insertAllWithDeduplication(listOf(profile))
            val saved = inserted.firstOrNull() ?: duplicates.firstOrNull()?.let {
                app.serverRepository.getProfileByFingerprint(it.effectiveFingerprint)
            } ?: profile
            app.settingsRepository.setSelectedProfileId(saved.id)

            val ping = describePing(saved)

            // Also add the worker's own subscription so all of its configs (every port and clean
            // address it publishes) are imported and kept up to date.
            val subNote = if (panel.securePath.isNotBlank()) {
                val subUrl = "https://$host/${panel.securePath}/sub/raw?app=xray"
                val existing = app.subscriptionRepository.getSubscriptionByUrl(subUrl)
                val result = if (existing != null) app.subscriptionManager.syncSubscription(existing)
                else app.subscriptionManager.addAndSyncSubscription(name, subUrl)
                if (result.isSuccess) "Subscription added: ${result.totalFound} configs (${result.addedCount} new)."
                else "Subscription could not be loaded yet: ${result.errorMessage.orEmpty()}"
            } else ""
            // Keep FIX BPB applied to newly imported configs once the user has turned it on.
            val fixNote = if (hasBpbFix(host)) "FIX BPB kept on ${applyBpbFix(host)} configs." else ""
            _state.value.copy(status = listOf("BPB profile imported and selected.", ping, subNote, fixNote)
                .filter { it.isNotBlank() }.joinToString(" "))
        }
    }

    /**
     * FIX BPB: stores a TLS fragment mask, the plain-TLS fingerprint, HTTP/1.1 ALPN and the
     * cipher-suite list on every TLS config that belongs to this BPB worker (single profile and
     * subscription). Each mask is first tried with a real request through one of the configs, and
     * the first one that carries traffic is kept.
     */
    fun fixBpbProfiles(panel: ManagedPanel) {
        runJob("Testing FIX BPB settings through the worker…") {
            val host = panel.host.ifBlank { android.net.Uri.parse(panel.url).host.orEmpty() }
            val owned = RayApplication.instance.serverRepository.getAllProfilesOnce()
                .filter { com.example.panels.BpbFix.belongsTo(it, host) }
            check(owned.isNotEmpty()) {
                "No configs from this BPB panel yet. Tap \"Add to Maximus VPN Profiles\" first, then FIX BPB."
            }
            val sample = owned.filter { com.example.panels.BpbFix.canApply(it) }
                .let { tls -> tls.firstOrNull { it.address.equals(host, ignoreCase = true) } ?: tls.firstOrNull() }
            check(sample != null) { "This BPB panel has no TLS configs, which are the ones FIX BPB changes." }
            val choice = withContext(Dispatchers.IO) { com.example.panels.BpbFix.choose(sample) }
            appendLog("[BPB] FIX BPB test result: ${choice::class.simpleName}")
            val reconnect = if (VpnController.connectionState.value.isTunnelUp) " Reconnect the VPN to use the new settings." else ""
            val status = when (choice) {
                is com.example.panels.BpbFix.Choice.Verified -> {
                    val changed = applyBpbFix(host, choice.mask)
                    "FIX BPB tested OK (${choice.latencyMs} ms through the worker) and applied to " +
                        "$changed config${if (changed == 1) "" else "s"}.$reconnect"
                }
                is com.example.panels.BpbFix.Choice.NotNeeded ->
                    "These BPB configs already work without FIX BPB (${choice.latencyMs} ms through the worker) " +
                        "and FIX BPB did not, so they were left unchanged."
                is com.example.panels.BpbFix.Choice.NothingWorks ->
                    "No request got through this BPB worker, with or without FIX BPB: ${choice.reason}. " +
                        "Check the worker's UUID and proxy IP in the BPB panel; configs were left unchanged."
                is com.example.panels.BpbFix.Choice.Untested -> {
                    val changed = applyBpbFix(host, com.example.panels.BpbFix.FINAL_MASK_ORIGINAL)
                    "FIX BPB applied to $changed config${if (changed == 1) "" else "s"} without a test " +
                        "(${choice.reason}). Disconnect the VPN and tap FIX BPB again to test it.$reconnect"
                }
            }
            _state.value.copy(status = status)
        }
    }

    private suspend fun hasBpbFix(host: String): Boolean =
        RayApplication.instance.serverRepository.getAllProfilesOnce()
            .any { com.example.panels.BpbFix.belongsTo(it, host) && com.example.panels.BpbFix.wasApplied(it) }

    /** The mask this worker's configs use, so new configs get the same tested one. */
    private suspend fun bpbFixMask(host: String): String =
        RayApplication.instance.serverRepository.getAllProfilesOnce()
            .firstOrNull { com.example.panels.BpbFix.belongsTo(it, host) && com.example.panels.BpbFix.isApplied(it) }
            ?.finalMask ?: com.example.panels.BpbFix.FINAL_MASK_ORIGINAL

    /** Returns how many configs now carry the fix. */
    private suspend fun applyBpbFix(host: String, mask: String? = null): Int {
        val repo = RayApplication.instance.serverRepository
        val chosen = mask ?: bpbFixMask(host)
        var count = 0
        repo.getAllProfilesOnce()
            .filter { com.example.panels.BpbFix.belongsTo(it, host) && com.example.panels.BpbFix.canApply(it) }
            .forEach { profile ->
                if (!com.example.panels.BpbFix.isApplied(profile, chosen)) repo.update(com.example.panels.BpbFix.apply(profile, chosen))
                count++
            }
        return count
    }

    private suspend fun describePing(profile: com.example.data.model.VlessProfile): String =
        when (val st = com.example.vpn.ServerTester.testServer(profile).status) {
            is com.example.data.model.ServerTestStatus.Available -> "Ping ${st.latencyMs} ms ✓"
            is com.example.data.model.ServerTestStatus.Slow -> "Ping ${st.latencyMs} ms (slow link)"
            is com.example.data.model.ServerTestStatus.Unavailable -> "Ping failed: ${st.reason}"
            is com.example.data.model.ServerTestStatus.InvalidConfig -> "Config check failed: ${st.error}"
            else -> ""
        }

    fun createQuickConfig(panel: ManagedPanel) {
        runJob("Creating a Reality configuration on ${panel.host}…") {
            val app = RayApplication.instance
            appendLog("[3X-UI] Creating VLESS Reality inbound on ${panel.host}")
            val result = provisioner.createQuickConfig(panel)
            appendLog("[3X-UI] Inbound created and verified in the panel")

            val profile = when (val parsed = com.example.vless.VlessParser.parse(result.configUri)) {
                is com.example.core.AppResult.Success -> parsed.data
                is com.example.core.AppResult.Error ->
                    error("3X-UI exported a link Maximus could not read: ${parsed.userFriendlyMessage}")
            }
            val (inserted, duplicates) = app.serverRepository.insertAllWithDeduplication(listOf(profile))
            val saved = inserted.firstOrNull() ?: duplicates.firstOrNull()?.let {
                app.serverRepository.getProfileByFingerprint(it.effectiveFingerprint)
            } ?: profile
            app.settingsRepository.setSelectedProfileId(saved.id)

            val ping = describePing(saved)
            appendLog("[3X-UI] $ping")
            val note = listOf(result.note, ping).filter { it.isNotBlank() }.joinToString(". ")
            _state.value.copy(
                panels = store.load(),
                status = "Configuration created and added to VPN profiles. $ping",
                generatedConfig = result.copy(note = note)
            )
        }
    }

    fun dismissGeneratedConfig() {
        _state.update { it.copy(generatedConfig = null) }
    }

    fun delete(panel: ManagedPanel) {
        store.delete(panel.id)
        _state.value = _state.value.copy(
            panels = store.load(),
            snapshots = _state.value.snapshots - panel.id,
            status = "Panel removed from this device."
        )
    }

    private fun resetLogs(first: String) {
        _state.update { it.copy(logs = listOf(first)) }
    }

    private fun appendLog(line: String) {
        _state.update { state ->
            state.copy(logs = (state.logs + line).takeLast(14))
        }
    }

    private fun runJob(message: String, block: suspend () -> PanelManagerUiState) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, status = message, error = "")
        job = viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.IO) { block() }
                _state.value = next.copy(busy = false, error = "")
            } catch (t: CancellationException) {
                _state.update { it.copy(busy = false, scanPaused = false, installingServer = false) }
                throw t
            } catch (t: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    scanPaused = false,
                    installingServer = false,
                    status = "",
                    error = t.message ?: "Operation failed"
                )
                appendLog("[ERROR] ${t.message ?: "Operation failed"}")
            }
        }
    }
}
