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
        private const val SUBSCRIPTION_REFRESH_DELAY_MS = 5_000L
        /** How long a connect may spend in the LAB's fast recovery before it reports no verified path. */
        private const val FAST_RECOVERY_MS = 90_000L
        /** Re-check interval for a connection whose last checks all passed. */
        private const val PING_HEALTHY_MS = 30_000L
        /** How long a clean-address scan may take before the connect goes on without one. */
        private const val CLEAN_IP_SCAN_LIMIT_MS = 12_000L
        /** Requests through a new tunnel before it counts as unverified, and the pause between them. */
        private const val VERIFY_ATTEMPTS = 3
        private const val VERIFY_RETRY_MS = 1_500L

        const val NOTIFICATION_CHANNEL_ID = "maximus_vpn_channel"
        const val NOTIFICATION_ID = 1001

        private val _vpnState = MutableStateFlow(ConnectionState())
        val vpnState: StateFlow<ConnectionState> = _vpnState.asStateFlow()

        fun updateState(state: ConnectionState) {
            _vpnState.value = state
        }

        /** Changes the state atomically, so concurrent watchers never overwrite each other's fields. */
        fun mutateState(change: (ConnectionState) -> ConnectionState) {
            while (true) {
                val current = _vpnState.value
                if (_vpnState.compareAndSet(current, change(current))) return
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

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

    /**
     * The modes route differently (see OperatingModePolicy), so a switch while connected reconnects
     * the same server under the new mode. The traffic block holds during the reconnect.
     */
    private suspend fun handleLiveModeSwitch(
        oldMode: com.example.data.model.OperationalMode,
        newMode: com.example.data.model.OperationalMode
    ) {
        val safeProfileName = com.example.core.SecretRedactor.redact(activeProfile?.name ?: "")
        XrayLogManager.i("VPN", "Mode switched from ${oldMode.name} to ${newMode.name}; reconnecting '$safeProfileName' under the new mode.")
        activeProfile?.let { connect(it) }
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
                connectJob?.cancel()
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

    /** What worked on each carrier or Wi-Fi: stealth alternates, kinds of connection, probe time limits. */
    private val networkMemory by lazy {
        val prefs = getSharedPreferences("network_memory", Context.MODE_PRIVATE)
        com.example.vpn.smart.NetworkMemory(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

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
        com.example.vpn.smart.ServerRace().run(candidates, resolve, timeoutSec, onFailure = {
            networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(it))
        }, disguiseOnly = dead)?.let { return it }
        val best = candidates.firstOrNull() ?: return null
        val choice = runCatching { finder.choose(best, resolve, all, firstFailed = true) }.getOrNull() ?: return null
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
            (profile.security.equals("tls", true) || profile.security.equals("reality", true))
        if (!fronted) return null
        showForegroundNotification("Looking for a clean address...")
        val best = withTimeoutOrNull(CLEAN_IP_SCAN_LIMIT_MS) {
            runCatching { com.example.panels.CleanIpOptimizer.findBestCleanIpSync() }.getOrNull()
        } ?: return null
        val candidate = com.example.panels.CleanIpOptimizer.variant(profile, best)
        if (com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(candidate) != null) return null
        val outcome = com.example.xray.RealDelayProbe.measure(candidate, timeoutSec)
        return (outcome as? com.example.xray.RealDelayProbe.Outcome.Delay)?.let {
            XrayLogManager.i("SMART", "Reached ${com.example.core.SecretRedactor.redact(profile.name)} through a clean address (${it.latencyMs} ms).")
            com.example.vpn.smart.ServerRace.Winner(candidate, profile, it.latencyMs)
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
        val outcome = com.example.xray.RealDelayProbe.measure(warp, timeoutSec)
        return (outcome as? com.example.xray.RealDelayProbe.Outcome.Delay)?.let {
            XrayLogManager.i("SMART", "Cloudflare WARP carries traffic (${it.latencyMs} ms).")
            com.example.vpn.smart.ServerRace.Winner(warp, warp, it.latencyMs)
        }
    }

    private suspend fun connectLocked(
        userProfile: VlessProfile,
        smart: Boolean = false,
        exclude: Set<String> = emptySet(),
        startedByUser: Boolean = false,
        force: Boolean = false
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
            attemptedProfileId = requestedProfile.id, networkGeneration = previous.networkGeneration
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
            disconnectResources()
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
            val raceFirst = smart || policy.alwaysSmartConnect

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

            // 3. Diagnostic step 3: Validate VLESS Profile Configuration
            var failure = com.example.vpn.safety.FailClosedPolicy.Failure.INVALID_PROFILE
            try {
                // Engine programs (Mihomo, Psiphon, the DNS tunnel) look up their own servers privately.
                if (profile.profileType != ProfileType.XRAY_JSON && !com.example.vpn.sidecar.Sidecars.isEngineProfile(profile) &&
                    !isLiteralIp(profile.address)) {
                    // Resolve outside the tunnel: once traffic is captured, DNS for the proxy itself
                    // would loop back into the VPN.
                    failure = com.example.vpn.safety.FailClosedPolicy.Failure.UNRESOLVABLE_SERVER
                    profile = resolveEndpoint(profile)
                    failure = com.example.vpn.safety.FailClosedPolicy.Failure.INVALID_PROFILE
                    XrayLogManager.i("VPN", "[DIAGNOSTICS] 3. Server host resolved outside the tunnel")
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
            var raced = false
            // What the pre-flight proved (PathGate): a real request failed on the selected path, or an
            // engine that can only be judged after it starts was chosen.
            var provenDead = false
            var engineAdopted = false
            activeRecovery = null
            fun adopt(win: com.example.vpn.smart.ServerRace.Winner) {
                com.example.vless.VlessValidator.validate(win.profile)
                com.example.vpn.engine.RuntimeCapabilities.requireSupported(win.profile)
                requestedProfile = win.owner
                activeProfile = win.owner
                profile = win.profile
                raced = true
                win.variantKey?.let { networkMemory.variants(network)[win.owner.id] = it }
                networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(win.profile), win.latencyMs)
                mutateState { it.copy(activeProfile = win.owner, attemptedProfileId = win.owner.id) }
            }
            // A recovery engine (Psiphon, Tor) cannot be measured before it starts: it is chosen, not
            // counted as a success; the traffic check after start decides.
            fun adoptEngine(engine: VlessProfile) {
                com.example.vless.VlessValidator.validate(engine)
                com.example.vpn.engine.RuntimeCapabilities.requireSupported(engine)
                requestedProfile = engine
                activeProfile = engine
                profile = engine
                engineAdopted = true
                mutateState { it.copy(activeProfile = engine, attemptedProfileId = engine.id) }
            }
            val finder = com.example.vpn.stealth.StealthPathFinder(
                memory = networkMemory.variants(network),
                firstTimeoutSec = timeouts.firstSec,
                alternateTimeoutSec = timeouts.alternateSec
            )
            var firstFailed = false
            if (raceFirst) {
                // The chosen server alone first, so a working one costs a single request; only when it
                // carries no traffic are the other servers raced (several kinds of connection per round).
                showForegroundNotification("Finding the fastest server...")
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.ORDINARY_PREFLIGHT)
                val first = finder.firstPath(requestedProfile, profile)
                val skip = recentlyFailed(first.profile)
                if (skip) XrayLogManager.i("SMART", "Selected server skipped: it failed a real request on this network moments ago.")
                when (val outcome = if (skip) com.example.xray.RealDelayProbe.Outcome.Failed("failed moments ago on this network", request = false)
                    else com.example.xray.RealDelayProbe.measure(first.profile, timeouts.firstSec)) {
                    is com.example.xray.RealDelayProbe.Outcome.Delay -> {
                        profile = first.profile
                        raced = true
                        networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(first.profile), outcome.latencyMs)
                    }
                    is com.example.xray.RealDelayProbe.Outcome.Failed -> {
                        firstFailed = true
                        provenDead = true
                        networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(first.profile))
                        ladder.advance(com.example.vpn.connectivity.RecoveryStage.SAVED_PROFILE_RACE)
                        raceServers(exclude + requestedProfile.id, network, timeouts.alternateSec, finder, retryDisguised = true)?.let { adopt(it) }
                    }
                    is com.example.xray.RealDelayProbe.Outcome.NotRun -> Unit
                }
            }

            // 3a. A recovery candidate that carried traffic for this config on this network before (FIX BPB
            // or earlier recovery): a derived copy, the saved config itself is unchanged. One request decides;
            // a failure counts towards its rollback and the normal path search continues.
            if (!raced) {
                val recovery = runCatching { com.example.RayApplication.instance.bpbRecovery.active(requestedProfile, network, base = profile) }.getOrNull()
                if (recovery != null) {
                    showForegroundNotification("Trying the recovered settings...")
                    when (val outcome = com.example.xray.RealDelayProbe.measure(recovery.profile, timeouts.firstSec)) {
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
                }
            }

            // 3b. Find a path that carries traffic before the core starts: the saved profile, its stealth
            // alternates (split handshake, other fingerprint, ECH, UDP junk) or another kind on the same
            // server. The probe's sockets bypass the traffic-blocking interface (RealDelayProbe.socketProtector).
            if (!raced && com.example.vpn.stealth.StealthVariants.of(profile).isNotEmpty()) {
                showForegroundNotification("Finding a working route...")
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.SAFE_VARIANTS)
                val resolvedRequested = profile
                val siblings = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                val choice = finder.choose(
                    requested = requestedProfile,
                    resolve = { p ->
                        if (p.id == requestedProfile.id) resolvedRequested
                        else if (p.profileType != ProfileType.XRAY_JSON && !com.example.vpn.sidecar.Sidecars.isEngineProfile(p) &&
                            !isLiteralIp(p.address)) resolveEndpoint(p) else p
                    },
                    siblings = siblings,
                    firstFailed = firstFailed
                )
                if (choice.owner.id != requestedProfile.id) {
                    com.example.vless.VlessValidator.validate(choice.profile)
                    com.example.vpn.engine.RuntimeCapabilities.requireSupported(choice.profile)
                    requestedProfile = choice.owner
                    activeProfile = choice.owner
                    mutateState { it.copy(activeProfile = choice.owner, attemptedProfileId = choice.owner.id) }
                }
                profile = choice.profile
                choice.latencyMs?.let {
                    raced = true
                    networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(choice.profile), it)
                }
                if (choice.nothingWorked) provenDead = true
                // Nothing on this server carried traffic: try the other saved servers now rather than
                // starting a dead connection and waiting for the watchdog.
                if (choice.nothingWorked) networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(requestedProfile))
                if (choice.nothingWorked && !raceFirst && settings.autoFailoverEnabled) {
                    showForegroundNotification("Server not answering, trying others...")
                    raceServers(exclude + requestedProfile.id + userProfile.id, network, timeouts.alternateSec, finder, retryDisguised = false)
                        ?.let { adopt(it) }
                }
                // Nothing worked, and the server sits behind Cloudflare: its address may be blocked
                // while other Cloudflare addresses are not. Scan for one and try the same server there.
                if (choice.nothingWorked && !raced) {
                    cleanIpPath(requestedProfile, timeouts.alternateSec)?.let { adopt(it) }
                }
                if (choice.nothingWorked && !raced && policy.mode == com.example.data.model.OperationalMode.GOD_MODE) {
                    warpPath(timeouts.alternateSec)?.let { adopt(it) }
                }
                if (choice.nothingWorked && !raced && policy.mode == com.example.data.model.OperationalMode.GOD_MODE) {
                    // WARP again, but over MASQUE (HTTP/3 to Cloudflare on 443), for networks that block
                    // the plain WireGuard UDP the step above uses. It registers its own device, so it is
                    // only taken when this build carries the program; verified with a real request once it runs.
                    val masque = com.example.vpn.sidecar.MasqueSidecar.profile()
                    val missing = com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(masque)
                    if (missing == null) {
                        XrayLogManager.i("SMART", "WARP UDP did not carry traffic; trying WARP over MASQUE (HTTP/3).")
                        adoptEngine(masque)
                    } else {
                        XrayLogManager.i("SMART", "MASQUE not tried: not available ($missing). Not a failure: nothing was tested.")
                    }
                }
                if (choice.nothingWorked && !raced && policy.mode == com.example.data.model.OperationalMode.GOD_MODE) {
                    // Psiphon finds its own servers. It cannot be measured before it starts, so it is
                    // only taken when this build carries it; if it finds nothing, traffic stays blocked.
                    val psiphon = com.example.vpn.sidecar.PsiphonSidecar.profile()
                    val missing = com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(psiphon)
                    if (missing == null) {
                        XrayLogManager.i("SMART", "Nothing else carried traffic; starting Psiphon (verified with a real request once it runs).")
                        adoptEngine(psiphon)
                    } else {
                        XrayLogManager.i("SMART", "Psiphon not tried: not configured ($missing). Not a failure: nothing was tested.")
                    }
                }
                if (choice.nothingWorked && !raced && policy.mode == com.example.data.model.OperationalMode.GOD_MODE) {
                    // Last of all, Tor over Snowflake: slow, but it needs nothing of the user's.
                    val tor = com.example.vpn.sidecar.TorSidecar.profile()
                    val missing = com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(tor)
                    if (missing == null) {
                        XrayLogManager.i("SMART", "Nothing else carried traffic; starting Tor over Snowflake (verified with a real request once it runs).")
                        adoptEngine(tor)
                    } else {
                        XrayLogManager.i("SMART", "Tor not tried: not configured ($missing). Not a failure: nothing was tested.")
                    }
                }
            }

            // Always a pre-flight: a server that was not tested above (no Smart Connect, no stealth variants)
            // gets one real request now, so a dead server is never started blindly.
            if (!raced && !provenDead && !engineAdopted && !force && !com.example.vpn.sidecar.Sidecars.isEngineProfile(profile)) {
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.ORDINARY_PREFLIGHT)
                if (recentlyFailed(profile)) {
                    provenDead = true
                    XrayLogManager.i("SMART", "Selected server not retried: it failed a real request on this network moments ago.")
                } else {
                    showForegroundNotification("Testing ${profile.name}...")
                    when (val outcome = com.example.xray.RealDelayProbe.measure(profile, timeouts.firstSec)) {
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
            }

            // Escalation before giving up: the LAB's fast recovery (Connect goal) over every other saved family
            // and the built-in recovery engines, stopping at the first path that carries a real request.
            var recoveryExhausted = false
            if (!raced && !engineAdopted && provenDead && !force && (raceFirst || settings.autoFailoverEnabled)) {
                ladder.advance(com.example.vpn.connectivity.RecoveryStage.RECOVERY_ENGINES)
                showForegroundNotification("No server answered; trying recovery methods...")
                val skipRefs = setOf(com.example.vpn.connectivity.PathRef.of(requestedProfile), com.example.vpn.connectivity.PathRef.of(userProfile))
                val deadline = System.currentTimeMillis() + FAST_RECOVERY_MS
                val outcome = runCatching {
                    withTimeoutOrNull(FAST_RECOVERY_MS + 10_000L) {
                        com.example.RayApplication.instance.lab.fastRecovery(skipRefs + exclude + requestedProfile.id + userProfile.id, deadline,
                            engineWindow = { test -> engineWindow(test) }) { step -> showForegroundNotification(step) }
                    }
                }.onFailure { XrayLogManager.w("SMART", "Recovery search stopped: ${it.message}") }.getOrNull()
                val found = outcome?.result
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
                    XrayLogManager.w("SMART", "Recovery found no path: ${outcome?.why ?: "not run"}.")
                }
            }

            // No blind connections: a server whose real request just failed here, with nothing verified in
            // its place, is not started (PathGate). The user can still choose "Connect anyway".
            val gate = com.example.vpn.smart.PathGate.decide(verified = raced, provenDead = provenDead,
                engineAdopted = engineAdopted, forced = force)
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
                ).copy(canConnectAnyway = true, recoveryStage = ladder.stage.title))
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
            val safeMtu = settings.mtu.coerceIn(1280, 1500)
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
            if (engineChain != null) profile = startChain(engineChain, settings)
            else if (sidecarEngine != null) profile = startSidecar(sidecarEngine, profile, settings)
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
            mutateState { if (it.attemptId == attemptId) it.copy(status = ConnectionStatus.VERIFYING, vpnIp = "172.19.0.1", errorMessage = null) else it }
            showForegroundNotification("Verifying traffic through ${profile.name}...")
            val check = verifyTraffic()
            book.recordPath(com.example.vpn.connectivity.EvidenceSource.SMART_CONNECT, com.example.vpn.connectivity.MeasurementType.TUNNEL_TRAFFIC,
                com.example.vpn.connectivity.PathRef.of(profile), com.example.vpn.stealth.ConnectionKind.of(profile), check.first != null,
                check.first, check.second?.name)
            if (check.first != null) ladder.advance(com.example.vpn.connectivity.RecoveryStage.CONNECTED_VERIFIED)
            else activeMtuKey?.let { com.example.vpn.lab.MtuIntelligence.cache.failed(it); activeMtuKey = null }
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
                if (_vpnState.value.status != ConnectionStatus.CONNECTED) return@launch
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
                tunnelProbe = { com.example.vpn.diagnostics.LiveTunnelProbe.latency(this@RayVpnService) },
                // Only the native core's own sockets carry the tunnel's data; other engines are always checked.
                passive = if (activeEngine is NativeTunVpnEngine) com.example.vpn.smart.PassiveHealth() else null,
                onTriggerSwitch = { newProfile, reason ->
                    // A recovered path that stopped carrying traffic counts towards its rollback.
                    activeRecovery?.let { com.example.RayApplication.instance.bpbRecovery.recordOutcome(it, success = false) }
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
                val usesHostHeader = profile.transport.lowercase() in setOf("ws", "xhttp", "httpupgrade", "splithttp", "h2", "http")
                return profile.copy(
                    address = resolved.address,
                    // The copy is the same path: evidence about it belongs to the saved config.
                    canonicalFingerprint = profile.effectiveFingerprint,
                    sni = if (usesTls && profile.sni.isBlank()) hostName else profile.sni,
                    host = if (usesHostHeader && profile.host.isBlank()) hostName else profile.host
                )
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
        val process = engine.startInApp(launch, context)
            ?: com.example.vpn.sidecar.SidecarProcess.start(engine.id, launch, workDir, context.socksPort)
        sidecar = process
        if (!process.awaitReady(launch.readyTimeoutMs)) {
            process.stop()
            sidecar = null
            error("The ${engine.id} engine did not start")
        }
        XrayLogManager.i("VPN", "Engine ${engine.id} is running on a local port")
        val watched = process
        serviceScope.launch(Dispatchers.IO) {
            while (isActive && sidecar === watched && watched.isAlive) delay(1_000)
            if (sidecar === watched) {
                XrayLogManager.w("VPN", "Engine ${engine.id} stopped; traffic stays blocked until another path works")
                failoverManager?.reportTunnelError("engine ${engine.id} stopped")
            }
        }
        return com.example.vpn.sidecar.SidecarChain.xrayProfile(profile, context, launch)
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
                val process = engine.startInApp(launch, context)
                    ?: com.example.vpn.sidecar.SidecarProcess.start(engine.id, launch, context.workDir, context.socksPort)
                if (!process.awaitReady(launch.readyTimeoutMs)) {
                    process.stop()
                    error("The ${engine.id} engine did not start")
                }
                process to launch
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
            test()
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
                    val sample = com.example.vpn.diagnostics.LiveTunnelProbe.measure(this@RayVpnService)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (_vpnState.value.attemptId == attemptId) passEvent("tunnel.recheck", sample.latencyMs)
                    passive?.recordActiveSuccess()
                    okStreak++
                    mutateState {
                        com.example.data.model.ConnectionVerification.afterCheck(it, attemptId, true, sample.latencyMs, System.currentTimeMillis())
                            .let { s -> if (s.attemptId == attemptId) s.copy(exitCountryCode = sample.country) else s }
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
                            .let { s -> if (s.attemptId == attemptId) s.copy(exitCountryCode = null) else s }
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
                perStepTimeoutMs = 11_000, perConfigTimeoutMs = 40_000
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
                com.example.vpn.diagnostics.LiveTunnelProbe.latency(this@RayVpnService)
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

    private fun disconnectResources() {
        // Establish the replacement blackhole before stopping either engine. Native
        // startup failures may close their fd, so retaining only that fd is insufficient.
        val previousInterface = vpnInterface
        if (protectionRequested) {
            try { vpnInterface = createBlockingInterface() }
            catch (e: Exception) { XrayLogManager.e("VPN", "Unable to replace traffic protection interface", e) }
        }
        durationJob?.cancel()
        durationJob = null
        pingJob?.cancel()
        pingJob = null

        serviceTxBytes.set(0)
        serviceRxBytes.set(0)

        failoverManager?.stopMonitoring()
        failoverManager = null

        tunnelManager?.stop()
        tunnelManager = null

        // Stop Xray before closing the TUN descriptor it owns.
        activeEngine.stop()
        sidecar?.stop()
        sidecar = null

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
