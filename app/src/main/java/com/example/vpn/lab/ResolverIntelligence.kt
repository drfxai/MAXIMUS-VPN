package com.example.vpn.lab

import android.net.Network
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Minimal DNS wire format: enough to ask one question and read the header and A answers. */
object DnsWire {
    data class Answer(val id: Int, val rcode: Int, val truncated: Boolean, val answers: Int, val addresses: List<InetAddress>, val size: Int,
                      /** Record types in the answer section, in order. */
                      val types: List<Int> = emptyList())

    const val TYPE_A = 1
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3

    /** A recursive query for [name]/[type]; with [ednsSize] > 0 an OPT record advertises that UDP size. */
    fun query(id: Int, name: String, type: Int, ednsSize: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(id); d.writeShort(0x0100); d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(if (ednsSize > 0) 1 else 0)
        val labels = java.net.IDN.toASCII(name.trimEnd('.')).split('.')
        require(labels.joinToString(".").length <= 253) { "bad name" }
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "bad label" }
            d.writeByte(bytes.size); d.write(bytes)
        }
        d.writeByte(0); d.writeShort(type); d.writeShort(1)
        if (ednsSize > 0) { d.writeByte(0); d.writeShort(41); d.writeShort(ednsSize); d.writeInt(0); d.writeShort(0) }
        return out.toByteArray()
    }

    /** Reads the header and the A records of a response; null when it is not a DNS response. */
    fun parse(data: ByteArray, length: Int): Answer? {
        if (!com.example.vpn.dns.DnsMessageValidator.wellFormed(data, length)) return null
        fun u16(i: Int) = ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
        val flags = u16(2)
        if ((flags and 0x8000) == 0) return null
        val qd = u16(4); val an = u16(6)
        var i = 12
        fun skipName() {
            while (i < length) {
                val len = data[i].toInt() and 0xff
                if (len == 0) { i++; return }
                if ((len and 0xC0) == 0xC0) { i += 2; return }
                i += 1 + len
            }
        }
        repeat(qd) { skipName(); i += 4 }
        val addresses = mutableListOf<InetAddress>()
        val types = mutableListOf<Int>()
        repeat(an) {
            if (i >= length) return@repeat
            skipName()
            if (i + 10 > length) return@repeat
            val type = u16(i); val recordClass = u16(i + 2); val rdLen = u16(i + 8)
            i += 10
            if (recordClass == 1) types += type
            if (recordClass == 1 && type == TYPE_A && rdLen == 4 && i + 4 <= length) addresses += InetAddress.getByAddress(data.copyOfRange(i, i + 4))
            if (recordClass == 1 && type == TYPE_AAAA && rdLen == 16 && i + 16 <= length) addresses += InetAddress.getByAddress(data.copyOfRange(i, i + 16))
            i += rdLen
        }
        return Answer(u16(0), flags and 0x000F, (flags and 0x0200) != 0, an, addresses, length, types)
    }

    /** Bind measured A/AAAA addresses to this question, rather than unrelated answer records. */
    internal fun response(query: ByteArray, data: ByteArray, length: Int): Answer? {
        if (!com.example.vpn.dns.DnsMessageValidator.isResponseTo(query, data, length)) return null
        val answer = parse(data, length) ?: return null
        return answer.copy(addresses = com.example.vpn.dns.DnsMessageValidator.addressAnswers(query, data, length)
            .map { InetAddress.getByAddress(it) })
    }
}

/**
 * Bounded, network-specific evaluation of DNS resolvers (Resolver Intelligence V2). Each resolver gets at
 * most six small queries: a normal A name, AAAA, TXT, a random never-existing name (NXDOMAIN hijacking),
 * an EDNS query (large responses, truncation), and the same question over TCP. The best resolver then gets
 * a short burst that measures loss, jitter and rate limiting. Results expire; nothing learnt on one network
 * is used on another.
 *
 * Statuses: DNS_HEALTHY (answers honestly), DNS_TUNNEL_CANDIDATE (also TXT and large answers or TCP, which a
 * DNS tunnel needs). RECURSION_VERIFIED needs a controlled foreign zone and DNS_TUNNEL_VERIFIED needs real
 * tunnel traffic, so neither is ever produced by these probes.
 */
object ResolverIntelligence {

    data class Candidate(val label: String, val address: String, val origin: String)

