package com.example.vpn.smart

/**
 * Hard time caps and early-abort rules for the recovery engines on metered or Cloudflare-blocked networks.
 *
 * Irancell field diagnostics (build 145, cf=0): after every Xray server failed, ByeDPI spent ~22 s on four
 * "recv: Connection timed out" replies, then Tor over WebTunnel ran for 2.5 min with every bridge dial reset
 * ("connection reset by peer" / "unrecognized reply") and bootstrap stuck at 25-30 %, while WARP waited behind
 * it and never ran before the user gave up. So, on such networks:
 *
 * - ByeDPI gets at most [BYEDPI_CAP_MS] in the whole search, and a test ends after [BYEDPI_TIMEOUT_STREAK]
 *   consecutive receive timeouts;
 * - Tor (every bridge type together) gets at most [TOR_CAP_MS], and a start ends when bootstrap makes no
 *   progress for [TOR_NO_PROGRESS_MS];
 * - a WebTunnel bridge that is reset or answers with an unrecognized reply [BRIDGE_FAILURES_TO_DROP] times is
 *   dropped for this search and remembered on this network for [BRIDGE_MEMORY_MS] ([WebTunnelFailureBook]);
 * - on Irancell with cf=0 the single capped Irancell WARP attempt (MASQUE over HTTP/2, see IrancellWarp) runs
 *   before the other recovery engines instead of only after them: cf=0 is a plain-TLS probe to Cloudflare, while
 *   Aether's MASQUE-H2 has its own name/edge fallbacks and ClientHello split.
 */
object RecoveryEngineCaps {
    const val BYEDPI_CAP_MS = 10_000L
    const val BYEDPI_TIMEOUT_STREAK = 2
    /** Below this, a ByeDPI test is not started at all. */
    const val BYEDPI_MIN_WINDOW_MS = 2_000L

    const val TOR_CAP_MS = 45_000L
    const val TOR_NO_PROGRESS_MS = 20_000L
    /** Below this, no further Tor start fits in the cap: Tor cannot bootstrap in a few seconds. */
    const val TOR_MIN_WINDOW_MS = 10_000L

    const val BRIDGE_FAILURES_TO_DROP = 3
    const val BRIDGE_MEMORY_MS = 6 * 60 * 60_000L

    /** The Irancell WARP attempt's own budget (IrancellWarp.BUDGET_MS, 15 s) plus a short traffic check. */
    const val IRANCELL_WARP_WINDOW_MS = com.example.vpn.connections.IrancellWarp.BUDGET_MS + 5_000L

    /** Metered (mobile data) or Cloudflare measured blocked here: heavy engines are capped. */
    fun constrained(metered: Boolean?, cloudflareBlocked: Boolean): Boolean = metered == true || cloudflareBlocked

    /** The capped group an engine belongs to, or null for engines without a group cap. */
    fun group(engineId: String?): String? = when (engineId) {
        "byedpi" -> "byedpi"
        "tor" -> "tor"
        else -> null
    }

    fun capOf(group: String?): Long? = when (group) {
        "byedpi" -> BYEDPI_CAP_MS
        "tor" -> TOR_CAP_MS
        else -> null
    }

    /** Irancell, Cloudflare measured blocked (cf=0), and an automatic Aether lead was held back: WARP before Tor. */
    fun irancellWarpFirst(irancell: Boolean, cloudflareBlocked: Boolean, aetherHeldBack: Boolean): Boolean =
        irancell && cloudflareBlocked && aetherHeldBack

    /** Time each capped group may still use in one recovery search. Not thread-safe: one per search. */
    class Budget(val constrained: Boolean) {
        private val spent = mutableMapOf<String, Long>()

        fun spent(engineId: String?): Long = group(engineId)?.let { spent[it] } ?: 0L

        /** Time left for [engineId]'s group, or null when it has no cap here. */
        fun left(engineId: String?): Long? {
            if (!constrained) return null
            val g = group(engineId) ?: return null
            return (capOf(g)!! - (spent[g] ?: 0L)).coerceAtLeast(0L)
        }

