package com.example.vpn

import kotlinx.coroutines.ensureActive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.SecretRedactor
import com.example.data.database.AppDatabase
import com.example.data.model.AppSettings
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.RoutingMode
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.vpn.engine.EngineSelectionPolicy
import com.example.vpn.engine.VpnEngine
import com.example.vpn.engine.NativeTunVpnEngine
import com.example.xray.XrayConfigBuilder
import com.example.xray.XrayEngine
import com.example.xray.XrayEngineImpl
import com.example.xray.XrayLogManager
import java.net.DatagramSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

class RayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.drfxai.maximusvpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.drfxai.maximusvpn.ACTION_DISCONNECT"
        const val ACTION_RECONNECT = "com.drfxai.maximusvpn.ACTION_RECONNECT"
        const val EXTRA_PROFILE_ID = "com.drfxai.maximusvpn.EXTRA_PROFILE_ID"
        const val EXTRA_SMART = "com.drfxai.maximusvpn.EXTRA_SMART"
        /** "Connect anyway": start the selected server even though its real pre-flight failed. */
        const val EXTRA_FORCE = "com.drfxai.maximusvpn.EXTRA_FORCE"
        /**
         * The power button: connect the config selected in Nodes exactly as saved. No remembered route,
         * disguise, other server, emergency transport, recovery or revival replaces it, and it is started
         * without a pre-flight; the traffic check after start reports whether it carries traffic.
         * Smart Connect is the separate, explicit search ([EXTRA_SMART]).
         */
        const val EXTRA_EXACT = "com.drfxai.maximusvpn.EXTRA_EXACT"
        private const val SUBSCRIPTION_REFRESH_DELAY_MS = 5_000L
        /** How long a connect may spend in the LAB's fast recovery before it reports no verified path. */
        internal const val FAST_RECOVERY_MS = 300_000L
        // The agent's last resort: revive dead Cloudflare-fronted configs. Bounded so a failed connect
        // does not hang; revival keeps the winning recipe for next time even if this run is cut short.
        internal const val CONFIG_REVIVE_MS = 60_000L
        /** Re-check interval for a connection whose last checks all passed. */
        private const val PING_HEALTHY_MS = 30_000L
        /** How long a clean-address scan may take before the connect goes on without one. */
        private const val CLEAN_IP_SCAN_LIMIT_MS = 12_000L
        /** Requests through a new tunnel before it counts as unverified, and the pause between them. */
        private const val VERIFY_ATTEMPTS = 3
        private const val VERIFY_RETRY_MS = 1_500L
        private const val SLOW_PROBE_STEP_MS = 20_000
        private const val SLOW_VERIFY_STEP_MS = 25_000L
        private const val SLOW_VERIFY_CONFIG_MS = 90_000L

        const val NOTIFICATION_CHANNEL_ID = "maximus_vpn_channel"
        const val NOTIFICATION_ID = 1001

        private val _vpnState = MutableStateFlow(ConnectionState())
        val vpnState: StateFlow<ConnectionState> = _vpnState.asStateFlow()

        /** Set at each Connect: true only when the Smart Connect button started the session. */
        @Volatile private var smartStarted = false

        /** Every published state says whether Smart Connect owns the session, so only its button lights up. */
        private fun stamped(state: ConnectionState): ConnectionState {
            val smart = smartStarted && state.status != ConnectionStatus.DISCONNECTED
            return if (state.smartSession == smart) state else state.copy(smartSession = smart)
        }

        fun updateState(state: ConnectionState) {
            _vpnState.value = stamped(state)
        }

        /** Changes the state atomically, so concurrent watchers never overwrite each other's fields. */
        fun mutateState(change: (ConnectionState) -> ConnectionState) {
            while (true) {
                val current = _vpnState.value
                if (_vpnState.compareAndSet(current, stamped(change(current)))) return
            }
        }

        private fun newId(): String = java.util.UUID.randomUUID().toString().take(12)

        private val _lockdown = MutableStateFlow(com.example.vpn.safety.MaximusVpnSupervisor.Lockdown.UNKNOWN)
        private val _environment = MutableStateFlow<com.example.vpn.smart.NetworkEnvironment.Report?>(null)
        /** How hostile the network looked at the last connect. */
        val environment: StateFlow<com.example.vpn.smart.NetworkEnvironment.Report?> = _environment.asStateFlow()

        /** Whether Android keeps traffic blocked if this app's process dies; read at each connect. */
        val lockdown: StateFlow<com.example.vpn.safety.MaximusVpnSupervisor.Lockdown> = _lockdown.asStateFlow()
    }

    // Safety net: any unexpected error in a VPN coroutine becomes a clean FAILED state instead of
    // crashing the whole app ("Maximus VPN closed because this app has a bug"). The user can then
    // retry or stop, which tears the connection down normally.
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, e ->
        if (e is CancellationException) return@CoroutineExceptionHandler
        runCatching { XrayLogManager.e("VPN", "Unhandled error in a VPN coroutine: ${e.message}", e) }
        runCatching {
            updateState(
                _vpnState.value.copy(
                    status = ConnectionStatus.FAILED,
                    errorMessage = "Unexpected error while connecting. Please try again."
                )
            )
        }
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + serviceExceptionHandler)
    private val connectionMutex = Mutex()
    private var connectJob: Job? = null
    /** Single-flight rules: stale failover or auto-reconnect work is dropped, one teardown and one switch at a time. */
    private val lifecycle = ConnectionLifecycle()
    private var durationJob: Job? = null
    private var pingJob: Job? = null

    private val supervisor = com.example.vpn.safety.MaximusVpnSupervisor { XrayLogManager.i("VPN", it) }
    private val protectionRequested: Boolean get() = supervisor.protectionRequested
    private val engineBreaker = com.example.vpn.engine.registry.EngineCircuitBreaker()
    /** The last server-name lookup met a blocked DNS answer. */
    @Volatile private var dnsPoisoned = false
    /** GOD MODE: server names are looked up only over DNS-over-HTTPS (see OperatingModePolicy). */
    @Volatile private var privateServerLookup = false
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelManager: TunnelManager? = null
    /** The separate engine program carrying traffic for the current profile, if any. */
    @Volatile private var sidecar: com.example.vpn.sidecar.RunningEngine? = null
    private var sidecarForwardProfile: VlessProfile? = null
    private var sidecarNetwork: String? = null
    private var sidecarOwnerFingerprint: String? = null

    /**
     * The local port of the engine program this session forwards to, when there is one. The app is left
     * out of its own tunnel then (Android refuses it the VPN network), so checks go through this port.
     */
    @Volatile private var engineEndpoint: com.example.vpn.verify.Socks5.Endpoint? = null
    /** The Smart Connect search of the current attempt: what each stage tested and verified. */
    @Volatile private var search: com.example.vpn.smart.SearchTracker? = null
    /** Network, MTU the tunnel was built with, and the configured MTU, for the MTU test (TunMtuTrial). */
    @Volatile private var tunMtu: Triple<String, Int, Int>? = null

    /** The upload and download check of the current attempt. */
    private var trafficCheckJob: Job? = null

    /** The remembered route (network, recipe id) the current session runs on, once verified. */
    @Volatile private var activeRoute: Pair<String, String>? = null

    private class Replay(val owner: VlessProfile, val profile: VlessProfile, val recovery: com.example.vpn.connectivity.DerivedRecoveryCandidate?)

    /**
     * Rebuilds the exact path of [recipe] from the saved config it names, or null when that config was
     * deleted or changed, or the alternate or recovered copy no longer applies. [resolved] is the config
     * with its name already resolved, when the caller has it. Engine programs are never replayed this way:
     * they cannot be tested before they start.
     */
    private suspend fun replayRoute(recipe: com.example.vpn.smart.RouteRecipe, network: String, resolved: VlessProfile?): Replay? {
        val owner = serverRepository.getAllProfilesOnce().firstOrNull { it.id == recipe.profileId } ?: return null
        if (owner.effectiveFingerprint != recipe.profileFingerprint) return null
        if (com.example.vpn.sidecar.Sidecars.isEngineProfile(owner) || owner.profileType == ProfileType.XRAY_JSON) return null
        val base = when {
            recipe.endpoint != null && !isLiteralIp(owner.address) -> com.example.vpn.smart.RouteRecipe.pinEndpoint(owner, recipe.endpoint)
            resolved != null -> resolved
            isLiteralIp(owner.address) -> owner
            else -> resolveEndpoint(owner)
        }
        var recovery: com.example.vpn.connectivity.DerivedRecoveryCandidate? = null
        val path = when {
            recipe.recoveryKey != null -> {
                recovery = com.example.RayApplication.instance.bpbRecovery.active(owner, network, base = base)
                    ?.takeIf { it.recoveryProfileKey == recipe.recoveryKey } ?: return null
                recovery.profile
            }
            recipe.variantKey != null -> com.example.vpn.stealth.StealthVariants.of(base).firstOrNull { it.key == recipe.variantKey }?.profile ?: return null
            else -> base
        }
        com.example.vless.VlessValidator.validate(path)
        com.example.vpn.engine.RuntimeCapabilities.requireSupported(path)
        return Replay(owner, path, recovery)
    }

    /**
     * Remembers the path that just carried verified traffic as a route for [network]: references and
     * non-secret settings only. Returns its key, or null for paths that cannot be replayed (engine
     * programs, configs that are not in the server list).
     */
    private suspend fun recordRoute(network: String, owner: VlessProfile, path: VlessProfile, variant: String?, latencyMs: Long): Pair<String, String>? = runCatching {
        if (com.example.vpn.sidecar.Sidecars.isEngineProfile(owner) || owner.profileType == ProfileType.XRAY_JSON) return@runCatching null
        if (serverRepository.getAllProfilesOnce().none { it.id == owner.id }) return@runCatching null
        val recovery = activeRecovery
        val now = System.currentTimeMillis()
        val recipe = com.example.vpn.smart.RouteRecipe(
            network = network, profileId = owner.id, profileFingerprint = owner.effectiveFingerprint,
            variantKey = if (recovery == null) variant else null,
            recoveryKey = recovery?.recoveryProfileKey,
            endpoint = path.address.takeIf { recovery == null && isLiteralIp(it) && !isLiteralIp(owner.address) },
            kind = com.example.vpn.stealth.ConnectionKind.of(path),
            latencyMs = latencyMs, verifiedAt = now, expiresAt = now + com.example.vpn.smart.RouteBook.TTL_MS
        )
        networkMemory.routes.recordVerified(recipe)
        network to recipe.id
    }.onFailure { XrayLogManager.w("SMART", "Could not remember the route: ${it.javaClass.simpleName}") }.getOrNull()
    private var activeEngine: VpnEngine = XrayEngineImpl.instance
    private var failoverManager: com.example.vpn.smart.FailoverManager? = null
    private val serviceTxBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val serviceRxBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val protectionFailureHandled = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var serverRepository: ServerRepository
    private lateinit var settingsRepository: SettingsRepository
    private var activeProfile: VlessProfile? = null
    /** The recovery candidate this session runs on, when a derived copy carried its traffic. */
    @Volatile private var activeRecovery: com.example.vpn.connectivity.DerivedRecoveryCandidate? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var settingsObserverJob: Job? = null
    private var lastObservedMode: com.example.data.model.OperationalMode? = null

    override fun onCreate() {
        super.onCreate()
        com.example.vpn.sidecar.Sidecars.nativeLibraryDir = applicationInfo.nativeLibraryDir
        com.example.vpn.sidecar.Sidecars.openAsset = { name -> assets.open(name) }
        com.example.vpn.sidecar.Sidecars.torStarter = { torrc, port -> com.example.vpn.sidecar.TorInApp.start(this, torrc, port) }
        val db = AppDatabase.getInstance(applicationContext)
        serverRepository = ServerRepository(db.serverProfileDao())
        settingsRepository = com.example.RayApplication.instance.settingsRepository
        createNotificationChannel()
        registerNetworkCallback()
        observeSettingsFlow()
        // Real-delay probes must bypass this VPN while it exists.
        com.example.xray.RealDelayProbe.socketProtector = { fd -> safeProtectFd(fd) }
    }

    private fun observeSettingsFlow() {
        settingsObserverJob?.cancel()
        settingsObserverJob = serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                val prevMode = lastObservedMode
                lastObservedMode = settings.operationalMode
                if (prevMode != null && prevMode != settings.operationalMode && _vpnState.value.isTunnelUp) {
                    handleLiveModeSwitch(prevMode, settings.operationalMode)
                }
            }
        }
    }

    /** Rebuild routing under the new policy without bootstrapping an unchanged reviewed sidecar. */
    private suspend fun handleLiveModeSwitch(
        oldMode: com.example.data.model.OperationalMode,
        newMode: com.example.data.model.OperationalMode
    ) {
        val ticket = lifecycle.current()
        connectionMutex.withLock {
            if (!lifecycle.isCurrent(ticket) || !_vpnState.value.isTunnelUp ||
                settingsRepository.getSettings().operationalMode != newMode) return@withLock
            val profile = activeProfile ?: return@withLock
            val engine = com.example.vpn.sidecar.Sidecars.forProfile(profile)
            val retain = com.example.vpn.sidecar.ModeSwitchReuse.allowed(
                engine?.id, _vpnState.value.isConnected, sidecar?.isAlive == true,
                sidecarForwardProfile != null && sidecarOwnerFingerprint == profile.effectiveFingerprint,
                sidecarNetwork != null && sidecarNetwork == ledgerNetwork()
            ) && (newMode != com.example.data.model.OperationalMode.GHOST_RIDER || engine?.id == "tor")
            XrayLogManager.i("VPN", "Mode switched from ${oldMode.name} to ${newMode.name}; " +
                if (retain) "applying routing while preserving the running ${engine?.id} engine; traffic will be reverified."
                else "applying routing with a fresh engine start.")
            lifecycle.newIntent() // Older queued failover must not undo the user's new mode (especially TRADE).
            connectLocked(profile, reuseSidecar = if (retain) sidecarForwardProfile else null)
        }
    }

    /** Records whether Android blocks traffic if the app dies, and says what to change when it does not. */
    private fun checkLockdown() {
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            com.example.vpn.safety.MaximusVpnSupervisor.lockdownOf(Build.VERSION.SDK_INT, { isAlwaysOn }, { isLockdownEnabled })
        } else {
            com.example.vpn.safety.MaximusVpnSupervisor.Lockdown.UNKNOWN
        }
        _lockdown.value = state
        com.example.vpn.safety.MaximusVpnSupervisor.lockdownAdvice(state, settingsRepository.getSettings().operationalMode)
            ?.let { XrayLogManager.w("VPN", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CONNECT && !_vpnState.value.isTunnelUp &&
            (!_vpnState.value.isBusy || _vpnState.value.status == ConnectionStatus.PREPARING)) {
            // Show "connecting" the moment the button is tapped. The protective interface, the stop of
            // the previous engine and the connect lock below can take a while, and until now the button
            // looked unresponsive during them.
            smartStarted = intent.getBooleanExtra(EXTRA_SMART, false)
            updateState(_vpnState.value.copy(status = ConnectionStatus.PREPARING, errorMessage = null))
        }
        if (action == null || action == ACTION_CONNECT || action == ACTION_RECONNECT) {
            supervisor.requestProtection()
            checkLockdown()
            if (!showForegroundNotification("Protecting traffic while connecting...")) return START_NOT_STICKY
            try { ensureBlockingInterface() } catch (e: Exception) {
                updateState(ConnectionState(status = ConnectionStatus.FAILED, errorMessage = "Unable to establish traffic protection"))
                return START_NOT_STICKY
            }
        }
        if (action == null) {
            // Android can restart an Always-on VPN with a null intent after process death.
            if (!showForegroundNotification("Starting Always-on Maximus VPN...")) return START_NOT_STICKY
            lifecycle.newIntent()
            connectJob?.cancel()
            connectJob = serviceScope.launch {
                val profile = settingsRepository.getSettings().selectedProfileId
                    ?.let { serverRepository.getProfileById(it) }
                    ?: serverRepository.getAllProfilesOnce().firstOrNull()
                if (profile != null) {
                    connect(profile)
                } else {
                    updateState(_vpnState.value.copy(
                        status = ConnectionStatus.FAILED,
                        errorMessage = "Always-on VPN has no selected server profile."
                    ))
                    if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
                    if (!protectionRequested) stopSelf()
                }
            }
            return START_STICKY
        }
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        val smart = intent?.getBooleanExtra(EXTRA_SMART, false) == true
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
        val exact = intent?.getBooleanExtra(EXTRA_EXACT, false) == true && !smart

        when (action) {
            ACTION_CONNECT -> {
                val fgOk = showForegroundNotification("Starting Maximus VPN...")
                if (!fgOk) {
                    XrayLogManager.e("VPN", "Aborting onStartCommand: startForeground failed.")
                    updateState(_vpnState.value.copy(
                        status = ConnectionStatus.FAILED,
                        errorMessage = "Foreground notification could not be started."
                    ))
                    if (!protectionRequested) stopSelf()
                    return START_NOT_STICKY
                }

                lifecycle.newIntent()
                // The user's choice for this session: reconnects and mode switches keep it until the next Connect.
                exactSession = exact
                smartStarted = smart
                connectJob?.cancel()
                // Engines of the attempt this Connect replaces stop now, so the new attempt does not wait behind them.
                com.example.vpn.sidecar.EngineReaper.stopAll()
                com.example.vpn.sidecar.EngineHandoff.clear()
                connectJob = serviceScope.launch {
                    val profile = if (!profileId.isNullOrBlank()) {
                        serverRepository.getProfileById(profileId)
                    } else null

                    val targetProfile = profile
                        ?: settingsRepository.getSettings().selectedProfileId?.let { serverRepository.getProfileById(it) }
                        ?: serverRepository.getAllProfilesOnce().firstOrNull()

                    if (targetProfile != null) {
                        connect(targetProfile, smart = smart, startedByUser = true, force = force)
                    } else {
                        val err = "No valid server profile found to connect."
                        XrayLogManager.e("VPN", err)
                        updateState(_vpnState.value.copy(
                            status = ConnectionStatus.FAILED,
                            errorMessage = err
                        ))
                        if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
                        if (!protectionRequested) stopSelf()
                    }
                }
            }
            ACTION_DISCONNECT -> {
                // ACTION_DISCONNECT can arrive through startForegroundService() when the
                // app is backgrounded. Android 14 still requires that service to call
                // startForeground() before doing any asynchronous teardown.
                if (!showForegroundNotification("Stopping Maximus VPN...")) {
                    if (!protectionRequested) stopSelf()
                    return START_NOT_STICKY
                }
                // A failover or auto-reconnect that fires from now on belongs to an older intent and is dropped.
                lifecycle.newIntent()
                connectJob?.cancel()
                connectJob = null
                // Show the stop at once, and end every engine still starting, so the cancelled attempt
                // unwinds immediately instead of after its current round of tests (see Abandonable).
                if (_vpnState.value.status != ConnectionStatus.DISCONNECTED) {
                    updateState(_vpnState.value.copy(status = ConnectionStatus.DISCONNECTING))
                }
                com.example.vpn.sidecar.EngineReaper.stopAll()
                com.example.vpn.sidecar.EngineHandoff.clear()
                serviceScope.launch {
                    // A second Disconnect while one runs joins it instead of tearing down twice.
                    lifecycle.singleTeardown { disconnect() }
                }
            }
            ACTION_RECONNECT -> {
                val fgOk = showForegroundNotification("Reconnecting Maximus VPN...")
                if (!fgOk) {
                    if (!protectionRequested) stopSelf()
                    return START_NOT_STICKY
                }
                lifecycle.newIntent()
                connectJob?.cancel()
                connectJob = serviceScope.launch {
                    val profile = activeProfile
                    if (profile != null) {
                        connect(profile)
                    } else {
                        updateState(_vpnState.value.copy(
                            status = ConnectionStatus.FAILED,
                            errorMessage = "Cannot reconnect: no active server profile."
                        ))
                        if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
                        if (!protectionRequested) stopSelf()
                    }
                }
            }
        }

        return if (action == ACTION_CONNECT || action == ACTION_RECONNECT) START_STICKY else START_NOT_STICKY
    }

    /**
     * [smart]: race the saved servers with real requests first ([profile] leads) and connect the fastest
     * that works; [exclude] are servers to leave out (one the watchdog just gave up on).
     */
    private suspend fun connect(
        profile: VlessProfile,
        smart: Boolean = false,
        exclude: Set<String> = emptySet(),
        /** The user pressed Connect (not failover, reconnect or Always-on); see FailClosedPolicy. */
        startedByUser: Boolean = false,
        /** The user chose "Connect anyway" for a server whose real pre-flight failed (see PathGate). */
        force: Boolean = false,
        /** Automatic work (failover, auto-reconnect) carries the intent it started in; a newer one drops it. */
        ticket: Long? = null
    ): Unit = connectionMutex.withLock {
        if (ticket != null && !lifecycle.isCurrent(ticket)) {
            XrayLogManager.i("VPN", "Dropped an automatic reconnect: a newer Connect or Disconnect came after it.")
            return@withLock
        }
        connectLocked(profile, smart, exclude, startedByUser, force)
    }

    /** The session was started with the power button: only the selected config, as saved (see [EXTRA_EXACT]). */
    @Volatile private var exactSession = false

    /** What worked on each carrier or Wi-Fi: stealth alternates, kinds of connection, probe time limits. */
    private val networkMemory: com.example.vpn.smart.NetworkMemory get() = com.example.RayApplication.instance.networkMemory

    /**
     * Races the saved servers other than [exclude] (see ServerRace) and returns the fastest that works.
     * When none works as saved (a network that filters every kind), the best-ranked one is tried in
     * disguise with [finder] (its saved form was just tested, so that step is skipped).
     */
    private suspend fun raceServers(
        exclude: Set<String>,
        network: String,
        timeoutSec: Int,
        finder: com.example.vpn.stealth.StealthPathFinder,
        /** Also try the [exclude]d servers in disguise (only their saved form was tested). */
        retryDisguised: Boolean
    ): com.example.vpn.smart.ServerRace.Winner? {
        // Profiles carried by an engine program cannot be measured before it runs, so they are not raced.
        val all = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
            .filter { !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) }
        val intel = runCatching { com.example.RayApplication.instance.iranIntel.current() }.getOrNull()
        val ranked = com.example.vpn.smart.ServerRace.rank(
            all, networkMemory.workingKinds(network), networkMemory.recentFailures(network), exclude = exclude,
            limit = com.example.vpn.smart.ServerRace.FULL_LIMIT,
            adjust = { p -> intel?.adjustmentFor(p, network)?.value ?: 0.0 }
        )
        // UDP where UDP is blocked, IPv6 where there is no IPv6: skipped, not hammered (TransportCapabilityEngine).
        val ordered = com.example.vpn.connectivity.TransportCapabilityEngine.order(ranked, transportCapabilities(network))
        // Servers that failed a real request on this network moments ago are not raced again (ConnectivityBrain).
        val book = com.example.vpn.connectivity.ConnectivityBrain.book
        val candidates = ordered.filter { book.eligibility(com.example.vpn.connectivity.PathRef.of(it)).automatic }
        if (candidates.size < ordered.size) XrayLogManager.i("SMART", "Skipped ${ordered.size - candidates.size} servers that failed here moments ago.")
        if (ordered.size < ranked.size) {
            XrayLogManager.i("SMART", "Skipped ${ranked.size - ordered.size} servers whose transport this network does not carry.")
        }
        // Servers left out because their saved form just failed are still tried in disguise.
        val dead = if (retryDisguised) all.filter { it.id in exclude } else emptyList()
        if (candidates.isEmpty() && dead.isEmpty()) return null
        XrayLogManager.i("SMART", "Testing ${candidates.size} other servers on ${com.example.vpn.smart.NetworkKey.describe(network)}.")
        val resolve = { p: VlessProfile -> if (p.profileType != ProfileType.XRAY_JSON && !isLiteralIp(p.address)) resolveEndpoint(p) else p }
        val raceJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        // Abandonable: a Stop returns from here at once; the round already running finishes on its own.
        com.example.vpn.Abandonable.run {
            com.example.vpn.smart.ServerRace(probe = ::probeAll).run(candidates, resolve,
                timeoutSec.coerceAtLeast(com.example.vpn.smart.ServerRace.FULL_MIN_TIMEOUT_SEC),
                maxBatches = com.example.vpn.smart.ServerRace.FULL_BATCHES, onFailure = {
                networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(it))
            }, disguiseOnly = dead, shouldStop = { raceJob?.isActive == false })
        }?.let { return it }
        val best = candidates.firstOrNull() ?: return null
        val choice = com.example.vpn.Abandonable.run { runCatching { finder.choose(best, resolve, all, firstFailed = true) }.getOrNull() } ?: return null
        return choice.latencyMs?.let { com.example.vpn.smart.ServerRace.Winner(choice.profile, choice.owner, it) }
    }

    /** What this network was measured to carry, plus how UDP kinds fared here lately. */
    private fun transportCapabilities(network: String): com.example.vpn.connectivity.TransportCapabilityEngine.Capabilities {
        val working = networkMemory.workingKinds(network)
        val failing = networkMemory.recentFailures(network)
        val udpKinds = setOf("QUIC", "WireGuard")
        val udpFailing = failing.any { it in udpKinds } && working.none { it in udpKinds }
        // Measurements older than ten minutes may describe another network: treated as not measured.
        val measured = com.example.vpn.smart.NetworkCapabilityDetector.last
            ?.takeIf { System.currentTimeMillis() - it.measuredAt < 10 * 60_000L }
        return com.example.vpn.connectivity.TransportCapabilityEngine.capabilities(
            measured,
            com.example.vpn.connectivity.TransportCapabilityEngine.History(
                udpFailures = if (udpFailing) com.example.vpn.connectivity.TransportCapabilityEngine.IMPAIRED_AFTER else 0
            )
        )
    }

    /**
     * A Cloudflare-fronted server whose address is blocked usually still answers on another Cloudflare
     * address: this scans for one and returns the same server reached through it, if it carries traffic.
     */
    private suspend fun cleanIpPath(profile: VlessProfile, timeoutSec: Int): com.example.vpn.smart.ServerRace.Winner? {
        val fronted = profile.transport.lowercase() in setOf("ws", "xhttp", "splithttp", "httpupgrade", "grpc", "h2") &&
            profile.security.equals("tls", true) && !profile.allowInsecure
        if (!fronted) return null
        showForegroundNotification("Looking for a verified clean path...")
        val scanNetwork = com.example.vpn.smart.NetworkKey.current(this)
        val cached = com.example.RayApplication.instance.endpointScores.validated(scanNetwork)
        val candidates = withTimeoutOrNull(CLEAN_IP_SCAN_LIMIT_MS) {
            com.example.panels.CleanIpOptimizer.findCleanIpsSync(port = profile.port, openSocket = {
                Socket().also { socket ->
                    if (!safeProtectSocket(socket)) { socket.close(); error("Clean-IP socket protection failed") }
                }
            })
        }.orEmpty()
        val attempt = kotlinx.coroutines.currentCoroutineContext()[Job]
        return com.example.vpn.Abandonable.run {
            com.example.vpn.smart.CleanPathRace.select(profile, (cached + candidates.map { it.ip }).distinct(),
                probe = { paths -> probeAll(paths, timeoutSec) },
                cancelled = { attempt?.isActive == false || com.example.vpn.smart.NetworkKey.current(this) != scanNetwork })
        }?.also {
            com.example.RayApplication.instance.endpointScores.record(listOf(com.example.vpn.connectivity.EndpointScoringEngine.Measurement(
                it.profile.address, scanNetwork, System.currentTimeMillis(), true, it.latencyMs)))
            XrayLogManager.i("SMART", "A clean path carried two real requests (${it.latencyMs} ms).")
        }
    }

    /**
     * GOD MODE's last step before reporting no path: Cloudflare WARP on the WireGuard path, which needs
     * no server of the user's. The first use registers a device over the phone's own network.
     */
    private suspend fun warpPath(timeoutSec: Int): com.example.vpn.smart.ServerRace.Winner? {
        showForegroundNotification("Trying Cloudflare WARP...")
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val network = cm?.allNetworks.orEmpty().firstOrNull { n ->
            cm?.getNetworkCapabilities(n)?.let { caps ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        } ?: return null
        val warp = runCatching {
            com.example.vpn.warp.WarpProvider.profile(this) { url ->
                network.openConnection(url, java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            }
        }.onFailure { XrayLogManager.w("SMART", "Cloudflare WARP registration failed: ${it.message}") }.getOrNull() ?: return null
        if (com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(warp) != null) return null
        val outcome = com.example.vpn.Abandonable.run { probe(warp, timeoutSec) }
        return (outcome as? com.example.xray.RealDelayProbe.Outcome.Delay)?.let {
            XrayLogManager.i("SMART", "Cloudflare WARP carries traffic (${it.latencyMs} ms).")
            com.example.vpn.smart.ServerRace.Winner(warp, warp, it.latencyMs)
        }
    }

    /** One real request per profile; each result counts towards the search stage that is running. */
    private fun probeAll(profiles: List<VlessProfile>, timeoutSec: Int): List<com.example.xray.RealDelayProbe.Outcome> =
        com.example.xray.RealDelayProbe.measure(profiles, timeoutSec).also { outcomes ->
            val tracker = search ?: return@also
            outcomes.forEach { o ->
                when (o) {
                    is com.example.xray.RealDelayProbe.Outcome.Delay -> tracker.count(carried = true)
                    is com.example.xray.RealDelayProbe.Outcome.Failed -> tracker.count(carried = false)
                    is com.example.xray.RealDelayProbe.Outcome.NotRun -> Unit
                }
            }
        }

    private fun probe(profile: VlessProfile, timeoutSec: Int): com.example.xray.RealDelayProbe.Outcome = probeAll(listOf(profile), timeoutSec).first()

    private suspend fun connectLocked(
        userProfile: VlessProfile,
        smart: Boolean = false,
        exclude: Set<String> = emptySet(),
        startedByUser: Boolean = false,
        force: Boolean = false,
        reuseSidecar: VlessProfile? = null
    ): Unit = withContext(Dispatchers.IO) {
        // The saved profile the connection belongs to; a same-server switch below can change it.
        var requestedProfile = userProfile
        // Engines receive [profile]; a hostname endpoint is replaced by its resolved IP below.
        var profile = requestedProfile
        // Correlation: a session runs from the user's Connect to Disconnect; every attempt (failover,
        // reconnect, mode switch) gets its own id, so a late result of an old attempt is ignored.
        val previous = _vpnState.value
        val sessionId = if (startedByUser || previous.sessionId == null || previous.status == ConnectionStatus.DISCONNECTED) newId()
            else previous.sessionId
        val attemptId = newId()
        val selectedId = if (startedByUser || previous.selectedProfileId == null) userProfile.id else previous.selectedProfileId
        fun fresh(status: ConnectionStatus, error: String? = null, stage: com.example.vpn.diagnostics.FailureStage? = null) = ConnectionState(
            status = status, activeProfile = requestedProfile, errorMessage = error, failureStage = stage,
            sessionId = sessionId, attemptId = attemptId, selectedProfileId = selectedId,
            attemptedProfileId = requestedProfile.id, networkGeneration = previous.networkGeneration,
            search = search?.progress()?.takeIf { it.attemptId == attemptId }
        )
        val connectStartedAt = System.currentTimeMillis()
        com.example.vpn.diagnostics.ConnectionMetrics.connectAttempts.incrementAndGet()
        // Every test in this connect feeds, and reads, the evidence of the current network session.
        com.example.vpn.connectivity.ConnectivityBrain.refreshSession()
        val book = com.example.vpn.connectivity.ConnectivityBrain.book
        val ladder = com.example.vpn.connectivity.RecoveryMachine { s ->
            mutateState { if (it.attemptId == attemptId) it.copy(recoveryStage = s.title) else it }
        }
        fun recentlyFailed(p: VlessProfile) = !force &&
            book.eligibility(com.example.vpn.connectivity.PathRef.of(p)) == com.example.vpn.connectivity.PathEligibility.RECENTLY_FAILED
        try {
            supervisor.requestProtection()
            ensureBlockingInterface()
            disconnectResources(preserveSidecar = reuseSidecar != null)
            protectionFailureHandled.set(false)

            // 1. Diagnostic step 1: Validate Android VPN permission
            val prepareIntent = VpnService.prepare(this@RayVpnService)
            if (prepareIntent != null) {
                val err = "VPN permission is not granted by user (VpnService.prepare returned intent)."
                XrayLogManager.e("VPN", "[DIAGNOSTICS] 1. VpnService.prepare() check: Permission DENIED.")
                updateState(fresh(ConnectionStatus.FAILED, "VPN permission not granted by Android system."))
                if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
                if (!protectionRequested) stopSelf()
                return@withContext
            }
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 1. VpnService.prepare() check: Permission GRANTED.")

            activeProfile = profile
            val policy = com.example.vpn.safety.OperatingModePolicy.of(settingsRepository.getSettings().operationalMode)
            dnsPoisoned = false
            privateServerLookup = policy.privateServerLookup
            val settings = policy.apply(settingsRepository.getSettings())
            // Trade mode and the power button pin the config: never race, never switch it for anything.
            val exact = exactSession || reuseSidecar != null
            val lockExit = policy.lockExit || exact
            val raceFirst = !lockExit && (smart || policy.alwaysSmartConnect)

            updateState(fresh(ConnectionStatus.PREPARING))

            // 2. Diagnostic step 2: Start foreground service immediately
            if (!showForegroundNotification("Preparing VPN interface...")) {
                XrayLogManager.e("VPN", "[DIAGNOSTICS] 2. Foreground service start FAILED.")
                updateState(_vpnState.value.copy(
                    status = ConnectionStatus.FAILED,
                    errorMessage = "Failed to start foreground service notification."
                ))
                if (!protectionRequested) stopSelf()
                return@withContext
            }
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 2. Foreground service started successfully.")

            // Routes are remembered per network, telling Wi-Fi networks apart (NetworkIdentity); a route
            // learned on one network is only ever a candidate on it, never on another.
            val routeNetwork = runCatching { com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService) }
                .getOrElse { com.example.vpn.smart.NetworkKey.current(this@RayVpnService) }
            activeRoute = null

            // 3. Diagnostic step 3: Validate VLESS Profile Configuration
            var failure = com.example.vpn.safety.FailClosedPolicy.Failure.INVALID_PROFILE
            var initialResolutionFailed = false
            try {
                // Engine programs (Mihomo, Psiphon, the DNS tunnel) look up their own servers privately.
                if (profile.profileType != ProfileType.XRAY_JSON && !com.example.vpn.sidecar.Sidecars.isEngineProfile(profile) &&
                    !isLiteralIp(profile.address)) {
                    // Resolve outside the tunnel: once traffic is captured, DNS for the proxy itself
                    // would loop back into the VPN.
                    failure = com.example.vpn.safety.FailClosedPolicy.Failure.UNRESOLVABLE_SERVER
                    profile = try {
                        resolveEndpoint(profile)
                    } catch (e: Exception) {
                        // The name does not resolve here now (DNS blocked or poisoned), but an address it
                        // resolved to carried verified traffic on this network: dial that address. TLS still
                        // checks the server's name; the pre-flight below still has to pass.
                        val known = if (policy.lockExit) null else networkMemory.routes.forNetwork(routeNetwork)
                            .firstOrNull { it.profileId == requestedProfile.id && it.endpoint != null &&
                                it.profileFingerprint == requestedProfile.effectiveFingerprint }?.endpoint
                        if (known != null) {
                            XrayLogManager.w("VPN", "Server name did not resolve; trying its previously verified address.")
                            com.example.vpn.smart.RouteRecipe.pinEndpoint(profile, known)
                        } else if (!lockExit && !force && (smart || policy.alwaysSmartConnect || settings.autoFailoverEnabled)) {
                            initialResolutionFailed = true
                            XrayLogManager.w("SMART", "Selected server DNS failed; continuing with alternate paths and recovery engines.")
                            profile
                        } else throw e
                    }
                    failure = com.example.vpn.safety.FailClosedPolicy.Failure.INVALID_PROFILE
                    if (!initialResolutionFailed) XrayLogManager.i("VPN", "[DIAGNOSTICS] 3. Server host resolved outside the tunnel")
                }
                com.example.vless.VlessValidator.validate(profile)
                com.example.vpn.engine.RuntimeCapabilities.requireSupported(profile)
            } catch (e: Exception) {
                XrayLogManager.e("VPN", "Profile validation error: ${e.message}", e)
                val release = com.example.vpn.safety.FailClosedPolicy.mayReleaseBlock(
                    failure, startedByUser, settingsRepository.getSettings().operationalMode)
                if (release) supervisor.release(com.example.vpn.safety.MaximusVpnSupervisor.Release.INVALID_PROFILE_BY_USER)
                disconnectResources()
                updateState(fresh(
                    ConnectionStatus.FAILED,
                    if (release) "Cannot use this profile: ${e.localizedMessage}"
                        else "Traffic blocked: ${e.localizedMessage}. Disconnect to use the network without the VPN.",
                    if (failure == com.example.vpn.safety.FailClosedPolicy.Failure.UNRESOLVABLE_SERVER)
                        com.example.vpn.diagnostics.FailureStage.DNS_RESOLUTION_FAILED else com.example.vpn.diagnostics.FailureStage.of(e)
                ))
                if (release) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    showForegroundNotification("Traffic blocked: server unreachable")
                }
                return@withContext
            }
            val safeName = com.example.core.SecretRedactor.redact(profile.name)
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 3. Profile configuration validated for '$safeName'")

            // 3a. Smart Connect and failover: the chosen server first, then a race of the other saved
            // servers. What worked on this carrier or Wi-Fi (NetworkMemory) orders the race and sets the
            // probe time limits. The probe's sockets bypass the traffic-blocking interface.
            val network = com.example.vpn.smart.NetworkKey.current(this@RayVpnService)
            val timeouts = networkMemory.timeouts(network)
            runCatching { com.example.vpn.smart.NetworkEnvironment.observe(this@RayVpnService, networkMemory, dnsPoisoned) }
                .onSuccess { report ->
                    _environment.value = report
                    XrayLogManager.i("SMART", "Network: ${report.describe()}")
                    // What was measured and what the app concludes from it are separate events.
                    val signals = report.signals.joinToString(",") { it.name }
                    com.example.vpn.diagnostics.events.EventLog.event("network.signals", attributes = mapOf("signals" to signals.ifEmpty { "none" }))
                    com.example.vpn.diagnostics.events.EventLog.event("network.assessment", attributes = mapOf(
                        "level" to report.level.name, "based.on" to signals.ifEmpty { "no failure signals" }
                    ), kind = com.example.vpn.diagnostics.events.DiagEvent.Kind.ASSESSMENT)
                    if (policy.mode == com.example.data.model.OperationalMode.DAILY && com.example.vpn.smart.NetworkEnvironment.suggestsGodMode(report)) {
                        XrayLogManager.w("SMART", "Assessment: filtering looks ${report.level.name.lowercase()} " +
                            "(based on: ${report.signals.joinToString { it.name.lowercase().replace('_', ' ') }}); GOD MODE proxies everything and tests every server.")
                    }
                }
            // Which stages this connect may run, steered by what was measured on this network (not by guesses).
            val plan = com.example.vpn.smart.SmartConnectPlan.of(com.example.vpn.smart.SmartConnectPlan.Input(
                godMode = policy.mode == com.example.data.model.OperationalMode.GOD_MODE,
                lockExit = lockExit, exact = exact && !policy.lockExit, force = force, mayMove = raceFirst || settings.autoFailoverEnabled,
                reading = com.example.vpn.smart.SmartConnectPlan.freshReading(
                    com.example.vpn.smart.NetworkCapabilityDetector.last, routeNetwork, System.currentTimeMillis())
            ))
            val tracker = com.example.vpn.smart.SearchTracker(attemptId, plan) { p ->
                mutateState { if (it.attemptId == attemptId) it.copy(search = p) else it }
            }
            search = tracker
            plan.reading?.let { XrayLogManager.i("SMART", "Measured network: ${it.summary()}.") }
            plan.transportsSkipped.forEach { (t, why) -> XrayLogManager.i("SMART", "${t.title} not tried directly: $why.") }
            var raced = false
            // What the pre-flight proved (PathGate): a real request failed on the selected path, or an
            // engine that can only be judged after it starts was chosen.
            var provenDead = initialResolutionFailed
            // Which stealth alternate carried the traffic, for the route recipe recorded once it is verified.
            var chosenVariant: String? = null
            activeRecovery = null
            fun adopt(win: com.example.vpn.smart.ServerRace.Winner) {
                com.example.vless.VlessValidator.validate(win.profile)
                com.example.vpn.engine.RuntimeCapabilities.requireSupported(win.profile)
                requestedProfile = win.owner
                activeProfile = win.owner
                profile = win.profile
                raced = true
                chosenVariant = win.variantKey
                win.variantKey?.let { networkMemory.variants(network)[win.owner.id] = it }
                networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(win.profile), win.latencyMs)
                mutateState { it.copy(activeProfile = win.owner, attemptedProfileId = win.owner.id) }
            }
            val finder = com.example.vpn.stealth.StealthPathFinder(
                // The finder tries the selected path alone first; once that carried nothing, the rest of its
                // tests (disguises, other kinds on the same server) belong to stage C.
                probe = { p, t ->
                    probeAll(p, t).also { out ->
                        if (tracker.runningStage == com.example.vpn.smart.SearchStage.SELECTED &&
                            out.none { it is com.example.xray.RealDelayProbe.Outcome.Delay }) tracker.start(com.example.vpn.smart.SearchStage.SAME_SERVER)
                    }
                },
                memory = networkMemory.variants(network),
                firstTimeoutSec = timeouts.firstSec,
                alternateTimeoutSec = timeouts.alternateSec
            )
            // Stage A: the exact route that carried verified traffic on this network before. The chosen
            // config's own route first; with Smart Connect, then the best other remembered route. Each has
            // to carry a real request here now; a failure counts against it and the normal search goes on.
            if (!lockExit && !force) {
                val remembered = networkMemory.routes.forNetwork(routeNetwork)
                val ordered = remembered.filter { it.profileId == requestedProfile.id } +
                    if (raceFirst) remembered.filter { it.profileId != requestedProfile.id && it.profileId !in exclude }.take(1) else emptyList()
                if (ordered.isNotEmpty()) showForegroundNotification("Trying the route that worked here before...")
                if (ordered.isEmpty()) tracker.skip(com.example.vpn.smart.SearchStage.REMEMBERED_ROUTE, "No route remembered for this network")
                else tracker.start(com.example.vpn.smart.SearchStage.REMEMBERED_ROUTE)
                for (recipe in ordered.take(2)) {
                    if (raced) break
                    coroutineContext.ensureActive()
                    // An error (the name does not resolve right now) skips the route; only a config that changed,
                    // was deleted, or lost its alternate drops it.
                    val rebuilt = runCatching { replayRoute(recipe, network, if (recipe.profileId == requestedProfile.id) profile else null) }
                    val replay = rebuilt.getOrNull()
                    if (replay == null) {
                        if (rebuilt.isSuccess) networkMemory.routes.drop(routeNetwork, recipe.id)
                        continue
                    }
                    if (recentlyFailed(replay.profile)) continue
                    val outcome = com.example.vpn.Abandonable.run { probe(replay.profile, timeouts.firstSec) }
                    // A network change during the request makes its answer describe another network.
                    val still = runCatching { com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService) }.getOrNull() == routeNetwork
                    when {
                        !still -> break
                        outcome is com.example.xray.RealDelayProbe.Outcome.Delay -> {
                            requestedProfile = replay.owner
                            activeProfile = replay.owner
                            profile = replay.profile
                            raced = true
                            chosenVariant = recipe.variantKey
                            activeRecovery = replay.recovery
                            networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(replay.profile), outcome.latencyMs)
                            mutateState { it.copy(activeProfile = replay.owner, attemptedProfileId = replay.owner.id) }
                            XrayLogManager.i("SMART", "The route that worked on this network before carried traffic again " +
                                "(${recipe.kind}, ${outcome.latencyMs} ms).")
                        }
                        outcome is com.example.xray.RealDelayProbe.Outcome.Failed -> {
                            networkMemory.routes.recordFailure(routeNetwork, recipe.id)
                            networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(replay.profile))
                            XrayLogManager.i("SMART", "The route that worked here before carried no traffic now; searching.")
                        }
                        else -> Unit
                    }
                }
                if (ordered.isNotEmpty()) tracker.finish(com.example.vpn.smart.SearchStage.REMEMBERED_ROUTE, verified = raced)
            }

            // Recheck the exact successful engine recipe before scanning unrelated servers or registering WARP.
            if (!raced && raceFirst && !lockExit && !force) {
                val early = withTimeoutOrNull(com.example.vpn.smart.RecoveryAttemptPolicy.TOR_WINDOW_MS + 10_000L) {
                    com.example.RayApplication.instance.lab.fastRecovery(
                        exclude = exclude, deadlineMs = System.currentTimeMillis() + 90_000L,
                        engineWindow = { test -> engineWindow(test) }, mode = settings.operationalMode,
                        rememberedOnly = true
                    ) { showForegroundNotification("Rechecking the engine route that worked here...") }
                }?.result
                if (early != null) {
                    com.example.vless.VlessValidator.validate(early.profile)
                    com.example.vpn.engine.RuntimeCapabilities.requireSupported(early.profile)
                    requestedProfile = early.profile
                    activeProfile = early.profile
                    profile = early.profile
                    raced = true
                    mutateState { it.copy(activeProfile = early.profile, attemptedProfileId = early.profile.id) }
                    XrayLogManager.i("SMART", "Previously working engine route passed fresh traffic checks on this network.")
                }
            }

            var firstFailed = initialResolutionFailed
            if (raceFirst && !raced) {
                // The chosen server alone first, so a working one costs a single request; only when it
                // carries no traffic are the other servers raced (several kinds of connection per round).
                showForegroundNotification("Finding the fastest server...")
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.ORDINARY_PREFLIGHT)
                tracker.start(com.example.vpn.smart.SearchStage.SELECTED)
                val first = finder.firstPath(requestedProfile, profile)
                chosenVariant = first.variantKey
                val skip = recentlyFailed(first.profile)
                if (skip) XrayLogManager.i("SMART", "Selected server skipped: it failed a real request on this network moments ago.")
                when (val outcome = if (initialResolutionFailed) com.example.xray.RealDelayProbe.Outcome.Failed("selected server DNS resolution failed", request = false)
                    else if (skip) com.example.xray.RealDelayProbe.Outcome.Failed("failed moments ago on this network", request = false)
                    else com.example.vpn.Abandonable.run { probe(first.profile, timeouts.firstSec) }) {
                    is com.example.xray.RealDelayProbe.Outcome.Delay -> {
                        profile = first.profile
                        raced = true
                        networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(first.profile), outcome.latencyMs)
                        tracker.finish(com.example.vpn.smart.SearchStage.SELECTED, verified = true)
                    }
                    is com.example.xray.RealDelayProbe.Outcome.Failed -> {
                        firstFailed = true
                        provenDead = true
                        networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(first.profile))
                        // A skipped retest is a result too: it failed a real request here moments ago.
                        if (skip) tracker.count(carried = false)
                        tracker.finish(com.example.vpn.smart.SearchStage.SELECTED, verified = false)
                        ladder.advance(com.example.vpn.connectivity.RecoveryStage.SAVED_PROFILE_RACE)
                        tracker.start(com.example.vpn.smart.SearchStage.OTHER_SERVERS)
                        raceServers(exclude + requestedProfile.id, network, timeouts.alternateSec, finder, retryDisguised = true)?.let { adopt(it) }
                        tracker.finish(com.example.vpn.smart.SearchStage.OTHER_SERVERS, verified = raced)
                    }
                    is com.example.xray.RealDelayProbe.Outcome.NotRun -> tracker.finish(com.example.vpn.smart.SearchStage.SELECTED, verified = false,
                        note = "The path test could not run")
                }
            }

            // 3a. A recovery candidate that carried traffic for this config on this network before (FIX BPB
            // or earlier recovery): a derived copy, the saved config itself is unchanged. One request decides;
            // a failure counts towards its rollback and the normal path search continues.
            if (!raced && !lockExit) {
                val recovery = runCatching { com.example.RayApplication.instance.bpbRecovery.active(requestedProfile, network, base = profile) }.getOrNull()
                if (recovery != null) {
                    showForegroundNotification("Trying the recovered settings...")
                    tracker.start(com.example.vpn.smart.SearchStage.SAME_SERVER)
                    when (val outcome = com.example.vpn.Abandonable.run { probe(recovery.profile, timeouts.firstSec) }) {
                        is com.example.xray.RealDelayProbe.Outcome.Delay -> {
                            profile = recovery.profile
                            raced = true
                            activeRecovery = recovery
                            com.example.RayApplication.instance.bpbRecovery.recordOutcome(recovery, success = true)
                            XrayLogManager.i("RECOVERY", "Recovered settings ${recovery.recoveryProfileKey} carried traffic (${outcome.latencyMs} ms).")
                        }
                        is com.example.xray.RealDelayProbe.Outcome.Failed -> {
                            com.example.RayApplication.instance.bpbRecovery.recordOutcome(recovery, success = false)
                            XrayLogManager.w("RECOVERY", "Recovered settings ${recovery.recoveryProfileKey} carried no traffic; trying other paths.")
                        }
                        is com.example.xray.RealDelayProbe.Outcome.NotRun -> Unit
                    }
                    tracker.finish(com.example.vpn.smart.SearchStage.SAME_SERVER, verified = raced)
                }
            }

            // 3b. Find a path that carries traffic before the core starts: the saved profile, its stealth
            // alternates (split handshake, other fingerprint, ECH, UDP junk) or another kind on the same
            // server. The probe's sockets bypass the traffic-blocking interface (RealDelayProbe.socketProtector).
            if (!raced && !lockExit && com.example.vpn.stealth.StealthVariants.of(profile).isNotEmpty()) {
                showForegroundNotification("Finding a working route...")
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.SAFE_VARIANTS)
                // Without Smart Connect the finder's first test is the selected server itself (stage B).
                tracker.start(if (firstFailed) com.example.vpn.smart.SearchStage.SAME_SERVER else com.example.vpn.smart.SearchStage.SELECTED)
                val resolvedRequested = profile
                val siblings = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                val choice = com.example.vpn.Abandonable.run { finder.choose(
                    requested = requestedProfile,
                    resolve = { p ->
                        if (p.id == requestedProfile.id) resolvedRequested
                        else if (p.profileType != ProfileType.XRAY_JSON && !com.example.vpn.sidecar.Sidecars.isEngineProfile(p) &&
                            !isLiteralIp(p.address)) resolveEndpoint(p) else p
                    },
                    siblings = siblings,
                    firstFailed = firstFailed
                ) }
                if (choice.owner.id != requestedProfile.id) {
                    com.example.vless.VlessValidator.validate(choice.profile)
                    com.example.vpn.engine.RuntimeCapabilities.requireSupported(choice.profile)
                    requestedProfile = choice.owner
                    activeProfile = choice.owner
                    mutateState { it.copy(activeProfile = choice.owner, attemptedProfileId = choice.owner.id) }
                }
                profile = choice.profile
                chosenVariant = choice.variantKey
                choice.latencyMs?.let {
                    raced = true
                    networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(choice.profile), it)
                }
                if (choice.nothingWorked) provenDead = true
                tracker.finishRunning(verified = choice.latencyMs != null)
                // Nothing on this server carried traffic: try the other saved servers now rather than
                // starting a dead connection and waiting for the watchdog.
                if (choice.nothingWorked) networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(requestedProfile))
                if (choice.nothingWorked && !raceFirst && settings.autoFailoverEnabled) {
                    showForegroundNotification("Server not answering, trying others...")
                    tracker.start(com.example.vpn.smart.SearchStage.OTHER_SERVERS)
                    raceServers(exclude + requestedProfile.id + userProfile.id, network, timeouts.alternateSec, finder, retryDisguised = false)
                        ?.let { adopt(it) }
                    tracker.finish(com.example.vpn.smart.SearchStage.OTHER_SERVERS, verified = raced)
                }
                if (choice.nothingWorked && !raced) tracker.start(com.example.vpn.smart.SearchStage.EMERGENCY)
                // Nothing worked, and the server sits behind Cloudflare: its address may be blocked
                // while other Cloudflare addresses are not. Scan for one and try the same server there.
                if (choice.nothingWorked && !raced && plan.allows(com.example.vpn.smart.EmergencyTransport.CLEAN_IP)) {
                    cleanIpPath(requestedProfile, timeouts.alternateSec)?.let { adopt(it) }
                }
                // Sidecar engines must carry requests before selection. The shared recovery pool below
                // tests MASQUE, Psiphon, saved Tor bridges and chains serially in DAILY and GOD MODE;
                // choosing several untested engines here used to overwrite the earlier choices with Tor.
                if (choice.nothingWorked) tracker.finish(com.example.vpn.smart.SearchStage.EMERGENCY, verified = raced)
            }

            // Always a pre-flight: a server that was not tested above (no Smart Connect, no stealth variants)
            // gets one real request now, so a dead server is never started blindly.
            // The power button skips it: the user's config is started as chosen and checked after start.
            if (!raced && !provenDead && !force && !exact && !com.example.vpn.sidecar.Sidecars.isEngineProfile(profile)) {
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.ORDINARY_PREFLIGHT)
                tracker.start(com.example.vpn.smart.SearchStage.SELECTED)
                if (recentlyFailed(profile)) {
                    provenDead = true
                    XrayLogManager.i("SMART", "Selected server not retried: it failed a real request on this network moments ago.")
                } else {
                    showForegroundNotification("Testing ${profile.name}...")
                    when (val outcome = com.example.vpn.Abandonable.run { probe(profile, timeouts.firstSec) }) {
                        is com.example.xray.RealDelayProbe.Outcome.Delay -> {
                            raced = true
                            networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(profile), outcome.latencyMs)
                        }
                        is com.example.xray.RealDelayProbe.Outcome.Failed -> {
                            provenDead = true
                            networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(profile))
                        }
                        is com.example.xray.RealDelayProbe.Outcome.NotRun -> Unit
                    }
                }
                tracker.finish(com.example.vpn.smart.SearchStage.SELECTED, verified = raced)
            }

            // A Stop pressed during the earlier probes unwinds here, before the long recovery stages begin,
            // so the connection attempt can always be cancelled rather than running on for minutes.
            coroutineContext.ensureActive()

            if (!raced && provenDead && !force && !lockExit && (raceFirst || settings.autoFailoverEnabled) &&
                plan.allows(com.example.vpn.smart.EmergencyTransport.WARP)) {
                tracker.start(com.example.vpn.smart.SearchStage.EMERGENCY)
                warpPath(timeouts.alternateSec)?.let { adopt(it) }
                tracker.finish(com.example.vpn.smart.SearchStage.EMERGENCY, verified = raced)
            }

            // Escalation before giving up: the LAB's fast recovery (Connect goal) over every other saved family
            // and the built-in recovery engines, stopping at the first path that carries a real request.
            var recoveryExhausted = false
            if (!raced && provenDead && !force && !lockExit && (raceFirst || settings.autoFailoverEnabled)) {
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.RECOVERY_ENGINES)
                showForegroundNotification("No server answered; trying recovery methods...")
                tracker.start(com.example.vpn.smart.SearchStage.RECOVERY)
                val skipRefs = setOf(com.example.vpn.connectivity.PathRef.of(requestedProfile), com.example.vpn.connectivity.PathRef.of(userProfile))
                val deadline = System.currentTimeMillis() + FAST_RECOVERY_MS
                val outcome = runCatching {
                    withTimeoutOrNull(FAST_RECOVERY_MS + com.example.vpn.smart.RecoveryAttemptPolicy.TOR_WINDOW_MS + 10_000L) {
                        com.example.RayApplication.instance.lab.fastRecovery(skipRefs + exclude + requestedProfile.id + userProfile.id, deadline,
                            engineWindow = { test -> engineWindow(test) }, mode = settings.operationalMode,
                            resolveForRecovery = { p -> if (isLiteralIp(p.address)) p else resolveEndpoint(p) }) { step -> showForegroundNotification(step) }
                    }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    XrayLogManager.w("SMART", "Recovery search stopped: ${it.message}")
                }.getOrElse {
                    com.example.vpn.lab.LabController.FastRecoveryOutcome(null, 0, false,
                        "recovery interrupted by ${it.javaClass.simpleName}")
                } ?: com.example.vpn.lab.LabController.FastRecoveryOutcome(null, 0, false,
                    "recovery time limit reached; unfinished engines were stopped")
                val found = outcome?.result
                outcome?.let { tracker.add(com.example.vpn.smart.SearchStage.RECOVERY, tested = it.tested, verified = if (found != null) 1 else 0) }
                tracker.finish(com.example.vpn.smart.SearchStage.RECOVERY, verified = found != null, note = outcome?.why)
                if (found != null) {
                    var chosen = found.profile
                    if (!found.engine && chosen.profileType != ProfileType.XRAY_JSON && !isLiteralIp(chosen.address)) chosen = resolveEndpoint(chosen)
                    com.example.vless.VlessValidator.validate(chosen)
                    com.example.vpn.engine.RuntimeCapabilities.requireSupported(chosen)
                    requestedProfile = found.profile
                    activeProfile = found.profile
                    profile = chosen
                    raced = true
                    ladder.advance(com.example.vpn.connectivity.RecoveryStage.FIRST_VERIFIED_PATH)
                    mutateState { it.copy(activeProfile = found.profile, attemptedProfileId = found.profile.id) }
                    XrayLogManager.i("SMART", "Recovery found ${found.family.title} (${com.example.core.SecretRedactor.redact(found.profile.name)}) " +
                        "after ${found.tested} test(s); it carried a real request.")
                } else {
                    recoveryExhausted = outcome?.exhausted == true
                    XrayLogManager.w("SMART", "Recovery found no path: ${outcome.why}.")
                }
            }

            // Last resort for the agent: when every saved server and recovery engine failed, revive the
            // dead Cloudflare-fronted configs (ECH, fragment, fingerprint, clean IP) and connect the one
            // that comes back. Bounded in time; never in Trade (its exit is pinned). The winning recipe is
            // saved to the ledger, so even a cut-short run helps the next connect.
            coroutineContext.ensureActive()
            if (!raced && provenDead && !force && !lockExit && recoveryExhausted &&
                (raceFirst || settings.autoFailoverEnabled)) {
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.RECOVERY_ENGINES)
                showForegroundNotification("Reviving configurations...")
                tracker.start(com.example.vpn.smart.SearchStage.REVIVAL)
                mutateState { if (it.attemptId == attemptId) it.copy(reviving = true) else it }
                val reviveDeadline = System.currentTimeMillis() + CONFIG_REVIVE_MS
                val attemptJob = coroutineContext[Job]
                val saved = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                val report = com.example.vpn.Abandonable.run {
                    runCatching {
                        com.example.RayApplication.instance.configRevival.run(
                            // Stops on its own deadline, and also the moment the user presses Stop (the attempt is cancelled).
                            saved, network, cancelled = { System.currentTimeMillis() > reviveDeadline || attemptJob?.isActive == false }
                        ) { p -> if (attemptJob?.isActive != false) p.current?.let { showForegroundNotification(it) } }
                    }.onFailure { XrayLogManager.w("SMART", "Config revival stopped: ${com.example.core.SecretRedactor.redact(it.message.orEmpty())}") }.getOrNull()
                }
                report?.results?.let { rs ->
                    val ok = setOf(com.example.vpn.lab.ConfigRevival.Outcome.REVIVED, com.example.vpn.lab.ConfigRevival.Outcome.ALREADY_WORKING)
                    tracker.add(com.example.vpn.smart.SearchStage.REVIVAL,
                        tested = rs.map { r -> if (r.outcome == com.example.vpn.lab.ConfigRevival.Outcome.ALREADY_WORKING) 1 else r.tried }.sum(),
                        verified = rs.count { it.outcome in ok })
                }
                // Results come back revived-first; take the fastest config that was brought back to life.
                val revived = report?.results
                    ?.filter { it.outcome == com.example.vpn.lab.ConfigRevival.Outcome.REVIVED }
                    ?.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
                val owner = revived?.let { r -> saved.firstOrNull { it.id == r.profileId } }
                val recovery = owner?.let {
                    runCatching { com.example.RayApplication.instance.bpbRecovery.active(it, network, base = it) }.getOrNull()
                }
                if (owner != null && recovery != null) {
                    // The revived copy still has to carry a real request here before we start the tunnel on
                    // it (same no-blind-connection rule as every other tier); a bad copy just falls through.
                    val chosen = runCatching {
                        var c = recovery.profile
                        if (c.profileType != ProfileType.XRAY_JSON && !com.example.vpn.sidecar.Sidecars.isEngineProfile(c) &&
                            !isLiteralIp(c.address)) c = resolveEndpoint(c)
                        com.example.vless.VlessValidator.validate(c)
                        com.example.vpn.engine.RuntimeCapabilities.requireSupported(c)
                        c
                    }.getOrNull()
                    val verified = chosen != null &&
                        // Not counted again: revival's own tests above already count this config.
                        com.example.vpn.Abandonable.run { com.example.xray.RealDelayProbe.measure(chosen, timeouts.firstSec) } is com.example.xray.RealDelayProbe.Outcome.Delay
                    if (verified && chosen != null) {
                        requestedProfile = owner
                        activeProfile = owner
                        profile = chosen
                        raced = true
                        activeRecovery = recovery
                        com.example.RayApplication.instance.bpbRecovery.recordOutcome(recovery, success = true)
                        ladder.advance(com.example.vpn.connectivity.RecoveryStage.FIRST_VERIFIED_PATH)
                        mutateState { it.copy(activeProfile = owner, attemptedProfileId = owner.id) }
                        XrayLogManager.i("SMART", "Config revival brought back ${com.example.core.SecretRedactor.redact(owner.name)} " +
                            "(${revived.recipe ?: "recipe"}); its recovered settings carried traffic.")
                    } else {
                        runCatching { com.example.RayApplication.instance.bpbRecovery.recordOutcome(recovery, success = false) }
                        XrayLogManager.w("SMART", "Config revival produced a candidate that carried no traffic; going on.")
                    }
                } else if (report != null) {
                    XrayLogManager.w("SMART", "Config revival brought nothing back (${report.results.size} eligible).")
                }
                tracker.finish(com.example.vpn.smart.SearchStage.REVIVAL, verified = raced)
                mutateState { if (it.attemptId == attemptId) it.copy(reviving = false) else it }
            }
            tracker.end()
            com.example.vpn.diagnostics.events.EventLog.event("connect.search", attributes = tracker.progress().stages.associate { r ->
                "stage.${r.stage.letter}" to "${r.outcome.name.lowercase()} ${r.tested}/${r.verified}"
            })

            // No blind connections: a server whose real request just failed here, with nothing verified in
            // its place, is not started (PathGate). The user can still choose "Connect anyway".
            val gate = com.example.vpn.smart.PathGate.decide(verified = raced, provenDead = provenDead,
                engineAdopted = false, forced = force || exact)
            if (!com.example.vpn.smart.PathGate.mayStart(gate)) {
                val name = com.example.core.SecretRedactor.redact(requestedProfile.name)
                XrayLogManager.w("SMART", "No verified path: '$name' carried no real traffic on this network and no alternative did. " +
                    "The tunnel was not started with it.")
                // Same rule as an unusable profile: a user's DAILY connect releases the block; an automatic
                // connect or GOD MODE keeps traffic blocked until the user disconnects.
                val release = com.example.vpn.safety.FailClosedPolicy.mayReleaseBlock(
                    com.example.vpn.safety.FailClosedPolicy.Failure.INVALID_PROFILE, startedByUser, settingsRepository.getSettings().operationalMode)
                if (release) supervisor.release(com.example.vpn.safety.MaximusVpnSupervisor.Release.INVALID_PROFILE_BY_USER)
                disconnectResources()
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.NO_VERIFIED_EGRESS_AFTER_RECOVERY)
                updateState(fresh(
                    ConnectionStatus.FAILED,
                    (if (recoveryExhausted) "No verified egress after recovery: $name and every available method were tested on this network and none carried traffic. Other servers or methods may still work."
                    else "No verified path: $name carried no traffic on this network, and no other server or method did.") +
                        if (release) "" else " Traffic stays blocked; disconnect to use the network without the VPN.",
                    com.example.vpn.diagnostics.FailureStage.HTTP_REQUEST_FAILED
                ).copy(canConnectAnyway = true, recoveryStage = ladder.stage.title, recoveryExhausted = recoveryExhausted))
                if (release) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    showForegroundNotification("Traffic blocked: no server carried traffic")
                }
                return@withContext
            }
            if (gate == com.example.vpn.smart.PathGate.Decision.FORCED) {
                XrayLogManager.w("SMART", "Connect anyway: starting '${com.example.core.SecretRedactor.redact(requestedProfile.name)}' although it failed its real test.")
            }

            // A WireGuard MTU the LAB committed for exactly this network, engine, transport and address family.
            if (profile.protocolType == com.example.data.model.ProtocolType.WIREGUARD && !com.example.vpn.sidecar.Sidecars.isEngineProfile(profile)) {
                val key = book.session()?.networkKey?.let { net ->
                    com.example.vpn.lab.MtuIntelligence.Key(net, "xray", "wireguard", if (profile.address.contains(':')) "ipv6" else "ipv4")
                }
                key?.let { com.example.vpn.lab.MtuIntelligence.cache.working(it, System.currentTimeMillis()) }
                    ?.takeIf { it != com.example.vpn.lab.MtuIntelligence.wireGuardMtu(profile) }
                    ?.let { mtu ->
                        XrayLogManager.i("SMART", "WireGuard MTU $mtu (verified on this network) is used instead of ${com.example.vpn.lab.MtuIntelligence.wireGuardMtu(profile)}.")
                        profile = com.example.vpn.lab.MtuIntelligence.withWireGuardMtu(profile, mtu).copy(id = profile.id)
                        activeMtuKey = key
                    }
            }

            // 4. Diagnostic step 4 & 5: Configure and establish Android VpnService TUN interface
            val configuredMtu = settings.mtu.coerceIn(1280, 1500)
            // A bulk transfer stalled on this network last time while small requests passed: this connect
            // tests the lower MTU (TunMtuTrial); its own bulk check decides whether it stays.
            val safeMtu = networkMemory.mtu.mtuFor(routeNetwork, configuredMtu)
            if (safeMtu != configuredMtu) XrayLogManager.i("VPN", "Tunnel MTU $safeMtu on this network instead of $configuredMtu: " +
                "a bulk transfer stalled here before (testing whether MTU is the cause).")
            tunMtu = Triple(routeNetwork, safeMtu, configuredMtu)
            val primaryDns = com.example.vpn.safety.VpnRoutePolicy.DNS_SERVER
            val builder = Builder()
                .setSession("Maximus - ${profile.name}")
                .setMtu(safeMtu)
                .setBlocking(true)
                .addDnsServer(primaryDns)
            // IPv4 and IPv6 are always captured (VpnRoutePolicy); unsupported engines drop IPv6 inside the TUN.
            com.example.vpn.safety.VpnRoutePolicy.ADDRESSES.forEach { builder.addAddress(it.address, it.prefix) }
            com.example.vpn.safety.VpnRoutePolicy.ROUTES.forEach { builder.addRoute(it.address, it.prefix) }
            val sidecarEngine = com.example.vpn.sidecar.Sidecars.forProfile(profile)
            val engineChain = com.example.vpn.sidecar.ChainRunner.readChain(profile)
            if (sidecarEngine != null || engineChain != null) {
                // A separate engine program cannot ask Android to keep its own sockets out of the VPN,
                // so the app's own traffic is left out; every other app still goes through the tunnel.
                builder.addDisallowedApplication(packageName)
            }

            XrayLogManager.i("VPN", "[DIAGNOSTICS] 4. Builder.establish() invoked (MTU=$safeMtu, Address=172.19.0.1/30, DNS=$primaryDns).")
            val pfd = try {
                builder.establish()
            } catch (e: Exception) {
                XrayLogManager.e("VPN", "builder.establish() threw exception: ${e.javaClass.simpleName}: ${e.message}", e)
                null
            }

            if (pfd == null) {
                val err = "VpnService.Builder.establish() returned null. Android VPN permission may be revoked or another VPN is active."
                XrayLogManager.e("VPN", "[DIAGNOSTICS] 5. Builder.establish() returned null.")
                XrayLogManager.e("VPN", err)
                updateState(_vpnState.value.copy(
                    status = ConnectionStatus.FAILED,
                    errorMessage = "VPN interface establishment failed (permission revoked or another VPN active).",
                    failureStage = com.example.vpn.diagnostics.FailureStage.TUN_ESTABLISH_FAILED
                ))
                disconnectResources()
                if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
                if (!protectionRequested) stopSelf()
                return@withContext
            }

            val previousInterface = vpnInterface
            vpnInterface = pfd
            previousInterface?.close()
            if (!coroutineContext.isActive) {
                // Keep the newly established capture interface during cancelled connects.
                disconnectResources()
                return@withContext
            }

            XrayLogManager.i("VPN", "[DIAGNOSTICS] 5. Builder.establish() returned non-null ParcelFileDescriptor.")
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 6. TUN file descriptor number: #${pfd.fd}.")

            Socket().use { probe ->
                check(safeProtectSocket(probe)) {
                    "Android rejected VPN socket protection; VPN forwarding cannot start. Check VPN permission."
                }
            }
            XrayLogManager.i("VPN", "[DIAGNOSTICS] Upstream TCP socket protection probe succeeded.")

            // 6. Update Connection State to VPN_INTERFACE_ESTABLISHED
            serviceTxBytes.set(0)
            serviceRxBytes.set(0)
            updateState(_vpnState.value.copy(
                status = ConnectionStatus.VPN_INTERFACE_ESTABLISHED,
                activeProfile = requestedProfile,
                vpnIp = "172.19.0.1",
                errorMessage = null,
                uploadBytes = 0,
                downloadBytes = 0,
                uploadSpeedBps = 0,
                downloadSpeedBps = 0
            ))
            showForegroundNotification("Android VPN Active (Establishing Proxy...)")

            // 7. Diagnostic step 7: Select and start active engine
            // Plain VLESS is unsupported by current native Xray on public endpoints; Kotlin carries it
            // (and HTTP/SOCKS) with selected DoH using verified TLS. Everything else runs on Xray.
            if (settings.preferredEngine == EngineType.MIHOMO) {
                XrayLogManager.w("VPN", "The Mihomo engine has no native core in this build; using Xray instead.")
            }
            if (reuseSidecar != null) {
                check(sidecar?.isAlive == true && sidecarNetwork == ledgerNetwork()) {
                    "Preserved engine stopped or network changed during mode switch"
                }
                profile = reuseSidecar
            } else if (engineChain != null) profile = startChain(engineChain, settings)
            else if (sidecarEngine != null) profile = startSidecar(sidecarEngine, profile, settings)
            engineEndpoint = if (sidecarEngine?.id == com.example.vpn.sidecar.ByedpiSidecar.ID)
                com.example.vpn.sidecar.ByedpiSidecar.healthEndpoint(profile)
                else if (engineChain != null || sidecarEngine != null) com.example.vpn.verify.Socks5.endpointOf(profile) else null
            sidecarForwardProfile = profile.takeIf { sidecar != null }
            sidecarNetwork = ledgerNetwork().takeIf { sidecar != null }
            sidecarOwnerFingerprint = requestedProfile.effectiveFingerprint.takeIf { sidecar != null }
            val runtime = EngineSelectionPolicy.select(profile)
            val engineId = com.example.vpn.engine.registry.EngineRegistry.descriptorFor(runtime).id
            if (!engineBreaker.allows(engineId)) {
                // Each profile has one engine today, so this only reports; with a second engine for
                // the same protocol the selection will skip an open breaker.
                XrayLogManager.w("VPN", "Engine $engineId failed to start several times in a row; trying it again.")
            }
            activeEngine = when (runtime) {
                EngineSelectionPolicy.Runtime.KOTLIN_TUNNEL -> com.example.vpn.engine.KotlinTunnelEngine.instance
                EngineSelectionPolicy.Runtime.XRAY -> XrayEngineImpl.instance
            }

            updateState(_vpnState.value.copy(activeEngineName = activeEngine.engineVersion))

            updateState(_vpnState.value.copy(status = ConnectionStatus.PROXY_CONNECTING))
            showForegroundNotification("Connecting Proxy via ${activeEngine.engineType.displayName}...")

            val startResult = if (activeEngine is NativeTunVpnEngine) {
                (activeEngine as NativeTunVpnEngine).startWithTun(profile, settings.copy(mtu = safeMtu), pfd.fd) { fd -> safeProtectFd(fd) }
            } else {
                activeEngine.start(
                    profile = profile,
                    settings = settings,
                    protectSocket = { socket: Socket -> safeProtectSocket(socket) },
                    protectDatagram = { dSocket: DatagramSocket -> safeProtectDatagram(dSocket) }
                )
            }
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 7. Proxy engine start result: $startResult.")
            if (startResult is com.example.core.AppResult.Error) {
                engineBreaker.recordFailure(engineId)
                throw EngineStartException(startResult.exception)
            }
            engineBreaker.recordSuccess(engineId)
            mutateState { if (it.attemptId == attemptId) it.copy(status = ConnectionStatus.ENGINE_STARTED) else it }

            if (!coroutineContext.isActive) {
                disconnectResources()
                return@withContext
            }

            // Native Xray owns the TUN fd directly; the Kotlin packet loop must not read it concurrently.
            if (activeEngine !is NativeTunVpnEngine) tunnelManager = TunnelManager(
                vpnInterface = pfd,
                profile = profile,
                settings = settings.copy(mtu = safeMtu),
                protectSocket = { socket -> safeProtectSocket(socket) },
                protectDatagram = { datagramSocket -> safeProtectDatagram(datagramSocket) },
                onTraffic = { sent, received ->
                    if (sent > 0) serviceTxBytes.addAndGet(sent)
                    if (received > 0) serviceRxBytes.addAndGet(received)
                    activeEngine.recordTraffic(sent, received)
                },
                onFatalTunnelFailure = { cause ->
                    XrayLogManager.e("VPN", "Fatal TUN failure: ${cause.message}", cause)
                    serviceScope.launch {
                        connectionMutex.withLock {
                            updateState(_vpnState.value.copy(
                                status = ConnectionStatus.FAILED,
                                errorMessage = "TUN packet loop terminated: ${cause.message}"
                            ))
                            disconnectResources()
                            if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
                            if (!protectionRequested) stopSelf()
                        }
                    }
                },
                onTunnelError = { reason ->
                    if (reason.contains("socket protection failed", ignoreCase = true)) {
                        if (protectionFailureHandled.compareAndSet(false, true)) {
                            serviceScope.launch {
                                connectionMutex.withLock {
                                    XrayLogManager.e("VPN", "Android rejected upstream socket protection. Closing VPN instead of switching servers.")
                                    updateState(_vpnState.value.copy(
                                        status = ConnectionStatus.FAILED,
                                        errorMessage = "Android rejected upstream socket protection. Re-enable VPN permission and reconnect."
                                    ))
                                    disconnectResources()
                                    if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
                                    if (!protectionRequested) stopSelf()
                                }
                            }
                        }
                    } else {
                        failoverManager?.reportTunnelError(reason)
                    }
                }
            )
            tunnelManager?.start()
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 8. ${if (activeEngine is NativeTunVpnEngine) "Native Xray TUN loop started" else "TUN packet loop started"}.")

            if (!coroutineContext.isActive) {
                disconnectResources()
                return@withContext
            }

            XrayLogManager.i("VPN", "[DIAGNOSTICS] 9. Upstream socket protection probe passed.")

            // 10. Only real traffic through the tunnel makes the connection CONNECTED. Until a request
            // goes through, the state says the tunnel is up but unverified.
            val startTime = System.currentTimeMillis()
            mutateState {
                if (it.attemptId == attemptId) it.copy(status = ConnectionStatus.VERIFYING, vpnIp = "172.19.0.1", errorMessage = null,
                    revived = activeRecovery != null) else it
            }
            showForegroundNotification("Verifying traffic through ${profile.name}...")
            val check = verifyTraffic()
            book.recordPath(com.example.vpn.connectivity.EvidenceSource.SMART_CONNECT, com.example.vpn.connectivity.MeasurementType.TUNNEL_TRAFFIC,
                com.example.vpn.connectivity.PathRef.of(profile), com.example.vpn.stealth.ConnectionKind.of(profile), check.first != null,
                check.first, check.second?.name)
            if (check.first != null) ladder.advance(com.example.vpn.connectivity.RecoveryStage.CONNECTED_VERIFIED)
            else activeMtuKey?.let { com.example.vpn.lab.MtuIntelligence.cache.failed(it); activeMtuKey = null }
            // Every engine on the path shares the verdict: the request crossed all of them, or none is proven.
            val pathEngines = listOf(engineId) + (engineChain?.hops?.map { it.engineId } ?: listOfNotNull(sidecarEngine?.id))
            pathEngines.forEach { id ->
                val ledger = com.example.vpn.engine.registry.EngineLedger.shared
                if (check.first != null) ledger.verified(id, routeNetwork)
                // Only a lone engine owns a failure; in a chain or behind Xray it may be another part's.
                else if (pathEngines.size == 1) ledger.failed(id, routeNetwork, check.second?.name)
            }
            mutateState {
                com.example.data.model.ConnectionVerification.afterCheck(it, attemptId, check.first != null, check.first, System.currentTimeMillis(), check.second)
            }
            if (_vpnState.value.isConnected) {
                showForegroundNotification("Connected to ${profile.name}")
                XrayLogManager.i("VPN", "[DIAGNOSTICS] 10. Traffic verified through the tunnel (${check.first} ms). Final state: CONNECTED.")
                val connectMs = System.currentTimeMillis() - connectStartedAt
                com.example.vpn.diagnostics.ConnectionMetrics.recordConnectTime(connectMs)
                XrayLogManager.i("VPN", "Time from connect to verified traffic: $connectMs ms.")
                onTrafficVerified(requestedProfile, check.first)
                activeRoute = recordRoute(routeNetwork, requestedProfile, profile, chosenVariant, check.first ?: 0L)
                if (com.example.vpn.sidecar.Sidecars.isEngineProfile(requestedProfile) &&
                    com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService) == routeNetwork) {
                    com.example.vpn.smart.EngineRouteHints.record(this@RayVpnService, routeNetwork, requestedProfile)
                }
                launchTrafficCheck()
            } else {
                val stage = check.second ?: com.example.vpn.diagnostics.FailureStage.UNKNOWN
                showForegroundNotification("Tunnel up, no traffic yet (${stage.name})")
                XrayLogManager.w("VPN", "[DIAGNOSTICS] 10. Tunnel started but no request went through it (${stage.name}). " +
                    "State: TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED; checks continue and failover may switch servers.")
                recordFreeOutcome(requestedProfile, success = false, rttMs = null, stage = stage)
            }

            // Refresh due subscriptions through the new tunnel: their addresses may be blocked outside it.
            serviceScope.launch {
                kotlinx.coroutines.delay(SUBSCRIPTION_REFRESH_DELAY_MS)
                if (_vpnState.value.status != ConnectionStatus.CONNECTED || _vpnState.value.attemptId != attemptId) return@launch
                runCatching { com.example.RayApplication.instance.subscriptionManager.refreshDue() }
                    .onSuccess { results ->
                        val restored = results.filter { it.isSuccess }.sumOf { it.addedCount }
                        if (results.isNotEmpty()) XrayLogManager.i("SUBSCRIPTION", "Refreshed ${results.count { it.isSuccess }} of ${results.size} due subscriptions through the tunnel; $restored new servers.")
                    }
                    .onFailure { XrayLogManager.w("SUBSCRIPTION", "Refresh after connect failed: ${it.message}") }
            }

            if (policy.mode == com.example.data.model.OperationalMode.GOD_MODE) {
                XrayLogManager.i("GOD_MODE", "Everything through the proxy, IPv6 blocked, server names over DNS-over-HTTPS only, " +
                    "every server tested, failover on; traffic stays blocked when nothing works.")
            }

            // Start Failover Manager & Watchdogs
            failoverManager?.stopMonitoring()
            failoverManager = com.example.vpn.smart.FailoverManager(
                serverRepository = serverRepository,
                protectSocket = { socket -> safeProtectSocket(socket) },
                tunnelProbe = { routeLatency() },
                // Only the native core's own sockets carry the tunnel's data; other engines are always checked.
                passive = if (activeEngine is NativeTunVpnEngine) com.example.vpn.smart.PassiveHealth() else null,
                staysOnSelected = { exactSession },
                onTriggerSwitch = onTriggerSwitch@{ newProfile, reason ->
                    if (exactSession) {
                        // Failover checks staysOnSelected first; this only catches a switch already under way.
                        return@onTriggerSwitch
                    }
                    // A recovered path that stopped carrying traffic counts towards its rollback.
                    activeRecovery?.let { com.example.RayApplication.instance.bpbRecovery.recordOutcome(it, success = false) }
                    activeRoute?.let { (net, id) -> networkMemory.routes.recordFailure(net, id) }
                    activeRoute = null
                    activeRecovery = null
                    val degraded = requestedProfile.id
                    // The kind that just stopped carrying traffic goes last in the race.
                    networkMemory.recordFailure(
                        com.example.vpn.smart.NetworkKey.current(this@RayVpnService),
                        com.example.vpn.stealth.ConnectionKind.of(profile)
                    )
                    val ticket = lifecycle.current()
                    if (!lifecycle.tryBeginSwitch()) {
                        XrayLogManager.i("FAILOVER", "A switch is already pending; this one is ignored ($reason).")
                    } else {
                        mutateState { com.example.data.model.ConnectionVerification.onSwitching(it) }
                        com.example.vpn.diagnostics.ConnectionMetrics.failoverSwitches.incrementAndGet()
                        // Through connectJob, so a Connect or Disconnect cancels it, and with the intent it began in,
                        // so it never brings the tunnel back after the user disconnected.
                        connectJob = serviceScope.launch {
                            try {
                                XrayLogManager.w("FAILOVER", "Executing auto-failover, '${newProfile.name}' first: $reason")
                                // Race the saved servers so the switch lands on one that carries traffic now.
                                connect(newProfile, smart = true, exclude = setOf(degraded), ticket = ticket)
                            } finally {
                                lifecycle.endSwitch()
                            }
                        }
                    }
                }
            ).apply {
                startMonitoring(profile, settings)
            }

            startDurationAndPingWatchers(profile, startTime)

        } catch (e: CancellationException) {
            disconnectResources()
            throw e
        } catch (e: Exception) {
            // An engine stopped by Stop/Disconnect fails its start; that is the cancel, not a connection failure.
            if (!coroutineContext.isActive) {
                disconnectResources()
                throw CancellationException("Connect attempt stopped")
            }
            XrayLogManager.e("VPN", "Fatal error establishing VPN connection: ${e.message}", e)
            updateState(_vpnState.value.copy(
                status = ConnectionStatus.FAILED,
                errorMessage = e.localizedMessage ?: "Unknown connection failure",
                failureStage = if (e is EngineStartException) com.example.vpn.diagnostics.FailureStage.ENGINE_START_FAILED
                    else com.example.vpn.diagnostics.FailureStage.of(e)
            ))
            disconnectResources()
            if (protectionRequested) showForegroundNotification("Traffic blocked: connection failed")
            if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
            if (!protectionRequested) stopSelf()
        }
    }

    private fun isLiteralIp(address: String): Boolean {
        val a = address.trim().removePrefix("[").removeSuffix("]")
        return a.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) ||
            (a.contains(':') && a.matches(Regex("[0-9a-fA-F:.]+")))
    }

    /**
     * Resolves a hostname endpoint (for example a workers.dev BPB address) on a physical network,
     * bypassing this VPN, and returns a copy that connects to the IP while keeping the hostname for
     * TLS SNI and the HTTP Host header.
     */
    @Suppress("DEPRECATION")
    private fun resolveEndpoint(profile: VlessProfile): VlessProfile {
        val hostName = profile.address.trim().removePrefix("[").removeSuffix("]")
        val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
        val networks = cm?.allNetworks.orEmpty().filter { network ->
            val caps = cm?.getNetworkCapabilities(network)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        var lastError: Exception? = null
        // What earlier real queries on this network showed: resolver order, names its DNS blocks.
        val dns = runCatching { com.example.RayApplication.instance.dnsResilience }.getOrNull()
        val networkKey = runCatching { com.example.vpn.smart.NetworkKey.current(this) }.getOrNull()
        val dnsProfile = dns?.profile(networkKey, EndpointResolver.DOH_ENDPOINTS)
        for (network in networks) {
            try {
                val resolved = EndpointResolver.resolve(
                    hostName,
                    system = { network.getAllByName(it).toList() },
                    open = { url -> network.openConnection(url) as java.net.HttpURLConnection },
                    private = privateServerLookup,
                    doh = dnsProfile?.resolverOrder ?: EndpointResolver.DOH_ENDPOINTS,
                    skipSystem = dnsProfile?.skipSystemFor(hostName) == true,
                    onOutcome = { resolver, outcome, ms -> dns?.record(networkKey, resolver, hostName, outcome, ms) }
                )
                dnsPoisoned = resolved.viaDoh ||
                    EndpointResolver.isBlockedAnswer(java.net.InetAddress.getByName(resolved.address))
                if (resolved.viaDoh) {
                    XrayLogManager.w("VPN", "[DIAGNOSTICS] The network's DNS gave a blocked answer for the server; " +
                        "resolved it over DNS-over-HTTPS instead")
                }
                val usesTls = profile.security.equals("tls", true) || profile.security.equals("reality", true)
                if (!resolved.viaDoh && EndpointResolver.isBlockedAnswer(java.net.InetAddress.getByName(resolved.address))) {
                    XrayLogManager.w("VPN", "[DIAGNOSTICS] The server name resolves to a private address, " +
                        "which filtering networks use for blocked sites; DNS-over-HTTPS was unreachable")
                    // Without TLS the login (UUID, password) would go to whoever holds that address.
                    check(usesTls) { "the network's DNS points the server at a filtering address" }
                }
                return com.example.vpn.smart.RouteRecipe.pinEndpoint(profile, resolved.address)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            "cannot resolve the server address '$hostName'" +
                ((lastError?.cause ?: lastError)?.message?.let { " ($it)" } ?: if (networks.isEmpty()) " (no internet connection)" else "")
        )
    }

    /**
     * Starts the engine program for [profile] and returns the profile Xray runs instead: a SOCKS5
     * outbound to that program. If the program dies later, Xray's proxy stops answering and traffic
     * stays blocked until failover picks another path.
     */
    private fun startSidecar(engine: com.example.vpn.sidecar.SidecarEngine, profile: VlessProfile, settings: AppSettings): VlessProfile {
        sidecar?.stop()
        sidecar = null
        // The recovery test just ran this engine and it carried real requests: take it over as it is.
        com.example.vpn.sidecar.EngineHandoff.take(profile.id, engine.id, settings.operationalMode)?.let { kept ->
            sidecar = kept.engine
            XrayLogManager.i("VPN", "Engine ${engine.id} kept running from the recovery test; no second start-up")
            watchSidecar(engine, kept.engine)
            return com.example.vpn.sidecar.SidecarChain.xrayProfile(profile, kept.context, kept.launch)
        }
        val executable = com.example.vpn.sidecar.Sidecars.executable(engine)
            ?: error("The ${engine.id} engine is not included in this build for this phone")
        val workDir = java.io.File(noBackupFilesDir, "engines/${engine.id}").apply { mkdirs() }
        val context = com.example.vpn.sidecar.SidecarContext(
            workDir = workDir,
            executable = executable,
            socksPort = com.example.vpn.sidecar.Sidecars.freeLoopbackPort(),
            socksUser = com.example.vpn.sidecar.Sidecars.randomToken(9),
            socksPass = com.example.vpn.sidecar.Sidecars.randomToken(),
            mode = settings.operationalMode
        )
        val launch = engine.prepare(profile, settings, context)
        showForegroundNotification("Starting the ${engine.id} engine...")
        val process = com.example.vpn.sidecar.EngineReaper.track(engine.startInApp(launch, context)
            ?: com.example.vpn.sidecar.SidecarProcess.start(engine.id, launch, workDir, context.socksPort))
        sidecar = process
        if (!process.awaitReady(launch.readyTimeoutMs)) {
            process.stop()
            sidecar = null
            com.example.vpn.engine.registry.EngineLedger.shared.failed(engine.id, ledgerNetwork(), "did not start")
            error("The ${engine.id} engine did not start")
        }
        com.example.vpn.engine.registry.EngineLedger.shared.started(engine.id, ledgerNetwork())
        XrayLogManager.i("VPN", "Engine ${engine.id} is running on a local port")
        watchSidecar(engine, process)
        return com.example.vpn.sidecar.SidecarChain.xrayProfile(profile, context, launch)
    }

    /** The network key the engine ledger files evidence under (the same one route memory uses). */
    private fun ledgerNetwork(): String = runCatching { com.example.vpn.smart.NetworkIdentity.current(this) }
        .getOrElse { com.example.vpn.smart.NetworkKey.current(this) }

    /** Reports [watched] stopping while it is still the connection's engine. */
    private fun watchSidecar(engine: com.example.vpn.sidecar.SidecarEngine, watched: com.example.vpn.sidecar.RunningEngine) {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive && sidecar === watched && watched.isAlive) delay(1_000)
            if (sidecar === watched) {
                XrayLogManager.w("VPN", "Engine ${engine.id} stopped; traffic stays blocked until another path works")
                failoverManager?.reportTunnelError("engine ${engine.id} stopped")
            }
        }
    }

    /**
     * Starts every hop of a chain and wires each to dial the next (exit hop first). Xray then proxies
     * to the entry hop's SOCKS port exactly as it would a lone engine.
     */
    private fun startChain(chain: com.example.vpn.sidecar.EngineChain.Chain, settings: AppSettings): VlessProfile {
        sidecar?.stop()
        sidecar = null
        val running = com.example.vpn.sidecar.ChainRunner.start(
            chain = chain,
            engineFor = { id -> com.example.vpn.sidecar.Sidecars.ENGINES.firstOrNull { it.id == id } },
            freePort = { com.example.vpn.sidecar.Sidecars.freeLoopbackPort() },
            context = com.example.vpn.sidecar.ChainRunner.ContextFactory { engineId, socksPort, upstream ->
                val engine = com.example.vpn.sidecar.Sidecars.ENGINES.first { it.id == engineId }
                val executable = com.example.vpn.sidecar.Sidecars.executable(engine)
                    ?: error("The $engineId engine is not included in this build for this phone")
                com.example.vpn.sidecar.SidecarContext(
                    workDir = java.io.File(noBackupFilesDir, "engines/chain/$engineId").apply { mkdirs() },
                    executable = executable,
                    socksPort = socksPort,
                    socksUser = com.example.vpn.sidecar.Sidecars.randomToken(9),
                    socksPass = com.example.vpn.sidecar.Sidecars.randomToken(),
                    mode = settings.operationalMode,
                    upstreamSocks = upstream
                )
            },
            start = com.example.vpn.sidecar.ChainRunner.HopStarter { engine, hopProfile, context ->
                showForegroundNotification("Starting the ${engine.id} engine...")
                val launch = engine.prepare(hopProfile, settings, context)
                val process = com.example.vpn.sidecar.EngineReaper.track(engine.startInApp(launch, context)
                    ?: com.example.vpn.sidecar.SidecarProcess.start(engine.id, launch, context.workDir, context.socksPort))
                try {
                    check(process.awaitReady(launch.readyTimeoutMs)) { "The ${engine.id} engine did not start" }
                    process to launch
                } catch (t: Throwable) {
                    process.stop()
                    throw t
                }
            }
        )
        sidecar = running
        XrayLogManager.i("VPN", "Engine chain is running on a local port")
        val watched = running
        serviceScope.launch(Dispatchers.IO) {
            while (isActive && sidecar === watched && watched.isAlive) delay(1_000)
            if (sidecar === watched) {
                XrayLogManager.w("VPN", "A chain engine stopped; traffic stays blocked until another path works")
                failoverManager?.reportTunnelError("a chain engine stopped")
            }
        }
        return com.example.vpn.sidecar.SidecarChain.xrayProfile(
            chain.hops.first().let { com.example.vpn.sidecar.EngineChain.hopProfile(it) },
            running.entryContext, running.entryLaunch
        )
    }

    /** The WireGuard MTU key this connection uses from the LAB's cache; forgotten when traffic fails with it. */
    @Volatile private var activeMtuKey: com.example.vpn.lab.MtuIntelligence.Key? = null

    /**
     * Runs an engine test (Psiphon, Tor, DNS tunnel, Mihomo) while the blocking interface leaves the app's own
     * sockets out: an engine program cannot protect its sockets, so without this window they would be caught by
     * the blocking interface and every engine would look dead. Other apps stay blocked throughout; the ordinary
     * blocking interface is restored afterwards.
     */
    private suspend fun <T> engineWindow(test: () -> T): T = withContext(Dispatchers.IO) {
        val open = runCatching { createBlockingInterface(excludeApp = true) }.getOrNull()
        val previous = vpnInterface
        if (open != null) { vpnInterface = open; runCatching { previous?.close() } }
        try {
            kotlinx.coroutines.runInterruptible { test() }
        } finally {
            if (open != null && vpnInterface === open) {
                runCatching { createBlockingInterface() }.getOrNull()?.let { closed ->
                    vpnInterface = closed
                    runCatching { open.close() }
                }
            }
        }
    }

    private fun ensureBlockingInterface() {
        if (vpnInterface == null) vpnInterface = createBlockingInterface()
    }

    private fun createBlockingInterface(excludeApp: Boolean = false): ParcelFileDescriptor = Builder().setSession("Maximus traffic protection")
            .setMtu(1280).setBlocking(true)
            .apply {
                if (excludeApp) addDisallowedApplication(packageName)
                com.example.vpn.safety.VpnRoutePolicy.ADDRESSES.forEach { addAddress(it.address, it.prefix) }
                com.example.vpn.safety.VpnRoutePolicy.ROUTES.forEach { addRoute(it.address, it.prefix) }
            }
            .addDnsServer(com.example.vpn.safety.VpnRoutePolicy.DNS_SERVER).establish()
            ?: error("Android refused the blocking VPN interface")

    private fun safeProtectSocket(socket: Socket): Boolean {
        try {
            return protectTcpSocket(socket) { protect(it) }
        } catch (e: Exception) {
            XrayLogManager.w("SOCKET", "Unable to prepare or protect TCP socket: ${e.message}")
        }
        return false
    }

    private fun safeProtectFd(fd: Int): Boolean = try {
        protect(fd)
    } catch (e: Exception) {
        XrayLogManager.e("VPN", "Failed to protect native Xray socket fd $fd", e)
        false
    }

    private fun safeProtectDatagram(datagramSocket: DatagramSocket): Boolean {
        try {
            return protect(datagramSocket)
        } catch (e: Exception) {
            XrayLogManager.d("SOCKET", "protect(DatagramSocket) exception: ${e.message}")
        }
        return false
    }

    private fun startDurationAndPingWatchers(profile: VlessProfile, startTime: Long) {
        durationJob?.cancel()
        var lastTx = 0L
        var lastRx = 0L
        durationJob = serviceScope.launch {
            while (isActive && _vpnState.value.isTunnelUp) {
                delay(1000)
                val durationSec = (System.currentTimeMillis() - startTime) / 1000
                if (!activeEngine.isRunning()) {
                    com.example.vpn.diagnostics.events.RuntimeHealth.engineTerminated(
                        _vpnState.value.activeEngineName ?: "engine", exitCode = null, expected = false)
                    connectionMutex.withLock {
                        updateState(_vpnState.value.copy(status = ConnectionStatus.FAILED,
                            errorMessage = "Proxy engine stopped. Traffic remains blocked; reconnect to resume."))
                        disconnectResources()
                        showForegroundNotification("Traffic blocked: proxy engine stopped")
                    }
                    break
                }
                val stats = activeEngine.getStats()

                val currentTx = maxOf(stats.txBytes, serviceTxBytes.get())
                val currentRx = maxOf(stats.rxBytes, serviceRxBytes.get())

                val calcTxSpeed = (currentTx - lastTx).coerceAtLeast(0L)
                val calcRxSpeed = (currentRx - lastRx).coerceAtLeast(0L)
                lastTx = currentTx
                lastRx = currentRx

                val speedTx = if (stats.txSpeedBps > 0) stats.txSpeedBps else calcTxSpeed
                val speedRx = if (stats.rxSpeedBps > 0) stats.rxSpeedBps else calcRxSpeed

                updateState(_vpnState.value.copy(
                    connectedDurationSeconds = durationSec,
                    uploadBytes = currentTx,
                    downloadBytes = currentRx,
                    uploadSpeedBps = speedTx,
                    downloadSpeedBps = speedRx
                ))
            }
        }

        pingJob?.cancel()
        val attemptId = _vpnState.value.attemptId
        val passive = if (activeEngine is NativeTunVpnEngine) com.example.vpn.smart.PassiveHealth() else null
        pingJob = serviceScope.launch {
            var okStreak = 0
            while (isActive && _vpnState.value.isTunnelUp && _vpnState.value.attemptId == attemptId) {
                val wasConnected = _vpnState.value.isConnected
                // A verified connection that is busy carrying data needs no extra request to prove it.
                if (wasConnected && passive?.maySkipActiveCheck() == true) {
                    com.example.vpn.diagnostics.ConnectionMetrics.passiveSkips.incrementAndGet()
                    delay(PING_HEALTHY_MS)
                    continue
                }
                try {
                    com.example.vpn.diagnostics.ConnectionMetrics.activeTunnelChecks.incrementAndGet()
                    val sample = if (engineEndpoint != null) com.example.vpn.diagnostics.LiveTunnelProbe.Sample(routeLatency(), null)
                        else com.example.vpn.diagnostics.LiveTunnelProbe.measure(this@RayVpnService, probeStepMs())
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (_vpnState.value.attemptId == attemptId) passEvent("tunnel.recheck", sample.latencyMs)
                    passive?.recordActiveSuccess()
                    okStreak++
                    mutateState {
                        com.example.data.model.ConnectionVerification.afterCheck(it, attemptId, true, sample.latencyMs, System.currentTimeMillis())
                            .let { s -> if (s.attemptId == attemptId) s.copy(exitCountryCode = sample.country, exitIp = sample.ip ?: s.exitIp) else s }
                    }
                    if (!wasConnected && _vpnState.value.isConnected && _vpnState.value.attemptId == attemptId) {
                        XrayLogManager.i("VPN", "Traffic now goes through the tunnel (${sample.latencyMs} ms): CONNECTED.")
                        showForegroundNotification("Connected to ${profile.name}")
                        _vpnState.value.activeProfile?.let { onTrafficVerified(it, sample.latencyMs) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val stage = com.example.vpn.diagnostics.FailureStage.of(e)
                    okStreak = 0
                    mutateState {
                        com.example.data.model.ConnectionVerification.afterCheck(it, attemptId, false, null, System.currentTimeMillis(), stage)
                            .let { s -> if (s.attemptId == attemptId) s.copy(exitCountryCode = null, exitIp = null) else s }
                    }
                    if (wasConnected && _vpnState.value.status == ConnectionStatus.DEGRADED) {
                        XrayLogManager.w("VPN", "Checks through the tunnel failed ${_vpnState.value.probeFailures} times in a row (${stage.name}): DEGRADED.")
                        showForegroundNotification("Connection degraded (${stage.name})")
                    }
                }
                // An unverified tunnel is checked again soon; a steadily working one less often (the failover
                // watchdog keeps its own faster cadence), so an idle phone is not woken every few seconds.
                delay(when {
                    !_vpnState.value.isConnected -> 4_000L
                    okStreak >= 3 -> PING_HEALTHY_MS
                    else -> 10_000L
                })
            }
        }
    }

    /**
     * Up to three real requests through the tunnel; returns (latency, null) on the first success or
     * (null, the last failure's stage). Bound to the VPN network, so it never falls back to the ISP.
     */
    private suspend fun verifyTraffic(): Pair<Long?, com.example.vpn.diagnostics.FailureStage?> {
        val state = _vpnState.value
        val attemptId = state.attemptId
        val generation = state.networkGeneration
        val engine = com.example.vpn.connectivity.MultiProbeHealthEngine(
            com.example.vpn.connectivity.ProbeBudget(
                maxRetries = VERIFY_ATTEMPTS - 1, retryBackoffMs = VERIFY_RETRY_MS, maxBackoffMs = VERIFY_RETRY_MS,
                perStepTimeoutMs = if (slowTunnel()) SLOW_VERIFY_STEP_MS else 11_000L,
                perConfigTimeoutMs = if (slowTunnel()) SLOW_VERIFY_CONFIG_MS else 40_000L
            )
        )
        val report = engine.run(
            com.example.vpn.connectivity.MultiProbeHealthEngine.Subject(
                sessionId = state.sessionId ?: attemptId ?: "none",
                configFingerprint = state.activeProfile?.effectiveFingerprint,
                engine = state.activeEngineName,
                networkProfileId = com.example.vpn.smart.NetworkCapabilityDetector.last?.key()
            ),
            listOf(com.example.vpn.connectivity.MultiProbeHealthEngine.step(com.example.vpn.connectivity.ProbeStep.HTTP_THROUGH_TUNNEL) {
                routeLatency()
            }),
            // A result for an older attempt or an earlier network never verifies this one.
            freshness = { _vpnState.value.attemptId == attemptId && _vpnState.value.networkGeneration == generation }
        )
        com.example.vpn.connectivity.ProbeEvents.record(report, state.activeProfile?.id)
        val result = report.results.lastOrNull()
        if (report.passed && result?.valueMs != null) {
            passEvent("tunnel.verify", result.valueMs)
            return result.valueMs to null
        }
        XrayLogManager.w("VPN", "Traffic check through the tunnel failed: ${report.observation()}. ${report.assessment()}")
        return null to (report.failureStage ?: com.example.vpn.diagnostics.FailureStage.UNKNOWN)
    }

    /**
     * One real request along the current route: through the VPN network, or through the engine program's
     * local port when the app is left out of its own tunnel (see [engineEndpoint]).
     */
    private suspend fun routeLatency(): Long {
        val endpoint = engineEndpoint ?: return com.example.vpn.diagnostics.LiveTunnelProbe.latency(this, probeStepMs())
        return withContext(Dispatchers.IO) {
            com.example.vpn.verify.TrafficCheck(com.example.vpn.verify.RouteDialers.engine(endpoint), stepTimeoutMs = probeStepMs()).smallRequest()
        }
    }

    /**
     * Every round trip of a DNS tunnel is a DNS query through a public resolver, so one HTTPS request
     * takes several seconds (about 10 s on a filtered mobile network). A 4 s request limit would call a
     * working DNS tunnel dead; other servers keep the short limit.
     */
    private fun slowTunnel(): Boolean =
        _vpnState.value.activeProfile?.let { com.example.vpn.ServerTester.isDnsTunnel(it) } == true

    private fun probeStepMs(): Int = if (slowTunnel()) SLOW_PROBE_STEP_MS else 4_000

    /**
     * After the first verified request: a download counted byte for byte and an upload the far side echoes,
     * along the same route. Runs in the background so it never delays the connection, and its result only
     * counts for the attempt and network it was measured on. A stalled bulk transfer is reported, not acted on.
     */
    private fun launchTrafficCheck() {
        val state = _vpnState.value
        val attemptId = state.attemptId ?: return
        val generation = state.networkGeneration
        val endpoint = engineEndpoint
        val dialer = endpoint?.let { com.example.vpn.verify.RouteDialers.engine(it) } ?: com.example.vpn.verify.RouteDialers.vpn(this)
        val path = if (endpoint != null) com.example.vpn.verify.ProbePath.ENGINE_PROXY else com.example.vpn.verify.ProbePath.TUN
        trafficCheckJob?.cancel()
        trafficCheckJob = serviceScope.launch(Dispatchers.IO) {
            val evidence = runCatching { com.example.vpn.verify.TrafficCheck(dialer).both(attemptId, generation, path) }
                .onFailure { XrayLogManager.w("VPN", "Upload/download check could not run: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return@launch
            if (!isActive) return@launch
            mutateState { if (evidence.appliesTo(it)) it.copy(trafficEvidence = evidence) else it }
            if (evidence.bidirectional && _vpnState.value.attemptId == attemptId) {
                activeRoute?.let { (net, id) -> networkMemory.routes.markBidirectional(net, id) }
            }
            XrayLogManager.i("VPN", "Upload/download check: ${evidence.describe()}.")
            if (evidence.bulkStalled) {
                XrayLogManager.w("VPN", "Small requests pass but a bulk transfer stalled: a path MTU black hole or throttling is suspected.")
            }
            // Only a check through the tunnel itself says anything about the tunnel's MTU.
            val mtuTest = tunMtu
            if (path == com.example.vpn.verify.ProbePath.TUN && mtuTest != null && _vpnState.value.attemptId == attemptId) {
                val (net, used, configured) = mtuTest
                networkMemory.mtu.onBulkCheck(net, used, configured, stalled = evidence.bulkStalled, passed = evidence.bidirectional)?.let { st ->
                    XrayLogManager.i("VPN", when (st) {
                        com.example.vpn.smart.TunMtuTrial.State.TRYING_LOW -> "The next connect on this network tests MTU ${com.example.vpn.smart.TunMtuTrial.LOW_MTU}."
                        com.example.vpn.smart.TunMtuTrial.State.LOW_HELPED -> "MTU $used fixed the bulk stall on this network; it is kept here."
                        com.example.vpn.smart.TunMtuTrial.State.LOW_DID_NOT_HELP -> "The bulk transfer stalled at MTU $used too: lowering MTU did not resolve this test; the cause remains unconfirmed. Returning to $configured."
                    })
                    com.example.vpn.diagnostics.events.EventLog.event("tunnel.mtu.trial", attributes = mapOf("state" to st.name, "mtu" to used.toString()))
                }
            }
            com.example.vpn.diagnostics.events.EventLog.event("tunnel.bidirectional", attributes = mapOf(
                "result" to if (evidence.bidirectional) "verified" else if (evidence.bulkStalled) "stalled" else "partial",
                "path" to path.name,
                "detail" to evidence.describe()
            ))
        }
    }

    /** A request that went through the tunnel, recorded so the PASS can be checked later. */
    private fun passEvent(testType: String, elapsedMs: Long) {
        val s = _vpnState.value
        com.example.vpn.diagnostics.events.EventLog.pass(
            testType = testType,
            destination = java.net.URI(com.example.xray.RealDelayProbe.PROBE_URL).host,
            profileRef = com.example.vpn.diagnostics.events.DiagEvent.profileRef(s.activeProfile?.id ?: s.attemptedProfileId),
            elapsedMs = elapsedMs,
            interfaceName = "tun (VPN network)"
        )
    }

    /** Traffic went through [profile]: its evidence and, for a free config, the last-known-good pool. */
    private fun onTrafficVerified(profile: VlessProfile, rttMs: Long?) {
        if (rttMs != null) {
            val network = com.example.vpn.smart.NetworkKey.current(this)
            networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(profile), rttMs)
            _environment.value = com.example.vpn.smart.NetworkEnvironment.observe(this, networkMemory, dnsPoisoned)
        }
        recordFreeOutcome(profile, success = true, rttMs = rttMs, stage = null)
    }

    private fun recordFreeOutcome(profile: VlessProfile, success: Boolean, rttMs: Long?, stage: com.example.vpn.diagnostics.FailureStage?) {
        if (!isFreeConfig(profile)) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val app = com.example.RayApplication.instance
                val network = com.example.vpn.smart.NetworkCapabilityDetector.last?.key()
                app.freeConfigEvidence.record(profile.effectiveFingerprint, com.example.vpn.hub.ConnectivityMeasurement(
                    timestamp = System.currentTimeMillis(),
                    kind = com.example.vpn.hub.ConnectivityMeasurement.Kind.CONNECTION,
                    success = success, networkKey = network, rttMs = rttMs, failureStage = stage
                ))
                if (success) {
                    app.lastKnownGood.recordVerified(profile, rttMs, System.currentTimeMillis())
                    app.lastKnownGood.dropDead({ fp ->
                        app.freeConfigEvidence.lifecycle(fp) == com.example.vpn.hub.FreeConfigLifecycle.DEAD
                    }, profile.effectiveFingerprint)
                }
            }.onFailure { XrayLogManager.w("FREE", "Could not record the free config result: ${it.message}") }
        }
    }

    private fun isFreeConfig(profile: VlessProfile): Boolean =
        com.example.vpn.hub.FreeConfigList.isList(profile.sourceSubscription.orEmpty()) ||
            com.example.vpn.hub.FreeConfigList.isList(profile.subscriptionUrl.orEmpty())

    /** The engine reported a failed start: the stage is ENGINE_START_FAILED whatever the message says. */
    private class EngineStartException(cause: Throwable) : RuntimeException(cause.message, cause)

    private suspend fun disconnect() = connectionMutex.withLock {
        disconnectLocked()
    }

    private suspend fun disconnectLocked() = withContext(Dispatchers.IO) {
        XrayLogManager.appendLog("Initiating clean VPN disconnection...", "VPN")
        updateState(_vpnState.value.copy(status = ConnectionStatus.DISCONNECTING))

        supervisor.release(com.example.vpn.safety.MaximusVpnSupervisor.Release.USER_DISCONNECT)
        disconnectResources()

        updateState(ConnectionState(
            status = ConnectionStatus.DISCONNECTED,
            activeProfile = null,
            connectedDurationSeconds = 0,
            uploadBytes = 0,
            downloadBytes = 0,
            uploadSpeedBps = 0,
            downloadSpeedBps = 0,
            networkGeneration = _vpnState.value.networkGeneration
        ))
        // Free configs kept only because this session used them can go now.
        serviceScope.launch {
            runCatching { com.example.RayApplication.instance.subscriptionManager.releaseRetained() }
        }

        if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
        if (!protectionRequested) stopSelf()
        XrayLogManager.appendLog("VPN successfully disconnected and interface closed.", "VPN")
    }

    private fun disconnectResources(preserveSidecar: Boolean = false) {
        // Establish the replacement blackhole before stopping either engine. Native
        // startup failures may close their fd, so retaining only that fd is insufficient.
        val previousInterface = vpnInterface
        if (protectionRequested) {
            try { vpnInterface = createBlockingInterface(excludeApp = preserveSidecar) }
            catch (e: Exception) { XrayLogManager.e("VPN", "Unable to replace traffic protection interface", e) }
        }
        durationJob?.cancel()
        durationJob = null
        pingJob?.cancel()
        pingJob = null
        trafficCheckJob?.cancel()
        trafficCheckJob = null
        engineEndpoint = null
        tunMtu = null

        serviceTxBytes.set(0)
        serviceRxBytes.set(0)

        failoverManager?.stopMonitoring()
        failoverManager = null

        tunnelManager?.stop()
        tunnelManager = null

        // Stop Xray before closing the TUN descriptor it owns.
        activeEngine.stop()
        if (!preserveSidecar) {
            sidecar?.stop()
            sidecar = null
            sidecarForwardProfile = null
            sidecarNetwork = null
            sidecarOwnerFingerprint = null
        }

        if (previousInterface !== vpnInterface) {
            try { previousInterface?.close() } catch (_: Exception) {}
        }
        if (!protectionRequested) {
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
        }

    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                XrayLogManager.appendLog("Underlying network available.", "NETWORK")
                // Results measured on the previous network no longer hold: a verified tunnel is re-checked.
                mutateState { com.example.data.model.ConnectionVerification.onNetworkChanged(it) }
                com.example.vpn.connectivity.ConnectivityBrain.refreshSession()
                if (_vpnState.value.status == ConnectionStatus.RECONNECTING) {
                    activeProfile?.let { prof ->
                        val ticket = lifecycle.current()
                        connectJob?.cancel()
                        connectJob = serviceScope.launch {
                            XrayLogManager.appendLog("Network restored. Auto-reconnecting to ${prof.name}...", "VPN")
                            connect(prof, ticket = ticket)
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                XrayLogManager.appendLog("Underlying network connection lost.", "NETWORK")
                mutateState { com.example.data.model.ConnectionVerification.onNetworkChanged(it) }
                val settings = settingsRepository.getSettings()
                if (_vpnState.value.isTunnelUp && _vpnState.value.status != ConnectionStatus.RECONNECTING && settings.autoReconnect) {
                    XrayLogManager.appendLog("Auto-reconnect is enabled. Waiting for network recovery...", "VPN")
                    updateState(_vpnState.value.copy(status = ConnectionStatus.RECONNECTING))
                }
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            XrayLogManager.w("NETWORK", "Failed to register network callback: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showForegroundNotification(statusText: String): Boolean {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, RayVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val reconnectIntent = Intent(this, RayVpnService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val reconnectPendingIntent = PendingIntent.getService(
            this,
            2,
            reconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val profileName = activeProfile?.name ?: "VLESS Tunnel"

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Maximus — $profileName")
            .setContentText(statusText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "Reconnect", reconnectPendingIntent)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } catch (e: Exception) {
                    XrayLogManager.w("VPN", "Special use FGS startup failed: ${e.message}. Falling back to standard startForeground.")
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            XrayLogManager.e("VPN", "Fatal: startForeground() failed: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    override fun onRevoke() {
        XrayLogManager.appendLog("VPN service revoked by system or another VPN application.", "VPN")
        supervisor.release(com.example.vpn.safety.MaximusVpnSupervisor.Release.REVOKED)
        disconnectResources()
        updateState(ConnectionState(status = ConnectionStatus.DISCONNECTED))
        if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
        if (!protectionRequested) stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        com.example.xray.RealDelayProbe.socketProtector = null
        supervisor.release(com.example.vpn.safety.MaximusVpnSupervisor.Release.SERVICE_DESTROYED)
        connectJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        settingsObserverJob?.cancel()
        settingsObserverJob = null
        disconnectResources()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        // The service is gone, so is any tunnel: a state left "up" would be stale (screens, tile and
        // the session's background tests would keep acting on a connection that no longer exists).
        val last = _vpnState.value
        if (last.isTunnelUp || last.isBusy) {
            updateState(ConnectionState(
                status = ConnectionStatus.DISCONNECTED,
                errorMessage = "The VPN service was stopped by the system.",
                networkGeneration = last.networkGeneration
            ))
        }
        super.onDestroy()
    }
}

