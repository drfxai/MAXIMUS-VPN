package com.example.vpn.automation

/** What a run knows about the phone when it starts. */
data class AutomationEnv(
    /** The physical network's fingerprint (NetworkIdentity), the key every cache is kept under. */
    val network: String,
    /** Any VPN is up (this app's tunnel, its blocking interface, or another app's). */
    val vpnActive: Boolean,
    /** This app's tunnel is connected and verified: downloads can go through it. */
    val tunnelUp: Boolean,
    val metered: Boolean,
    val now: Long,
    val hasDnsTunnels: Boolean = false,
    val hasFronted: Boolean = false,
    val hasRevivable: Boolean = false,
    /** "GeoFiles via proxy" is on: download them only through this app's tunnel. */
    val geoNeedsTunnel: Boolean = false,
    /**
     * The only VPN up is this app's own traffic-blocking interface (a failed connect kept fail-closed until the user
     * presses Disconnect) and the service can let this app's own sockets out beside it ([OwnVpnWindow]): measuring
     * tasks run then, while other apps stay blocked. Build 174 report: every upkeep after a failed connect or a
     * stop was skipped with VPN_ACTIVE because that blocking interface counted as "a VPN".
     */
    val ownBlockOnly: Boolean = false,
    /**
     * This app's tunnel is up and the app itself is left out of it (an engine program: Aether, Serverless, Psiphon,
     * Lantern…): this app's own sockets then reach the phone's network directly, beside the tunnel, so a network-only
     * measurement ([AutomationPolicy.BESIDE_TUNNEL]) measures the real link, not the tunnel. Build 175 report: every
     * upkeep while connected skipped with VPN_ACTIVE, and the one after each stop found the next connect already up.
     */
    val appOutsideTunnel: Boolean = false,
    /** The only VPN up is this app's own, and a connect is under way (no tunnel yet, no plain traffic block). */
    val ownConnecting: Boolean = false
)

/**
 * Which upkeep tasks a trigger runs, given the phone's state and how fresh each task's result is on this
 * network. Pure. Measuring tasks never run under another app's VPN, nor through this app's connected tunnel (they would
 * measure the tunnel); network-only ones run beside a tunnel the app is left out of ([BESIDE_TUNNEL]); beside this app's own traffic block ([AutomationEnv.ownBlockOnly]) they run, outside it. The GeoFiles
 * download prefers the tunnel and otherwise waits for an unmetered network.
 */
object AutomationPolicy {
    data class Decision(val task: AutomationTask, val skip: SkipReason?) {
        val run: Boolean get() = skip == null
    }

    /**
     * Tasks that only send this app's own packets on the phone's network (DNS queries, TLS handshakes to CDN edges)
     * and start no engine program. They run beside a connected tunnel the app is left out of
     * ([AutomationEnv.appOutsideTunnel]). The DNS-tunnel ladder and config revival start engines (more processes,
     * more ports, minutes of work on the user's link) and keep waiting for a stop.
     */
    val BESIDE_TUNNEL: Set<AutomationTask> = setOf(AutomationTask.RESOLVER_SCAN, AutomationTask.CLEAN_IP_SCAN)

    /** A network-only [task] may run beside this app's connected tunnel. Pure. */
    fun besideTunnel(task: AutomationTask, env: AutomationEnv): Boolean =
        task in BESIDE_TUNNEL && env.tunnelUp && env.appOutsideTunnel

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** How old a task's last success on this network may be before [trigger] runs it again. */
    fun maxAge(task: AutomationTask, trigger: AutomationTrigger): Long = when (trigger) {
        AutomationTrigger.MANUAL -> 0
        AutomationTrigger.CONNECT_FAILED -> when (task) {
            AutomationTask.RESOLVER_SCAN -> 30 * MIN
            AutomationTask.DNS_LADDER, AutomationTask.CLEAN_IP_SCAN -> HOUR
            AutomationTask.CONFIG_REVIVAL -> 2 * HOUR
            AutomationTask.GEO_UPDATE -> 3 * DAY
        }
        else -> when (task) {
            // The resolver pool lives 12 h and asks for a rescan at half-life.
            AutomationTask.RESOLVER_SCAN -> 6 * HOUR
            AutomationTask.CLEAN_IP_SCAN -> 12 * HOUR
            AutomationTask.DNS_LADDER, AutomationTask.CONFIG_REVIVAL -> DAY
            AutomationTask.GEO_UPDATE -> 3 * DAY
        }
    }

