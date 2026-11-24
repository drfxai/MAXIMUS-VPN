package com.example.vpn.verify

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Sockets that reach their target through an engine program's local SOCKS5 port ([Socks5]), for an HTTP client
 * (OkHttp) used while this app is left out of its own VPN: binding to the VPN network is refused then, so the engine's
 * port is the only way through the tunnel. Names are never looked up on the phone's network: [remoteDns] hands the
 * client a placeholder address that carries the host name, and the engine resolves it on its side. TLS runs on top
 * of these sockets as usual (certificate and host name checks unchanged).
 */
class Socks5SocketFactory(private val endpoint: Socks5.Endpoint, private val handshakeTimeoutMs: Int = 20_000) : SocketFactory() {
    override fun createSocket(): Socket = Socks5Socket(endpoint, handshakeTimeoutMs)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port), handshakeTimeoutMs) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port), handshakeTimeoutMs) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket(address, port)

    /** Opens the stream to the engine's port on connect, then asks it for the target by name. */
    class Socks5Socket(private val endpoint: Socks5.Endpoint, private val handshakeTimeoutMs: Int) : Socket() {
        override fun connect(target: SocketAddress, timeout: Int) {
            val address = target as? InetSocketAddress ?: throw IllegalArgumentException("Unsupported address")
            super.connect(InetSocketAddress(endpoint.host, endpoint.port), timeout)
            val previous = soTimeout
            try {
                soTimeout = if (timeout > 0) timeout else handshakeTimeoutMs
                Socks5.handshake(this, endpoint, targetName(address), address.port)
                soTimeout = previous
            } catch (e: Exception) {
                runCatching { close() }
                throw e
            }
        }

        override fun connect(target: SocketAddress) = connect(target, handshakeTimeoutMs)
    }

    companion object {
        /** The name to ask the engine for: the host name when there is one, else the literal address. Pure. */
        fun targetName(address: InetSocketAddress): String = address.hostString

        /**
         * For OkHttp's `dns`: one placeholder address per name (0.0.0.0, never dialed) that keeps [host], so the socket
         * sends the name to the engine instead of an address looked up outside the tunnel.
         */
        fun remoteDns(host: String): List<InetAddress> = listOf(InetAddress.getByAddress(host, ByteArray(4)))
    }
}
