package com.example.core

/** Defense in depth for model-bound text. Diagnostics separately use a strict allowlist. */
object AiPrivacyFilter {
    private val urls = Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\"']+")
    private val ipv4 = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]+)?\\b")
    private val ipv6 = Regex("(?i)(?<![a-z0-9])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.%]+")
    private val domains = Regex("(?i)\\b(?:[a-z0-9_-]+\\.)+[a-z][a-z0-9-]{1,62}\\b")
    private val keys = Regex("(?i)\\b(?:api[_-]?key|api[_-]?token|admin[_-]?password|password|passwd|private[_-]?key|secret|authorization|token)\\b[\\s\"']*[:=][\\s\"']*[^\\s,}\"']+")
    private val googleKeys = Regex("\\bAIza[A-Za-z0-9_-]{20,}\\b")
    fun redact(text: String): String {
        var result = SecretRedactor.redact(text)
        result = keys.replace(result, "[REDACTED_SECRET]")
        result = googleKeys.replace(result, "[REDACTED_SECRET]")
        result = urls.replace(result, "[REDACTED_URL]")
        result = ipv4.replace(result, "[REDACTED_IP]")
        result = ipv6.replace(result, "[REDACTED_IP]")
        return domains.replace(result, "[REDACTED_HOST]")
    }
    fun sanitize(value: Any?): Any? = when (value) {
        is String -> redact(value)
        is Map<*, *> -> value.entries.associate { it.key.toString() to sanitize(it.value) }
        is Iterable<*> -> value.map(::sanitize)
        is Number, is Boolean, null -> value
        else -> "[WITHHELD]"
    }
}
