package com.example.vpn.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.RayApplication
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import com.example.update.GeoFilesUpdater
import com.example.vpn.BundledResolvers
import com.example.vpn.VpnController
import com.example.vpn.cleanip.CdnEdgeScanner
import com.example.vpn.dns.ResolverScanner
import com.example.vpn.dnsfirst.DnsLadderRunner
import com.example.vpn.dnsfirst.DnsLadderWinners
import com.example.vpn.dnsfirst.DnsTunnelLadder
import com.example.vpn.dnsfirst.DnsTunnelSpecs
import com.example.vpn.dnsfirst.EngineKind
import com.example.vpn.dnsfirst.TunnelCaps
import com.example.vpn.dnsfirst.TunnelResolverProbe
import com.example.vpn.lab.ConfigRevival
import com.example.vpn.lab.EngineProbe
import com.example.vpn.sidecar.Sidecars
import com.example.vpn.smart.NetworkIdentity
import com.example.vpn.smart.NetworkKey
import com.example.vpn.subscription.SubscriptionPreset
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The upkeep task bodies, wired to the app's existing scanners and stores: the resolver scanner and its
 * per-network pool, the DNS tunnel ladder, the CDN clean-IP finder and its per-network cache, config revival
 * (whose winners the next connect uses), and the verified GeoFiles updater. Nothing here changes a saved
 * profile except: measured latency of configs that answered, and — only when the user turned it on — removal of
 * subscription configs that stayed dead ([ConfigHealthBook]).
 */
