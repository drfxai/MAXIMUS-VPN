package com.example.vpn.dns

import com.example.vpn.EndpointResolver
import com.example.vpn.sidecar.ResolverReach
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.ln

/**
 * Per-network DNS resolver scanner for the DNS-tunnel engines (CottenDNS/MasterDNS, dnstt, Slipstream).
 *
 * Each candidate gets a few plain questions over UDP/53, one over TCP/53 and, for providers known to run it,
 * one over DoT/853, all with short time limits and a bounded number in flight. An answer for a control name
 * whose addresses are known ([CONTROL_NAME]) and one for a name Iran filters ([CENSORED_NAME]) tell an honest
 * resolver from one that hijacks: Iran's filter answers with 10.10.34.x, and any private or bogon address
 * for a public name is a forged answer. An answer from an address other than the one asked is interception.
 * Survivors are scored 0–100 and ranked; [VerifiedResolverPool] keeps the best per network.
 *
 * Probe set, hijack/intercept checks and the capped 0–100 score follow ZedSecure's resolver scanner
 * (CluvexStudio/ZedSecure `scan/DnsScan.kt`, ZedSecure-Cores `zedscan/score.rs`), AGPL-3.0, re-written in
 * Kotlin; see THIRD_PARTY_NOTICES.md. This only measures resolvers; it never starts a tunnel.
 */
object ResolverScanner {
    /** Resolves to 1.1.1.1 / 1.0.0.1 everywhere; anything else means the answer was rewritten. */
    const val CONTROL_NAME = "one.one.one.one"
    val CONTROL_ANSWERS = setOf("1.1.1.1", "1.0.0.1")
    /** Filtered in Iran; a poisoning resolver answers it with 10.10.34.x. */
    const val CENSORED_NAME = "www.youtube.com"

    /** Well-known public resolvers; [Candidate.dot] marks the ones that also serve DoT on 853. */
    val PUBLIC = listOf(
        Candidate("1.1.1.1", "public", dot = true), Candidate("1.0.0.1", "public", dot = true),
        Candidate("8.8.8.8", "public", dot = true), Candidate("8.8.4.4", "public", dot = true),
        Candidate("9.9.9.9", "public", dot = true), Candidate("149.112.112.112", "public", dot = true),
        Candidate("94.140.14.14", "public", dot = true), Candidate("208.67.222.222", "public"),
        Candidate("76.76.2.0", "public", dot = true), Candidate("185.228.168.9", "public")
    )

    /** Domestic public resolvers (Shecan, Electro, Begzar); reachable when foreign UDP/53 is dropped. */
    val IRAN = listOf(
        Candidate("178.22.122.100", "iran"), Candidate("185.51.200.2", "iran"),
        Candidate("78.157.42.100", "iran"), Candidate("78.157.42.101", "iran"),
        Candidate("185.55.226.26", "iran"), Candidate("185.55.225.25", "iran")
    )

    data class Candidate(val ip: String, val source: String, val dot: Boolean = false)

    data class Timeouts(val udpMs: Int = 900, val tcpMs: Int = 1_200, val dotMs: Int = 1_800)

    data class Result(
        val ip: String,
        val source: String,
        /** Valid NOERROR answers over UDP/53 out of [udpSamples]. */
        val udpReplies: Int,
        val udpSamples: Int,
        val udpMedianMs: Long?,
        val tcpMs: Long? = null,
        val dotMs: Long? = null,
        val hijacked: Boolean = false,
        val intercepted: Boolean = false
    ) {
        val udp: Boolean get() = udpReplies > 0
        val tcp: Boolean get() = tcpMs != null
        val dot: Boolean get() = dotMs != null
        /** Answers at all, and honestly. Only these go into the pool. */
        val usable: Boolean get() = !hijacked && !intercepted && (udp || tcp || dot)
        val bestMs: Long? get() = listOfNotNull(udpMedianMs, tcpMs, dotMs).minOrNull()
        val score: Int get() = ResolverScanner.score(this)
    }

