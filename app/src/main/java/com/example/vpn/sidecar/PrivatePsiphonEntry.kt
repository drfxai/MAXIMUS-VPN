package com.example.vpn.sidecar

import org.json.JSONObject

/**
 * Psiphon's server-entry format (see upstream protocol.EncodeServerEntry) is UTF-8 text
 * "IP webPort webSecret webCertificate JSON" encoded as hex. For this application we
 * only accept a private OSSH-capable entry with a literal IPv4 and an SSH host key.
 *
 * No DNS lookups, network access or logs of the private entry occur during validation.
 */
object PrivatePsiphonEntry {
    data class Description(val ipAddress: String, val sshObfuscatedPort: Int)

    private val ipv4 = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    private val hex = Regex("^[0-9a-fA-F]+$")

    fun inspect(encoded: String): Description? {
        if (encoded.length !in 64..262_144 || encoded.length % 2 != 0 ||
            !hex.matches(encoded)) return null

        val bytes = ByteArray(encoded.length / 2)
        for (i in bytes.indices) {
            val hi = Character.digit(encoded[i * 2], 16)
            val lo = Character.digit(encoded[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        val text = bytes.toString(Charsets.UTF_8)
        val fields = text.split(' ', limit = 5)
        if (fields.size != 5) return null
        if (!validIp(fields[0])) return null
        val payload = runCatching { JSONObject(fields[4]) }.getOrNull() ?: return null
        val host = payload.optString("ipAddress")
        if (host != fields[0]) return null
        val port = payload.optInt("sshObfuscatedPort")
        if (port !in 1..65535) return null
        if (payload.optString("sshHostKey").isBlank()) return null
        if (payload.optString("sshUsername").isBlank() ||
            payload.optString("sshPassword").isBlank()) return null
        // Refuse an unbounded profile or control characters in any legacy field.
        if (fields.take(4).any { s -> s.length > 4096 || s.any { ch -> ch.code < 32 } }) return null
        return Description(host, port)
    }

    fun validIp(ip: String): Boolean {
        if (!ipv4.matches(ip)) return false
        return ip.split('.').all { octet ->
            (octet.length == 1 || octet.first() != '0') &&
                ((octet.toIntOrNull() ?: -1) in 0..255)
        }
    }
}
