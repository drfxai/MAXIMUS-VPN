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
        val addresses = try {
            BoundedDnsLookup.resolve(timeoutMs) { network.getAllByName(host) }.take(2)
        } catch (e: IOException) {
            throw refusalOr(e)
        }
        if (addresses.isEmpty()) throw UnknownHostException(host)
        var last: IOException? = null
        for (address in addresses) {
            // createSocket() binds the socket to the VPN network at once: an app excluded from its own VPN
            // gets EPERM here, which is "not testable", never a failed route (TunCheckPlan).
            val socket = try { network.socketFactory.createSocket() } catch (e: IOException) { throw refusalOr(e) }
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

    /**
     * Binds one socket to the VPN network and closes it: the refusal reason when this app may not use the VPN
     * network (it is excluded from its own VPN), else null. Never throws.
     */
    fun vpnBindPreflight(context: Context): String? = TunCheckPlan.bindPreflight {
        LiveTunnelProbe.vpnNetwork(context).socketFactory.createSocket()
    }

    private fun refusalOr(e: IOException): IOException =
        if (e !is VpnBindRefusedException && TunCheckPlan.isBindRefusal(e)) VpnBindRefusedException(TunCheckPlan.reasonOf(e), e) else e

    /** Through an engine program's local SOCKS5 port; the engine resolves the name on its side. */
    fun engine(endpoint: Socks5.Endpoint): Dialer = Dialer { host, port, timeoutMs ->
        Socks5.connect(endpoint, host, port, timeoutMs)
    }

}
