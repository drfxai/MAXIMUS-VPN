package com.example.vpn.connections

import com.example.vpn.verify.Socks5
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Real, bounded DNS-over-HTTPS and authenticated HTTPS checks through the selected engine. */
object AccessProbe {
    data class Result(val dns: Boolean? = null, val tls: Boolean = false, val traffic: Boolean = false,
        val latencyMs: Long? = null, val failure: String? = null)

    fun check(endpoint: Socks5.Endpoint, checkDns: Boolean): Result {
        var tls = false
        var traffic = false
        var latencyMs: Long? = null
        var failure: String? = null
        for ((host, path, status) in listOf(
            Triple("www.google.com", "/generate_204", 204),
            Triple("example.com", "/", 200)
        )) {
            ensureRunning()
            val start = System.nanoTime()
            try {
                val body = request(endpoint, host, path, "*/*", status) { tls = true }
                check(status == 204 || body.isNotBlank())
                latencyMs = (System.nanoTime() - start) / 1_000_000
                traffic = true
                failure = null
                break
            } catch (e: Exception) {
                if (e is InterruptedException || e is java.util.concurrent.CancellationException) throw e
                failure = when (e) {
                    is javax.net.ssl.SSLException -> "TLS certificate or handshake failed"
                    is java.net.SocketTimeoutException -> "HTTPS check timed out"
                    else -> "No verified HTTPS response"
                }
            }
        }
        ensureRunning()
        val dns = if (checkDns) {
            try {
                val answer = request(endpoint, "dns.google", "/resolve?name=example.com&type=A", "application/json", 200) {}
                val json = JSONObject(answer)
                json.optInt("Status", -1) == 0 && (json.optJSONArray("Answer")?.length() ?: 0) > 0
            } catch (e: Exception) {
                if (e is InterruptedException || e is java.util.concurrent.CancellationException) throw e
                false
            }
        } else null
        ensureRunning()
        return Result(dns, tls, traffic, latencyMs, failure)
    }

    private fun ensureRunning() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun request(endpoint: Socks5.Endpoint, host: String, path: String, accept: String, status: Int, onTls: () -> Unit): String {
        val raw = Socks5.connect(endpoint, host, 443, 5_000)
        try {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            ssl.use {
                it.soTimeout = 5_000
                it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                it.startHandshake()
                onTls()
                it.outputStream.write("GET $path HTTP/1.1\r\nHost: $host\r\nAccept: $accept\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                it.outputStream.flush()
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                val deadline = System.nanoTime() + 5_000_000_000
                while (bytes.size() < 32_768) {
                    ensureRunning()
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) throw java.net.SocketTimeoutException()
                    it.soTimeout = remaining.toInt()

                    val n = it.inputStream.read(buffer, 0, minOf(buffer.size, 32_768 - bytes.size()))
                    if (n < 0) break
                    bytes.write(buffer, 0, n)
                    if (status == 204 && bytes.toString("UTF-8").contains("\r\n\r\n")) break
                }
                val response = bytes.toString("UTF-8")
                check(response.startsWith("HTTP/1.1 $status ") || response.startsWith("HTTP/1.0 $status "))
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
