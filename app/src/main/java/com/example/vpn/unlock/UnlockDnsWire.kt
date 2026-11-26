package com.example.vpn.unlock

import com.example.vpn.dns.DnsResponse
import com.example.vpn.dns.ResolverScanner
import com.example.vpn.packet.IPv4Header
import com.example.vpn.packet.IpProtocol
import com.example.vpn.packet.PacketBuilder
import com.example.vpn.packet.UdpHeader
import com.example.vpn.sidecar.ResolverReach
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * DNS on the wire for Unlock mode: one question to an upstream server over UDP/53 with a TCP/53 fallback, on
 * sockets the VPN protects (they leave beside the tunnel), and the IPv4/UDP packets read from and written to
 * the DNS-only TUN.
 */
object UnlockDnsWire {
    /** Largest DNS message accepted from upstream over TCP (and so written back into the TUN). */
    const val MAX_MESSAGE = 4096
    private val random = SecureRandom()

    /** Puts a socket outside the VPN; false when Android refused (the socket is then not used). */
    class Protector(
        val udp: (DatagramSocket) -> Boolean = { true },
        val tcp: (Socket) -> Boolean = { true }
    )

    data class Exchange(val response: ByteArray, val ms: Long, val intercepted: Boolean, val tcp: Boolean)

    /** ANCOUNT of a DNS message (0 when too short). */
    fun answerCount(message: ByteArray): Int =
        if (message.size < 12) 0 else ((message[6].toInt() and 0xff) shl 8) or (message[7].toInt() and 0xff)

    /** True when the TC (truncated) bit is set in a DNS header. */
    fun truncated(message: ByteArray): Boolean = message.size >= 3 && (message[2].toInt() and 0x02) != 0

    /**
     * Sends [query] to [server] over UDP, then over TCP when UDP stayed silent or came back truncated. The
     * reply must match the question (id, name, type) or it is ignored.
     */
    fun exchange(server: String, query: ByteArray, timeoutMs: Int, protector: Protector): Exchange? {
        val address = runCatching { InetAddress.getByName(server) }.getOrNull() ?: return null
        val accept = { reply: ByteArray -> reply.takeIf { DnsResponse.isResponseTo(query, it) } }
        val udp = udp(address, query, timeoutMs, protector, accept)
        if (udp != null && !truncated(udp.response)) return udp
        return tcp(address, query, timeoutMs, protector, accept) ?: udp
    }

    /**
     * Sends [original] reshaped with [shape] ([UnlockDnsShaping.shape]) over UDP; over TCP only when the UDP answer
     * is truncated (a silent UDP means this shape does not work here, and the caller moves on quickly). The reply
     * comes back as an answer to [original] ([UnlockDnsShaping.restore]), so the app sees its own question and ID.
     */
    fun exchangeShaped(server: String, original: ByteArray, shape: QueryShape, timeoutMs: Int, protector: Protector): Exchange? {
        val shaped = UnlockDnsShaping.shape(original, shape) ?: return null
        val address = runCatching { InetAddress.getByName(server) }.getOrNull() ?: return null
        val accept = { reply: ByteArray -> UnlockDnsShaping.restore(original, reply) }
        val udp = udp(address, shaped, timeoutMs, protector, accept)
        if (udp == null || !truncated(udp.response)) return udp
        // A shape that sets TC in the question: many resolvers copy it into a complete answer. With answer
        // records present the answer is used as is (the session clears TC before an app sees it).
        if (UnlockDnsAnswer.setsTc(shape) && answerCount(udp.response) > 0) return udp
        return tcp(address, shaped, timeoutMs, protector, accept) ?: udp
    }

    private fun udp(server: InetAddress, query: ByteArray, timeoutMs: Int, protector: Protector,
                    accept: (ByteArray) -> ByteArray?): Exchange? {
        try {
            DatagramSocket().use { socket ->
                if (!protector.udp(socket)) return null
                socket.soTimeout = timeoutMs
                val started = System.nanoTime()
                socket.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
                val buffer = ByteArray(MAX_MESSAGE)
                val deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                // A late or foreign datagram is skipped; the socket timeout and the deadline bound the wait.
                while (System.nanoTime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val reply = accept(buffer.copyOf(packet.length))
                    if (reply != null) {
                        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        return Exchange(reply, ms, intercepted = packet.address != server, tcp = false)
                    }
                }
            }
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
        return null
    }

    private fun tcp(server: InetAddress, query: ByteArray, timeoutMs: Int, protector: Protector,
                    accept: (ByteArray) -> ByteArray?): Exchange? {
        try {
            Socket().use { socket ->
                if (!protector.tcp(socket)) return null
                val started = System.nanoTime()
                socket.connect(InetSocketAddress(server, 53), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getOutputStream().apply {
                    write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()) + query)
                    flush()
                }
                val input = DataInputStream(socket.getInputStream())
                val length = input.readUnsignedShort()
                if (length !in 12..MAX_MESSAGE) return null
                val reply = accept(ByteArray(length).also { input.readFully(it) }) ?: return null
                return Exchange(reply, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), intercepted = false, tcp = true)
            }
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
        return null
    }

