package com.example.panels

import com.example.data.model.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * CFScanner-grade Clean IP Optimizer inspired by Morteza Bashsiz's CFScanner
 * and Cloudflare Anycast edge routing research.
 *
 * CRITICAL ARCHITECTURAL PRINCIPLE:
 * Clean Cloudflare Anycast IPs only bypass local ISP/GFW censorship by rerouting
 * entry traffic to an unblocked Cloudflare edge. THEY CANNOT RESURRECT OR REVIVE
 * EXPIRED, DELETED, OR OFFLINE ORIGIN SERVERS. If the backend VPS, Cloudflare Worker,
 * or subscription is dead (HTTP 521, 522, 1000, or invalid UUID), no clean IP
 * can make it work.
 *
 * This optimizer performs real multi-stage verification:
 * 1. Origin Health Pre-Check: Diagnoses whether the origin backend is actually online.
 * 2. High-concurrency TCP & Jitter measurement across ISP-targeted Anycast subnets.
 * 3. End-to-end TLS SNI & RFC 6455 WebSocket Upgrade validation against the real target domain.
 * 4. Active CDN verification & real download throughput / speed testing (like CFScanner).
 */
class CleanIpOptimizer(private val openSocket: () -> Socket = { Socket() }) {

    data class Result(
        val ip: String,
        val medianMs: Long,
        val jitterMs: Long,
        val successes: Int,
        val totalTries: Int = 3,
        val country: String = "CF",
        val flagEmoji: String = "🌐",
        val operatorTag: String = "Cloudflare CDN",
        val downloadSpeedKbps: Long = 0L,
        val verificationStatus: String = "TCP only; traffic not verified",
        val originVerified: Boolean = false,
        val tlsHandshakeMs: Long? = null,
        val httpRequestMs: Long? = null
    ) {
        val lossPercent: Double
            get() = (((totalTries - successes).toDouble() / totalTries.toDouble()) * 100.0).coerceIn(0.0, 100.0)

        val formattedSpeed: String
            get() = when {
                downloadSpeedKbps >= 1024 -> String.format(Locale.US, "%.1f MB/s", downloadSpeedKbps / 1024.0)
                downloadSpeedKbps > 0 -> "$downloadSpeedKbps KB/s"
                else -> "--"
            }

        val qualityGrade: String
            get() = when {
                medianMs <= 110 && lossPercent == 0.0 && jitterMs <= 15 -> "A+"
                medianMs <= 165 && lossPercent == 0.0 -> "A"
                medianMs <= 240 && lossPercent <= 33.3 -> "B"
                else -> "C"
            }

        // Composite rank score: lowest is best. High download speed lowers score (improves rank).
        val score: Double
            get() {
                val speedBonus = (downloadSpeedKbps / 100.0).coerceAtMost(50.0)
                return (medianMs + (jitterMs * 1.5) + (lossPercent * 25.0) - speedBonus).coerceAtLeast(1.0)
            }
    }

    data class ScanProgress(
        val tested: Int,
        val reachable: Int,
        val currentIp: String,
        val totalCandidates: Int,
        val stage: String = "Probing"
    )

    sealed class OriginHealth {
        data class Online(
            val message: String,
            val httpCode: Int = 101,
            val latencyMs: Long = 0L,
            val isWebSocket: Boolean = true
        ) : OriginHealth()

        data class Down(
            val reason: String,
            val httpCode: Int? = null,
            val details: String = "Clean IPs only bypass ISP firewalls for active origin servers. They cannot resurrect an offline or deleted server."
        ) : OriginHealth()

        data class Incompatible(
            val reason: String,
            val details: String = "This protocol connects directly to VPS IP addresses or does not use Cloudflare CDN."
        ) : OriginHealth()

        object Unknown : OriginHealth()
    }

