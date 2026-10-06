package com.example.vpn.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Measures [NetworkCapabilityProfile] on the phone's own network (never through the VPN). Each probe
 * is a single small request with a short time limit; a probe that cannot run leaves its field null
 * ("not measured") rather than guessing. QUIC, ECH and upload limits are not probed here.
 */
object NetworkCapabilityDetector {
    private const val TIMEOUT_MS = 4000

    @Volatile var last: NetworkCapabilityProfile? = null
        private set

    @Suppress("DEPRECATION")
    private fun physical(cm: ConnectivityManager): Network? = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
    }

    /** Measures now; returns null when there is no physical network. Runs off the main thread. */
    suspend fun detect(context: Context): NetworkCapabilityProfile? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@withContext null
        val network = physical(cm) ?: return@withContext null
        val caps = cm.getNetworkCapabilities(network)
        val link = cm.getLinkProperties(network)
        val addresses = link?.linkAddresses.orEmpty().map { it.address }
        val transport = when {
            caps == null -> "other"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val carrier = if (transport == "cellular") runCatching {
            context.getSystemService(TelephonyManager::class.java)?.networkOperator?.takeIf { it.length in 5..6 && it.all(Char::isDigit) }
        }.getOrNull() else null
        coroutineScope {
            val dns = async { probe { network.getAllByName("www.google.com").isNotEmpty() } }
            val tcp = async { probe { tcpConnect(network, InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), 443) } }
            val tls = async { tlsProbe(network, "www.cloudflare.com") }
            val udp = async { probe { udpDns(network) } }
            val tlsResult = tls.await()
            NetworkCapabilityProfile(
                transport = transport,
                ipv4Available = addresses.any { it is Inet4Address },
                ipv6Available = addresses.any { it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress },
                udpAvailable = udp.await(),
                tcpAvailable = tcp.await(),
                tlsAvailable = tlsResult?.first,
                http2Available = tlsResult?.second,
                quicAvailable = null,
                cloudflareReachable = tlsResult?.first,
                echCapable = null,
                uploadConstrained = null,
                dnsWorking = dns.await(),
                meteredNetwork = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
                carrierCode = carrier,
                measuredAt = System.currentTimeMillis()
            ).also { last = it }
        }
    }

    private inline fun probe(block: () -> Boolean): Boolean? = try {
        block()
    } catch (_: java.io.IOException) {
        false
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        false
    }

    private fun tcpConnect(network: Network, address: InetAddress, port: Int): Boolean =
        network.socketFactory.createSocket().use { s ->
            s.connect(InetSocketAddress(address, port), TIMEOUT_MS)
            s.isConnected
        }

    /** (handshake completed, HTTP/2 offered by ALPN) or null when the probe could not run. */
    private fun tlsProbe(network: Network, host: String): Pair<Boolean, Boolean?>? = try {
        val address = network.getAllByName(host).first()
        val raw = network.socketFactory.createSocket()
        raw.connect(InetSocketAddress(address, 443), TIMEOUT_MS)
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
        ssl.use {
            it.soTimeout = TIMEOUT_MS
            val params = it.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            params.endpointIdentificationAlgorithm = "HTTPS"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) params.applicationProtocols = arrayOf("h2", "http/1.1")
            it.sslParameters = params
            it.startHandshake()
            val h2 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.applicationProtocol == "h2" else null
            true to h2
        }
    } catch (_: java.net.UnknownHostException) {
        null
    } catch (_: Exception) {
        false to null
    }

    /** A DNS query for "example.com" over UDP to 1.1.1.1:53; true when any answer comes back. */
    private fun udpDns(network: Network): Boolean {
        val query = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0x00, 0x01, 0x00, 0x01
        )
        java.net.DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = TIMEOUT_MS
            val server = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
            socket.send(DatagramPacket(query, query.size, server, 53))
            val buffer = ByteArray(512)
            val answer = DatagramPacket(buffer, buffer.size)
            socket.receive(answer)
            return answer.length > 12 && buffer[0] == 0x12.toByte() && buffer[1] == 0x34.toByte()
        }
    }
}
