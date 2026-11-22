package com.example.vpn.cleanip

import com.example.data.model.VlessProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * The CDNs a fronted config (WebSocket, HTTPUpgrade, XHTTP, gRPC or H2 over TLS) can sit behind, with the
 * edge addresses this phone may dial instead of the config's own address. The CDN routes by the TLS name and
 * Host, which stay the config's own, so any reachable edge of the same CDN reaches the same origin.
 *
 * Lists are bundled snapshots taken 2026-11-22: Cloudflare www.cloudflare.com/ips-v4, Fastly
 * api.fastly.com/public-ip-list, CloudFront's viewer-facing CLOUDFRONT_GLOBAL_IP_LIST (origin-facing and
 * China-only blocks left out), and one listed edge from each of 42 of the busiest Gcore-operated /24s of
 * api.gcore.com/cdn/public-ip-list (Gcore publishes single edge addresses, so those stay /32; partner PoPs
 * inside other operators' networks are left out).
 */
enum class Cdn(val label: String, val ranges: List<String>, val speedHost: String?) {
    CLOUDFLARE("Cloudflare", com.example.panels.CleanIpOptimizer.CLOUDFLARE_V4_RANGES, "speed.cloudflare.com"),
    FASTLY("Fastly", listOf(
        "23.235.32.0/20", "43.249.72.0/22", "103.244.50.0/24", "103.245.222.0/23", "103.245.224.0/24",
        "104.156.80.0/20", "140.248.64.0/18", "140.248.128.0/17", "146.75.0.0/17", "151.101.0.0/16",
        "157.52.64.0/18", "167.82.0.0/17", "167.82.128.0/20", "167.82.160.0/20", "167.82.224.0/20",
        "172.111.64.0/18", "185.31.16.0/22", "199.27.72.0/21", "199.232.0.0/16"
    ), null),
    GCORE("Gcore", listOf(
        "5.101.222.10/32", "5.188.126.10/32", "5.188.133.10/32", "31.184.207.10/32", "37.9.33.122/32", "45.82.101.10/32",
        "79.133.108.13/32", "80.93.215.10/32", "80.240.113.4/32", "80.240.124.10/32", "82.97.205.10/32", "82.158.113.10/32",
        "85.234.68.10/32", "85.234.75.10/32", "85.234.77.10/32", "85.234.95.4/32", "87.120.252.166/32", "92.38.159.10/32",
        "92.223.12.10/32", "92.223.40.10/32", "92.223.63.10/32", "92.223.76.132/32", "92.223.78.22/32", "92.223.107.132/32",
        "92.223.116.200/32", "92.223.118.10/32", "92.223.120.132/32", "92.223.124.10/32", "93.114.56.123/32", "93.123.11.10/32",
        "93.123.17.132/32", "93.174.165.10/32", "94.176.183.10/32", "95.85.93.155/32", "128.75.231.2/32", "185.101.136.10/32",
        "185.244.209.10/32", "193.36.180.13/32", "193.169.251.13/32", "194.158.198.1/32", "202.78.171.10/32", "217.195.193.10/32"
    ), null),
    CLOUDFRONT("CloudFront", listOf(
        "3.160.0.0/14", "3.164.0.0/18", "3.164.64.0/18", "3.164.128.0/17", "3.165.0.0/16", "3.166.0.0/15",
        "3.168.0.0/14", "3.172.0.0/18", "3.172.64.0/18", "3.173.0.0/17", "3.173.128.0/18", "3.173.192.0/18",
        "3.174.0.0/15", "13.32.0.0/15", "13.35.0.0/16", "13.224.0.0/14", "13.249.0.0/16", "18.64.0.0/14",
        "18.68.0.0/16", "18.154.0.0/15", "18.160.0.0/15", "18.164.0.0/15", "18.172.0.0/15", "18.238.0.0/15",
        "18.244.0.0/15", "52.84.0.0/15", "52.222.128.0/17", "54.182.0.0/16", "54.192.0.0/16", "54.230.0.0/17",
        "54.230.128.0/18", "65.8.0.0/16", "65.9.0.0/17", "65.9.128.0/18", "99.84.0.0/16", "99.86.0.0/16",
        "108.138.0.0/15", "108.156.0.0/14", "143.204.0.0/16"
    ), null);
}

/**
 * Finds clean CDN edges for one config on one network: samples addresses across its CDN's ranges, then gives
 * each a TCP connect and a TLS handshake carrying the config's own server name (bounded concurrency, short
 * timeouts), ranks them by handshake success and latency, and optionally downloads a small sample from the
 * best few. A handshake whose certificate does not match the name never counts as clean: that is an
 * interception, not an edge.
 *
 * The result is a hint, not a verdict: config revival still sends a real request through the config with the
 * address swapped (SNI, Host, path and credentials unchanged) before anything is used.
 */
class CdnEdgeScanner(
    private val handshake: (ip: String, port: Int, sni: String, timeoutMs: Int) -> Handshake =
        { ip, port, sni, timeoutMs -> liveHandshake(ip, port, sni, timeoutMs) },
    /** KB/s of a small download from [ip] with [host] as the TLS name, or null when it failed. */
    private val download: (ip: String, host: String, bytes: Int, timeoutMs: Int) -> Long? =
        { ip, host, bytes, timeoutMs -> liveDownload(ip, host, bytes, timeoutMs) },
    private val random: Random = Random.Default,
    val concurrency: Int = 16,
    val timeoutMs: Int = 1_800
) {
    /** One TCP + TLS attempt. [tcpMs] null: TCP failed; [tlsMs] null: TLS failed or was not tried. */
    data class Handshake(
        val tcpMs: Long?,
        val tlsMs: Long?,
        /** False when TLS reached a certificate that does not verify for the name; null when unknown. */
        val certMatches: Boolean? = null
    ) {
        val ok: Boolean get() = tcpMs != null && tlsMs != null && certMatches != false
        val certMismatch: Boolean get() = tcpMs != null && certMatches == false
    }

    /** An address's measurements, best first by [score]. */
    data class Edge(
        val ip: String,
        val cdn: Cdn,
        val attempts: List<Handshake>,
        val downloadKbps: Long? = null
    ) {
        val successes: Int get() = attempts.count { it.ok }
        val certMismatch: Boolean get() = attempts.any { it.certMismatch }
        /** Median TCP connect + TLS handshake time over the successful attempts. */
        val medianMs: Long? get() = attempts.filter { it.ok }.map { it.tcpMs!! + it.tlsMs!! }.sorted()
            .let { if (it.isEmpty()) null else it[it.size / 2] }

        /** Lower is better: latency, plus a heavy cost per failed attempt, plus a throughput cost once measured. */
        val score: Double get() {
            val ms = medianMs ?: return Double.MAX_VALUE
            val failures = attempts.size - successes
            val speedCost = when {
                downloadKbps == null -> 0.0
                downloadKbps <= 0L -> SPEED_FAILED_COST
                else -> SPEED_COST_FACTOR / downloadKbps
            }
            return ms + failures * FAILED_ATTEMPT_COST + speedCost
        }
    }

    /**
     * Scans [cdn] for edges that complete TLS for [sni] on [port]. [preferred] (addresses that worked here
     * before) are tested first; [samples] fresh addresses follow. Stops sampling once [enough] edges passed.
     * The top [speedTop] get a small download when [cdn] has a speed host.
     */
    suspend fun scan(
        cdn: Cdn,
        sni: String,
        port: Int = 443,
        preferred: List<String> = emptyList(),
        samples: Int = 48,
        tries: Int = 2,
        enough: Int = 8,
        speedTop: Int = 0,
        speedBytes: Int = 64_000
    ): List<Edge> = withContext(Dispatchers.IO) {
        val candidates = (preferred.filter { CdnRanges.ipv4(it) != null } + CdnRanges.sample(cdn, samples, random)).distinct()
        val gate = Semaphore(concurrency.coerceIn(1, 64))
        val passed = java.util.concurrent.atomic.AtomicInteger()
        val edges = coroutineScope {
            candidates.map { ip ->
                async {
                    gate.withPermit {
                        if (passed.get() >= enough) return@withPermit null
                        val attempts = mutableListOf<Handshake>()
                        for (i in 0 until tries.coerceAtLeast(1)) {
                            val h = runCatching { handshake(ip, port, sni, timeoutMs) }.getOrElse { Handshake(null, null) }
                            attempts += h
                            // A dead or intercepted address needs no second try.
                            if (!h.ok) break
                        }
                        Edge(ip, cdn, attempts).also { if (it.successes > 0 && !it.certMismatch) passed.incrementAndGet() }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val ranked = rank(edges)
        val host = cdn.speedHost
        if (speedTop <= 0 || host == null) return@withContext ranked
        val tested = ranked.take(speedTop).map { e ->
            e.copy(downloadKbps = runCatching { download(e.ip, host, speedBytes, timeoutMs * 3) }.getOrNull() ?: 0L)
        }
        rank(tested + ranked.drop(speedTop))
    }

    companion object {
        const val FAILED_ATTEMPT_COST = 600.0
        const val SPEED_COST_FACTOR = 30_000.0
        const val SPEED_FAILED_COST = 1_000.0

        /** Edges that completed at least one clean handshake, best first; ties keep the address order stable. */
        fun rank(edges: List<Edge>): List<Edge> = edges
            .filter { it.successes > 0 && !it.certMismatch }
            .distinctBy { it.ip }
            .sortedWith(compareBy<Edge>({ it.score }, { it.ip }))

        /** The CDN behind a fronted config's address, from its literal IP or the [resolve]d addresses of its name. */
        fun cdnFor(profile: VlessProfile, resolve: (String) -> List<String> = { emptyList() }): Cdn? {
            val address = profile.address.trim().removePrefix("[").removeSuffix("]")
            val ips = if (CdnRanges.ipv4(address) != null) listOf(address) else runCatching { resolve(address) }.getOrDefault(emptyList())
            return ips.firstNotNullOfOrNull { CdnRanges.cdnOf(it) }
        }

        /** The TLS name the config sends (its SNI, else its Host, else its domain address). */
        fun tlsName(profile: VlessProfile): String? = profile.sni.ifBlank { profile.host }
            .ifBlank { profile.address.takeUnless { CdnRanges.ipv4(it) != null || it.contains(':') }.orEmpty() }
            .trim().ifBlank { null }

        /** Whether a scanned edge can stand in for this config's address: TLS kept, certificate checked, a CDN transport. */
        fun fronted(profile: VlessProfile): Boolean =
            profile.security.equals("tls", true) && !profile.allowInsecure && profile.rawConfig.isBlank() &&
                profile.transport.lowercase() in setOf("ws", "httpupgrade", "xhttp", "splithttp", "grpc", "h2") &&
                tlsName(profile) != null

        /** One TCP connect and TLS handshake to [ip] with [sni], timed separately, on the phone's network. */
        fun liveHandshake(ip: String, port: Int, sni: String, timeoutMs: Int): Handshake {
            val raw: Socket = runCatching { com.example.panels.CleanIpOptimizer.directSocket() }.getOrElse { return Handshake(null, null) }
            raw.use { socket ->
                val t0 = System.nanoTime()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(ip, port), timeoutMs)
                } catch (_: Exception) { return Handshake(null, null) }
                val tcp = ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
                val t1 = System.nanoTime()
                return try {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, sni, port, true) as SSLSocket
                    ssl.soTimeout = timeoutMs
                    ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                    ssl.startHandshake()
                    val tls = ((System.nanoTime() - t1) / 1_000_000).coerceAtLeast(1)
                    val matches = HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.session)
                    runCatching { ssl.close() }
                    Handshake(tcp, tls, matches)
                } catch (e: SSLHandshakeException) {
                    // A chain the phone does not trust for this name is an interception, not a dead edge.
                    val cert = generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException } ||
                        e.message.orEmpty().contains("certificate", ignoreCase = true)
                    Handshake(tcp, null, if (cert) false else null)
                } catch (_: Exception) {
                    Handshake(tcp, null)
                }
            }
        }

        /** KB/s of a [bytes] download from [host]'s `/__down` (Cloudflare's speed test) through edge [ip]. */
        fun liveDownload(ip: String, host: String, bytes: Int, timeoutMs: Int): Long? = runCatching {
            com.example.panels.CleanIpOptimizer.directSocket().use { socket ->
                socket.connect(InetSocketAddress(ip, 443), timeoutMs)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, 443, true) as SSLSocket
                ssl.soTimeout = timeoutMs
                ssl.sslParameters = ssl.sslParameters.apply {
                    serverNames = listOf(SNIHostName(host)); endpointIdentificationAlgorithm = "HTTPS"
                }
                ssl.startHandshake()
                val start = System.nanoTime()
                ssl.outputStream.apply {
                    write("GET /__down?bytes=$bytes HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val input = ssl.inputStream
                if (!com.example.panels.CleanIpOptimizer.skipHeaders(input)) return@use null
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (total < bytes) { val n = input.read(buf); if (n < 0) break; total += n }
                val ms = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
                if (total < bytes / 2) null else total * 1000 / 1024 / ms
            }
        }.getOrNull()
    }
}

/** IPv4 range arithmetic and sampling over the bundled [Cdn] lists. */
object CdnRanges {
    /** The address as an unsigned 32-bit number, or null when it is not a dotted IPv4 address. */
    fun ipv4(value: String): Long? {
        val parts = value.trim().split('.')
        if (parts.size != 4) return null
        var n = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null
            val v = p.toInt()
            if (v > 255) return null
            n = (n shl 8) or v.toLong()
        }
        return n
    }

    fun format(n: Long): String = "${(n shr 24) and 0xff}.${(n shr 16) and 0xff}.${(n shr 8) and 0xff}.${n and 0xff}"

    /** Network number and prefix length of [cidr], or null when malformed. */
    fun parse(cidr: String): Pair<Long, Int>? {
        val (base, bits) = cidr.split('/').takeIf { it.size == 2 }?.let { it[0] to it[1].toIntOrNull() } ?: return null
        if (bits == null || bits !in 1..32) return null
        val n = ipv4(base) ?: return null
        val mask = (0xffffffffL shl (32 - bits)) and 0xffffffffL
        return (n and mask) to bits
    }

    fun contains(cidr: String, ip: String): Boolean {
        val (net, bits) = parse(cidr) ?: return false
        val n = ipv4(ip) ?: return false
        val mask = (0xffffffffL shl (32 - bits)) and 0xffffffffL
        return (n and mask) == net
    }

    /** The CDN whose bundled list holds [ip], or null. */
    fun cdnOf(ip: String): Cdn? = Cdn.entries.firstOrNull { cdn -> cdn.ranges.any { contains(it, ip) } }

    /** The range of [cdn] that holds [ip] (used to tell ranges apart), or null. */
    fun rangeOf(cdn: Cdn, ip: String): String? = cdn.ranges.firstOrNull { contains(it, ip) }

    /** A usable host inside [cidr]: network and broadcast addresses skipped, a /32 is itself. */
    fun hostIn(cidr: String, random: Random): String? {
        val (net, bits) = parse(cidr) ?: return null
        val size = 1L shl (32 - bits)
        val offset = if (size <= 2) 0L else random.nextLong(1, size - 1)
        return format(net or offset)
    }

    /**
     * [count] distinct addresses spread over [cdn]'s ranges: one per range in a shuffled order, round after
     * round, so a small sample still touches many ranges (and many /32 edges for Gcore).
     */
    fun sample(cdn: Cdn, count: Int, random: Random = Random.Default): List<String> {
        if (count <= 0) return emptyList()
        val ranges = cdn.ranges.shuffled(random)
        val out = LinkedHashSet<String>()
        var rounds = 0
        while (out.size < count && rounds < 8) {
            val before = out.size
            for (r in ranges) {
                if (out.size >= count) break
                hostIn(r, random)?.let(out::add)
            }
            rounds++
            if (out.size == before) break
        }
        return out.toList()
    }
}

