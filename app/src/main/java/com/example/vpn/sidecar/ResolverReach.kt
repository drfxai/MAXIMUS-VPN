package com.example.vpn.sidecar

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * One ordinary DNS question (an A record for the tunnel domain) to each resolver of a DNS tunnel, sent from
 * the app's own process before the engine starts. The engine runs under the same app and outside the VPN
 * like this check, so a resolver this check cannot even send to is one the engine cannot reach either.
 * The CottenDNS engine reports that case only as "UPLOAD_MTU value=0" for every resolver.
 */
object ResolverReach {
    sealed class Outcome {
        data class Answered(val ms: Long, val rcode: Int) : Outcome()
        /** Sent, but nothing came back in time (dropped or filtered on the way). */
        object Silent : Outcome()
        /** The resolver's host said its UDP port 53 is closed (the engine may still try TCP/53). */
        object Closed : Outcome()
        /** The phone itself refused to send it: no route, no permission, no network for this app. */
        data class Blocked(val reason: String) : Outcome()
    }

    /** "1.1.1.1:53" or "[2606:4700::1111]:53", as [CottenDnsSidecar.resolver] writes them. */
    internal fun address(resolver: String): InetSocketAddress {
        val host = if (resolver.startsWith('[')) resolver.substringAfter('[').substringBefore(']') else resolver.substringBeforeLast(':')
        val port = resolver.substringAfterLast(':').toInt()
        return InetSocketAddress(java.net.InetAddress.getByName(host), port)
    }

    /** A recursive A query for [name]. */
    internal fun query(id: Int, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        name.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Invalid DNS name" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }

    fun check(resolver: String, name: String, timeoutMs: Int = 3_000, open: () -> DatagramSocket = { DatagramSocket() }): Outcome {
        val id = Random.nextInt(0, 0x10000)
        val started = System.currentTimeMillis()
        return try {
            open().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(address(resolver))
                val q = query(id, name)
                socket.send(DatagramPacket(q, q.size))
                val buf = ByteArray(1500)
                // Only the answer to this question counts (same id, response bit set); a few strays are skipped.
                repeat(4) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (packet.length >= 12 && ((buf[0].toInt() and 0xff) shl 8 or (buf[1].toInt() and 0xff)) == id &&
                        buf[2].toInt() and 0x80 != 0) {
                        return Outcome.Answered(System.currentTimeMillis() - started, buf[3].toInt() and 0x0f)
                    }
                }
                Outcome.Silent
            }
        } catch (_: SocketTimeoutException) {
            Outcome.Silent
        } catch (_: PortUnreachableException) {
            Outcome.Closed
        } catch (e: IOException) {
            Outcome.Blocked(e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            Outcome.Blocked(e.message ?: "permission denied")
        }
    }

    /** The log line for one result. */
    fun describe(resolver: String, outcome: Outcome): String = when (outcome) {
        is Outcome.Answered -> "Resolver $resolver answered a plain DNS question in ${outcome.ms} ms" +
            if (outcome.rcode == 0) "" else " (DNS error code ${outcome.rcode})"
        Outcome.Silent -> "Resolver $resolver did not answer a plain DNS question in time"
        Outcome.Closed -> "Resolver $resolver refused UDP port 53"
        is Outcome.Blocked -> "Resolver $resolver could not be reached from this phone: ${outcome.reason}"
    }
}