    /** 0–100: latency 40 (log curve 40 ms → 1500 ms), UDP success 40, TCP +12, DoT +8; capped when dishonest. */
    fun score(r: Result): Int {
        val ms = r.bestMs ?: return 0
        val latency = when {
            ms <= 40 -> 40.0
            ms >= 1_500 -> 0.0
            else -> 40.0 * (1 - ln(ms / 40.0) / ln(1_500 / 40.0))
        }
        val success = if (r.udpSamples == 0) 0.0 else 40.0 * r.udpReplies / r.udpSamples
        var s = (latency + success + (if (r.tcp) 12 else 0) + (if (r.dot) 8 else 0)).toInt().coerceIn(0, 100)
        if (r.udpSamples > 0 && r.udpReplies * 2 < r.udpSamples) s = minOf(s, 60)
        if (r.intercepted) s = minOf(s, 30)
        if (r.hijacked) s = minOf(s, 10)
        return s
    }

    /** Usable results, best first: score, then latency, then address (stable). */
    fun rank(results: List<Result>): List<Result> = results.filter { it.usable }
        .sortedWith(compareByDescending<Result> { it.score }.thenBy { it.bestMs ?: Long.MAX_VALUE }.thenBy { it.ip })

    /** True for an address no honest resolver gives for a public name: Iran's 10.10.34.0/24 and any bogon. */
    fun poisoned(ip: String): Boolean {
        if (ip.startsWith("10.10.34.")) return true
        val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return true
        return EndpointResolver.isBlockedAnswer(address)
    }

    /**
     * [control] / [censored] are the A answers for [CONTROL_NAME] / [CENSORED_NAME] (null when not answered).
     * Hijacked when any answer is poisoned, or the control name came back with addresses that are not its own.
     */
    fun hijacked(control: List<String>?, censored: List<String>?): Boolean {
        if ((control.orEmpty() + censored.orEmpty()).any(::poisoned)) return true
        return control != null && control.isNotEmpty() && control.none { it in CONTROL_ANSWERS }
    }

    /**
     * Candidate list: [configured] resolver IPs first (they are what the profile will use), then public and
     * domestic resolvers, then [bundled] samples from the resolver pack; IPv4 literals only, deduplicated.
     */
    fun candidates(configured: List<String>, bundled: List<String>, max: Int = MAX_CANDIDATES): List<Candidate> {
        val ipv4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        val all = configured.map { Candidate(it, "config", dot = PUBLIC.any { p -> p.ip == it && p.dot }) } +
            PUBLIC + IRAN + bundled.map { Candidate(it, "bundled") }
        return all.filter { ipv4.matches(it.ip) && !poisoned(it.ip) }.distinctBy { it.ip }.take(max)
    }

