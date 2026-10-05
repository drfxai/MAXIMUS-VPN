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
        private const val SUBSCRIPTION_REFRESH_DELAY_MS = 5_000L
        /** How long a clean-address scan may take before the connect goes on without one. */
        private const val CLEAN_IP_SCAN_LIMIT_MS = 12_000L

        const val NOTIFICATION_CHANNEL_ID = "maximus_vpn_channel"
        const val NOTIFICATION_ID = 1001

        private val _vpnState = MutableStateFlow(ConnectionState())
        val vpnState: StateFlow<ConnectionState> = _vpnState.asStateFlow()

        fun updateState(state: ConnectionState) {
            _vpnState.value = state
        }

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
    private var activeEngine: VpnEngine = XrayEngineImpl.instance
    private var failoverManager: com.example.vpn.smart.FailoverManager? = null
    private val serviceTxBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val serviceRxBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val protectionFailureHandled = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var serverRepository: ServerRepository
    private lateinit var settingsRepository: SettingsRepository
    private var activeProfile: VlessProfile? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var settingsObserverJob: Job? = null
    private var lastObservedMode: com.example.data.model.OperationalMode? = null

    override fun onCreate() {
        super.onCreate()
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
                if (prevMode != null && prevMode != settings.operationalMode && _vpnState.value.isConnected) {
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

                connectJob?.cancel()
                connectJob = serviceScope.launch {
                    val profile = if (!profileId.isNullOrBlank()) {
                        serverRepository.getProfileById(profileId)
                    } else null

                    val targetProfile = profile
                        ?: settingsRepository.getSettings().selectedProfileId?.let { serverRepository.getProfileById(it) }
                        ?: serverRepository.getAllProfilesOnce().firstOrNull()

                    if (targetProfile != null) {
                        connect(targetProfile, smart = smart, startedByUser = true)
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
                connectJob?.cancel()
                connectJob = null
                serviceScope.launch {
                    disconnect()
                }
            }
            ACTION_RECONNECT -> {
                val fgOk = showForegroundNotification("Reconnecting Maximus VPN...")
                if (!fgOk) {
                    if (!protectionRequested) stopSelf()
                    return START_NOT_STICKY
                }
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
        startedByUser: Boolean = false
    ): Unit = connectionMutex.withLock { connectLocked(profile, smart, exclude, startedByUser) }

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
        val all = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
        val candidates = com.example.vpn.smart.ServerRace.rank(
            all, networkMemory.workingKinds(network), networkMemory.recentFailures(network), exclude = exclude
        )
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

    private suspend fun connectLocked(
        userProfile: VlessProfile,
        smart: Boolean = false,
        exclude: Set<String> = emptySet(),
        startedByUser: Boolean = false
    ): Unit = withContext(Dispatchers.IO) {
        // The saved profile the connection belongs to; a same-server switch below can change it.
        var requestedProfile = userProfile
        // Engines receive [profile]; a hostname endpoint is replaced by its resolved IP below.
        var profile = requestedProfile
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
                updateState(_vpnState.value.copy(
                    status = ConnectionStatus.FAILED,
                    errorMessage = "VPN permission not granted by Android system."
                ))
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

            updateState(ConnectionState(
                status = ConnectionStatus.PREPARING,
                activeProfile = profile
            ))

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
                if (profile.profileType != ProfileType.XRAY_JSON && !isLiteralIp(profile.address)) {
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
                updateState(ConnectionState(
                    status = ConnectionStatus.FAILED,
                    activeProfile = requestedProfile,
                    errorMessage = if (release) "Cannot use this profile: ${e.localizedMessage}"
                        else "Traffic blocked: ${e.localizedMessage}. Disconnect to use the network without the VPN."
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
                    if (policy.mode == com.example.data.model.OperationalMode.DAILY && com.example.vpn.smart.NetworkEnvironment.suggestsGodMode(report)) {
                        XrayLogManager.w("SMART", "Filtering on this network is heavy; GOD MODE proxies everything and tests every server.")
                    }
                }
            var raced = false
            fun adopt(win: com.example.vpn.smart.ServerRace.Winner) {
                com.example.vless.VlessValidator.validate(win.profile)
                com.example.vpn.engine.RuntimeCapabilities.requireSupported(win.profile)
                requestedProfile = win.owner
                activeProfile = win.owner
                profile = win.profile
                raced = true
                win.variantKey?.let { networkMemory.variants(network)[win.owner.id] = it }
                networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(win.profile), win.latencyMs)
                updateState(_vpnState.value.copy(activeProfile = win.owner))
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
                val first = finder.firstPath(requestedProfile, profile)
                when (val outcome = com.example.xray.RealDelayProbe.measure(first.profile, timeouts.firstSec)) {
                    is com.example.xray.RealDelayProbe.Outcome.Delay -> {
                        profile = first.profile
                        raced = true
                        networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(first.profile), outcome.latencyMs)
                    }
                    is com.example.xray.RealDelayProbe.Outcome.Failed -> {
                        firstFailed = true
                        networkMemory.recordFailure(network, com.example.vpn.stealth.ConnectionKind.of(first.profile))
                        raceServers(exclude + requestedProfile.id, network, timeouts.alternateSec, finder, retryDisguised = true)?.let { adopt(it) }
                    }
                    is com.example.xray.RealDelayProbe.Outcome.NotRun -> Unit
                }
            }

            // 3b. Find a path that carries traffic before the core starts: the saved profile, its stealth
            // alternates (split handshake, other fingerprint, ECH, UDP junk) or another kind on the same
            // server. The probe's sockets bypass the traffic-blocking interface (RealDelayProbe.socketProtector).
            if (!raced && com.example.vpn.stealth.StealthVariants.of(profile).isNotEmpty()) {
                showForegroundNotification("Finding a working route...")
                val resolvedRequested = profile
                val siblings = runCatching { serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())
                val choice = finder.choose(
                    requested = requestedProfile,
                    resolve = { p ->
                        if (p.id == requestedProfile.id) resolvedRequested
                        else if (p.profileType != ProfileType.XRAY_JSON && !isLiteralIp(p.address)) resolveEndpoint(p) else p
                    },
                    siblings = siblings,
                    firstFailed = firstFailed
                )
                if (choice.owner.id != requestedProfile.id) {
                    com.example.vless.VlessValidator.validate(choice.profile)
                    com.example.vpn.engine.RuntimeCapabilities.requireSupported(choice.profile)
                    requestedProfile = choice.owner
                    activeProfile = choice.owner
                }
                profile = choice.profile
                choice.latencyMs?.let {
                    networkMemory.recordSuccess(network, com.example.vpn.stealth.ConnectionKind.of(choice.profile), it)
                }
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
            }

            // 4. Diagnostic step 4 & 5: Configure and establish Android VpnService TUN interface
            val safeMtu = settings.mtu.coerceIn(1280, 1500)
            val primaryDns = "172.19.0.2"
            val builder = Builder()
                .setSession("Maximus - ${profile.name}")
                .setMtu(safeMtu)
                .setBlocking(true)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(primaryDns)
                // Always capture IPv6. Unsupported engines drop it inside the TUN.
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)

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
                    errorMessage = "VPN interface establishment failed (permission revoked or another VPN active)."
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
                throw startResult.exception
            }
            engineBreaker.recordSuccess(engineId)

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

            // 10. Update Connection State to CONNECTED
            val startTime = System.currentTimeMillis()
            updateState(_vpnState.value.copy(
                status = ConnectionStatus.CONNECTED,
                activeProfile = requestedProfile,
                lastConnectedTime = startTime,
                vpnIp = "172.19.0.1",
                errorMessage = null
            ))

            showForegroundNotification("Connected to ${profile.name}")
            XrayLogManager.i("VPN", "[DIAGNOSTICS] 10. Connection lifecycle complete. Final state: CONNECTED.")

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
                onTriggerSwitch = { newProfile, reason ->
                    val degraded = requestedProfile.id
                    // The kind that just stopped carrying traffic goes last in the race.
                    networkMemory.recordFailure(
                        com.example.vpn.smart.NetworkKey.current(this@RayVpnService),
                        com.example.vpn.stealth.ConnectionKind.of(profile)
                    )
                    serviceScope.launch {
                        XrayLogManager.w("FAILOVER", "Executing auto-failover, '${newProfile.name}' first: $reason")
                        // Race the saved servers so the switch lands on one that carries traffic now.
                        connect(newProfile, smart = true, exclude = setOf(degraded))
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
                errorMessage = e.localizedMessage ?: "Unknown connection failure"
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
        for (network in networks) {
            try {
                val resolved = EndpointResolver.resolve(
                    hostName,
                    system = { network.getAllByName(it).toList() },
                    open = { url -> network.openConnection(url) as java.net.HttpURLConnection },
                    private = privateServerLookup
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

    private fun ensureBlockingInterface() {
        if (vpnInterface == null) vpnInterface = createBlockingInterface()
    }

    private fun createBlockingInterface(): ParcelFileDescriptor = Builder().setSession("Maximus traffic protection")
            .setMtu(1280).setBlocking(true)
            .addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0)
            .addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
            .addDnsServer("172.19.0.2").establish()
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
            while (isActive && _vpnState.value.isConnected) {
                delay(1000)
                val durationSec = (System.currentTimeMillis() - startTime) / 1000
                if (!activeEngine.isRunning()) {
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
        pingJob = serviceScope.launch {
            while (isActive && _vpnState.value.isConnected) {
                try {
                    val sample = com.example.vpn.diagnostics.LiveTunnelProbe.measure(this@RayVpnService)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (_vpnState.value.isConnected) updateState(_vpnState.value.copy(
                        pingMs = sample.latencyMs, exitCountryCode = sample.country,
                        pingCheckedAt = System.currentTimeMillis()))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (_vpnState.value.isConnected) updateState(_vpnState.value.copy(
                        pingMs = null, exitCountryCode = null, pingCheckedAt = System.currentTimeMillis()))
                }
                delay(10000)
            }
        }
    }

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
            downloadSpeedBps = 0
        ))

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
                if (_vpnState.value.status == ConnectionStatus.RECONNECTING) {
                    activeProfile?.let { prof ->
                        serviceScope.launch {
                            XrayLogManager.appendLog("Network restored. Auto-reconnecting to ${prof.name}...", "VPN")
                            connect(prof)
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                XrayLogManager.appendLog("Underlying network connection lost.", "NETWORK")
                val settings = settingsRepository.getSettings()
                if (_vpnState.value.isConnected && settings.autoReconnect) {
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
        super.onDestroy()
    }
}