class AutomationTasks(
    private val context: Context,
    private val stopped: () -> Boolean = { false },
    /** False once the window beside this app's own traffic block closed ([OwnVpnWindow]); true otherwise. */
    private val windowIntact: () -> Boolean = { true }
) {
    private val app: RayApplication get() = RayApplication.instance

    fun network(): String = runCatching { NetworkIdentity.current(context) }.getOrElse { NetworkKey.current(context) }

    suspend fun profiles(): List<VlessProfile> =
        runCatching { app.serverRepository.getAllProfilesOnce() }.getOrDefault(emptyList())

    suspend fun env(profiles: List<VlessProfile>): AutomationEnv = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val vpns = runCatching {
            cm?.allNetworks.orEmpty().mapNotNull { cm?.getNetworkCapabilities(it) }.filter { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        }.getOrDefault(emptyList())
        val state = VpnController.connectionState.value
        // Android shows a VPN network only to the apps it applies to: while an engine program's session leaves this app
        // out of its own VPN, the VPN is invisible here. Build 175: at 10:23 a NETWORK_CHANGED run saw "no VPN" during
        // an Aether connect and ran config revival (engine starts) beside it. This app's own session state counts too.
        val vpnActive = vpns.isNotEmpty() || state.isBusy || state.isTunnelUp
        // Android runs one VPN per user, so while this app's service holds its blocking interface the VPN seen is
        // that one. From API 30 an owner UID that is reported and is another app's (a work-profile VPN) rules it out;
        // Android may also hide the owner (-1), which says nothing either way.
        val onlyOurs = vpns.all { caps ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                caps.ownerUid == android.os.Process.myUid() || caps.ownerUid < 0 else true
        }
        val tunnelUp = state.status == ConnectionStatus.CONNECTED
        val ownBlockOnly = vpnActive && !tunnelUp && onlyOurs && OwnVpnWindow.blockOnly()
        val metered = runCatching { cm?.isActiveNetworkMetered ?: true }.getOrDefault(true)
        AutomationEnv(
            network = network(), vpnActive = vpnActive, tunnelUp = tunnelUp, metered = metered, now = System.currentTimeMillis(),
            hasDnsTunnels = dnsTunnels(profiles).isNotEmpty(),
            hasFronted = fronted(profiles).isNotEmpty(),
            hasRevivable = runCatching { app.configRevival.eligibleCount(revivable(profiles)) > 0 }.getOrDefault(false),
            geoNeedsTunnel = GeoFilesUpdater.viaVpn(context),
            ownBlockOnly = ownBlockOnly,
            // An engine program's session leaves this app out of its own VPN: its sockets reach the phone's network.
            appOutsideTunnel = tunnelUp && runCatching { com.example.vpn.RayVpnService.appOutsideVpn }.getOrDefault(false),
            ownConnecting = vpnActive && !tunnelUp && !ownBlockOnly && onlyOurs && state.isBusy
        )
    }

    fun bodies(profiles: List<VlessProfile>): Map<AutomationTask, TaskBody> = mapOf(
        AutomationTask.RESOLVER_SCAN to TaskBody { env -> resolverScan(env, profiles) },
        AutomationTask.DNS_LADDER to TaskBody { env -> dnsLadder(env, profiles) },
        AutomationTask.CLEAN_IP_SCAN to TaskBody { env -> cleanIps(env, profiles) },
        AutomationTask.CONFIG_REVIVAL to TaskBody { env -> revival(env, profiles) },
        AutomationTask.GEO_UPDATE to TaskBody { env -> geo(env) }
    )

    private fun sameNetwork(env: AutomationEnv) {
        check(network() == env.network) { "the network changed during the run" }
        // A connect or Disconnect ended the window beside the traffic block: later measurements may have been caught.
        check(windowIntact()) { "the traffic block changed during the run" }
        // Beside a tunnel the app was left out of: a session that puts the app inside its VPN (an in-process Xray
        // config) would have carried the later measurements through the tunnel.
        if (env.appOutsideTunnel) check(runCatching { com.example.vpn.RayVpnService.appOutsideVpn }.getOrDefault(false) ||
            VpnController.connectionState.value.status == ConnectionStatus.DISCONNECTED) { "this app joined its own tunnel during the run" }
    }

    private suspend fun resolverScan(env: AutomationEnv, profiles: List<VlessProfile>): TaskResult = withContext(Dispatchers.IO) {
        val configured = dnsTunnels(profiles).mapNotNull { DnsTunnelSpecs.spec(it) }.flatMap { it.configured }
            .filterNot { it.startsWith("https://") }
        val bundled = (0 until 4).flatMap { day -> BundledResolvers.labSample(env.network, env.now + day * 86_400_000L) }
        val ranked = ResolverScanner.scan(ResolverScanner.candidates(configured, bundled))
        sameNetwork(env)
        app.resolverPool.record(env.network, ranked)
        TaskResult(ranked.size, ranked.take(3).joinToString { "${it.ip} ${it.score}" })
    }

    private suspend fun dnsLadder(env: AutomationEnv, profiles: List<VlessProfile>): TaskResult = withContext(Dispatchers.IO) {
        val tunnels = dnsTunnels(profiles)
        val specs = tunnels.mapNotNull { DnsTunnelSpecs.spec(it) }
        if (specs.isEmpty()) return@withContext TaskResult(0, "no DNS tunnel config can be read")
        val verified = app.resolverPool.verified(env.network).map { it.ip }
        val caps: Map<String, List<TunnelCaps>> = specs.groupBy { it.domain }.entries.take(MAX_DOMAINS).associate { (domain, group) ->
            val ips = (verified.take(12) + group.flatMap { it.configured }.filterNot { it.startsWith("https://") })
                .filter(BundledResolvers::publicIpv4).distinct().take(MAX_RESOLVERS)
            val udp = TunnelResolverProbe.scan(ips, domain) { ip -> TunnelResolverProbe.probe(ip, domain, TunnelResolverProbe.Udp(ip)) }
            val doh = if (group.none { it.kind == EngineKind.DNSTT }) emptyList() else {
                // dnstt accepts only IP-literal DoH URLs, so only the profiles' own DoH resolvers are candidates.
                val urls = group.flatMap { it.configured }.filter { it.startsWith("https://") }.distinct()
                TunnelResolverProbe.scan(urls, domain, parallelism = 3) { url ->
                    TunnelResolverProbe.probe(url, domain, TunnelResolverProbe.Doh(url), kind = "doh")
                }
            }
            domain to (udp + doh)
        }
        sameNetwork(env)
        val memory = AutomationStores.ladder(context)
        val plan = DnsTunnelLadder.plan(specs, caps, memory, env.network)
        val byId = tunnels.associateBy { it.id }
        val report = DnsLadderRunner.run(plan, specs, memory, env.network, budgetMs = LADDER_BUDGET_MS,
            cancelled = { stopped() || !windowIntact() || network() != env.network }) test@{ rung ->
            val base = byId[rung.profileId] ?: return@test DnsLadderRunner.Outcome(false, failure = "config removed", notTested = true)
            val variant = DnsTunnelSpecs.apply(base, rung)
                ?: return@test DnsLadderRunner.Outcome(false, failure = "variant does not apply", notTested = true)
            val engine = Sidecars.forProfile(variant)
                ?: return@test DnsLadderRunner.Outcome(false, failure = "no engine", notTested = true)
            val r = EngineProbe.test(context, engine, variant, LADDER_REQUEST_SEC)
            when {
                r.stage.carriesTraffic -> DnsLadderRunner.Outcome(true, r.latencyMs)
                r.notTested -> DnsLadderRunner.Outcome(false, failure = r.failure, notTested = true)
                else -> DnsLadderRunner.Outcome(false, failure = r.failure)
            }
        }
        // Smart Connect's verified-path memory: automatic Aether's fallback and the pinned-restart offer can use them.
        DnsLadderWinners.publish(report, env.network,
            nameOf = { id -> byId[id]?.let { com.example.core.SecretRedactor.redact(it.name) } },
            record = { net, id, name -> app.networkMemory.winners.recordVerified(net, id, name) })
        val usable = caps.values.sumOf { list -> list.count { it.usable } }
        XrayLogManager.i("DNS", "DNS tunnel ladder: ${report.wins.size} of ${specs.size} tunnels verified, ${report.tested} variants tried, " +
            "$usable resolver paths reach the tunnel domains")
        TaskResult(report.wins.size, "${report.wins.size}/${specs.size} tunnels · ${report.tested} variants · $usable resolver paths")
    }

    private suspend fun cleanIps(env: AutomationEnv, profiles: List<VlessProfile>): TaskResult = withContext(Dispatchers.IO) {
        val finder = app.cdnCleanIps
        val country = app.networkCountry()
        val targets = fronted(profiles).distinctBy { runCatching { finder.cdnOf(it) }.getOrNull() to CdnEdgeScanner.tlsName(it) }.take(MAX_CLEAN_IP_CONFIGS)
        var found = 0
        val notes = mutableListOf<String>()
        for (p in targets) {
            if (stopped() || !windowIntact()) break
            val f = runCatching { finder.find(p, env.network, country) }.getOrNull() ?: continue
            found += f.ips.size
            f.skipped?.let { notes += it }
        }
        sameNetwork(env)
        TaskResult(found, (listOf("${targets.size} fronted configs") + notes.distinct().take(1)).joinToString(" · "))
    }

    private suspend fun revival(env: AutomationEnv, profiles: List<VlessProfile>): TaskResult = withContext(Dispatchers.IO) {
        // Revival keeps its winners under the carrier key, as the VPN reads them on connect.
        val coarse = NetworkKey.current(context)
        val report = app.configRevival.run(revivable(profiles), coarse, cancelled = { stopped() || !windowIntact() })
        check(windowIntact()) { "the traffic block changed during the run" }
        val ok = setOf(ConfigRevival.Outcome.REVIVED, ConfigRevival.Outcome.ALREADY_WORKING)
        report.results.filter { it.outcome in ok }.forEach { r ->
            r.latencyMs?.let { runCatching { app.serverRepository.updateLatency(r.profileId, it) } }
        }
        val health = AutomationStores.health(context)
        health.record(env.network, report.results.associate { r ->
            r.profileId to when (r.outcome) {
                ConfigRevival.Outcome.REVIVED, ConfigRevival.Outcome.ALREADY_WORKING -> true
                ConfigRevival.Outcome.NOT_REVIVED -> false
                ConfigRevival.Outcome.NOT_TESTED -> null
            }
        })
        var dropped = 0
        if (AutomationStores.autoDrop(context)) {
            val selected = runCatching { app.settingsRepository.getSettings().selectedProfileId }.getOrNull()
            val ids = health.droppable(profiles.filter { it.id != selected }.map {
                ConfigHealthBook.Candidate(it.id, fromSubscription = !it.subscriptionUrl.isNullOrBlank() || !it.sourceSubscription.isNullOrBlank(), favorite = it.isFavorite)
            })
            ids.forEach { id -> if (runCatching { app.serverRepository.delete(id) }.isSuccess) dropped++ }
            health.forget(ids)
        }
        val alive = report.results.count { it.outcome in ok }
        val revived = report.results.count { it.outcome == ConfigRevival.Outcome.REVIVED }
        TaskResult(alive, "$alive of ${report.results.size} answer · $revived revived" + if (dropped > 0) " · $dropped dead removed" else "")
    }

    private suspend fun geo(env: AutomationEnv): TaskResult {
        return when (val o = GeoFilesUpdater.update(context, viaVpn = env.tunnelUp)) {
            is GeoFilesUpdater.Outcome.Updated -> TaskResult(1, "${o.previous} → ${o.tag}, SHA-256 verified")
            is GeoFilesUpdater.Outcome.UpToDate -> TaskResult(0, "up to date (${o.tag})")
        }
    }

    companion object {
        const val MAX_DOMAINS = 3
        const val MAX_RESOLVERS = 16
        const val MAX_CLEAN_IP_CONFIGS = 3
        /** A worker gets ten minutes; the ladder keeps well inside that with the scans before it. */
        const val LADDER_BUDGET_MS = 4 * 60_000L
        const val LADDER_REQUEST_SEC = 20

        fun dnsTunnels(profiles: List<VlessProfile>): List<VlessProfile> = profiles.filter { DnsTunnelSpecs.kindOf(it) != null }
        fun fronted(profiles: List<VlessProfile>): List<VlessProfile> =
            profiles.filter { runCatching { CdnEdgeScanner.fronted(it) }.getOrDefault(false) }
        fun revivable(profiles: List<VlessProfile>): List<VlessProfile> = profiles.filter { SubscriptionPreset.autoSelectable(it) }
    }
}
