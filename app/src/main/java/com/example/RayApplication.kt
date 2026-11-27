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

    /**
     * What worked on each carrier or Wi-Fi (see NetworkMemory). One instance for the VPN and Settings, so
     * clearing it in Settings clears what the next connect uses.
     */
    val networkMemory: com.example.vpn.smart.NetworkMemory by lazy {
        val prefs = getSharedPreferences("network_memory", MODE_PRIVATE)
        com.example.vpn.smart.NetworkMemory(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() },
            loadRoutes = { prefs.getString("routes", null) },
            saveRoutes = { prefs.edit().putString("routes", it).apply() },
            loadMtu = { prefs.getString("mtu", null) },
            saveMtu = { prefs.edit().putString("mtu", it).apply() },
            loadSpeeds = { prefs.getString("speeds", null) },
            saveSpeeds = { prefs.edit().putString("speeds", it).apply() },
            loadFreezes = { prefs.getString("freezes", null) },
            saveFreezes = { prefs.edit().putString("freezes", it).apply() },
            loadShield = { prefs.getString("uplink_shield", null) },
            saveShield = { prefs.edit().putString("uplink_shield", it).apply() },
            loadWinners = { prefs.getString("verified_winners", null) },
            saveWinners = { prefs.edit().putString("verified_winners", it).apply() },
            loadPsiphonRegions = { prefs.getString("psiphon_regions", null) },
            savePsiphonRegions = { prefs.edit().putString("psiphon_regions", it).apply() }
        )
    }

    /** DNS resolvers verified per network by the resolver scanner, preferred by the DNS-tunnel engines. */
    val resolverPool: com.example.vpn.dns.VerifiedResolverPool by lazy {
        val prefs = getSharedPreferences("resolver_pool", MODE_PRIVATE)
        com.example.vpn.dns.VerifiedResolverPool(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

    /** Which config and recipe won config revival per network; cleared with the learned network data. */
    val revivalWinners: com.example.vpn.lab.RevivalWinnerMemory by lazy {
        val prefs = getSharedPreferences("revival_winners", MODE_PRIVATE)
        com.example.vpn.lab.RevivalWinnerMemory(load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() })
    }

    /** Recovers degraded BPB / Cloudflare-fronted configs with derived copies; originals are never changed. */
    val bpbRecovery: com.example.vpn.connectivity.BpbRecoveryEngine by lazy {
        com.example.vpn.connectivity.BpbRecoveryEngine(ledger = recoveryLedger, fragmentReverted = { key, network ->
            fragmentProfiles.decide(key, network) == com.example.vpn.connectivity.FragmentProfileEngine.Decision.REVERT
        })
    }

    /**
     * LAB "Revive configs": the same recipes, gate and ledger as [bpbRecovery], with more recipes tried per
     * config. What it records is what the VPN uses on the next connect.
     */
    val configRevival: com.example.vpn.lab.ConfigRevival by lazy {
        com.example.vpn.lab.ConfigRevival(
            engine = com.example.vpn.connectivity.BpbRecoveryEngine(
                ledger = recoveryLedger, maxCandidates = com.example.vpn.lab.ConfigRevival.RECIPES_PER_CONFIG,
                fragmentReverted = { key, network ->
                    fragmentProfiles.decide(key, network) == com.example.vpn.connectivity.FragmentProfileEngine.Decision.REVERT
                }
            ),
            probe = { profiles ->
                // A candidate with a fork-only field (the oob recipe's dialMode) is tested on the fork, never on stock Xray.
                com.example.vpn.connectivity.ForkAwareProbe.measure(profiles,
                    needsFork = { com.example.vpn.sidecar.ForkXraySidecar.applies(it) },
                    stock = { plain ->
                        com.example.xray.RealDelayProbe.measure(plain, com.example.vpn.lab.ConfigRevival.TIMEOUT_SEC).map { o ->
                            when (o) {
                                is com.example.xray.RealDelayProbe.Outcome.Delay -> com.example.vpn.connectivity.DerivedRecoveryCandidate.TestResult.Passed(o.latencyMs)
                                is com.example.xray.RealDelayProbe.Outcome.Failed -> com.example.vpn.connectivity.DerivedRecoveryCandidate.TestResult.Failed(o.reason)
                                is com.example.xray.RealDelayProbe.Outcome.NotRun -> com.example.vpn.connectivity.DerivedRecoveryCandidate.TestResult.NotRun(o.reason)
                            }
                        }
                    },
                    fork = { p ->
                        val r = com.example.vpn.lab.EngineProbe.test(this, com.example.vpn.sidecar.ForkXraySidecar, p,
                            com.example.vpn.lab.ConfigRevival.TIMEOUT_SEC, record = false)
                        com.example.vpn.connectivity.ForkAwareProbe.testResult(r.stage.carriesTraffic, r.notTested, r.latencyMs, r.failure)
                    },
                    missing = com.example.vpn.connectivity.DerivedRecoveryCandidate.TestResult.NotRun("no result"))
            },
            endpoints = { network -> endpointScores.validated(network) },
            winners = revivalWinners,
            context = { network -> recoveryContext(network) },
            echCheck = {
                com.example.vpn.connectivity.EchKeyCheck.check(com.example.vpn.connectivity.RecoveryProfiles.CLOUDFLARE_ECH_DOH) { url ->
                    // On the phone's own network: during a connect this app's traffic is held in the blocking interface.
                    val physical = getSystemService(android.net.ConnectivityManager::class.java)
                        ?.let { com.example.vpn.smart.NetworkCapabilityDetector.physical(it) }
                    val c = (physical?.openConnection(java.net.URL(url)) ?: java.net.URL(url).openConnection()) as java.net.HttpURLConnection
                    try {
                        c.connectTimeout = 5000; c.readTimeout = 5000
                        c.setRequestProperty("Accept", "application/dns-message")
                        // readNBytes is API 33; the app supports 24, so read a bounded amount by hand.
                        if (c.responseCode == 200) c.inputStream.use { s ->
                            val buf = ByteArray(4096); var n = 0
                            while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                            buf.copyOf(n)
                        } else null
                    } finally { c.disconnect() }
                }
            },
            findCleanIps = { profile, _ ->
                val scanNetwork = com.example.vpn.smart.NetworkIdentity.current(this)
                val scope = com.example.vpn.connectivity.EndpointScoringEngine.destinationScope(scanNetwork, profile)
                val country = networkCountry()
                val cdn = cdnCleanIps.cdnOf(profile)
                // On a network that proxies Cloudflare TLS by name, no Cloudflare edge is "clean": skip that scan.
                val cfUseful = cdn != com.example.vpn.cleanip.Cdn.CLOUDFLARE || !cdnCleanIps.cloudflareProxied(scanNetwork, country)
                // Edge addresses that pass TCP, TLS (with the config's SNI) and the config's own WebSocket or
                // HTTP exchange; each one found is recorded for this network, which is what makes it a recipe.
                val found = if (cdn == com.example.vpn.cleanip.Cdn.CLOUDFLARE && cfUseful && com.example.panels.CleanIpOptimizer.canRevive(profile)) {
                    kotlinx.coroutines.runBlocking {
                        com.example.panels.CleanIpOptimizer().scan(profile, targetPort = profile.port, enableSpeedTest = false, candidateCount = 64,
                            preferred = endpointScores.remembered(scope, limit = 4), enough = 6,
                            assertNetwork = { check(com.example.vpn.smart.NetworkIdentity.current(this@RayApplication) == scanNetwork) { "Network changed during clean-IP scan" } }) { }
                    }.take(6)
                } else emptyList()
                check(com.example.vpn.smart.NetworkIdentity.current(this) == scanNetwork) { "Network changed during clean-IP scan" }
                val now = System.currentTimeMillis()
                endpointScores.record(found.filter { it.originVerified }.map { com.example.vpn.connectivity.EndpointScoringEngine.Measurement(it.ip, scope, now, true, it.medianMs) })
                // XHTTP, gRPC and H2 configs, Fastly/Gcore/CloudFront fronts, and too few WebSocket edges: handshake-ranked
                // edges of the config's own CDN with its own server name, cached per network. Revival still sends a
                // real request through each copy before using it.
                val more = if (found.size >= 3 || !cfUseful) emptyList() else runCatching {
                    kotlinx.coroutines.runBlocking { cdnCleanIps.find(profile, scanNetwork, country, preferred = found.map { it.ip }).ips }
                }.getOrDefault(emptyList())
                check(com.example.vpn.smart.NetworkIdentity.current(this) == scanNetwork) { "Network changed during clean-IP scan" }
                (found.map { it.ip } + more).distinct().take(8)
            }
        )
    }

    /**
     * What recovery may assume about [network]: its firewall type from the network key, whether the phone's own
     * network has a global IPv6 address (a fresh measurement saying no wins; otherwise the link's addresses), and
     * whether the patterniha Xray fork is in this build. Cheap: no probe is sent.
     */
    fun recoveryContext(network: String?): com.example.vpn.connectivity.RecoveryContext {
        val ipv6 = runCatching {
            val measured = com.example.vpn.smart.NetworkEnvironment.freshEvidence(com.example.vpn.smart.NetworkCapabilityDetector.last,
                com.example.vpn.smart.NetworkIdentity.current(this), System.currentTimeMillis())?.ipv6Available
            if (measured == false) false
            else {
                val cm = getSystemService(android.net.ConnectivityManager::class.java)
                val physical = cm?.let { com.example.vpn.smart.NetworkCapabilityDetector.physical(it) }
                val addresses = physical?.let { cm?.getLinkProperties(it) }?.linkAddresses?.map { it.address }
                if (addresses == null) measured
                else com.example.vpn.connectivity.RecoveryContext.hasGlobalIpv6(addresses)
            }
        }.getOrNull()
        val fork = runCatching { com.example.vpn.sidecar.Sidecars.isBundled(com.example.vpn.sidecar.ForkXraySidecar) }.getOrDefault(false)
        return com.example.vpn.connectivity.RecoveryContext(com.example.vpn.connectivity.NetworkFirewalls.detect(network), ipv6, fork)
    }

    /** Clean CDN edges per network and whether the network proxies Cloudflare TLS by name. */
    val cleanIpCache: com.example.vpn.cleanip.CleanIpCache by lazy {
        val prefs = getSharedPreferences("clean_ip_cache", MODE_PRIVATE)
        com.example.vpn.cleanip.CleanIpCache(
            load = { prefs.getString("v1", null) },
            save = { prefs.edit().putString("v1", it).apply() }
        )
    }

    /** Clean-IP scans for every CDN transport and CDN, used by config revival. */
    val cdnCleanIps: com.example.vpn.cleanip.CdnCleanIpFinder by lazy {
        com.example.vpn.cleanip.CdnCleanIpFinder(cleanIpCache, resolve = { name ->
            // The config's own name, looked up on the phone's network (not the tunnel or the blocking interface).
            val physical = getSystemService(android.net.ConnectivityManager::class.java)
                ?.let { com.example.vpn.smart.NetworkCapabilityDetector.physical(it) }
            (physical?.getAllByName(name) ?: java.net.InetAddress.getAllByName(name))
                .filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }
        })
    }

    /** The mobile network's country (ISO alpha-2, upper case), or null when unknown. */
    fun networkCountry(): String? = runCatching {
        getSystemService(android.telephony.TelephonyManager::class.java)?.networkCountryIso?.trim()?.uppercase(java.util.Locale.US)
    }.getOrNull()?.ifBlank { null }

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

    /** The Autonomous Network LAB: experiments on safe derived copies, verified profiles per network. */
    val lab: com.example.vpn.lab.LabController by lazy {
        val prefs = getSharedPreferences("network_lab", MODE_PRIVATE)
        com.example.vpn.lab.LabController(
            context = this,
            store = com.example.vpn.lab.LabStore(load = { prefs.getString("v1", null) }, save = { prefs.edit().putString("v1", it).apply() }),
            ledger = recoveryLedger,
            endpoints = endpointScores,
            loadProfile = { id -> serverRepository.getProfileById(id) },
            loadAllProfiles = { serverRepository.getAllProfilesOnce() }
        ).also { it.start() }
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
        // Tor's own process only runs TorService; nothing of the app starts there.
        if (com.example.core.AppProcess.isTorProcess()) return
        // Build 178: what each engine did on each network survives the app being closed (the report's engine list).
        runCatching {
            val ledgerPrefs = getSharedPreferences("engine_ledger", MODE_PRIVATE)
            com.example.vpn.engine.registry.EngineLedger.shared.store = com.example.vpn.engine.registry.EngineLedger.Store(
                load = { ledgerPrefs.getString("history", null) },
                save = { value -> ledgerPrefs.edit().putString("history", value).apply() })
        }
        com.example.vpn.sidecar.BuiltinExitNode.loadProfile = { id ->
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { serverRepository.getProfileById(id) }
        }
        com.example.vpn.sidecar.AetherSidecar.identityDirectory = { java.io.File(noBackupFilesDir, "identities/aether") }
        com.example.vpn.sidecar.MatrixTransportSidecar.identityDirectory = { java.io.File(noBackupFilesDir, "engines/matrix-transport") }
        com.example.vpn.sidecar.Sidecars.nativeLibraryDir = applicationInfo.nativeLibraryDir
        com.example.vpn.sidecar.Sidecars.openAsset = { name -> assets.open(name) }
        // Bundled Iran GeoFiles for Xray's geoip:/geosite: rules; copied off the main thread, and again on
        // demand by XrayEngine if the VPN starts first.
        com.example.xray.GeoAssets.install(this)
        CoroutineScope(Dispatchers.IO).launch { com.example.xray.GeoAssets.ensureReady() }
        com.example.vpn.BundledResolvers.updatedAsset = { part -> com.example.update.ResolverPackUpdater.open(this, part) }
        // Unlock mode's server list: the verified resolver pack's copy when it has one, else the bundled asset.
        com.example.vpn.unlock.UnlockDnsList.updated = { com.example.update.ResolverPackUpdater.openUnlock(this) }
        com.example.vpn.unlock.UnlockDnsList.bundled = { assets.open(com.example.vpn.unlock.UnlockDnsList.ASSET) }
        com.example.vpn.unlock.UnlockDns.networkKey = { com.example.vpn.smart.NetworkIdentity.current(this) }
        com.example.vpn.sidecar.Sidecars.torStarter = { torrc, port -> com.example.vpn.sidecar.TorInApp.start(this, torrc, port) }
        com.example.vpn.sidecar.Sidecars.privatePsiphonSettings = { id ->
            com.example.vpn.sidecar.PrivatePsiphonStore(this).load(id)
        }
        com.example.vpn.sidecar.Sidecars.psiphonNetworkIdentity = { com.example.vpn.smart.NetworkIdentity.current(this) }
        // Firewall type by behaviour (FirewallProbe): probed at Connect, used by NetworkFirewalls only when confident.
        com.example.vpn.connectivity.FirewallProbe.store = getSharedPreferences("firewall_probe", MODE_PRIVATE).let { prefs ->
            com.example.vpn.connectivity.FirewallBehaviour.Store(
                load = { prefs.getString("v1", null)?.ifBlank { null } },
                save = { prefs.edit().putString("v1", it).apply() }
            )
        }.also { store -> com.example.vpn.connectivity.NetworkFirewalls.behaviour = store::confident }
        com.example.vpn.sidecar.Sidecars.psiphonLinkMetered = {
            val manager = getSystemService(android.net.ConnectivityManager::class.java)
            manager?.let { cm ->
                com.example.vpn.smart.NetworkCapabilityDetector.physical(cm)?.let(cm::getNetworkCapabilities)
            }?.let { !it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } == true
        }
        com.example.vpn.sidecar.Sidecars.psiphonLinkDns = {
            val manager = getSystemService(android.net.ConnectivityManager::class.java)
            val link = manager?.let { cm ->
                com.example.vpn.smart.NetworkCapabilityDetector.physical(cm)?.let(cm::getLinkProperties)
            }
            link?.takeUnless { android.os.Build.VERSION.SDK_INT >= 28 && it.isPrivateDnsActive }?.dnsServers.orEmpty()
                .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress?.substringBefore('%') }.distinct()
        }
        com.example.vpn.sidecar.Sidecars.psiphonSettings = {
            com.example.data.security.SecureStorage(this).getAndDecrypt(com.example.vpn.sidecar.PsiphonSidecar.SETTINGS_STORAGE_KEY)?.takeIf { it.isNotBlank() }
        }

        // Home-screen widget follows the connection state.
        runCatching { com.example.vpn.widget.ConnectWidgetProvider.observe(this) }

        // Keep the log of the previous run (a crash or a forced stop would otherwise wipe it).
        runCatching { XrayLogManager.attachDisk(java.io.File(filesDir, "logs")) }

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
                    canonicalFingerprint = profile.effectiveFingerprint,
                    sni = if (usesTls && profile.sni.isBlank()) host else profile.sni,
                    host = profile.host.ifBlank { if (profile.transport.lowercase() in setOf("ws", "httpupgrade", "xhttp", "splithttp", "h2", "http")) host else "" }
                )
            }.getOrDefault(profile)
        }

        // One connectivity brain: every real test feeds the current network session's evidence, which the LAB,
        // Smart Connect and failover all read (see ConnectivityBrain).
        com.example.vpn.connectivity.ConnectivityBrain.networkKey = {
            runCatching { com.example.vpn.smart.NetworkIdentity.current(this) }.getOrNull()
        }
        val builtinMethods = getSharedPreferences("builtin_verified_methods", MODE_PRIVATE)
        com.example.vpn.connections.BuiltinMethodMemory.network = {
            runCatching { com.example.vpn.smart.NetworkIdentity.current(this) }.getOrNull()
        }
        com.example.vpn.connections.BuiltinMethodMemory.read = { key -> builtinMethods.getString(key, null) }
        com.example.vpn.connections.BuiltinMethodMemory.write = { key, value ->
            builtinMethods.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
        // Build 181 Phase 5: Smart SNI / Smart ECH / clean-IP scanner settings, and Smart ECH's DoH key fetch on the
        // phone's own network (pinned IP-literal resolvers, verified TLS: HttpsURLConnection's default trust and host check).
        val smartSni = getSharedPreferences("smart_sni", MODE_PRIVATE)
        com.example.vpn.smartsni.SmartSniSettings.read = { key -> smartSni.getString(key, null) }
        com.example.vpn.smartsni.SmartSniSettings.write = { key, value ->
            smartSni.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
        com.example.vpn.smartsni.SmartEch.doh = { url ->
            require(url.startsWith("https://")) { "DoH must be HTTPS" }
            val physical = getSystemService(android.net.ConnectivityManager::class.java)
                ?.let { com.example.vpn.smart.NetworkCapabilityDetector.physical(it) }
            val c = (physical?.openConnection(java.net.URL(url)) ?: java.net.URL(url).openConnection()) as javax.net.ssl.HttpsURLConnection
            try {
                c.connectTimeout = 4000; c.readTimeout = 4000
                c.setRequestProperty("Accept", "application/dns-message")
                if (c.responseCode == 200) c.inputStream.use { s ->
                    val buf = ByteArray(8192); var n = 0
                    while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    buf.copyOf(n)
                } else null
            } finally { c.disconnect() }
        }
        // Build 181 Phase 6: the user's own servers with ECH (Panels → My Servers), read from the encrypted server
        // store; their configs connect with the server's ECH (PersonalEch). Loaded off the main thread.
        com.example.vpn.smartsni.PersonalEch.load = {
            com.example.panels.servers.ServerStore(this).load().flatMap { s ->
                com.example.vpn.smartsni.PersonalEch.fromRecord(s.id, s.displayName, s.host,
                    com.example.panels.servers.ech.ServerEch.record(s.tool(com.example.panels.servers.ServerToolCatalog.XUI)?.settings.orEmpty()))
            }
        }
        com.example.vpn.smartsni.PersonalEch.parseLink = { link ->
            com.example.vpn.engine.UniversalImportEngine.importText(link).validProfiles.firstOrNull()
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.example.vpn.smartsni.PersonalEch.reload() }
            runCatching { com.example.panels.servers.ech.ServerEchRotation.runDue(this@RayApplication) }
        }
        // Per-network clean MASQUE HTTP/2 gateways (UAC SNI Spoofer's clean-H2 list) and their screen-on prewarm.
        val cleanH2 = getSharedPreferences("aether_clean_h2", MODE_PRIVATE)
        com.example.vpn.connections.CleanH2PeerMemory.read = { key -> cleanH2.getString(key, null) }
        com.example.vpn.connections.CleanH2PeerMemory.write = { key, value ->
            cleanH2.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
        // Per-network MASQUE TLS fingerprint for Aether's Auto setting (semi-python/firefox/go/chrome on Irancell).
        val aetherFingerprints = getSharedPreferences("aether_fingerprints", MODE_PRIVATE)
        com.example.vpn.connections.AetherFingerprintBook.read = { key -> aetherFingerprints.getString(key, null) }
        com.example.vpn.connections.AetherFingerprintBook.write = { key, value ->
            aetherFingerprints.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
        runCatching { com.example.vpn.connections.AetherPrewarmMonitor.install(this) }
        com.example.xray.RealDelayProbe.observer = com.example.vpn.connectivity.ConnectivityBrain::onProbe
        // Probe targets: the last valid signed manifest, else the built-in list; refreshed in the background.
        com.example.vpn.connectivity.ProbeManifestStore.install(this)

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
