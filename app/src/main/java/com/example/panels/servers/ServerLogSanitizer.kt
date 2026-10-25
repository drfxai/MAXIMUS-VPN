package com.example.panels.servers

/** Server output may contain capability URLs: redact the entire path and query before displaying it. */
object ServerLogSanitizer {
    private val url = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    fun redact(raw: String): String = url.replace(com.example.core.SecretRedactor.redact(raw)) { match ->
        val uri = runCatching { java.net.URI(match.value) }.getOrNull()
        val host = uri?.host ?: return@replace "[REDACTED_URL]"
        val shown = if (host.contains(':')) "[$host]" else host
        "${uri.scheme}://$shown${if (uri.port >= 0) ":${uri.port}" else ""}/[REDACTED]"
    }
}