    companion object {
        /**
         * Cloudflare's published IPv4 ranges (the list at www.cloudflare.com/ips-v4). Cloudflare is an
         * Anycast network: which edge any one of these addresses reaches is decided by how the phone's
         * network peers with Cloudflare, not by the address, so a "clean" IP cannot be picked by country
         * or ISP in advance. The scanner therefore samples real addresses across every published range and
         * ranks them by what it actually measures from this phone (latency, loss, throughput), rather than
         * labelling them with a country or operator it cannot verify.
         */
        val CLOUDFLARE_V4_RANGES = listOf(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22"
        )

        /** The scan scopes the UI offers: all of Cloudflare's ranges, or the user's own CIDR list. */
        const val SCOPE_ALL = "All Cloudflare ranges"
        const val SCOPE_CUSTOM = "Custom CIDR / Subnet"

        /** A random usable host inside [cidr] (network and broadcast addresses skipped). */
        fun randomHostIn(cidr: String): String {
            val parts = cidr.split('/')
            val bits = parts[1].toInt().also { require(it in 1..32) }
            val baseNum = ipv4(parts[0])
            val size = 1L shl (32 - bits)
            val network = baseNum and ((0xffffffffL shl (32 - bits)) and 0xffffffffL)
            val offset = if (size <= 2) 0L else Random.nextLong(1, size - 1)
            val n = network or offset
            return "${(n shr 24) and 0xff}.${(n shr 16) and 0xff}.${(n shr 8) and 0xff}.${n and 0xff}"
        }

        fun eligible(p: VlessProfile): Boolean = p.profileType == ProfileType.VLESS &&
            p.protocolType == ProtocolType.VLESS && p.transport.equals("ws", true) &&
            p.security.equals("tls", true) && p.sni.isNotBlank() &&
            p.rawConfig.isBlank()

        fun isCloudflareCompatible(p: VlessProfile): Boolean {
            if (p.security.equals("reality", ignoreCase = true)) return false
            val tr = p.transport.lowercase()
            if (tr != "ws" && tr != "httpupgrade" && tr != "grpc") return false
            val host = p.host.ifBlank { p.sni.ifBlank { p.address } }
            if (host.isBlank() || (host.matches(Regex("^[0-9.]+$")) && p.sni.isBlank())) return false
            return true
        }

        fun ranked(results: List<Result>, limit: Int = 15): List<Result> = results
            .filter { it.successes >= 2 }
            .distinctBy { it.ip }
            .sortedBy { it.score }
            .take(limit)

        fun ipv4(value: String): Long {
            val parts = value.split('.')
            require(parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 })
            return parts.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        }

        fun inCidr(ip: String, cidr: String): Boolean {
            val (base, bits) = cidr.split('/')
            val prefix = bits.toInt().also { require(it in 1..32) }
            val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            return (ipv4(ip) and mask) == (ipv4(base) and mask)
        }

        val CLOUDFLARE_PORTS_TLS = setOf(443, 8443, 2053, 2083, 2087, 2096)
        val CLOUDFLARE_PORTS_HTTP = setOf(80, 8080, 8880, 2052, 2082, 2086, 2095)
        val CLOUDFLARE_ALL_PORTS = CLOUDFLARE_PORTS_TLS + CLOUDFLARE_PORTS_HTTP

