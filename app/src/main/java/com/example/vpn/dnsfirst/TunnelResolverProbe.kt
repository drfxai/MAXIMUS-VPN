package com.example.vpn.dnsfirst

import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ln

/** The DNS tunnel engines the ladder drives, by what they need from a resolver. */
enum class EngineKind { DNSTT, SLIPSTREAM, COTTEN }

/**
 * What one resolver does with tunnel-shaped traffic for one tunnel domain, measured with ordinary queries for
 * random names under that domain (they reach the tunnel's own name server, which is what a tunnel needs):
 * whether it recurses for random labels at all, for near-maximum names, for TXT/NULL/CNAME questions, whether it
 * advertises a large EDNS payload, answers over TCP/53, keeps up with a short burst, and accepts a query with the
 * reserved Z bit set (CircumVolve row 10; recorded only, see docs/DNS_FIRST.md).
 *
 * A clean NXDOMAIN counts as reached: the tunnel server (or its parent zone) answered. Only a real request
 * through the engine ([DnsLadderRunner]) proves the tunnel itself works.
 */
data class TunnelCaps(
    val resolver: String,
    val domain: String,
    /** "udp", "tcp" or "doh" — how the probe talked to [resolver] ([resolver] is a URL for DoH). */
    val transport: String = "udp",
    val reach: Boolean,
    val rcode: Int? = null,
    val rttMs: Long? = null,
    val longName: Boolean = false,
    val txt: Boolean = false,
    val nullType: Boolean = false,
    val cname: Boolean = false,
    val ednsPayload: Int? = null,
    val tcp: Boolean = false,
    val burstSent: Int = 0,
    val burstAnswered: Int = 0,
    val zShape: Boolean = false
) {
    /**
     * Queries a second this resolver keeps up with, as the engines' per-resolver rate (dnstt -qps): 4 when it
     * answered (almost) the whole burst, 2 when most, else 1. Never above dnstt's own limit of 4.
     */
    val safeQps: Int get() = when {
        burstSent == 0 -> 1
        burstAnswered * 10 >= burstSent * 9 -> 4
        burstAnswered * 10 >= burstSent * 6 -> 2
        else -> 1
    }

    /** Usable for some tunnel at all: recursion for random names under the domain, long names included. */
    val usable: Boolean get() = reach && longName

    fun suits(kind: EngineKind, overTcp: Boolean = false): Boolean = usable && when (kind) {
        // dnstt and Slipstream carry the downstream in TXT answers to long random names.
        EngineKind.DNSTT, EngineKind.SLIPSTREAM -> txt && !overTcp
        EngineKind.COTTEN -> if (overTcp) tcp else true
    }

    /** 0–100: latency 40, TXT 15, burst 15, EDNS ≥ 1232 10, TCP 8, NULL 6, CNAME 6. 0 when not usable. */
    val score: Int get() {
        if (!usable) return 0
        val ms = rttMs ?: 1_500
        val latency = when {
            ms <= 40 -> 40.0
            ms >= 1_500 -> 0.0
            else -> 40.0 * (1 - ln(ms / 40.0) / ln(1_500 / 40.0))
        }
        val burst = if (burstSent == 0) 0.0 else 15.0 * burstAnswered / burstSent
        return (latency + burst + (if (txt) 15 else 0) + (if ((ednsPayload ?: 0) >= DnsWire.EDNS_PAYLOAD) 10 else 0) +
            (if (tcp) 8 else 0) + (if (nullType) 6 else 0) + (if (cname) 6 else 0)).toInt().coerceIn(1, 100)
    }
}

object TunnelResolverProbe {
    data class Timeouts(val queryMs: Int = 1_500, val burstMs: Int = 1_800, val tcpMs: Int = 2_000)

