package com.example.vpn.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/** Local DNS path evidence is not an internet-side resolver leak verdict. */
data class DnsPathResult(
    val completed: Boolean,
    val summary: String,
    val testedAt: Long = System.currentTimeMillis(),
    val latencyMs: Long? = null
) {
    val dnsLeakDetected: Boolean? get() = null
}

internal object DnsProbePacket {
    fun query(random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val name = "$nonce.example.com"
        val question = name.split('.').flatMap { label -> listOf(label.length.toByte()) + label.toByteArray().toList() } + byteArrayOf(0, 0, 1, 0, 1).toList()
        val id = random.nextInt(65536)
        return byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) + question.toByteArray()
    }

    fun matches(query: ByteArray, answer: ByteArray): Boolean =
        query.size > 12 && answer.size >= query.size &&
            answer[0] == query[0] && answer[1] == query[1] &&
            (answer[2].toInt() and 0xfA) == 0x80 && // response, standard query, not truncated
            (answer[3].toInt() and 15) in setOf(0, 3) &&
            answer[4] == 0.toByte() && answer[5] == 1.toByte() &&
            query.copyOfRange(12, query.size).contentEquals(answer.copyOfRange(12, query.size))
}

internal object DnsPathTest {
    suspend fun run(context: Context): DnsPathResult = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // Never use the default/underlying network as a fallback. Require our TUN address.
        val network = manager.allNetworks.firstOrNull { net ->
            manager.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                manager.getLinkProperties(net)?.linkAddresses?.any { it.address.hostAddress == "172.19.0.1" } == true
        } ?: return@withContext DnsPathResult(false, "VPN network unavailable to this app; DNS test inconclusive.")
        try {
            val query = DnsProbePacket.query()
            val start = SystemClock.elapsedRealtime()
            DatagramSocket(null).use { socket ->
                network.bindSocket(socket)
                socket.soTimeout = 8000
                socket.connect(InetAddress.getByAddress(byteArrayOf(172.toByte(), 19, 0, 2)), 53)
                socket.send(DatagramPacket(query, query.size))
                val reply = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(reply)
                check(DnsProbePacket.matches(query, reply.data.copyOfRange(reply.offset, reply.offset + reply.length)))
            }
            if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
                return@withContext DnsPathResult(false, "VPN changed during test; result discarded.")
            }
            DnsPathResult(true, "DNS response received on the VPN network. Internet-side DNS leaks remain untested.",
                latencyMs = SystemClock.elapsedRealtime() - start)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            DnsPathResult(false, "DNS path test inconclusive: no valid response on the VPN network. No direct fallback was attempted.")
        }
    }
}
