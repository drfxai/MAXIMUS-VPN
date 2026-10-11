package com.example.vpn.connectivity

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Checks which DoH resolvers return Cloudflare's shared ECH key (the HTTPS record of
 * cloudflare-ech.com with an "ech" parameter) on this network, so the Cloudflare ECH recipes whose
 * resolver is blocked are not tried: Xray fetches the key through the same resolver and would fail.
 * The query is an ordinary RFC 8484 GET; nothing about the user's configs is sent.
 */
object EchKeyCheck {
    const val TYPE_HTTPS = 65
    private const val ECH_PARAM = 5

    /** The DNS query for [name] type HTTPS, in wire format. */
    fun query(name: String = RecoverySecurityGate.CLOUDFLARE_ECH_NAME): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.trimEnd('.').split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(byteArrayOf(0, 0, TYPE_HTTPS.toByte(), 0, 1))
        return out.toByteArray()
    }

    /** [dohUrl] with the query as its `dns` parameter (base64url, no padding). */
    fun url(dohUrl: String): String =
        dohUrl + (if ('?' in dohUrl) "&" else "?") + "dns=" + Base64.getUrlEncoder().withoutPadding().encodeToString(query())

    /** True when [response] answers without error and holds an HTTPS record with an ECH parameter. */
    fun hasEchKey(response: ByteArray?): Boolean = runCatching {
        val r = response ?: return false
        fun u16(i: Int) = ((r[i].toInt() and 0xff) shl 8) or (r[i + 1].toInt() and 0xff)
        if (r.size < 12 || (r[2].toInt() and 0x80) == 0 || (r[3].toInt() and 0x0f) != 0) return false
        val questions = u16(4)
        val answers = u16(6)
        var i = 12
        fun skipName() {
            while (true) {
                val len = r[i].toInt() and 0xff
                if (len == 0) { i += 1; return }
                if (len and 0xc0 == 0xc0) { i += 2; return }
                i += 1 + len
            }
        }
        repeat(questions) { skipName(); i += 4 }
        repeat(answers) {
            skipName()
            val type = u16(i)
            val rdLength = u16(i + 8)
            val start = i + 10
            if (type == TYPE_HTTPS) {
                var j = start + 2
                // TargetName: uncompressed labels.
                while (r[j].toInt() != 0) j += 1 + (r[j].toInt() and 0xff)
                j += 1
                while (j + 4 <= start + rdLength) {
                    val key = u16(j)
                    val len = u16(j + 2)
                    if (key == ECH_PARAM && len > 0) return true
                    j += 4 + len
                }
            }
            i = start + rdLength
        }
        false
    }.getOrDefault(false)

    /** Each resolver in [dohUrls] → whether it returned the ECH key; [fetch] does the GET (null on failure). */
    fun check(dohUrls: List<String>, fetch: (String) -> ByteArray?): Map<String, Boolean> =
        dohUrls.associateWith { hasEchKey(runCatching { fetch(url(it)) }.getOrNull()) }
}
