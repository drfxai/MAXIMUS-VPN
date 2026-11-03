package com.example.vpn.connections

import com.example.vpn.verify.Socks5
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Real, bounded DNS-over-HTTPS and authenticated HTTPS checks through the selected engine. */
object AccessProbe {
    data class Result(val dns: Boolean = false, val tls: Boolean = false, val traffic: Boolean = false,
        val latencyMs: Long? = null, val failure: String? = null)
    fun check(endpoint: Socks5.Endpoint): Result {
        var dns = false
        var tls = false
        val start = System.nanoTime()
        return try {
            val answer = request(endpoint, "1.1.1.1", "/dns-query?name=example.com&type=A", "application/dns-json") { tls = true }
            val json = JSONObject(answer)
            dns = json.optInt("Status", -1) == 0 && (json.optJSONArray("Answer")?.length() ?: 0) > 0
            check(dns) { "DNS response rejected" }
            val body = request(endpoint, "www.cloudflare.com", "/cdn-cgi/trace", "text/plain") { tls = true }
            check(body.lineSequence().any { it.startsWith("ip=") }) { "Unexpected HTTPS response" }
            Result(dns, tls, true, (System.nanoTime() - start) / 1_000_000)
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            // Fixed categories only: never retain destination responses or WARP identities.
            Result(dns, tls, false, failure = when(e) {
                is javax.net.ssl.SSLException -> "TLS certificate or handshake failed"
                is java.net.SocketTimeoutException -> "Access check timed out"
                else -> "No verified response through this path"
            })
        }
    }
    private fun request(endpoint: Socks5.Endpoint, host: String, path: String, accept: String, onTls: () -> Unit): String {
        val raw = Socks5.connect(endpoint, host, 443, 8_000)
        try {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            ssl.use {
                it.soTimeout = 8_000
                it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                it.startHandshake(); onTls()
                it.outputStream.write("GET $path HTTP/1.1\r\nHost: $host\r\nAccept: $accept\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                it.outputStream.flush()
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                while (bytes.size() < 32_768) {
                    val n = it.inputStream.read(buffer, 0, minOf(buffer.size, 32_768 - bytes.size()))
                    if (n < 0) break
                    bytes.write(buffer, 0, n)
                }
                val response = bytes.toString("UTF-8")
                check(response.startsWith("HTTP/1.1 200 ") || response.startsWith("HTTP/1.0 200 "))
                val split = response.indexOf("\r\n\r\n")
                check(split >= 0)
                val head = response.substring(0, split)
                val body = response.substring(split + 4)
                return if (head.contains("transfer-encoding: chunked", true)) decodeChunks(body) else body
            }
        } finally { raw.close() }
    }
    internal fun decodeChunks(body: String): String {
        val result = StringBuilder()
        var at = 0
        while (true) {
            val end = body.indexOf("\r\n", at); require(end >= at)
            val size = body.substring(at, end).substringBefore(';').toInt(16)
            require(size >= 0 && size <= 32_768)
            if (size == 0) return result.toString()
            at = end + 2; require(at + size + 2 <= body.length)
            result.append(body, at, at + size); at += size + 2
        }
    }
}
