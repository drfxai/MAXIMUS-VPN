package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.panels.servers.SmartNodeStore
import com.example.vpn.smart.NetworkKey
import com.example.vpn.RayVpnService
import com.example.vpn.VpnController
import com.example.vpn.smart.SmartConnect
import com.example.xray.XrayLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VpnViewModel(
    private val serverRepository: ServerRepository = RayApplication.instance.serverRepository,
    private val settingsRepository: SettingsRepository = RayApplication.instance.settingsRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = VpnController.connectionState

    private val _pendingProfile = MutableStateFlow<VlessProfile?>(null)
    val pendingProfile: StateFlow<VlessProfile?> = _pendingProfile.asStateFlow()
    /** How the connect waiting for the VPN permission was asked for: Smart Connect, or the power button. */
    private var pendingSmart = false
    private var pendingExact = false
    private var pendingPowerButton = false
    /** The connect waiting for the VPN permission is Unlock mode's (no profile). */
    private var pendingUnlock = false

    val selectedProfile: StateFlow<VlessProfile?> = combine(
        serverRepository.allProfiles,
        settingsRepository.settingsFlow
    ) { profiles, settings ->
        if (profiles.isEmpty()) return@combine null
        val targetId = settings.selectedProfileId
        // The user cleared the selection on Home (build 179, N9): nothing is selected until they pick again.
        if (targetId == com.example.ui.home.HomeSelection.CLEARED) return@combine null
        profiles.firstOrNull { it.id == targetId } ?: profiles.first()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    /** The Optimal Node agent's ranked shortlist: best first, up to three. */
    val topNodes: StateFlow<List<SmartConnect.SmartSelection>> = combine(
        serverRepository.allProfiles,
        settingsRepository.settingsFlow,
        // Only what changes the list: which server carries traffic, and each connect step (tests finish then).
        VpnController.connectionState.map { s -> s.status to s.activeProfile?.takeIf { s.status == ConnectionStatus.CONNECTED || s.status == ConnectionStatus.DEGRADED } }.distinctUntilChanged()
    ) { profiles, settings, (_, active) ->
        val book = com.example.vpn.connectivity.ConnectivityBrain.book
        // Read the Keystore-backed provenance on IO, not the Compose/UI dispatcher.
        // A corrupt/unavailable ledger never unlocks a private Psiphon bootstrap.
        val managed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { SmartNodeStore(RayApplication.instance).all() }.getOrDefault(emptyList())
        }
        SmartConnect.rankForCard(profiles, settings.scoringProfile, active = active, count = 3,
            managedRecords = managed, networkKey = NetworkKey.current(RayApplication.instance),
            evidenceOf = { p ->
            runCatching { book.eligibility(com.example.vpn.connectivity.PathRef.of(p)) }.getOrNull()
        })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val smartRecommendation: StateFlow<SmartConnect.SmartSelection?> = topNodes
        .combine(settingsRepository.settingsFlow) { ranked, _ -> ranked.firstOrNull() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun prepareConnect(profile: VlessProfile?) {
        _pendingProfile.value = profile
        RayVpnService.updateState(
            VpnController.connectionState.value.copy(
                status = ConnectionStatus.PREPARING,
                activeProfile = profile ?: selectedProfile.value
            )
        )
    }

    fun startOrRequestPermission(
        context: Context,
        profile: VlessProfile?,
        smart: Boolean = false,
        /** The power button: only [profile], as saved (see RayVpnService.EXTRA_EXACT). */
        exact: Boolean = false,
        /** Started by the power button itself, not a pick in a list (build 179, N2: the log blamed the power button). */
        powerButton: Boolean = false,
        onRequestPermission: (Intent) -> Unit
    ) {
        val targetProfile = profile ?: selectedProfile.value ?: smartRecommendation.value?.profile
        val prepareIntent = VpnController.prepareVpn(context)
        if (prepareIntent != null) {
            XrayLogManager.i("VPN", "VPN permission required before connecting '${targetProfile?.name ?: "default"}'")
            prepareConnect(targetProfile)
            pendingSmart = smart
            pendingExact = exact
            pendingPowerButton = powerButton
            onRequestPermission(prepareIntent)
        } else if (targetProfile != null) {
            // Shown on the tap, before the service starts and reaches its own first step.
            RayVpnService.mutateState { s -> if (s.isTunnelUp || s.isBusy) s else s.copy(status = ConnectionStatus.PREPARING, errorMessage = null, stopReason = null) }
            VpnController.startVpn(context, targetProfile, smart = smart, exact = exact, powerButton = powerButton)
        } else {
            viewModelScope.launch {
                val fallback = serverRepository.getAllProfilesOnce().firstOrNull()
                if (fallback != null) {
                    VpnController.startVpn(context, fallback, smart = smart, exact = exact, powerButton = powerButton)
                } else {
                    connectWithoutServer(context)
                }
            }
        }
    }

    fun onPermissionGranted(context: Context) {
        if (pendingUnlock || settingsRepository.getSettings().operationalMode == com.example.data.model.OperationalMode.UNLOCK) {
            pendingUnlock = false
            _pendingProfile.value = null
            XrayLogManager.i("VPN", "Permission granted by user. Starting Unlock mode.")
            VpnController.startUnlock(context)
            return
        }
        viewModelScope.launch {
            val profileToConnect = _pendingProfile.value
                ?: selectedProfile.value
                ?: smartRecommendation.value?.profile
                ?: serverRepository.getAllProfilesOnce().firstOrNull()

            _pendingProfile.value = null
            val smart = pendingSmart
            val exact = pendingExact
            val powerButton = pendingPowerButton
            pendingSmart = false
            pendingExact = false
            pendingPowerButton = false

            if (profileToConnect != null) {
                XrayLogManager.i("VPN", "Permission granted by user. Explicitly starting VPN tunnel for '${profileToConnect.name}'")
                VpnController.startVpn(context, profileToConnect, smart = smart, exact = exact, powerButton = powerButton)
            } else {
                XrayLogManager.w("VPN", "Permission granted but no profile available.")
                connectWithoutServer(context)
            }
        }
    }

    /**
     * The power button with no server selected or saved. Build 176 device test: it failed silently ("No server profile
     * available to connect." only in the log, the screen still said "Tap the button to connect"). It now starts Smart
     * Connect without a config, the same as the "Connect without a config" button; Trade mode, which connects only the
     * selected server, says why nothing started instead.
     */
    private fun connectWithoutServer(context: Context) {
        val mode = settingsRepository.getSettings().operationalMode
        if (!com.example.vpn.safety.PowerButtonPolicy.configFreeWhenNoServer(mode)) {
            RayVpnService.updateState(
                VpnController.connectionState.value.copy(
                    status = ConnectionStatus.FAILED,
                    errorMessage = context.getString(com.example.R.string.power_no_server_trade)
                )
            )
            return
        }
        XrayLogManager.i("VPN", "Power button: no server is selected or saved; starting Smart Connect without a config.")
        val profile = com.example.vpn.connections.BuiltinConnections.resolve(context,
            com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod.AETHER_WARP.id)
            ?: com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod.AETHER_WARP.profile()
        RayVpnService.mutateState { s -> if (s.isTunnelUp || s.isBusy) s else s.copy(status = ConnectionStatus.PREPARING, errorMessage = null, stopReason = null) }
        VpnController.startVpn(context, profile, smart = true, exact = false)
    }

    fun onPermissionDenied() {
        _pendingProfile.value = null
        pendingUnlock = false
        XrayLogManager.w("VPN", "User denied VPN permission dialog.")
        RayVpnService.updateState(
            VpnController.connectionState.value.copy(
                status = ConnectionStatus.FAILED,
                errorMessage = "VPN permission was denied by user."
            )
        )
    }

    fun toggleConnection(context: Context, origin: com.example.vpn.StopOrigin = com.example.vpn.StopOrigin.POWER_BUTTON,
                         onRequestPermission: ((Intent) -> Unit)? = null) {
        val currentState = connectionState.value
        // FAILED keeps the protective block in place, so the button must release it rather than
        // retry the same failing profile forever.
        if (currentState.isTunnelUp || currentState.isBusy || currentState.status == ConnectionStatus.FAILED) {
            // A second tap right after the Connect tap is part of that tap (StopGuard, build 178 field report).
            if (currentState.status != ConnectionStatus.FAILED && com.example.vpn.StopGuard.swallow(origin)) {
                VpnController.stopVpn(context, origin)
                return
            }
            // The button answers on the tap; the service then tears down (it sets the same state itself). The state the
            // stop came from goes with it (build 179, N8: every stop was logged "while disconnecting").
            if (currentState.status != ConnectionStatus.FAILED) {
                RayVpnService.mutateState { it.copy(status = ConnectionStatus.DISCONNECTING) }
            }
            VpnController.stopVpn(context, origin, fromStatus = currentState.status)
        } else {
            if (settingsRepository.getSettings().operationalMode == com.example.data.model.OperationalMode.UNLOCK) {
                connectUnlock(context) { intent -> onRequestPermission?.invoke(intent) }
                return
            }
            // The power button connects the config selected in Nodes first. With "Power button fallback" on
            // (default) a config that carries no real traffic hands over to failover and the recovery engines;
            // off, only that config is started. Smart Connect stays the separate button that compares paths.
            val targetProfile = selectedProfile.value ?: smartRecommendation.value?.profile
            startOrRequestPermission(context, targetProfile,
                exact = com.example.vpn.safety.PowerButtonPolicy.exact(settingsRepository.getSettings()), powerButton = true) { intent ->
                onRequestPermission?.invoke(intent)
            }
        }
    }

    /** Unlock mode's Connect: no server profile, only the VPN permission is needed. */
    fun connectUnlock(context: Context, onRequestPermission: (Intent) -> Unit) {
        val prepareIntent = VpnController.prepareVpn(context)
        if (prepareIntent != null) {
            pendingUnlock = true
            RayVpnService.mutateState { s -> if (s.isTunnelUp || s.isBusy) s else s.copy(status = ConnectionStatus.PREPARING, errorMessage = null, stopReason = null) }
            onRequestPermission(prepareIntent)
            return
        }
        RayVpnService.mutateState { s -> if (s.isTunnelUp || s.isBusy) s else s.copy(status = ConnectionStatus.PREPARING, errorMessage = null, stopReason = null) }
        VpnController.startUnlock(context)
    }

    /**
     * The user's explicit "Connect anyway" after a connect stopped because the selected server carried no
     * real traffic. Never called automatically.
     */
    fun connectAnyway(context: Context, onRequestPermission: ((Intent) -> Unit)? = null) {
        val profile = selectedProfile.value ?: return
        val prepareIntent = VpnController.prepareVpn(context)
        if (prepareIntent != null) {
            prepareConnect(profile)
            onRequestPermission?.invoke(prepareIntent)
            return
        }
        XrayLogManager.i("VPN", "User chose Connect anyway for '${profile.name}'.")
        VpnController.startVpn(context, profile, force = true)
    }

    /**
     * The user tapped "Connect with ..." on a failed Aether or direct Psiphon pick (or a pinned restart): start the connection verified on this network, as
     * chosen (exact), so it is the user's own choice rather than an automatic switch.
     */
    fun connectSuggested(context: Context, profileId: String, onRequestPermission: ((Intent) -> Unit)? = null) {
        viewModelScope.launch {
            val profile = com.example.vpn.connections.BuiltinConnections.resolve(context, profileId)
                ?: serverRepository.getProfileById(profileId) ?: return@launch
            XrayLogManager.i("VPN", "User chose the suggested connection (verified on this network) instead of the failing one.")
            startOrRequestPermission(context, profile, exact = true) { intent -> onRequestPermission?.invoke(intent) }
        }
    }

    /**
     * Smart Connect: the search across saved servers, disguises, recovery and revival, led by the optimal
     * node. It leaves the Nodes selection alone, so the power button still connects the user's own choice.
     */
    fun connectSmart(context: Context, onRequestPermission: ((Intent) -> Unit)? = null) {
        val lead = smartRecommendation.value?.profile ?: selectedProfile.value
        startOrRequestPermission(context, lead, smart = true) { intent ->
            onRequestPermission?.invoke(intent)
        }
    }

    /**
     * Build 179 device test (N9): Home's card stayed on "MASQUE HTTP/3 · Selected in Nodes" with no way back to
     * "No server selected". Clears the Nodes selection and the last tried connection while nothing is running.
     */
    fun clearSelection() {
        val state = connectionState.value
        if (state.isTunnelUp || state.isBusy) return
        settingsRepository.setSelectedProfileId(com.example.ui.home.HomeSelection.CLEARED)
        RayVpnService.mutateState { s -> if (s.isTunnelUp || s.isBusy) s else com.example.ui.home.HomeSelection.cleared(s) }
    }

    fun selectServer(profile: VlessProfile) {
        settingsRepository.setSelectedProfileId(profile.id)
    }

    fun reconnect(context: Context) {
        VpnController.reconnectVpn(context)
    }
}
