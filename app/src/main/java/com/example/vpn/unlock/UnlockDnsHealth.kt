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
    /**
     * The A answer for [name] from [server] over TCP/53 only, or null (not supported, or nothing usable). Build 179:
     * a UDP answer rewritten on the path is checked against the server's own answer over TCP.
     */
    fun lookupTcp(server: String, name: String, timeoutMs: Int): Lookup? = null
}

data class ServerCheck(
    val ip: String,
    val health: ServerHealth,
    /** DNS round trip (the faster of the two questions); null when the server never answered. */
    val latencyMs: Long? = null,
    val reason: BlockReason? = null,
    val checkedAt: Long = 0L,
    /** The query shape this server passed with (its plain questions failed); null for plain. */
    val shape: QueryShape? = null,
    /** Its UDP answers are rewritten on the path but its TCP/53 answers are its own: it is asked over TCP (build 179). */
    val tcp: Boolean = false,
    /** What the check saw, for the log ("control name answered 10.10.34.35"); never shown as a server's fault alone. */
    val detail: String? = null
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

    /**
     * A second control name whose address never changes and that no filter list carries (a root server): used only when
     * [CONTROL_NAME] came back as a national filter address, to tell "this name is filtered on this network" from "this
     * server rewrites answers".
     */
    const val ROOT_CONTROL_NAME = "a.root-servers.net"
    val ROOT_CONTROL_ANSWERS = setOf("198.41.0.4")

    /** Every address is a filter or bogon answer (10.10.34.x and the like): the name is filtered here, not rewritten. */
    fun filtered(ips: List<String>): Boolean = ips.isNotEmpty() && ips.all(ResolverScanner::poisoned)

    enum class ControlVerdict { OK, OK_OVER_TCP, OK_BY_ROOT, HIJACKED }

    /**
     * Build 179 device test (Irancell): all four Unlock servers were "hijacked" in 33-63 ms, which was the network, not
     * the servers: one.one.one.one is answered with a filter address on the way. The control now passes when
     * - the UDP answer is the name's own ([OK]);
     * - the server's own answer over TCP/53 is ([OK_OVER_TCP]): UDP is rewritten on the path, so the server is asked
     *   over TCP;
     * - the control name only came back as a filter address (UDP and TCP) and the root control ([ROOT_CONTROL_NAME])
     *   answers exactly ([OK_BY_ROOT]).
     * Any other public address for the control name is a rewrite: [HIJACKED]. Pure.
     */
    fun controlVerdict(udp: List<String>, tcp: List<String>?, root: () -> List<String>?): ControlVerdict = when {
        !hijacked(udp) -> ControlVerdict.OK
        tcp != null && !hijacked(tcp) -> ControlVerdict.OK_OVER_TCP
        filtered(udp) && (tcp == null || tcp.isEmpty() || filtered(tcp)) &&
            root()?.let { it.isNotEmpty() && it.all { a -> a in ROOT_CONTROL_ANSWERS } } == true -> ControlVerdict.OK_BY_ROOT
        else -> ControlVerdict.HIJACKED
    }

    /** The log's account of a control answer that was not the name's own. */
    fun controlDetail(udp: List<String>, tcp: List<String>?): String =
        "$CONTROL_NAME answered " + udp.take(3).joinToString(", ").ifEmpty { "nothing" } + " over UDP" +
            (tcp?.let { " and " + it.take(3).joinToString(", ").ifEmpty { "nothing" } + " over TCP" } ?: "") +
            if (filtered(udp)) " (a filter address: this network rewrites or filters plain DNS)" else " (not its own address)"

    fun check(ip: String, probe: UnlockProbe, now: Long = System.currentTimeMillis()): ServerCheck {
        fun blocked(reason: BlockReason, ms: Long? = null, detail: String? = null) =
            ServerCheck(ip, ServerHealth.BLOCKED, ms, reason, now, detail = detail)
        val control = probe.lookup(ip, CONTROL_NAME, LOOKUP_TIMEOUT_MS) ?: return blocked(BlockReason.NO_ANSWER)
        if (control.intercepted) return blocked(BlockReason.INTERCEPTED, control.ms)
        var overTcp = false
        var detail: String? = null
        var controlMs = control.ms
        if (hijacked(control.ips)) {
            val tcp = probe.lookupTcp(ip, CONTROL_NAME, LOOKUP_TIMEOUT_MS)
            val verdict = controlVerdict(control.ips, tcp?.ips) {
                probe.lookup(ip, ROOT_CONTROL_NAME, LOOKUP_TIMEOUT_MS)?.takeIf { !it.intercepted }?.ips
            }
            detail = controlDetail(control.ips, tcp?.ips)
            when (verdict) {
                ControlVerdict.HIJACKED -> return blocked(BlockReason.HIJACKED, control.ms, detail)
                ControlVerdict.OK_OVER_TCP -> { overTcp = true; controlMs = tcp?.ms ?: control.ms; detail += "; the server's own answer over TCP is correct, so it is asked over TCP" }
                ControlVerdict.OK_BY_ROOT -> detail += "; $ROOT_CONTROL_NAME answered correctly, so the server is not rewriting answers"
                ControlVerdict.OK -> Unit
            }
        }
        val unlock = if (overTcp) probe.lookupTcp(ip, UNLOCK_PROBE, LOOKUP_TIMEOUT_MS) else probe.lookup(ip, UNLOCK_PROBE, LOOKUP_TIMEOUT_MS)
        if (unlock == null || unlock.intercepted || unlock.ips.isEmpty() || unlock.ips.any(ResolverScanner::poisoned)) {
            return blocked(if (unlock?.intercepted == true) BlockReason.INTERCEPTED else BlockReason.NO_UNLOCK_ANSWER, controlMs, detail)
        }
        val reachable = unlock.ips.distinct().take(REACH_TRIES).any { probe.reach(it, 443, REACH_TIMEOUT_MS) != null }
        val latency = minOf(controlMs, unlock.ms)
        if (!reachable) return blocked(BlockReason.UNLOCK_UNREACHABLE, latency, detail)
        return ServerCheck(ip, if (latency > SLOW_MS) ServerHealth.SLOW else ServerHealth.HEALTHY, latency, null, now,
            tcp = overTcp, detail = detail)
    }

    /**
     * Every server was refused for an answer rewritten on the way (or intercepted): the failure says it is this network's
     * DNS, not the servers. Pure.
     */
    fun networkRewritesDns(checks: List<ServerCheck>): Boolean =
        checks.isNotEmpty() && checks.none { it.usable } &&
            checks.all { it.reason == BlockReason.HIJACKED || it.reason == BlockReason.INTERCEPTED }

    /** Block reasons a query shape can change: something on the path answered the plain question. */
    val SHAPEABLE = setOf(BlockReason.HIJACKED, BlockReason.INTERCEPTED, BlockReason.NO_UNLOCK_ANSWER)

    /**
     * [check] with query shaping: the [remembered] shape first (it passed on this network before), then plain;
     * when plain fails for a [SHAPEABLE] reason, each [QueryShape] in turn within [UnlockDnsShaping.CHECK_BUDGET_MS].
     * A server with no answer at all (dead, or UDP and TCP blocked) gets no shaped attempts. The result carries
     * the shape that passed; otherwise the plain result.
     */
    fun checkShaped(
        ip: String,
        plain: UnlockProbe,
        shaped: (QueryShape) -> UnlockProbe,
        remembered: QueryShape?,
        now: Long = System.currentTimeMillis(),
        clock: () -> Long = System::currentTimeMillis
    ): ServerCheck {
        if (remembered != null) {
            val again = check(ip, shaped(remembered), now)
            if (again.usable) return again.copy(shape = remembered)
        }
        val first = check(ip, plain, now)
        if (first.usable || first.reason !in SHAPEABLE) return first
        val started = clock()
        for (shape in QueryShape.entries) {
            if (shape == remembered) continue
            if (clock() - started >= UnlockDnsShaping.CHECK_BUDGET_MS) break
            val result = check(ip, shaped(shape), now)
            if (result.usable) return result.copy(shape = shape)
        }
        return first
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
        now: Long = System.currentTimeMillis(),
        checker: (String) -> ServerCheck = { check(it, probe, now) }
    ): List<ServerCheck> {
        if (servers.isEmpty()) return emptyList()
        val list = servers.distinct()
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, list.size))
        return try {
            val futures = pool.invokeAll(list.map { ip -> Callable { runCatching { checker(ip) }.getOrNull() } },
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
