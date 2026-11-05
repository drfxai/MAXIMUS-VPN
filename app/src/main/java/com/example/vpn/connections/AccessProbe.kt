package com.example.vpn.connections

import com.example.vpn.verify.Socks5
import org.json.JSONObject
import com.example.vpn.verify.Dialer
import com.example.vpn.verify.TrafficCheck

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
                val body = request(endpoint, host, path, status) { tls = true }
                check(status == 204 || body.isNotBlank())
                latencyMs = (System.nanoTime() - start) / 1_000_000
                traffic = true
                failure = null
                break
            } catch (e: Exception) {
                if (e is InterruptedException || e is java.util.concurrent.CancellationException) throw e
                failure = when (e.cause ?: e) {
                    is javax.net.ssl.SSLException -> "TLS certificate or handshake failed"
                    is java.net.SocketTimeoutException -> "HTTPS check timed out"
                    else -> "No verified HTTPS response"
                }
            }
        }
        ensureRunning()
        val dns = if (checkDns) {
            try {
                val answer = request(endpoint, "dns.google", "/resolve?name=example.com&type=A", 200) {}
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

    private fun request(endpoint: Socks5.Endpoint, host: String, path: String, status: Int, onTls: () -> Unit): String {
        val dialer = Dialer { target, port, timeout -> Socks5.connect(endpoint, target, port, timeout) }
        val probe = TrafficCheck(dialer, stepTimeoutMs = 8_000, onTlsVerified = onTls)
        val response = probe.exchange(TrafficCheck.Target(host, 443, path), "GET", null, keepBody = true)
        check(response.status == status) { "Unexpected HTTPS status" }
        return response.body?.toString("UTF-8").orEmpty()
    }
}