        /** The window for the next test: [normalMs] cut to the group's remaining cap; null = cap used up, skip it. */
        fun window(engineId: String?, normalMs: Long): Long? {
            val left = left(engineId) ?: return normalMs
            val min = if (group(engineId) == "tor") TOR_MIN_WINDOW_MS else BYEDPI_MIN_WINDOW_MS
            return if (left < min) null else minOf(normalMs, left)
        }

        fun charge(engineId: String?, ms: Long) {
            val g = group(engineId) ?: return
            spent[g] = (spent[g] ?: 0L) + ms.coerceAtLeast(0L)
        }
    }

    /** True for ByeDPI's "recv: Connection timed out" (and similar receive-timeout) lines. */
    fun isRecvTimeout(line: String): Boolean =
        line.contains("recv", ignoreCase = true) && line.contains("timed out", ignoreCase = true)

    /** True for a bridge dial the censor cut or answered with something that is not the bridge. */
    fun isBridgeFailure(line: String): Boolean =
        line.contains("connection reset by peer", ignoreCase = true) ||
            line.contains("unrecognized reply", ignoreCase = true)

    /** Ends a ByeDPI test after [streak] receive timeouts in a row. Thread-safe. */
    class ByedpiWatch(private val streak: Int = BYEDPI_TIMEOUT_STREAK) {
        private var run = 0
        private var reason: String? = null

        @Synchronized fun saw(line: String) {
            if (line.isBlank() || reason != null) return
            if (isRecvTimeout(line)) {
                run++
                if (run >= streak) reason = "$run consecutive recv timeouts"
            } else run = 0
        }

        @Synchronized fun abortReason(): String? = reason
    }

    /**
     * Watches one Tor start: bootstrap progress ("Tor N%: ..." lines) and WebTunnel bridge failures. Ends the
     * start when bootstrap has not advanced for [noProgressMs], or when every WebTunnel bridge of the start has
     * been dropped. Thread-safe: the bridge program's log thread and the waiting thread both feed it.
     */
    class TorWatch(
        private val startedAt: Long,
        private val noProgressMs: Long = TOR_NO_PROGRESS_MS,
        private val failuresToDrop: Int = BRIDGE_FAILURES_TO_DROP
    ) {
        /** [lineAddr]: the address field of the bridge line, which Tor's own warnings name. */
        private data class Bridge(val host: String, val lineAddr: String?, var addr: String?)

        private var lastProgressAt = startedAt
        private var best = -1
        private val bridges = linkedMapOf<String, Bridge>()
        private val failures = mutableMapOf<String, Int>()
        private val dropped = linkedSetOf<String>()
        private var unattributed = 0

        /** A WebTunnel bridge line this start uses (before or after its `addr=` was pinned). */
        @Synchronized fun bridge(line: String) {
            if (!line.trim().startsWith("webtunnel ")) return
            val host = hostOf(line) ?: return
            val addr = addrOf(line)
            val known = bridges[host]
            if (known == null) bridges[host] = Bridge(host, line.trim().split(' ').getOrNull(1)?.lowercase(), addr)
            else if (addr != null) known.addr = addr
        }

        @Synchronized fun saw(line: String, now: Long) {
            com.example.vpn.sidecar.TorStartDeadline.percentOf(line)?.let { p ->
                if (p > best) { best = p; lastProgressAt = now }
            }
            if (!isBridgeFailure(line) || bridges.isEmpty()) return
            val lower = line.lowercase()
            var hit = bridges.values.filter { b ->
                lower.contains(b.host.lowercase()) || b.lineAddr?.let { lower.contains(it) } == true ||
                    b.addr?.let { a -> lower.contains(a) || lower.contains(a.substringBeforeLast(':')) } == true
            }
            // Not named in the line: attribute only when that is unambiguous (one bridge, or all on one address).
            if (hit.isEmpty()) {
                val addrs = bridges.values.map { it.addr?.substringBeforeLast(':') }.toSet()
                hit = if (bridges.size == 1 || (addrs.size == 1 && addrs.first() != null)) bridges.values.toList() else emptyList()
            }
            if (hit.isEmpty()) {
                unattributed++
                if (unattributed >= failuresToDrop * bridges.size) dropped += bridges.keys
                return
            }
            hit.forEach { b ->
                val n = (failures[b.host] ?: 0) + 1
                failures[b.host] = n
                if (n >= failuresToDrop) dropped += b.host
            }
        }

        /** WebTunnel bridge hosts that failed [failuresToDrop] times in this start. */
        @Synchronized fun dropped(): Set<String> = dropped.toSet()

        @Synchronized fun abortReason(now: Long): String? = when {
            bridges.isNotEmpty() && dropped.containsAll(bridges.keys) ->
                "every WebTunnel bridge was reset or gave an unrecognized reply $failuresToDrop times"
            now - lastProgressAt >= noProgressMs ->
                "no bootstrap progress for ${noProgressMs / 1000}s" + (if (best >= 0) " (stuck at $best%)" else "")
            else -> null
        }

        @Synchronized fun bestPercent(): Int = best

        companion object {
            fun hostOf(line: String): String? = line.split(' ').firstOrNull { it.startsWith("url=") }?.removePrefix("url=")
                ?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.lowercase()

            fun addrOf(line: String): String? = line.split(' ').firstOrNull { it.startsWith("addr=") }?.removePrefix("addr=")
                ?.removePrefix("[")?.replace("]:", ":")
        }
    }

