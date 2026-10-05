package com.example.vpn.share

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Phone-to-phone sharing without any network (war plan Phase 3): a server becomes one QR code that
 * MAXIMUS reads back without loss, for every protocol (WireGuard keys, Hysteria2 masks and protocol
 * extras included), where a share link only exists for some protocols.
 *
 * Format: `mx1:` + base64url(deflate(JSON of the connection fields)), or `mx1:<i>/<n>:` + the same for
 * code i of a set of n shown one after another.
 */
object ShareCode {
    const val PREFIX = "mx1:"

    /** Codes above this many characters scan badly on phone screens; the server is left out of a set. */
    const val MAX_CHARS = 1800

    data class Part(val index: Int, val total: Int, val profile: VlessProfile)

    private val TEXT_FIELDS: List<Pair<String, (VlessProfile) -> String>> = listOf(
        "name" to { p -> p.name }, "address" to { p -> p.address }, "uuid" to { p -> p.uuid },
        "encryption" to { p -> p.encryption }, "transport" to { p -> p.transport }, "security" to { p -> p.security },
        "sni" to { p -> p.sni }, "host" to { p -> p.host }, "path" to { p -> p.path },
        "serviceName" to { p -> p.serviceName }, "flow" to { p -> p.flow }, "fingerprint" to { p -> p.fingerprint },
        "cipherSuites" to { p -> p.cipherSuites }, "finalMask" to { p -> p.finalMask },
        "publicKey" to { p -> p.publicKey }, "shortId" to { p -> p.shortId }, "spiderX" to { p -> p.spiderX },
        "alpn" to { p -> p.alpn }, "headerType" to { p -> p.headerType },
        "pinnedPeerCertSha256" to { p -> p.pinnedPeerCertSha256 }, "verifyPeerCertByName" to { p -> p.verifyPeerCertByName },
        "echConfigList" to { p -> p.echConfigList }, "echSockopt" to { p -> p.echSockopt },
        "targetStrategy" to { p -> p.targetStrategy }, "extraSettings" to { p -> p.extraSettings },
        "rawConfig" to { p -> p.rawConfig }
    )

    fun encode(profile: VlessProfile, index: Int? = null, total: Int? = null): String {
        val json = JSONObject()
        TEXT_FIELDS.forEach { (key, get) -> get(profile).takeIf { it.isNotEmpty() }?.let { json.put(key, it) } }
        json.put("port", profile.port)
        if (profile.allowInsecure) json.put("allowInsecure", true)
        json.put("protocol", profile.protocolType.name)
        if (profile.profileType != ProfileType.VLESS) json.put("profileType", profile.profileType.name)
        if (profile.engineType != EngineType.XRAY) json.put("engine", profile.engineType.name)
        val body = base64Url(deflate(json.toString().toByteArray(Charsets.UTF_8)))
        val head = if (index != null && total != null) "$PREFIX$index/$total:" else PREFIX
        return head + body
    }

    /** Null when [text] is not a share code or is damaged. */
    fun decode(text: String): Part? = runCatching { decodeOrNull(text) }.getOrNull()

    private fun decodeOrNull(text: String): Part? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        var rest = trimmed.substring(PREFIX.length)
        var index = 1
        var total = 1
        Regex("^(\\d{1,3})/(\\d{1,3}):").find(rest)?.let { m ->
            index = m.groupValues[1].toInt()
            total = m.groupValues[2].toInt()
            rest = rest.substring(m.value.length)
        }
        if (total < 1 || index !in 1..total) return null
        val json = JSONObject(String(inflate(unBase64Url(rest)), Charsets.UTF_8))
        fun s(key: String) = json.optString(key, "")
        val address = s("address")
        val port = json.optInt("port", 0)
        if (address.isBlank() || port !in 1..65535) return null
        val profile = VlessProfile(
            id = UUID.randomUUID().toString(),
            name = s("name").ifBlank { "Shared $address" },
            address = address,
            port = port,
            uuid = s("uuid"),
            encryption = s("encryption").ifEmpty { "none" },
            transport = s("transport").ifEmpty { "tcp" },
            security = s("security").ifEmpty { "none" },
            sni = s("sni"), host = s("host"), path = s("path"), serviceName = s("serviceName"),
            flow = s("flow"), fingerprint = s("fingerprint"), cipherSuites = s("cipherSuites"),
            finalMask = s("finalMask"), publicKey = s("publicKey"), shortId = s("shortId"),
            spiderX = s("spiderX"), alpn = s("alpn"), headerType = s("headerType"),
            allowInsecure = json.optBoolean("allowInsecure", false),
            pinnedPeerCertSha256 = s("pinnedPeerCertSha256"), verifyPeerCertByName = s("verifyPeerCertByName"),
            echConfigList = s("echConfigList"), echSockopt = s("echSockopt"), targetStrategy = s("targetStrategy"),
            extraSettings = s("extraSettings"), rawConfig = s("rawConfig"),
            protocolType = enumOr(s("protocol"), ProtocolType.VLESS),
            profileType = enumOr(s("profileType"), ProfileType.VLESS),
            engineType = enumOr(s("engine"), EngineType.XRAY)
        )
        return Part(index, total, profile)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 64 * 1024) throw IllegalArgumentException("share code too large")
        }
        inflater.end()
        return out.toByteArray()
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    internal fun base64Url(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 >= 0) sb.append(ALPHABET[((b1 and 15) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            if (b2 >= 0) sb.append(ALPHABET[b2 and 63])
            i += 3
        }
        return sb.toString()
    }

    internal fun unBase64Url(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) throw IllegalArgumentException("bad character")
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
