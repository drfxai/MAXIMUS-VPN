package com.example.vpn.verify

import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal SOCKS5 client (RFC 1928, user/password per RFC 1929) for checks through an engine
 * program's local port. The target name is sent to the engine, which resolves it on its side, so the
 * check never looks the name up on the phone's network.
 */
object Socks5 {
    /** An engine program's local SOCKS5 port and its per-run credential. */
    data class Endpoint(val host: String, val port: Int, val user: String?, val pass: String?) {
        override fun toString(): String = "$host:$port" + if (user != null) " (with credential)" else ""
    }

    class Socks5Exception(message: String) : IOException(message)

    /**
     * The local SOCKS5 endpoint Xray forwards to for an engine program (see SidecarChain.xrayProfile),
     * or null when [profile] is not such a forwarding profile.
     */
    fun endpointOf(profile: VlessProfile): Endpoint? {
        if (profile.profileType != ProfileType.XRAY_JSON) return null
        val outbound = runCatching { JSONObject(profile.rawConfig) }.getOrNull()
            ?.optJSONArray("outbounds")?.optJSONObject(0) ?: return null
        if (outbound.optString("protocol") != "socks") return null
        val server = outbound.optJSONObject("settings")?.optJSONArray("servers")?.optJSONObject(0) ?: return null
        val host = server.optString("address")
        if (host != "127.0.0.1" && host != "::1" && host != "localhost") return null
        val port = server.optInt("port", -1).takeIf { it in 1..65535 } ?: return null
        val user = server.optJSONArray("users")?.optJSONObject(0)
        return Endpoint(host, port, user?.optString("user")?.takeIf { it.isNotEmpty() }, user?.optString("pass"))
    }

    fun greeting(withAuth: Boolean): ByteArray =
        if (withAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)

    fun authRequest(user: String, pass: String): ByteArray {
        val u = user.toByteArray(Charsets.UTF_8)
        val p = pass.toByteArray(Charsets.UTF_8)
        require(u.size in 1..255 && p.size <= 255) { "SOCKS credential too long" }
        return byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p
    }

    fun connectRequest(host: String, port: Int): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        require(name.size in 1..255) { "Host name too long" }
        require(port in 1..65535) { "Bad port" }
        return byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte())
    }

    /** Opens a TCP stream to [host]:[port] through [endpoint]; the returned socket is ready for TLS. */
    fun connect(endpoint: Endpoint, host: String, port: Int, timeoutMs: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            socket.soTimeout = timeoutMs
            handshake(socket, endpoint, host, port)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    internal fun handshake(socket: Socket, endpoint: Endpoint, host: String, port: Int) {
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        val auth = endpoint.user != null
        out.write(greeting(auth))
        out.flush()
        if (input.readUnsignedByte() != 5) throw Socks5Exception("Not a SOCKS5 server")
        when (val method = input.readUnsignedByte()) {
            0 -> Unit
            2 -> {
                if (!auth) throw Socks5Exception("The engine asked for a credential")
                out.write(authRequest(endpoint.user!!, endpoint.pass.orEmpty()))
                out.flush()
                input.readUnsignedByte()
                if (input.readUnsignedByte() != 0) throw Socks5Exception("The engine refused the credential")
            }
            else -> throw Socks5Exception("No acceptable SOCKS method ($method)")
        }
        out.write(connectRequest(host, port))
        out.flush()
        if (input.readUnsignedByte() != 5) throw Socks5Exception("Bad SOCKS reply")
        val reply = input.readUnsignedByte()
        input.readUnsignedByte()
        val skip = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> input.readUnsignedByte()
            else -> throw Socks5Exception("Bad SOCKS address type")
        }
        input.readFully(ByteArray(skip + 2))
        if (reply != 0) throw Socks5Exception("The engine could not reach the target (SOCKS reply $reply)")
    }
}