    enum class DnsStatus(val title: String) {
        DNS_FAILED("No answer"),
        DNS_MANIPULATED("Tampered"),
        DNS_HEALTHY("Healthy"),
        /** Needs a nonce answered by a foreign authoritative zone; not produced until that zone exists. */
        RECURSION_VERIFIED("Recursion verified"),
        DNS_TUNNEL_CANDIDATE("DNS tunnel candidate"),
        /** Only from real traffic through a DNS tunnel; never from these probes. */
        DNS_TUNNEL_VERIFIED("DNS tunnel verified")
    }

    /** DNS request loss and timing; TCP results never imply IP packet-loss measurements. */
    data class Burst(
        val label: String,
        val sent: Int,
        val answered: Int,
        val medianMs: Long?,
        val jitterMs: Long?,
        /** Answers stopped part-way through: early queries answered, the last ones not. */
        val rateLimited: Boolean,
        val measuredAt: Long,
        val transport: String = "UDP"
    ) {
        val lossPercent: Int get() = if (sent == 0) 0 else (sent - answered) * 100 / sent
    }

    /** Summarizes a burst from per-query round trips (null: no answer). Pure. */
    fun burstOf(label: String, rtts: List<Long?>, now: Long): Burst {
        val got = rtts.filterNotNull()
        val half = rtts.size / 2
        val early = rtts.take(half).count { it != null }
        val late = rtts.drop(half).count { it != null }
        val rateLimited = rtts.size >= 6 && half > 0 && early * 10 >= half * 8 && late * 10 <= (rtts.size - half) * 2
        val jitter = if (got.size >= 2) got.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toLong() else null
        return Burst(label, rtts.size, got.size, got.sorted().getOrNull(got.size / 2), jitter, rateLimited, now)
    }

    data class Result(
        val label: String,
        val address: String,
        val origin: String,
        val networkKey: String,
        val udp: Boolean?,
        val tcp: Boolean?,
        val rttMs: Long?,
        /** Answered a foreign name with block-page or private addresses. */
        val blockPage: Boolean?,
        /** Answered a random never-existing name with an address instead of NXDOMAIN. */
        val nxdomainHijack: Boolean?,
        /** Largest EDNS UDP size it answered without truncation (null: not measured). */
        val ednsSize: Int?,
        val measuredAt: Long,
        val expiresAt: Long,
        /** Answered an AAAA question with records. */
        val aaaa: Boolean? = null,
        /** Answered a TXT question with records (DNS tunnels carry data in TXT). */
        val txt: Boolean? = null,
        /** The large answer came back truncated over UDP. */
        val truncated: Boolean? = null
    ) {
        val status: DnsStatus get() = when {
            udp != true && tcp != true -> DnsStatus.DNS_FAILED
            blockPage == true || nxdomainHijack == true -> DnsStatus.DNS_MANIPULATED
            txt == true && (ednsSize != null || tcp == true) -> DnsStatus.DNS_TUNNEL_CANDIDATE
            else -> DnsStatus.DNS_HEALTHY
        }

        /** Fit to carry a DNS tunnel: answers honestly over UDP or TCP. */
        val usable: Boolean get() = (udp == true || tcp == true) && blockPage != true && nxdomainHijack != true
        val reliability: Double get() = listOf(udp, tcp, blockPage?.not(), nxdomainHijack?.not()).filterNotNull().let { if (it.isEmpty()) 0.0 else it.count { v -> v }.toDouble() / it.size }

        fun toJson(): JSONObject = JSONObject().put("l", label).put("a", address).put("o", origin).put("n", networkKey)
            .put("u", udp ?: JSONObject.NULL).put("t", tcp ?: JSONObject.NULL).put("r", rttMs ?: JSONObject.NULL)
            .put("b", blockPage ?: JSONObject.NULL).put("h", nxdomainHijack ?: JSONObject.NULL).put("e", ednsSize ?: JSONObject.NULL)
            .put("m", measuredAt).put("x", expiresAt)
            .put("q6", aaaa ?: JSONObject.NULL).put("tx", txt ?: JSONObject.NULL).put("tr", truncated ?: JSONObject.NULL)

        companion object {
            fun fromJson(o: JSONObject): Result? = runCatching {
                fun b(k: String) = if (o.isNull(k)) null else o.getBoolean(k)
                Result(o.getString("l"), o.getString("a"), o.optString("o"), o.getString("n"), b("u"), b("t"),
                    if (o.isNull("r")) null else o.getLong("r"), b("b"), b("h"), if (o.isNull("e")) null else o.getInt("e"), o.getLong("m"), o.getLong("x"),
                    b("q6"), b("tx"), b("tr"))
            }.getOrNull()
        }
    }