/**
 * The clean-IP path for one fronted config on one network, as config revival uses it: fresh cached edges
 * first; on Cloudflare, nothing when the network proxies Cloudflare TLS by name ([SniProxyDetector], checked
 * once per network and remembered); otherwise a handshake scan of the config's CDN, cached for the network.
 */
class CdnCleanIpFinder(
    private val cache: CleanIpCache,
    private val scanner: CdnEdgeScanner = CdnEdgeScanner(),
    private val detect: (expectedCountry: String?) -> SniProxyDetector.Verdict = { SniProxyDetector.judge(SniProxyDetector.probe(it)) },
    /** IPv4 addresses of a config's domain address, looked up on the phone's own network. */
    private val resolve: (String) -> List<String> = { emptyList() }
) {
    data class Found(val cdn: Cdn?, val ips: List<String>, val skipped: String? = null, val fromCache: Boolean = false)

    /** The CDN a config is fronted by; an address no bundled list holds is taken as Cloudflare, the usual front. */
    fun cdnOf(profile: VlessProfile): Cdn = CdnEdgeScanner.cdnFor(profile, resolve) ?: Cdn.CLOUDFLARE

    /** Whether [network] proxies Cloudflare TLS by name: the remembered verdict, or a fresh check. */
    fun cloudflareProxied(network: String, expectedCountry: String?): Boolean =
        cache.sniProxied(network) ?: runCatching { detect(expectedCountry) }.getOrNull()
            ?.also { cache.record(network, it) }?.let { it.conclusive && it.proxied } ?: false

    suspend fun find(
        profile: VlessProfile,
        network: String,
        expectedCountry: String?,
        preferred: List<String> = emptyList(),
        speedTop: Int = 2
    ): Found {
        if (!CdnEdgeScanner.fronted(profile)) return Found(null, emptyList(), "not a CDN-fronted TLS config")
        val sni = CdnEdgeScanner.tlsName(profile)!!
        val cdn = cdnOf(profile)
        cache.best(network, cdn, sni).takeIf { it.isNotEmpty() }?.let { return Found(cdn, it, fromCache = true) }
        if (cdn == Cdn.CLOUDFLARE && cloudflareProxied(network, expectedCountry)) {
            return Found(cdn, emptyList(), "this network proxies Cloudflare TLS by name; a clean Cloudflare IP cannot help here")
        }
        val port = profile.port.takeIf { it in 1..65535 } ?: 443
        val ips = scanner.scan(cdn, sni, port, preferred, speedTop = speedTop).take(CleanIpCache.MAX_IPS).map { it.ip }
        cache.put(network, cdn, sni, ips)
        return Found(cdn, ips)
    }
}