        /**
         * Re-binds a connection endpoint to a high-speed Clean IP while strictly preserving
         * TLS SNI, HTTP Host, UUID, Path, and protocol parameters.
         */
        fun reviveProfile(
            original: VlessProfile,
            cleanIp: Result,
            asClone: Boolean = false
        ): VlessProfile {
            if (original.security.equals("reality", ignoreCase = true)) {
                throw IllegalArgumentException("Reality configurations cannot use Cloudflare Clean IPs. Reality endpoints require direct VPS connection. Use DPI Desync or God Mode instead.")
            }

            val origAddress = original.address
            val effectiveSni = original.sni.ifBlank {
                if (!origAddress.matches(Regex("^[0-9.]+$")) && !origAddress.contains(":")) origAddress else "cloudflare.com"
            }
            val effectiveHost = original.host.ifBlank {
                if (!origAddress.matches(Regex("^[0-9.]+$")) && !origAddress.contains(":")) origAddress else effectiveSni
            }

            val effectivePort = if (original.port in CLOUDFLARE_ALL_PORTS) {
                original.port
            } else if (original.security.equals("tls", ignoreCase = true) || effectiveSni.isNotBlank() || original.transport.equals("ws", ignoreCase = true)) {
                443
            } else {
                80
            }

            val effectiveTransport = if (original.transport.isBlank() || original.transport.equals("tcp", ignoreCase = true)) {
                "ws"
            } else {
                original.transport
            }

            val effectiveSecurity = if (original.security.isBlank() || original.security.equals("none", ignoreCase = true)) {
                if (effectivePort in CLOUDFLARE_PORTS_TLS || effectivePort == 443) "tls" else "none"
            } else {
                original.security
            }

            val baseName = original.name
                .removePrefix("⚡ [Revived] ")
                .removePrefix("⚡ [CF-Optimized] ")
            val newName = if (asClone) {
                "⚡ [CF-Optimized] $baseName (${cleanIp.qualityGrade})"
            } else {
                original.name
            }

            return original.copy(
                id = if (asClone) UUID.randomUUID().toString() else original.id,
                canonicalFingerprint = "",
                name = newName,
                address = cleanIp.ip,
                port = effectivePort,
                transport = effectiveTransport,
                security = effectiveSecurity,
                sni = effectiveSni,
                host = effectiveHost,
                lastLatencyMs = cleanIp.medianMs,
                jitterMs = cleanIp.jitterMs,
                packetLoss = cleanIp.lossPercent,
                countryCode = cleanIp.country,
                lastTestedTimestamp = System.currentTimeMillis()
            )
        }

        fun variant(p: VlessProfile, r: Result): VlessProfile = reviveProfile(p, r, asClone = true)

        /**
         * Three TCP pings per address, so the latency, jitter and loss are measured rather than
         * assumed; the lowest median among the addresses that answered at least twice wins.
         */
        suspend fun findBestCleanIpSync(region: String = "Iran"): Result? = findCleanIpsSync(region).firstOrNull()

        suspend fun findCleanIpsSync(region: String = "Iran", port: Int = 443, openSocket: () -> Socket = { Socket() }): List<Result> = withContext(Dispatchers.IO) {
            require(port in 1..65535)
            val candidates = generateCandidates(region, 24)
            val results = mutableListOf<Result>()
            val optimizer = CleanIpOptimizer(openSocket)
            for (batch in candidates.chunked(8)) {
                val batchRes = coroutineScope {
                    batch.map { (ip, country, meta) ->
                        async(Dispatchers.IO) {
                            val tries = 3
                            val latencies = (1..tries).mapNotNull { optimizer.tcpPing(ip, port, 1000) }.sorted()
                            if (latencies.size < 2) return@async null
                            Result(
                                ip = ip,
                                medianMs = latencies[latencies.size / 2],
                                jitterMs = latencies.last() - latencies.first(),
                                successes = latencies.size,
                                totalTries = tries,
                                country = country,
                                flagEmoji = meta.first,
                                operatorTag = meta.second
                            )
                        }
                    }.awaitAll().filterNotNull()
                }
                results.addAll(batchRes)
                if (results.size >= 3) break
            }
            ranked(results, limit = 8)
        }