    /**
     * Measures [candidates] with at most [parallelism] in flight and returns them ranked ([rank]).
     * [probe] is the real network measurement ([measure]) in the app, a fake in tests.
     */
    fun scan(
        candidates: List<Candidate>,
        parallelism: Int = PARALLELISM,
        deadlineMs: Long = DEADLINE_MS,
        probe: (Candidate) -> Result = { measure(it) }
    ): List<Result> {
        if (candidates.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, candidates.size))
        return try {
            val futures = pool.invokeAll(candidates.map { c -> Callable { runCatching { probe(c) }.getOrNull() } },
                deadlineMs, TimeUnit.MILLISECONDS)
            rank(futures.mapNotNull { f -> if (f.isCancelled) null else runCatching { f.get() }.getOrNull() })
        } finally {
            pool.shutdownNow()
        }
    }

    /** Real measurement of one resolver: 2 control + 1 censored question over UDP, 1 over TCP, DoT when known. */
    fun measure(c: Candidate, t: Timeouts = Timeouts()): Result {
        val target = InetSocketAddress(InetAddress.getByName(c.ip), 53)
        var intercepted = false
        val udpTimes = mutableListOf<Long>()
        var control: List<String>? = null
        var censored: List<String>? = null
        listOf(CONTROL_NAME, CONTROL_NAME, CENSORED_NAME).forEach { name ->
            when (val a = udp(target, name, t.udpMs)) {
                is Answer.Ok -> {
                    udpTimes += a.ms
                    if (name == CONTROL_NAME) control = (control.orEmpty() + a.ips).distinct() else censored = a.ips
                }
                Answer.Intercepted -> intercepted = true
                Answer.None -> Unit
            }
        }
        val tcp = stream(name = CONTROL_NAME, timeoutMs = t.tcpMs) { Socket().apply { connect(target, t.tcpMs) } }
        if (control == null && tcp is Answer.Ok) control = tcp.ips
        val dot = if (!c.dot) null else stream(name = CONTROL_NAME, timeoutMs = t.dotMs) {
            val raw = Socket().apply { connect(InetSocketAddress(target.address, 853), t.dotMs); soTimeout = t.dotMs }
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, c.ip, 853, true) as SSLSocket)
                .apply { startHandshake() }
        }
        val sorted = udpTimes.sorted()
        return Result(
            ip = c.ip, source = c.source, udpReplies = udpTimes.size, udpSamples = 3,
            udpMedianMs = sorted.getOrNull(sorted.size / 2),
            tcpMs = (tcp as? Answer.Ok)?.ms, dotMs = (dot as? Answer.Ok)?.ms,
            hijacked = hijacked(control, censored), intercepted = intercepted
        )
    }

    // ---- DNS wire ----

    internal sealed class Answer {
        data class Ok(val ms: Long, val ips: List<String>) : Answer()
        object Intercepted : Answer()
        object None : Answer()
    }

    private val random = SecureRandom()

    private fun udp(target: InetSocketAddress, name: String, timeoutMs: Int): Answer {
        return try { DatagramSocket().use { socket ->
            val q = ResolverReach.query(random.nextInt(0x10000), name)
            val started = System.nanoTime()
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(q, q.size, target))
            val buf = ByteArray(1500)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            if (packet.address != target.address) Answer.Intercepted
            else aRecords(q, buf, packet.length)?.let { Answer.Ok(ms, it) } ?: Answer.None
        } } catch (_: IOException) { Answer.None } catch (_: SecurityException) { Answer.None }
    }

    /** One question over a stream (TCP/53 or DoT) with the 2-byte length prefix. */
    private fun stream(name: String, timeoutMs: Int, open: () -> Socket): Answer {
        val started = System.nanoTime()
        return try { open().use { s ->
            s.soTimeout = timeoutMs
            val q = ResolverReach.query(random.nextInt(0x10000), name)
            s.getOutputStream().apply { write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte()) + q); flush() }
            val input = DataInputStream(s.getInputStream())
            val len = input.readUnsignedShort()
            if (len !in 12..4096) return Answer.None
            val buf = ByteArray(len).also { input.readFully(it) }
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            aRecords(q, buf, len)?.let { Answer.Ok(ms, it) } ?: Answer.None
        } } catch (_: IOException) { Answer.None } catch (_: SecurityException) { Answer.None }
    }

    /**
     * The IPv4 A records in [response] to [query], or null when it is not a matching NOERROR answer.
     * Follows name compression; CNAMEs and other records are skipped.
     */
    internal fun aRecords(query: ByteArray, response: ByteArray, length: Int): List<String>? {
        val rcode = ResolverReach.responseCode(query, response, length) ?: return null
        if (rcode != 0) return null
        fun u8(i: Int) = response[i].toInt() and 0xff
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        val count = u16(6)
        var p = query.size
        val ips = mutableListOf<String>()
        repeat(count) {
            // NAME: labels ending in 0, or a 2-byte pointer.
            while (true) {
                if (p >= length) return ips
                val l = u8(p)
                if (l == 0) { p += 1; break }
                if (l and 0xc0 == 0xc0) { p += 2; break }
                p += 1 + l
            }
            if (p + 10 > length) return ips
            val type = u16(p); val rdLength = u16(p + 8)
            p += 10
            if (p + rdLength > length) return ips
            if (type == 1 && rdLength == 4) ips += (0 until 4).joinToString(".") { u8(p + it).toString() }
            p += rdLength
        }
        return ips
    }

    const val PARALLELISM = 8
    const val MAX_CANDIDATES = 40
    /** Whole-scan limit; one candidate takes at most ~5 s, so 40 at 8 in flight fit. */
    const val DEADLINE_MS = 30_000L
}
