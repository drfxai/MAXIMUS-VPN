package com.example.vpn.cleanip

import com.example.vpn.cleanip.CdnEdgeScanner.Handshake
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Tells whether this network transparently proxies Cloudflare-bound TLS by its server name (reported on
 * Irancell and seen on MCI: the operator terminates TCP itself and re-originates allowed names, so a clean
 * Cloudflare address changes nothing). On such a network clean-IP scanning for Cloudflare only wastes the
 * connect budget; ECH or another CDN is what can help.
 *
 * Evidence, all gathered without the VPN:
 * - **Decoys (strong):** a TLS handshake for a Cloudflare name to Fastly, CloudFront and Gcore addresses must
 *   fail its certificate check. One that completes with a valid Cloudflare certificate was routed by name.
 * - **Trace (strong):** Cloudflare's `/cdn-cgi/trace` reports `loc` (where Cloudflare sees the request come
 *   from). Another country than the phone's network country means the request left from somewhere else.
 * - **Certificate mismatch (medium):** most Cloudflare edges answer the name with a certificate that does not
 *   verify for it.
 * - **Uniform RTT (medium):** TCP connects to edges in four or more different ranges take nearly the same time,
 *   and the TLS handshake takes far longer than TCP: TCP ended close by, TLS travelled further.
 *
 * One strong sign or two medium ones flag the network.
 */
object SniProxyDetector {
    const val PROBE_NAME = "speed.cloudflare.com"

    data class Evidence(
        /** Cloudflare edges, one per range where possible, with [PROBE_NAME] as the TLS name. */
        val edges: List<Pair<String, Handshake>>,
        /** Non-Cloudflare CDN addresses with [PROBE_NAME] as the TLS name. */
        val decoys: List<Pair<String, Handshake>> = emptyList(),
        /** `loc=` from `/cdn-cgi/trace`, or null when not read. */
        val traceLoc: String? = null,
        /** The phone's network country (ISO 3166 alpha-2), or null when unknown. */
        val expectedCountry: String? = null
    )

    data class Verdict(
        val proxied: Boolean,
        /** False when too little answered to say either way; such a verdict is not remembered. */
        val conclusive: Boolean,
        val reasons: List<String>
    )

    /** Coefficient of variation under which RTTs across ranges count as identical. */
    const val UNIFORM_CV = 0.10
    const val MIN_RANGES = 4

    fun judge(e: Evidence): Verdict {
        val strong = mutableListOf<String>()
        val medium = mutableListOf<String>()
        e.decoys.filter { it.second.ok }.takeIf { it.isNotEmpty() }?.let { hits ->
            strong += "TLS for $PROBE_NAME completed on non-Cloudflare address ${hits.joinToString { it.first }}"
        }
        val loc = e.traceLoc?.trim()?.uppercase()?.ifBlank { null }
        val home = e.expectedCountry?.trim()?.uppercase()?.ifBlank { null }
        if (loc != null && home != null && loc != "XX" && loc != home) strong += "Cloudflare sees this network in $loc, not $home"

        val reached = e.edges.filter { it.second.tcpMs != null }
        val mismatched = reached.count { it.second.certMismatch }
        if (reached.size >= 2 && mismatched * 2 >= reached.size) medium += "$mismatched of ${reached.size} Cloudflare edges presented a certificate that does not match"

        val clean = e.edges.filter { it.second.ok }
        val ranges = clean.mapNotNull { CdnRanges.rangeOf(Cdn.CLOUDFLARE, it.first) }.distinct().size
        if (ranges >= MIN_RANGES) {
            val tcp = clean.map { it.second.tcpMs!!.toDouble() }
            val tls = clean.map { it.second.tlsMs!!.toDouble() }
            val cv = coefficientOfVariation(tcp)
            val tcpMid = median(tcp)
            val tlsMid = median(tls)
            if (cv < UNIFORM_CV && tlsMid >= 2.5 * tcpMid + 30) {
                medium += "TCP to $ranges Cloudflare ranges took the same ${tcpMid.toLong()} ms while TLS took ${tlsMid.toLong()} ms"
            }
        }

        val conclusive = strong.isNotEmpty() || medium.size >= 2 || reached.size >= 2 || e.decoys.any { it.second.tcpMs != null } || loc != null
        val proxied = strong.isNotEmpty() || medium.size >= 2
        return Verdict(proxied, conclusive, strong + medium)
    }

    fun median(values: List<Double>): Double = values.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

    fun coefficientOfVariation(values: List<Double>): Double {
        if (values.size < 2) return Double.MAX_VALUE
        val mean = values.average()
        if (mean <= 0.0) return Double.MAX_VALUE
        val sd = kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return sd / mean
    }

    /** `loc=` from a `/cdn-cgi/trace` body, or null. */
    fun parseTraceLoc(body: String): String? = body.lineSequence()
        .map { it.trim() }.firstOrNull { it.startsWith("loc=") }?.removePrefix("loc=")?.trim()?.ifBlank { null }

    /** Gathers [Evidence] on the phone's network: 6 Cloudflare edges, a decoy per other CDN, and one trace. */
    fun probe(
        expectedCountry: String?,
        handshake: (ip: String, port: Int, sni: String, timeoutMs: Int) -> Handshake =
            { ip, port, sni, t -> CdnEdgeScanner.liveHandshake(ip, port, sni, t) },
        trace: (ip: String) -> String? = { liveTrace(it) },
        random: Random = Random.Default,
        timeoutMs: Int = 2_000
    ): Evidence {
        val edges = CdnRanges.sample(Cdn.CLOUDFLARE, 6, random).map { it to handshake(it, 443, PROBE_NAME, timeoutMs) }
        val decoys = listOf(Cdn.FASTLY, Cdn.CLOUDFRONT, Cdn.GCORE).mapNotNull { CdnRanges.sample(it, 1, random).firstOrNull() }
            .map { it to handshake(it, 443, PROBE_NAME, timeoutMs) }
        val loc = edges.firstOrNull { it.second.ok }?.let { runCatching { trace(it.first) }.getOrNull() }?.let(::parseTraceLoc)
        return Evidence(edges, decoys, loc, expectedCountry)
    }

    /** The body of `https://speed.cloudflare.com/cdn-cgi/trace` fetched through edge [ip], certificate checked. */
    fun liveTrace(ip: String): String? = runCatching {
        com.example.panels.CleanIpOptimizer.directSocket().use { socket ->
            socket.connect(InetSocketAddress(ip, 443), 2_500)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, PROBE_NAME, 443, true) as SSLSocket
            ssl.soTimeout = 3_000
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(SNIHostName(PROBE_NAME)); endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.startHandshake()
            ssl.outputStream.apply {
                write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $PROBE_NAME\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val input = ssl.inputStream
            if (!com.example.panels.CleanIpOptimizer.skipHeaders(input)) return@use null
            val buf = ByteArray(4096)
            var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            String(buf, 0, n, Charsets.UTF_8)
        }
    }.getOrNull()
}
