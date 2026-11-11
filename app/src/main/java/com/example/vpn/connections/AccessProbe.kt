package com.example.vpn.connections

import com.example.vpn.verify.Socks5
import org.json.JSONObject
import com.example.vpn.verify.Dialer
import com.example.vpn.verify.TrafficCheck

/** Real, bounded DNS-over-HTTPS and authenticated HTTPS checks through the selected engine. */
object AccessProbe {
    data class Result(val dns: Boolean? = null, val tls: Boolean = false, val traffic: Boolean = false,
        val latencyMs: Long? = null, val failure: String? = null)

    fun check(endpoint: Socks5.Endpoint, checkDns: Boolean, diagnoseAddress: Boolean = false): Result {
        var tls = false
        var traffic = false
        var latencyMs: Long? = null
        val failures = mutableListOf<String>()
        for ((host, path, status) in listOf(
            Triple("www.google.com", "/generate_204", 204),
            Triple("example.com", "/", 200)
        )) {
            ensureRunning()
            val start = System.nanoTime()
            var stage = "SOCKS"
            try {
                val body = request(endpoint, host, path, status, onConnected = { stage = "TLS" }) {
                    tls = true
                    stage = "HTTP"
                }
                check(status == 204 || body.isNotBlank())
                latencyMs = (System.nanoTime() - start) / 1_000_000
                traffic = true
                break
            } catch (e: Exception) {
                if (e is InterruptedException || e is java.util.concurrent.CancellationException) throw e
                failures += describe(stage, e)
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
        if (!traffic && diagnoseAddress) {
            failures += "DNS control: " + addressControl(endpoint)
        }
        return Result(dns, tls, traffic, latencyMs, failures.takeUnless { traffic }?.joinToString(" | "))
    }

    private fun addressControl(endpoint: Socks5.Endpoint): String {
        // The same HTTPS reference is checked by name and by IPv4. The dial address
        // changes while SNI, certificate verification and the HTTP Host stay the same.
        val host = "cloudflare-dns.com"
        val path = "/dns-query?name=example.com&type=A"
        return listOf("hostname" to host, "IPv4" to "1.1.1.1").joinToString(", ") { (label, address) ->
            ensureRunning()
            var stage = "SOCKS"
            val result = try {
                val answer = request(endpoint, host, path, 200, address, { stage = "TLS" },
                    mapOf("Accept" to "application/dns-json")) { stage = "HTTP" }
                val json = JSONObject(answer)
                check(json.optInt("Status", -1) == 0 && (json.optJSONArray("Answer")?.length() ?: 0) > 0)
                "PASS"
            } catch (e: Exception) {
                if (e is InterruptedException || e is java.util.concurrent.CancellationException) throw e
                describe(stage, e)
            }
            "$label=$result"
        }
    }

    private fun describe(stage: String, error: Exception): String {
        val cause = error.cause ?: error
        val reason = when {
            cause is java.net.SocketTimeoutException ||
                (error as? TrafficCheck.TransferException)?.timedOut == true -> "timeout"
            cause is javax.net.ssl.SSLException -> "handshake or certificate failure"
            cause is java.net.UnknownHostException -> "name resolution failure"
            cause is Socks5.Socks5Exception -> "proxy rejected the connection"
            else -> "no valid response"
        }
        return "$stage: $reason"
    }

    private fun ensureRunning() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun request(
        endpoint: Socks5.Endpoint, host: String, path: String, status: Int,
        address: String = host, onConnected: () -> Unit = {},
        headers: Map<String, String> = emptyMap(), onTls: () -> Unit
    ): String {
        val dialer = Dialer { _, port, timeout ->
            Socks5.connect(endpoint, address, port, timeout).also { onConnected() }
        }
        val probe = TrafficCheck(dialer, stepTimeoutMs = 8_000, onTlsVerified = onTls)
        val response = probe.exchange(TrafficCheck.Target(host, 443, path), "GET", null, keepBody = true, extraHeaders = headers)
        check(response.status == status) { "Unexpected HTTPS status" }
        return response.body?.toString("UTF-8").orEmpty()
    }
}