    /** How the probe reaches one resolver; the real ones are [Udp] and [Doh], tests use fakes. */
    interface Transport {
        /** One query; the raw reply bytes, or null on silence/error. [tcp] asks for TCP/53 where it applies. */
        fun exchange(query: ByteArray, timeoutMs: Int, tcp: Boolean = false): ByteArray?
        /** All [queries] at once; the replies that came back within [timeoutMs], in any order. */
        fun burst(queries: List<ByteArray>, timeoutMs: Int): List<ByteArray>
    }

    const val BURST = 10

    /** Measures [resolver] for [domain] through [transport]. Blocking. */
    fun probe(
        resolver: String,
        domain: String,
        transport: Transport,
        random: Random = SecureRandom(),
        t: Timeouts = Timeouts(),
        kind: String = "udp",
        clock: () -> Long = System::nanoTime
    ): TunnelCaps {
        fun ask(name: String, type: Int, edns: DnsWire.Edns? = null, tcp: Boolean = false, shape: (ByteArray) -> ByteArray = { it }):
            Pair<DnsWire.Reply?, Long> {
            val q = DnsWire.query(random.nextInt(0x10000), name, type, edns)
            val wire = shape(q)
            val started = clock()
            val raw = runCatching { transport.exchange(wire, t.queryMs, tcp) }.getOrNull() ?: return null to 0L
            val ms = TimeUnit.NANOSECONDS.toMillis(clock() - started)
            return DnsWire.parse(wire, raw) to ms
        }
        fun short() = DnsWire.randomLabel(random, 12) + "." + domain
        // 1. Random label under the tunnel domain, twice (one loss is not a verdict).
        var first = ask(short(), DnsWire.TYPE_A)
        if (first.first == null) first = ask(short(), DnsWire.TYPE_A)
        val reply = first.first
        if (reply == null || !reply.resolved) {
            val tcpOnly = kind == "udp" && ask(short(), DnsWire.TYPE_A, tcp = true).first?.resolved == true
            return TunnelCaps(resolver, domain, kind, reach = false, rcode = reply?.rcode, tcp = tcpOnly)
        }
        val padded = DnsWire.Edns(DnsWire.EDNS_PAYLOAD, DnsWire.PAD_BLOCK)
        // 2. A near-maximum name with a TXT question and padded EDNS: what dnstt/Slipstream upstream looks like.
        val long = ask(DnsWire.tunnelLikeName(random, domain), DnsWire.TYPE_TXT, padded)
        val txt = ask(short(), DnsWire.TYPE_TXT, padded)
        val nul = ask(short(), DnsWire.TYPE_NULL)
        val cname = ask(short(), DnsWire.TYPE_CNAME)
        val tcp = kind == "udp" && ask(short(), DnsWire.TYPE_TXT, tcp = true).first?.resolved == true
        val z = ask(short(), DnsWire.TYPE_A, shape = DnsWire::withZ).first?.resolved == true
        // 3. A short burst of TXT queries: what a resolver lets through before it starts dropping.
        val burstQueries = List(BURST) { DnsWire.query(random.nextInt(0x10000), DnsWire.tunnelLikeName(random, domain, 120), DnsWire.TYPE_TXT) }
        val replies = runCatching { transport.burst(burstQueries, t.burstMs) }.getOrDefault(emptyList())
        val answered = burstQueries.count { q -> replies.any { r -> DnsWire.parse(q, r)?.resolved == true } }
        val edns = listOfNotNull(long.first?.ednsPayload, txt.first?.ednsPayload).maxOrNull()
        return TunnelCaps(
            resolver = resolver, domain = domain, transport = kind, reach = true, rcode = reply.rcode, rttMs = first.second,
            longName = long.first?.resolved == true, txt = txt.first?.resolved == true,
            nullType = nul.first?.resolved == true, cname = cname.first?.resolved == true,
            ednsPayload = edns, tcp = tcp, burstSent = BURST, burstAnswered = answered, zShape = z
        )
    }