        fun parseCustomSubnet(input: String, count: Int = 30): List<Triple<String, String, Pair<String, String>>> {
            val items = input.split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
            val results = mutableListOf<Triple<String, String, Pair<String, String>>>()
            for (item in items) {
                runCatching {
                    if (item.contains('/')) {
                        val parts = item.split('/')
                        val baseIp = parts.getOrNull(0) ?: return@runCatching
                        val mask = parts.getOrNull(1)?.toIntOrNull() ?: 24
                        if (mask in 8..30) {
                            val ipNum = ipv4(baseIp)
                            val totalHosts = (1L shl (32 - mask))
                            val samples = (count / items.size.coerceAtLeast(1)).coerceIn(4, 30)
                            repeat(samples) {
                                val offset = Random.nextLong(1, totalHosts.coerceAtMost(255L))
                                val sampledNum = (ipNum and ((0xffffffffL shl (32 - mask)) and 0xffffffffL)) or offset
                                val ipStr = "${(sampledNum shr 24) and 0xff}.${(sampledNum shr 16) and 0xff}.${(sampledNum shr 8) and 0xff}.${sampledNum and 0xff}"
                                results.add(Triple(ipStr, "CUSTOM", Pair("🎯", "Custom CIDR ($item)")))
                            }
                        }
                    } else if (item.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$"))) {
                        repeat(10) {
                            val last = Random.nextInt(2, 254)
                            results.add(Triple("$item.$last", "CUSTOM", Pair("🎯", "Custom Subnet ($item)")))
                        }
                    } else if (item.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$"))) {
                        results.add(Triple(item, "CUSTOM", Pair("🎯", "Custom IP")))
                    }
                }
            }
            return results.distinctBy { it.first }.shuffled().take(count)
        }