    /** The real probe used by health checks and the Home badges; with [shape], every question is reshaped. */
    class Probe(private val protector: Protector = Protector(), private val shape: QueryShape? = null) : UnlockProbe {
        override fun lookup(server: String, name: String, timeoutMs: Int): Lookup? {
            val query = ResolverReach.query(random.nextInt(0x10000), name)
            val answer = (if (shape == null) exchange(server, query, timeoutMs, protector)
                else exchangeShaped(server, query, shape, minOf(timeoutMs, UnlockDnsShaping.ATTEMPT_TIMEOUT_MS), protector)) ?: return null
            val ips = ResolverScanner.aRecords(query, answer.response, answer.response.size) ?: return null
            return Lookup(ips, answer.ms, answer.intercepted)
        }

        override fun reach(ip: String, port: Int, timeoutMs: Int): Long? {
            try {
                Socket().use { socket ->
                    if (!protector.tcp(socket)) return null
                    val started = System.nanoTime()
                    socket.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                }
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
            return null
        }
    }

    /** A DNS question an app sent to the TUN's DNS address. */
    class Query(val client: ByteArray, val clientPort: Int, val dnsAddress: ByteArray, val payload: ByteArray)

    /**
     * The DNS question in an IPv4/UDP packet addressed to [dnsAddress]:53, or null for anything else (other
     * protocols or ports, fragments, malformed packets). Only these packets reach the DNS-only TUN.
     */
    fun parseQuery(packet: ByteArray, length: Int, dnsAddress: ByteArray): Query? {
        val ip = IPv4Header.parse(packet, 0, length) ?: return null
        if (ip.protocol != IpProtocol.UDP) return null
        // More-fragments flag or a fragment offset: DNS questions are never fragmented; drop.
        if (ip.flags and 0x3FFF != 0) return null
        if (!ip.dstIp.contentEquals(dnsAddress)) return null
        val udp = UdpHeader.parse(packet, ip.ihl * 4, ip.totalLength) ?: return null
        if (udp.dstPort != 53 || udp.payloadLength < 12 || udp.payloadLength > MAX_MESSAGE) return null
        val payload = packet.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        return Query(ip.srcIp, udp.srcPort, ip.dstIp, payload)
    }

    /** The IPv4/UDP packet carrying [response] back from the TUN's DNS address to the asking app. */
    fun reply(query: Query, response: ByteArray): ByteArray =
        PacketBuilder.buildUdpPacket(query.dnsAddress, query.client, 53, query.clientPort, response)

    /** A SERVFAIL answer to [question], so the asking app fails fast instead of waiting out its timeout. */
    fun servfail(question: ByteArray): ByteArray? {
        if (question.size < 12) return null
        val out = question.copyOf()
        // QR=1, keep opcode and RD; RA=1, RCODE=2. Answer/authority/additional counts zeroed (EDNS dropped).
        out[2] = ((question[2].toInt() and 0x79) or 0x80).toByte()
        out[3] = 0x82.toByte()
        for (i in 6 until 12) out[i] = 0
        // Keep only the question section; EDNS (additional) records are cut.
        val end = questionEnd(question) ?: return null
        return out.copyOf(end)
    }

    private fun questionEnd(message: ByteArray): Int? {
        val qd = ((message[4].toInt() and 0xff) shl 8) or (message[5].toInt() and 0xff)
        if (qd != 1) return null
        var p = 12
        while (true) {
            if (p >= message.size) return null
            val l = message[p].toInt() and 0xff
            if (l == 0) { p += 1; break }
            if (l and 0xc0 != 0) return null
            p += 1 + l
        }
        return (p + 4).takeIf { it <= message.size }
    }
}