    /** Probes every resolver in [resolvers] for [domain], at most [parallelism] at once, within [deadlineMs]. */
    fun scan(
        resolvers: List<String>,
        domain: String,
        parallelism: Int = 6,
        deadlineMs: Long = 45_000,
        probe: (String) -> TunnelCaps
    ): List<TunnelCaps> {
        if (resolvers.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, resolvers.size))
        return try {
            pool.invokeAll(resolvers.map { r -> Callable { runCatching { probe(r) }.getOrNull() } }, deadlineMs, TimeUnit.MILLISECONDS)
                .mapNotNull { f -> if (f.isCancelled) null else runCatching { f.get() }.getOrNull() }
                .sortedWith(compareByDescending<TunnelCaps> { it.score }.thenBy { it.rttMs ?: Long.MAX_VALUE }.thenBy { it.resolver })
        } finally {
            pool.shutdownNow()
        }
    }

    /** UDP/53 (and TCP/53 on request) to one resolver IP, from the app's own sockets (outside the VPN). */
    class Udp(ip: String, port: Int = 53) : Transport {
        private val target = InetSocketAddress(InetAddress.getByName(ip), port)

        override fun exchange(query: ByteArray, timeoutMs: Int, tcp: Boolean): ByteArray? {
            return try {
                if (tcp) stream(query, timeoutMs) else datagram(query, timeoutMs)
            } catch (_: IOException) { null } catch (_: SecurityException) { null }
        }

        private fun datagram(query: ByteArray, timeoutMs: Int): ByteArray? {
            DatagramSocket().use { s ->
                s.send(DatagramPacket(query, query.size, target))
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                val buf = ByteArray(4096)
                while (true) {
                    val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (left <= 0) return null
                    s.soTimeout = left.toInt().coerceAtLeast(1)
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { return null }
                    // A reply from another address is interception: never trust it.
                    if (p.address == target.address && DnsWire.parse(query, buf, p.length) != null) return buf.copyOf(p.length)
                }
            }
        }

        private fun stream(query: ByteArray, timeoutMs: Int): ByteArray? {
            Socket().use { s ->
                s.connect(target, timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()) + query); flush() }
                val input = DataInputStream(s.getInputStream())
                val len = input.readUnsignedShort()
                if (len < 12) return null
                return ByteArray(len).also { input.readFully(it) }
            }
        }

        override fun burst(queries: List<ByteArray>, timeoutMs: Int): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            try {
                DatagramSocket().use { s ->
                    queries.forEach { q -> s.send(DatagramPacket(q, q.size, target)) }
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                    val buf = ByteArray(4096)
                    while (out.size < queries.size) {
                        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        if (left <= 0) break
                        s.soTimeout = left.toInt().coerceAtLeast(1)
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (_: SocketTimeoutException) { break }
                        if (p.address == target.address) out += buf.copyOf(p.length)
                    }
                }
            } catch (_: IOException) { } catch (_: SecurityException) { }
            return out
        }
    }

    /** RFC 8484 POST to one DoH URL; [post] is the HTTP exchange (real one below, a fake in tests). */
    class Doh(
        private val url: String,
        private val post: (url: String, body: ByteArray, timeoutMs: Int) -> ByteArray? = ::httpPost
    ) : Transport {
        override fun exchange(query: ByteArray, timeoutMs: Int, tcp: Boolean): ByteArray? {
            // RFC 8484 §4.1: the ID SHOULD be 0 for caching; the reply is matched by the question, so keep it.
            return post(url, query, timeoutMs)
        }

        override fun burst(queries: List<ByteArray>, timeoutMs: Int): List<ByteArray> {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong() * 2)
            return queries.mapNotNull { q ->
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).toInt()
                if (left <= 0) null else post(url, q, left)
            }
        }
    }

    fun httpPost(url: String, body: ByteArray, timeoutMs: Int): ByteArray? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/dns-message")
                c.setRequestProperty("Accept", "application/dns-message")
                c.outputStream.use { it.write(body) }
                if (c.responseCode != 200) null else c.inputStream.use { s ->
                    val buf = ByteArray(8192); var n = 0
                    while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    buf.copyOf(n)
                }
            } finally { c.disconnect() }
        } catch (_: IOException) { null } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null }
    }
}
