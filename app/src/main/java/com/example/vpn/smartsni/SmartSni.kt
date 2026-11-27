package com.example.vpn.smartsni

import com.example.vpn.connections.BuiltinMethodMemory
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Build 181 (Phase 5, A): Smart SNI. Finds, per network, a TLS name (SNI) that the filter lets through to Cloudflare,
 * remembers it, uses it at once on the next connect there, and rotates to the next one when it gets blocked.
 *
 * Irancell (Build 180): TCP 443 to Cloudflare is open, but a ClientHello naming `*.cloudflareclient.com` is dropped
 * silently (a blocklist SNI filter); other names (`www.speedtest.net`, `x.cloudflare.com`, random names) get a
 * ServerHello. DNS is poisoned, so every probe dials Cloudflare addresses directly and never resolves a name.
 *
 * - Pool ([POOL]): names that worked on Irancell, popular Cloudflare-hosted names, and (for MASQUE only, see
 *   [com.example.vpn.connections.AetherSniProbe]) generated random names. Bundled in code; no list is fetched.
 * - Probe ([probe]): 3 at a time, 10 s in total ([PARALLEL], [BUDGET_MS]). A name passes only when the TLS handshake
 *   completes with a certificate the phone trusts for that name (system CAs + hostname check, never insecure) AND an
 *   HTTP request over it returns data (`GET /cdn-cgi/trace`, which every Cloudflare-proxied name answers). A
 *   ServerHello alone is "reached", not "passed".
 * - Memory: per network key ([BuiltinMethodMemory.network]: Irancell, MCI and each Wi-Fi apart), on this phone only.
 *   Nothing is ever shared between users or sent anywhere.
 * - Rotation ([rotate]): a name that gets blocked (a start or a session through it fails) goes on a [COOLDOWN_MS]
 *   cooldown on that network and the next ranked name is used.
 * - A manual name ([SmartSniSettings.manualSni]) always wins; with Smart SNI off and no manual name nothing changes.
 * - Logs carry names only, never addresses.
 */
object SmartSni {
    const val PARALLEL = 3
    const val BUDGET_MS = 10_000L
    const val COOLDOWN_MS = 6 * 60 * 60_000L
    /** How long a passing probe steers the next connect on a network. */
    const val MEMORY_MS = 6 * 60 * 60_000L
    const val MAX_REMEMBERED = 12

    /** Names that got a ServerHello on Irancell in Build 180. */
    val LEARNED = listOf("www.speedtest.net", "x.cloudflare.com")

    /**
     * Cloudflare-hosted names that a probe can check end to end (they answer `/cdn-cgi/trace`). Iran's filter is a
     * blocklist, so popular names that Iranian sites depend on are likely to pass; the probe decides, per network.
     * `cloudflare-ech.com` is Cloudflare's ECH outer name: probing it tells Smart ECH whether ECH can work here.
     */
    val POOL = LEARNED + listOf(
        "cdnjs.cloudflare.com", "challenges.cloudflare.com", "www.cloudflare.com", "developers.cloudflare.com",
        "speed.cloudflare.com", "blog.cloudflare.com", "workers.cloudflare.com", "cloudflare-ech.com"
    )

    /** A domain name: letters, digits, hyphens, dots, at least one letter; never an address or a wildcard. Pure. */
    fun validName(name: String): Boolean = name.length in 4..253 &&
        Regex("^(?=.*[a-z])([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$").matches(name)

    /** A name under .com that is no site: 12 random letters (MASQUE only; it cannot be checked end to end). */
    fun randomName(rng: java.util.Random = SecureRandom()): String =
        (1..12).map { 'a' + rng.nextInt(26) }.joinToString("") + ".com"

    enum class Verdict(val label: String, val rank: Int) {
        PASSED("TLS verified, data flowed", 0),
        REACHED("ServerHello, no data", 1),
        CERT("certificate not valid for the name (rejected)", 2),
        DROPPED("no ServerHello (dropped)", 3),
        RESET("connection reset", 4),
        TCP("TCP connect failed", 5),
        SKIPPED("not probed: time budget", 6)
    }

