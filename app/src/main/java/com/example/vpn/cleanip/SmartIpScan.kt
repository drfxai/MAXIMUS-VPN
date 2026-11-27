package com.example.vpn.cleanip

import com.example.vpn.connections.AetherSniProbe
import com.example.vpn.connections.BuiltinMethodMemory
import com.example.vpn.smartsni.SmartSni
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Build 181 (Phase 5, C): the smart automatic clean-IP scanner, on top of the existing per-config CDN scan
 * ([CdnCleanIpFinder], Smart Connect's stage E) which it does not replace.
 *
 * Two kinds of Cloudflare address, scanned within strict bounds ([SAMPLE] random addresses per kind, [TRIES] tries
 * each, [CONCURRENCY] at a time, [BUDGET_MS] in total):
 * - WARP MASQUE gateways ([WARP_RANGES], TCP 443): TCP connect + a TLS ClientHello carrying the Smart SNI name; the
 *   edge must answer (ServerHello or alert). Aether's own pin check still decides on its real handshake. WireGuard
 *   endpoints (UDP 2408 and the other WARP ports) are not probed here: a UDP probe needs a WireGuard handshake with the
 *   account's keys, which Aether's own `--scan` already does.
 * - Cloudflare CDN edges ([Cdn.CLOUDFLARE] ranges): TCP + verified TLS for the Smart SNI name + data over it
 *   (`/cdn-cgi/trace`), and a small download (`speed.cloudflare.com/__down`) from the best few when that name
 *   passes on this network (throughput). Configs' own scans keep their own TLS name (IP × SNI stays a pair).
 *
 * Score ([score]): median TCP+TLS time, plus a heavy cost per lost try, plus a throughput cost when measured. Best
 * addresses are kept per network ([MEMORY_MS]); a failed connect through one drops it ([drop]) and an empty list
 * makes the next Smart connect scan again. A background refresh runs at most every [REFRESH_MIN_MS] per network.
 * Logs carry counts and latency only, never addresses (the LAB screen may show them).
 */
object SmartIpScan {
    const val BUDGET_MS = 8_000L
    const val SAMPLE = 16
    const val TRIES = 2
    const val CONCURRENCY = 6
    const val KEEP = 6
    const val MEMORY_MS = 6 * 60 * 60_000L
    const val REFRESH_MIN_MS = 30 * 60_000L
    const val SPEED_TOP = 2
    const val LOST_TRY_COST = 600.0
    const val SPEED_COST_FACTOR = 30_000.0
    const val SPEED_FAILED_COST = 1_000.0

    /** WARP MASQUE gateway /24s (162.159.198.x is MASQUE's own; the others answer MASQUE H2 on 443 too). */
    val WARP_RANGES = listOf("162.159.192.0/24", "162.159.193.0/24", "162.159.195.0/24", "162.159.198.0/24")
    const val WARP_PORT = 443

    enum class Kind { WARP, CDN }

    /** One try: TCP+TLS time when the edge answered, null when lost. */
    data class Edge(val ip: String, val kind: Kind, val tries: List<Long?>, val dataFlowed: Boolean = false, val kbps: Long? = null) {
        val ok: Boolean get() = tries.any { it != null }
        val lossPercent: Int get() = if (tries.isEmpty()) 100 else tries.count { it == null } * 100 / tries.size
        val medianMs: Long? get() = tries.filterNotNull().sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val address: String get() = if (kind == Kind.WARP) "$ip:$WARP_PORT" else ip
    }

    /** Lower is better; an edge that never answered scores [Double.MAX_VALUE]. Pure. */
    fun score(e: Edge): Double {
        val ms = e.medianMs ?: return Double.MAX_VALUE
        val lost = e.tries.count { it == null }
        val speed = when {
            e.kbps == null -> 0.0
            e.kbps <= 0 -> SPEED_FAILED_COST
            else -> SPEED_COST_FACTOR / e.kbps
        }
        // A CDN edge that completed TLS but carried no data is worse than any that did.
        val noData = if (e.kind == Kind.CDN && !e.dataFlowed) SPEED_FAILED_COST else 0.0
        return ms + lost * LOST_TRY_COST + speed + noData
    }

    /** Answering edges, best first, spread over /24s. Pure. */
    fun rank(edges: List<Edge>): List<Edge> {
        val best = edges.filter { it.ok }.distinctBy { it.ip }.sortedWith(compareBy<Edge>({ score(it) }, { it.ip }))
        val order = CdnRanges.diverse(best.map { it.ip })
        return order.mapNotNull { ip -> best.firstOrNull { it.ip == ip } }
    }

    /** [count] distinct addresses across [ranges], one per range in turn. Pure given [random]. */
    fun sample(ranges: List<String>, count: Int, random: Random = Random.Default): List<String> {
        val out = LinkedHashSet<String>()
        var rounds = 0
        while (out.size < count && rounds < count * 4) {
            for (r in ranges.shuffled(random)) { if (out.size >= count) break; CdnRanges.hostIn(r, random)?.let(out::add) }
            rounds++
        }
        return out.toList()
    }

    // ---- probes (replaced in tests) ------------------------------------------------------------------------------

    /** TCP+TLS ms when the WARP gateway [ip] answered a ClientHello naming [sni], else null. */
    @Volatile var warpProbe: (ip: String, sni: String, timeoutMs: Int) -> Long? = { ip, sni, t ->
        AetherSniProbe.probe(AetherSniProbe.Candidate(sni, ip, WARP_PORT), t).let { r -> if (r.outcome.reached) r.ms.coerceAtLeast(1) else null }
    }

    /** TCP+TLS+data ms and whether data flowed, for CDN edge [ip] with TLS name [sni]; null when lost. */
    @Volatile var cdnProbe: (ip: String, sni: String, timeoutMs: Int) -> Pair<Long, Boolean>? = { ip, sni, t ->
        SmartSni.liveCheck(sni, ip, t).let { p ->
            when (p.verdict) {
                SmartSni.Verdict.PASSED -> p.ms.coerceAtLeast(1) to true
                SmartSni.Verdict.REACHED -> p.ms.coerceAtLeast(1) to false
                else -> null
            }
        }
    }

    /** KB/s from [ip] (speed.cloudflare.com), or null. */
    @Volatile var download: (ip: String, timeoutMs: Int) -> Long? = { ip, t ->
        CdnEdgeScanner.liveDownload(ip, "speed.cloudflare.com", 64_000, t)
    }

    data class Report(val warp: List<Edge>, val cdn: List<Edge>, val sni: String, val ms: Long) {
        val count: Int get() = warp.size + cdn.size
        val bestMs: Long? get() = (warp + cdn).mapNotNull { it.medianMs }.minOrNull()
        /** The log line: counts and latency only, no addresses. */
        val summary: String get() = "Clean-IP scan (SNI $sni): ${warp.size} WARP gateways, ${cdn.size} CDN edges answered" +
            (bestMs?.let { ", best $it ms" } ?: "") + " in ${ms / 1000.0}s"
    }

    /**
     * Scans both kinds ([kinds]) for [network] within [budgetMs], keeps the best [KEEP] of each, and returns the
     * report. [sni]: the Smart SNI name for the TLS probes (a random name when none is known; CDN probes then need a
     * real name, so they use the first Smart SNI pool name).
     */
    fun scan(network: String?, sni: String?, kinds: Set<Kind> = setOf(Kind.WARP, Kind.CDN), budgetMs: Long = BUDGET_MS,
             random: Random = Random.Default, now: () -> Long = System::currentTimeMillis, log: (String) -> Unit = {}): Report {
        val started = now()
        val warpName = sni?.takeIf(SmartSni::validName) ?: SmartSni.randomName()
        val cdnName = sni?.takeIf { it in SmartSni.POOL } ?: SmartSni.ranked(network, passedOnly = true).firstOrNull() ?: SmartSni.LEARNED.first()
        val remembered = remembered(network)
        val warpJobs = if (Kind.WARP !in kinds) emptyList() else
            (remembered.filter { it.kind == Kind.WARP }.map { it.ip } + sample(WARP_RANGES, SAMPLE, random)).distinct().take(SAMPLE).map { Kind.WARP to it }
        val cdnJobs = if (Kind.CDN !in kinds) emptyList() else
            (remembered.filter { it.kind == Kind.CDN }.map { it.ip } + CdnRanges.sample(Cdn.CLOUDFLARE, SAMPLE, random)).distinct().take(SAMPLE).map { Kind.CDN to it }
        // Interleaved, so a short budget still touches both kinds.
        val ordered = (0 until maxOf(warpJobs.size, cdnJobs.size)).flatMap { i -> listOfNotNull(warpJobs.getOrNull(i), cdnJobs.getOrNull(i)) }
        val speedBudget = if (Kind.CDN in kinds && SmartSni.passedHere(network, "speed.cloudflare.com")) budgetMs / 4 else 0L
        val probed = SmartSni.bounded(ordered, CONCURRENCY, budgetMs - speedBudget,
            task = { (kind, ip), left ->
                val per = minOf(left / TRIES, 2_500).coerceAtLeast(300)
                var data = false
                val tries = (1..TRIES).map {
                    if (kind == Kind.WARP) runCatching { warpProbe(ip, warpName, per) }.getOrNull()
                    else runCatching { cdnProbe(ip, cdnName, per) }.getOrNull()?.let { (ms, flowed) -> data = data || flowed; ms }
                }
                Edge(ip, kind, tries, dataFlowed = data)
            },
            missed = { (kind, ip), _ -> Edge(ip, kind, emptyList()) })
        var cdn = rank(probed.filter { it.kind == Kind.CDN })
        if (speedBudget > 0 && cdn.isNotEmpty()) {
            val top = cdn.take(SPEED_TOP).map { e -> e.copy(kbps = runCatching { download(e.ip, (speedBudget / SPEED_TOP).toInt()) }.getOrNull() ?: 0L) }
            cdn = rank(top + cdn.drop(SPEED_TOP))
        }
        val warp = rank(probed.filter { it.kind == Kind.WARP })
        val report = Report(warp.take(KEEP), cdn.take(KEEP), if (Kind.WARP in kinds) warpName else cdnName, now() - started)
        save(network, report, now())
        log(report.summary)
        return report
    }

    // ---- per-network memory --------------------------------------------------------------------------------------

    private fun key(network: String) = "smartIp#$network"

    data class Saved(val ip: String, val kind: Kind, val ms: Long)

    @Synchronized
    private fun save(network: String?, r: Report, now: Long) {
        if (network.isNullOrBlank()) return
        fun list(edges: List<Edge>) = JSONArray().also { a -> edges.forEach { e -> a.put(JSONObject().put("ip", e.ip).put("ms", e.medianMs ?: 0)) } }
        val o = JSONObject().put("at", now).put("warp", list(r.warp)).put("cdn", list(r.cdn))
        runCatching { BuiltinMethodMemory.write(key(network), o.toString()) }
    }

    private fun load(network: String?): Pair<Long, List<Saved>> {
        if (network.isNullOrBlank()) return 0L to emptyList()
        val o = runCatching { JSONObject(BuiltinMethodMemory.read(key(network)) ?: return 0L to emptyList()) }.getOrNull() ?: return 0L to emptyList()
        fun list(name: String, kind: Kind) = o.optJSONArray(name)?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { e -> e.optString("ip").takeIf { CdnRanges.ipv4(it) != null }?.let { Saved(it, kind, e.optLong("ms")) } } }
        }.orEmpty()
        return o.optLong("at") to (list("warp", Kind.WARP) + list("cdn", Kind.CDN))
    }

    /** The best addresses kept for [network] (fresh only), best first per kind. */
    @Synchronized
    fun remembered(network: String?, now: Long = System.currentTimeMillis()): List<Saved> {
        val (at, list) = load(network)
        return if (now - at in 0 until MEMORY_MS) list else emptyList()
    }

    /** WARP gateways for Aether, as "ip:443". */
    fun warpPeers(network: String?, now: Long = System.currentTimeMillis()): List<String> =
        remembered(network, now).filter { it.kind == Kind.WARP }.map { "${it.ip}:$WARP_PORT" }

    /** A connect through [address] ("ip" or "ip:port") failed on [network]: drop it (an empty list makes the next connect rescan). */
    @Synchronized
    fun drop(network: String?, address: String) {
        if (network.isNullOrBlank()) return
        val ip = address.substringBeforeLast(':').takeIf { CdnRanges.ipv4(it) != null } ?: address
        val (at, list) = load(network)
        if (list.none { it.ip == ip }) return
        val left = list.filter { it.ip != ip }
        fun arr(kind: Kind) = JSONArray().also { a -> left.filter { it.kind == kind }.forEach { s -> a.put(JSONObject().put("ip", s.ip).put("ms", s.ms)) } }
        runCatching { BuiltinMethodMemory.write(key(network), JSONObject().put("at", at).put("warp", arr(Kind.WARP)).put("cdn", arr(Kind.CDN)).toString()) }
    }

    /** Whether a Smart connect should scan before it starts: the scanner is on and nothing fresh is kept for [kind]. */
    fun needsScan(network: String?, kind: Kind, now: Long = System.currentTimeMillis()): Boolean =
        com.example.vpn.smartsni.SmartSniSettings.cleanIpScan && !network.isNullOrBlank() && remembered(network, now).none { it.kind == kind }

    /** Background refresh rate limit: on, same network, idle, and the last scan at least [REFRESH_MIN_MS] ago. Pure. */
    fun mayRefresh(enabled: Boolean, idle: Boolean, lastScanAt: Long, now: Long): Boolean =
        enabled && idle && (lastScanAt <= 0 || now - lastScanAt !in 0 until REFRESH_MIN_MS)

    fun lastScanAt(network: String?): Long = load(network).first
}
