package com.example.vpn.subscription

import java.net.URI

/** Recognizes a pasted subscription, including a bare domain/path. Never guesses an IP or disables TLS. */
object SubscriptionInput {
    fun url(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 8192 || text.any { it.isWhitespace() || it.isISOControl() }) return null
        if (text.contains("://") && !text.startsWith("https://", true) && !text.startsWith("http://", true)) return null
        val candidate = if (text.contains("://")) text else "https://$text"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.port !in -1..65535 || uri.port == 0) return null
        if (!text.contains("://") && (!uri.host.contains('.') || uri.rawPath.isNullOrEmpty())) return null
        return candidate
    }

    fun failure(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            causes.any { it is javax.net.ssl.SSLPeerUnverifiedException } ->
                "TLS hostname verification failed. Install a trusted certificate for the exact subscription hostname, or use a panel-provided address covered by its certificate. Browser access after a certificate warning does not validate this link."
            causes.any { it is javax.net.ssl.SSLHandshakeException } ->
                "TLS handshake failed. Check the certificate chain, expiry, device clock and network path. Certificate verification remains enabled."
            else -> com.example.panels.servers.ServerLogSanitizer.redact(error.message ?: "Subscription request failed")
        }
    }
}
