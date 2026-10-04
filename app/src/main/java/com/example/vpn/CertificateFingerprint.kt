package com.example.vpn

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Reads the SHA-256 of a TLS server's leaf certificate, in the hex form Xray's
 * pinnedPeerCertSha256 expects.
 *
 * The trust manager records the chain the server presents and then rejects it, so the
 * handshake always aborts: nothing is ever sent over a connection that was not verified.
 */
object CertificateFingerprint {
    private class Captured(val chain: Array<X509Certificate>) : CertificateException("captured")

    private object CapturingTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client certificates are not accepted")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            throw Captured(chain)

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun sha256Hex(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /**
     * Connects to [address]:[port], sending [sni] (falls back to [address]) as the server name,
     * and returns the leaf certificate's SHA-256. Blocking; call it off the main thread.
     */
    fun fetch(
        address: String,
        port: Int,
        sni: String = "",
        timeoutMs: Int = 8000,
        protect: (Socket) -> Unit = {}
    ): String {
        require(address.isNotBlank()) { "Address is empty" }
        require(port in 1..65535) { "Port $port is out of range" }
        val serverName = sni.trim().ifBlank { address.trim() }.removePrefix("[").removeSuffix("]")
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(CapturingTrustManager), null)

        val raw = Socket()
        try {
            protect(raw)
            raw.connect(InetSocketAddress(address.trim().removePrefix("[").removeSuffix("]"), port), timeoutMs)
            raw.soTimeout = timeoutMs
            val socket = context.socketFactory.createSocket(raw, serverName, port, true) as SSLSocket
            socket.use {
                val params = it.sslParameters
                if (serverName.any { c -> c.isLetter() } && !serverName.contains(':')) {
                    params.serverNames = listOf(SNIHostName(serverName))
                }
                it.sslParameters = params
                try {
                    it.startHandshake()
                } catch (e: Exception) {
                    val captured = generateSequence<Throwable>(e) { t -> t.cause }.filterIsInstance<Captured>().firstOrNull()
                    if (captured != null && captured.chain.isNotEmpty()) return sha256Hex(captured.chain[0])
                    throw e
                }
            }
        } finally {
            runCatching { raw.close() }
        }
        error("The server did not present a certificate")
    }
}
