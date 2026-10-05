package com.example.vpn.stealth

import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.EngineSelectionPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Alternate ways to send the same connection that only change what the client puts on the wire, so
 * they work against the user's existing server without any server change:
 *
 * - TLS and REALITY over TCP (direct or through a CDN): the ClientHello split into small TCP pieces
 *   (DPI that does not reassemble cannot read the SNI), another browser's TLS fingerprint, both, and
 *   for TLS with a domain name, Encrypted ClientHello fetched over DoH (hides the SNI entirely when the
 *   site supports ECH).
 * - Other TCP (VLESS Encryption, Shadowsocks, VMess): the first packets split into random pieces, which
 *   breaks first-packet length and entropy classifiers.
 * - QUIC (Hysteria2) and WireGuard: random junk datagrams before the handshake, as AmneziaWG does, so a
 *   classifier that looks at the first packet of a UDP flow sees noise. Servers drop the junk.
 *
 * Every profile the app runs on Xray gets at least two alternates. Which one works is decided by a
 * real request through each ([StealthPathFinder]), never assumed.
 */
object StealthVariants {
    data class Variant(val key: String, val label: String, val profile: VlessProfile)

    const val FRAGMENT = "fragment"
    const val FINGERPRINT = "fingerprint"
    const val FRAGMENT_FINGERPRINT = "fragment+fingerprint"
    const val ECH = "ech"
    const val SPLIT_FIRST = "split-first"
    const val SPLIT_FIRST_THREE = "split-first-three"
    const val NOISE = "noise"
    const val NOISE_BURST = "noise-burst"

    /** DoH resolver Xray asks for the site's ECH configuration (HTTPS record). */
    const val ECH_DNS = "https://1.1.1.1/dns-query"

    private val TCP_TRANSPORTS = setOf("tcp", "raw", "ws", "grpc", "xhttp", "splithttp", "httpupgrade", "http", "h2")

    /** Alternates for [profile], best first; empty for profiles that do not run on Xray. */
    fun of(profile: VlessProfile): List<Variant> {
        if (profile.profileType == ProfileType.XRAY_JSON) return emptyList()
        if (EngineSelectionPolicy.select(profile) != EngineSelectionPolicy.Runtime.XRAY) return emptyList()
        val mask = parseMask(profile.finalMask) ?: return emptyList()
        return when {
            profile.protocolType == ProtocolType.HYSTERIA2 || profile.protocolType == ProtocolType.WIREGUARD ->
                udpVariants(profile, mask)
            profile.transport.lowercase().ifBlank { "tcp" } !in TCP_TRANSPORTS -> emptyList()
            profile.security.lowercase() in setOf("tls", "reality") -> tlsVariants(profile, mask)
            else -> plainTcpVariants(profile, mask)
        }
    }

    /** The variant with [key] for [profile], if it still applies (used to reuse the last winner). */
    fun find(profile: VlessProfile, key: String): Variant? = of(profile).firstOrNull { it.key == key }

    private fun tlsVariants(profile: VlessProfile, mask: JSONObject): List<Variant> {
        val out = mutableListOf<Variant>()
        val hasTcpMask = mask.optJSONArray("tcp")?.let { it.length() > 0 } == true
        val fp = profile.fingerprint.lowercase()
        // "unsafe" means Go TLS with custom cipher suites; another fingerprint would drop them.
        val altFp = when {
            fp == "unsafe" -> null
            fp.isBlank() || fp == "chrome" || fp.startsWith("hellochrome") -> "firefox"
            else -> "chrome"
        }
        val fragmented = if (hasTcpMask) null else profile.copy(finalMask = withTcp(mask, helloFragment()).toString())
        fragmented?.let { out += Variant(FRAGMENT, "TLS handshake split into small pieces", it) }
        altFp?.let { out += Variant(FINGERPRINT, "${it.replaceFirstChar(Char::uppercase)} TLS fingerprint", profile.copy(fingerprint = it)) }
        if (fragmented != null && altFp != null) {
            out += Variant(FRAGMENT_FINGERPRINT, "Split handshake with a $altFp fingerprint", fragmented.copy(fingerprint = altFp))
        }
        val sni = profile.sni.ifBlank { profile.host }
        if (profile.security.equals("tls", ignoreCase = true) && profile.echConfigList.isBlank() && isDomain(sni)) {
            out += Variant(ECH, "Encrypted ClientHello (hides the server name)", profile.copy(echConfigList = "$sni+$ECH_DNS"))
        }
        if (out.size < 2 && !hasTcpMask) {
            out += Variant(SPLIT_FIRST, "First packets split into random pieces", profile.copy(finalMask = withTcp(mask, packetFragment("1-3", "40-120")).toString()))
        }
        return out
    }

    private fun plainTcpVariants(profile: VlessProfile, mask: JSONObject): List<Variant> {
        if (mask.optJSONArray("tcp")?.let { it.length() > 0 } == true) return emptyList()
        return listOf(
            Variant(SPLIT_FIRST, "First packet split into random pieces", profile.copy(finalMask = withTcp(mask, packetFragment("1-1", "20-60")).toString())),
            Variant(SPLIT_FIRST_THREE, "First three packets split into random pieces", profile.copy(finalMask = withTcp(mask, packetFragment("1-3", "50-150")).toString()))
        )
    }

    private fun udpVariants(profile: VlessProfile, mask: JSONObject): List<Variant> {
        val udp = mask.optJSONArray("udp") ?: JSONArray()
        // A config that already sends junk (AmneziaWG Jc) is its own noise variant.
        if ((0 until udp.length()).any { udp.optJSONObject(it)?.optString("type") == "noise" }) return emptyList()
        return listOf(
            Variant(NOISE, "Junk packet before the handshake", profile.copy(finalMask = withUdp(mask, noise(1, "40-120", "0")).toString())),
            Variant(NOISE_BURST, "Burst of junk packets before the handshake", profile.copy(finalMask = withUdp(mask, noise(4, "30-90", "5-15")).toString()))
        )
    }

    internal fun helloFragment(): JSONObject = JSONObject().put("type", "fragment").put(
        "settings", JSONObject().put("packets", "tlshello").put("length", "100-200").put("delay", "10-20")
    )

    private fun packetFragment(packets: String, length: String): JSONObject = JSONObject().put("type", "fragment").put(
        "settings", JSONObject().put("packets", packets).put("length", length).put("delay", "5-15")
    )

    private fun noise(count: Int, length: String, delay: String): JSONObject {
        val items = JSONArray()
        repeat(count) { items.put(JSONObject().put("rand", length).put("delay", delay)) }
        return JSONObject().put("type", "noise").put("settings", JSONObject().put("reset", 120).put("noise", items))
    }

    private fun withTcp(mask: JSONObject, item: JSONObject): JSONObject =
        JSONObject(mask.toString()).put("tcp", JSONArray().put(item))

    private fun withUdp(mask: JSONObject, item: JSONObject): JSONObject {
        val copy = JSONObject(mask.toString())
        val udp = copy.optJSONArray("udp") ?: JSONArray()
        udp.put(item)
        return copy.put("udp", udp)
    }

    private fun parseMask(raw: String): JSONObject? =
        if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrNull()

    private fun isDomain(name: String): Boolean =
        name.contains('.') && !name.contains(':') && !name.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
}