    /** One line at the end of the escalation: each engine, the time it took, and what came of it. */
    class EscalationSummary {
        private data class Entry(var attempts: Int, var ms: Long, val results: LinkedHashSet<String>)
        private val entries = linkedMapOf<String, Entry>()

        @Synchronized fun add(engine: String, ms: Long, result: String) {
            val e = entries.getOrPut(engine) { Entry(0, 0, linkedSetOf()) }
            e.attempts++
            e.ms += ms.coerceAtLeast(0)
            e.results += result.take(90)
        }

        @Synchronized fun isEmpty(): Boolean = entries.isEmpty()

        @Synchronized fun line(outcome: String): String = "Escalation summary: " +
            (if (entries.isEmpty()) "no engine was tried" else entries.entries.joinToString(" · ") { (name, e) ->
                name + (if (e.attempts > 1) "×${e.attempts}" else "") + " " + "%.1fs".format(java.util.Locale.ROOT, e.ms / 1000.0) +
                    " " + e.results.joinToString("; ")
            }) + " → $outcome"
    }
}

/**
 * WebTunnel bridges whose dials were reset or answered with an unrecognized reply [RecoveryEngineCaps.BRIDGE_FAILURES_TO_DROP]
 * times, per network, for [RecoveryEngineCaps.BRIDGE_MEMORY_MS]: later recovery searches on that network leave them out.
 * In memory for the app process; keyed by the bridge URL's host name.
 */
class WebTunnelFailureBook(
    private val ttlMs: Long = RecoveryEngineCaps.BRIDGE_MEMORY_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val until = mutableMapOf<Pair<String, String>, Long>()

    @Synchronized fun drop(network: String, host: String) {
        until[network to host.lowercase()] = clock() + ttlMs
    }

    @Synchronized fun dropped(network: String, host: String): Boolean {
        val key = network to host.lowercase()
        val t = until[key] ?: return false
        if (clock() >= t) { until.remove(key); return false }
        return true
    }

    /** [lines] without the WebTunnel bridges remembered as failing on [network]; other lines are kept. */
    fun usable(network: String, lines: List<String>): List<String> = lines.filter { line ->
        val host = RecoveryEngineCaps.TorWatch.hostOf(line)
        !(line.trim().startsWith("webtunnel ") && host != null && dropped(network, host))
    }

    companion object {
        val shared = WebTunnelFailureBook()
    }
}
