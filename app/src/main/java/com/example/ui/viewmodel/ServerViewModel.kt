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

    fun rapidCreateVlessTcpConfig(
        panel: ManagedPanel? = _selected3xuiPanel.value,
        targetPort: Int? = null,
        onComplete: (InboundGenerationResult) -> Unit = {}
    ) {
        if (_isGenerating3xuiConfig.value) return
        val target = panel ?: _managedPanels.value.firstOrNull { it.type == PanelType.XUI && it.host.isNotBlank() && (it.password.isNotBlank() || it.apiToken.isNotBlank()) }
        if (target == null || target.host.isBlank() || (target.password.isBlank() && target.apiToken.isBlank())) {
            XrayLogManager.w("3X-UI", "Aborted config generation: 3X-UI panel is not installed or has no access credentials.")
            return
        }
        _isGenerating3xuiConfig.value = true
        viewModelScope.launch {
            try {
                XrayLogManager.i("3X-UI", "Quick generating VLESS TCP configuration on 3X-UI (${target.host})...")
                val result = withContext(Dispatchers.IO) {
                    threeXUiGenerator.generateRapidVlessTcp(
                        panel = target,
                        targetPort = targetPort
                    )
                }
                // Parse the canonical URI exported by 3X-UI. This guarantees the
                // local profile matches the exact runtime inbound that Xray is serving.
                val parsed = VlessParser.parse(result.clientUri)
                val canonicalProfile = when (parsed) {
                    is AppResult.Success -> parsed.data
                    is AppResult.Error -> error("3X-UI exported a client link Maximus could not parse: ${parsed.userFriendlyMessage}")
                }
                repository.insert(canonicalProfile)
                settingsRepository.setSelectedProfileId(canonicalProfile.id)
                val canonicalResult = result.copy(profile = canonicalProfile)
                _lastGenerated3xuiResult.value = canonicalResult
                _showGeneratedDialog.value = true
                XrayLogManager.i("3X-UI", "Quick VLESS TCP configuration generated & added to Maximus VPN: '${result.profile.name}'")
                onComplete(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                XrayLogManager.e("3X-UI", "Failed to quick-generate VLESS TCP: ${e.message}", e)
            } finally {
                _isGenerating3xuiConfig.value = false
            }
        }
    }

    fun generateAndAdd3xuiConfig(
        panel: ManagedPanel? = _selected3xuiPanel.value,
        protocol: ThreeXUiProtocol,
        security: ThreeXUiSecurity,
        customPort: Int? = null,
        customHostOrSni: String? = null,
        customPath: String? = null,
        onComplete: (InboundGenerationResult) -> Unit = {}
    ) {
        if (_isGenerating3xuiConfig.value) return
        val target = panel ?: _managedPanels.value.firstOrNull { it.type == PanelType.XUI && it.host.isNotBlank() && (it.password.isNotBlank() || it.apiToken.isNotBlank()) }
        if (target == null || target.host.isBlank() || (target.password.isBlank() && target.apiToken.isBlank())) {
            XrayLogManager.w("3X-UI", "Aborted config generation: 3X-UI panel is not installed or has no access credentials.")
            return
        }
        _isGenerating3xuiConfig.value = true
        viewModelScope.launch {
            try {
                XrayLogManager.i("3X-UI", "Generating 3X-UI inbound configuration on ${target.host}: ${protocol.displayName} + ${security.displayName}...")
                val result = withContext(Dispatchers.IO) {
                    threeXUiGenerator.generateInbound(
                        panel = target,
                        protocol = protocol,
                        security = security,
                        customPort = customPort,
                        customHostOrSni = customHostOrSni,
                        customPath = customPath
                    )
                }
                val parsed = VlessParser.parse(result.clientUri)
                val canonicalProfile = when (parsed) {
                    is AppResult.Success -> parsed.data
                    is AppResult.Error -> error(
                        "3X-UI exported a client link Maximus could not parse: ${parsed.userFriendlyMessage}"
                    )
                }
                repository.insert(canonicalProfile)
                settingsRepository.setSelectedProfileId(canonicalProfile.id)
                val canonicalResult = result.copy(profile = canonicalProfile)
                _lastGenerated3xuiResult.value = canonicalResult
                _showGeneratedDialog.value = true
                XrayLogManager.i("3X-UI", "Successfully generated 3X-UI configuration '${canonicalProfile.name}' and imported the canonical runtime link.")
                onComplete(canonicalResult)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                XrayLogManager.e("3X-UI", "Error generating 3X-UI configuration: ${e.message}", e)
            } finally {
                _isGenerating3xuiConfig.value = false
            }
        }
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
        if (com.example.vpn.VpnController.connectionState.value.isConnected) {
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

    fun deleteServer(profileId: String) {
        viewModelScope.launch {
            val profile = repository.getProfileById(profileId)
            XrayLogManager.i("UI", "Deleted server profile: '${profile?.name ?: profileId}'")
            repository.delete(profileId)
        }
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

            if (result.status is ServerTestStatus.Available) {
                repository.updateLatency(profile.id, result.status.latencyMs)
            } else if (result.status is ServerTestStatus.Slow) {
                repository.updateLatency(profile.id, result.status.latencyMs)
            } else {
                repository.updateLatency(profile.id, null)
            }
        }
    }

    fun testAllServers() {
        if (_isTestingAll.value) return
        viewModelScope.launch {
            _isTestingAll.value = true
            try {
                val currentProfiles = serverList.value
                for (profile in currentProfiles) {
                    _serverTestingStates.value = _serverTestingStates.value + (profile.id to ServerTestStatus.Testing)
                    val result = ServerTester.testServer(profile, timeoutMs = 2500)
                    _serverTestingStates.value = _serverTestingStates.value + (profile.id to result.status)

                    val latency = when (result.status) {
                        is ServerTestStatus.Available -> result.status.latencyMs
                        is ServerTestStatus.Slow -> result.status.latencyMs
                        else -> null
                    }
                    repository.updateLatency(profile.id, latency)
                }
            } finally {
                _isTestingAll.value = false
            }
        }
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