    const val TTL_MS = 6L * 60 * 60 * 1000
    const val RECHECK_MS = 2L * 60 * 1000

    /** History may live for hours; an active LAB measurement must be much fresher. */
    fun reusable(results: List<Result>, now: Long = System.currentTimeMillis()): Boolean =
        results.isNotEmpty() && results.all { now - it.measuredAt in 0..RECHECK_MS && it.expiresAt > now }
    private const val TIMEOUT_MS = 2500
    private const val BURST_TIMEOUT_MS = 1200
    private const val PROBE_NAME = "www.google.com"
    /** A real foreign zone; a random label under it must not exist. */
    private const val NX_ZONE = "google.com"

    /** Public resolvers worth checking besides the network's own; addresses only, no lookup needed. */
    val PUBLIC = listOf(
        Candidate("Cloudflare", "1.1.1.1", "public"),
        Candidate("Google", "8.8.8.8", "public"),
        Candidate("Quad9", "9.9.9.9", "public"),
        Candidate("Shecan", "178.22.122.100", "public-domestic"),
        Candidate("Electro", "78.157.42.100", "public-domestic")
    )

    /** At most [max] resolvers: the network's own first, then public ones. */
    fun candidates(networkDns: List<InetAddress>, max: Int = 7): List<Candidate> =
        (networkDns.filter { it.address.size == 4 }.map { Candidate("Network DNS", it.hostAddress ?: "", "network") } + PUBLIC)
            .filter { it.address.isNotBlank() }.distinctBy { it.address }.take(max)

    /** Classifies a response to a foreign name and to a random never-existing name. Pure; tested. */
    fun judge(normal: DnsWire.Answer?, random: DnsWire.Answer?): Pair<Boolean?, Boolean?> {
        val blockPage = normal?.let { a -> a.addresses.isNotEmpty() && a.addresses.any { com.example.vpn.EndpointResolver.isBlockedAnswer(it) } }
        val hijack = random?.let { it.rcode == DnsWire.RCODE_NOERROR && it.addresses.isNotEmpty() }
        return blockPage to hijack
    }

    /** UDP loss or truncation is not a failed resolver when TCP can carry the same question. */
    internal fun completeAnswer(udp: () -> DnsWire.Answer?, tcp: () -> DnsWire.Answer?): DnsWire.Answer? {
        val answer = udp()
        if (answer != null && !answer.truncated) return answer
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("DNS probe stopped")
        return tcp()?.takeUnless { it.truncated }
    }

    fun evaluate(network: Network?, c: Candidate, networkKey: String, now: Long = System.currentTimeMillis()): Result {
        val server = InetAddress.getByName(c.address)
        val rnd = SecureRandom()
        val started = System.nanoTime()
        val normal = runCatching { udp(network, server, DnsWire.query(rnd.nextInt(0xffff), PROBE_NAME, DnsWire.TYPE_A)) }.getOrNull()
        val tcpStarted = System.nanoTime()
        val tcpAnswer = runCatching { tcp(network, server, DnsWire.query(rnd.nextInt(65536), PROBE_NAME, DnsWire.TYPE_A)) }.getOrNull()
        val rtt = normal?.let { (tcpStarted - started) / 1_000_000 }
            ?: tcpAnswer?.let { (System.nanoTime() - tcpStarted) / 1_000_000 }
        val tcpOk = tcpAnswer?.let { it.rcode == 0 && !it.truncated && it.addresses.isNotEmpty() } ?: false
        // A TCP-only resolver still needs the honesty, AAAA and TXT checks.
        fun followup(name: String, type: Int, ednsSize: Int = 0): DnsWire.Answer? = runCatching {
            val q = DnsWire.query(rnd.nextInt(65536), name, type, ednsSize)
            if (normal != null) completeAnswer(
                { runCatching { udp(network, server, q) }.getOrNull() },
                { tcp(network, server, q) }
            ) else if (tcpOk) tcp(network, server, q) else null
        }.getOrNull()
        val nonce = "mx" + java.lang.Long.toHexString(rnd.nextLong()).take(12)
        val random = followup("$nonce.$NX_ZONE", DnsWire.TYPE_A)
        // Keep UDP-size evidence separate: a TCP fallback must never count as an EDNS UDP pass.
        val edns = if (normal != null) runCatching {
            udp(network, server, DnsWire.query(rnd.nextInt(65536), PROBE_NAME, DnsWire.TYPE_A, 1232))
        }.getOrNull() else null
        val aaaa = followup(PROBE_NAME, DnsWire.TYPE_AAAA)
        val txt = followup(NX_ZONE, DnsWire.TYPE_TXT, 1232)
        val (udpBlock, hijack) = judge(normal, random)
        val tcpBlock = judge(tcpAnswer, null).first
        val blockPage = if (udpBlock == true || tcpBlock == true) true else udpBlock ?: tcpBlock
        return Result(c.label, c.address, c.origin, networkKey, udp = normal?.let { it.rcode == 0 && !it.truncated && it.addresses.isNotEmpty() } ?: false, tcp = tcpOk, rttMs = rtt, blockPage = blockPage,
            nxdomainHijack = hijack, ednsSize = edns?.let { if (!it.truncated && it.rcode == 0 && it.addresses.isNotEmpty()) 1232 else null }, measuredAt = now, expiresAt = now + TTL_MS,
            aaaa = aaaa?.let { it.rcode == 0 && !it.truncated && it.types.contains(DnsWire.TYPE_AAAA) }, txt = txt?.let { it.rcode == 0 && !it.truncated && it.types.contains(DnsWire.TYPE_TXT) },
            truncated = listOfNotNull(edns, txt).takeIf { it.isNotEmpty() }?.any { it.truncated })
    }

