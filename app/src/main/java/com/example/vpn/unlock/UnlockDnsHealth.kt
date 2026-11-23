package com.example.vpn.unlock

import com.example.vpn.dns.ResolverScanner
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** How an Unlock DNS server did in its last check, as the sheet shows it. */
enum class ServerHealth { UNKNOWN, HEALTHY, SLOW, BLOCKED }

/** Why a server was marked [ServerHealth.BLOCKED]. */
enum class BlockReason {
    /** No answer to the control question over UDP or TCP. */
    NO_ANSWER,
    /** The control name came back with addresses that are not its own (or a filter/bogon address). */
    HIJACKED,
    /** The answer came from an address other than the server asked: something on the path answers for it. */
    INTERCEPTED,
    /** The server did not answer the unlock probe name with usable addresses. */
    NO_UNLOCK_ANSWER,
    /** The unlock probe's addresses did not accept a TCP connection on 443. */
    UNLOCK_UNREACHABLE
}

/** One DNS answer as the probe saw it: A records, round trip, and whether another address replied. */
data class Lookup(val ips: List<String>, val ms: Long, val intercepted: Boolean = false)

/** The network side of a health check; the app uses [UnlockDnsWire.Probe], tests a fake. */
interface UnlockProbe {
    /** The A answer for [name] from [server] (UDP/53, then TCP/53), or null when nothing usable came back. */
    fun lookup(server: String, name: String, timeoutMs: Int): Lookup?
    /** Connect time to [ip]:443 in ms, or null when the connection did not open. */
    fun reach(ip: String, port: Int, timeoutMs: Int): Long?
}

data class ServerCheck(
    val ip: String,
    val health: ServerHealth,
    /** DNS round trip (the faster of the two questions); null when the server never answered. */
    val latencyMs: Long? = null,
    val reason: BlockReason? = null,
    val checkedAt: Long = 0L
) {
    val usable: Boolean get() = health == ServerHealth.HEALTHY || health == ServerHealth.SLOW
}

/**
 * Health checks for Unlock DNS servers.
 *
 * Each server answers two questions. The control name ([CONTROL_NAME], one.one.one.one) must come back as
 * 1.1.1.1 / 1.0.0.1: anything else means the answer was rewritten on the way (or by the server), and a server
 * whose plain answers are rewritten must never become the phone's resolver. The unlock probe
 * ([UNLOCK_PROBE], gemini.google.com) must come back with public A records, and one of them must accept a TCP
 * connection on 443: that is the relay the user's apps will reach. Servers that pass are ranked by DNS round
 * trip; above [SLOW_MS] a server is shown as Slow but stays usable.
 */
object UnlockDnsHealth {
    const val CONTROL_NAME = ResolverScanner.CONTROL_NAME
    val CONTROL_ANSWERS = ResolverScanner.CONTROL_ANSWERS
    const val UNLOCK_PROBE = "gemini.google.com"
    const val SLOW_MS = 400L
    const val LOOKUP_TIMEOUT_MS = 1_800
    const val REACH_TIMEOUT_MS = 2_500
    /** How many of the probe's addresses get a TCP/443 attempt before the server counts as unreachable. */
    const val REACH_TRIES = 2
    const val PARALLELISM = 4
    /** Whole-check limit; one server takes at most ~2 lookups x 2 transports + 2 connects. */
    const val DEADLINE_MS = 12_000L

    /** True when [ips] for the control name are not exactly its own addresses, or any is a filter/bogon. */
    fun hijacked(ips: List<String>): Boolean =
        ips.isEmpty() || ips.any(ResolverScanner::poisoned) || ips.any { it !in CONTROL_ANSWERS }