    private val triggers: Map<AutomationTask, Set<AutomationTrigger>> = run {
        val measuring = setOf(AutomationTrigger.NETWORK_CHANGED, AutomationTrigger.VPN_STOPPED, AutomationTrigger.DAILY,
            AutomationTrigger.CONNECT_FAILED, AutomationTrigger.MANUAL)
        mapOf(
            AutomationTask.RESOLVER_SCAN to measuring,
            AutomationTask.DNS_LADDER to measuring,
            AutomationTask.CLEAN_IP_SCAN to measuring,
            AutomationTask.CONFIG_REVIVAL to measuring,
            AutomationTask.GEO_UPDATE to setOf(AutomationTrigger.DAILY, AutomationTrigger.CONNECTED, AutomationTrigger.MANUAL)
        )
    }

    /**
     * Whether a finished run should be tried again a little later (WorkManager's linear backoff, re-reading the phone's
     * state each time). After a failed connect the blocking interface may still be settling; and while this app's own
     * connect is under way (build 175: each VPN_STOPPED run, 30 s after a Disconnect, found the user's next connect
     * already starting and skipped everything), the next try finds the tunnel up (network-only tasks run beside it),
     * the traffic block (measured beside it) or nothing (everything runs). Pure.
     */
    fun retryLater(trigger: AutomationTrigger, env: AutomationEnv, skipped: Collection<SkipReason>, ranAny: Boolean,
                   attempt: Int, maxRetries: Int): Boolean {
        if (attempt >= maxRetries || SkipReason.VPN_ACTIVE !in skipped) return false
        if (trigger == AutomationTrigger.CONNECT_FAILED) return !ranAny && SkipReason.FRESH !in skipped
        return env.ownConnecting && trigger in setOf(AutomationTrigger.NETWORK_CHANGED, AutomationTrigger.VPN_STOPPED,
            AutomationTrigger.DAILY, AutomationTrigger.CONNECT_FAILED)
    }

    /** The report's `vpn` label for a run. Pure. */
    fun vpnLabel(env: AutomationEnv): String = when {
        !env.vpnActive -> "none"
        env.tunnelUp && env.appOutsideTunnel -> "tunnel-app-outside"
        env.tunnelUp -> "tunnel"
        env.ownBlockOnly -> "own-block"
        env.ownConnecting -> "own-connect"
        else -> "other"
    }

    /** GeoFiles are global, not per network. */
    fun cacheKey(task: AutomationTask, env: AutomationEnv): String = if (task == AutomationTask.GEO_UPDATE) "*" else env.network

    fun plan(trigger: AutomationTrigger, env: AutomationEnv, lastOk: (AutomationTask, String) -> Long): List<Decision> =
        AutomationTask.entries.map { task -> Decision(task, skip(task, trigger, env, lastOk)) }

    private fun skip(task: AutomationTask, trigger: AutomationTrigger, env: AutomationEnv, lastOk: (AutomationTask, String) -> Long): SkipReason? {
        if (trigger !in triggers.getValue(task)) return SkipReason.NOT_THIS_TRIGGER
        when (task) {
            AutomationTask.GEO_UPDATE -> {
                if (!env.tunnelUp && env.geoNeedsTunnel) return SkipReason.NEEDS_TUNNEL
                if (!env.tunnelUp && env.vpnActive && !env.ownBlockOnly) return SkipReason.VPN_ACTIVE
                if (!env.tunnelUp && env.metered && trigger != AutomationTrigger.MANUAL) return SkipReason.METERED
            }
            else -> if (env.vpnActive && !env.ownBlockOnly && !besideTunnel(task, env)) return SkipReason.VPN_ACTIVE
        }
        when (task) {
            AutomationTask.DNS_LADDER -> if (!env.hasDnsTunnels) return SkipReason.NOTHING_TO_DO
            AutomationTask.CLEAN_IP_SCAN -> if (!env.hasFronted) return SkipReason.NOTHING_TO_DO
            AutomationTask.CONFIG_REVIVAL -> if (!env.hasRevivable) return SkipReason.NOTHING_TO_DO
            else -> Unit
        }
        val last = lastOk(task, cacheKey(task, env))
        val age = env.now - last
        if (last > 0 && age in 0 until maxAge(task, trigger)) return SkipReason.FRESH
        return null
    }
}
