package com.example

import android.app.Application
import com.example.data.database.AppDatabase
import com.example.data.model.VlessProfile
import com.example.data.repository.BenchmarkRepository
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.SubscriptionRepository
import com.example.vpn.benchmark.BenchmarkEngine
import com.example.vpn.subscription.SubscriptionManager
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RayApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var serverRepository: ServerRepository
        private set

    lateinit var subscriptionRepository: SubscriptionRepository
        private set

    lateinit var benchmarkRepository: BenchmarkRepository
        private set

    lateinit var subscriptionManager: SubscriptionManager
        private set

    lateinit var benchmarkEngine: BenchmarkEngine
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    /** Up to three free configs that carried verified traffic here; a refresh never deletes them. */
    val lastKnownGood: com.example.vpn.hub.LastKnownGoodPool by lazy {
        val prefs = getSharedPreferences("free_last_known_good", MODE_PRIVATE)
        com.example.vpn.hub.LastKnownGoodPool(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

    /** Free configs a refresh dropped while they were in use; removed after disconnect. */
    val retainedFreeConfigs: com.example.vpn.hub.RetainedFreeConfigs by lazy {
        val prefs = getSharedPreferences("free_retained", MODE_PRIVATE)
        com.example.vpn.hub.RetainedFreeConfigs(
            load = { prefs.getStringSet("ids", emptySet()).orEmpty().toSet() },
            save = { prefs.edit().putStringSet("ids", it).apply() }
        )
    }

    /** What BPB recovery learned here: derived settings that worked, per original config and network. */
    val recoveryLedger: com.example.vpn.connectivity.RecoveryLedger by lazy {
        val prefs = getSharedPreferences("recovery_ledger", MODE_PRIVATE)
        com.example.vpn.connectivity.RecoveryLedger(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

    /** Recovers degraded BPB / Cloudflare-fronted configs with derived copies; originals are never changed. */
    val bpbRecovery: com.example.vpn.connectivity.BpbRecoveryEngine by lazy {
        com.example.vpn.connectivity.BpbRecoveryEngine(ledger = recoveryLedger, fragmentReverted = { key, network ->
            fragmentProfiles.decide(key, network) == com.example.vpn.connectivity.FragmentProfileEngine.Decision.REVERT
        })
    }

    /** Iran intelligence rules from the signed free list's manifest; they only nudge the order servers are tried in. */
    val iranIntel: com.example.vpn.connectivity.IranIntelligence.Store by lazy {
        val prefs = getSharedPreferences("iran_intel", MODE_PRIVATE)
        com.example.vpn.connectivity.IranIntelligence.Store(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

    /** Real DNS queries per network: resolver order and names this network's DNS blocks. */
    val dnsResilience: com.example.vpn.connectivity.DnsResilienceEngine by lazy {
        val prefs = getSharedPreferences("dns_resilience", MODE_PRIVATE)
        com.example.vpn.connectivity.DnsResilienceEngine(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

    /** Edge addresses that completed real exchanges on each network; the validated ones feed recovery. */
    val endpointScores: com.example.vpn.connectivity.EndpointScoringEngine by lazy {
        val prefs = getSharedPreferences("endpoint_scores", MODE_PRIVATE)
        com.example.vpn.connectivity.EndpointScoringEngine(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

    /** Fragmentation trials against the plain config, so fragmentation that makes things worse is reverted. */
    val fragmentProfiles: com.example.vpn.connectivity.FragmentProfileEngine by lazy {
        val prefs = getSharedPreferences("fragment_trials", MODE_PRIVATE)
        com.example.vpn.connectivity.FragmentProfileEngine(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

    /** This phone's measurements of free configs, kept apart from the list builder's global checks. */
    val freeConfigEvidence: com.example.vpn.hub.FreeConfigEvidenceStore by lazy {
        val prefs = getSharedPreferences("free_config_evidence", MODE_PRIVATE)
        com.example.vpn.hub.FreeConfigEvidenceStore(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

    /**
     * Structured diagnostics: the persisted event log, crash/ANR/stall/memory evidence, and one event
     * per connection state change, carrying the session and attempt ids. Disconnect cancels the
     * session's background tests.
     */
    private fun startDiagnostics() {
        val events = com.example.vpn.diagnostics.events.EventLog
        val tests = com.example.vpn.diagnostics.events.Tests.registry
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        events.context = {
            val s = com.example.vpn.RayVpnService.vpnState.value
            com.example.vpn.diagnostics.events.EventLog.Context(
                sessionId = s.sessionId, attemptId = s.attemptId, engine = s.activeEngineName,
                network = com.example.vpn.smart.NetworkCapabilityDetector.last?.key()
            )
        }
        events.init(java.io.File(filesDir, "diagnostics/events"))
        com.example.vpn.diagnostics.events.RuntimeHealth.install(this) { work -> scope.launch(Dispatchers.IO) { work() } }
        scope.launch {
            var previous = com.example.vpn.RayVpnService.vpnState.value
            com.example.vpn.RayVpnService.vpnState.collect { state ->
                if (state.sessionId != previous.sessionId) {
                    if (state.sessionId == null) tests.endSession() else tests.beginSession(state.sessionId)
                }
                com.example.vpn.diagnostics.events.ConnectionEvents.between(previous, state, System.currentTimeMillis()) {
                    com.example.vpn.diagnostics.events.DiagEvent.profileRef(it)
                }.forEach { events.record(it) }
                previous = state
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Install Global Uncaught Exception Handler to capture any UI/Thread/Coroutine crash for agent diagnostics
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                XrayLogManager.fatal(
                    tag = "CRASH",
                    message = "Uncaught exception on thread '${thread.name}': ${throwable.localizedMessage}",
                    throwable = throwable
                )
            } catch (_: Exception) {}
            // The crash event reaches disk before the process dies.
            try { com.example.vpn.diagnostics.events.EventLog.flush(1000) } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        XrayLogManager.i("APP", "Maximus Application starting. Initializing database and subsystems...")
        startDiagnostics()

        // Real-delay probes (server ping, FIX BPB) dial the server themselves; resolve its name without
        // trusting a filtering network's DNS answer. While the VPN runs the probe does not run at all.
        com.example.xray.RealDelayProbe.endpointResolver = { profile ->
            val host = profile.address.trim().removePrefix("[").removeSuffix("]")
            if (profile.profileType == com.example.data.model.ProfileType.XRAY_JSON ||
                com.example.vpn.tunnel.ProxyDnsTransport.isLiteralAddress(host) ||
                com.example.vpn.VpnController.connectionState.value.isTunnelUp
            ) profile
            else runCatching {
                val resolved = com.example.vpn.EndpointResolver.resolve(
                    host,
                    system = { java.net.InetAddress.getAllByName(it).toList() },
                    open = { url -> url.openConnection() as java.net.HttpURLConnection }
                )
                val usesTls = profile.security.equals("tls", true) || profile.security.equals("reality", true)
                profile.copy(
                    address = resolved.address,
                    sni = if (usesTls && profile.sni.isBlank()) host else profile.sni,
                    host = profile.host.ifBlank { if (profile.transport.lowercase() in setOf("ws", "httpupgrade", "xhttp", "splithttp", "h2", "http")) host else "" }
                )
            }.getOrDefault(profile)
        }

        try {
            database = AppDatabase.getInstance(this)
            serverRepository = ServerRepository(database.serverProfileDao())
            subscriptionRepository = SubscriptionRepository(database.subscriptionDao())
            benchmarkRepository = BenchmarkRepository(database.benchmarkDao())
            subscriptionManager = SubscriptionManager(
                subscriptionRepository,
                serverRepository,
                snapshots = com.example.vpn.subscription.SubscriptionSnapshots(
                    java.io.File(filesDir, "subscription-snapshots"),
                    encrypt = { com.example.data.security.SecureStorage.encrypt(it, this) },
                    decrypt = { com.example.data.security.SecureStorage.decrypt(it, this) }
                )
            )
            benchmarkEngine = BenchmarkEngine(serverRepository, benchmarkRepository)
            settingsRepository = SettingsRepository(this)
            XrayLogManager.i("APP", "Database, server repository, and settings repository initialized successfully.")
        } catch (e: Exception) {
            XrayLogManager.e("APP", "Fatal error initializing application database/repositories: ${e.localizedMessage}", e)
        }

        // Database initialization ready for user configuration imports
        XrayLogManager.i("APP", "Application environment initialized successfully.")

        // Clean up legacy non-functional seed nodes and initialize profile state
        CoroutineScope(Dispatchers.IO).launch {
            // Loaded here, off the main thread, so screens read it from memory.
            runCatching { freeConfigEvidence.all() }
            runCatching { lastKnownGood.entries() }
            try {
                subscriptionRepository.migrateSensitiveUrls()
                val prefs = getSharedPreferences("official_subscriptions", MODE_PRIVATE)
                com.example.vpn.subscription.OfficialSubscriptions.ensure(
                    subscriptionRepository,
                    seeded = prefs.getStringSet("seeded", emptySet()).orEmpty(),
                    markSeeded = { prefs.edit().putStringSet("seeded", it).apply() }
                )?.let { launch { subscriptionManager.syncSubscription(it) } }
                // The signed free list, offered once per install when this build can check its signature.
                if (com.example.vpn.hub.FreeConfigList.available()) {
                    com.example.vpn.subscription.OfficialSubscriptions.ensure(
                        subscriptionRepository,
                        seeded = prefs.getStringSet("seeded_free", emptySet()).orEmpty(),
                        markSeeded = { prefs.edit().putStringSet("seeded_free", it).apply() },
                        urls = listOf(com.example.vpn.hub.FreeConfigList.URL),
                        name = com.example.vpn.hub.FreeConfigList.NAME
                    )?.let { launch { subscriptionManager.syncSubscription(it) } }
                }
                serverRepository.migrateSensitiveSubscriptionSources()
                serverRepository.delete("seed-vless-ws-1")
                serverRepository.delete("seed-vless-reality-1")

                val currentSelected = settingsRepository.getSettings().selectedProfileId
                if (currentSelected == "seed-vless-ws-1" || currentSelected == "seed-vless-reality-1") {
                    val firstValid = serverRepository.getAllProfilesOnce().firstOrNull()
                    settingsRepository.setSelectedProfileId(firstValid?.id)
                }
            } catch (e: Exception) {
                XrayLogManager.w("APP", "Profile startup cleanup notice: ${e.message}")
            }
        }
    }

    companion object {
        lateinit var instance: RayApplication
            private set
    }
}
