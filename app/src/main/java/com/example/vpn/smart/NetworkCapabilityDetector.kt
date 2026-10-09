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
 *
 * About ten small connections per measurement, made when the network changes or the user asks; never on
 * a timer. QUIC is checked with a version-negotiation exchange (no handshake, no data) with 1.1.1.1:443.
 * International references are addressed by IP literal with their own certificate name, so
 * they measure the path abroad even when the network's DNS is tampered with.
 */
object NetworkCapabilityDetector {
    private const val TIMEOUT_MS = 4000

    /** International TLS references: (IP literal, a name its certificate covers). No DNS needed. */
    internal val INTERNATIONAL = listOf(byteArrayOf(1, 1, 1, 1) to "one.one.one.one", byteArrayOf(8, 8, 8, 8) to "dns.google")

    /** Domestic references (Iran); a TCP connection only, no request. Reachable says nothing about leaving the country. */
    internal val DOMESTIC = listOf("www.aparat.com", "www.digikala.com")

    /** A name commonly filtered by SNI, offered to the same address as a neutral name to compare the two. */
    internal const val FILTERED_SNI = "www.youtube.com"

    /** A foreign name whose system-DNS answer shows whether the network's resolver tampers with answers. */
    private const val DNS_REFERENCE = "www.google.com"

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
            val dns = async { dnsProbe(network) }
            val tcp = async { probe { tcpConnect(network, InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), 443) } }
            val tls = async { tlsProbe(network, "www.cloudflare.com") }
            val udp = async { probe { udpDns(network) } }
            val quic = async { probe { quicProbe(network) } }
            val intl = INTERNATIONAL.map { (ip, name) -> async { literalTls(network, InetAddress.getByAddress(ip), name) } }
            val sensitive = async { literalTls(network, InetAddress.getByAddress(INTERNATIONAL.first().first), FILTERED_SNI) }
            val domestic = async { domesticProbe(network) }
            val doh = async { probe { dohProbe(network) } }
            val tlsResult = tls.await()
            val dnsResult = dns.await()
            val intlResults = intl.map { it.await() }
            val measuredIntl = intlResults.filter { it != TlsOutcome.NOT_RUN }
            NetworkCapabilityProfile(
                transport = transport,
                ipv4Available = addresses.any { it is Inet4Address },
                ipv6Available = addresses.any { it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress },
                udpAvailable = udp.await(),
                tcpAvailable = tcp.await(),
                tlsAvailable = tlsResult?.first,
                http2Available = tlsResult?.second,
                quicAvailable = quic.await(),
                cloudflareReachable = tlsResult?.first,
                echCapable = null,
                uploadConstrained = null,
                dnsWorking = dnsResult?.let { it == DnsOutcome.ANSWER },
                meteredNetwork = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
                carrierCode = carrier,
                measuredAt = System.currentTimeMillis(),
                dnsManipulated = when (dnsResult) { DnsOutcome.TAMPERED -> true; DnsOutcome.ANSWER -> false; else -> null },
                dohReachable = doh.await(),
                internationalOk = measuredIntl.count { it == TlsOutcome.COMPLETED }.takeIf { measuredIntl.isNotEmpty() },
                internationalTried = measuredIntl.size.takeIf { it > 0 },
                domesticReachable = domestic.await(),
                sniFiltered = sniFiltered(intlResults.first(), sensitive.await())
            ).also { last = it }
        }
    }

    internal enum class DnsOutcome { ANSWER, TAMPERED, FAILED }

    /** ANSWER: a public address; TAMPERED: only block-page/private addresses; FAILED: no answer; null: could not run. */
    private fun dnsProbe(network: Network): DnsOutcome? = try {
        val answers = network.getAllByName(DNS_REFERENCE).toList()
        classifyDns(answers)
    } catch (_: java.net.UnknownHostException) {
        DnsOutcome.FAILED
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        DnsOutcome.FAILED
    }

    internal fun classifyDns(answers: List<InetAddress>): DnsOutcome = when {
        answers.isEmpty() -> DnsOutcome.FAILED
        answers.all { com.example.vpn.EndpointResolver.isBlockedAnswer(it) } -> DnsOutcome.TAMPERED
        else -> DnsOutcome.ANSWER
    }

    /**
     * How a TLS handshake ended. A certificate or name mismatch is still an answer from the server
     * ([TlsOutcome.SERVER_ANSWERED]); a reset, a silent drop or an early close is [TlsOutcome.INTERFERED].
     */
    internal enum class TlsOutcome { COMPLETED, SERVER_ANSWERED, INTERFERED, NOT_RUN }

    private fun literalTls(network: Network, address: InetAddress, sni: String): TlsOutcome {
        val raw = try {
            network.socketFactory.createSocket().also { it.connect(InetSocketAddress(address, 443), TIMEOUT_MS) }
        } catch (_: SecurityException) {
            return TlsOutcome.NOT_RUN
        } catch (_: Exception) {
            return TlsOutcome.INTERFERED
        }
        return try {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni, 443, true) as SSLSocket
            ssl.use {
                it.soTimeout = TIMEOUT_MS
                val params = it.sslParameters
                params.serverNames = listOf(SNIHostName(sni))
                params.endpointIdentificationAlgorithm = "HTTPS"
                it.sslParameters = params
                it.startHandshake()
                TlsOutcome.COMPLETED
            }
        } catch (e: Exception) {
            tlsFailure(e)
        } finally {
            runCatching { raw.close() }
        }
    }

    /** A certificate complaint means the server spoke TLS back; anything else is treated as interference. */
    internal fun tlsFailure(e: Throwable): TlsOutcome {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.security.cert.CertificateException || cause is javax.net.ssl.SSLPeerUnverifiedException) return TlsOutcome.SERVER_ANSWERED
            cause = cause.cause
        }
        val text = e.message.orEmpty()
        return if (text.contains("certificate", true) || text.contains("hostname", true) || text.contains("trust anchor", true)) TlsOutcome.SERVER_ANSWERED
        else TlsOutcome.INTERFERED
    }

    /** True when the neutral name passed and the filtered name was cut; null when they cannot be compared. */
    internal fun sniFiltered(neutral: TlsOutcome, sensitive: TlsOutcome): Boolean? = when {
        neutral != TlsOutcome.COMPLETED || sensitive == TlsOutcome.NOT_RUN -> null
        sensitive == TlsOutcome.INTERFERED -> true
        else -> false
    }

    /** True if any domestic reference accepts TCP; null when none of their names resolved (no evidence either way). */
    private fun domesticProbe(network: Network): Boolean? {
        var resolved = false
        for (host in DOMESTIC) {
            val address = try { network.getAllByName(host).firstOrNull { !com.example.vpn.EndpointResolver.isBlockedAnswer(it) } } catch (_: Exception) { null }
                ?: continue
            resolved = true
            if (probe { tcpConnect(network, address, 443) } == true) return true
        }
        return if (resolved) false else null
    }

    /** One DNS-over-HTTPS question to an IP-literal resolver, sent over the physical network. */
    private fun dohProbe(network: Network): Boolean {
        val url = com.example.vpn.EndpointResolver.DOH_ENDPOINTS.first().format("example.com")
        return com.example.vpn.EndpointResolver.queryDoh(url) { network.openConnection(it) as java.net.HttpURLConnection } != null
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

    /**
     * QUIC reachability without a QUIC stack: a 1200-byte long-header packet with a reserved version makes
     * any QUIC server answer with a Version Negotiation packet (RFC 9000 section 6). An answer means QUIC
     * passes this network; silence means it was dropped (or UDP to 443 is blocked).
     */
    private fun quicProbe(network: Network): Boolean {
        val random = java.security.SecureRandom()
        val dcid = ByteArray(8).also(random::nextBytes)
        val scid = ByteArray(8).also(random::nextBytes)
        val packet = quicVersionProbe(dcid, scid)
        java.net.DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = TIMEOUT_MS
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByAddress(INTERNATIONAL.first().first), 443))
            val buffer = ByteArray(1500)
            val answer = DatagramPacket(buffer, buffer.size)
            socket.receive(answer)
            return isVersionNegotiation(buffer, answer.length, scid)
        }
    }

    /** Reserved version 0x?a?a?a?a: servers must not accept it, so they reply with Version Negotiation. */
    internal fun quicVersionProbe(dcid: ByteArray, scid: ByteArray): ByteArray {
        val out = ByteArray(1200)
        var i = 0
        out[i++] = 0xC0.toByte()
        byteArrayOf(0x1a, 0x2a, 0x3a, 0x4a).forEach { out[i++] = it }
        out[i++] = dcid.size.toByte(); dcid.forEach { out[i++] = it }
        out[i++] = scid.size.toByte(); scid.forEach { out[i++] = it }
        return out
    }

    /** A Version Negotiation packet: long header, version 0, and our source connection ID echoed as its destination. */
    internal fun isVersionNegotiation(data: ByteArray, length: Int, scid: ByteArray): Boolean {
        if (length < 7 + scid.size || (data[0].toInt() and 0x80) == 0) return false
        if ((1..4).any { data[it].toInt() != 0 }) return false
        val dcidLen = data[5].toInt() and 0xff
        if (dcidLen != scid.size || length < 6 + dcidLen) return false
        return (0 until dcidLen).all { data[6 + it] == scid[it] }
    }
}
