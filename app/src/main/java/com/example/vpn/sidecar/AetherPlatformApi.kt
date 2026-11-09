package com.example.vpn.sidecar

import com.example.vpn.verify.Dialer
import com.example.vpn.verify.Socks5
import com.example.vpn.verify.TrafficCheck
import com.example.xray.XrayLogManager
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Account HTTPS over Android's authenticated TLS stack and the verified OOB SOCKS path.
 * A session token gates the loopback inlet. This carries account calls only, never VPN traffic.
 */
internal class AetherPlatformApi(
    private val endpoint: Socks5.Endpoint,
    private val addressOverride: String = "",
    private val exchange: ((String, String, String, Map<String, String>, ByteArray?) -> TrafficCheck.Response)? = null
) : Closeable {
    val token = Sidecars.randomToken(32)
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val address = "127.0.0.1:${listener.localPort}"
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val outbound = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    private val pool = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
        { job -> Thread(job, "aether-account-tls").apply { isDaemon = true } })
    init {
        Thread({
            while (!closed) {
                val client = try { listener.accept() } catch (_: Exception) { break }
                clients.add(client)
                try { pool.execute { client.use { serve(it) }; clients.remove(client) } }
                catch (_: Exception) { clients.remove(client); client.close() }
            }
        }, "aether-account-inlet").apply { isDaemon = true; start() }
    }
    private fun serve(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val first = line(input).split(' ')
            require(first.size == 3 && first[2] == "HTTP/1.1")
            val method = first[0]; val path = first[1]
            require(method in setOf("GET", "POST", "PATCH") && path.matches(Regex("/v[0-9A-Za-z]+/reg(?:/[A-Za-z0-9_-]+)?")))
            val headers = linkedMapOf<String, String>(); var bytes = 0
            while (true) {
                val row = line(input); if (row.isEmpty()) break
                bytes += row.length; require(bytes <= 16384)
                val colon = row.indexOf(':'); require(colon > 0)
                val key = row.substring(0, colon).lowercase(); val value = row.substring(colon + 1).trim()
                require(key.matches(Regex("[a-z0-9-]+")) && key !in headers)
                headers[key] = value
            }
            require(MessageDigest.isEqual(headers.remove("x-maximus-api-token").orEmpty().toByteArray(), token.toByteArray()))
            val host = headers.remove("host"); require(host in HOSTS)
            require("transfer-encoding" !in headers)
            val size = headers.remove("content-length")?.toInt() ?: 0; require(size in 0..131072)
            val body = if (size > 0) ByteArray(size).also { data ->
                var read = 0; while (read < size) { val n = input.read(data, read, size - read); require(n > 0); read += n }
            } else null
            headers.keys.removeAll(setOf("connection", "accept-encoding", "cache-control"))
            var upstream: Socket? = null
            val response = try {
                exchange?.invoke(host!!, path, method, headers, body) ?: TrafficCheck(
                    Dialer { target, port, timeout -> Socks5.connect(endpoint, addressOverride.ifBlank { target }, port, timeout).also {
                        upstream = it; outbound.add(it); if (closed) it.close()
                    } }, stepTimeoutMs = 15_000).exchange(TrafficCheck.Target(host!!, 443, path), method, body, true, headers)
            } finally { upstream?.let { outbound.remove(it) } }
            val payload = response.body?.toByteArray() ?: byteArrayOf()
            require(payload.size <= 262144)
            val retry = response.headers["retry-after"]?.toLongOrNull()?.coerceIn(0, 30)
            val head = "HTTP/1.1 ${response.status} Account\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\n" +
                (retry?.let { "Retry-After: $it\r\n" } ?: "") + "Connection: close\r\n\r\n"
            client.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
        } catch (e: Exception) {
            // Never print account request/response bodies, bearer tokens or keys.
            XrayLogManager.i("BUILTIN", "aether/api · Android account HTTPS failed (${com.example.vpn.diagnostics.FailureStage.of(e.cause ?: e).name}); registration unverified")
            runCatching { client.getOutputStream().write("HTTP/1.1 502 Account unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
        }
    }
    override fun close() {
        closed = true; listener.close(); clients.forEach { runCatching { it.close() } }; outbound.forEach { runCatching { it.close() } }; pool.shutdownNow()
    }
    companion object {
        val HOSTS = setOf("api.devices.cloudflare.com", "api.cloudflareclient.com")
        private fun line(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read(); require(b >= 0 && out.size() < 8192)
                if (b == 10) break
                out.write(b)
            }
            val row = out.toString("US-ASCII"); require(row.endsWith('\r'))
            return row.dropLast(1).also { require(it.none { c -> c < ' ' && c != '\t' }) }
        }
    }
}
