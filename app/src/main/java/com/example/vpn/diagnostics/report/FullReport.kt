package com.example.vpn.diagnostics.report

/**
 * "Copy full report": every diagnostic section as one plain-text block for the clipboard.
 *
 * Pure, so it is tested without a phone. Every section passes [redact] here even when its builder
 * already redacted it, so a section added later cannot leak a secret into the clipboard.
 */
object FullReport {
    /**
     * Android's clipboard goes through Binder, which fails above about 1 MB. Characters are up to
     * 2 bytes there, so the text is kept well under that and the tail is cut with a note.
     */
    const val MAX_CHARS = 300_000

    data class Section(val title: String, val body: String)

    /** A PEM block (private key, certificate request and the like) is dropped whole. */
    private val PEM_BLOCK = Regex("-----BEGIN [A-Z0-9 ]+-----[\\s\\S]*?-----END [A-Z0-9 ]+-----")

    /** WireGuard/AmneziaWG keys written "PrivateKey = ..." or "PresharedKey: ..." (base64, 44 chars). */
    private val WG_KEY = Regex("(?i)\\b(private_?key|pre_?shared_?key|psk)(\\s*[:=]\\s*)\"?[A-Za-z0-9+/]{42,44}={0,2}\"?")

    /** Password, passphrase or obfs password given as a link or query parameter. */
    private val LINK_PASSWORD = Regex("(?i)([?&;](?:password|passwd|pwd|obfs-password|obfs_password|psk|pass)=)[^&\\s#\"']+")

    /** user:password@ inside any scheme the proxy patterns do not list (socks, http, naive, ...). */
    private val USERINFO = Regex("(?i)\\b([a-z][a-z0-9+.-]{1,20}://)[^/@\\s:]+:[^/@\\s]+@")

    fun redact(text: String): String {
        if (text.isBlank()) return text
        var t = PEM_BLOCK.replace(text, "[REDACTED_PEM_BLOCK]")
        t = WG_KEY.replace(t) { m -> "${m.groupValues[1]}${m.groupValues[2]}[REDACTED_KEY]" }
        t = LINK_PASSWORD.replace(t) { m -> "${m.groupValues[1]}[REDACTED]" }
        t = USERINFO.replace(t) { m -> "${m.groupValues[1]}[REDACTED_CREDENTIALS]@" }
        // Proxy links, UUIDs, REALITY keys, tokens, e-mail, phone numbers and addresses.
        return ReportRedaction.secondPass(t)
    }

    /** The header lines (app version and build, network type, ...) then each section, all redacted. */
    fun compose(header: List<String>, sections: List<Section>): String {
        val sb = StringBuilder()
        sb.appendLine("MAXIMUS VPN · FULL DIAGNOSTIC REPORT")
        sb.appendLine("Secrets (UUIDs, passwords, keys, tokens, addresses) are redacted.")
        header.forEach { sb.appendLine(it) }
        sections.forEach { s ->
            sb.appendLine()
            sb.appendLine("===== ${s.title.uppercase()} =====")
            sb.appendLine(s.body.trimEnd().ifBlank { "(empty)" })
        }
        sb.appendLine()
        sb.appendLine("===== END OF FULL REPORT =====")
        val text = redact(sb.toString())
        if (text.length <= MAX_CHARS) return text
        val note = "\n[Report truncated at $MAX_CHARS characters; use Export Report for the complete JSON file.]\n"
        return text.take(MAX_CHARS - note.length) + note
    }
}
