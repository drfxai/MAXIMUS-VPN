package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import org.json.JSONObject

/**
 * The security gate every derived connection setting passes before it is tried. Connectivity never
 * overrides a refusal here.
 *
 * A derived copy may only differ from its original in [RecoveryProfile.ALLOWED_FIELDS], and each
 * change must keep TLS authentication intact: same security mode, same SNI / Host / credential / pins,
 * certificate checking on, a known fingerprint, no weak cipher, a bounded fragmentation mask, ECH only
 * through an IP-literal DoH resolver (for the server's own name or Cloudflare's shared ECH name), and a new endpoint only as a public IP literal (the certificate is
 * still checked against the unchanged SNI).
 */
object RecoverySecurityGate {
    data class Result(val passed: Boolean, val reason: String? = null) {
        companion object { val OK = Result(true) }
    }

    /**
     * Fingerprints Xray-core v26.9.9 accepts for TLS. "unsafe" selects Go's own crypto/tls; checked in
     * Xray's source (infra/conf/transport_security.go, transport/internet/tls/config.go): certificate chain
     * and hostname verification stay on. REALITY refuses it.
     */
    val TLS_FINGERPRINTS = setOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "unsafe")

    private val WEAK_CIPHER = Regex("NULL|EXPORT|RC4|_DES_|3DES|_anon_|_MD5|PSK", RegexOption.IGNORE_CASE)

    /** Fragment bounds: no TCP piece over 2000 bytes, no delay over 200 ms, at most 512 splits (the "v1" fragment recipe uses 355). */
    const val MAX_FRAGMENT_LENGTH = 2000
    const val MAX_FRAGMENT_DELAY_MS = 200
    const val MAX_SPLITS = 512

    fun check(parent: VlessProfile, derived: VlessProfile): Result {
        fun refuse(why: String) = Result(false, why)
        if (derived.allowInsecure) return refuse("certificate checking would be turned off")
        if (!derived.security.equals("tls", true) || !parent.security.equals(derived.security, true)) {
            return refuse("the security mode would change (${parent.security} to ${derived.security})")
        }
        val identity = listOf<Pair<String, (VlessProfile) -> Any?>>(
            "protocol" to { it.protocolType }, "port" to { it.port }, "credential" to { it.uuid }, "sni" to { it.sni },
            "host" to { it.host }, "path" to { it.path }, "serviceName" to { it.serviceName }, "transport" to { it.transport },
            "encryption" to { it.encryption }, "flow" to { it.flow }, "publicKey" to { it.publicKey }, "shortId" to { it.shortId },
            "pinnedPeerCertSha256" to { it.pinnedPeerCertSha256 }, "verifyPeerCertByName" to { it.verifyPeerCertByName },
            "extraSettings" to { it.extraSettings }, "profileType" to { it.profileType }
        )
        identity.firstOrNull { (_, get) -> get(parent) != get(derived) }?.let { return refuse("${it.first} may not change") }

        if (derived.fingerprint.lowercase() !in TLS_FINGERPRINTS) return refuse("unknown TLS fingerprint '${derived.fingerprint}'")
        if (derived.cipherSuites.isNotBlank() && WEAK_CIPHER.containsMatchIn(derived.cipherSuites)) return refuse("weak cipher suite")
        maskProblem(derived.finalMask)?.let { return refuse(it) }
        if (derived.echConfigList != parent.echConfigList) echProblem(derived)?.let { return refuse(it) }
        if (derived.address != parent.address) endpointProblem(parent, derived)?.let { return refuse(it) }
        return Result.OK
    }

    /** Only fragment and noise masks with bounded numbers; null when acceptable. */
    fun maskProblem(mask: String): String? {
        if (mask.isBlank()) return null
        val json = runCatching { JSONObject(mask) }.getOrNull() ?: return "the fragment mask is not valid JSON"
        for (layer in json.keys()) {
            if (layer != "tcp" && layer != "udp") return "unknown mask layer '$layer'"
            val items = json.optJSONArray(layer) ?: return "mask layer '$layer' is not a list"
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: return "mask entry is not an object"
                val type = item.optString("type")
                if (type != "fragment" && type != "noise") return "mask type '$type' is not allowed"
                val settings = item.optJSONObject("settings") ?: JSONObject()
                fun numbers(key: String): List<Int> {
                    val values = settings.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }
                        ?: listOfNotNull(settings.opt(key)?.toString())
                    return values.flatMap { v -> v.split('-').mapNotNull { it.trim().toIntOrNull() } }
                }
                if ((numbers("lengths") + numbers("length")).any { it < 0 || it > MAX_FRAGMENT_LENGTH }) return "fragment length out of bounds"
                if ((numbers("delays") + numbers("delay")).any { it < 0 || it > MAX_FRAGMENT_DELAY_MS }) return "fragment delay out of bounds"
                if (numbers("maxSplit").any { it < 0 || it > MAX_SPLITS }) return "too many fragment splits"
            }
        }
        return null
    }

    private fun echProblem(p: VlessProfile): String? {
        val v = p.echConfigList
        if (v.isBlank()) return null
        val name = v.substringBefore('+', "")
        val resolver = v.substringAfter('+', "")
        if (name.isBlank() || resolver.isBlank()) return "ECH must name the server and a DoH resolver"
        if (!name.equals(p.sni.ifBlank { p.host }, true) && !name.equals(CLOUDFLARE_ECH_NAME, true)) {
            return "ECH must look up the server's own name or Cloudflare's shared ECH name"
        }
        val host = resolver.removePrefix("https://").substringBefore('/')
        if (!resolver.startsWith("https://") || !isIpLiteral(host)) return "ECH resolver must be a DoH address given as an IP"
        return null
    }

    private fun endpointProblem(parent: VlessProfile, derived: VlessProfile): String? {
        if (parent.sni.isBlank() && parent.host.isBlank()) return "without an SNI the certificate would be checked against the new address"
        val ip = derived.address.removePrefix("[").removeSuffix("]")
        if (!isIpLiteral(ip)) return "a new endpoint must be an IP address"
        if (isPrivateOrReserved(ip)) return "the new endpoint is a private or reserved address"
        return null
    }

    /**
     * The public name of the ECH key Cloudflare shares across every site it fronts. Looking it up gives a
     * Cloudflare-fronted config (a Worker, Pages or a proxied domain) ECH even when its own name has no
     * HTTPS record. The certificate is still checked against the config's unchanged SNI.
     */
    const val CLOUDFLARE_ECH_NAME = "cloudflare-ech.com"

    fun isIpLiteral(h: String): Boolean {
        val v4 = h.split('.')
        if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }) return true
        return h.contains(':') && h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    /** Pure check (no DNS) for addresses a public server never has. */
    fun isPrivateOrReserved(ip: String): Boolean {
        val v4 = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (v4.size == 4) {
            val (a, b) = v4[0] to v4[1]
            return a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) || (a == 100 && b in 64..127) || (a == 198 && b in 18..19) || (a == 192 && b == 0 && v4[2] == 2) ||
                (a == 198 && b == 51 && v4[2] == 100) || (a == 203 && b == 0 && v4[2] == 113)
        }
        val h = ip.lowercase()
        return h == "::" || h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb") || h.startsWith("ff") || h.startsWith("2001:db8") || h.startsWith("::ffff:")
    }
}