        fun generateCandidates(
            regionQuery: String = SCOPE_ALL,
            count: Int = 60,
            customSubnet: String? = null
        ): List<Triple<String, String, Pair<String, String>>> {
            if (!customSubnet.isNullOrBlank()) {
                val parsed = parseCustomSubnet(customSubnet, count)
                if (parsed.isNotEmpty()) return parsed
            }

            // Sample real addresses across Cloudflare's published ranges, weighted by range size so
            // every address is about equally likely, then rank by what the probes measure from here.
            val weighted = CLOUDFLARE_V4_RANGES.map { it to (1L shl (32 - it.substringAfter('/').toInt())) }
            val totalWeight = weighted.sumOf { it.second }
            val candidates = LinkedHashSet<String>()
            var guard = 0
            val target = count.coerceAtLeast(1)
            while (candidates.size < target && guard < target * 40) {
                guard++
                val roll = Random.nextLong(totalWeight)
                var acc = 0L
                var chosen = weighted.first().first
                for ((cidr, weight) in weighted) {
                    acc += weight
                    if (roll < acc) { chosen = cidr; break }
                }
                candidates.add(randomHostIn(chosen))
            }
            return candidates.map { Triple(it, "CF", Pair("🌐", "Cloudflare edge")) }
        }
    }

    // Use the platform trust store; accepting every certificate would make probe results
    // vulnerable to on-path TLS interception and would not verify the requested SNI.
    private val probeSslSocketFactory: SSLSocketFactory by lazy {
        SSLSocketFactory.getDefault() as SSLSocketFactory
    }

    /**
     * Checks if the configuration's origin server is actually alive.
     * Prevents users from falsely expecting clean IPs to resurrect dead servers.
     */
    suspend fun checkOriginHealth(profile: VlessProfile): OriginHealth = withContext(Dispatchers.IO) {
        if (profile.security.equals("reality", ignoreCase = true)) {
            return@withContext OriginHealth.Incompatible(
                "VLESS-Reality uses direct VPS handshakes with camouflaged SNI. Cloudflare CDN Anycast IPs cannot route Reality packets."
            )
        }

        val transport = profile.transport.lowercase()
        if (transport != "ws" && transport != "httpupgrade" && transport != "grpc") {
            return@withContext OriginHealth.Incompatible(
                "Transport '$transport' cannot route through Cloudflare CDN. Only WebSocket (ws), HTTPUpgrade, or gRPC behind Cloudflare support Clean IPs."
            )
        }

        val targetHost = profile.host.ifBlank { profile.sni.ifBlank { profile.address } }
        if (targetHost.isBlank() || (targetHost.matches(Regex("^[0-9.]+$")) && profile.sni.isBlank())) {
            return@withContext OriginHealth.Incompatible(
                "Missing domain SNI or Host header. Cloudflare Anycast CDN requires a registered domain hostname to route requests to the backend."
            )
        }

        val path = profile.path.ifBlank { "/" }
            .replace("\r", "").replace("\n", "")
            .let { if (it.startsWith('/')) it else "/$it" }
        val isTls = profile.security.equals("tls", true) || profile.port in CLOUDFLARE_PORTS_TLS || profile.port == 443
        val port = if (profile.port in CLOUDFLARE_ALL_PORTS) profile.port else (if (isTls) 443 else 80)

        val start = System.currentTimeMillis()
        try {
            // DNS resolution check
            val resolved = try {
                InetAddress.getAllByName(targetHost)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext OriginHealth.Down(
                    "Domain '$targetHost' cannot be resolved (DNS NXDOMAIN). Domain expired or DNS records were removed."
                )
            }

            val ipToProbe = resolved.firstOrNull()?.hostAddress ?: targetHost
            openSocket().use { rawSocket ->
                rawSocket.tcpNoDelay = true
                rawSocket.soTimeout = 4000
                rawSocket.connect(InetSocketAddress(ipToProbe, port), 4000)

                val probeSocket: Socket = if (isTls) {
                    val ssl = probeSslSocketFactory.createSocket(rawSocket, targetHost, port, true) as SSLSocket
                    ssl.soTimeout = 4000
                    val sslParams = ssl.sslParameters ?: SSLParameters()
                    sslParams.endpointIdentificationAlgorithm = "HTTPS"
                    runCatching { sslParams.serverNames = listOf(SNIHostName(targetHost)) }
                    ssl.sslParameters = sslParams
                    ssl.startHandshake()
                    ssl
                } else {
                    rawSocket
                }

                val key = Base64.getEncoder().encodeToString(ByteArray(16).apply { SecureRandom().nextBytes(this) })
                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $targetHost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "User-Agent: CFScanner/1.0\r\n\r\n"

                probeSocket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.UTF_8))
                    flush()
                }

                val statusLine = readLine(probeSocket.getInputStream())
                val code = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: .*)?$").matchEntire(statusLine)?.groupValues?.get(1)?.toIntOrNull()
                val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)

                when (code) {
                    101 -> OriginHealth.Online("Origin server is Online and healthy (WebSocket Upgrade 101 OK).", httpCode = 101, latencyMs = latency, isWebSocket = true)
                    404 -> OriginHealth.Online("Origin server responded (HTTP 404). Backend service is online, verify WebSocket path '$path'.", httpCode = 404, latencyMs = latency, isWebSocket = false)
                    in 200..399 -> OriginHealth.Online("Origin server is reachable (HTTP $code).", httpCode = code ?: 200, latencyMs = latency, isWebSocket = false)
                    521 -> OriginHealth.Down("Cloudflare Error 521: Web server is down. The origin VPS or backend is offline. Clean IPs cannot resurrect an offline server.", httpCode = 521)
                    522, 524 -> OriginHealth.Down("Cloudflare Error $code: Origin connection timed out. Backend server is unreachable.", httpCode = code)
                    525, 526 -> OriginHealth.Down("Cloudflare Error $code: SSL handshake failed with origin server.", httpCode = code)
                    530, 1000, 1001, 1016 -> OriginHealth.Down("Cloudflare Error $code: DNS points to prohibited IP or domain configuration error.", httpCode = code)
                    else -> OriginHealth.Down("Origin returned HTTP ${code ?: "Unknown"}. Backend appears to be offline or misconfigured.", httpCode = code)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OriginHealth.Down("Origin connection failed: ${e.localizedMessage ?: "Timeout"}. Backend appears to be offline.")
        }
    }

    /**
     * Executes multi-stage clean-IP scan (CFScanner methodology):
     * Phase 1: High-concurrency TCP socket probe with 1200ms timeout.
     * Phase 2: TLS handshake verification with target SNI.
     * Phase 3: Real WebSocket Upgrade (if profile provided) or active Cloudflare CDN verification.
     * Phase 4: Download throughput speed test (like CFScanner).
     */
    suspend fun scan(
        p: VlessProfile? = null,
        targetPort: Int = 443,
        region: String = "Iran 🇮🇷 (All Operators)",
        customSubnet: String? = null,
        enableSpeedTest: Boolean = true,
        candidateCount: Int = 100,
        assertNetwork: () -> Unit = {},
        checkpoint: suspend () -> Unit = {},
        progress: (ScanProgress) -> Unit
    ): List<Result> = withContext(Dispatchers.IO) {
        assertNetwork()

        val isTls = targetPort in CLOUDFLARE_PORTS_TLS || (p?.security?.equals("tls", true) == true)
        val sniTarget = (p?.sni?.ifBlank { p.host.ifBlank { "speed.cloudflare.com" } } ?: "speed.cloudflare.com")
            .replace("\r", "").replace("\n", "")
        val hostHeader = (p?.host?.ifBlank { p.sni.ifBlank { "speed.cloudflare.com" } } ?: "speed.cloudflare.com")
            .replace("\r", "").replace("\n", "")
        val path = (p?.path?.ifBlank { "/" } ?: "/")
            .replace("\r", "").replace("\n", "")
            .let { if (it.startsWith('/')) it else "/$it" }
        val isWs = p?.transport?.equals("ws", ignoreCase = true) == true

        val candidatePool = generateCandidates(region, candidateCount, customSubnet)
        val total = candidatePool.size
        val accepted = mutableListOf<Result>()
        var testedCount = 0

        progress(ScanProgress(0, 0, candidatePool.firstOrNull()?.first.orEmpty(), total, "Starting Scan"))

        // Batches of 16 for high throughput without exhausting Android socket handles
        val chunks = candidatePool.chunked(16)
        for (chunk in chunks) {
            ensureActive()
            checkpoint()

            val batchResults = coroutineScope {
                chunk.map { (ip, country, meta) ->
                    val (flag, operatorTag) = meta
                    async(Dispatchers.IO) {
                        ensureActive()
                        val latencies = mutableListOf<Long>()

                        // Probe 1: Fast TCP Connect
                        val t1 = tcpPing(ip, targetPort, 1200)
                        if (t1 == null) return@async null
                        latencies.add(t1)
                        // Comparable samples only: TLS and HTTP durations are separate measurements.
                        repeat(2) { tcpPing(ip, targetPort, 1200)?.let { latencies.add(it) } }
                        if (latencies.size < 2) return@async null
                        var tlsMs: Long? = null

                        // Probe 2: TLS Handshake check
                        var tlsOk = true
                        if (isTls) {
                            val t2 = tlsHandshakePing(ip, targetPort, sniTarget, 1500)
                            if (t2 != null) {
                                tlsMs = t2
                            } else {
                                tlsOk = false
                            }
                        }
                        if (!tlsOk) return@async null

                        // Probe 3: Real HTTP / WebSocket Handshake Verification
                        var verificationStatus = "Verified"
                        var originVerified = false
                        val httpResult = testHttpEndpoint(ip, targetPort, isTls, sniTarget, hostHeader, path, isWs)
                        if (httpResult != null) {
                            verificationStatus = httpResult.first
                            originVerified = httpResult.second
                            // HTTP request time is recorded separately below.
                        } else {
                            return@async null
                        }

                        // A TCP/TLS handshake alone is not a clean-IP result. For a selected
                        // WebSocket profile require an actual 101 from that origin; for a
                        // generic Cloudflare scan require a successful CDN HTTP response.
                        if (isWs && !originVerified) return@async null
                        if (!isWs && !verificationStatus.contains("200 OK") &&
                            !verificationStatus.contains("204 OK")) return@async null

                        // Probe 4: Speed Test (CFScanner style)
                        var downloadSpeed = 0L
                        if (enableSpeedTest) {
                            downloadSpeed = downloadSpeedTest(ip, targetPort, isTls, 2500) ?: 0L
                        }

                        val sorted = latencies.sorted()
                        val median = sorted[sorted.size / 2]
                        val jitter = if (sorted.size > 1) sorted.last() - sorted.first() else 4L

                        Result(
                            ip = ip,
                            medianMs = median,
                            jitterMs = jitter,
                            successes = latencies.size.coerceAtLeast(1),
                            totalTries = 3,
                            country = country,
                            flagEmoji = flag,
                            operatorTag = operatorTag,
                            downloadSpeedKbps = downloadSpeed,
                            verificationStatus = verificationStatus,
                            originVerified = originVerified,
                            tlsHandshakeMs = tlsMs,
                            httpRequestMs = httpResult?.third
                        )
                    }
                }.awaitAll()
            }

            for (res in batchResults.filterNotNull()) {
                accepted.add(res)
            }
            testedCount += chunk.size
            progress(
                ScanProgress(
                    tested = testedCount,
                    reachable = accepted.size,
                    currentIp = chunk.lastOrNull()?.first.orEmpty(),
                    totalCandidates = total,
                    stage = "Testing ${chunk.lastOrNull()?.first.orEmpty()}"
                )
            )
        }

        ranked(accepted)
    }

    private fun tcpPing(ip: String, port: Int, timeoutMs: Int): Long? {
        val start = System.currentTimeMillis()
        return try {
            openSocket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val duration = System.currentTimeMillis() - start
                duration.coerceAtLeast(1L)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tlsHandshakePing(ip: String, port: Int, sniHost: String, timeoutMs: Int): Long? {
        val start = System.currentTimeMillis()
        return try {
            openSocket().use { rawSocket ->
                rawSocket.tcpNoDelay = true
                rawSocket.connect(InetSocketAddress(ip, port), timeoutMs)

                val sslSocket = probeSslSocketFactory.createSocket(rawSocket, sniHost, port, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs

                val sslParams = sslSocket.sslParameters ?: SSLParameters()
                sslParams.endpointIdentificationAlgorithm = "HTTPS"
                if (sniHost.isNotBlank() && !sniHost.matches(Regex("^[0-9.]+$"))) {
                    runCatching {
                        sslParams.serverNames = listOf(SNIHostName(sniHost))
                    }
                }
                sslSocket.sslParameters = sslParams
                sslSocket.startHandshake()
                sslSocket.close()

                val duration = System.currentTimeMillis() - start
                duration.coerceAtLeast(1L)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Probes HTTP / WebSocket endpoint on the candidate IP.
     * Returns: Triple(statusString, isOriginVerified, latencyMs)
     */
    private fun testHttpEndpoint(
        ip: String,
        port: Int,
        isTls: Boolean,
        sniHost: String,
        hostHeader: String,
        path: String,
        isWebSocket: Boolean
    ): Triple<String, Boolean, Long>? {
        val start = System.currentTimeMillis()
        return try {
            openSocket().use { rawSocket ->
                rawSocket.tcpNoDelay = true
                rawSocket.soTimeout = 2000
                rawSocket.connect(InetSocketAddress(ip, port), 2000)

                val socket: Socket = if (isTls) {
                    val ssl = probeSslSocketFactory.createSocket(rawSocket, sniHost, port, true) as SSLSocket
                    ssl.soTimeout = 2000
                    val sslParams = ssl.sslParameters ?: SSLParameters()
                    sslParams.endpointIdentificationAlgorithm = "HTTPS"
                    if (sniHost.isNotBlank() && !sniHost.matches(Regex("^[0-9.]+$"))) {
                        runCatching { sslParams.serverNames = listOf(SNIHostName(sniHost)) }
                    }
                    ssl.sslParameters = sslParams
                    ssl.startHandshake()
                    ssl
                } else {
                    rawSocket
                }

                val request = if (isWebSocket) {
                    val key = Base64.getEncoder().encodeToString(ByteArray(16).apply { SecureRandom().nextBytes(this) })
                    "GET $path HTTP/1.1\r\n" +
                        "Host: $hostHeader\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "User-Agent: CFScanner/1.0\r\n\r\n"
                } else {
                    "GET /cdn-cgi/trace HTTP/1.1\r\n" +
                        "Host: speed.cloudflare.com\r\n" +
                        "User-Agent: CFScanner/1.0\r\n" +
                        "Connection: close\r\n\r\n"
                }

                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.UTF_8))
                    flush()
                }

                val statusLine = readLine(socket.getInputStream())
                val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val code = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: .*)?$").matchEntire(statusLine)?.groupValues?.get(1)?.toIntOrNull()

                when (code) {
                    101 -> Triple("WS 101 OK", true, latency)
                    200 -> Triple("CDN 200 OK", !isWebSocket, latency)
                    204 -> Triple("HTTP 204 OK", false, latency)
                    521 -> Triple("HTTP 521 (Origin Down)", false, latency)
                    522 -> Triple("HTTP 522 (Timeout)", false, latency)
                    403 -> Triple("HTTP 403 (CF Block)", false, latency)
                    else -> Triple("HTTP ${code ?: "Unknown"}", false, latency)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * CFScanner download speed test using Cloudflare's official /__down?bytes=50000 endpoint.
     */
    private fun downloadSpeedTest(ip: String, port: Int, isTls: Boolean, timeoutMs: Int = 2500): Long? {
        val start = System.currentTimeMillis()
        return try {
            openSocket().use { rawSocket ->
                rawSocket.tcpNoDelay = true
                rawSocket.soTimeout = timeoutMs
                rawSocket.connect(InetSocketAddress(ip, port), timeoutMs)

                val socket: Socket = if (isTls) {
                    val ssl = probeSslSocketFactory.createSocket(rawSocket, "speed.cloudflare.com", port, true) as SSLSocket
                    ssl.soTimeout = timeoutMs
                    val sslParams = ssl.sslParameters ?: SSLParameters()
                    sslParams.endpointIdentificationAlgorithm = "HTTPS"
                    runCatching { sslParams.serverNames = listOf(SNIHostName("speed.cloudflare.com")) }
                    ssl.sslParameters = sslParams
                    ssl.startHandshake()
                    ssl
                } else {
                    rawSocket
                }

                val request = "GET /__down?bytes=50000 HTTP/1.1\r\n" +
                    "Host: speed.cloudflare.com\r\n" +
                    "User-Agent: CFScanner/1.0\r\n" +
                    "Connection: close\r\n\r\n"

                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.UTF_8))
                    flush()
                }

                val input = socket.getInputStream()
                // Read HTTP response header
                var prev = 0
                var headerBytes = 0
                while (headerBytes < 4096) {
                    val b = input.read()
                    if (b == -1) break
                    headerBytes++
                    if (prev == '\r'.code && b == '\n'.code) {
                        val next1 = input.read()
                        val next2 = input.read()
                        if (next1 == '\r'.code && next2 == '\n'.code) break
                    }
                    prev = b
                }

                val buf = ByteArray(8192)
                var totalBodyBytes = 0
                val bodyStart = System.currentTimeMillis()
                while (totalBodyBytes < 50000) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    totalBodyBytes += read
                    if (System.currentTimeMillis() - bodyStart > timeoutMs) break
                }
                val elapsedMs = (System.currentTimeMillis() - bodyStart).coerceAtLeast(10L)
                val kbps = (totalBodyBytes.toLong() * 1000L) / (elapsedMs * 1024L)
                kbps.coerceAtLeast(1L)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        var count = 0
        while (count < 2048) {
            val b = input.read()
            if (b == -1) break
            count++
            if (b == '\n'.code) {
                return sb.toString().removeSuffix("\r")
            }
            sb.append(b.toChar())
        }
        return sb.toString().removeSuffix("\r")
    }
}
