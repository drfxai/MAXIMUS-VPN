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
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

class RayVpnService : VpnService() {

    companion object {
        /** Linux TCP_MAXSEG (netinet/tcp.h); android.system.OsConstants does not export it. */
        private const val TCP_MAXSEG = 2
        const val ACTION_CONNECT = "com.drfxai.maximusvpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.drfxai.maximusvpn.ACTION_DISCONNECT"
        const val ACTION_RECONNECT = "com.drfxai.maximusvpn.ACTION_RECONNECT"
        /** "Try another server" on a connection whose large downloads stall: the stall failover, asked by the user. */
        const val ACTION_TRY_ANOTHER_SERVER = "com.drfxai.maximusvpn.ACTION_TRY_ANOTHER_SERVER"
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
        private const val CLEAN_IP_SCAN_LIMIT_MS = 15_000L
        /** How long stage E waits for the CDN lookup and the SNI-proxy check before scanning without them. */
        private const val CLEAN_IP_LOOKUP_MS = 4_000L
        /** Requests through a new tunnel before it counts as unverified, and the pause between them. */
        private const val VERIFY_ATTEMPTS = 3
        private const val VERIFY_RETRY_MS = 1_500L
        private const val SLOW_PROBE_STEP_MS = 20_000
        private const val SLOW_VERIFY_STEP_MS = 25_000L
        /** How long tun.forwarded waits for other apps' traffic before it says "waiting" (not a failure). */
        private const val TUN_FORWARD_WINDOW_MS = 90_000L
        private const val TUN_FORWARD_POLL_MS = 3_000L
        private const val SLOW_VERIFY_CONFIG_MS = 90_000L
        /** Moves to the next working path after a stalled download, per Smart Connect. */
        private const val MAX_STALL_SWITCHES = com.example.vpn.smart.StallFailover.MAX_SWITCHES

        const val NOTIFICATION_CHANNEL_ID = "maximus_vpn_channel"
        const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ACCENT = 0xFFD4AF37.toInt()

        private val _vpnState = MutableStateFlow(ConnectionState())
        val vpnState: StateFlow<ConnectionState> = _vpnState.asStateFlow()

        /**
         * True while the current VPN interface leaves this app out (separate engine programs need that).
         * The app's own web pages, such as the God Mode browser, would then load over the normal connection.
         */
        @Volatile var appOutsideVpn = false
            private set

        /** Set at each Connect: true only when the Smart Connect button started the session. */
        @Volatile private var smartStarted = false

        /** Every published state says whether Smart Connect owns the session, so only its button lights up. */
        private fun stamped(state: ConnectionState): ConnectionState {
            val smart = smartStarted && state.status != ConnectionStatus.DISCONNECTED
            return if (state.smartSession == smart) state else state.copy(smartSession = smart)
        }

        fun updateState(state: ConnectionState) {
            // A connect attempt the user's Stop fenced off is still unwinding: it no longer owns the state (StopFence).
            if (!StopFence.admits()) return
            _vpnState.value = stamped(state)
        }

        /** Changes the state atomically, so concurrent watchers never overwrite each other's fields. */
        fun mutateState(change: (ConnectionState) -> ConnectionState) {
            if (!StopFence.admits()) return
            while (true) {
                val current = _vpnState.value
                if (_vpnState.compareAndSet(current, stamped(change(current)))) return
            }
        }

        private fun newId(): String = java.util.UUID.randomUUID().toString().take(12)

        /** Never a profile id: marks a recovery search that follows an engine's failed carrier search. */
        internal const val ENGINE_FAILED_MARKER = "__engine-carriers-failed__"
        /**
         * Never a profile id: the saved servers stalled or froze in this session (see [StallFailover]), so the
         * search skips them and the selected server and goes on with the later stages (E-G).
         */
        internal const val XRAY_EXHAUSTED_MARKER = "__xray-bulk-exhausted__"
        /**
         * Never a profile id: the run of an automatic Aether lead that Smart Connect held back on a cf=0 network
         * ([com.example.vpn.smart.SmartConnectPlan.deferAether]) now that nothing else carried traffic; the saved
         * servers and recovery engines it waited for are not searched again.
         */
        internal const val AETHER_LAST_MARKER = "__aether-last__"
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
    /** The session's tun.forwarded watch (self-excluded sessions only); separate so the relay gate never waits on it. */
    private var tunForwardJob: Job? = null

    /** The YouTube path check of the current Psiphon attempt ([launchVideoPathProbe]). */
    private var videoProbeJob: Job? = null

    /** Unlock mode's DNS-only session, when that mode is connected (never alongside a tunnel). */
    @Volatile private var unlockSession: com.example.vpn.unlock.UnlockDnsSession? = null

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
    /** Engine name and start time the connected notification shows next to its timer. */
    @Volatile private var notificationCore = "Xray"
    @Volatile private var notificationSince = 0L
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
    private var handoffJob: Job? = null
    private val handoffPolicy = com.example.vpn.connectivity.NetworkHandoffPolicy()
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
        // Smart Connect races: a second-target retry that rescued nobody is not repeated for a minute (RetryMemory).
        com.example.xray.RealDelayProbe.skipUselessRetry = true
        // Clean-IP scans and checks measure the phone's network, not this VPN (or its blocking interface).
        com.example.panels.CleanIpOptimizer.socketProtector = { socket -> vpnInterface == null || safeProtectSocket(socket) }
        // The TLS record-slicing bridge dials the real server outside this VPN, like the probes.
        com.example.vpn.stealth.recordslice.RecordSliceBridges.protect = { socket -> vpnInterface == null || safeProtectSocket(socket) }
        com.example.vpn.stealth.recordslice.RecordSliceBridges.resolve = { host -> bridgeResolve(host) }
        com.example.vpn.stealth.recordslice.RecordSliceBridges.setMss = { socket, mss -> setSocketMss(socket, mss) }
        com.example.vpn.stealth.recordslice.RecordSliceBridges.log = { line -> XrayLogManager.i("STEALTH", line) }
        // Unlock DNS checks and upstream questions leave beside whichever interface this service holds.
        com.example.vpn.unlock.UnlockDns.protector = com.example.vpn.unlock.UnlockDnsWire.Protector(
            udp = { socket -> vpnInterface == null || safeProtectDatagram(socket) },
            tcp = { socket -> vpnInterface == null || safeProtectSocket(socket) }
        )
        com.example.vpn.unlock.UnlockDns.onChoiceChanged = { onUnlockChoiceChanged() }
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
        val unlock = com.example.data.model.OperationalMode.UNLOCK
        if (newMode == unlock || oldMode == unlock) {
            connectionMutex.withLock {
                if (!lifecycle.isCurrent(ticket) || !_vpnState.value.isTunnelUp ||
                    settingsRepository.getSettings().operationalMode != newMode) return@withLock
                lifecycle.newIntent()
                if (newMode == unlock) {
                    // A tunnel becomes the DNS-only session; the engines stop with it.
                    XrayLogManager.i("VPN", "Mode switched to Unlock: replacing the tunnel with the DNS-only session.")
                    com.example.vpn.sidecar.EngineReaper.stopAll()
                    com.example.vpn.sidecar.EngineHandoff.clear()
                    connectUnlockLocked()
                } else {
                    // Unlock has no server to carry into a tunnel mode: stop, and Connect starts the new mode.
                    XrayLogManager.i("VPN", "Mode switched from Unlock to ${newMode.name}: Unlock DNS stopped; Connect starts the new mode.")
                    disconnectLocked()
                }
            }
            return
        }
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
        if (intent?.action == ACTION_TRY_ANOTHER_SERVER) {
            val s = _vpnState.value
            val running = s.activeProfile
            val attemptId = s.attemptId
            if (s.isConnected && running != null && attemptId != null) {
                XrayLogManager.i("FAILOVER", "Try another server: asked by the user.")
                switchAfterStall(attemptId, com.example.vpn.smart.NetworkKey.current(this), running, userAsked = true)
            }
            // Keeps the session's restart policy: this start only acts on the running session.
            return START_STICKY
        }
        if (intent?.action != ACTION_DISCONNECT) {
            stopService(Intent(this, com.example.vpn.share.LocalProxyService::class.java))
        }
        val action = intent?.action
        if ((action == null || action == ACTION_CONNECT || action == ACTION_RECONNECT) &&
            settingsRepository.getSettings().operationalMode == com.example.data.model.OperationalMode.UNLOCK) {
            return startUnlock(action)
        }
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
            connectJob = serviceScope.launch(AttemptTicket(lifecycle.current())) {
                val profile = settingsRepository.getSettings().selectedProfileId
                    ?.let { com.example.vpn.connections.BuiltinConnections.resolve(this@RayVpnService, it) ?: serverRepository.getProfileById(it) }
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
                connectJob = serviceScope.launch(AttemptTicket(lifecycle.current())) {
                    val profile = if (!profileId.isNullOrBlank()) {
                        com.example.vpn.connections.BuiltinConnections.resolve(this@RayVpnService, profileId)
                            ?: serverRepository.getProfileById(profileId)
                    } else null

                    val targetProfile = profile
                        ?: settingsRepository.getSettings().selectedProfileId?.let { com.example.vpn.connections.BuiltinConnections.resolve(this@RayVpnService, it) ?: serverRepository.getProfileById(it) }
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
                // Every attempt started before this Stop is fenced off: one still unwinding a blocking step can no
                // longer change the state, the notification, or hand over to another connect (StopFence).
                StopFence.fence(lifecycle.newIntent())
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
                    lifecycle.singleTeardown { userDisconnect() }
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
                connectJob = serviceScope.launch(AttemptTicket(lifecycle.current())) {
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
        ticket: Long? = null,
        /** Start [profile] exactly as given for this attempt only (the ~16 KB freeze retry, see [onFreeze]). */
        pinned: Boolean = false
    ): Unit = connectionMutex.withLock {
        if (ticket != null && !lifecycle.isCurrent(ticket)) {
            XrayLogManager.i("VPN", "Dropped an automatic reconnect: a newer Connect or Disconnect came after it.")
            return@withLock
        }
        connectLocked(profile, smart, exclude, startedByUser, force, pinned = pinned)
    }

    /** The session was started with the power button: only the selected config, as saved (see [EXTRA_EXACT]). */
    @Volatile private var exactSession = false

    /**
     * Paths that also carried traffic when Smart Connect compared them, best first: the next one is used when
     * the download check finds the chosen one stalls (see [switchAfterStall]). Cleared by every user Connect.
     */
    @Volatile private var smartRunnersUp: List<com.example.vpn.smart.ServerRace.Winner> = emptyList()

    /** The post-connect speed pick already ran (or switched) since the user's last Connect: never twice. */
    @Volatile private var speedPickRan = false
    @Volatile private var stallSwitches = 0
    /** The later Smart Connect stages already ran after a stall or freeze since the user's Connect. */
    @Volatile private var stallEscalated = false

    /** The same-server retry a ~16 KB freeze started ([onFreeze]); its own transfer check decides what follows. */
    private data class FreezeRescue(val serverId: String, val variantId: String, val rescue: com.example.vpn.smart.FreezeFailover.Rescue,
                                    /** The Uplink Shield MSS ladder step the variant runs at, when it is a shield retry. */
                                    val mss: Int? = null)
    @Volatile private var freezeRescue: FreezeRescue? = null
    /** Servers that already had their same-server freeze retry since the user's Connect. */
    private val freezeRescued: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    @Volatile private var freezeSwitches = 0

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
        /** Only the selected server that just failed preflight may be retried in disguise. */
        retryDisguisedProfileId: String? = null,
        /**
         * The user pressed Smart Connect: compare several working paths (rechecked, LAB results and measured
         * speeds on [routeNetwork] counted) instead of taking the first that answers (see ServerRace).
         */
        compare: Boolean = false,
        /** Paths that work now (the selected server just passed) and compete in the comparison. */
        seed: List<com.example.vpn.smart.ServerRace.Winner> = emptyList(),
        routeNetwork: String = network
    ): com.example.vpn.smart.ServerRace.Winner? {
        // Profiles carried by an engine program cannot be measured before it runs, so they are not raced.
        val all = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
            .filter { !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) }
        val intel = runCatching { com.example.RayApplication.instance.iranIntel.current() }.getOrNull()
        // What the LAB's Full Analysis found working here, and what the download check measured through each server here.
        val proven = if (compare) runCatching { com.example.RayApplication.instance.lab.provenOn(routeNetwork).toSet() }.getOrDefault(emptySet())
            else emptySet()
        val speeds = networkMemory.speeds
        val penalty = { p: VlessProfile -> speeds.penaltyMs(routeNetwork, p.id) }
        if (proven.isNotEmpty()) XrayLogManager.i("SMART", "${proven.size} config(s) the LAB's Full Analysis found working here go first.")
        val ranked = com.example.vpn.smart.ServerRace.rank(
            all, networkMemory.workingKinds(network), networkMemory.recentFailures(network), exclude = exclude + seed.map { it.owner.id },
            limit = com.example.vpn.smart.ServerRace.FULL_LIMIT,
            adjust = { p -> intel?.adjustmentFor(p, network)?.value ?: 0.0 },
            preferred = proven, penaltyMs = penalty
        )
        // UDP where UDP is blocked, IPv6 where there is no IPv6: skipped, not hammered (TransportCapabilityEngine).
        val ordered = com.example.vpn.connectivity.TransportCapabilityEngine.order(ranked, transportCapabilities(network))
        // Servers that failed a real request on this network moments ago are not raced again (ConnectivityBrain).
        val book = com.example.vpn.connectivity.ConnectivityBrain.book
        val freezes = networkMemory.freezes
        // Frozen here, and every saved server on the same IPv4 /16 as a frozen one (FrozenGuard.widen; build 151).
        val frozenIds = freezes.frozen(network)
        val frozenHere = com.example.vpn.smart.FrozenGuard.widen(frozenIds, all)
        if (frozenHere.size > frozenIds.size) XrayLogManager.i("SMART", "${frozenHere.size - frozenIds.size} server(s) share a /16 " +
            "with a server frozen on this network; treated as frozen here.")
        // Never down to nothing: the oldest-skipped come back (unfrozen first) when fewer than a few are left (PoolRefill).
        val refill = com.example.vpn.smart.PoolRefill.refill(ordered,
            failedAt = { book.recentFailureAt(com.example.vpn.connectivity.PathRef.of(it)) }, frozen = { it.id in frozenHere })
        val fresh = if (book.session()?.networkKey == routeNetwork) {
            val verifiedOrder = book.eligibleInOrder(refill.candidates, com.example.vpn.connectivity.PathRef::of)
            val verifiedIds = verifiedOrder.mapTo(hashSetOf()) { it.id }
            verifiedOrder + refill.candidates.filterNot { it.id in verifiedIds }
        } else refill.candidates
        if (refill.skipped > 0) XrayLogManager.i("SMART", com.example.vpn.smart.PoolRefill.describe(refill))
        // Last-known-good servers on this network (they passed the upload/download check here) are tested first,
        // and servers that showed the ~16 KB freeze here go to the back while they cool down (FreezeBook).
        val lastGoodIds = freezes.lastGood(network).filter { it !in exclude }
        val eligibleLastGood = if (lastGoodIds.isEmpty()) emptyList() else com.example.vpn.smart.ServerRace.rank(
            all.filter { it.id in lastGoodIds }, exclude = exclude + seed.map { it.owner.id }, limit = lastGoodIds.size
        ).filter { book.eligibility(com.example.vpn.connectivity.PathRef.of(it)).automatic }
        val freezeOrder = com.example.vpn.smart.FreezeBook.order(fresh, lastGoodIds, frozenHere, eligibleLastGood)
        XrayLogManager.i("SMART", freezeOrder.describe())
        // A server frozen here does not compete (a stealth form of it meets the same per-connection cap); it is
        // the last resort only when nothing else is left (FrozenGuard).
        val guard = com.example.vpn.smart.FrozenGuard.split(seed, freezeOrder.profiles, frozenHere, { it.owner.id }, { it.id })
        if (guard.leftOut > 0) XrayLogManager.i("SMART", "Left out ${guard.leftOut} server(s) frozen on this network; used only if nothing else works.")
        val candidates = guard.candidates
        val raceSeed = guard.seed
        if (ordered.size < ranked.size) {
            XrayLogManager.i("SMART", "Skipped ${ranked.size - ordered.size} servers whose transport this network does not carry.")
        }
        val dead = all.filter { it.id == retryDisguisedProfileId && (it.id !in frozenHere || candidates.isEmpty()) }
        if (candidates.isEmpty() && dead.isEmpty()) return raceSeed.firstOrNull() ?: guard.lastResortSeed.firstOrNull()
        XrayLogManager.i("SMART", "Testing ${candidates.size} other servers on ${com.example.vpn.smart.NetworkKey.describe(network)}" +
            if (compare) "; comparing the working ones before choosing." else ".")
        val resolve = { p: VlessProfile -> if (p.profileType != ProfileType.XRAY_JSON && !isLiteralIp(p.address)) resolveEndpoint(p) else p }
        val raceJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        // Abandonable: a Stop returns from here at once; the round already running finishes on its own.
        com.example.vpn.Abandonable.run {
            com.example.vpn.smart.ServerRace(probe = ::probeAll).run(candidates, resolve,
                timeoutSec.coerceAtLeast(com.example.vpn.smart.ServerRace.FULL_MIN_TIMEOUT_SEC),
                maxBatches = com.example.vpn.smart.ServerRace.FULL_BATCHES, onFailure = {
                networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(it))
            }, disguiseOnly = dead, shouldStop = { raceJob?.isActive == false },
                contenders = if (compare) com.example.vpn.smart.ContenderPick.CONTENDERS else 1,
                seed = raceSeed, preferred = proven, penaltyMs = penalty)
        }?.let { return it }
        guard.lastResortSeed.firstOrNull()?.let {
            XrayLogManager.w("SMART", "Only the selected server answered, and it froze on this network before; using it as the last resort.")
            return it
        }
        if (compare) return null
        val best = candidates.firstOrNull() ?: return null
        val choice = com.example.vpn.Abandonable.run { runCatching { finder.choose(best, resolve, all.filterNot { it.id in exclude }, firstFailed = true) }.getOrNull() } ?: return null
        return choice.latencyMs?.let { com.example.vpn.smart.ServerRace.Winner(choice.profile, choice.owner, it) }
    }