    fun check(ip: String, probe: UnlockProbe, now: Long = System.currentTimeMillis()): ServerCheck {
        fun blocked(reason: BlockReason, ms: Long? = null) = ServerCheck(ip, ServerHealth.BLOCKED, ms, reason, now)
        val control = probe.lookup(ip, CONTROL_NAME, LOOKUP_TIMEOUT_MS) ?: return blocked(BlockReason.NO_ANSWER)
        if (control.intercepted) return blocked(BlockReason.INTERCEPTED, control.ms)
        if (hijacked(control.ips)) return blocked(BlockReason.HIJACKED, control.ms)
        val unlock = probe.lookup(ip, UNLOCK_PROBE, LOOKUP_TIMEOUT_MS)
        if (unlock == null || unlock.intercepted || unlock.ips.isEmpty() || unlock.ips.any(ResolverScanner::poisoned)) {
            return blocked(if (unlock?.intercepted == true) BlockReason.INTERCEPTED else BlockReason.NO_UNLOCK_ANSWER, control.ms)
        }
        val reachable = unlock.ips.distinct().take(REACH_TRIES).any { probe.reach(it, 443, REACH_TIMEOUT_MS) != null }
        val latency = minOf(control.ms, unlock.ms)
        if (!reachable) return blocked(BlockReason.UNLOCK_UNREACHABLE, latency)
        return ServerCheck(ip, if (latency > SLOW_MS) ServerHealth.SLOW else ServerHealth.HEALTHY, latency, null, now)
    }

    /** Healthy first, then Slow, then not yet checked, then Blocked; fastest first within each. */
    fun rank(checks: List<ServerCheck>): List<ServerCheck> = checks.sortedWith(
        compareBy<ServerCheck> { order(it.health) }.thenBy { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.ip }
    )

    private fun order(h: ServerHealth): Int = when (h) {
        ServerHealth.HEALTHY -> 0
        ServerHealth.SLOW -> 1
        ServerHealth.UNKNOWN -> 2
        ServerHealth.BLOCKED -> 3
    }

    /**
     * The server to use. With [autoPick] the fastest usable one (dead servers are dropped); otherwise the
     * user's [manual] choice as long as it is still listed, whatever its last check said (the user decides),
     * falling back to auto-pick when it is gone.
     */
    fun pick(ranked: List<ServerCheck>, autoPick: Boolean, manual: String?): String? {
        if (!autoPick && manual != null && ranked.any { it.ip == manual }) return manual
        return rank(ranked).firstOrNull { it.usable }?.ip
    }

    /** The next usable server after [current] in rank order, for a runtime switch when [current] stops answering. */
    fun next(ranked: List<ServerCheck>, current: String): String? =
        rank(ranked).firstOrNull { it.usable && it.ip != current }?.ip

    /** Checks [servers] with at most [parallelism] in flight; a server not done by [deadlineMs] counts as Blocked. */
    fun checkAll(
        servers: List<String>,
        probe: UnlockProbe,
        parallelism: Int = PARALLELISM,
        deadlineMs: Long = DEADLINE_MS,
        now: Long = System.currentTimeMillis()
    ): List<ServerCheck> {
        if (servers.isEmpty()) return emptyList()
        val list = servers.distinct()
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, list.size))
        return try {
            val futures = pool.invokeAll(list.map { ip -> Callable { runCatching { check(ip, probe, now) }.getOrNull() } },
                deadlineMs, TimeUnit.MILLISECONDS)
            rank(list.mapIndexed { i, ip ->
                val f = futures[i]
                (if (f.isCancelled) null else runCatching { f.get() }.getOrNull())
                    ?: ServerCheck(ip, ServerHealth.BLOCKED, null, BlockReason.NO_ANSWER, now)
            })
        } finally {
            pool.shutdownNow()
        }
    }

    /** Services the Home badges verify through the active server: label key and the name looked up. */
    enum class Service(val host: String) {
        GEMINI("gemini.google.com"),
        CHATGPT("chatgpt.com"),
        AI_STUDIO("aistudio.google.com"),
        ANDROID_DEV("developer.android.com")
    }

    /**
     * Whether [service] resolves through [server] to public addresses that accept TCP/443. This says the
     * relay is reachable from this network, not that the service accepted the account.
     */
    fun verify(server: String, service: Service, probe: UnlockProbe): Boolean {
        val answer = probe.lookup(server, service.host, LOOKUP_TIMEOUT_MS) ?: return false
        if (answer.intercepted || answer.ips.isEmpty() || answer.ips.any(ResolverScanner::poisoned)) return false
        return answer.ips.distinct().take(REACH_TRIES).any { probe.reach(it, 443, REACH_TIMEOUT_MS) != null }
    }
}
