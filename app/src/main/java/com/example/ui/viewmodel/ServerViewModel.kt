package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.core.AppResult
import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.panels.InboundGenerationResult
import com.example.panels.ManagedPanel
import com.example.panels.PanelStore
import com.example.panels.PanelType
import com.example.panels.ThreeXUiConfigGenerator
import com.example.panels.ThreeXUiProtocol
import com.example.panels.ThreeXUiSecurity
import com.example.vless.BatchParseResult
import com.example.vless.VlessParser
import com.example.vless.VlessValidator
import com.example.vpn.ServerTester
import com.example.vpn.engine.UniversalImportEngine
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ServerSortOption {
    DEFAULT,
    SCORE,
    LATENCY,
    DOWNLOAD_SPEED,
    STABILITY,
    NAME
}

class ServerViewModel(
    private val repository: ServerRepository = RayApplication.instance.serverRepository,
    private val settingsRepository: SettingsRepository = RayApplication.instance.settingsRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _onlyFavorites = MutableStateFlow(false)
    val onlyFavorites: StateFlow<Boolean> = _onlyFavorites.asStateFlow()

    private val _sortOption = MutableStateFlow(ServerSortOption.DEFAULT)
    val sortOption: StateFlow<ServerSortOption> = _sortOption.asStateFlow()

    private val _isTestingAll = MutableStateFlow(false)
    val isTestingAll: StateFlow<Boolean> = _isTestingAll.asStateFlow()

    private val _serverTestingStates = MutableStateFlow<Map<String, ServerTestStatus>>(emptyMap())
    val serverTestingStates: StateFlow<Map<String, ServerTestStatus>> = _serverTestingStates.asStateFlow()

    private val panelStore = PanelStore(RayApplication.instance)
    private val threeXUiGenerator = ThreeXUiConfigGenerator()

    private val _managedPanels = MutableStateFlow<List<ManagedPanel>>(emptyList())
    val managedPanels: StateFlow<List<ManagedPanel>> = _managedPanels.asStateFlow()

    private val _selected3xuiPanel = MutableStateFlow<ManagedPanel?>(null)
    val selected3xuiPanel: StateFlow<ManagedPanel?> = _selected3xuiPanel.asStateFlow()

    private val _isGenerating3xuiConfig = MutableStateFlow(false)
    val isGenerating3xuiConfig: StateFlow<Boolean> = _isGenerating3xuiConfig.asStateFlow()

    private val _lastGenerated3xuiResult = MutableStateFlow<InboundGenerationResult?>(null)
    val lastGenerated3xuiResult: StateFlow<InboundGenerationResult?> = _lastGenerated3xuiResult.asStateFlow()

    private val _showGeneratedDialog = MutableStateFlow(false)
    val showGeneratedDialog: StateFlow<Boolean> = _showGeneratedDialog.asStateFlow()

    init {
        refreshPanels()
    }

    fun refreshPanels() {
        val loaded = panelStore.load()
        _managedPanels.value = loaded
        val xuiPanels = loaded.filter { it.type == PanelType.XUI && it.host.isNotBlank() && (it.password.isNotBlank() || it.apiToken.isNotBlank()) }
        if (_selected3xuiPanel.value == null || !xuiPanels.any { it.id == _selected3xuiPanel.value?.id }) {
            _selected3xuiPanel.value = xuiPanels.firstOrNull()
        }
    }

    fun setSelected3xuiPanel(panel: ManagedPanel?) {
        _selected3xuiPanel.value = panel
    }

    fun dismissGeneratedDialog() {
        _showGeneratedDialog.value = false
    }

    private val _generation3xuiError = MutableStateFlow("")
    val generation3xuiError: StateFlow<String> = _generation3xuiError.asStateFlow()

    fun dismissGeneration3xuiError() {
        _generation3xuiError.value = ""
    }

    private fun usable3xuiPanel(panel: ManagedPanel?): ManagedPanel? =
        (panel ?: _managedPanels.value.firstOrNull {
            it.type == PanelType.XUI && it.host.isNotBlank() && (it.password.isNotBlank() || it.apiToken.isNotBlank())
        })?.takeIf { it.host.isNotBlank() && (it.password.isNotBlank() || it.apiToken.isNotBlank()) }

    /**
     * Runs one generation on the real panel, imports exactly the verified link, then pings the new
     * endpoint from this device and reports the outcome in the result dialog.
     */
    private fun runGeneration(
        panel: ManagedPanel?,
        label: String,
        onComplete: (InboundGenerationResult) -> Unit,
        generate: (ManagedPanel) -> InboundGenerationResult
    ) {
        if (_isGenerating3xuiConfig.value) return
        val target = usable3xuiPanel(panel)
        if (target == null) {
            val msg = "Install a 3X-UI panel first (Panels → Servers), then create configurations."
            XrayLogManager.w("3X-UI", "Aborted config generation: $msg")
            _generation3xuiError.value = msg
            return
        }
        _generation3xuiError.value = ""
        _isGenerating3xuiConfig.value = true
        viewModelScope.launch {
            try {
                XrayLogManager.i("3X-UI", "Creating $label on ${target.host}...")
                val result = withContext(Dispatchers.IO) { generate(target) }
                // Import exactly the link that the panel/runtime serves. Hysteria2 and WireGuard
                // profiles were already parsed from their links by the generator.
                val canonicalProfile = if (!result.clientUri.startsWith("vless://")) result.profile
                else when (val parsed = VlessParser.parse(result.clientUri)) {
                    is AppResult.Success -> parsed.data
                    is AppResult.Error -> error(
                        "3X-UI exported a client link Maximus could not parse: ${parsed.userFriendlyMessage}"
                    )
                }
                repository.insert(canonicalProfile)
                settingsRepository.setSelectedProfileId(canonicalProfile.id)

                val ping = when (val st = ServerTester.testServer(canonicalProfile).status) {
                    is ServerTestStatus.Available -> "Ping ${st.latencyMs} ms ✓"
                    is ServerTestStatus.Slow -> "Ping ${st.latencyMs} ms (slow link)"
                    is ServerTestStatus.Unavailable -> "Ping failed: ${st.reason}"
                    is ServerTestStatus.InvalidConfig -> "Config check failed: ${st.error}"
                    else -> ""
                }
                val finalResult = result.copy(
                    profile = canonicalProfile,
                    statusMessage = listOf(result.statusMessage, ping).filter { it.isNotBlank() }.joinToString(". ")
                )
                _lastGenerated3xuiResult.value = finalResult
                _showGeneratedDialog.value = true
                XrayLogManager.i("3X-UI", "Created '${canonicalProfile.name}' and added it to Maximus VPN. $ping")
                onComplete(finalResult)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Configuration could not be created"
                XrayLogManager.e("3X-UI", "Failed to create $label: $msg", e)
                _generation3xuiError.value = msg
            } finally {
                _isGenerating3xuiConfig.value = false
            }
        }
    }

    /** One tap: VLESS + Reality + Vision on a free port (443 when available). */
    fun quickRecommendedConfig(
        panel: ManagedPanel? = _selected3xuiPanel.value,
        onComplete: (InboundGenerationResult) -> Unit = {}
    ) = runGeneration(panel, "a Reality configuration", onComplete) { target ->
        threeXUiGenerator.generateRecommended(target)
    }

    fun rapidCreateVlessTcpConfig(
        panel: ManagedPanel? = _selected3xuiPanel.value,
        targetPort: Int? = null,
        onComplete: (InboundGenerationResult) -> Unit = {}
    ) = runGeneration(panel, "a VLESS TCP configuration", onComplete) { target ->
        threeXUiGenerator.generateRapidVlessTcp(panel = target, targetPort = targetPort)
    }

    fun generateAndAdd3xuiConfig(
        panel: ManagedPanel? = _selected3xuiPanel.value,
        protocol: ThreeXUiProtocol,
        security: ThreeXUiSecurity,
        customPort: Int? = null,
        customHostOrSni: String? = null,
        customPath: String? = null,
        onComplete: (InboundGenerationResult) -> Unit = {}
    ) = runGeneration(panel, "${protocol.displayName} + ${security.displayName} inbound", onComplete) { target ->
        threeXUiGenerator.generateInbound(
            panel = target,
            protocol = protocol,
            security = security,
            customPort = customPort,
            customHostOrSni = customHostOrSni,
            customPath = customPath
        )
    }

    val selectedProfileId: StateFlow<String?> = settingsRepository.settingsFlow
        .combine(MutableStateFlow(Unit)) { settings, _ -> settings.selectedProfileId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val serverList: StateFlow<List<VlessProfile>> = combine(
        repository.allProfiles,
        _searchQuery,
        _onlyFavorites,
        _sortOption
    ) { profiles, query, favoritesOnly, sort ->
        var list = profiles
        if (favoritesOnly) {
            list = list.filter { it.isFavorite }
        }
        if (query.isNotBlank()) {
            list = list.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.address.contains(query, ignoreCase = true) ||
                        it.transport.contains(query, ignoreCase = true) ||
                        it.security.contains(query, ignoreCase = true) ||
                        it.protocolType.name.contains(query, ignoreCase = true)
            }
        }
        when (sort) {
            ServerSortOption.SCORE -> list.sortedByDescending { it.overallScore }
            ServerSortOption.LATENCY -> list.sortedWith(compareBy(nullsLast()) { it.lastLatencyMs })
            ServerSortOption.DOWNLOAD_SPEED -> list.sortedByDescending { it.downloadMbps }
            ServerSortOption.STABILITY -> list.sortedByDescending { it.stability }
            ServerSortOption.NAME -> list.sortedBy { it.name.lowercase() }
            ServerSortOption.DEFAULT -> list
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavoritesFilter() {
        _onlyFavorites.value = !_onlyFavorites.value
    }

    fun setSortOption(option: ServerSortOption) {
        _sortOption.value = option
    }

    fun selectServer(profile: VlessProfile) {
        XrayLogManager.i("UI", "Selected active server profile: '${profile.name}' (${profile.address}:${profile.port})")
        settingsRepository.setSelectedProfileId(profile.id)
        if (com.example.vpn.VpnController.connectionState.value.let { it.isTunnelUp && !it.isBusy }) {
            XrayLogManager.i("VPN", "Active connection detected. Switching tunnel to '${profile.name}' (${profile.address}:${profile.port})...")
            com.example.vpn.VpnController.startVpn(RayApplication.instance, profile)
        }
    }

    fun toggleFavorite(profile: VlessProfile) {
        viewModelScope.launch {
            val newState = !profile.isFavorite
            XrayLogManager.i("UI", "Toggled favorite for '${profile.name}': $newState")
            repository.toggleFavorite(profile.id, newState)
        }
    }

    /** A downloaded free config (from the signed free list). */
    fun isFreeConfig(profile: VlessProfile): Boolean =
        com.example.vpn.hub.FreeConfigList.isList(profile.sourceSubscription.orEmpty()) ||
            com.example.vpn.hub.FreeConfigList.isList(profile.subscriptionUrl.orEmpty())

    /**
     * Deletes every free config and nothing else. A connected session keeps running on its own copy,
     * and diagnostic history stays; [onDone] gets how many were deleted.
     */
    fun deleteAllFree(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val n = runCatching { RayApplication.instance.subscriptionManager.deleteAllFree() }
                .onFailure { XrayLogManager.e("UI", "Delete Free failed: ${it.message}", it) }
                .getOrDefault(0)
            onDone(n)
        }
    }

    fun deleteServer(profileId: String) {
        viewModelScope.launch {
            val profile = repository.getProfileById(profileId)
            XrayLogManager.i("UI", "Deleted server profile: '${profile?.name ?: profileId}'")
            // A free config also leaves the last-known-good bookkeeping and its test metadata.
            if (profile != null && isFreeConfig(profile)) RayApplication.instance.subscriptionManager.deleteFree(profile) else repository.delete(profileId)
        }
    }

    suspend fun getServer(profileId: String): VlessProfile? = repository.getProfileById(profileId)

    /**
     * Saves an edited node. Returns null on success, or the reason it was refused. When the node is
     * the one carrying the live tunnel, the tunnel is restarted so the new settings take effect.
     */
    suspend fun updateServer(profile: VlessProfile): String? {
        val error = try {
            VlessValidator.validate(profile)
            com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(profile)
        } catch (e: Exception) {
            e.localizedMessage ?: "Invalid configuration"
        }
        if (error != null) return error
        val saved = profile.copy(canonicalFingerprint = "")
        repository.update(saved)
        XrayLogManager.i("UI", "Edited server profile: '${saved.name}' (${saved.address}:${saved.port})")
        val settings = settingsRepository.getSettings()
        if (settings.selectedProfileId == saved.id && com.example.vpn.VpnController.connectionState.value.let { it.isTunnelUp && !it.isBusy }) {
            XrayLogManager.i("VPN", "Applying edited settings of '${saved.name}' to the active tunnel...")
            com.example.vpn.VpnController.startVpn(RayApplication.instance, saved)
        }
        return null
    }

    /** SHA-256 of the server's TLS certificate, for pinning. */
    suspend fun fetchCertificateFingerprint(address: String, port: Int, sni: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { com.example.vpn.CertificateFingerprint.fetch(address, port, sni) }
        }

    fun duplicateServer(profile: VlessProfile) {
        viewModelScope.launch {
            val duplicate = profile.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = "${profile.name} (Copy)",
                createdAt = System.currentTimeMillis()
            )
            XrayLogManager.i("UI", "Duplicated server '${profile.name}' -> '${duplicate.name}'")
            repository.insert(duplicate)
        }
    }

    fun addServer(profile: VlessProfile): AppResult<Unit> {
        return try {
            VlessValidator.validate(profile)
            viewModelScope.launch {
                repository.insert(profile)
                if (settingsRepository.getSettings().selectedProfileId == null) {
                    settingsRepository.setSelectedProfileId(profile.id)
                }
            }
            XrayLogManager.i("UI", "Successfully manually added server profile: '${profile.name}' (${profile.address}:${profile.port})")
            AppResult.Success(Unit)
        } catch (e: Exception) {
            val err = e.localizedMessage ?: "Invalid configuration"
            XrayLogManager.e("UI", "Failed to add manual server '${profile.name}': $err", e)
            AppResult.Error(e, err)
        }
    }

    fun importConfigurationUniversal(rawInput: String, sourceUrl: String? = null): AppResult<List<VlessProfile>> {
        return try {
            XrayLogManager.i("PARSER", "Universal parser analyzing input (${rawInput.length} chars, source=${sourceUrl ?: "direct"})...")
            val parseResult = UniversalImportEngine.importText(rawInput, sourceFileName = sourceUrl)
            if (parseResult.validProfiles.isNotEmpty()) {
                viewModelScope.launch {
                    val (inserted, duplicates) = repository.insertAllWithDeduplication(parseResult.validProfiles)
                    if (settingsRepository.getSettings().selectedProfileId == null && inserted.isNotEmpty()) {
                        settingsRepository.setSelectedProfileId(inserted.first().id)
                    }
                    XrayLogManager.i("PARSER", "Successfully imported ${inserted.size} profiles (${duplicates.size} duplicates filtered).")
                }
                AppResult.Success(parseResult.validProfiles)
            } else {
                val ex = Exception("No valid proxy profiles found in input.")
                XrayLogManager.w("PARSER", "Universal import completed but found 0 valid profiles.")
                AppResult.Error(ex, ex.message ?: "No profiles found.")
            }
        } catch (e: Exception) {
            val err = "Import failed: ${e.localizedMessage}"
            XrayLogManager.e("PARSER", err, e)
            AppResult.Error(e, err)
        }
    }

    fun importFromVlessUri(rawUri: String): AppResult<VlessProfile> {
        val result = VlessParser.parse(rawUri)
        if (result is AppResult.Success) {
            XrayLogManager.i("PARSER", "Parsed VLESS URI for server: '${result.data.name}' (${result.data.address}:${result.data.port})")
            viewModelScope.launch {
                repository.insert(result.data)
                if (settingsRepository.getSettings().selectedProfileId == null) {
                    settingsRepository.setSelectedProfileId(result.data.id)
                }
            }
        } else if (result is AppResult.Error) {
            XrayLogManager.w("PARSER", "VLESS URI parse error: ${result.userFriendlyMessage}")
        }
        return result
    }

    fun importBatch(rawText: String): BatchParseResult {
        XrayLogManager.i("PARSER", "Batch parsing text input (${rawText.lines().size} lines)...")
        val batchResult = VlessParser.parseBatch(rawText)
        val successCount = batchResult.successfulProfiles.size
        val failCount = batchResult.failedEntries.size
        XrayLogManager.i("PARSER", "Batch parse completed: $successCount succeeded, $failCount failed.")
        if (batchResult.successfulProfiles.isNotEmpty()) {
            viewModelScope.launch {
                repository.insertAllWithDeduplication(batchResult.successfulProfiles)
            }
        }
        return batchResult
    }

    fun testServer(profile: VlessProfile) {
        viewModelScope.launch {
            _serverTestingStates.value = _serverTestingStates.value + (profile.id to ServerTestStatus.Testing)
            val result = ServerTester.testServer(profile)
            _serverTestingStates.value = _serverTestingStates.value + (profile.id to result.status)

            when (result.status) {
                is ServerTestStatus.Available -> repository.updateLatency(profile.id, result.status.latencyMs)
                is ServerTestStatus.Slow -> repository.updateLatency(profile.id, result.status.latencyMs)
                // Not measured (the VPN is on): the last result stays.
                ServerTestStatus.Idle -> Unit
                else -> repository.updateLatency(profile.id, null)
            }
        }
    }

    private var testAllJob: kotlinx.coroutines.Job? = null

    /** Servers finished and in total in the current "Ping all" run. */
    private val _testAllProgress = MutableStateFlow(0 to 0)
    val testAllProgress: StateFlow<Pair<Int, Int>> = _testAllProgress.asStateFlow()

    /**
     * Pings every server one at a time, so only one test core runs and the phone stays responsive; each
     * row updates as its test finishes. [stopTestAll] ends the run.
     */
    fun testAllServers() {
        if (_isTestingAll.value) return
        val currentProfiles = serverList.value
        if (currentProfiles.isEmpty()) return
        _isTestingAll.value = true
        _testAllProgress.value = 0 to currentProfiles.size
        testAllJob = viewModelScope.launch {
            try {
                for ((i, profile) in currentProfiles.withIndex()) {
                    _serverTestingStates.value = _serverTestingStates.value + (profile.id to ServerTestStatus.Testing)
                    val result = ServerTester.testServer(profile, timeoutMs = 2500)
                    _serverTestingStates.value = _serverTestingStates.value + (profile.id to result.status)
                    val latency = when (result.status) {
                        is ServerTestStatus.Available -> result.status.latencyMs
                        is ServerTestStatus.Slow -> result.status.latencyMs
                        else -> null
                    }
                    // Not measured (the VPN is on): the last result stays.
                    if (result.status != ServerTestStatus.Idle) repository.updateLatency(profile.id, latency)
                    _testAllProgress.value = (i + 1) to currentProfiles.size
                }
            } finally {
                // A stopped run leaves no row spinning.
                _serverTestingStates.value = _serverTestingStates.value.filterValues { it !is ServerTestStatus.Testing }
                _isTestingAll.value = false
            }
        }
    }

    /** Stops "Ping all"; servers already tested keep their result. */
    fun stopTestAll() {
        testAllJob?.cancel()
    }

    fun exportUri(profile: VlessProfile): String {
        return VlessParser.toUri(profile)
    }

    fun fetchRemoteSubscription(url: String, onResult: (AppResult<Int>) -> Unit) {
        viewModelScope.launch {
            try {
                val syncResult = withContext(Dispatchers.IO) {
                    RayApplication.instance.subscriptionManager.addAndSyncSubscription(
                        name = "Imported Subscription",
                        url = url
                    )
                }
                if (syncResult.isSuccess) {
                    onResult(AppResult.Success(syncResult.addedCount))
                } else {
                    val msg = syncResult.errorMessage ?: "Failed to download subscription"
                    onResult(AppResult.Error(Exception(msg), msg))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Subscription download error"
                onResult(AppResult.Error(e, err))
            }
        }
    }
}