    data class Probe(val name: String, val verdict: Verdict, val ms: Long, val detail: String = "") {
        val passed: Boolean get() = verdict == Verdict.PASSED
    }

    /** One log line per probe: name, verdict, time (no address). Pure. */
    fun describe(p: Probe): String = "Smart SNI ${p.name} · ${p.verdict.label} · ${p.ms} ms" +
        p.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

    // ---- bounded parallel runner (also used by the clean-IP scanner) -------------------------------------------

    /**
     * Runs [task] for each item, at most [parallel] at a time, within [budgetMs] in total. [task] gets the time left
     * (ms) as its own timeout. An item not started, or not finished, in time gets [missed]. Results keep item order.
     */
    fun <T, R> bounded(items: List<T>, parallel: Int, budgetMs: Long, task: (T, Int) -> R, missed: (T, Boolean) -> R): List<R> {
        if (items.isEmpty()) return emptyList()
        val deadline = System.currentTimeMillis() + budgetMs
        val threads = ThreadFactory { r -> Thread(r, "smart-probe").apply { isDaemon = true } }
        val pool = Executors.newFixedThreadPool(parallel.coerceIn(1, 16).coerceAtMost(items.size), threads)
        return try {
            val futures = items.map { item ->
                item to pool.submit(Callable {
                    val left = deadline - System.currentTimeMillis()
                    if (left < MIN_TASK_MS) missed(item, false) else task(item, left.toInt())
                })
            }
            futures.map { (item, f) ->
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                runCatching { f.get(left, TimeUnit.MILLISECONDS) }.getOrElse { f.cancel(true); missed(item, true) }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private const val MIN_TASK_MS = 300L

    // ---- probe --------------------------------------------------------------------------------------------------

    /** Replaced in tests: one end-to-end check of [name] through Cloudflare edge [edge]. */
    @Volatile var check: (name: String, edge: String, timeoutMs: Int) -> Probe = ::liveCheck

    /**
     * Probes [names] (default: the pool) 3 at a time within [budgetMs], each through one of [edges] (Cloudflare
     * addresses; never resolved by DNS), records the results for [network] and returns them. Each result goes to [log].
     */
    fun probe(network: String?, names: List<String> = POOL, edges: List<String>, budgetMs: Long = BUDGET_MS,
              log: (String) -> Unit = {}): List<Probe> {
        val usable = edges.filter { it.isNotBlank() }
        if (usable.isEmpty()) return emptyList()
        val jobs = names.distinct().filter(::validName).mapIndexed { i, n -> n to usable[i % usable.size] }
        val started = System.currentTimeMillis()
        val results = bounded(jobs, PARALLEL, budgetMs,
            task = { (n, e), left -> runCatching { check(n, e, minOf(left, PROBE_TIMEOUT_MS)) }.getOrElse { Probe(n, Verdict.TCP, 0, it.javaClass.simpleName) } },
            missed = { (n, _), late -> Probe(n, if (late) Verdict.DROPPED else Verdict.SKIPPED, System.currentTimeMillis() - started,
                if (late) "no answer in ${budgetMs / 1000}s" else "") })
        results.forEach { log(describe(it)) }
        record(network, results)
        return results
    }

    private const val PROBE_TIMEOUT_MS = 5_000

    /** Ranks probes: passed first, then reached, then by time. Pure. */
    fun rank(probes: List<Probe>): List<Probe> = probes.sortedWith(compareBy<Probe>({ it.verdict.rank }, { it.ms }, { it.name }))

    /**
     * TCP to [edge]:443, TLS with SNI [name] verified by the system CAs and the hostname check, then
     * `GET /cdn-cgi/trace`: data must come back. Nothing about the user is sent.
     */
    fun liveCheck(name: String, edge: String, timeoutMs: Int): Probe {
        val started = System.currentTimeMillis()
        fun done(v: Verdict, detail: String = "") = Probe(name, v, System.currentTimeMillis() - started, detail)
        val raw = runCatching { com.example.panels.CleanIpOptimizer.directSocket() }.getOrElse { return done(Verdict.TCP, "socket protection failed") }
        return try {
            try { raw.connect(InetSocketAddress(edge, 443), minOf(timeoutMs, 4_000)) } catch (e: Exception) { return done(Verdict.TCP, e.javaClass.simpleName) }
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, name, 443, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(SNIHostName(name))
                endpointIdentificationAlgorithm = "HTTPS"
            }
            try {
                ssl.startHandshake()
            } catch (e: SSLHandshakeException) {
                val cert = generateSequence<Throwable>(e) { it.cause }.any { it is java.security.cert.CertificateException } ||
                    e.message.orEmpty().contains("certificate", ignoreCase = true)
                return done(if (cert) Verdict.CERT else Verdict.RESET, e.message.orEmpty().take(60))
            } catch (_: SocketTimeoutException) {
                return done(Verdict.DROPPED)
            } catch (e: java.net.SocketException) {
                return done(Verdict.RESET, e.message.orEmpty().take(60))
            }
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(name, ssl.session)) return done(Verdict.CERT)
            try {
                ssl.outputStream.apply {
                    write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $name\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val buf = ByteArray(2048)
                var n = 0
                while (n < 16) { val r = ssl.inputStream.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                val head = String(buf, 0, n, Charsets.US_ASCII)
                if (n >= 12 && head.startsWith("HTTP/1.")) done(Verdict.PASSED) else done(Verdict.REACHED, "no HTTP answer")
            } catch (_: Exception) {
                done(Verdict.REACHED, "data did not flow")
            } finally {
                runCatching { ssl.close() }
            }
        } finally {
            runCatching { raw.close() }
        }
    }

    // ---- per-network memory ---------------------------------------------------------------------------------------

    private fun key(network: String) = "smartSni#$network"

    private data class State(val ok: Map<String, Pair<Probe, Long>>, val cool: Map<String, Long>, val current: String?)

    private fun load(network: String): State {
        val root = runCatching { JSONObject(BuiltinMethodMemory.read(key(network)) ?: "{}") }.getOrElse { JSONObject() }
        val ok = LinkedHashMap<String, Pair<Probe, Long>>()
        root.optJSONObject("ok")?.let { o ->
            o.keys().forEach { n ->
                val v = o.optJSONObject(n) ?: return@forEach
                val verdict = runCatching { Verdict.valueOf(v.optString("v")) }.getOrNull() ?: return@forEach
                if (validName(n)) ok[n] = Probe(n, verdict, v.optLong("ms")) to v.optLong("at")
            }
        }
        val cool = LinkedHashMap<String, Long>()
        root.optJSONObject("cool")?.let { o -> o.keys().forEach { n -> cool[n] = o.optLong(n) } }
        return State(ok, cool, root.optString("cur").takeIf { validName(it) })
    }

    private fun save(network: String, s: State, now: Long) {
        val ok = JSONObject()
        s.ok.entries.filter { now - it.value.second in 0 until MEMORY_MS }
            .sortedBy { it.value.first.verdict.rank }.take(MAX_REMEMBERED)
            .forEach { (n, v) -> ok.put(n, JSONObject().put("v", v.first.verdict.name).put("ms", v.first.ms).put("at", v.second)) }
        val cool = JSONObject()
        s.cool.filter { it.value > now }.forEach { (n, until) -> cool.put(n, until) }
        val root = JSONObject().put("ok", ok).put("cool", cool)
        s.current?.let { root.put("cur", it) }
        runCatching { BuiltinMethodMemory.write(key(network), root.toString()) }
    }

    /** Keeps the names that passed or reached the edge on [network] (dropped ones are not remembered). */
    @Synchronized
    fun record(network: String?, probes: List<Probe>, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        val s = load(network)
        val ok = LinkedHashMap(s.ok)
        probes.forEach { p ->
            if (p.verdict == Verdict.PASSED || p.verdict == Verdict.REACHED) ok[p.name] = p to now
            else if (p.verdict != Verdict.SKIPPED) ok.remove(p.name)
        }
        save(network, s.copy(ok = ok), now)
    }

    /** Names on cooldown on [network] now. */
    @Synchronized
    fun cooling(network: String?, now: Long = System.currentTimeMillis()): Set<String> {
        if (network.isNullOrBlank()) return emptySet()
        return load(network).cool.filter { it.value > now }.keys
    }

    /** Remembered names for [network], best first, without those on cooldown. */
    @Synchronized
    fun ranked(network: String?, now: Long = System.currentTimeMillis(), passedOnly: Boolean = false): List<String> {
        if (network.isNullOrBlank()) return emptyList()
        val s = load(network)
        val fresh = s.ok.values.filter { now - it.second in 0 until MEMORY_MS }.map { it.first }
            .filter { s.cool[it.name]?.let { until -> until <= now } ?: true }
            .filter { !passedOnly || it.passed }
        return rank(fresh).map { it.name }
    }

    /** True when [name] passed end to end on [network] recently and is not on cooldown. */
    fun passedHere(network: String?, name: String, now: Long = System.currentTimeMillis()): Boolean =
        name in ranked(network, now, passedOnly = true)

    /**
     * The name to use on [network]: the manual one when set; with Smart SNI off, none; else the name in use (if not
     * cooling), else the best remembered one. Null: nothing known yet (probe first).
     */
    @Synchronized
    fun pick(network: String?, now: Long = System.currentTimeMillis()): String? {
        SmartSniSettings.manualSni.takeIf { it.isNotBlank() }?.let { return it }
        if (!SmartSniSettings.smartSni || network.isNullOrBlank()) return null
        val s = load(network)
        val ranked = ranked(network, now)
        return s.current?.takeIf { it in ranked } ?: ranked.firstOrNull()
    }

    /** [name] carried traffic on [network]: it becomes the one in use there. */
    @Synchronized
    fun recordSuccess(network: String?, name: String, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank() || !validName(name)) return
        val s = load(network)
        val ok = LinkedHashMap(s.ok)
        val prev = ok[name]?.first
        ok[name] = (prev?.takeIf { it.verdict == Verdict.PASSED } ?: Probe(name, Verdict.REACHED, prev?.ms ?: 0)) to now
        save(network, s.copy(ok = ok, cool = s.cool - name, current = name), now)
    }

    /**
     * [blocked] stopped working on [network] (a start or a running session through it failed): it goes on a
     * [COOLDOWN_MS] cooldown there and the next ranked name is returned (null when none is left: probe again).
     * A manual name is never rotated away; it is returned as is.
     */
    @Synchronized
    fun rotate(network: String?, blocked: String, now: Long = System.currentTimeMillis()): String? {
        SmartSniSettings.manualSni.takeIf { it.isNotBlank() }?.let { return it }
        if (network.isNullOrBlank()) return null
        val s = load(network)
        val next = ranked(network, now).firstOrNull { it != blocked }
        save(network, s.copy(cool = s.cool + (blocked to now + COOLDOWN_MS), current = next), now)
        return next
    }

    /** Forgets what was learned on [network]. */
    fun clear(network: String?) { if (!network.isNullOrBlank()) runCatching { BuiltinMethodMemory.write(key(network), null) } }

    /** What LAB shows: the name in use here and where it came from. Pure apart from memory reads. */
    fun status(network: String?): String {
        val manual = SmartSniSettings.manualSni
        if (manual.isNotBlank()) return "$manual (manual)"
        if (!SmartSniSettings.smartSni) return "off"
        return pick(network)?.let { name -> if (passedHere(network, name)) "$name (verified here)" else "$name (reached here)" }
            ?: "not probed on this network yet"
    }
}