    /** [count] small A queries; each socket operation has a bounded timeout. */
    fun burst(network: Network?, r: Result, count: Int = 10, gapMs: Long = 150, now: Long = System.currentTimeMillis()): Burst {
        require(count in 1..20 && gapMs in 0L..2000L) { "Probe burst exceeds limits" }
        val server = InetAddress.getByName(r.address)
        val rnd = SecureRandom()
        val tcpOnly = r.udp != true && r.tcp == true
        var usedTcp = tcpOnly
        val rtts = (1..count).map {
            val t0 = System.nanoTime()
            val ok = runCatching {
                val query = DnsWire.query(rnd.nextInt(65536), PROBE_NAME, DnsWire.TYPE_A)
                val answer = if (tcpOnly) tcp(network, server, query, BURST_TIMEOUT_MS)
                    else completeAnswer(
                        { runCatching { udp(network, server, query, BURST_TIMEOUT_MS) }.getOrNull() },
                        { if (r.tcp == true) { usedTcp = true; tcp(network, server, query, BURST_TIMEOUT_MS) } else null }
                    )
                answer
                    ?.let { it.rcode == 0 && !it.truncated && it.addresses.any { a -> !com.example.vpn.EndpointResolver.isBlockedAnswer(a) } } == true
            }.getOrDefault(false)
            val rtt = if (ok) (System.nanoTime() - t0) / 1_000_000 else null
            Thread.sleep(gapMs)
            rtt
        }
        return burstOf(r.label, rtts, now).copy(transport = if (tcpOnly) "TCP" else if (usedTcp) "UDP/TCP" else "UDP")
    }

    /** Best first: usable, then reliability, then speed. */
    fun rank(results: List<Result>): List<Result> =
        results.sortedWith(compareByDescending<Result> { it.usable }.thenByDescending { it.reliability }.thenBy { it.rttMs ?: Long.MAX_VALUE })

    private fun udp(network: Network?, server: InetAddress, query: ByteArray, timeoutMs: Int = TIMEOUT_MS): DnsWire.Answer? {
        DatagramSocket().use { s ->
            network?.bindSocket(s)
            s.soTimeout = timeoutMs
            s.connect(server, 53)
            s.send(DatagramPacket(query, query.size))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            return DnsWire.response(query, buf, p.length)
        }
    }

    private fun tcp(network: Network?, server: InetAddress, query: ByteArray, timeoutMs: Int = TIMEOUT_MS): DnsWire.Answer? {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.use { s ->
            s.connect(InetSocketAddress(server, 53), timeoutMs)
            s.soTimeout = timeoutMs
            val out = DataOutputStream(s.getOutputStream())
            out.writeShort(query.size); out.write(query); out.flush()
            val input = DataInputStream(s.getInputStream())
            val len = input.readUnsignedShort()
            val buf = ByteArray(len)
            input.readFully(buf)
            return DnsWire.response(query, buf, len)
        }
    }
}
