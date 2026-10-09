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
    data class Answer(val id: Int, val rcode: Int, val truncated: Boolean, val answers: Int, val addresses: List<InetAddress>, val size: Int)

    const val TYPE_A = 1
    const val TYPE_TXT = 16
    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3

    /** A recursive query for [name]/[type]; with [ednsSize] > 0 an OPT record advertises that UDP size. */
    fun query(id: Int, name: String, type: Int, ednsSize: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(id); d.writeShort(0x0100); d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(if (ednsSize > 0) 1 else 0)
        name.trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
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
        if (length < 12) return null
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
        repeat(an) {
            if (i >= length) return@repeat
            skipName()
            if (i + 10 > length) return@repeat
            val type = u16(i); val rdLen = u16(i + 8)
            i += 10
            if (type == TYPE_A && rdLen == 4 && i + 4 <= length) addresses += InetAddress.getByAddress(data.copyOfRange(i, i + 4))
            i += rdLen
        }
        return Answer(u16(0), flags and 0x000F, (flags and 0x0200) != 0, an, addresses, length)
    }
}

/**
 * Bounded, network-specific evaluation of DNS resolvers. Each resolver gets at most four small queries:
 * a normal name, a random never-existing name (NXDOMAIN hijacking), an EDNS query (large responses),
 * and the same question over TCP. Results expire; nothing learnt on one network is used on another.
 */
object ResolverIntelligence {

    data class Candidate(val label: String, val address: String, val origin: String)

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
        val expiresAt: Long
    ) {
        /** Fit to carry a DNS tunnel: answers honestly over UDP or TCP. */
        val usable: Boolean get() = (udp == true || tcp == true) && blockPage != true && nxdomainHijack != true
        val reliability: Double get() = listOf(udp, tcp, blockPage?.not(), nxdomainHijack?.not()).filterNotNull().let { if (it.isEmpty()) 0.0 else it.count { v -> v }.toDouble() / it.size }

        fun toJson(): JSONObject = JSONObject().put("l", label).put("a", address).put("o", origin).put("n", networkKey)
            .put("u", udp ?: JSONObject.NULL).put("t", tcp ?: JSONObject.NULL).put("r", rttMs ?: JSONObject.NULL)
            .put("b", blockPage ?: JSONObject.NULL).put("h", nxdomainHijack ?: JSONObject.NULL).put("e", ednsSize ?: JSONObject.NULL)
            .put("m", measuredAt).put("x", expiresAt)

        companion object {
            fun fromJson(o: JSONObject): Result? = runCatching {
                fun b(k: String) = if (o.isNull(k)) null else o.getBoolean(k)
                Result(o.getString("l"), o.getString("a"), o.optString("o"), o.getString("n"), b("u"), b("t"),
                    if (o.isNull("r")) null else o.getLong("r"), b("b"), b("h"), if (o.isNull("e")) null else o.getInt("e"), o.getLong("m"), o.getLong("x"))
            }.getOrNull()
        }
    }

    const val TTL_MS = 6L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 2500
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
        val blockPage = normal?.let { a -> a.addresses.isNotEmpty() && a.addresses.all { com.example.vpn.EndpointResolver.isBlockedAnswer(it) } }
        val hijack = random?.let { it.rcode == DnsWire.RCODE_NOERROR && it.addresses.isNotEmpty() }
        return blockPage to hijack
    }

    fun evaluate(network: Network?, c: Candidate, networkKey: String, now: Long = System.currentTimeMillis()): Result {
        val server = InetAddress.getByName(c.address)
        val rnd = SecureRandom()
        val started = System.nanoTime()
        val normal = runCatching { udp(network, server, DnsWire.query(rnd.nextInt(0xffff), PROBE_NAME, DnsWire.TYPE_A)) }.getOrNull()
        val rtt = normal?.let { (System.nanoTime() - started) / 1_000_000 }
        val nonce = "mx" + java.lang.Long.toHexString(rnd.nextLong()).take(12)
        val random = if (normal != null) runCatching { udp(network, server, DnsWire.query(rnd.nextInt(0xffff), "$nonce.$NX_ZONE", DnsWire.TYPE_A)) }.getOrNull() else null
        val edns = if (normal != null) runCatching { udp(network, server, DnsWire.query(rnd.nextInt(0xffff), PROBE_NAME, DnsWire.TYPE_A, 1232)) }.getOrNull() else null
        val tcp = runCatching { tcp(network, server, DnsWire.query(rnd.nextInt(0xffff), PROBE_NAME, DnsWire.TYPE_A)) != null }.getOrDefault(false)
        val (blockPage, hijack) = judge(normal, random)
        return Result(c.label, c.address, c.origin, networkKey, udp = normal != null, tcp = tcp, rttMs = rtt, blockPage = blockPage,
            nxdomainHijack = hijack, ednsSize = edns?.let { if (!it.truncated) 1232 else null }, measuredAt = now, expiresAt = now + TTL_MS)
    }

    /** Best first: usable, then reliability, then speed. */
    fun rank(results: List<Result>): List<Result> =
        results.sortedWith(compareByDescending<Result> { it.usable }.thenByDescending { it.reliability }.thenBy { it.rttMs ?: Long.MAX_VALUE })

    private fun udp(network: Network?, server: InetAddress, query: ByteArray): DnsWire.Answer? {
        DatagramSocket().use { s ->
            network?.bindSocket(s)
            s.soTimeout = TIMEOUT_MS
            s.send(DatagramPacket(query, query.size, server, 53))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            return DnsWire.parse(buf, p.length)
        }
    }

    private fun tcp(network: Network?, server: InetAddress, query: ByteArray): DnsWire.Answer? {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.use { s ->
            s.connect(InetSocketAddress(server, 53), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            val out = DataOutputStream(s.getOutputStream())
            out.writeShort(query.size); out.write(query); out.flush()
            val input = DataInputStream(s.getInputStream())
            val len = input.readUnsignedShort()
            val buf = ByteArray(len)
            input.readFully(buf)
            return DnsWire.parse(buf, len)
        }
    }
}
