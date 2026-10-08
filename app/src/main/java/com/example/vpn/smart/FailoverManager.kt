package com.example.vpn.smart

import com.example.core.SecretRedactor
import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.vpn.connectivity.SmartFailoverPolicy
import com.example.vpn.godmode.MaximusMeshManager
import com.example.vpn.godmode.PsiphonConduitBridge
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Watches the running connection with real requests through the tunnel and switches when it stops
 * carrying traffic (see WatchPolicy: about 18 seconds). GOD MODE then climbs the ladder of things that
 * actually exist, each step tried only when the one before it found nothing:
 *
 * 1. the server the user chose, on the path this network remembers;
 * 2. the other saved servers, raced with real requests, disguised forms included (ServerRace);
 * 3. the same server through a clean Cloudflare address (CleanIpOptimizer), when it is CDN-fronted;
 * 4. nodes from the signed free list that carried traffic (the hub), once one is published.
 *
 * Steps 2 to 4 are driven from the connect path in RayVpnService. When nothing works the connection is
 * reported as severed and traffic stays blocked; the watchdog keeps retrying the user's own server, so a
 * filter that lifts is picked up without the user doing anything.
 */
class FailoverManager(
    private val serverRepository: ServerRepository,
    private val protectSocket: ((Socket) -> Boolean)? = null,
    /** Latency of a real request through the running tunnel; throws when nothing gets through. */
    private val tunnelProbe: (suspend () -> Long)? = null,
    /** Passive evidence that lets a busy, recently verified connection skip a check (null: always check). */
    private val passive: PassiveHealth? = null,
    private val onTriggerSwitch: (VlessProfile, String) -> Unit
) {
    enum class CascadeTier(val stageNumber: Int, val title: String, val badge: String) {
        TIER_1_PRIMARY_REALITY(1, "Your server", "YOUR SERVER"),
        TIER_2_SECONDARY_NODES(2, "Your other servers", "OTHER SERVERS"),
        TIER_3_CLEAN_ADDRESS(3, "A clean Cloudflare address", "CLEAN ADDRESS"),
        TIER_4_FREE_LIST(4, "The signed free list", "FREE LIST"),
        DEGRADED_OFFLINE(5, "Nothing is carrying traffic", "BLOCKED")
    }

    private val scope = com.example.core.managerScope("FAILOVER")
    private var monitorJob: Job? = null
    private var recoveryProbeJob: Job? = null
    private var failoverJob: Job? = null

    private val _currentTier = MutableStateFlow(CascadeTier.TIER_1_PRIMARY_REALITY)
    val currentTier: StateFlow<CascadeTier> = _currentTier.asStateFlow()

    /** The primary and its Backup A / Backup B on other failure domains (SmartFailoverPolicy.plan). */
    private val _backupPlan = MutableStateFlow<SmartFailoverPolicy.Plan?>(null)
    val backupPlan: StateFlow<SmartFailoverPolicy.Plan?> = _backupPlan.asStateFlow()

    private val _failoverEvents = MutableStateFlow<String?>(null)
    val failoverEvents: StateFlow<String?> = _failoverEvents.asStateFlow()

    private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastTunnelReportTime = java.util.concurrent.atomic.AtomicLong(0L)
    private var currentActiveProfile: VlessProfile? = null
    private var currentActiveSettings: AppSettings? = null

    fun startMonitoring(currentProfile: VlessProfile, settings: AppSettings) {
        stopMonitoring()
        if (!settings.autoFailoverEnabled) return

        currentActiveProfile = currentProfile
        currentActiveSettings = settings
        _currentTier.value = determineInitialTier(currentProfile)
        consecutiveFailures.set(0)

        monitorJob = scope.launch {
            val safeName = SecretRedactor.redact(currentProfile.name)
            XrayLogManager.i("FAILOVER", "Failover watchdog active for '$safeName' [Mode: ${settings.operationalMode.displayName}, Tier: ${_currentTier.value.badge}].")
            runCatching {
                // Backups with recent real-traffic evidence first; the label says what is actually known.
                val plan = SmartFailoverPolicy.plan(currentProfile,
                    eligibleFallbacks(serverRepository.allProfiles.first(), currentProfile), { com.example.vpn.connectivity.BackupEvidence.rank(it) })
                _backupPlan.value = plan
                fun label(b: SmartFailoverPolicy.Backup?, none: String) =
                    b?.let { "${it.kind} on ${it.failureDomain} (${com.example.vpn.connectivity.BackupEvidence.describe(it.profile)})" } ?: none
                XrayLogManager.i("FAILOVER", "Backup plan: A ${label(plan.backupA, "none on another network")}, " +
                    "B ${label(plan.backupB, "none on a third network")}.")
            }.onFailure { if (it is CancellationException) throw it }

            while (isActive) {
                // Every 12 s while healthy, every 3 s after a failed check (see WatchPolicy).
                delay(WatchPolicy.nextCheckDelayMs(consecutiveFailures.get()))

                // While confirming a failure every check is a real request; otherwise data flowing through a
                // recently verified tunnel stands in for one (PassiveHealth).
                val isHealthy = if (consecutiveFailures.get() == 0 && passive?.maySkipActiveCheck() == true) {
                    com.example.vpn.diagnostics.ConnectionMetrics.passiveSkips.incrementAndGet()
                    true
                } else checkActiveTunnel(currentProfile, settings.failoverThresholdMs)
                // A blocking probe can outlive a switch or disconnect; its result is stale then.
                if (!isActive) break
                if (!isHealthy) {
                    val failures = consecutiveFailures.updateAndGet { (it + 1).coerceAtMost(WatchPolicy.FAILURES_TO_SWITCH) }
                    XrayLogManager.w("FAILOVER", "Node $safeName carried no traffic or exceeded the latency limit ($failures/${WatchPolicy.FAILURES_TO_SWITCH}).")

                    // Several failures in a row, so one lost request does not cause flapping.
                    if (failures >= WatchPolicy.FAILURES_TO_SWITCH) {
                        // The cooldown must survive a service switch, which creates a new manager.
                        if (acquireFailoverCooldown(maxOf(25000L, policy.cooldownMs()))) {
                            attemptFailover(currentProfile, settings)
                        }
                    }
                } else {
                    consecutiveFailures.set(0)
                    failedFamilies.clear()
                }
            }
        }

        // In GOD MODE: If running on emergency bridge or mesh (Tier 3/4), start background recovery probe
        if (settings.operationalMode == OperationalMode.GOD_MODE) {
            startRecoveryWatchdog(settings)
        }
    }

    /**
     * Reports runtime connection or handshake failures (e.g. WebSocket 301/404, TLS drop)
     * detected by active tunnel packet streaming.
     */
    fun reportTunnelError(reason: String) {
        val profile = currentActiveProfile ?: return
        val settings = currentActiveSettings ?: return
        if (monitorJob?.isActive != true) return

        // Resolver failures have their own retry/backoff path. A DNS-only failure is not
        // proof that the proxy endpoint is unhealthy and must not cause node flapping.
        if (reason.contains("DNS", ignoreCase = true)) {
            XrayLogManager.w("DNS", "Resolver path failed on active tunnel; keeping node until endpoint health also fails.")
            return
        }

        val now = System.currentTimeMillis()
        while (true) {
            val previous = lastTunnelReportTime.get()
            if (now - previous < 4000L) return
            if (lastTunnelReportTime.compareAndSet(previous, now)) break
        }

        val failures = consecutiveFailures.updateAndGet { (it + 1).coerceAtMost(3) }
        val safeName = SecretRedactor.redact(profile.name)
        XrayLogManager.w("FAILOVER", "Active tunnel error reported on '$safeName' ($failures/3): ${SecretRedactor.redact(reason)}")

        if (failures >= 3) {
            if (acquireFailoverCooldown(maxOf(20000L, policy.cooldownMs()))) {
                failoverJob?.cancel()
                failoverJob = scope.launch {
                    attemptFailover(profile, settings)
                }
            } else {
                consecutiveFailures.set(3)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        recoveryProbeJob?.cancel()
        recoveryProbeJob = null
        failoverJob?.cancel()
        failoverJob = null
        consecutiveFailures.set(0)
        currentActiveProfile = null
        currentActiveSettings = null
    }

    /** Which step of the ladder this profile is: a saved server, a clean address or the free list. */
    internal fun determineInitialTier(profile: VlessProfile): CascadeTier = when {
        profile.id.startsWith("hub-") -> CascadeTier.TIER_4_FREE_LIST
        profile.name.contains("[CF-Optimized]") -> CascadeTier.TIER_3_CLEAN_ADDRESS
        profile.overallScore >= 75.0 -> CascadeTier.TIER_1_PRIMARY_REALITY
        else -> CascadeTier.TIER_2_SECONDARY_NODES
    }

    /**
     * The active node is healthy when a real request gets through the tunnel. A handshake with the
     * server is not enough: a Cloudflare Worker (BPB) edge answers it even when the worker carries no
     * traffic. A real request takes several round trips, so the latency limit has a floor.
     */
    private suspend fun checkActiveTunnel(profile: VlessProfile, latencyThreshold: Long): Boolean {
        val probe = tunnelProbe ?: return checkHealth(profile, latencyThreshold)
        return try {
            com.example.vpn.diagnostics.ConnectionMetrics.activeTunnelChecks.incrementAndGet()
            (probe() < maxOf(latencyThreshold, REAL_REQUEST_LATENCY_FLOOR_MS)).also { if (it) passive?.recordActiveSuccess() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // After a switch or disconnect the request fails because the tunnel is gone; not worth a log.
            if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                XrayLogManager.d("FAILOVER", "Request through the tunnel failed: ${SecretRedactor.redact(e.message.orEmpty())}")
            }
            false
        }
    }

    private suspend fun checkHealth(profile: VlessProfile, latencyThreshold: Long): Boolean {
        val result = com.example.vpn.ServerTester.testServer(
            profile = profile,
            timeoutMs = 3000,
            protectSocket = protectSocket
        )
        return when (val status = result.status) {
            is ServerTestStatus.Available -> status.latencyMs < latencyThreshold
            is ServerTestStatus.Slow -> status.latencyMs < latencyThreshold
            else -> false
        }
    }

    /**
     * Periodically probes Tier 1 primary servers when running in emergency fallback mode,
     * enabling automatic de-escalation once upstream internet filtering ceases.
     */
    private fun startRecoveryWatchdog(settings: AppSettings) {
        recoveryProbeJob?.cancel()
        recoveryProbeJob = scope.launch {
            while (isActive) {
                delay(30000) // Check primary recovery every 30s
                // Any step past the user's own server: check whether that server works again.
                if (_currentTier.value.stageNumber > 1) {
                    try {
                        val profiles = serverRepository.allProfiles.first()
                        val topPrimary = profiles.filter { !it.id.startsWith("bridge-") && !it.id.startsWith("mesh-") && !it.id.startsWith("hub-") }
                            .maxByOrNull { it.overallScore }
                            ?.takeIf { it.effectiveFingerprint != currentActiveProfile?.effectiveFingerprint }

                        // While this connection is healthy the primary cannot get a real test (the running core
                        // refuses one), and a handshake proves nothing about traffic: a healthy tunnel is never
                        // dropped on that evidence. The watchdog switches only when this connection is failing
                        // or nothing works; then trying the primary costs nothing.
                        val currentFailing = _currentTier.value == CascadeTier.DEGRADED_OFFLINE || consecutiveFailures.get() > 0
                        if (!currentFailing) continue

                        // Back to the primary only after it passed several checks over a minute and this
                        // config has run for a while (hysteresis), so the two never alternate.
                        if (topPrimary != null && policy.recordPrimaryCheck(checkHealth(topPrimary, maxOf(450L, settings.failoverThresholdMs)))) {
                            policy.recordSwitch(currentActiveProfile, failed = false)
                            val reason = "This connection is failing and your own server answers again; trying ${topPrimary.name}."
                            _currentTier.value = CascadeTier.TIER_1_PRIMARY_REALITY
                            _failoverEvents.value = reason
                            XrayLogManager.i("GOD_MODE", reason)
                            onTriggerSwitch(topPrimary, reason)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun attemptFailover(degradedProfile: VlessProfile, settings: AppSettings) {
        val allProfiles = try {
            serverRepository.allProfiles.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        val safeDegraded = SecretRedactor.redact(degradedProfile.name)

        // Non-degraded candidates pool (excluding current failing node, bridges, mesh)
        val nonDegraded = eligibleFallbacks(allProfiles, degradedProfile)
        // A filter that stops one node usually stops its whole kind (QUIC, a TLS fingerprint, a CDN
        // path), so the next node is of a kind that has not failed during this outage.
        failedFamilies.add(protocolFamily(degradedProfile))
        val otherFamilies = preferUntriedFamilies(nonDegraded, failedFamilies)
        if (otherFamilies.size < nonDegraded.size && otherFamilies.isNotEmpty()) {
            XrayLogManager.i("FAILOVER", "Trying a different kind of connection than ${failedFamilies.joinToString()}.")
        }
        // Off the failed config's CDN or network, and not one that was just left after failing.
        val otherDomains = policy.withoutPenalized(SmartFailoverPolicy.preferOtherDomains(otherFamilies, degradedProfile))
        val scoredCandidates = otherDomains.filter { it.overallScore > 0 }
        val candidateProfiles = if (scoredCandidates.isNotEmpty()) scoredCandidates else otherDomains

        // --- GOD MODE CASCADE LADDER ---
        if (settings.operationalMode == OperationalMode.GOD_MODE) {
            XrayLogManager.w("GOD_MODE", "Nothing is getting through $safeDegraded; trying the other saved servers.")

            // Step 1: Secondary Reality / VLESS nodes
            val bestFallback = SmartConnect.selectBestNode(candidateProfiles, settings.scoringProfile)

            if (bestFallback != null) {
                _currentTier.value = CascadeTier.TIER_2_SECONDARY_NODES
                val reason = "Switched to ${bestFallback.profile.name}."
                _failoverEvents.value = reason
                XrayLogManager.i("GOD_MODE", reason)
                policy.recordSwitch(degradedProfile)
                onTriggerSwitch(bestFallback.profile, reason)
                return
            }

            // Discovery and TCP reachability are not authenticated proxy credentials.
            // Never invent a VLESS UUID or send traffic to an unverified LAN beacon.
            _currentTier.value = CascadeTier.DEGRADED_OFFLINE
            XrayLogManager.e("GOD_MODE", "No other saved server is usable after $safeDegraded failed; the connect path will try a clean address and the free list. Traffic stays blocked meanwhile.")
        }

        // Standard Daily Mode fallback
        val fallback = SmartConnect.selectBestNode(candidateProfiles, settings.scoringProfile)
            ?: candidateProfiles.firstOrNull()?.let {
                SmartConnect.SmartSelection(
                    profile = it,
                    reasonPing = "Fallback",
                    reasonDownload = "Standard",
                    reasonStability = "Normal",
                    reasonPacketLoss = "0%",
                    overallScore = it.overallScore,
                    description = "Fallback to ${it.name}"
                )
            }

        if (fallback != null) {
            consecutiveFailures.set(0)
            val reason = "Failover: Availability or latency policy triggered on $safeDegraded. Switched to ${fallback.profile.name}."
            _failoverEvents.value = reason
            XrayLogManager.i("FAILOVER", reason)
            policy.recordSwitch(degradedProfile)
            onTriggerSwitch(fallback.profile, reason)
        } else {
            consecutiveFailures.set(0)
            XrayLogManager.w("FAILOVER", "No distinct supported fallback node is available for $safeDegraded; keeping the current connection.")
        }
    }

    companion object {
        internal const val REAL_REQUEST_LATENCY_FLOOR_MS = 3000L

        private val lastFailoverAt = java.util.concurrent.atomic.AtomicLong(0L)

        /** Backoff, penalties and switch-back hysteresis; shared because a switch creates a new manager. */
        internal val policy = SmartFailoverPolicy()

        private fun acquireFailoverCooldown(intervalMs: Long): Boolean {
            val now = System.nanoTime() / 1_000_000
            while (true) {
                val previous = lastFailoverAt.get()
                if (previous != 0L && now - previous < intervalMs) return false
                if (lastFailoverAt.compareAndSet(previous, now)) return true
            }
        }

        /** Kinds of connection that failed during the current outage; cleared once a node works again. */
        private val failedFamilies: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** The kind of traffic a censor sees; see [com.example.vpn.stealth.ConnectionKind]. */
        internal fun protocolFamily(profile: VlessProfile): String = com.example.vpn.stealth.ConnectionKind.of(profile)

        /** Candidates of a kind not in [failed]; all candidates when every kind has failed. */
        internal fun preferUntriedFamilies(candidates: List<VlessProfile>, failed: Set<String>): List<VlessProfile> =
            candidates.filter { protocolFamily(it) !in failed }.ifEmpty { candidates }

        internal fun eligibleFallbacks(profiles: List<VlessProfile>, current: VlessProfile): List<VlessProfile> = profiles.filter {
            com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(it) == null &&
                it.id != current.id && it.effectiveFingerprint != current.effectiveFingerprint &&
                // The same host and port over another kind (Hysteria2 on UDP next to REALITY on TCP) is a real alternative.
                !(it.address.equals(current.address, ignoreCase = true) && it.port == current.port &&
                    protocolFamily(it) == protocolFamily(current)) &&
                !it.id.startsWith("bridge-") && !it.id.startsWith("mesh-")
        }
    }
}