    /**
     * True when real lookups on [network] in the last day saw its DNS answer server names with block pages.
     * A connect that skips the lookup (cached answer, IP address, private lookup) must not call the network open.
     */
    private fun dnsTamperedOn(network: String?): Boolean = runCatching {
        com.example.RayApplication.instance.dnsResilience.profile(network, EndpointResolver.DOH_ENDPOINTS).tamperedNames.isNotEmpty()
    }.getOrDefault(false)

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
     * A CDN-fronted server (WebSocket, HTTPUpgrade, XHTTP, gRPC or H2 over TLS; Cloudflare, Fastly, CloudFront
     * or Gcore) whose address is blocked usually still answers on another edge of the same CDN: this looks for
     * one ([com.example.vpn.cleanip.CleanEdgePlan]) and returns the same server reached through it, if it
     * carries two real requests. Cloudflare is not scanned on a network that proxies Cloudflare TLS by name.
     */
    private suspend fun cleanIpPath(
        profile: VlessProfile,
        timeoutSec: Int,
        /** The config's CDN when the caller already looked it up; null looks it up here. */
        knownCdn: com.example.vpn.cleanip.Cdn? = null
    ): com.example.vpn.smart.ServerRace.Winner? {
        if (!com.example.vpn.cleanip.CdnEdgeScanner.fronted(profile)) return null
        val started = System.currentTimeMillis()
        val app = com.example.RayApplication.instance
        val finder = app.cdnCleanIps
        val scanNetwork = ledgerNetwork()
        // The CDN lookup (a DNS answer for a domain address) and the SNI-proxy check do blocking network I/O:
        // each runs in the service scope and is only waited on briefly. A check that finishes later is still
        // remembered for this network, so the next connect here knows.
        val cdn = knownCdn ?: bounded(CLEAN_IP_LOOKUP_MS) { finder.cdnOf(profile) }
        val proxied = if ((cdn ?: com.example.vpn.cleanip.Cdn.CLOUDFLARE) != com.example.vpn.cleanip.Cdn.CLOUDFLARE) false
            else app.cleanIpCache.sniProxied(scanNetwork)
                ?: bounded(CLEAN_IP_LOOKUP_MS) { finder.cloudflareProxied(scanNetwork, app.networkCountry()) }
        val decision = com.example.vpn.cleanip.CleanEdgePlan.decide(profile, cdn, proxied)
        if (!decision.runs) {
            XrayLogManager.i("SMART", "Clean-edge scan skipped: ${decision.skipped}.")
            return null
        }
        showForegroundNotification("Looking for a verified clean path...")
        val scope = com.example.vpn.connectivity.EndpointScoringEngine.destinationScope(scanNetwork, profile)
        val endpointScores = app.endpointScores
        val cached = endpointScores.validated(scope)
        // Candidates must pass TLS with this server's own name (and, for Cloudflare WebSocket, its WebSocket/HTTP
        // exchange), not just a TCP connect: on a filtered network nearly every CDN address accepts TCP and then
        // drops TLS. Addresses that worked here before are tested first; what passes before the time limit is kept.
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        if (decision.websocketScan) withTimeoutOrNull(CLEAN_IP_SCAN_LIMIT_MS) {
            try {
                com.example.panels.CleanIpOptimizer().scan(profile, targetPort = profile.port, enableSpeedTest = false,
                    candidateCount = 48, preferred = endpointScores.remembered(scope, limit = 4),
                    enough = 6, onAccepted = { found += it.ip },
                    assertNetwork = {
                        check(ledgerNetwork() == scanNetwork) { "Network changed during clean-IP scan" }
                    }) { }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                XrayLogManager.w("SMART", "Clean-IP scan stopped: ${e.message}")
            }
        }
        val websocket = synchronized(found) { found.toList() }
        val remaining = CLEAN_IP_SCAN_LIMIT_MS - (System.currentTimeMillis() - started)
        // XHTTP, gRPC and H2 configs, Fastly/CloudFront/Gcore fronts, and too few WebSocket edges: handshake-ranked
        // edges of the config's own CDN with its own TLS name, cached per network (fresh cache answers at once).
        val handshake = if (!com.example.vpn.cleanip.CleanEdgePlan.topUp(decision, websocket.size, remaining)) emptyList() else
            withTimeoutOrNull(remaining) {
                runCatching {
                    finder.find(profile, scanNetwork, app.networkCountry(), preferred = websocket, speedTop = 0, checkProxy = false,
                        knownCdn = decision.cdn)
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .onSuccess { f -> XrayLogManager.i("SMART", "${f.cdn?.label ?: "CDN"} edges for ${profile.transport}: ${f.ips.size}${if (f.fromCache) " (remembered here)" else ""}.") }
                    .getOrNull()?.ips
            }.orEmpty()
        if (ledgerNetwork() != scanNetwork) return null
        val candidates = com.example.vpn.cleanip.CleanEdgePlan.candidates(cached, websocket, handshake)
        if (candidates.isEmpty()) return null
        val attempt = kotlinx.coroutines.currentCoroutineContext()[Job]
        return com.example.vpn.Abandonable.run {
            com.example.vpn.smart.CleanPathRace.select(profile, candidates,
                probe = { paths -> probeAll(paths, timeoutSec) },
                cancelled = { attempt?.isActive == false || ledgerNetwork() != scanNetwork })
        }?.also {
            endpointScores.record(listOf(com.example.vpn.connectivity.EndpointScoringEngine.Measurement(
                it.profile.address, scope, System.currentTimeMillis(), true, it.latencyMs)))
            XrayLogManager.i("SMART", "A clean ${decision.cdn?.label ?: "CDN"} path carried two real requests (${it.latencyMs} ms).")
        }
    }

    /** [block] on the service scope, waited on for at most [ms]; null when it took longer or failed. It keeps running. */
    private suspend fun <T> bounded(ms: Long, block: () -> T): T? {
        val job = serviceScope.async(Dispatchers.IO) { runCatching(block).getOrNull() }
        return withTimeoutOrNull(ms) { job.await() }
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
        reuseSidecar: VlessProfile? = null,
        pinned: Boolean = false
    ): Unit = withContext(Dispatchers.IO) {
        // The saved profile the connection belongs to; a same-server switch below can change it.
        var requestedProfile = if (settingsRepository.getSettings().preferredEngine == EngineType.SING_BOX &&
            !com.example.vpn.sidecar.Sidecars.isEngineProfile(userProfile) &&
            com.example.vpn.sidecar.SingBoxSidecar.autoSelectProblem(userProfile) == null &&
            com.example.vpn.sidecar.Sidecars.isBundled(com.example.vpn.sidecar.SingBoxSidecar)) {
            com.example.vpn.sidecar.SingBoxSidecar.select(userProfile)
        } else userProfile
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
        // A new connect the user asked for supersedes a background WARP-relay retry from an earlier one, and gets
        // its own (single) background relay switch.
        if (startedByUser) {
            aetherRelayPending = null
            aetherRelaySwitched = false
            aetherRelayExit = null
        }
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
        psiphonDirectStartFailed = false
        psiphonChainAllowed = false
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
            // A freeze retry ([pinned]) runs its derived config as given; the session itself stays unpinned.
            val exact = exactSession || reuseSidecar != null || pinned
            val lockExit = policy.lockExit || exact
            val raceFirst = !lockExit && (smart || policy.alwaysSmartConnect)
            // Smart Connect pressed by the user compares working paths; failover and reconnects take the first.
            val compare = raceFirst && smart && startedByUser && !force
            if (startedByUser) {
                smartRunnersUp = emptyList()
                speedPickRan = false
                stallSwitches = 0
                stallEscalated = false
                freezeRescue = null
                freezeRescued.clear()
                freezeSwitches = 0
            }

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

            // cf=0: WARP needs Cloudflare, so an automatic Aether lead is not started first. The saved servers and the
            // recovery engines go first and Aether runs at the very end (the PathGate step below). Not when the user
            // picked Aether (power button, Aether page), nor when Aether carried traffic on this network before.
            val cloudflareBlocked = runCatching {
                com.example.vpn.smart.SmartConnectPlan.cloudflareBlocked(
                    com.example.vpn.smart.NetworkCapabilityDetector.last, routeNetwork, System.currentTimeMillis())
            }.getOrDefault(false)
            val aetherLast = AETHER_LAST_MARKER in exclude
            var deferredAether: VlessProfile? = null
            // No saved server to lead with: Aether is held back like a failed engine, so the recovery engines run first.
            var aetherHeldBack = false
            if (com.example.vpn.smart.SmartConnectPlan.deferAether(cloudflareBlocked,
                    aether = com.example.vpn.sidecar.AetherSidecar.handles(requestedProfile), smart = smart, force = force,
                    lockExit = lockExit, verifiedHere = aetherVerifiedOn(routeNetwork, requestedProfile, settings),
                    alreadyLast = aetherLast || ENGINE_FAILED_MARKER in exclude || XRAY_EXHAUSTED_MARKER in exclude)) {
                deferredAether = requestedProfile
                val alternate = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                    .firstOrNull { it.id != requestedProfile.id && it.id !in exclude &&
                        !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) &&
                        com.example.vpn.subscription.SubscriptionPreset.autoSelectable(it) }
                    ?.let { alt ->
                        if (settings.preferredEngine == EngineType.SING_BOX && com.example.vpn.sidecar.SingBoxSidecar.autoSelectProblem(alt) == null &&
                            com.example.vpn.sidecar.Sidecars.isBundled(com.example.vpn.sidecar.SingBoxSidecar)) com.example.vpn.sidecar.SingBoxSidecar.select(alt)
                        else alt
                    }
                com.example.vpn.diagnostics.events.EventLog.event("smart.aether.deferred", attributes = mapOf(
                    "reason" to "cloudflare blocked (cf=0)", "lead" to if (alternate != null) "saved server" else "recovery engines"))
                if (alternate != null) {
                    requestedProfile = alternate
                    profile = alternate
                    activeProfile = alternate
                    mutateState { if (it.attemptId == attemptId) it.copy(activeProfile = alternate, attemptedProfileId = alternate.id) else it }
                    XrayLogManager.i("SMART", "Cloudflare is blocked on this network (cf=0): Aether (WARP) waits for the end of the search; " +
                        "saved servers go first.")
                } else {
                    aetherHeldBack = true
                    XrayLogManager.i("SMART", "Cloudflare is blocked on this network (cf=0) and no saved server is available: " +
                        "recovery engines go first, Aether (WARP) last.")
                }
            }

            // Direct Psiphon failed to start on this network in the last 6 h: an automatic connect starts with Psiphon
            // over Lantern instead of spending another 2 x 120 s on direct (PsiphonChainMemory). Never when pinned.
            if (com.example.vpn.connections.PsiphonChainMemory.isDirect(requestedProfile) &&
                com.example.vpn.connections.PsiphonChainMemory.preferChain(routeNetwork)) {
                val chain = com.example.vpn.connections.PsiphonChainMemory.chainProfile()
                if (com.example.vpn.connections.PsiphonChainMemory.mayUseChain(direct = true, smart = smart,
                        autoFailover = settings.autoFailoverEnabled, fallback = settings.powerButtonFallback,
                        exact = exact, exitLocked = lockExit, force = force, chainAlreadyTried = chain.id in exclude,
                        chainAvailable = com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(chain) == null)) {
                    com.example.vpn.diagnostics.events.EventLog.event("psiphon.chain_first", attributes = mapOf(
                        "reason" to com.example.vpn.connections.PsiphonChainMemory.describe(routeNetwork)))
                    XrayLogManager.i("SMART", "Psiphon: " + com.example.vpn.connections.PsiphonChainMemory.describe(routeNetwork) + ".")
                    requestedProfile = chain
                    profile = chain
                    activeProfile = chain
                    mutateState { if (it.attemptId == attemptId) it.copy(activeProfile = chain, attemptedProfileId = chain.id) else it }
                }
            }
            // Whether a failed direct Psiphon start may hand over to Psiphon over Lantern in this connect (the failure
            // handler's own check). startSidecar reads it to skip a TCP-only retry the first start already disproved.
            psiphonChainAllowed = com.example.vpn.connections.PsiphonChainMemory.isDirect(requestedProfile) &&
                com.example.vpn.connections.PsiphonChainMemory.chainProfile().let { chain ->
                    com.example.vpn.connections.PsiphonChainMemory.mayUseChain(direct = true, smart = smart,
                        autoFailover = settings.autoFailoverEnabled, fallback = settings.powerButtonFallback,
                        exact = exact, exitLocked = lockExit, force = force, chainAlreadyTried = chain.id in exclude,
                        chainAvailable = com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(chain) == null)
                }

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
                    failure, startedByUser, settingsRepository.getSettings().operationalMode, settingsRepository.getSettings().killSwitchEnabled)
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
            // Uplink Shield: a network where the MSS clamp cleared the 16 KB upload stall gets the ladder step that
            // worked there (1360, 1220 or 1140) on every connect.
            com.example.vpn.stealth.recordslice.UplinkShield.networkMss = networkMemory.shield.activeMss(network)
            // XHTTP speed profile (xmux, post size/interval, padding) for this network; the link's own values win.
            com.example.xray.XhttpProfiles.networkKey = network
            // Smart Connect: a server that froze on this network (~16 KB cap) is neither retried as selected nor in a
            // stealth form while another saved server has not frozen here (FrozenGuard).
            // Build 151: the same /16 as a frozen server counts as frozen (FrozenGuard.widen), in every stage A-G.
            suspend fun frozenWide(): Set<String> {
                if (!raceFirst) return emptySet()
                val saved = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                    .filter { !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) }
                return com.example.vpn.smart.FrozenGuard.widen(networkMemory.freezes.frozen(network), saved)
            }
            var selectedFrozenLogged = false
            suspend fun frozenHold(p: VlessProfile): Boolean {
                if (!raceFirst) return false
                val frozen = frozenWide()
                if (p.id !in frozen) return false
                val others = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                    .filter { !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) }.map { it.id }
                val hold = !com.example.vpn.smart.FrozenGuard.stealthAllowed(p.id, frozen, others)
                // The session's selected server froze here (or sits on a frozen /16): say so once, and let Smart pick.
                // A pinned config (power button, Trade) never gets here: raceFirst is off for it.
                if (hold && !selectedFrozenLogged) {
                    selectedFrozenLogged = true
                    val sameRange = p.id !in networkMemory.freezes.frozen(network)
                    XrayLogManager.i("SMART", com.example.vpn.smart.FrozenGuard.selectedFrozenLine(
                        com.example.core.SecretRedactor.redact(p.name), sameRange))
                    com.example.vpn.diagnostics.events.EventLog.event("smart.selected_frozen", attributes = mapOf(
                        "profile" to (com.example.vpn.diagnostics.events.DiagEvent.profileRef(p.id) ?: "none"),
                        "reason" to if (sameRange) "same /16 as a frozen server" else "frozen on this network"))
                }
                return hold
            }
            runCatching { com.example.vpn.smart.NetworkEnvironment.observe(this@RayVpnService, networkMemory, dnsPoisoned || dnsTamperedOn(network)) }
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
                    com.example.vpn.smart.NetworkCapabilityDetector.last, routeNetwork, System.currentTimeMillis()),
                cloudflareBlocked = cloudflareBlocked
            ))
            val tracker = com.example.vpn.smart.SearchTracker(attemptId, plan) { p ->
                mutateState { if (it.attemptId == attemptId) it.copy(search = p) else it }
            }
            search = tracker
            plan.reading?.let { XrayLogManager.i("SMART", "Measured network: ${it.summary()}.") }
            plan.transportsSkipped.forEach { (t, why) -> XrayLogManager.i("SMART", "${t.title} not tried directly: $why.") }
            if (plan.cloudflareLast) XrayLogManager.i("SMART", "Cloudflare is blocked on this network (cf=0): clean IP for Cloudflare fronts, WARP, MASQUE " +
                "and Matrix move to the end of the search.")
            var raced = false
            // What the pre-flight proved (PathGate): a real request failed on the selected path, or an
            // engine that can only be judged after it starts was chosen.
            // An engine profile (Aether) that just failed its own carrier search in this attempt (see the
            // NoVerifiedPath recovery below) counts as proven dead, so the recovery stages run for it.
            val engineCarriersFailed = (ENGINE_FAILED_MARKER in exclude || aetherHeldBack) &&
                com.example.vpn.sidecar.Sidecars.isEngineProfile(requestedProfile)
            // A stall or freeze failover used up the saved servers: they all carry small requests here, so testing
            // them again would only pick one that stalls; the search goes straight on to stages E-G.
            val xrayExhausted = XRAY_EXHAUSTED_MARKER in exclude
            if (xrayExhausted) {
                listOf(com.example.vpn.smart.SearchStage.REMEMBERED_ROUTE, com.example.vpn.smart.SearchStage.SELECTED,
                    com.example.vpn.smart.SearchStage.SAME_SERVER, com.example.vpn.smart.SearchStage.OTHER_SERVERS).forEach {
                    tracker.skip(it, "Saved servers stalled or froze on bulk downloads in this session")
                }
            }
            // The run of Aether held back to the end: everything before it was already searched.
            if (aetherLast) {
                listOf(com.example.vpn.smart.SearchStage.REMEMBERED_ROUTE, com.example.vpn.smart.SearchStage.SELECTED,
                    com.example.vpn.smart.SearchStage.SAME_SERVER, com.example.vpn.smart.SearchStage.OTHER_SERVERS).forEach {
                    tracker.skip(it, "Searched before Aether: Cloudflare is blocked on this network")
                }
            }
            var provenDead = initialResolutionFailed || engineCarriersFailed || xrayExhausted
            // The clean Cloudflare address scan was held back to the end of the search (cf=0).
            var cleanIpDeferred = false
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
                com.example.vpn.stealth.StealthPathFinder.rememberFingerprint(networkMemory.variants(network), win.owner, win.profile, win.variantKey)
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
            // Recheck the exact successful engine recipe before scanning unrelated servers or registering WARP.
            // In comparison mode only replay a recently verified Lantern route before testing the selected server.
            if (!raced && raceFirst && !lockExit && !force && !aetherLast && !xrayExhausted) {
                val replayBudget = if (compare) 30_000L else 90_000L
                val earlyOutcome = withTimeoutOrNull(replayBudget) {
                    com.example.RayApplication.instance.lab.fastRecovery(
                        exclude = exclude, deadlineMs = System.currentTimeMillis() + replayBudget,
                        engineWindow = { test -> engineWindow(test) }, mode = settings.operationalMode,
                        rememberedOnly = true, rememberedRadianceOnly = compare
                    ) { showForegroundNotification("Rechecking the engine route that worked here...") }
                }
                val early = earlyOutcome?.result
                if (early != null) {
                    tracker.start(com.example.vpn.smart.SearchStage.REMEMBERED_ROUTE)
                    tracker.add(com.example.vpn.smart.SearchStage.REMEMBERED_ROUTE, tested = early.tested, verified = 1)
                    tracker.finish(com.example.vpn.smart.SearchStage.REMEMBERED_ROUTE, verified = true,
                        note = "Remembered engine carried fresh traffic on this network.")
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


            // Stage A: the exact route that carried verified traffic on this network before. The chosen
            // config's own route first; with Smart Connect, then the best other remembered route. Each has
            // to carry a real request here now; a failure counts against it and the normal search goes on.
            if (!raced && !lockExit && !force && !xrayExhausted && !aetherLast) {
                // Smart Connect replays only a route whose upload and download went through here and whose
                // download did not stall since; anything weaker competes in the comparison instead.
                val frozenA = frozenWide()
                val remembered = networkMemory.routes.forNetwork(routeNetwork).filter {
                    it.profileId !in exclude && it.profileId !in frozenA &&
                        (!compare || it.bidirectional && networkMemory.speeds.sample(routeNetwork, it.profileId)?.stalled != true)
                }
                val ordered = remembered.filter { it.profileId == requestedProfile.id } +
                    if (raceFirst) remembered.filter { it.profileId != requestedProfile.id }.take(1) else emptyList()
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


            var firstFailed = initialResolutionFailed || engineCarriersFailed || xrayExhausted
            // The engine's carriers were all just tried and none carried traffic: not tested again here.
            if (raceFirst && !raced && !engineCarriersFailed && !xrayExhausted && !aetherLast) {
                // The chosen server alone first, so a working one costs a single request; only when it
                // carries no traffic are the other servers raced (several kinds of connection per round).
                showForegroundNotification("Finding the fastest server...")
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.ORDINARY_PREFLIGHT)
                tracker.start(com.example.vpn.smart.SearchStage.SELECTED)
                val first = finder.firstPath(requestedProfile, profile)
                chosenVariant = first.variantKey
                val frozenFirst = frozenHold(requestedProfile)
                val skip = recentlyFailed(first.profile) || frozenFirst
                if (frozenFirst) XrayLogManager.i("SMART", com.example.vpn.smart.FrozenGuard.skipLine(com.example.core.SecretRedactor.redact(requestedProfile.name)))
                else if (skip) XrayLogManager.i("SMART", "Selected server skipped: it failed a real request on this network moments ago.")
                when (val outcome = if (initialResolutionFailed) com.example.xray.RealDelayProbe.Outcome.Failed("selected server DNS resolution failed", request = false)
                    else if (skip) com.example.xray.RealDelayProbe.Outcome.Failed("failed moments ago on this network", request = false)
                    else com.example.vpn.Abandonable.run { probe(first.profile, timeouts.firstSec) }) {
                    is com.example.xray.RealDelayProbe.Outcome.Delay -> {
                        profile = first.profile
                        raced = true
                        networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(first.profile), outcome.latencyMs)
                        tracker.finish(com.example.vpn.smart.SearchStage.SELECTED, verified = true)
                        // Smart Connect: the selected server answering is not enough; it competes with the best
                        // other servers, the ones the LAB proved here first, and the most stable, fastest one wins.
                        if (compare) {
                            showForegroundNotification("Comparing the fastest servers...")
                            tracker.start(com.example.vpn.smart.SearchStage.OTHER_SERVERS)
                            val selected = com.example.vpn.smart.ServerRace.Winner(first.profile, requestedProfile, outcome.latencyMs, first.variantKey)
                            val best = raceServers(exclude + requestedProfile.id, network, timeouts.alternateSec, finder,
                                compare = true, seed = listOf(selected), routeNetwork = routeNetwork)
                            if (best == null) {
                                raced = false
                                firstFailed = true
                                provenDead = true
                                chosenVariant = null
                                ladder.advance(com.example.vpn.connectivity.RecoveryStage.SAVED_PROFILE_RACE)
                            } else if (best.owner.id != requestedProfile.id) {
                                adopt(best)
                            } else {
                                chosenVariant = best.variantKey
                            }
                            smartRunnersUp = best?.runnersUp.orEmpty()
                            tracker.finish(com.example.vpn.smart.SearchStage.OTHER_SERVERS, verified = raced)
                        }
                    }
                    is com.example.xray.RealDelayProbe.Outcome.Failed -> {
                        firstFailed = true
                        provenDead = true
                        // A frozen server that was not tested says nothing about its kind of connection here.
                        if (!frozenFirst) networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(first.profile))
                        // A skipped retest is a result too: it failed a real request here moments ago.
                        if (skip) tracker.count(carried = false)
                        tracker.finish(com.example.vpn.smart.SearchStage.SELECTED, verified = false)
                        ladder.advance(com.example.vpn.connectivity.RecoveryStage.SAVED_PROFILE_RACE)
                        tracker.start(com.example.vpn.smart.SearchStage.OTHER_SERVERS)
                        raceServers(exclude + requestedProfile.id, network, timeouts.alternateSec, finder,
                            retryDisguisedProfileId = requestedProfile.id.takeUnless { it in exclude }, compare = compare, routeNetwork = routeNetwork)?.let {
                            adopt(it)
                            if (compare) smartRunnersUp = it.runnersUp
                        }
                        tracker.finish(com.example.vpn.smart.SearchStage.OTHER_SERVERS, verified = raced)
                    }
                    is com.example.xray.RealDelayProbe.Outcome.NotRun -> tracker.finish(com.example.vpn.smart.SearchStage.SELECTED, verified = false,
                        note = "The path test could not run")
                }
            }

            // 3a. A recovery candidate that carried traffic for this config on this network before (FIX BPB
            // or earlier recovery): a derived copy, the saved config itself is unchanged. One request decides;
            // a failure counts towards its rollback and the normal path search continues.
            if (!raced && !lockExit && !xrayExhausted && !aetherLast && !frozenHold(requestedProfile)) {
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
            val frozenStealth = !raced && !lockExit && frozenHold(requestedProfile)
            if (frozenStealth) {
                // TLS fingerprint, split handshake or ECH do not beat a per-connection byte cap: no stealth form of it.
                XrayLogManager.i("STEALTH", com.example.vpn.smart.FrozenGuard.skipLine(com.example.core.SecretRedactor.redact(requestedProfile.name)))
                provenDead = true
            }
            if (!raced && !lockExit && !xrayExhausted && !aetherLast && !frozenStealth && com.example.vpn.stealth.StealthVariants.of(profile).isNotEmpty()) {
                showForegroundNotification("Finding a working route...")
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.SAFE_VARIANTS)
                // Without Smart Connect the finder's first test is the selected server itself (stage B).
                tracker.start(if (firstFailed) com.example.vpn.smart.SearchStage.SAME_SERVER else com.example.vpn.smart.SearchStage.SELECTED)
                val resolvedRequested = profile
                val siblings = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                    .filterNot { it.id in exclude }
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
                    raceServers(exclude + requestedProfile.id + userProfile.id, network, timeouts.alternateSec, finder)
                        ?.let { adopt(it) }
                    tracker.finish(com.example.vpn.smart.SearchStage.OTHER_SERVERS, verified = raced)
                }
                if (choice.nothingWorked && !raced) tracker.start(com.example.vpn.smart.SearchStage.EMERGENCY)
                // Nothing worked, and the server sits behind a CDN: its address may be blocked while other
                // edges of the same CDN are not. Scan for one and try the same server there.
                // cf=0: the clean-address scan waits for the end of the search (after the recovery engines), unless the
                // config sits on Fastly, CloudFront or Gcore, which a Cloudflare block does not touch (CleanEdgePlan.timing).
                if (choice.nothingWorked && !raced) {
                    val early = plan.early(com.example.vpn.smart.EmergencyTransport.CLEAN_IP)
                    val deferred = plan.deferred(com.example.vpn.smart.EmergencyTransport.CLEAN_IP)
                    val front = if (!early && deferred && com.example.vpn.cleanip.CdnEdgeScanner.fronted(requestedProfile))
                        bounded(CLEAN_IP_LOOKUP_MS) { com.example.RayApplication.instance.cdnCleanIps.frontOf(requestedProfile) } else null
                    when (com.example.vpn.cleanip.CleanEdgePlan.timing(early, deferred, front)) {
                        com.example.vpn.cleanip.CleanEdgePlan.Timing.EARLY -> {
                            if (!early) XrayLogManager.i("SMART", "Cloudflare is blocked here, but this config is fronted by " +
                                "${front?.label}: its clean edges are tried now, not at the end.")
                            cleanIpPath(requestedProfile, timeouts.alternateSec, knownCdn = front)?.let { adopt(it) }
                        }
                        com.example.vpn.cleanip.CleanEdgePlan.Timing.DEFERRED -> cleanIpDeferred = true
                        com.example.vpn.cleanip.CleanEdgePlan.Timing.NONE -> Unit
                    }
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
                plan.early(com.example.vpn.smart.EmergencyTransport.WARP)) {
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
                    withTimeoutOrNull(FAST_RECOVERY_MS + 1_000L) {
                        com.example.RayApplication.instance.lab.fastRecovery(skipRefs + exclude + requestedProfile.id + userProfile.id + frozenWide(), deadline,
                            engineWindow = { test -> engineWindow(test) }, mode = settings.operationalMode,
                            resolveForRecovery = { p -> if (isLiteralIp(p.address)) p else resolveEndpoint(p) },
                            heldBackAether = deferredAether) { step ->
                            tracker.note(com.example.vpn.smart.SearchStage.RECOVERY, step)
                            showForegroundNotification(step)
                        }
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
                    // Irancell, cf=0: the held-back Aether already had its one capped WARP attempt before Tor.
                    if (outcome?.irancellWarpTried == true) deferredAether = null
                }
            }

            // cf=0: the Cloudflare-based emergency transports held back above run now, after the recovery engines.
            coroutineContext.ensureActive()
            if (!raced && provenDead && !force && !lockExit && (raceFirst || settings.autoFailoverEnabled) &&
                (cleanIpDeferred || plan.deferred(com.example.vpn.smart.EmergencyTransport.WARP))) {
                tracker.start(com.example.vpn.smart.SearchStage.EMERGENCY)
                showForegroundNotification("Trying Cloudflare-based methods last...")
                if (cleanIpDeferred) cleanIpPath(requestedProfile, timeouts.alternateSec)?.let { adopt(it) }
                if (!raced && plan.deferred(com.example.vpn.smart.EmergencyTransport.WARP)) warpPath(timeouts.alternateSec)?.let { adopt(it) }
                tracker.finish(com.example.vpn.smart.SearchStage.EMERGENCY, verified = raced)
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
                val frozenG = frozenWide()
                val saved = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                    .filter { com.example.vpn.subscription.SubscriptionPreset.autoSelectable(it) }
                    .filterNot { it.id in exclude }
                    .let { list -> list.filterNot { it.id in frozenG }.ifEmpty { list } }
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
                // cf=0: nothing else carried traffic, so the Aether lead held back at the start runs now, last.
                val lastAether = deferredAether
                if (lastAether != null) {
                    coroutineContext.ensureActive()
                    XrayLogManager.w("SMART", "No saved server or recovery engine carried traffic; trying Aether (WARP) last, " +
                        "as Cloudflare is blocked on this network.")
                    showForegroundNotification("Trying Aether (WARP) last...")
                    tracker.end()
                    disconnectResources()
                    connectLocked(lastAether, smart = true, exclude = exclude + AETHER_LAST_MARKER)
                    return@withContext
                }
                val name = com.example.core.SecretRedactor.redact(requestedProfile.name)
                XrayLogManager.w("SMART", "No verified path: '$name' carried no real traffic on this network and no alternative did. " +
                    "The tunnel was not started with it.")
                // Same rule as an unusable profile: a user's DAILY connect releases the block; an automatic
                // connect or GOD MODE keeps traffic blocked until the user disconnects.
                val release = com.example.vpn.safety.FailClosedPolicy.mayReleaseBlock(
                    com.example.vpn.safety.FailClosedPolicy.Failure.INVALID_PROFILE, startedByUser, settingsRepository.getSettings().operationalMode, settingsRepository.getSettings().killSwitchEnabled)
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
            // The default on mobile data is 1400 (1280 for WireGuard/MASQUE-carried paths), see TunMtuDefaults;
            // Wi-Fi and Ethernet keep the setting. The MTU trial below works from this base.
            val carriesWireGuard = profile.protocolType == com.example.data.model.ProtocolType.WIREGUARD ||
                com.example.vpn.sidecar.Sidecars.forProfile(profile)?.id in setOf(
                    com.example.vpn.sidecar.AetherSidecar.ID, com.example.vpn.sidecar.MasqueSidecar.ID)
            val configuredMtu = com.example.vpn.smart.TunMtuDefaults.base(settings.mtu, routeNetwork, carriesWireGuard)
            if (configuredMtu != settings.mtu.coerceIn(1280, 1500)) XrayLogManager.i("VPN", "Tunnel MTU $configuredMtu on mobile data " +
                "instead of ${settings.mtu}" + if (carriesWireGuard) " (WireGuard/MASQUE path)." else ".")
            // A bulk transfer stalled on this network last time while small requests passed: this connect
            // tests the lower MTU (TunMtuTrial); its own bulk check decides whether it stays.
            val safeMtu = networkMemory.mtu.mtuFor(routeNetwork, configuredMtu)
            if (safeMtu != configuredMtu) XrayLogManager.i("VPN", "Tunnel MTU $safeMtu on this network instead of $configuredMtu: " +
                "a bulk transfer stalled here before (testing whether MTU is the cause).")
            tunMtu = Triple(routeNetwork, safeMtu, configuredMtu)
            val amnezia = com.example.vpn.engine.AmneziaNativeEngine.takeIf { it.handles(profile) }
            val nativeConfig = amnezia?.config(profile)
            val primaryDns = nativeConfig?.dns?.first() ?: com.example.vpn.safety.VpnRoutePolicy.DNS_SERVER
            val interfaceIp = nativeConfig?.addresses?.first()?.ip ?: "172.19.0.1"
            val builder = Builder()
                .setSession("Maximus - ${profile.name}")
                .setMtu(safeMtu)
                .setBlocking(true)
            if (nativeConfig == null) {
                builder.addDnsServer(primaryDns)
                com.example.vpn.safety.VpnRoutePolicy.ADDRESSES.forEach { builder.addAddress(it.address, it.prefix) }
            } else {
                nativeConfig.dns.forEach { builder.addDnsServer(it) }
                nativeConfig.addresses.forEach { builder.addAddress(it.ip, it.prefix) }
            }
            // Capture both families; the native engine only accepts traffic allowed by its peer.
            com.example.vpn.safety.VpnRoutePolicy.ROUTES.forEach { builder.addRoute(it.address, it.prefix) }
            var sidecarEngine = if (amnezia == null) com.example.vpn.sidecar.Sidecars.forProfile(profile) else null
            val engineChain = com.example.vpn.sidecar.ChainRunner.readChain(profile)
            // A saved server whose link carries patterniha-fork-only fields runs on the fork (the Serverless program) as a
            // local SOCKS sidecar; without that program it runs on stock Xray with those fields dropped (ForkXraySidecar).
            com.example.vpn.engine.ProfileExtras.read(profile).optString(com.example.vpn.engine.ProfileExtras.PATT_DIAL_MODE)
                .takeIf { it.isNotBlank() && com.example.vpn.engine.PatternihaFields.dialMode(profile) == null }
                ?.let { XrayLogManager.w("VPN", "fork-xray · dialMode \"${it.take(64)}\" is not valid for the fork (oob./oob4./oob6. byte lists); ignored.") }
            if (amnezia == null && sidecarEngine == null && engineChain == null && reuseSidecar == null &&
                com.example.vpn.sidecar.ForkXraySidecar.applies(profile)) {
                val fields = com.example.vpn.sidecar.ForkXraySidecar.forkFields(profile).joinToString(",")
                if (com.example.vpn.sidecar.Sidecars.isBundled(com.example.vpn.sidecar.ForkXraySidecar)) {
                    sidecarEngine = com.example.vpn.sidecar.ForkXraySidecar
                    XrayLogManager.i("VPN", "fork-xray · link fields [$fields] need the patterniha Xray fork; running it as a local sidecar.")
                } else {
                    profile = com.example.vpn.sidecar.ForkXraySidecar.stockProfile(profile)
                    XrayLogManager.w("VPN", "fork-xray · fork program not in this build; stock Xray runs without [$fields].")
                }
            }
            appOutsideVpn = sidecarEngine != null || engineChain != null
            // A separate engine program cannot ask Android to keep its own sockets out of the VPN, so the app's
            // own traffic is left out then. The user's per-app routing (Settings › Routing & DNS) is applied too.
            com.example.vpn.routing.SplitTunnelStore.apply(this@RayVpnService, builder, excludeSelf = appOutsideVpn, interfaceName = "tunnel")

            XrayLogManager.i("VPN", "[DIAGNOSTICS] 4. Builder.establish() invoked (MTU=$safeMtu, Address=$interfaceIp, DNS=$primaryDns).")
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
                vpnIp = interfaceIp,
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
            else if (sidecarEngine === com.example.vpn.sidecar.ForkXraySidecar) {
                profile = try {
                    startSidecar(com.example.vpn.sidecar.ForkXraySidecar, profile, settings)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
                    // The fork did not start: stock Xray carries the server without the fork-only fields.
                    XrayLogManager.w("VPN", "fork-xray · sidecar did not start (${e.javaClass.simpleName}); stock Xray without fork fields.")
                    com.example.vpn.diagnostics.events.EventLog.event("fork_xray.fallback", attributes = mapOf("reason" to e.javaClass.simpleName))
                    sidecar?.stop()
                    sidecar = null
                    sidecarEngine = null
                    com.example.vpn.sidecar.ForkXraySidecar.stockProfile(profile)
                }
            }
            else if (sidecarEngine != null) {
                // Automatic Aether that may hand over (same rule as the NoVerifiedPath recovery below): the path verified
                // on this network that it would fall back to. With Cloudflare filtered, Aether then skips its gateway
                // scans and fails within seconds instead of ~3 minutes (ConnectionEngineRunner.start).
                val aetherFallback = if (com.example.vpn.sidecar.AetherSidecar.handles(requestedProfile) &&
                    com.example.vpn.connections.ConnectionEngineRunner.mayRecoverAether(aether = true, smart = smart,
                        autoFailover = settings.autoFailoverEnabled, exact = exactSession, exitLocked = policy.lockExit,
                        force = force, alreadyEscalated = exclude.isNotEmpty(), fallback = settings.powerButtonFallback))
                    verifiedFallback(routeNetwork, exclude + requestedProfile.id)?.second else null
                profile = startSidecar(sidecarEngine, profile, settings, aetherFallback)
            }
            mutateState { it.copy(activeRoute = com.example.data.model.ConnectionRoute.label(profile)) }
            engineEndpoint = if (sidecarEngine?.id == com.example.vpn.sidecar.ByedpiSidecar.ID)
                com.example.vpn.sidecar.ByedpiSidecar.healthEndpoint(profile)
                else if (engineChain != null || sidecarEngine != null) com.example.vpn.verify.Socks5.endpointOf(profile) else null
            sidecarForwardProfile = profile.takeIf { sidecar != null }
            sidecarNetwork = ledgerNetwork().takeIf { sidecar != null }
            sidecarOwnerFingerprint = requestedProfile.effectiveFingerprint.takeIf { sidecar != null }
            // The app is left out of its own VPN here, so the device (TUN) path cannot be dialed from the app
            // (TunCheckPlan). A byte meter on the TUN → engine hop measures what the TUN forwards for other apps.
            val xrayProfile = withTunForwardMeter(profile)
            val runtime = EngineSelectionPolicy.select(profile)
            val engineId = if (amnezia != null) com.example.vpn.engine.AmneziaNativeEngine.ID
                else com.example.vpn.engine.registry.EngineRegistry.descriptorFor(runtime).id
            if (!engineBreaker.allows(engineId)) {
                // Each profile has one engine today, so this only reports; with a second engine for
                // the same protocol the selection will skip an open breaker.
                XrayLogManager.w("VPN", "Engine $engineId failed to start several times in a row; trying it again.")
            }
            activeEngine = amnezia ?: when (runtime) {
                EngineSelectionPolicy.Runtime.KOTLIN_TUNNEL -> com.example.vpn.engine.KotlinTunnelEngine.instance
                EngineSelectionPolicy.Runtime.XRAY -> XrayEngineImpl.instance
            }

            updateState(_vpnState.value.copy(activeEngineName = activeEngine.engineVersion))
            notificationCore = amnezia?.engineVersion ?: ConnectedNotification.coreLabel(
                kotlinTunnel = runtime == EngineSelectionPolicy.Runtime.KOTLIN_TUNNEL,
                sidecarId = sidecarEngine?.id, chain = engineChain != null)

            updateState(_vpnState.value.copy(status = ConnectionStatus.PROXY_CONNECTING))
            showForegroundNotification("Connecting Proxy via ${activeEngine.engineType.displayName}...")

            // Post-connect speed pick: the race's other verified servers ride along in this core as loopback
            // probes, measured once the transfer check passed (PostConnectSpeedPick, startSpeedPick).
            com.example.vpn.smart.PostConnectSpeedPick.clear()
            if (raced && raceFirst && !force && !exact && activeRecovery == null && amnezia == null &&
                sidecarEngine == null && engineChain == null && runtime == EngineSelectionPolicy.Runtime.XRAY) {
                runCatching { stageSpeedProbes(settings) }
                    .onFailure { XrayLogManager.w("SMART", "speed-pick · probes not staged: ${it.javaClass.simpleName}") }
            }
            val startResult = if (activeEngine is NativeTunVpnEngine) {
                (activeEngine as NativeTunVpnEngine).startWithTun(xrayProfile, settings.copy(mtu = safeMtu), pfd.fd) { fd -> safeProtectFd(fd) }
            } else {
                activeEngine.start(
                    profile = xrayProfile,
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
                profile = xrayProfile,
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
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 8. ${if (activeEngine is NativeTunVpnEngine) "Native TUN loop started" else "TUN packet loop started"}.")

            if (!coroutineContext.isActive) {
                disconnectResources()
                return@withContext
            }

            XrayLogManager.i("VPN", "[DIAGNOSTICS] 9. Upstream socket protection probe passed.")

            // 10. Only real traffic through the tunnel makes the connection CONNECTED. Until a request
            // goes through, the state says the tunnel is up but unverified.
            val startTime = System.currentTimeMillis()
            mutateState {
                if (it.attemptId == attemptId) it.copy(status = ConnectionStatus.VERIFYING, vpnIp = interfaceIp, errorMessage = null,
                    revived = activeRecovery != null) else it
            }
            showForegroundNotification("Verifying traffic through ${profile.name}...")
            val verificationGeneration = _vpnState.value.networkGeneration
            val result = verifyTraffic()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (_vpnState.value.attemptId != attemptId) return@withContext
            val currentNetwork = _vpnState.value.networkGeneration == verificationGeneration &&
                result.second != com.example.vpn.diagnostics.FailureStage.NETWORK_CHANGED
            val check = if (currentNetwork) result else null to com.example.vpn.diagnostics.FailureStage.NETWORK_CHANGED
            val measurement = if (engineEndpoint == null) com.example.vpn.connectivity.MeasurementType.TUNNEL_TRAFFIC
                else com.example.vpn.connectivity.MeasurementType.ENGINE_REQUEST
            if (currentNetwork) {
                val measuredProfile = if (engineEndpoint != null && check.first != null) requestedProfile else profile
                book.recordPath(com.example.vpn.connectivity.EvidenceSource.SMART_CONNECT, measurement,
                    com.example.vpn.connectivity.PathRef.of(measuredProfile),
                    com.example.vpn.stealth.ConnectionKind.of(measuredProfile), check.first != null,
                    check.first, check.second?.name)
            }
            // Bulk gate (build 151): on a metered network or one with a ~16 KB freeze on record, a Smart pick must also
            // carry a short bulk download before it counts as verified. One that stops inside the freeze window is marked
            // frozen here and the search goes on without it (at most BulkGate.MAX_CANDIDATES per Connect, ≤ 6 s each).
            if (check.first != null && currentNetwork && engineEndpoint == null && raced && raceFirst && !force && !exact &&
                activeRecovery == null && !com.example.vpn.sidecar.Sidecars.isEngineProfile(requestedProfile)) {
                val freezes = networkMemory.freezes
                val gated = com.example.vpn.smart.BulkGate.gatedSoFar(exclude)
                val gateNow = System.currentTimeMillis()
                val meteredHere = _environment.value?.signals?.contains(com.example.vpn.smart.NetworkEnvironment.Signal.METERED) == true ||
                    com.example.vpn.connections.AetherBackgroundRelay.metered(measuredMetered = runCatching {
                        com.example.vpn.connections.ConnectionEngineRunner.linkHints(com.example.vpn.smart.NetworkEnvironment.freshEvidence(
                            com.example.vpn.smart.NetworkCapabilityDetector.last, routeNetwork, gateNow)).metered
                    }.getOrDefault(false), activeMetered = false, networkKey = network)
                val recentlyGood = freezes.good(network, requestedProfile.id)
                    ?.let { gateNow - it.at in 0 until com.example.vpn.smart.BulkGate.RECENT_GOOD_MS } == true
                if (com.example.vpn.smart.BulkGate.applies(smartPick = true, tunPath = !appOutsideVpn, metered = meteredHere,
                        freezeHistory = freezes.frozen(network).isNotEmpty(), recentlyGood = recentlyGood, gatedSoFar = gated)) {
                    showForegroundNotification("Checking a bulk download through ${profile.name}...")
                    val read = withTimeoutOrNull(com.example.vpn.smart.BulkGate.BUDGET_MS + 500L) {
                        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                            com.example.vpn.verify.TrafficCheck(com.example.vpn.verify.RouteDialers.vpn(this@RayVpnService),
                                stepTimeoutMs = com.example.vpn.smart.BulkGate.STEP_MS).gateDownload()
                        }
                    } ?: com.example.vpn.smart.BulkGate.Read.NotTested("gate time limit")
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (_vpnState.value.attemptId != attemptId) return@withContext
                    val verdict = com.example.vpn.smart.BulkGate.verdict(read)
                    val safe = com.example.core.SecretRedactor.redact(requestedProfile.name)
                    com.example.vpn.diagnostics.events.EventLog.event("smart.bulk_gate", attributes = mapOf(
                        "result" to verdict.name.lowercase(), "detail" to com.example.vpn.smart.BulkGate.describe(read),
                        "candidate" to (gated + 1).toString(),
                        "profile" to (com.example.vpn.diagnostics.events.DiagEvent.profileRef(requestedProfile.id) ?: "none")))
                    XrayLogManager.i("SMART", "Bulk gate ${verdict.name.lowercase()} for $safe: ${com.example.vpn.smart.BulkGate.describe(read)}.")
                    if (verdict == com.example.vpn.smart.BulkGate.Verdict.FROZEN) {
                        freezes.recordFrozen(network, requestedProfile.id)
                        networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(profile))
                        XrayLogManager.w("SMART", "$safe passed one small request but its download stopped at about 16 KB: " +
                            "marked frozen on this network; searching again without it (and its /16).")
                        showForegroundNotification("Server froze at 16 KB; trying another...")
                        disconnectResources()
                        connectLocked(userProfile, smart = smart, exclude = exclude + com.example.vpn.smart.BulkGate.marker(gated + 1))
                        return@withContext
                    }
                }
            }
            if (check.first != null) ladder.advance(com.example.vpn.connectivity.RecoveryStage.CONNECTED_VERIFIED)
            else if (currentNetwork) activeMtuKey?.let { com.example.vpn.lab.MtuIntelligence.cache.failed(it); activeMtuKey = null }
            // Local proxy checks only exercise the sidecar chain. TUN checks exercise the forwarding core.
            val sidecarIds = engineChain?.hops?.map { it.engineId } ?: listOfNotNull(sidecarEngine?.id)
            val pathEngines = if (engineEndpoint == null) listOf(engineId) + sidecarIds else sidecarIds
            if (currentNetwork) pathEngines.forEach { id ->
                val ledger = com.example.vpn.engine.registry.EngineLedger.shared
                if (check.first != null) ledger.verified(id, routeNetwork)
                // A chain failure does not identify which engine failed.
                else if (pathEngines.size == 1) ledger.failed(id, routeNetwork, check.second?.name)
            }
            mutateState {
                com.example.data.model.ConnectionVerification.afterCheck(it, attemptId, check.first != null, check.first, System.currentTimeMillis(), check.second, path =
                    if (engineEndpoint == null) com.example.vpn.verify.ProbePath.TUN else com.example.vpn.verify.ProbePath.ENGINE_PROXY)
            }
            if (_vpnState.value.isConnected) {
                showConnectedNotification()
                val checkedPath = if (engineEndpoint != null) "engine local proxy" else "Android VPN interface"
                XrayLogManager.i("VPN", "[DIAGNOSTICS] 10. Traffic verified through $checkedPath (${check.first} ms). Final state: CONNECTED.")
                val connectMs = System.currentTimeMillis() - connectStartedAt
                com.example.vpn.diagnostics.ConnectionMetrics.recordConnectTime(connectMs)
                XrayLogManager.i("VPN", "Time from connect to verified traffic: $connectMs ms.")
                onTrafficVerified(requestedProfile, check.first)
                // A fallback for automatic Aether on this network when Cloudflare is filtered (VerifiedWinnerBook).
                if (!com.example.vpn.sidecar.AetherSidecar.handles(requestedProfile)) runCatching {
                    networkMemory.winners.recordVerified(routeNetwork, requestedProfile.id,
                        com.example.core.SecretRedactor.redact(requestedProfile.name))
                }
                activeRoute = recordRoute(routeNetwork, requestedProfile, profile, chosenVariant, check.first ?: 0L)
                retryAetherRelayInBackground(requestedProfile, routeNetwork, attemptId)
                if (com.example.vpn.sidecar.Sidecars.isEngineProfile(requestedProfile) &&
                    com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService) == routeNetwork) {
                    com.example.vpn.smart.EngineRouteHints.record(this@RayVpnService, routeNetwork, requestedProfile)
                }
                launchTrafficCheck(check.first)
                launchVideoPathProbe(requestedProfile)
            } else {
                val stage = check.second ?: com.example.vpn.diagnostics.FailureStage.UNKNOWN
                showForegroundNotification("Tunnel up, no traffic yet (${stage.name})")
                XrayLogManager.w("VPN", "[DIAGNOSTICS] 10. Tunnel started but no request went through it (${stage.name}). " +
                    "State: TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED; checks continue and failover may switch servers.")
                if (currentNetwork) recordFreeOutcome(requestedProfile, success = false, rttMs = null, stage = stage)
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
                savedProfile = { requestedProfile },
                frozenOn = { net -> networkMemory.freezes.frozen(net) },
                onTriggerSwitch = onTriggerSwitch@{ newProfile, reason, excluded ->
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
                        connectJob = serviceScope.launch(AttemptTicket(ticket)) {
                            try {
                                XrayLogManager.w("FAILOVER", "Executing auto-failover, '${newProfile.name}' first: $reason")
                                // Race the saved servers so the switch lands on one that carries traffic now.
                                connect(newProfile, smart = true, exclude = excluded + degraded, ticket = ticket)
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
            val failedNetwork = runCatching { com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService) }.getOrNull()
            // The user's own Aether pick failed (no silent switch for it): offer the path verified on this network
            // with one tap instead (FAILED card, "Connect with ...").
            val suggestion = if (e is com.example.vpn.connections.ConnectionEngineRunner.NoVerifiedPath && failedNetwork != null &&
                com.example.vpn.sidecar.AetherSidecar.handles(requestedProfile)) verifiedFallback(failedNetwork, exclude + requestedProfile.id)
                else null
            updateState(_vpnState.value.copy(
                status = ConnectionStatus.FAILED,
                errorMessage = e.localizedMessage ?: "Unknown connection failure",
                failureStage = if (e is EngineStartException) com.example.vpn.diagnostics.FailureStage.ENGINE_START_FAILED
                    else com.example.vpn.diagnostics.FailureStage.of(e),
                suggestedProfileId = suggestion?.first?.id,
                suggestedProfileName = suggestion?.second?.label()
            ))
            disconnectResources()
            // Aether's selected engine is only verified *after* startup. If every built-in
            // carrier failed, the earlier Smart Connect preflight may have had nothing to
            // probe. Continue once with saved-server discovery, subject to user policy.
            val configured = runCatching { settingsRepository.getSettings() }.getOrNull()
            if (e is com.example.vpn.connections.ConnectionEngineRunner.NoVerifiedPath && configured != null &&
                com.example.vpn.connections.ConnectionEngineRunner.mayRecoverAether(
                    aether = com.example.vpn.sidecar.AetherSidecar.handles(requestedProfile),
                    smart = smart, autoFailover = configured.autoFailoverEnabled,
                    exact = exactSession,
                    exitLocked = com.example.vpn.safety.OperatingModePolicy.of(configured.operationalMode).lockExit,
                    force = force, alreadyEscalated = exclude.isNotEmpty(),
                    fallback = configured.powerButtonFallback)) {
                // Build 171: a path verified on this same network minutes ago (best measured rate, then the most
                // recent) goes first, instead of a saved server that was never tested here or the recovery engines.
                if (suggestion != null) {
                    val (winnerProfile, winner) = suggestion
                    aetherRelayPending = if (e.relayDeferred) runCatching {
                        AetherRelayPending(requestedProfile, smart, failedNetwork!!, System.currentTimeMillis())
                    }.getOrNull() else null
                    com.example.vpn.diagnostics.events.EventLog.event("smart.verified_fallback", attributes = mapOf(
                        "from" to "aether", "profile" to (com.example.vpn.diagnostics.events.DiagEvent.profileRef(winner.profileId) ?: "none"),
                        "rate.kbps" to (winner.bytesPerSecond?.div(1024)?.toString() ?: "unmeasured"),
                        "age.min" to ((System.currentTimeMillis() - winner.at) / 60_000).toString()))
                    XrayLogManager.w("SMART", "Aether carries no verified traffic here; using ${winner.label()}, verified on this " +
                        "network ${(System.currentTimeMillis() - winner.at) / 60_000} min ago.")
                    showForegroundNotification("Aether failed; using the connection verified on this network...")
                    mutateState { it.copy(suggestedProfileId = null, suggestedProfileName = null) }
                    connectLocked(winnerProfile, smart = true, exclude = exclude + requestedProfile.id)
                    return@withContext
                }
                val alternate = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                    .firstOrNull { it.id != requestedProfile.id && it.id !in exclude &&
                        !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) &&
                        com.example.vpn.subscription.SubscriptionPreset.autoSelectable(it) }
                if (alternate != null) {
                    // WARP data is blocked here and no saved server was verified on this network to carry it: once a
                    // saved server is verified and connected, WARP through it is retried in the background.
                    aetherRelayPending = if (e.relayDeferred) runCatching {
                        AetherRelayPending(requestedProfile, smart,
                            com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService), System.currentTimeMillis())
                    }.getOrNull() else null
                    XrayLogManager.w("SMART", "Aether carries no verified traffic; continuing Smart Connect on saved servers.")
                    showForegroundNotification("Aether failed; trying verified saved-server paths...")
                    connectLocked(alternate, smart = true, exclude = exclude + requestedProfile.id)
                    return@withContext
                }
                // No saved server to move to (a fresh install, or only WARP configured): run the recovery
                // engines (MASQUE, Psiphon, Tor bridges, chains) instead of stopping at Aether's failure.
                // The search starts from this same Aether profile already marked as failed, so PathGate never
                // restarts it blindly and the attempt ends cleanly if nothing carries a real request.
                XrayLogManager.w("SMART", "Aether carries no verified traffic and no saved server is available; trying recovery engines.")
                showForegroundNotification("Aether failed; trying recovery engines...")
                connectLocked(requestedProfile, smart = smart,
                    exclude = exclude + requestedProfile.id + ENGINE_FAILED_MARKER)
                return@withContext
            }
            // Direct Psiphon never brought a tunnel up here: try Psiphon chained over Lantern before giving up
            // (build 170 Irancell: direct timed out twice while the chain connected at once).
            if (psiphonDirectStartFailed && configured != null) {
                psiphonDirectStartFailed = false
                val chain = com.example.vpn.connections.PsiphonChainMemory.chainProfile()
                if (com.example.vpn.connections.PsiphonChainMemory.mayUseChain(
                        direct = com.example.vpn.connections.PsiphonChainMemory.isDirect(requestedProfile),
                        smart = smart, autoFailover = configured.autoFailoverEnabled,
                        fallback = configured.powerButtonFallback, exact = exactSession,
                        exitLocked = com.example.vpn.safety.OperatingModePolicy.of(configured.operationalMode).lockExit,
                        force = force, chainAlreadyTried = chain.id in exclude,
                        chainAvailable = com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(chain) == null)) {
                    com.example.vpn.diagnostics.events.EventLog.event("psiphon.chain_fallback", attributes = mapOf(
                        "reason" to "direct Psiphon start failed", "next" to "psiphon over lantern"))
                    XrayLogManager.w("SMART", "Direct Psiphon did not start on this network; trying Psiphon over Lantern.")
                    showForegroundNotification("Psiphon failed; trying Psiphon over Lantern...")
                    connectLocked(chain, smart = smart, exclude = exclude + requestedProfile.id)
                    return@withContext
                }
            }
            if (fallbackFromDirect(requestedProfile)) return@withContext
            if (protectionRequested) showForegroundNotification("Traffic blocked: connection failed")
            if (!protectionRequested) stopForeground(STOP_FOREGROUND_REMOVE)
            if (!protectionRequested) stopSelf()
        }
    }

    /**
     * The best other path verified on [network] ([com.example.vpn.smart.VerifiedWinnerBook]), resolved to its profile;
     * null when none is remembered, it no longer exists, or this build cannot run it.
     */
    private suspend fun verifiedFallback(network: String, exclude: Set<String>):
        Pair<VlessProfile, com.example.vpn.smart.VerifiedWinnerBook.Winner>? = runCatching {
        val winner = networkMemory.winners.best(network, exclude) ?: return@runCatching null
        val profile = com.example.vpn.connections.BuiltinConnections.resolve(this@RayVpnService, winner.profileId)
            ?: serverRepository.getProfileById(winner.profileId)
        if (profile == null) {
            networkMemory.winners.forget(network, winner.profileId)
            return@runCatching null
        }
        if (com.example.vpn.sidecar.AetherSidecar.handles(profile) ||
            com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(profile) != null) return@runCatching null
        profile to winner
    }.getOrNull()

    /** Network, Psiphon egress region and profile of the running direct Psiphon tunnel (PsiphonRegionBook). */
    @Volatile private var psiphonRegionInUse: Triple<String, String, String>? = null

    @Volatile private var directFallbackAttempt: String? = null
    /** Direct Psiphon failed its start in the current connect attempt (set in [startSidecar], read by its catch). */
    @Volatile private var psiphonDirectStartFailed = false
    /** The current connect may hand a failed direct Psiphon start over to Psiphon over Lantern (set in connectLocked). */
    @Volatile private var psiphonChainAllowed = false

    /** Aether that stopped because WARP data is blocked here, waiting for a verified saved server to relay through. */
    private data class AetherRelayPending(val aether: VlessProfile, val smart: Boolean, val network: String, val at: Long)
    @Volatile private var aetherRelayPending: AetherRelayPending? = null
    @Volatile private var aetherRelayJob: kotlinx.coroutines.Job? = null
    /** The background relay already switched this user Connect to Aether: at most one such switch per Connect. */
    @Volatile private var aetherRelaySwitched = false
    /** The saved server (and how it was reached) the background relay verified, for the Aether start it switches to. */
    private data class AetherRelayExit(val network: String, val profileId: String, val route: com.example.vpn.sidecar.BuiltinExitNode.Route)
    @Volatile private var aetherRelayExit: AetherRelayExit? = null

    /**
     * The saved server [connected] just carried verified traffic after Aether stopped on blocked WARP data: try WARP
     * through it (wireguard-relay, then h2-relay) on its own port in the background. The working connection is left
     * alone unless the relay carries verified HTTPS and this same connection is still up on the same network; only
     * then does the session switch to Aether, which goes straight to the verified relay exit. The switch is an
     * automatic one (under [ConnectionLifecycle.tryBeginSwitch] with the ticket of the intent it began in), not a
     * user Connect: freeze/stall counters and runners-up are kept, and it happens at most once per user Connect.
     * Never through a server frozen on this network; on a metered link the whole run has its own cap.
     */
    private fun retryAetherRelayInBackground(connected: VlessProfile, network: String, attemptId: String) {
        val pending = aetherRelayPending ?: return
        val now = System.currentTimeMillis()
        // WARP's data plane is held on this network (gateway scan failed with Cloudflare filtered): no relay run.
        if (com.example.vpn.connections.WarpDataPlaneHold.blocked(network, now)) {
            aetherRelayPending = null
            XrayLogManager.i("BUILTIN", "aether/relay · background · SKIP · " +
                com.example.vpn.connections.WarpDataPlaneHold.describe(network, now))
            return
        }
        // WARP registration failed on this network recently: the relay would repeat it (WarpRegistrationMemory).
        if (com.example.vpn.connections.WarpRegistrationMemory.blocked(network, now)) {
            aetherRelayPending = null
            XrayLogManager.i("BUILTIN", "aether/relay · background · SKIP · " +
                com.example.vpn.connections.WarpRegistrationMemory.describe(network, now))
            return
        }
        fun frozenHere() = runCatching {
            connected.id in networkMemory.freezes.frozen(com.example.vpn.smart.NetworkKey.current(this@RayVpnService))
        }.getOrDefault(false)
        val frozen = frozenHere()
        if (!com.example.vpn.connections.AetherBackgroundRelay.shouldStart(pending.network, pending.at, network,
                com.example.vpn.sidecar.BuiltinExitNode.eligible(connected), _vpnState.value.isConnected, now,
                connectedFrozen = frozen, alreadySwitched = aetherRelaySwitched)) {
            if (now - pending.at > com.example.vpn.connections.AetherBackgroundRelay.MAX_AGE_MS || pending.network != network ||
                aetherRelaySwitched) aetherRelayPending = null
            if (frozen) XrayLogManager.i("BUILTIN", "aether/relay · background · SKIP · ${connected.name.ifBlank { "saved server" }} froze on this network")
            return
        }
        aetherRelayPending = null
        aetherRelayJob?.cancel()
        // The intent this relay began in: a newer Connect or Disconnect drops its switch.
        val ticket = lifecycle.current()
        val generation = _vpnState.value.networkGeneration
        aetherRelayJob = serviceScope.launch(Dispatchers.IO) {
            fun current() = lifecycle.isCurrent(ticket) &&
                com.example.vpn.connections.AetherBackgroundRelay.isSameRunningAttempt(_vpnState.value, attemptId, generation)
            delay(com.example.vpn.connections.AetherBackgroundRelay.SETTLE_MS)
            if (!current()) return@launch
            val via = connected.name.ifBlank { "saved server" }
            val measured = com.example.vpn.smart.NetworkEnvironment.freshEvidence(
                com.example.vpn.smart.NetworkCapabilityDetector.last, network, System.currentTimeMillis())
            val metered = com.example.vpn.connections.AetherBackgroundRelay.metered(
                measuredMetered = com.example.vpn.connections.ConnectionEngineRunner.linkHints(measured).metered,
                activeMetered = runCatching { connectivityManager?.isActiveNetworkMetered }.getOrNull() == true,
                networkKey = runCatching { com.example.vpn.smart.NetworkKey.current(this@RayVpnService) }.getOrNull())
            // Not before the main tunnel passed a real watchdog check after connect plus a grace period, and on a
            // metered link not while it has failover strikes (AetherBackgroundRelay.gate).
            val gateStartedAt = System.currentTimeMillis()
            var waitLogged = false
            while (true) {
                val t = System.currentTimeMillis()
                val watchdog = failoverManager
                val gate = com.example.vpn.connections.AetherBackgroundRelay.gate(
                    firstRealPassAt = watchdog?.firstRealPassAt() ?: 0L, now = t, metered = metered,
                    strikes = watchdog?.strikeCount() ?: 0,
                    registrationBlocked = com.example.vpn.connections.WarpRegistrationMemory.blocked(network, t),
                    waitedMs = t - gateStartedAt,
                    // The main tunnel's bulk.tun check is still running: the relay waits for its verdict.
                    bulkRunning = trafficCheckJob?.isActive == true,
                    warpDataHeld = com.example.vpn.connections.WarpDataPlaneHold.blocked(network, t))
                when (gate) {
                    com.example.vpn.connections.AetherBackgroundRelay.Gate.START -> break
                    com.example.vpn.connections.AetherBackgroundRelay.Gate.SKIP_WARP_DATA -> {
                        XrayLogManager.i("BUILTIN", "aether/relay · background · SKIP · " +
                            com.example.vpn.connections.WarpDataPlaneHold.describe(network, t))
                        return@launch
                    }
                    com.example.vpn.connections.AetherBackgroundRelay.Gate.SKIP_REGISTRATION -> {
                        XrayLogManager.i("BUILTIN", "aether/relay · background · SKIP · " +
                            com.example.vpn.connections.WarpRegistrationMemory.describe(network, t))
                        return@launch
                    }
                    com.example.vpn.connections.AetherBackgroundRelay.Gate.SKIP_TIMEOUT -> {
                        XrayLogManager.i("BUILTIN", "aether/relay · background · SKIP · $via did not pass a real traffic check " +
                            "without failover strikes within ${com.example.vpn.connections.AetherBackgroundRelay.MAX_GATE_WAIT_MS / 60_000} min")
                        return@launch
                    }
                    com.example.vpn.connections.AetherBackgroundRelay.Gate.WAIT -> if (!waitLogged) {
                        waitLogged = true
                        XrayLogManager.i("BUILTIN", "aether/relay · background · WAIT · until $via passes a real traffic check " +
                            "and its upload/download check has finished " +
                            "(+${com.example.vpn.connections.AetherBackgroundRelay.GRACE_AFTER_REAL_CHECK_MS / 1000}s)" +
                            if (metered) " with no failover strike" else "")
                    }
                }
                delay(com.example.vpn.connections.AetherBackgroundRelay.GATE_POLL_MS)
                if (!current()) return@launch
            }
            // On a metered link the relay stops once the main tunnel gets a failover strike.
            var strikeStop = false
            fun strikesNow(): Boolean = com.example.vpn.connections.AetherBackgroundRelay.mustStop(metered,
                failoverManager?.strikeCount() ?: 0).also { if (it) strikeStop = true }
            val budget = com.example.vpn.connections.AetherBackgroundRelay.budgetMs(metered)
            val deadlineAt = budget?.let { System.currentTimeMillis() + it }
            XrayLogManager.i("BUILTIN", "aether/relay · background · START via $via" +
                (budget?.let { " · metered link, ${it / 1000}s cap" }.orEmpty()))
            val engine = com.example.vpn.sidecar.AetherSidecar
            val executable = com.example.vpn.sidecar.Sidecars.executable(engine) ?: run {
                XrayLogManager.w("BUILTIN", "aether/relay · background · FAIL · the Aether engine is not included in this build; staying on $via")
                return@launch
            }
            val workDir = java.io.File(noBackupFilesDir, "engines/aether-relay-background").apply { deleteRecursively(); mkdirs() }
            var started: com.example.vpn.connections.ConnectionEngineRunner.Started? = null
            try {
                val settings = settingsRepository.getSettings()
                val route = runCatching {
                    networkMemory.routes.forNetwork(network).firstOrNull { it.profileId == connected.id && it.recoveryKey == null }
                }.getOrNull()
                val context = com.example.vpn.sidecar.SidecarContext(workDir, executable,
                    com.example.vpn.sidecar.Sidecars.freeLoopbackPort(), com.example.vpn.sidecar.Sidecars.randomToken(9),
                    com.example.vpn.sidecar.Sidecars.randomToken(), settings.operationalMode)
                val exitRoute = com.example.vpn.sidecar.BuiltinExitNode.Route(route?.endpoint, route?.variantKey)
                val relayRun: suspend () -> com.example.vpn.connections.ConnectionEngineRunner.Started = {
                    kotlinx.coroutines.runInterruptible {
                        com.example.vpn.connections.ConnectionEngineRunner.start(engine, pending.aether, settings, context,
                            relayExitNode = connected.id, relayExitRoute = exitRoute, metered = metered,
                            relayOnly = true, cancelled = { !current() || strikesNow() ||
                                deadlineAt != null && System.currentTimeMillis() > deadlineAt })
                    }
                }
                val result = if (budget != null) kotlinx.coroutines.withTimeout(budget) { relayRun() } else relayRun()
                started = result
                val method = com.example.vpn.engine.ProfileExtras.read(result.profile).optString("carrier") + "-relay"
                val sameNetwork = runCatching { com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService) }.getOrNull() == network
                if (com.example.vpn.connections.AetherBackgroundRelay.shouldSwitch(relayVerified = result.check.traffic,
                        stillConnected = current(), sameNetwork = sameNetwork, connectedFrozen = frozenHere(),
                        alreadySwitched = aetherRelaySwitched)) {
                    // An automatic switch inside the service, never a user Connect: one switch at a time, with the
                    // intent it began in, counters and runners-up kept.
                    if (!lifecycle.tryBeginSwitch()) {
                        XrayLogManager.i("BUILTIN", "aether/relay · background · PASS · $method via $via verified, but another switch is under way; staying")
                        return@launch
                    }
                    if (!current()) {
                        lifecycle.endSwitch()
                        XrayLogManager.i("BUILTIN", "aether/relay · background · PASS · $method via $via verified, but the connection changed; staying")
                        return@launch
                    }
                    XrayLogManager.i("BUILTIN", "aether/relay · background · PASS · $method via $via carried verified HTTPS; switching to Aether")
                    result.running.stop()
                    started = null
                    aetherRelaySwitched = true
                    aetherRelayExit = AetherRelayExit(network, connected.id, exitRoute)
                    mutateState { com.example.data.model.ConnectionVerification.onSwitching(it) }
                    connectJob = serviceScope.launch(AttemptTicket(ticket)) {
                        try {
                            connect(pending.aether, smart = pending.smart, ticket = ticket)
                        } finally {
                            aetherRelayExit = null
                            lifecycle.endSwitch()
                        }
                    }
                } else {
                    XrayLogManager.i("BUILTIN", "aether/relay · background · PASS · $method via $via verified, but the connection changed; staying")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.TimeoutCancellationException || deadlineAt != null &&
                    System.currentTimeMillis() >= deadlineAt && (e is kotlinx.coroutines.CancellationException || e is InterruptedException)) {
                    XrayLogManager.w("BUILTIN", "aether/relay · background · FAIL · ${(budget ?: 0) / 1000}s cap on this metered link used; staying on $via")
                    return@launch
                }
                val reason = when (e) {
                    is kotlinx.coroutines.CancellationException, is InterruptedException ->
                        if (strikeStop) "the main tunnel failed a traffic check on this metered link" else "the connection changed"
                    is com.example.vpn.connections.ConnectionEngineRunner.NoVerifiedPath -> e.message ?: "no verified relay"
                    else -> e.javaClass.simpleName
                }
                XrayLogManager.w("BUILTIN", "aether/relay · background · FAIL · $reason; staying on $via")
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                started?.running?.stop()
                workDir.deleteRecursively()
            }
        }
    }

    /**
     * Aether carried a real request on [network] before: in this session's engine ledger, as a remembered route,
     * or as an automatic method verified here within the last hours ([com.example.vpn.connections.BuiltinMethodMemory]).
     */
    private fun aetherVerifiedOn(network: String, aether: VlessProfile, settings: AppSettings): Boolean =
        com.example.vpn.engine.registry.EngineLedger.shared.verifiedHere(com.example.vpn.sidecar.AetherSidecar.ID, network) ||
            runCatching { networkMemory.routes.forNetwork(network).any { it.profileId == aether.id } }.getOrDefault(false) ||
            runCatching { com.example.vpn.connections.ConnectionEngineRunner.aetherVerifiedHere(network, aether, settings.dnsServer) }
                .getOrDefault(false)

    /** Explicit session consent only; stays in the service when the Connections page is closed. */
    private fun fallbackFromDirect(profile: VlessProfile): Boolean {
        // Never a new Connect for an attempt the user already stopped.
        if (!StopFence.admits()) return false
        val state = _vpnState.value
        val attempt = state.attemptId ?: return false
        if (state.status !in setOf(ConnectionStatus.FAILED, ConnectionStatus.DEGRADED) ||
            state.activeProfile?.id != profile.id || directFallbackAttempt == attempt ||
            !com.example.vpn.sidecar.ServerlessSidecar.handles(profile) ||
            !com.example.vpn.engine.ProfileExtras.read(profile).optBoolean("vpnFallback")) return false
        val warp = com.example.vpn.connections.BuiltinConnections.resolve(this, "maximus-aether") ?: return false
        directFallbackAttempt = attempt
        showForegroundNotification("Direct access failed; switching to the enabled WARP fallback")
        VpnController.startVpn(this, warp, exact = true)
        return true
    }

    private fun isLiteralIp(address: String): Boolean {
        val a = address.trim().removePrefix("[").removeSuffix("]")
        return a.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) ||
            (a.contains(':') && a.matches(Regex("[0-9a-fA-F:.]+")))
    }

    /**
     * The address each server name last resolved to in this service (resolveEndpoint), lower-case name to IP. Only
     * what connects already looked up: no extra DNS query is made for the network comparison ([serverNetworkKeys]).
     */
    private val resolvedHosts = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [com.example.vpn.smart.HostPrefix.keys] of [p]: its IP prefix, or its name plus the prefix it last resolved to. */
    private fun serverNetworkKeys(p: VlessProfile): Set<String> {
        val host = p.address.trim().removePrefix("[").removeSuffix("]").lowercase()
        return com.example.vpn.smart.HostPrefix.keys(host, resolvedHosts[host])
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
                // A private lookup (GOD MODE, Ghost Rider) never asks the network's DNS, so an answer over DoH says
                // nothing about it; what earlier lookups on this network learned still does.
                val systemAsked = !privateServerLookup
                dnsPoisoned = (systemAsked && resolved.viaDoh) || dnsProfile?.tamperedNames?.isNotEmpty() == true ||
                    EndpointResolver.isBlockedAnswer(java.net.InetAddress.getByName(resolved.address))
                if (resolved.viaDoh && systemAsked) {
                    XrayLogManager.w("VPN", "[DIAGNOSTICS] The network's DNS gave a blocked answer for the server; " +
                        "resolved it over DNS-over-HTTPS instead")
                } else if (resolved.viaDoh) {
                    XrayLogManager.i("VPN", "[DIAGNOSTICS] Server name looked up privately over DNS-over-HTTPS (the network's DNS was not asked)")
                }
                val usesTls = profile.security.equals("tls", true) || profile.security.equals("reality", true)
                if (!resolved.viaDoh && EndpointResolver.isBlockedAnswer(java.net.InetAddress.getByName(resolved.address))) {
                    XrayLogManager.w("VPN", "[DIAGNOSTICS] The server name resolves to a private address, " +
                        "which filtering networks use for blocked sites; DNS-over-HTTPS was unreachable")
                    // Without TLS the login (UUID, password) would go to whoever holds that address.
                    check(usesTls) { "the network's DNS points the server at a filtering address" }
                }
                resolvedHosts[hostName.lowercase()] = resolved.address
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
    private suspend fun startSidecar(engine: com.example.vpn.sidecar.SidecarEngine, profile: VlessProfile, settings: AppSettings,
                                     /** Automatic Aether's verified fallback on this network, when it may hand over to it. */
                                     aetherFallback: com.example.vpn.smart.VerifiedWinnerBook.Winner? = null): VlessProfile =
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            // Cancellation interrupts native readiness waits as well as the HTTPS test.
            // A plain withContext(IO) does not interrupt these blocking operations.
            sidecar?.stop()
            sidecar = null
            // The recovery test just ran this engine and it carried real requests: take it over as it is.
            com.example.vpn.sidecar.EngineHandoff.take(profile.id, engine.id, settings.operationalMode)?.let { kept ->
                sidecar = kept.engine
                XrayLogManager.i("VPN", "Engine ${engine.id} kept running from the recovery test; no second start-up")
                watchSidecar(engine, kept.engine)
                registerWarpThroughCarrier(engine.id, kept.engine, kept.context, kept.launch, settings)
                return@runInterruptible com.example.vpn.sidecar.SidecarChain.xrayProfile(profile, kept.context, kept.launch)
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
            val managed = engine.id in setOf(com.example.vpn.sidecar.AetherSidecar.ID, com.example.vpn.sidecar.ServerlessSidecar.ID)
            if (managed) showForegroundNotification("Testing the ${engine.id} connection...")
            val connectionOwner = connectJob
            // Network measurements are admissible only for the current physical link
            // and for a short freshness window. Never apply them to a different SIM/Wi-Fi.
            val measured = if (engine.id == com.example.vpn.sidecar.AetherSidecar.ID)
                com.example.vpn.smart.NetworkEnvironment.freshEvidence(
                    com.example.vpn.smart.NetworkCapabilityDetector.last,
                    com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService),
                    System.currentTimeMillis())
            else null
            // Same reading for Connect, the Connections page test and the LAB probe (ConnectionEngineRunner.linkHints).
            val hints = com.example.vpn.connections.ConnectionEngineRunner.linkHints(measured)
            val ipv4Only = hints.ipv4Only
            // Build 167: automatic Aether on a network with no fresh reading. One cheap Cloudflare TLS handshake on the
            // physical link first (≤ ~6 s); a filtered result gets the fixed filtered-link budgets and WireGuard first.
            // Build 171: also for a carrier the user picked (MASQUE HTTP/2) while WARP's data plane is held here, so its
            // run can stop at once on a still-filtered link instead of a 180 s scan.
            val holdHere = engine.id == com.example.vpn.sidecar.AetherSidecar.ID && runCatching {
                com.example.vpn.connections.WarpDataPlaneHold.blocked(com.example.vpn.connections.BuiltinMethodMemory.network())
            }.getOrDefault(false)
            val quickCloudflare = if (engine.id == com.example.vpn.sidecar.AetherSidecar.ID && measured == null &&
                (holdHere || com.example.vpn.engine.ProfileExtras.read(profile).optString("carrier", "auto") == "auto")) runCatching {
                com.example.vpn.smart.NetworkCapabilityDetector.quickCloudflareTls(this@RayVpnService)
            }.getOrNull().also { cf ->
                com.example.vpn.diagnostics.events.EventLog.event("aether.cf_quick", attributes = mapOf(
                    "result" to when (cf) { true -> "reachable"; false -> "filtered"; null -> "not_measured" }))
            } else null
            val preferWireGuard = hints.cloudflareFiltered || quickCloudflare == false
            // A saved server that carried verified traffic on this same network: Aether can carry WARP through it
            // when the carrier drops WARP's own data (ConnectionEngineRunner.relayed).
            // The exit hop dials it the way it verified: the recipe's pinned address (e.g. a clean CDN address) and
            // its stealth alternate, never a fresh lookup of the name that may land on a filtered address.
            val relayNetwork = if (engine.id == com.example.vpn.sidecar.AetherSidecar.ID)
                runCatching { com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService) }.getOrNull() else null
            // The background relay switch hands over the exit it just verified; Aether uses exactly that one here.
            val verifiedExit = aetherRelayExit?.takeIf { relayNetwork != null && it.network == relayNetwork }
            val relayRoute = if (verifiedExit == null && relayNetwork != null) runCatching {
                networkMemory.routes.forNetwork(relayNetwork)
                    .firstOrNull { r -> r.recoveryKey == null &&
                        com.example.vpn.sidecar.BuiltinExitNode.loadProfile?.invoke(r.profileId)
                            ?.takeIf { it.effectiveFingerprint == r.profileFingerprint }
                            ?.let(com.example.vpn.sidecar.BuiltinExitNode::eligible) == true }
            }.getOrNull() else null
            val relayExitNode = verifiedExit?.profileId ?: relayRoute?.profileId
            val relayExitRoute = verifiedExit?.route
                ?: com.example.vpn.sidecar.BuiltinExitNode.Route(relayRoute?.endpoint, relayRoute?.variantKey)
            val metered = hints.metered || runCatching { connectivityManager?.isActiveNetworkMetered }.getOrNull() == true
            // cf=0 as Smart Connect reads it (SmartConnectPlan.cloudflareBlocked): a gateway-scan failure then holds WARP's
            // data plane on this network (WarpDataPlaneHold). The power button's own Aether run is manual: never refused.
            val cfBlocked = preferWireGuard || relayNetwork != null && runCatching {
                com.example.vpn.smart.SmartConnectPlan.cloudflareBlocked(com.example.vpn.smart.NetworkCapabilityDetector.last,
                    relayNetwork, System.currentTimeMillis())
            }.getOrDefault(false)
            val started = if (managed) com.example.vpn.connections.ConnectionEngineRunner.start(engine, profile, settings, context,
                ipv4Only = ipv4Only, preferWireGuard = preferWireGuard, relayExitNode = relayExitNode,
                relayExitRoute = relayExitRoute, metered = metered, manual = exactSession, cloudflareBlocked = cfBlocked,
                lanternAvailable = engine.id == com.example.vpn.sidecar.AetherSidecar.ID &&
                    runCatching { com.example.vpn.sidecar.LanternRelayHop.available() }.getOrDefault(false),
                // Automatic: the path it hands over to. The user's own pick: named in its fast-fail message only.
                verifiedAlternative = aetherFallback?.label() ?: if (engine.id == com.example.vpn.sidecar.AetherSidecar.ID &&
                    exactSession && relayNetwork != null) runCatching {
                    networkMemory.winners.best(relayNetwork, setOf(profile.id))?.label()
                }.getOrNull() else null,
                handOver = aetherFallback != null,
                cancelled = { connectionOwner?.isActive == false }) else null
            var runningProfile = started?.profile ?: profile
            var engineContext = started?.context ?: context
            if (engine.id in com.example.vpn.dns.DnsTunnelResolverOrder.ENGINES) {
                runningProfile = preferVerifiedResolvers(engine, runningProfile)
            }
            if (engine.id == com.example.vpn.sidecar.CottenDnsSidecar.ID) {
                runningProfile = prioritizeDnsTunnelResolvers(engine, runningProfile)
            }
            var launch = started?.launch ?: engine.prepare(runningProfile, settings, engineContext)
            showForegroundNotification("Starting the ${engine.id} engine...")

            fun launchProcess() = com.example.vpn.sidecar.EngineReaper.track(
                engine.startPrepared(runningProfile, settings, launch, engineContext)
                    ?: com.example.vpn.sidecar.SidecarProcess.start(engine.id, launch, workDir, engineContext.socksPort)
            )
            psiphonRegionInUse = null
            // Direct Psiphon on public servers: the egress region by measured quality, inside a bounded budget
            // (PsiphonRegionRun); a region the user fixed is kept and stops at once when Psiphon lists it unavailable.
            val psiphonRegional = started == null && engine.id == com.example.vpn.sidecar.PsiphonSidecar.ID &&
                com.example.vpn.sidecar.PsiphonSidecar.privateId(profile).isEmpty()
            val regionNetwork = if (psiphonRegional) runCatching { com.example.vpn.smart.NetworkIdentity.current(this@RayVpnService) }.getOrNull() else null
            val userRegion = if (psiphonRegional) com.example.vpn.sidecar.PsiphonSidecar.region(profile) else ""
            class RegionStart(val running: com.example.vpn.sidecar.RunningEngine) : com.example.vpn.sidecar.PsiphonRegionRun.Start {
                private val notices get() = com.example.vpn.sidecar.EngineReaper.underlying(running) as? com.example.vpn.sidecar.SidecarProcess
                override fun await(timeoutMs: Long, giveUp: () -> Boolean) = running.awaitReadyUnless(timeoutMs, giveUp)
                override fun available() = notices?.psiphonAvailableRegions()
                override fun stage() = notices?.psiphonStage() ?: 0
                override fun connectedRegion() = notices?.psiphonConnectedRegion()
                override fun stop() {
                    running.stop()
                    if (sidecar === running) sidecar = null
                }
            }
            var regionOutcome: com.example.vpn.sidecar.PsiphonRegionRun.Outcome<RegionStart>? = null
            var process: com.example.vpn.sidecar.RunningEngine
            var ready: Boolean
            if (psiphonRegional) {
                val remembered = regionNetwork?.let { net -> runCatching { networkMemory.psiphonRegions.ranked(net) }.getOrNull() }.orEmpty()
                val total = if (userRegion.isEmpty()) com.example.vpn.sidecar.PsiphonRegionPlan.TOTAL_AUTO_MS else launch.readyTimeoutMs
                var firstStep = true
                val outcome = com.example.vpn.sidecar.PsiphonRegionRun().run(userRegion, remembered, total, launch = { step ->
                    val stepProfile = if (step.region == userRegion) profile
                        else com.example.vpn.engine.ProfileExtras.with(profile, com.example.vpn.sidecar.PsiphonSidecar.KEY_REGION, step.region)
                    if (!firstStep || stepProfile !== profile) {
                        runningProfile = stepProfile
                        // A stopped process can still be releasing its listener. Never reuse its port.
                        if (!firstStep) engineContext = context.copy(socksPort = com.example.vpn.sidecar.Sidecars.freeLoopbackPort())
                        launch = engine.prepare(runningProfile, settings, engineContext)
                    }
                    firstStep = false
                    val label = step.region.ifEmpty { "any" }
                    XrayLogManager.i("VPN", "Psiphon: region $label for up to ${step.budgetMs / 1000}s (${step.why}).")
                    com.example.vpn.diagnostics.events.EventLog.event("psiphon.region", attributes = mapOf(
                        "region" to label, "budget.s" to (step.budgetMs / 1000).toString(), "why" to step.why), result = "START")
                    if (!step.any) showForegroundNotification("Psiphon: trying region $label…")
                    RegionStart(launchProcess()).also { sidecar = it.running }
                }, cancelled = { connectionOwner?.isActive == false || !StopFence.admits() })
                regionOutcome = outcome
                process = requireNotNull(outcome.ready ?: outcome.last).running
                ready = outcome.ready != null
                // What each concrete region did here steers the next start (PsiphonRegionBook).
                if (regionNetwork != null) outcome.attempts.filter { !it.ready && it.step.region.isNotEmpty() }.forEach {
                    runCatching { networkMemory.psiphonRegions.recordFailure(regionNetwork, it.step.region) }
                }
                outcome.winner?.let { w ->
                    val notices = com.example.vpn.sidecar.EngineReaper.underlying(process) as? com.example.vpn.sidecar.SidecarProcess
                    // ConnectedServerRegion can trail the Tunnels notice by a moment.
                    var region = w.connectedRegion ?: notices?.psiphonConnectedRegion()
                    var waited = 0
                    while (region == null && notices != null && waited < 600) { Thread.sleep(50); waited += 50; region = notices.psiphonConnectedRegion() }
                    val code = region ?: w.step.region
                    com.example.vpn.diagnostics.events.EventLog.event("psiphon.region", attributes = mapOf(
                        "region" to code.ifEmpty { "unknown" }, "step" to w.step.region.ifEmpty { "any" },
                        "ready.ms" to w.elapsedMs.toString(), "starts" to outcome.attempts.size.toString()), result = "PASS")
                    if (code.isNotEmpty() && regionNetwork != null) {
                        runCatching { networkMemory.psiphonRegions.recordReady(regionNetwork, code, w.elapsedMs) }
                        psiphonRegionInUse = Triple(regionNetwork, code, profile.id)
                    }
                }
            } else {
                process = started?.running ?: launchProcess()
                sidecar = process
                ready = started != null || process.awaitReady(launch.readyTimeoutMs)
            }
            // The automatic-region run already used its whole budget (and the chain over Lantern may follow); a region
            // Psiphon lists as unavailable will not appear over TCP either. A fixed, available region keeps its retry.
            val regionRun = regionOutcome
            val regionAllowsRetry = regionRun == null || (userRegion.isNotEmpty() && regionRun.failure == null)
            val tcpRetry = if (!ready && engine.id == com.example.vpn.sidecar.PsiphonSidecar.ID && regionAllowsRetry &&
                connectionOwner?.isActive != false) com.example.vpn.sidecar.PsiphonSidecar.tcpStartupRetry(profile) else null
            // What the first (UDP+TCP) start dialed. When it already dialed enough TCP servers with none connecting and
            // the chain over Lantern may take over, the TCP-only retry would repeat those protocols for another 120 s.
            val firstDials = if (tcpRetry != null) (com.example.vpn.sidecar.EngineReaper.underlying(process) as? com.example.vpn.sidecar.SidecarProcess)?.psiphonDials() else null
            val retry = tcpRetry?.takeUnless {
                firstDials != null && com.example.vpn.sidecar.PsiphonSidecar.skipTcpRetry(firstDials,
                    chainAllowed = psiphonChainAllowed && com.example.vpn.connections.PsiphonChainMemory.isDirect(profile))
            }
            if (tcpRetry != null && retry == null) {
                XrayLogManager.w("VPN", "Psiphon: the first start already dialed ${firstDials?.tcp} TCP servers without a " +
                    "connection; skipping the TCP-only retry for Psiphon over Lantern.")
                com.example.vpn.diagnostics.events.EventLog.event("psiphon.tcp_retry_skipped", attributes = mapOf(
                    "dials" to firstDials?.summary().orEmpty(), "next" to "psiphon over lantern"))
            }
            // What the first (UDP+TCP) Psiphon start saw, before the TCP-only retry replaces the process.
            val firstNotices = if (retry != null) (com.example.vpn.sidecar.EngineReaper.underlying(process) as? com.example.vpn.sidecar.SidecarProcess)?.psiphonNotices() else null
            if (retry != null) {
                process.stop()
                sidecar = null
                XrayLogManager.w("VPN", "Psiphon did not establish a tunnel. Retrying once with TCP transports.")
                showForegroundNotification("Retrying Psiphon over TCP…")
                runningProfile = retry
                // A stopped process can still be releasing its listener. Never reuse its port.
                engineContext = context.copy(socksPort = com.example.vpn.sidecar.Sidecars.freeLoopbackPort())
                launch = engine.prepare(runningProfile, settings, engineContext)
                process = launchProcess()
                sidecar = process
                ready = process.awaitReady(launch.readyTimeoutMs)
            }
            if (!ready) {
                process.stop()
                sidecar = null
                val hint = regionRun?.failure ?: regionRun?.takeIf { userRegion.isEmpty() }?.let { o ->
                    "Psiphon brought no tunnel up in ${o.attempts.size} start(s) (regions: " +
                        o.attempts.joinToString(", ") { it.step.region.ifEmpty { "any" } } + ") within " +
                        "${o.attempts.sumOf { it.elapsedMs } / 1000}s: " +
                        (process.startupFailure ?: "no readiness signal") }
                    ?: process.startupFailure ?: "No readiness signal; inspect redacted engine logs"
                com.example.vpn.engine.registry.EngineLedger.shared.failed(engine.id, ledgerNetwork(), "startup failed: $hint")
                val psiphonDirect = engine.id == com.example.vpn.sidecar.PsiphonSidecar.ID &&
                    com.example.vpn.connections.PsiphonChainMemory.isDirect(profile)
                // Next automatic connect on this network starts with Psiphon over Lantern (PsiphonChainMemory).
                if (psiphonDirect) {
                    com.example.vpn.connections.PsiphonChainMemory.recordDirectFailure(ledgerNetwork())
                    psiphonDirectStartFailed = true
                }
                val notices = (com.example.vpn.sidecar.EngineReaper.underlying(process) as? com.example.vpn.sidecar.SidecarProcess)?.psiphonNotices()
                com.example.vpn.diagnostics.events.EventLog.event("engine.startup.failure",
                    com.example.vpn.diagnostics.events.Severity.ERROR,
                    attributes = buildMap {
                        put("engine.name", engine.id)
                        put("reason", hint)
                        // The last ~20 Psiphon notices (type + whitelisted fields), so a report shows why.
                        notices?.let { put("psiphon.notices", it) }
                        firstNotices?.let { put("psiphon.notices.first_try", it) }
                        // Counts and protocol names only: what was dialed and how far it got (StartupDials.verdict).
                        regionRun?.let { o ->
                            put("psiphon.regions.tried", o.attempts.joinToString(",") { it.step.region.ifEmpty { "any" } })
                            o.attempts.lastOrNull { it.available != null }?.available?.let { put("psiphon.regions.available", it.sorted().joinToString(",")) }
                        }
                        if (engine.id == com.example.vpn.sidecar.PsiphonSidecar.ID) {
                            (com.example.vpn.sidecar.EngineReaper.underlying(process) as? com.example.vpn.sidecar.SidecarProcess)?.psiphonDials()?.let {
                                put("psiphon.dials", it.summary())
                                put("psiphon.verdict", it.verdict)
                            }
                            if (retry != null) firstDials?.let { put("psiphon.dials.first_try", it.summary()) }
                        }
                    },
                    stage = com.example.vpn.diagnostics.FailureStage.ENGINE_START_FAILED, result = "FAIL")
                error("The ${engine.id} engine did not start: $hint")
            }
            com.example.vpn.engine.registry.EngineLedger.shared.started(engine.id, ledgerNetwork())
            if (engine.id == com.example.vpn.sidecar.PsiphonSidecar.ID && com.example.vpn.connections.PsiphonChainMemory.isDirect(profile))
                com.example.vpn.connections.PsiphonChainMemory.clear(ledgerNetwork())
            XrayLogManager.i("VPN", "Engine ${engine.id} is running on a local port")
            watchSidecar(engine, process)
            registerWarpThroughCarrier(engine.id, process, engineContext, launch, settings)
            return@runInterruptible com.example.vpn.sidecar.SidecarChain.xrayProfile(runningProfile, engineContext, launch)
    }

    /**
     * Psiphon or Tor carries this connection: once it is verified, register the WireGuard WARP identities
     * through it when none is saved, so a later session on a Cloudflare-filtered network (cf=0) can start
     * WireGuard straight away (AetherIdentityRegistrar). Background, once per 6 h at most, abandoned as soon as
     * this engine stops being the connection's engine.
     */
    private fun registerWarpThroughCarrier(engineId: String, watched: com.example.vpn.sidecar.RunningEngine,
                                           context: com.example.vpn.sidecar.SidecarContext,
                                           launch: com.example.vpn.sidecar.SidecarLaunch, settings: AppSettings) {
        if (engineId !in com.example.vpn.sidecar.AetherIdentityRegistrar.CARRIER_ENGINES) return
        val upstream = runCatching {
            com.example.vpn.sidecar.AetherIdentityRegistrar.upstream(context.socksPort,
                context.socksUser.takeIf { launch.socksAuth }, context.socksPass.takeIf { launch.socksAuth })
        }.getOrNull() ?: return
        serviceScope.launch(Dispatchers.IO) {
            fun current() = sidecar === watched && watched.isAlive
            var waited = 0L
            while (isActive && current() && _vpnState.value.status != ConnectionStatus.CONNECTED && waited < 300_000L) {
                delay(2_000); waited += 2_000
            }
            if (!isActive || !current() || _vpnState.value.status != ConnectionStatus.CONNECTED) return@launch
            delay(com.example.vpn.sidecar.AetherIdentityRegistrar.SETTLE_MS)
            if (!current() || _vpnState.value.status != ConnectionStatus.CONNECTED) return@launch
            try {
                com.example.vpn.sidecar.AetherIdentityRegistrar.lock.withLock {
                    kotlinx.coroutines.runInterruptible {
                        com.example.vpn.sidecar.AetherIdentityRegistrar.registerThroughCarrier(engineId, upstream, settings,
                            stillValid = { current() && _vpnState.value.status == ConnectionStatus.CONNECTED })
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                XrayLogManager.w("BUILTIN", "aether/keys · registration through $engineId could not run: ${e.javaClass.simpleName}")
            }
        }
    }
    /**
     * Before CottenDNS starts: sample up to four configured resolvers in parallel on the underlying link,
     * from this app, which like the engine is outside the VPN. The log then says which resolvers this phone
     * reaches; the engine itself reports an unreachable one only as "UPLOAD_MTU value=0". When none can even
     * be sent to, the connect stops here instead of after the engine's 45 s start-up limit.
     */
    private fun prioritizeDnsTunnelResolvers(engine: com.example.vpn.sidecar.SidecarEngine, profile: VlessProfile): VlessProfile {
        val s = runCatching { com.example.vpn.sidecar.CottenDnsSidecar.settings(profile) }.getOrNull() ?: return profile
        // The question goes over UDP/53: it says nothing about a TCP, DoT or DoH resolver.
        if (s.transport !in setOf("auto", "udp")) return profile
        val resolvers = s.resolvers.take(4)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(resolvers.size)
        val outcomes = try {
            pool.invokeAll(resolvers.map { r ->
                java.util.concurrent.Callable {
                    List(3) { com.example.vpn.sidecar.ResolverReach.check(r, s.domain, timeoutMs = 850) }
                }
            }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        resolvers.zip(outcomes).forEach { (r, samples) ->
            val replies = samples.count { it is com.example.vpn.sidecar.ResolverReach.Outcome.Answered }
            XrayLogManager.i("DNS", "Resolver $r replied to $replies/${samples.size} matching DNS queries; tunnel traffic not tested yet.")
        }
        if (s.transport == "udp" && s.resolvers.size == resolvers.size &&
            outcomes.flatten().all { it is com.example.vpn.sidecar.ResolverReach.Outcome.Blocked }) {
            com.example.vpn.engine.registry.EngineLedger.shared.failed(engine.id, ledgerNetwork(), "resolvers unreachable")
            error("None of this config's DNS resolvers can be reached from this phone " +
                "(${(outcomes.first().first() as com.example.vpn.sidecar.ResolverReach.Outcome.Blocked).reason}), so the ${engine.id} engine cannot start")
        }
        val ordered = com.example.vpn.sidecar.ResolverReach.prioritize(s.resolvers, resolvers.zip(outcomes))
        return if (ordered == s.resolvers) profile
            else com.example.vpn.sidecar.CottenDnsSidecar.withSettings(profile, s.copy(resolvers = ordered))
    }

    private val resolverScanRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Puts the resolvers the scanner verified on this network first (config-based profiles: reorder only;
     * profiles left on the engine's default resolver: verified ones are added in front), and starts a
     * background rescan when this network has no fresh scan. The saved profile is never changed.
     */
    private fun preferVerifiedResolvers(engine: com.example.vpn.sidecar.SidecarEngine, profile: VlessProfile): VlessProfile {
        val network = ledgerNetwork()
        val pool = com.example.RayApplication.instance.resolverPool
        val applied = com.example.vpn.dns.DnsTunnelResolverOrder.apply(engine.id, profile, pool.verified(network))
        if (applied.changed) XrayLogManager.i("DNS", "Resolvers verified on this network go first" +
            if (applied.configFree) " (added to the default resolver)" else " (config order otherwise kept)")
        if (pool.needsScan(network) && resolverScanRunning.compareAndSet(false, true)) {
            val configured = runCatching {
                when (engine.id) {
                    com.example.vpn.sidecar.CottenDnsSidecar.ID -> com.example.vpn.sidecar.CottenDnsSidecar.settings(profile).resolvers
                    com.example.vpn.sidecar.SlipstreamSidecar.ID -> com.example.vpn.sidecar.SlipstreamSidecar.settings(profile).resolvers
                    else -> com.example.vpn.sidecar.DnsttSidecar.settings(profile).resolvers
                        .filter { it.kind != com.example.vpn.sidecar.DnsttSidecar.ResolverKind.DOH }.map { it.address }
                }.map({ com.example.vpn.dns.VerifiedResolverPool.hostOf(it) })
            }.getOrDefault(emptyList())
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val bundled = (0 until 4).flatMap { day ->
                        com.example.vpn.BundledResolvers.labSample(network, System.currentTimeMillis() + day * 86_400_000L)
                    }
                    val ranked = com.example.vpn.dns.ResolverScanner.scan(
                        com.example.vpn.dns.ResolverScanner.candidates(configured, bundled))
                    // A scan that outlived a network change describes the old network: drop it.
                    if (ledgerNetwork() == network) {
                        pool.record(network, ranked)
                        XrayLogManager.i("DNS", "Resolver scan: ${ranked.size} honest resolvers answered on this network" +
                            if (ranked.isEmpty()) "" else ranked.take(3).joinToString(prefix = " (best: ", postfix = ")") { "${it.ip} ${it.score}" })
                    }
                } catch (t: Throwable) {
                    XrayLogManager.w("DNS", "Resolver scan stopped: ${t.message ?: t.javaClass.simpleName}")
                } finally {
                    resolverScanRunning.set(false)
                }
            }
        }
        return applied.profile
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
                val process = com.example.vpn.sidecar.EngineReaper.track(engine.startPrepared(hopProfile, settings, launch, context)
                    ?: com.example.vpn.sidecar.SidecarProcess.start(engine.id, launch, context.workDir, context.socksPort))
                try {
                    check(process.awaitReady(launch.readyTimeoutMs)) {
                        process.startupFailure ?: "The ${engine.id} engine did not start"
                    }
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
            running.entryProfile,
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
        // A failed exemption leaves engine sockets inside the blocking interface.
        // Do not turn that local setup failure into a failed network measurement.
        val open = createBlockingInterface(excludeApp = true)
        val previous = vpnInterface
        vpnInterface = open
        runCatching { previous?.close() }
        try {
            kotlinx.coroutines.runInterruptible { test() }
        } finally {
            if (vpnInterface === open) {
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
                appOutsideVpn = excludeApp
                // Same per-app rules as the tunnel, so apps left outside keep working while it reconnects.
                com.example.vpn.routing.SplitTunnelStore.apply(this@RayVpnService, this, excludeSelf = excludeApp, interfaceName = "guard")
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

    /** The server address for the record-slicing bridge, looked up outside this VPN (as for the core itself). */
    private fun bridgeResolve(host: String): List<java.net.InetAddress> {
        val name = host.trim().removePrefix("[").removeSuffix("]")
        if (isLiteralIp(name)) return listOf(java.net.InetAddress.getByName(name))
        resolvedHosts[name.lowercase()]?.let { return listOf(java.net.InetAddress.getByName(it)) }
        val pinned = resolveEndpoint(VlessProfile(name = name, address = name, port = 443, uuid = ""))
        return listOf(java.net.InetAddress.getByName(pinned.address))
    }

    /**
     * TCP_MAXSEG on a not yet connected socket (Uplink Shield in the record-slicing bridge). Android 8+ only
     * (Os.setsockoptInt); older phones keep the bridge's paced first 16 KB, and Xray's own sockopt still applies
     * when no bridge is used.
     */
    private fun setSocketMss(socket: Socket, mss: Int): Boolean = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) false else try {
        if (!socket.isBound) socket.bind(java.net.InetSocketAddress(0))
        android.os.ParcelFileDescriptor.fromSocket(socket).use { pfd ->
            android.system.Os.setsockoptInt(pfd.fileDescriptor, android.system.OsConstants.IPPROTO_TCP, TCP_MAXSEG, mss)
        }
        true
    } catch (e: Exception) {
        XrayLogManager.w("STEALTH", "Uplink Shield: TCP_MAXSEG $mss not applied (${e.javaClass.simpleName})")
        false
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
        notificationSince = startTime
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
                // Degraded and failed states keep their own message; only a working tunnel shows live speeds.
                if (_vpnState.value.isConnected) refreshConnectedNotification()
            }
        }

        pingJob?.cancel()
        val attemptId = _vpnState.value.attemptId
        val passive = if (activeEngine is NativeTunVpnEngine) com.example.vpn.smart.PassiveHealth() else null
        pingJob = serviceScope.launch {
            var okStreak = 0
            // The early-degrade strike is counted once per connection.
            var earlyStrikeCounted = false
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
                        com.example.data.model.ConnectionVerification.afterCheck(it, attemptId, true, sample.latencyMs, System.currentTimeMillis(), path =
                            if (engineEndpoint == null) com.example.vpn.verify.ProbePath.TUN else com.example.vpn.verify.ProbePath.ENGINE_PROXY)
                            .let { s -> if (s.attemptId == attemptId) s.copy(exitCountryCode = sample.country, exitIp = sample.ip ?: s.exitIp) else s }
                    }
                    if (!wasConnected && _vpnState.value.isConnected && _vpnState.value.attemptId == attemptId) {
                        XrayLogManager.i("VPN", "Traffic now goes through the tunnel (${sample.latencyMs} ms): CONNECTED.")
                        showConnectedNotification()
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
                        // DEGRADED on timeouts within a minute of connect: a failover strike (StrikeWindow.earlyDegradeStrike).
                        if (!earlyStrikeCounted && com.example.vpn.smart.StrikeWindow.earlyDegradeStrike(stage,
                                _vpnState.value.lastConnectedTime, System.currentTimeMillis())) {
                            earlyStrikeCounted = true
                            failoverManager?.reportEarlyDegrade("${stage.name} after ${(System.currentTimeMillis() -
                                (_vpnState.value.lastConnectedTime ?: 0L)) / 1000}s")
                        }
                        showForegroundNotification("Connection degraded (${stage.name})")
                        if (fallbackFromDirect(profile)) return@launch
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
                perStepTimeoutMs = when {
                    state.activeProfile?.id == com.example.vpn.connections.BuiltinConnections.AETHER ||
                        state.activeProfile?.id == com.example.vpn.connections.BuiltinConnections.DIRECT -> 28_000L
                    slowTunnel() -> SLOW_VERIFY_STEP_MS
                    else -> 11_000L
                },
                perConfigTimeoutMs = if (slowTunnel()) SLOW_VERIFY_CONFIG_MS else 40_000L
            )
        )
        val step = if (engineEndpoint == null) com.example.vpn.connectivity.ProbeStep.HTTP_THROUGH_TUNNEL
            else com.example.vpn.connectivity.ProbeStep.HTTP_THROUGH_ENGINE_PROXY
        val report = engine.run(
            com.example.vpn.connectivity.MultiProbeHealthEngine.Subject(
                sessionId = state.sessionId ?: attemptId ?: "none",
                configFingerprint = state.activeProfile?.effectiveFingerprint,
                engine = state.activeEngineName,
                networkProfileId = com.example.vpn.smart.NetworkCapabilityDetector.last?.key()
            ),
            listOf(com.example.vpn.connectivity.MultiProbeHealthEngine.step(step) {
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
        val endpoint = engineEndpoint
        if (endpoint == null) {
            check(!appOutsideVpn) { "Engine proxy unavailable for traffic verification" }
            return com.example.vpn.diagnostics.LiveTunnelProbe.latency(this, probeStepMs())
        }
        val managed = _vpnState.value.activeProfile?.id in setOf(
            com.example.vpn.connections.BuiltinConnections.AETHER, com.example.vpn.connections.BuiltinConnections.DIRECT
        )
        if (managed) return kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val check = com.example.vpn.connections.AccessProbe.check(endpoint, checkDns = false)
            check.latencyMs ?: throw java.io.IOException(check.failure ?: "HTTPS response was not verified")
        }
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
     * Psiphon and Aether sessions: once traffic is verified, check through the engine's local port that YouTube's
     * front end and its video CDN answer (www.youtube.com/generate_204, redirector.googlevideo.com/report_mapping,
     * capped reads), and record what UDP and DNS do on this path (QUIC ended locally, who resolves names).
     * Background, once, never changes the connection; its result only counts for the attempt it ran in.
     */
    private fun launchVideoPathProbe(requested: VlessProfile) {
        val engineId = com.example.vpn.sidecar.EngineUdp.engineId(requested)
        if (!com.example.vpn.sidecar.EngineUdp.videoProbed(engineId)) return
        val endpoint = engineEndpoint ?: return
        val state = _vpnState.value
        val attemptId = state.attemptId ?: return
        val registry = com.example.vpn.diagnostics.events.Tests.registry
        // The carrier Aether actually runs (the session runner's pick), not the saved "auto".
        val running = state.activeProfile?.takeIf { com.example.vpn.sidecar.EngineUdp.engineId(it) == engineId } ?: requested
        val udp = com.example.vpn.sidecar.EngineUdp.describe(running)
        val dns = com.example.vpn.sidecar.EngineUdp.dnsPath(running)
        videoProbeJob?.cancel()
        videoProbeJob = serviceScope.launch(Dispatchers.IO) {
            val testId = registry.queue("video.engine_proxy", state.sessionId,
                com.example.vpn.diagnostics.events.DiagEvent.profileRef(state.activeProfile?.id))
            registry.start(testId)
            val result = try {
                kotlinx.coroutines.runInterruptible {
                    com.example.vpn.verify.VideoPathProbe(com.example.vpn.verify.RouteDialers.engine(endpoint)).run(udp, dns)
                }
            } catch (e: CancellationException) {
                registry.cancel(testId, "connection check cancelled")
                throw e
            }
            if (_vpnState.value.attemptId != attemptId) {
                registry.cancel(testId, "stale connection attempt")
                return@launch
            }
            registry.finish(testId, result.passed, "ENGINE_PROXY: ${result.describe()}",
                if (result.passed) null else com.example.vpn.diagnostics.FailureStage.HTTP_REQUEST_FAILED)
            com.example.vpn.diagnostics.events.EventLog.event("probe.video_path",
                attributes = result.attributes(engineId), result = if (result.passed) "PASS" else "FAIL")
            XrayLogManager.i("VPN", "YouTube path check through $engineId: ${result.describe()}")
        }
    }

    /**
     * After the first verified request: a download counted byte for byte and an upload the far side echoes,
     * along the same route. Runs in the background so it never delays the connection, and its result only
     * counts for the attempt and network it was measured on. A stalled bulk transfer is reported, not acted on.
     */
    private fun launchTrafficCheck(requestMs: Long?) {
        val state = _vpnState.value
        val attemptId = state.attemptId ?: return
        val generation = state.networkGeneration
        val endpoint = engineEndpoint
        val dialer = endpoint?.let { com.example.vpn.verify.RouteDialers.engine(it) } ?: com.example.vpn.verify.RouteDialers.vpn(this)
        val path = if (endpoint != null) com.example.vpn.verify.ProbePath.ENGINE_PROXY else com.example.vpn.verify.ProbePath.TUN
        val measuredOn = tunMtu?.first
        // The server under test, as the diagnostics report may show it (host or address prefix only).
        val serverLabel = com.example.vpn.verify.serverPrefix(state.activeProfile?.address)
        // A Cloudflare Worker exit cannot reach speed.cloudflare.com: the graded upload skips it there.
        val workerExit = state.activeProfile?.let { com.example.vpn.verify.cloudflareWorkerExit(it.address, it.host, it.sni) } == true
        // FreezeBook is keyed like the rest of Smart Connect's memory: the carrier ("cell:43235") or Wi-Fi.
        val freezeNetwork = com.example.vpn.smart.NetworkKey.current(this)
        val registry = com.example.vpn.diagnostics.events.Tests.registry
        registry.beginSession(state.sessionId)
        // This check is the Uplink Shield retry's verdict: when it never finishes (the session ends, the network or
        // attempt changes), the shield's run is recorded inconclusive, never as help (build 151).
        val shieldRun = freezeRescue?.takeIf {
            it.rescue == com.example.vpn.smart.FreezeFailover.Rescue.UPLINK_SHIELD && it.variantId == state.activeProfile?.id
        }
        fun shieldInconclusive(why: String) {
            if (shieldRun != null && path == com.example.vpn.verify.ProbePath.TUN) shieldNoVerdict(freezeNetwork, shieldRun.serverId, why,
                shieldRun.mss ?: com.example.vpn.stealth.recordslice.UplinkShield.MSS)
        }
        trafficCheckJob?.cancel()
        trafficCheckJob = serviceScope.launch(Dispatchers.IO) {
            val testId = registry.queue("bulk.${path.name.lowercase()}", state.sessionId,
                com.example.vpn.diagnostics.events.DiagEvent.profileRef(state.activeProfile?.id))
            registry.start(testId)
            val evidence = runCatching {
                val step = if (slowTunnel()) SLOW_VERIFY_STEP_MS.toInt()
                    else com.example.vpn.verify.TrafficCheck.stepTimeoutFor(requestMs)
                com.example.vpn.verify.TrafficCheck(dialer, stepTimeoutMs = step, cloudflareWorkerExit = workerExit)
                    .both(attemptId, generation, path)
            }
                .onFailure {
                    if (it is CancellationException) {
                        registry.cancel(testId, "connection check cancelled")
                        shieldInconclusive("check cancelled")
                        throw it
                    }
                    if (path == com.example.vpn.verify.ProbePath.TUN && com.example.vpn.verify.TunCheckPlan.isBindRefusal(it)) {
                        // Android refused the app's socket on the VPN network: not testable, never a route failure.
                        registry.notTestable(testId, com.example.vpn.verify.TunCheckPlan.bindRefusedResult(
                            com.example.vpn.verify.TunCheckPlan.reasonOf(it)))
                        shieldInconclusive("TUN check not testable")
                        XrayLogManager.i("VPN", "Upload/download check not testable: ${com.example.vpn.verify.TunCheckPlan.reasonOf(it)}")
                    } else {
                        registry.finish(testId, false, it.javaClass.simpleName,
                            com.example.vpn.diagnostics.FailureStage.of(it))
                        XrayLogManager.w("VPN", "Upload/download check could not run: ${it.javaClass.simpleName}")
                    }
                }
                .getOrNull() ?: return@launch
            if (!isActive || !evidence.appliesTo(_vpnState.value)) {
                registry.cancel(testId, "stale connection attempt or network")
                shieldInconclusive("session ended before the verdict")
                return@launch
            }
            if (path == com.example.vpn.verify.ProbePath.TUN && evidence.bindRefused) {
                // Nothing was measured: the app may not use its own VPN network. No evidence, no failover, no strike.
                registry.notTestable(testId, com.example.vpn.verify.TunCheckPlan.bindRefusedResult(evidence.describe()))
                shieldInconclusive("TUN check not testable")
                XrayLogManager.i("VPN", "Upload/download check not testable: Android refused the app's socket on the VPN network.")
                return@launch
            }
            registry.finish(testId, evidence.bidirectional, "${path.name}: ${evidence.diagLine(serverLabel)}",
                if (evidence.bidirectional) null else com.example.vpn.diagnostics.FailureStage.HTTP_REQUEST_FAILED)
            mutateState { if (evidence.appliesTo(it)) it.copy(trafficEvidence = evidence) else it }
            if (path == com.example.vpn.verify.ProbePath.TUN &&
                evidence.bidirectional && _vpnState.value.attemptId == attemptId) {
                activeRoute?.let { (net, id) -> networkMemory.routes.markBidirectional(net, id) }
            }
            XrayLogManager.i("VPN", "Upload/download check: ${evidence.diagLine(serverLabel)}.")
            if (evidence.uploadFreezeDeadDownload) {
                XrayLogManager.w("VPN", "The upload stopped at 16 KB and the download carried nothing: this is the censor's " +
                    "fixed-size freeze on this server's address, not an MTU problem.")
            } else if (evidence.fixedSizeFreeze) {
                XrayLogManager.w("VPN", "The download froze after about 16 KB: this is the censor's fixed-size freeze on this " +
                    "server's address, not an MTU problem. Another server, XHTTP or TLS fragmentation avoids it.")
            } else if (evidence.bulkStalled) {
                XrayLogManager.w("VPN", "Small requests pass but a bulk transfer stalled: a path MTU black hole or throttling is suspected.")
            }
            // What the download measured through this server here steers the next Smart Connect (RouteSpeedBook).
            val downloadStalled = (evidence.download as? com.example.vpn.verify.LegResult.Failed)?.stalled == true
            val measured = _vpnState.value.takeIf { it.attemptId == attemptId }?.activeProfile
            if (measured != null && measuredOn != null) {
                val measuredRate = (evidence.download as? com.example.vpn.verify.LegResult.Passed)?.bytesPerSecond
                networkMemory.speeds.record(measuredOn, measured.id, measuredRate, downloadStalled)
                // The verified fallback ranks by this rate; a stalled download drops it there.
                runCatching { networkMemory.winners.recordRate(measuredOn, measured.id, measuredRate, downloadStalled) }
                // The Psiphon region this session's tunnel connected to: its verified rate ranks it next time.
                if (measuredRate != null) psiphonRegionInUse?.takeIf { it.first == measuredOn && it.third == measured.id }?.let { (net, region, _) ->
                    runCatching { networkMemory.psiphonRegions.recordRate(net, region, measuredRate) }
                }
                // A route whose download stalls is not the one to replay first next time.
                if (downloadStalled) activeRoute?.let { (net, id) -> networkMemory.routes.drop(net, id) }
            }
            // Do not auto-switch a sidecar on a local SOCKS-only stall before the TUN
            // path has been measured. A verified local port is not device-level proof.
            if (measured != null && path == com.example.vpn.verify.ProbePath.TUN)
                onTransferChecked(attemptId, freezeNetwork, evidence)
            // Only a check through the tunnel itself says anything about the tunnel's MTU.
            val mtuTest = tunMtu
            if (path == com.example.vpn.verify.ProbePath.TUN && mtuTest != null && _vpnState.value.attemptId == attemptId) {
                val (net, used, configured) = mtuTest
                networkMemory.mtu.onBulkCheck(net, used, configured,
                    // A ~16 KB freeze stops at the same byte whatever the MTU; it never starts an MTU trial.
                    stalled = evidence.bulkStalled && !evidence.fixedSizeFreeze, passed = evidence.bidirectional,
                    // Only a stall within the first ~1.5 KB looks like an MTU black hole; a later one is a per-connection cap.
                    mayStart = com.example.vpn.smart.StallFailover.mtuTrialFor(evidence.stallBytes),
                    // A cap-class stall (16050 B and the like) ends a running low-MTU trial: MTU is not the cause.
                    capStall = com.example.vpn.smart.StallFailover.capStall(evidence.bulkStalled, evidence.fixedSizeFreeze, evidence.stallBytes)
                )?.let { st ->
                    XrayLogManager.i("VPN", when (st) {
                        com.example.vpn.smart.TunMtuTrial.State.TRYING_LOW -> "The next connect on this network tests MTU ${com.example.vpn.smart.TunMtuTrial.LOW_MTU}."
                        com.example.vpn.smart.TunMtuTrial.State.LOW_HELPED -> "MTU $used fixed the bulk stall on this network; it is kept here."
                        com.example.vpn.smart.TunMtuTrial.State.LOW_DID_NOT_HELP -> "The bulk transfer stalled at MTU $used too: lowering MTU did not resolve this test; the cause remains unconfirmed. Returning to $configured."
                    })
                    // "mtu" stays for older readers; it is the MTU this check ran at, not the one the trial tests next.
                    com.example.vpn.diagnostics.events.EventLog.event("tunnel.mtu.trial",
                        attributes = com.example.vpn.smart.TunMtuTrial.eventAttributes(st, used, configured))
                }
                if (networkMemory.mtu.lastClearedByCap) XrayLogManager.i("VPN", "The stall at ${evidence.stallBytes} B is a " +
                    "per-connection cap, not MTU: the low-MTU trial on this network is cleared; MTU $configured comes back.")
            }
            com.example.vpn.diagnostics.events.EventLog.event("tunnel.bidirectional", attributes = mapOf(
                "result" to if (evidence.bidirectional) "verified" else if (evidence.bulkStalled) "stalled" else "partial",
                "path" to path.name,
                "server" to (serverLabel ?: "unknown"),
                "stall.bytes" to (evidence.stallBytes?.toString() ?: "none"),
                "detail" to evidence.describe()
            ))

            // The engine's loopback SOCKS port proves only that the engine works. For sidecars the device (TUN)
            // path that other apps use is judged separately (TunCheckPlan).
            if (endpoint != null && isActive && _vpnState.value.attemptId == attemptId) {
                when (com.example.vpn.verify.TunCheckPlan.mode(appOutsideVpn)) {
                    com.example.vpn.verify.TunCheckPlan.Mode.SELF_EXCLUDED ->
                        recordTunNotTestable(state, serverLabel)
                    com.example.vpn.verify.TunCheckPlan.Mode.BIND_PROBE ->
                        runBoundTunCheck(state, attemptId, generation, requestMs, workerExit, serverLabel, freezeNetwork)
                }
            }
        }
    }

    /**
     * The app is excluded from its own VPN, so Android refuses its sockets on the VPN network and a bind-based
     * bulk.tun cannot run (build 165: every sidecar failed in ~200-430 ms with a SocketException and 0 bytes).
     * Recorded as not testable, never as a failure: no evidence is replaced, nothing switches or is marked. The
     * device path is then judged by what the TUN forwards for other apps ([watchTunForwarded]).
     */
    private fun recordTunNotTestable(state: ConnectionState, serverLabel: String?) {
        val registry = com.example.vpn.diagnostics.events.Tests.registry
        val tunId = registry.queue("bulk.tun", state.sessionId,
            com.example.vpn.diagnostics.events.DiagEvent.profileRef(state.activeProfile?.id))
        registry.start(tunId)
        registry.notTestable(tunId, com.example.vpn.verify.TunCheckPlan.selfExcludedResult(serverLabel))
        XrayLogManager.i("VPN", "${com.example.vpn.verify.TunCheckPlan.SELF_EXCLUDED_LINE}: the engine program runs " +
            "outside the TUN; the device path is measured from the traffic it forwards for other apps.")
        com.example.vpn.diagnostics.events.EventLog.event("tunnel.bidirectional", attributes = mapOf(
            "result" to com.example.vpn.verify.TunCheckPlan.NOT_TESTABLE_SELF_EXCLUDED, "path" to "TUN",
            "server" to (serverLabel ?: "unknown"),
            "detail" to com.example.vpn.verify.TunCheckPlan.SELF_EXCLUDED_LINE
        ))
        watchTunForwarded(state)
    }

    /**
     * tun.forwarded: watches the meter on the TUN → engine hop for up to [TUN_FORWARD_WINDOW_MS] and records
     * whether other apps' traffic flowed both ways (≥ 32 KB down, some upstream, no engine errors). Informational:
     * it never changes the connection state, starts a failover or marks a server.
     */
    private fun watchTunForwarded(state: ConnectionState) {
        val attemptId = state.attemptId ?: return
        val meter = com.example.vpn.verify.TunPathStatus.meter
        val registry = com.example.vpn.diagnostics.events.Tests.registry
        val id = registry.queue(com.example.vpn.verify.TunForwarded.EVENT, state.sessionId,
            com.example.vpn.diagnostics.events.DiagEvent.profileRef(state.activeProfile?.id))
        registry.start(id)
        if (meter == null || meter.isClosed) {
            registry.notTestable(id, "not measured: no meter on the TUN → engine hop for this session")
            return
        }
        tunForwardJob?.cancel()
        tunForwardJob = serviceScope.launch(Dispatchers.IO) {
            val since = meter.snapshot()
            var window = since
            var verdict = com.example.vpn.verify.TunForwarded.State.WAITING
            val deadline = System.currentTimeMillis() + TUN_FORWARD_WINDOW_MS
            try {
                while (isActive && _vpnState.value.attemptId == attemptId && !meter.isClosed) {
                    delay(TUN_FORWARD_POLL_MS)
                    window = meter.snapshot() - since
                    verdict = com.example.vpn.verify.TunForwarded.verdict(window)
                    if (verdict == com.example.vpn.verify.TunForwarded.State.VERIFIED) break
                    if (System.currentTimeMillis() >= deadline) break
                }
            } catch (e: CancellationException) {
                registry.cancel(id, "connection check cancelled")
                throw e
            }
            if (_vpnState.value.attemptId != attemptId || meter.isClosed) {
                registry.cancel(id, "stale connection attempt")
                return@launch
            }
            com.example.vpn.verify.TunPathStatus.lastWindow = window
            val line = "TUN: ${verdict.word} · ${com.example.vpn.verify.TunForwarded.describe(window)}"
            when (verdict) {
                com.example.vpn.verify.TunForwarded.State.VERIFIED -> registry.finish(id, true, line)
                // The engine refused flows the TUN handed it: a real device-path problem, reported (not acted on).
                com.example.vpn.verify.TunForwarded.State.FAILED -> registry.finish(id, false, line,
                    com.example.vpn.diagnostics.FailureStage.TCP_CONNECT_FAILED)
                else -> registry.notTestable(id, line)
            }
            XrayLogManager.i("VPN", "Android TUN forwarding (other apps' traffic): $line")
            com.example.vpn.diagnostics.events.EventLog.event(com.example.vpn.verify.TunForwarded.EVENT,
                attributes = com.example.vpn.verify.TunForwarded.attributes(window, verdict))
        }
    }

    /**
     * The app is inside its own VPN (no engine program left out): the bind-based bulk check through the VPN
     * network. A refused bind is recorded as not testable, never as a route failure.
     */
    private suspend fun runBoundTunCheck(state: ConnectionState, attemptId: String, generation: Int, requestMs: Long?,
                                         workerExit: Boolean, serverLabel: String?, freezeNetwork: String) {
        val registry = com.example.vpn.diagnostics.events.Tests.registry
        val tunId = registry.queue("bulk.tun", state.sessionId,
            com.example.vpn.diagnostics.events.DiagEvent.profileRef(state.activeProfile?.id))
        registry.start(tunId)
        try {
            com.example.vpn.verify.RouteDialers.vpnBindPreflight(this@RayVpnService)?.let { reason ->
                registry.notTestable(tunId, com.example.vpn.verify.TunCheckPlan.bindRefusedResult(reason))
                XrayLogManager.i("VPN", "Android TUN check not testable: $reason")
                return
            }
            val step = if (slowTunnel()) SLOW_VERIFY_STEP_MS.toInt()
                else com.example.vpn.verify.TrafficCheck.stepTimeoutFor(requestMs)
            val tunEvidence = com.example.vpn.verify.TrafficCheck(
                com.example.vpn.verify.RouteDialers.vpn(this@RayVpnService), stepTimeoutMs = step,
                cloudflareWorkerExit = workerExit
            ).both(attemptId, generation, com.example.vpn.verify.ProbePath.TUN)
            if (!kotlinx.coroutines.currentCoroutineContext().isActive || !tunEvidence.appliesTo(_vpnState.value)) {
                registry.cancel(tunId, "stale connection attempt or network")
                return
            }
            if (tunEvidence.bindRefused) {
                registry.notTestable(tunId, com.example.vpn.verify.TunCheckPlan.bindRefusedResult(tunEvidence.describe()))
                XrayLogManager.i("VPN", "Android TUN check not testable: Android refused the app's socket on the VPN network.")
                return
            }
            registry.finish(tunId, tunEvidence.bidirectional, "TUN: ${tunEvidence.diagLine(serverLabel)}",
                if (tunEvidence.bidirectional) null else com.example.vpn.diagnostics.FailureStage.HTTP_REQUEST_FAILED)
            XrayLogManager.i("VPN", "Android TUN upload/download check: ${tunEvidence.diagLine(serverLabel)}")
            // Prefer device-level evidence over a PASS only at the engine port.
            mutateState { if (tunEvidence.appliesTo(it)) it.copy(trafficEvidence = tunEvidence) else it }
            if (tunEvidence.bidirectional) activeRoute?.let { (net, id) ->
                networkMemory.routes.markBidirectional(net, id)
            }
            onTransferChecked(attemptId, freezeNetwork, tunEvidence)
            com.example.vpn.diagnostics.events.EventLog.event("tunnel.bidirectional", attributes = mapOf(
                "result" to if (tunEvidence.bidirectional) "verified" else if (tunEvidence.bulkStalled) "stalled" else "partial",
                "path" to "TUN", "server" to (serverLabel ?: "unknown"),
                "stall.bytes" to (tunEvidence.stallBytes?.toString() ?: "none"), "detail" to tunEvidence.describe()
            ))
        } catch (e: CancellationException) {
            registry.cancel(tunId, "connection check cancelled")
            throw e
        } catch (e: Exception) {
            if (com.example.vpn.verify.TunCheckPlan.isBindRefusal(e)) {
                registry.notTestable(tunId, com.example.vpn.verify.TunCheckPlan.bindRefusedResult(com.example.vpn.verify.TunCheckPlan.reasonOf(e)))
                return
            }
            // Untested, never evidence that the physical ISP path worked.
            XrayLogManager.w("VPN", "Android TUN check unavailable: ${e.javaClass.simpleName}; local proxy success is not device-level proof")
            registry.finish(tunId, false, "TUN check unavailable: ${e.javaClass.simpleName}",
                com.example.vpn.diagnostics.FailureStage.of(e))
            com.example.vpn.diagnostics.events.EventLog.event("tunnel.bidirectional", attributes = mapOf(
                "result" to "not_tested", "path" to "TUN",
                "detail" to "TUN probe could not run (${e.javaClass.simpleName})"
            ))
        }
    }

    /**
     * The profile Xray runs with: for an app left out of its own VPN with a loopback engine port, the SOCKS outbound
     * goes through a [com.example.vpn.verify.TunForwardMeter] first. Any problem starting it leaves the profile as it
     * was (the session runs without the meter).
     */
    private fun withTunForwardMeter(profile: VlessProfile): VlessProfile {
        com.example.vpn.verify.TunPathStatus.reset()
        com.example.vpn.verify.TunPathStatus.selfExcluded = appOutsideVpn
        val endpoint = engineEndpoint ?: return profile
        if (!appOutsideVpn || com.example.vpn.verify.Socks5.endpointOf(profile)?.port != endpoint.port) return profile
        val meter = runCatching { com.example.vpn.verify.TunForwardMeter.start(endpoint.port) }.getOrNull() ?: run {
            XrayLogManager.w("VPN", "TUN forwarding meter unavailable; the device path is not measured this session.")
            return profile
        }
        val rewired = com.example.vpn.verify.TunForwardMeter.rewire(profile, meter.port)
        if (rewired == null) {
            meter.close()
            return profile
        }
        com.example.vpn.verify.TunPathStatus.meter = meter
        XrayLogManager.i("VPN", "TUN → engine traffic is counted on a local meter (tun.forwarded).")
        return rewired
    }

    /**
     * The download through [stalled] stalled on [network] while small requests passed (outside the ~16 KB freeze
     * window): it is kept with the servers frozen here (FreezeBook) and the session moves on, the way failover
     * moves (through connectJob, with the intent it began in): to the next path that also carried traffic in
     * this Smart Connect, or with none a race that leaves the stalled and frozen servers out, at most
     * [MAX_STALL_SWITCHES] times per Connect (see [com.example.vpn.smart.StallFailover]). Never in a pinned
     * session (power button, Trade mode). [userAsked]: the user pressed "Try another server", which is not
     * held back by the automatic switch cap.
     */
    private fun switchAfterStall(attemptId: String, network: String, stalled: VlessProfile, userAsked: Boolean = false,
                                 stallBytes: Int? = null) {
        if (_vpnState.value.attemptId != attemptId) return
        networkMemory.freezes.recordStalled(network, stalled.id)
        serviceScope.launch(Dispatchers.IO) {
            val saved = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
            val usable = saved.filter {
                !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) && com.example.vpn.subscription.SubscriptionPreset.autoSelectable(it)
            }
            val stalledKeys = serverNetworkKeys(stalled) + (saved.firstOrNull { it.id == stalled.id }?.let { serverNetworkKeys(it) }.orEmpty())
            val networks = (usable + smartRunnersUp.map { it.owner }).associate { it.id to serverNetworkKeys(it) }
            val decision = com.example.vpn.smart.StallFailover.decide(com.example.vpn.smart.StallFailover.Input(
                stalledId = stalled.id, pinned = exitPinned(), userAsked = userAsked, lockedExit = exitLockedByMode(),
                switchesSoFar = stallSwitches,
                runnersUp = smartRunnersUp.map { it.owner.id },
                frozen = networkMemory.freezes.frozen(network),
                candidates = usable.map { it.id },
                // The user's "Try another server" may run the later stages again; automatic failover runs them once.
                escalated = stallEscalated && !userAsked,
                maxSwitches = if (userAsked) Int.MAX_VALUE else MAX_STALL_SWITCHES,
                stallBytes = stallBytes, stalledKeys = stalledKeys, networks = networks
            ))
            val byId = saved.associateBy { it.id }
            val name = { id: String -> com.example.core.SecretRedactor.redact(byId[id]?.name ?: if (id == stalled.id) stalled.name else id) }
            if (stallBytes != null) {
                val sameNet = if (com.example.vpn.smart.StallFailover.classify(stallBytes) == com.example.vpn.smart.StallFailover.StallClass.CAP)
                    com.example.vpn.smart.StallFailover.sameNetwork(stalled.id, stalledKeys, networks).size else 0
                XrayLogManager.w("FAILOVER", com.example.vpn.smart.StallFailover.stallLine(stallBytes, stalledKeys, sameNet))
            }
            XrayLogManager.w("FAILOVER", com.example.vpn.smart.StallFailover.describe(decision, name))
            stallEvent(decision, stalled.id, userAsked)
            if (_vpnState.value.attemptId != attemptId) return@launch
            // Build 167: "Try another server" on a power-button session is the user's choice to leave the pinned
            // config. The session continues as Smart Connect from here, leaving the stalled server out.
            if (com.example.vpn.smart.StallFailover.unpinsSession(decision, userAsked, exactSession)) {
                exactSession = false
                smartStarted = true
                XrayLogManager.i("FAILOVER", "Try another server on a pinned session: the user chose to leave it; continuing as Smart Connect.")
            }
            when (decision) {
                is com.example.vpn.smart.StallFailover.Decision.RunnerUp -> {
                    val next = smartRunnersUp.firstOrNull { it.owner.id == decision.toId } ?: return@launch
                    val exclude = networkMemory.freezes.frozen(network) + stalled.id
                    launchSwitch("stall") { ticket ->
                        stallSwitches++
                        smartRunnersUp = smartRunnersUp.filter { it.owner.id != next.owner.id && it.owner.id !in exclude }
                        connect(next.owner, smart = true, exclude = exclude, ticket = ticket)
                    }
                }
                is com.example.vpn.smart.StallFailover.Decision.Race -> {
                    val lead = com.example.vpn.smart.ServerRace.rank(usable, networkMemory.workingKinds(network),
                        networkMemory.recentFailures(network), exclude = decision.exclude, limit = 1).firstOrNull()
                    if (lead == null) {
                        XrayLogManager.w("FAILOVER", "failover · stall · STAY (no saved server to race)")
                        return@launch
                    }
                    launchSwitch("stall") { ticket ->
                        stallSwitches++
                        smartRunnersUp = smartRunnersUp.filter { it.owner.id !in decision.exclude && it.owner.id != lead.id }
                        com.example.vpn.diagnostics.ConnectionMetrics.failoverSwitches.incrementAndGet()
                        connect(lead, smart = true, exclude = decision.exclude, ticket = ticket)
                    }
                }
                is com.example.vpn.smart.StallFailover.Decision.Escalate -> {
                    val reason = decision.reason
                    launchSwitch("stall") { ticket -> escalateBeyondSavedServers(network, stalled, reason, ticket) }
                }
                is com.example.vpn.smart.StallFailover.Decision.Stay -> Unit
            }
        }
    }

    /**
     * A stall or freeze failover used up the saved servers in this session ([reason]): the Smart Connect search
     * goes on with its later stages (E emergency transports, F recovery engines such as Tor, serverless, DNS
     * tunnels and slipstream, G config revival), bounded by their own time limits, leaving every saved server
     * out. When none of them carries traffic, the session comes back to [from], which at least carried small
     * requests, instead of ending with no tunnel. Runs once per Connect ([stallEscalated]); the caller holds
     * the switch.
     */
    private suspend fun escalateBeyondSavedServers(network: String, from: VlessProfile, reason: String, ticket: Long) {
        stallEscalated = true
        if (!lifecycle.isCurrent(ticket)) return
        val saved = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
        val savedServers = saved.filter { !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) }.map { it.id }.toSet()
        val exclude = savedServers + networkMemory.freezes.frozen(network) + from.id + XRAY_EXHAUSTED_MARKER
        XrayLogManager.w("SMART", "Bulk downloads stalled on every saved server tried ($reason); continuing Smart Connect " +
            "with emergency transports, recovery engines and config revival (stages E-G).")
        escalationEvent("start", reason, from.id)
        showForegroundNotification("Downloads stalling; trying other methods...")
        connect(from, smart = true, exclude = exclude, ticket = ticket)
        if (!lifecycle.isCurrent(ticket)) return
        if (_vpnState.value.status != ConnectionStatus.FAILED) {
            escalationEvent("found", reason, _vpnState.value.activeProfile?.id ?: from.id)
            return
        }
        XrayLogManager.w("SMART", "The later stages found no path that carries traffic; back on " +
            "${com.example.core.SecretRedactor.redact(from.name)}, which carries small requests but stalls on large downloads.")
        escalationEvent("restore", reason, from.id)
        connect(from, pinned = true, ticket = ticket)
    }

    private fun escalationEvent(action: String, reason: String, profileId: String) {
        com.example.vpn.diagnostics.events.EventLog.event("failover.escalate", attributes = mapOf(
            "action" to action, "reason" to reason, "stages" to "E-G",
            "profile" to (com.example.vpn.diagnostics.events.DiagEvent.profileRef(profileId) ?: "none")
        ))
    }

    /**
     * The freeze failover gave up on [server] ([reason]): continue with the later stages unless the session is
     * pinned or they already ran. Returns whether it did; the caller holds the switch.
     */
    private suspend fun escalateAfterFreeze(network: String, server: VlessProfile, reason: String, ticket: Long): Boolean {
        if (!com.example.vpn.smart.StallFailover.escalatesAfterFreeze(exitPinned(), stallEscalated)) return false
        escalateBeyondSavedServers(network, server, "freeze16k: $reason", ticket)
        return true
    }

    /** The power button or Trade mode pinned this session's config: failover never changes it. */
    private suspend fun exitPinned(): Boolean = exactSession || exitLockedByMode()

    /** Trade mode: the exit address must never change, not even when the user asks for another server. */
    private suspend fun exitLockedByMode(): Boolean = runCatching {
        com.example.vpn.safety.OperatingModePolicy.of(settingsRepository.getSettings().operationalMode).lockExit
    }.getOrDefault(false)

    /**
     * Starts an automatic move of the running session ([block] gets the intent ticket), unless another switch
     * is already under way. Through connectJob, so a Connect or Disconnect cancels it.
     */
    private fun launchSwitch(what: String, block: suspend (Long) -> Unit): Boolean {
        val ticket = lifecycle.current()
        if (!lifecycle.tryBeginSwitch()) {
            XrayLogManager.w("FAILOVER", "failover · $what · another switch is already under way")
            return false
        }
        mutateState { com.example.data.model.ConnectionVerification.onSwitching(it) }
        connectJob = serviceScope.launch(AttemptTicket(ticket)) {
            try { block(ticket) } finally { lifecycle.endSwitch() }
        }
        return true
    }

    private fun stallEvent(d: com.example.vpn.smart.StallFailover.Decision, profileId: String, userAsked: Boolean) {
        com.example.vpn.diagnostics.events.EventLog.event("failover.stall", attributes = mapOf(
            "action" to com.example.vpn.smart.StallFailover.action(d),
            "reason" to com.example.vpn.smart.StallFailover.reason(d),
            "trigger" to if (userAsked) "user" else "transfer-check",
            "profile" to (com.example.vpn.diagnostics.events.DiagEvent.profileRef(profileId) ?: "none")
        ))
    }

    /**
     * The upload/download check through the TUN finished for [attemptId]: what it says about the running server
     * on [network] goes into FreezeBook, and a ~16 KB freeze starts the freeze failover ([onFreeze]). Any other
     * download stall keeps the runner-up switch ([switchAfterStall]).
     */
    private fun onTransferChecked(attemptId: String, network: String, evidence: com.example.vpn.verify.TrafficEvidence) {
        val state = _vpnState.value
        if (state.attemptId != attemptId) return
        val running = state.activeProfile ?: return
        val freezes = networkMemory.freezes
        val rescue = freezeRescue?.takeIf { it.variantId == running.id }
        val label = rescue?.let { "failover · freeze16k · ${it.rescue.label} ${com.example.core.SecretRedactor.redact(running.name)}" }
        val download = evidence.download
        val uplinkStall = com.example.vpn.stealth.recordslice.UplinkShield.signature(evidence)
        // The Uplink Shield's result on this network: it helped when the upload no longer stalls at 16 KB.
        // MSS ladder: a step that did not help, with downloads passing, steps down at once (1360 → 1220 → 1140).
        var shieldStep: Int? = null
        var shieldFrom = com.example.vpn.stealth.recordslice.UplinkShield.MSS
        if (rescue?.rescue == com.example.vpn.smart.FreezeFailover.Rescue.UPLINK_SHIELD) {
            val mss = rescue?.mss ?: com.example.vpn.stealth.recordslice.UplinkShield.mss(running) ?: com.example.vpn.stealth.recordslice.UplinkShield.MSS
            val helped = !uplinkStall && !evidence.fixedSizeFreeze
            shieldFrom = mss
            networkMemory.shield.record(network, helped = helped, mss = mss)
            shieldEvent(if (helped) "helped" else "no-help", running.id, mss)
            if (!helped && !evidence.fixedSizeFreeze && download is com.example.vpn.verify.LegResult.Passed &&
                networkMemory.shield.mayTry(network)) {
                shieldStep = networkMemory.shield.nextMss(network).takeIf { it < mss }
            }
        }
        when {
            shieldStep != null && rescue != null -> {
                val step: Int = shieldStep ?: com.example.vpn.stealth.recordslice.UplinkShield.MSS
                val from = shieldFrom
                val retry = com.example.vpn.smart.FreezeFailover.Decision.Retry(rescue.serverId,
                    com.example.vpn.stealth.recordslice.UplinkShield.enable(running, step),
                    com.example.vpn.smart.FreezeFailover.Rescue.UPLINK_SHIELD)
                val name = { _: String -> com.example.core.SecretRedactor.redact(running.name) }
                serviceScope.launch(Dispatchers.IO) {
                    if (exitPinned() || _vpnState.value.attemptId != attemptId) return@launch
                    XrayLogManager.w("FAILOVER", "failover · upload16k · uplink shield at $from B did not clear " +
                        "the stall; stepping the MSS down to $step B")
                    shieldEvent("retry", running.id, step)
                    startFreezeRescue(network, running, retry, name)
                }
            }
            evidence.fixedSizeFreeze -> {
                freezes.recordFrozen(network, running.id)
                if (label != null) XrayLogManager.w("FAILOVER", "$label: the download froze again after about 16 KB.")
                freezeRescue = null
                // Upload capped at 16 KB and a download that carried nothing (#218): the only same-server rescue still
                // worth one try is the Uplink Shield (#220), and only when it has not already failed on this network
                // (UplinkShieldBook.mayTry) and the config can take it; otherwise no same-server rescue, move on now.
                val shieldFirst = evidence.uploadFreezeDeadDownload && uplinkStall && rescue == null &&
                    running.id !in freezeRescued && networkMemory.shield.mayTry(network) &&
                    com.example.vpn.smart.FreezeFailover.canShield(running) &&
                    !com.example.vpn.sidecar.Sidecars.isEngineProfile(running)
                if (evidence.uploadFreezeDeadDownload) XrayLogManager.w("FAILOVER", "failover · freeze16k · upload stalled at " +
                    "${evidence.stallBytes ?: 16384} B and the download carried nothing: marked frozen on this network; " +
                    if (shieldFirst) "one same-server try with the Uplink Shield first" else "switching")
                onFreeze(attemptId, network, running, rescueTried = rescue != null || running.id in freezeRescued ||
                    (evidence.uploadFreezeDeadDownload && !shieldFirst), uplinkStall = uplinkStall)
            }
            download is com.example.vpn.verify.LegResult.Passed && uplinkStall && rescue == null &&
                running.id !in freezeRescued && networkMemory.shield.mayTry(network) &&
                com.example.vpn.smart.FreezeFailover.canShield(running) &&
                !com.example.vpn.sidecar.Sidecars.isEngineProfile(running) -> {
                // Downloads pass but every upload stops at the 16 KB grade: try the same server with the MSS clamp.
                freezes.recordGood(network, running.id)
                val shieldMss = networkMemory.shield.nextMss(network)
                val retry = com.example.vpn.smart.FreezeFailover.Decision.Retry(running.id,
                    com.example.vpn.stealth.recordslice.UplinkShield.enable(running, shieldMss),
                    com.example.vpn.smart.FreezeFailover.Rescue.UPLINK_SHIELD)
                val name = { _: String -> com.example.core.SecretRedactor.redact(running.name) }
                serviceScope.launch(Dispatchers.IO) {
                    // A pinned config (power button without fallback, Trade mode) is never changed.
                    if (exitPinned() || _vpnState.value.attemptId != attemptId) return@launch
                    XrayLogManager.w("FAILOVER", "failover · upload16k · the upload stalled at the 16 KB grade; " +
                        com.example.vpn.smart.FreezeFailover.describe(retry, name))
                    shieldEvent("retry", running.id, shieldMss)
                    startFreezeRescue(network, running, retry, name)
                }
            }
            download is com.example.vpn.verify.LegResult.Passed &&
                (evidence.upload as? com.example.vpn.verify.LegResult.Failed)?.stalled != true -> {
                // Passed the transfer check here: last-known-good on this network.
                freezes.recordGood(network, running.id, rescue?.rescue?.key)
                if (rescue == null) startSpeedPick(attemptId, network, running)
                if (label != null) {
                    XrayLogManager.i("FAILOVER", "$label: passed the transfer check (${download.bytes / 1024} KB)" +
                        if (uplinkStall) "; the upload still stalls at the 16 KB grade." else ".")
                    freezeEvent("rescued", rescue?.rescue?.key.orEmpty(), running.id)
                    freezeRescue = null
                }
            }
            (download as? com.example.vpn.verify.LegResult.Failed)?.stalled == true ->
                switchAfterStall(attemptId, network, running, stallBytes = (download as? com.example.vpn.verify.LegResult.Failed)?.bytes)
        }
    }

    private fun speedPickMetered(): Boolean =
        _environment.value?.signals?.contains(com.example.vpn.smart.NetworkEnvironment.Signal.METERED) == true ||
            runCatching {
                val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching false
                val n = com.example.vpn.smart.NetworkCapabilityDetector.physical(cm) ?: return@runCatching cm.isActiveNetworkMetered
                cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            }.getOrDefault(false)

    /**
     * Before a Smart pick's Xray core starts: the race's other verified servers (up to
     * [com.example.vpn.smart.PostConnectSpeedPick.MAX_ALTERNATES]) become loopback probes in that core, unless the
     * speed pick already ran since the last Connect or the network is metered without the user's permission.
     */
    private fun stageSpeedProbes(settings: AppSettings) {
        val pick = com.example.vpn.smart.PostConnectSpeedPick
        val network = com.example.vpn.smart.NetworkKey.current(this)
        val frozen = networkMemory.freezes.frozen(network)
        val byOwner = smartRunnersUp.filter { !com.example.vpn.sidecar.Sidecars.isEngineProfile(it.owner) &&
            it.profile.profileType != ProfileType.XRAY_JSON }.associateBy { it.owner.id }
        val ids = pick.pickAlternates(smartRunnersUp.map { it.owner.id }.filter { it in byOwner }, current = "", exclude = frozen)
        val metered = speedPickMetered()
        if (!pick.applies(smartPick = true, tunPath = !appOutsideVpn, metered = metered,
                allowMetered = settings.speedPickOnMetered, alternates = ids.size, alreadyRan = speedPickRan)) {
            if (metered && ids.isNotEmpty() && !speedPickRan && !settings.speedPickOnMetered)
                XrayLogManager.i("SMART", "speed-pick · skipped on a metered network (Settings → Failover to allow it).")
            return
        }
        val probes = ids.mapIndexedNotNull { i, id ->
            val w = byOwner[id] ?: return@mapIndexedNotNull null
            val tag = "${pick.TAG_PREFIX}$i"
            val out = runCatching {
                pick.standaloneOutbound(org.json.JSONObject(com.example.xray.XrayConfigBuilder.buildJson(w.profile, AppSettings())), tag)
            }.getOrNull() ?: return@mapIndexedNotNull null
            com.example.vpn.smart.PostConnectSpeedPick.Probe(id, tag, com.example.vpn.sidecar.Sidecars.freeLoopbackPort(),
                com.example.vpn.sidecar.Sidecars.randomToken(9), com.example.vpn.sidecar.Sidecars.randomToken(), out)
        }
        if (probes.isNotEmpty()) pick.stage(probes)
    }

    /**
     * The Smart pick passed its transfer check: time a ~2 MB download through it (the TUN) and through each probe
     * the core carries, one after another, and move to an alternate only when it was more than 10% faster
     * ([com.example.vpn.smart.PostConnectSpeedPick.decide]). Runs at most once per Connect; a pinned session never moves.
     */
    private fun startSpeedPick(attemptId: String, network: String, running: VlessProfile) {
        val pick = com.example.vpn.smart.PostConnectSpeedPick
        val probes = pick.active
        if (probes.isEmpty() || speedPickRan) return
        speedPickRan = true
        serviceScope.launch(Dispatchers.IO) {
            if (exitPinned() || _vpnState.value.attemptId != attemptId) return@launch
            fun measure(dialer: com.example.vpn.verify.Dialer, target: com.example.vpn.verify.TrafficCheck.Target): Long? = runCatching {
                (com.example.vpn.verify.TrafficCheck(dialer, downloads = listOf(target), stepTimeoutMs = pick.STEP_TIMEOUT_MS)
                    .timedDownload(target) as? com.example.vpn.verify.LegResult.Passed)?.bytesPerSecond
            }.getOrNull()
            // The first reference that works through the server in use times every route, so the rates compare.
            var measured: Pair<com.example.vpn.verify.TrafficCheck.Target, Long>? = null
            for (t in com.example.vpn.verify.TrafficCheck.SPEED_PICK_DOWNLOADS) {
                if (!isActive || _vpnState.value.attemptId != attemptId) return@launch
                val rate = kotlinx.coroutines.runInterruptible { measure(com.example.vpn.verify.RouteDialers.vpn(this@RayVpnService), t) }
                if (rate != null) { measured = t to rate; break }
            }
            val (target, current) = measured ?: run {
                XrayLogManager.i("SMART", "speed-pick · the download through the server in use could not be timed; staying.")
                return@launch
            }
            val rates = linkedMapOf<String, Long?>()
            for (p in probes) {
                if (!isActive || _vpnState.value.attemptId != attemptId) return@launch
                rates[p.ownerId] = kotlinx.coroutines.runInterruptible {
                    measure(com.example.vpn.verify.RouteDialers.engine(com.example.vpn.verify.Socks5.Endpoint("127.0.0.1", p.port, p.user, p.pass)), target)
                }
                rates[p.ownerId]?.let { networkMemory.speeds.record(network, p.ownerId, it, stalled = false) }
            }
            val kb = { r: Long? -> r?.let { "${it / 1024} KB/s" } ?: "not measured" }
            XrayLogManager.i("SMART", "speed-pick · in use ${kb(current)}; " +
                rates.entries.joinToString { "${com.example.vpn.diagnostics.events.DiagEvent.profileRef(it.key) ?: "alt"} ${kb(it.value)}" })
            val to = pick.decide(current, rates)
            com.example.vpn.diagnostics.events.EventLog.event("smart.speed_pick", attributes = mapOf(
                "result" to if (to == null) "stay" else "switch", "alternates" to rates.size.toString(),
                "current_bps" to current.toString(), "best_bps" to (rates.values.filterNotNull().maxOrNull()?.toString() ?: "none")))
            if (to == null || _vpnState.value.attemptId != attemptId || exitPinned()) return@launch
            val lead = smartRunnersUp.firstOrNull { it.owner.id == to } ?: return@launch
            XrayLogManager.i("SMART", "speed-pick · switching: ${com.example.core.SecretRedactor.redact(lead.owner.name)} was more " +
                "than ${(pick.SWITCH_MARGIN * 100).toInt()}% faster than ${com.example.core.SecretRedactor.redact(running.name)}.")
            launchSwitch("speed-pick") { ticket ->
                smartRunnersUp = smartRunnersUp.filter { it.owner.id != lead.owner.id }
                connect(lead.owner, smart = true, exclude = setOf(running.id), ticket = ticket)
            }
        }
    }

    /**
     * The running [server] shows the censor's ~16 KB freeze on [network]: small requests pass, so the failover
     * watchdog never switches, but bulk downloads stop. Retry the same server once with deep fragmentation (or
     * its saved XHTTP form), then move to a verified backup; see [com.example.vpn.smart.FreezeFailover]. The
     * traffic-blocking interface stays up during each move (connect keeps protection before tearing down).
     */
    private fun onFreeze(attemptId: String, network: String, server: VlessProfile, rescueTried: Boolean,
                         uplinkStall: Boolean = false) {
        serviceScope.launch(Dispatchers.IO) {
            val (decision, name) = freezeDecision(network, server, rescueTried, uplinkStall)
            XrayLogManager.w("FAILOVER", com.example.vpn.smart.FreezeFailover.describe(decision, name))
            if (_vpnState.value.attemptId != attemptId) return@launch
            when (decision) {
                is com.example.vpn.smart.FreezeFailover.Decision.Retry -> startFreezeRescue(network, server, decision, name)
                is com.example.vpn.smart.FreezeFailover.Decision.Switch -> {
                    val ticket = lifecycle.current()
                    if (!lifecycle.tryBeginSwitch()) {
                        XrayLogManager.w("FAILOVER", "failover · freeze16k · FAIL (another switch is already under way)")
                        return@launch
                    }
                    connectJob = serviceScope.launch(AttemptTicket(ticket)) {
                        try { runFreezeSwitch(network, decision, name, ticket) } finally { lifecycle.endSwitch() }
                    }
                }
                is com.example.vpn.smart.FreezeFailover.Decision.Fail -> {
                    freezeEvent("fail", decision.reason, server.id)
                    if (com.example.vpn.smart.StallFailover.escalatesAfterFreeze(exitPinned(), stallEscalated)) {
                        val reason = decision.reason
                        launchSwitch("freeze16k") { ticket -> escalateBeyondSavedServers(network, server, "freeze16k: $reason", ticket) }
                    }
                }
            }
        }
    }

    private suspend fun freezeDecision(network: String, server: VlessProfile, rescueTried: Boolean, uplinkStall: Boolean = false):
        Pair<com.example.vpn.smart.FreezeFailover.Decision, (String) -> String> {
        val saved = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
        val byId = saved.associateBy { it.id }
        val usable = { p: VlessProfile ->
            !com.example.vpn.sidecar.Sidecars.isEngineProfile(p) && com.example.vpn.subscription.SubscriptionPreset.autoSelectable(p)
        }
        val freezes = networkMemory.freezes
        val pinned = exitPinned()
        val savedServer = byId[server.id] ?: server
        val decision = com.example.vpn.smart.FreezeFailover.decide(com.example.vpn.smart.FreezeFailover.Input(
            server = savedServer, running = savedServer, pinned = pinned, rescueTried = rescueTried,
            switchesSoFar = freezeSwitches,
            runnersUp = smartRunnersUp.map { it.owner }.filter(usable),
            lastGood = freezes.lastGood(network).mapNotNull { byId[it] }.filter(usable),
            frozen = freezes.frozen(network), saved = saved.filter(usable),
            engineProgram = com.example.vpn.sidecar.Sidecars.isEngineProfile(server),
            serverKeys = serverNetworkKeys(server) + serverNetworkKeys(savedServer),
            networks = (saved.filter(usable) + smartRunnersUp.map { it.owner }).associate { it.id to serverNetworkKeys(it) },
            uplinkStall = uplinkStall, shieldMayTry = networkMemory.shield.mayTry(network),
            shieldMss = networkMemory.shield.nextMss(network)
        ))
        val name = { id: String -> com.example.core.SecretRedactor.redact(byId[id]?.name ?: if (id == server.id) server.name else id) }
        return decision to name
    }

    /** Reconnects the same server as the rescue variant; when that carries no traffic at all, moves on at once. */
    private fun startFreezeRescue(network: String, server: VlessProfile, d: com.example.vpn.smart.FreezeFailover.Decision.Retry,
                                  name: (String) -> String) {
        val ticket = lifecycle.current()
        if (!lifecycle.tryBeginSwitch()) {
            XrayLogManager.w("FAILOVER", "failover · freeze16k · FAIL (another switch is already under way)")
            return
        }
        freezeRescued += d.serverId
        val shieldMss = if (d.rescue == com.example.vpn.smart.FreezeFailover.Rescue.UPLINK_SHIELD)
            com.example.vpn.stealth.recordslice.UplinkShield.mss(d.variant) else null
        freezeRescue = FreezeRescue(d.serverId, d.variant.id, d.rescue, shieldMss)
        freezeEvent("retry", d.rescue.key, d.serverId)
        mutateState { com.example.data.model.ConnectionVerification.onSwitching(it) }
        connectJob = serviceScope.launch(AttemptTicket(ticket)) {
            try {
                connect(d.variant, pinned = true, ticket = ticket)
                // The retry's transfer check decides when it carried traffic; with none at all, move on now.
                if (lifecycle.isCurrent(ticket) && freezeRescue?.variantId == d.variant.id && !_vpnState.value.isConnected) {
                    freezeRescue = null
                    // A shield retry that carried nothing at all did not help here.
                    if (d.rescue == com.example.vpn.smart.FreezeFailover.Rescue.UPLINK_SHIELD) {
                        val mss = shieldMss ?: com.example.vpn.stealth.recordslice.UplinkShield.MSS
                        networkMemory.shield.record(network, helped = false, mss = mss)
                        shieldEvent("no-help", d.serverId, mss)
                    }
                    XrayLogManager.w("FAILOVER", "failover · freeze16k · ${d.rescue.label} ${name(d.serverId)}: carried no traffic.")
                    val (next, nextName) = freezeDecision(network, server, rescueTried = true)
                    XrayLogManager.w("FAILOVER", com.example.vpn.smart.FreezeFailover.describe(next, nextName))
                    when (next) {
                        is com.example.vpn.smart.FreezeFailover.Decision.Switch -> runFreezeSwitch(network, next, nextName, ticket)
                        is com.example.vpn.smart.FreezeFailover.Decision.Fail -> {
                            freezeEvent("fail", next.reason, server.id)
                            escalateAfterFreeze(network, server, next.reason, ticket)
                        }
                        is com.example.vpn.smart.FreezeFailover.Decision.Retry -> Unit
                    }
                }
            } finally {
                lifecycle.endSwitch()
            }
        }
    }

    /**
     * Moves off the frozen server (the caller holds the switch): to the verified backup, or with none a Smart
     * Connect race that leaves every server frozen on this network out.
     */
    private suspend fun runFreezeSwitch(network: String, d: com.example.vpn.smart.FreezeFailover.Decision.Switch,
                                        name: (String) -> String, ticket: Long) {
        val frozen = networkMemory.freezes.frozen(network) + d.fromId + d.sameNetwork
        val lead = d.to ?: runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
            .filter { !com.example.vpn.sidecar.Sidecars.isEngineProfile(it) }
            .let { com.example.vpn.smart.ServerRace.rank(it, networkMemory.workingKinds(network), networkMemory.recentFailures(network),
                exclude = frozen, limit = 1) }.firstOrNull()
        if (lead == null) {
            XrayLogManager.w("FAILOVER", "failover · freeze16k · FAIL (no other saved server to move ${name(d.fromId)} to)")
            freezeEvent("fail", "no backup", d.fromId)
            val from = runCatching { serverRepository.getProfileById(d.fromId) }.getOrNull() ?: _vpnState.value.activeProfile
            if (from != null) escalateAfterFreeze(network, from, "no backup", ticket)
            return
        }
        if (!lifecycle.isCurrent(ticket)) return
        mutateState { com.example.data.model.ConnectionVerification.onSwitching(it) }
        freezeSwitches++
        smartRunnersUp = smartRunnersUp.filter { it.owner.id !in frozen && it.owner.id != lead.id }
        com.example.vpn.diagnostics.ConnectionMetrics.failoverSwitches.incrementAndGet()
        freezeEvent("switch", d.to?.let { "backup" } ?: "race", d.fromId)
        connect(lead, smart = true, exclude = frozen, ticket = ticket)
    }

    /**
     * The Uplink Shield's run on [network] ended with no transfer verdict: recorded inconclusive (never as help). After
     * [com.example.vpn.smart.UplinkShieldBook.MAX_INCONCLUSIVE] in a row the shield is skipped there for 12 h.
     */
    private fun shieldNoVerdict(network: String, serverId: String, why: String,
                                mss: Int = com.example.vpn.stealth.recordslice.UplinkShield.MSS) {
        val book = networkMemory.shield
        val n = book.recordInconclusive(network, mss)
        if (freezeRescue?.serverId == serverId && freezeRescue?.rescue == com.example.vpn.smart.FreezeFailover.Rescue.UPLINK_SHIELD) freezeRescue = null
        XrayLogManager.i("FAILOVER", "failover · uplink-shield · inconclusive ($why): $n in a row on this network" +
            if (!book.mayTry(network)) "; not tried here for ${com.example.vpn.smart.UplinkShieldBook.NO_HELP_COOLDOWN_MS / 3_600_000} h" else "")
        shieldEvent("inconclusive", serverId, mss)
    }

    private fun shieldEvent(action: String, profileId: String,
                            mss: Int = com.example.vpn.stealth.recordslice.UplinkShield.MSS) {
        com.example.vpn.diagnostics.events.EventLog.event("failover.uplink_shield", attributes = mapOf(
            "action" to action, "mss" to mss.toString(),
            "profile" to (com.example.vpn.diagnostics.events.DiagEvent.profileRef(profileId) ?: "none")
        ))
    }

    private fun freezeEvent(action: String, detail: String, profileId: String) {
        com.example.vpn.diagnostics.events.EventLog.event("failover.freeze16k", attributes = mapOf(
            "action" to action, "detail" to detail,
            "profile" to (com.example.vpn.diagnostics.events.DiagEvent.profileRef(profileId) ?: "none")
        ))
    }

    /** A request that went through the tunnel, recorded so the PASS can be checked later. */
    private fun passEvent(testType: String, elapsedMs: Long) {
        val s = _vpnState.value
        com.example.vpn.diagnostics.events.EventLog.pass(
            testType = testType,
            destination = if (engineEndpoint == null) java.net.URI(com.example.xray.RealDelayProbe.PROBE_URL).host else "HTTPS probe target",
            profileRef = com.example.vpn.diagnostics.events.DiagEvent.profileRef(s.activeProfile?.id ?: s.attemptedProfileId),
            elapsedMs = elapsedMs,
            interfaceName = if (engineEndpoint == null) "tun (VPN network)" else "engine local proxy"
        )
    }

    /** Traffic went through [profile]: its evidence and, for a free config, the last-known-good pool. */
    private fun onTrafficVerified(profile: VlessProfile, rttMs: Long?) {
        if (rttMs != null) {
            val network = com.example.vpn.smart.NetworkKey.current(this)
            networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(profile), rttMs)
            _environment.value = com.example.vpn.smart.NetworkEnvironment.observe(this, networkMemory, dnsPoisoned || dnsTamperedOn(network))
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

    // ---- Unlock mode: DNS only ----

    private var unlockVerifyJob: Job? = null

    /**
     * Connect, Reconnect or an Always-on start while the mode is Unlock. No profile, no engine and no traffic
     * block: the service only swaps the resolver (see [connectUnlockLocked]).
     */
    private fun startUnlock(action: String?): Int {
        if (!showForegroundNotification(com.example.ui.language.LanguageStore.localized(this).getString(R.string.unlock_notification_starting))) {
            updateState(_vpnState.value.copy(status = ConnectionStatus.FAILED, errorMessage = "Foreground notification could not be started."))
            stopSelf()
            return START_NOT_STICKY
        }
        if (action != ACTION_RECONNECT || !_vpnState.value.isTunnelUp) {
            updateState(_vpnState.value.copy(status = ConnectionStatus.PREPARING, errorMessage = null))
        }
        lifecycle.newIntent()
        exactSession = false
        smartStarted = false
        connectJob?.cancel()
        com.example.vpn.sidecar.EngineReaper.stopAll()
        com.example.vpn.sidecar.EngineHandoff.clear()
        connectJob = serviceScope.launch(AttemptTicket(lifecycle.current())) { connectionMutex.withLock { connectUnlockLocked() } }
        return START_STICKY
    }

    /**
     * Unlock mode: check the Unlock DNS servers from the phone's own network, pick one (fastest usable, or the
     * user's choice), and bring up the DNS-only TUN ([com.example.vpn.safety.UnlockDnsRoutePolicy]) whose only
     * route is the in-tunnel DNS address. Every other packet keeps using the phone's network directly.
     */
    private suspend fun connectUnlockLocked(): Unit = withContext(Dispatchers.IO) {
        val text = com.example.ui.language.LanguageStore.localized(this@RayVpnService)
        val previous = _vpnState.value
        val sessionId = newId()
        val attemptId = newId()
        fun failed(message: String) {
            XrayLogManager.w("UNLOCK", message)
            disconnectResources()
            updateState(ConnectionState(status = ConnectionStatus.FAILED, errorMessage = message,
                sessionId = sessionId, attemptId = attemptId, networkGeneration = previous.networkGeneration))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        updateState(ConnectionState(status = ConnectionStatus.CONNECTING, sessionId = sessionId, attemptId = attemptId,
            activeRoute = "Unlock DNS", networkGeneration = previous.networkGeneration))
        // Unlock never holds the traffic block: there is no tunnel to leak from, only a resolver to swap.
        supervisor.release(com.example.vpn.safety.MaximusVpnSupervisor.Release.UNLOCK_MODE)
        checkLockdown()
        if (_lockdown.value == com.example.vpn.safety.MaximusVpnSupervisor.Lockdown.ON) {
            // With "Block connections without VPN", Android drops every packet outside the TUN, i.e. all of them.
            return@withContext failed(text.getString(R.string.unlock_error_lockdown))
        }
        // Stop whatever ran before (a tunnel and its engines) and release its interface.
        disconnectResources()
        com.example.vpn.unlock.UnlockDns.load(this@RayVpnService)
        val choice = com.example.vpn.unlock.UnlockDns.state.value
        val checks = com.example.vpn.unlock.UnlockDns.checkServers(choice.servers)
        com.example.vpn.unlock.UnlockDns.publishChecks(checks)
        checks.forEach { c ->
            XrayLogManager.i("UNLOCK", "${c.ip}: ${c.health.name.lowercase()}" + (c.latencyMs?.let { " $it ms" } ?: "") +
                (c.reason?.let { " (${it.name.lowercase().replace('_', ' ')})" } ?: "") +
                (c.shape?.let { " [query shape ${it.key}]" } ?: ""))
        }
        val server = com.example.vpn.unlock.UnlockDnsHealth.pick(checks, choice.autoPick, choice.manual)
            ?: return@withContext failed(text.getString(R.string.unlock_error_no_server))
        val latency = checks.firstOrNull { it.ip == server }?.latencyMs
        val tun = try {
            Builder().setSession("Maximus Unlock DNS")
                .setMtu(com.example.vpn.safety.UnlockDnsRoutePolicy.MTU)
                .apply {
                    com.example.vpn.routing.SplitTunnelStore.apply(this@RayVpnService, this, excludeSelf = false, interfaceName = "unlock-dns")
                    val a = com.example.vpn.safety.UnlockDnsRoutePolicy.ADDRESS
                    addAddress(a.address, a.prefix)
                    com.example.vpn.safety.UnlockDnsRoutePolicy.ROUTES.forEach { addRoute(it.address, it.prefix) }
                    addDnsServer(com.example.vpn.safety.UnlockDnsRoutePolicy.DNS_SERVER)
                    // No IPv6 address: without this Android would block IPv6 for every app on the VPN.
                    allowFamily(android.system.OsConstants.AF_INET6)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false)
                }
                .establish()
        } catch (e: Exception) {
            XrayLogManager.e("UNLOCK", "Builder.establish() failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        } ?: return@withContext failed(text.getString(R.string.unlock_error_interface))
        vpnInterface = tun
        appOutsideVpn = false
        val session = com.example.vpn.unlock.UnlockDnsSession(
            tun = tun, initialServer = server, initialChecks = checks, autoPick = choice.autoPick,
            protector = com.example.vpn.unlock.UnlockDns.protector,
            log = { XrayLogManager.i("UNLOCK", it) },
            onSwitch = { ip, ms -> onUnlockServerChanged(ip, ms) }
        )
        unlockSession = session
        session.start()
        val now = System.currentTimeMillis()
        updateState(ConnectionState(status = ConnectionStatus.CONNECTED, sessionId = sessionId, attemptId = attemptId,
            activeRoute = "Unlock DNS • $server", pingMs = latency, pingCheckedAt = now, lastConnectedTime = now,
            networkGeneration = previous.networkGeneration))
        notificationSince = now
        com.example.vpn.unlock.UnlockDns.refreshPrivateDns(this@RayVpnService)
        onUnlockServerChanged(server, latency)
        XrayLogManager.i("UNLOCK", "Unlock mode on: DNS goes to $server" + (latency?.let { " ($it ms)" } ?: "") +
            "; all other traffic stays on the phone's network.")
    }

    /** The session uses [ip] now: Home, the notification and the service badges follow. */
    private fun onUnlockServerChanged(ip: String, latencyMs: Long?) {
        com.example.vpn.unlock.UnlockDns.setActive(ip, latencyMs)
        mutateState { if (it.isConnected && unlockSession != null) it.copy(activeRoute = "Unlock DNS • $ip", pingMs = latencyMs) else it }
        startForegroundWith(unlockNotification(ip, latencyMs))
        unlockVerifyJob?.cancel()
        unlockVerifyJob = serviceScope.launch { com.example.vpn.unlock.UnlockDns.verifyServices(ip) }
    }

    /** Auto-pick, the manual server or the list changed in the sheet. */
    private fun onUnlockChoiceChanged() {
        val session = unlockSession ?: return
        val choice = com.example.vpn.unlock.UnlockDns.state.value
        session.autoPick = choice.autoPick
        val checks = choice.checks.ifEmpty { session.checks }
        session.updateChecks(checks.filter { it.ip in choice.servers }, choice.manual)
    }

    /** Re-checks every Unlock server (network change); a dead current server is replaced when auto-picking. */
    private fun recheckUnlock(why: String) {
        val ticket = lifecycle.current()
        serviceScope.launch {
            val session = unlockSession ?: return@launch
            XrayLogManager.i("UNLOCK", "Re-checking Unlock DNS servers: $why.")
            com.example.vpn.unlock.UnlockDns.refreshPrivateDns(this@RayVpnService)
            val checks = runCatching { com.example.vpn.unlock.UnlockDns.recheck(this@RayVpnService) }.getOrNull() ?: return@launch
            if (!lifecycle.isCurrent(ticket) || unlockSession !== session) return@launch
            val choice = com.example.vpn.unlock.UnlockDns.state.value
            session.autoPick = choice.autoPick
            session.updateChecks(checks, choice.manual)
        }
    }

    private fun unlockNotification(server: String, latencyMs: Long?): Notification {
        val text = com.example.ui.language.LanguageStore.localized(this)
        val line = latencyMs?.let { text.getString(R.string.unlock_pill, server, it) } ?: text.getString(R.string.unlock_pill_no_ms, server)
        return baseNotification()
            .setContentTitle(text.getString(R.string.unlock_notification_title))
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle()
                .setBigContentTitle(text.getString(R.string.unlock_notification_title))
                .bigText(line + "\n" + text.getString(R.string.unlock_info_only_dns)))
            .setWhen(notificationSince.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .addAction(0, text.getString(R.string.notification_disconnect), disconnectPendingIntent())
            .build()
    }

    /**
     * The user's Stop. The cancelled attempt normally gives the connect lock back at once; when one of its blocking
     * steps does not answer the cancel (socket I/O ignores Thread.interrupt), the teardown does not wait out that
     * step's own time limit (28 s in the build 171 report) but goes ahead after [StopFence.LOCK_GRACE_MS]. The
     * stale attempt was fenced off by the Stop, so it can no longer bring the tunnel or its state back.
     */
    private suspend fun userDisconnect() {
        val locked = StopFence.acquireWithin(connectionMutex)
        try {
            if (!locked) XrayLogManager.w("VPN", "Stop: the cancelled connect attempt is still inside a blocking step; " +
                "tearing down without waiting for it.")
            disconnectLocked()
        } finally {
            if (locked) connectionMutex.unlock()
        }
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
        // A background WARP-relay test belongs to the connection being torn down.
        aetherRelayJob?.cancel()
        aetherRelayJob = null
        // Unlock mode's DNS-only session ends with any teardown (Disconnect, a tunnel connect, revoke).
        unlockSession?.stop()
        unlockSession = null
        unlockVerifyJob?.cancel()
        unlockVerifyJob = null
        com.example.vpn.unlock.UnlockDns.setActive(null, null)
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
        tunForwardJob?.cancel()
        tunForwardJob = null
        videoProbeJob?.cancel()
        videoProbeJob = null
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
        // The meter sits between Xray and the engine program: it goes with Xray.
        com.example.vpn.verify.TunPathStatus.reset()
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
            appOutsideVpn = false
        }

    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleNetworkHandoff()
            }

            override fun onLost(network: Network) {
                scheduleNetworkHandoff()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                scheduleNetworkHandoff()
            }

            override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
                scheduleNetworkHandoff()
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            XrayLogManager.w("NETWORK", "Failed to register network callback: ${e.message}")
        }
    }

    /** Debounce callback bursts, ignore VPN interfaces and rebuild sockets tied to a retired physical link. */
    @Synchronized private fun scheduleNetworkHandoff() {
        val ticket = lifecycle.current()
        handoffJob?.cancel()
        handoffJob = serviceScope.launch {
            delay(500)
            if (!lifecycle.isCurrent(ticket)) return@launch
            val cm = connectivityManager ?: return@launch
            val directDefault = cm.activeNetwork
            val next = cm.allNetworks.mapNotNull { network ->
                cm.getNetworkCapabilities(network)?.takeIf {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }?.let { network to it }
            }.sortedWith(compareByDescending<Pair<Network, NetworkCapabilities>> {
                it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }.thenByDescending { it.first == directDefault }
                .thenByDescending { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) })
                .firstOrNull()?.first
            val signature = next?.let { n -> n.toString() + ":" + cm.getLinkProperties(n)?.linkAddresses.orEmpty().map { it.toString() }.sorted().joinToString(",") }
            if (!handoffPolicy.observe(signature)) return@launch
            if (unlockSession != null) {
                // Unlock mode: no tunnel to rebuild or re-verify; re-check the servers on the new network instead.
                mutateState { it.copy(networkGeneration = it.networkGeneration + 1) }
                runCatching { setUnderlyingNetworks(next?.let { arrayOf(it) } ?: emptyArray()) }
                if (next != null) recheckUnlock("network changed")
                return@launch
            }
            mutateState { com.example.data.model.ConnectionVerification.onNetworkChanged(it) }
            com.example.vpn.connectivity.ConnectivityBrain.refreshSession()
            runCatching { setUnderlyingNetworks(next?.let { arrayOf(it) } ?: emptyArray()) }
            val current = _vpnState.value
            if (!current.isTunnelUp && current.status != ConnectionStatus.RECONNECTING) return@launch
            if (!settingsRepository.getSettings().autoReconnect) return@launch
            updateState(current.copy(status = ConnectionStatus.RECONNECTING))
            // Keep the current capture interface in place while no physical network exists.
            if (next == null) return@launch
            val profile = activeProfile ?: return@launch
            connectJob?.cancel()
            connectJob = serviceScope.launch(AttemptTicket(ticket)) {
                if (lifecycle.isCurrent(ticket)) {
                    XrayLogManager.i("NETWORK", "Physical network changed; rebuilding the tunnel and rechecking traffic.")
                    connect(profile, ticket = ticket)
                }
            }
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

    private fun openAppPendingIntent(): PendingIntent {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun disconnectPendingIntent(): PendingIntent {
        val disconnectIntent = Intent(this, RayVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        return PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun showForegroundNotification(statusText: String): Boolean {
        // A fenced-off attempt (the user pressed Stop) must not put "Trying ..." back over "Stopping".
        if (!StopFence.admits()) return true
        val reconnectIntent = Intent(this, RayVpnService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val reconnectPendingIntent = PendingIntent.getService(
            this,
            2,
            reconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = com.example.ui.language.LanguageStore.localized(this)
        val profileName = activeProfile?.name ?: text.getString(R.string.notification_default_tunnel)

        val notification: Notification = baseNotification()
            .setContentTitle("Maximus — $profileName")
            .setContentText(statusText)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, text.getString(R.string.notification_disconnect), disconnectPendingIntent())
            .addAction(android.R.drawable.ic_menu_rotate, text.getString(R.string.notification_reconnect), reconnectPendingIntent)
            .build()
        return startForegroundWith(notification)
    }

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_maximus)
            .setColor(NOTIFICATION_ACCENT)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    /**
     * Connected: the server name, the engine and a running timer in the header, live speeds below, and
     * expanded, the full server name with the session totals and one Disconnect button.
     */
    private fun connectedNotification(): Notification {
        val state = _vpnState.value
        val server = state.activeProfile?.name ?: activeProfile?.name ?: "Maximus VPN"
        val route = com.example.data.model.ConnectionRoute.withExitCountry(
            state.activeRoute ?: notificationCore, state.exitCountryCode
        )
        val since = notificationSince.takeIf { it > 0 } ?: System.currentTimeMillis()
        return baseNotification()
            .setContentTitle(server)
            .setContentText(ConnectedNotification.contentLine(state.bulkLimited,
                ConnectedNotification.speedLine(state.downloadSpeedBps, state.uploadSpeedBps)))
            .setSubText(route)
            .setWhen(since)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setStyle(NotificationCompat.BigTextStyle()
                .setBigContentTitle(server)
                .bigText(route + "\n" + ConnectedNotification.expandedText(state.downloadSpeedBps, state.uploadSpeedBps,
                    state.downloadBytes, state.uploadBytes)))
            .addAction(0, com.example.ui.language.LanguageStore.localized(this).getString(R.string.notification_disconnect), disconnectPendingIntent())
            .build()
    }

    private fun showConnectedNotification(): Boolean = startForegroundWith(connectedNotification())

    /** Once a second while connected; the service is already in the foreground, so a plain update is enough. */
    private fun refreshConnectedNotification() {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, connectedNotification())
        } catch (e: Exception) {
            XrayLogManager.w("VPN", "Could not refresh the connected notification: ${e.javaClass.simpleName}")
        }
    }

    private fun startForegroundWith(notification: Notification): Boolean {
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
        com.example.xray.RealDelayProbe.skipUselessRetry = false
        com.example.xray.RealDelayProbe.retryMemory.reset()
        com.example.panels.CleanIpOptimizer.socketProtector = null
        com.example.vpn.stealth.recordslice.RecordSliceBridges.protect = null
        com.example.vpn.stealth.recordslice.RecordSliceBridges.resolve = null
        com.example.vpn.stealth.recordslice.RecordSliceBridges.setMss = null
        com.example.vpn.stealth.recordslice.RecordSliceBridges.closeAll()
        com.example.vpn.stealth.recordslice.UplinkShield.networkMss = null
        com.example.xray.XhttpProfiles.networkKey = null
        com.example.vpn.unlock.UnlockDns.protector = com.example.vpn.unlock.UnlockDnsWire.Protector()
        com.example.vpn.unlock.UnlockDns.onChoiceChanged = null
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

