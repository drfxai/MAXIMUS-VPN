package com.example.vpn.tunnel

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vless.VlessHeader
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** DNS has one explicit resolver and no direct-network fallback. */
internal object ProxyDnsTransport {
    private const val LIMIT = 65535

    fun exchange(profile: VlessProfile, resolver: String, query: ByteArray,
                 protect: (Socket) -> Boolean): ByteArray {
        require(query.size in 12..LIMIT)
        val doh = resolver.startsWith("https://")
        val uri = if (doh) URI(resolver) else null
        val host = uri?.host ?: resolver
        require(host.isNotBlank()) { "Missing DNS resolver" }
        require(uri == null || (uri.userInfo == null && uri.fragment == null))
        val port = if (doh) uri!!.port.takeIf { it > 0 } ?: 443 else 53
        // Endpoint bootstrap must not resolve the proxy through the ISP.
        require(isLiteralAddress(profile.address)) { "Proxy endpoint needs a literal IP for private DNS bootstrap" }
        require(profile.security.isBlank() || profile.security.equals("none", true)) {
            "TLS proxy profiles require the native engine"
        }
        require(profile.transport.isBlank() || profile.transport.equals("tcp", true)) { "DNS proxy requires TCP transport" }
        Socket().use { socket ->
            check(protect(socket)) { "VPN socket protection failed" }
            socket.soTimeout = 10000
            socket.connect(InetSocketAddress(profile.address, profile.port), 10000)
            val rawInput = socket.getInputStream()
            val input = if (profile.protocolType == ProtocolType.VLESS) vlessPayload(rawInput) else rawInput
            val output = socket.getOutputStream()
            when (profile.protocolType) {
                ProtocolType.VLESS -> {
                    output.write(VlessHeader.encodeRequest(VlessHeader.uuidToBytes(profile.uuid),
                        VlessHeader.COMMAND_TCP, port, host))
                    output.flush()

                }
                ProtocolType.SOCKS5 -> UpstreamProtocol.performSocks5Connect(input, output, host, port)
                ProtocolType.HTTP -> {
                    output.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray())
                    output.flush()
                    UpstreamProtocol.validateHttpConnectResponse(input)
                }
                else -> error("Unsupported DNS proxy protocol")
            }
            if (!doh) {
                require(isLiteralAddress(host)) { "Plain DNS resolver must be a literal IP" }
                output.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()))
                output.write(query)
                output.flush()
                val data = DataInputStream(input)
                val size = data.readUnsignedShort()
                require(size in 12..LIMIT)
                return ByteArray(size).also(data::readFully)
            }
            val tls = ProxyTlsChannel(input, output, host, port)
            val path = uri!!.rawPath.ifBlank { "/dns-query" } + (uri.rawQuery?.let { "?$it" } ?: "")
            val request = "POST $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/dns-message\r\nAccept: application/dns-message\r\nAccept-Encoding: identity\r\nContent-Length: ${query.size}\r\nConnection: close\r\n\r\n"
            tls.write(request.toByteArray(Charsets.US_ASCII))
            tls.write(query)
            return readHttpResponse(tls.input)
        }
    }

    /** Strip the VLESS response once, on the first read after the first payload write.
     * Reading it before emitting TLS ClientHello can deadlock a server waiting for data. */
    internal fun vlessPayload(input: InputStream): InputStream = object : InputStream() {
        private var ready = false
        private fun prepare() {
            if (ready) return
            val version = input.read()
            val extras = input.read()
            check(version == 0 && extras >= 0) { "Invalid VLESS response" }
            DataInputStream(input).readFully(ByteArray(extras))
            ready = true
        }
        override fun read(): Int { prepare(); return input.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            prepare()
            return input.read(bytes, offset, length)
        }
    }

    internal fun readHttpResponse(input: InputStream): ByteArray {
        val status = line(input)
        require(status.matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) { "DoH server rejected query" }
        val headers = mutableMapOf<String, String>()
        var total = status.length
        while (true) {
            val value = line(input)
            total += value.length
            require(total <= 16384) { "DoH headers exceeded limit" }
            if (value.isEmpty()) break
            val separator = value.indexOf(':')
            require(separator > 0)
            val key = value.substring(0, separator).lowercase()
            require(key !in headers) { "Duplicate DoH header" }
            headers[key] = value.substring(separator + 1).trim()
        }
        require(headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() == "application/dns-message")
        require(headers["content-encoding"] == null || headers["content-encoding"] == "identity")
        val data = DataInputStream(input)
        if (headers["transfer-encoding"]?.lowercase() == "chunked") {
            require(headers["content-length"] == null)
            val body = java.io.ByteArrayOutputStream()
            while (true) {
                val size = line(input).substringBefore(';').toInt(16)
                require(size >= 0 && body.size() + size <= LIMIT)
                if (size == 0) break
                body.write(ByteArray(size).also(data::readFully))
                require(line(input).isEmpty())
            }
            return body.toByteArray().also { require(it.size >= 12) }
        }
        require(headers["transfer-encoding"] == null)
        val size = headers["content-length"]?.toIntOrNull() ?: error("DoH response requires a bounded length")
        require(size in 12..LIMIT)
        return ByteArray(size).also(data::readFully)
    }

    private fun line(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val next = input.read()
            require(next >= 0) { "Truncated DoH response" }
            if (next == 10) {
                val value = bytes.toByteArray()
                require(value.isNotEmpty() && value.last() == 13.toByte())
                return String(value, 0, value.size - 1, Charsets.US_ASCII)
            }
            bytes.write(next)
        }
        error("DoH header line exceeded limit")
    }

    internal fun isLiteralAddress(host: String): Boolean =
        host.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) &&
            host.split('.').all { it.toInt() in 0..255 } ||
            host.contains(':') && host.matches(Regex("[0-9a-fA-F:]+"))
}
