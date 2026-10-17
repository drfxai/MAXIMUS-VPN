package com.example.vpn.verify

import android.content.Context
import com.example.vpn.diagnostics.LiveTunnelProbe
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/** The two ways a check reaches the internet along the current route (see [ProbePath]). */
object RouteDialers {
    /** Bound to Android's VPN network: names resolve and connections go through the tunnel. */
    fun vpn(context: Context): Dialer = Dialer { host, port, timeoutMs ->
        val network = LiveTunnelProbe.vpnNetwork(context)
        val addresses = network.getAllByName(host).take(2)
        if (addresses.isEmpty()) throw UnknownHostException(host)
        var last: IOException? = null
        for (address in addresses) {
            val socket = network.socketFactory.createSocket()
            try {
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                return@Dialer socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                last = e
            }
        }
        throw last ?: IOException("No address for $host")
    }

    /** Through an engine program's local SOCKS5 port; the engine resolves the name on its side. */
    fun engine(endpoint: Socks5.Endpoint): Dialer = Dialer { host, port, timeoutMs ->
        Socks5.connect(endpoint, host, port, timeoutMs)
    }

}
