package com.example.vpn.engine

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Share-link parsing for the protocols the bundled Xray core runs besides VLESS, VMess, Trojan and
 * Shadowsocks: Hysteria2 and WireGuard. Both import and the config builder use the same field
 * layout, so a link and an edited profile build the same outbound.
 */
object ProtocolLinks {
    /** Seconds between port hops when a link asks for hopping without an interval (Hysteria's default). */
    const val DEFAULT_HOP_INTERVAL = "30"

    /**
     * Parses `hysteria2://auth@host:port/?sni=..&obfs=salamander&obfs-password=..&mport=..#name`.
     * The port may be a list or range ("443,20000-50000"), which turns on port hopping.
     */
    fun parseHysteria2(uri: String): VlessProfile {
        val parts = splitUri(uri, "hysteria2", "hy2")
        require(parts.host.isNotBlank()) { "Hysteria2 link is missing the server host" }
        val params = parts.params
        val portSpec = parts.portSpec.ifBlank { "443" }
        val firstPort = firstPort(portSpec)
        val hopPorts = params["mport"]?.takeIf { it.isNotBlank() }
            ?: portSpec.takeIf { it.contains(',') || it.contains('-') }

        val mask = JSONObject()
        val udp = JSONArray()
        val obfs = params["obfs"].orEmpty()
        if (obfs.isNotBlank()) {
            require(obfs.equals("salamander", ignoreCase = true)) { "Hysteria2 obfs '$obfs' is not supported" }
            val password = params["obfs-password"].orEmpty()
            require(password.isNotBlank()) { "Hysteria2 salamander obfs needs obfs-password" }
            udp.put(JSONObject().put("type", "salamander").put("settings", JSONObject().put("password", password)))
        }
        if (hopPorts != null) {
            udp.put(hopMask(hopPorts, params["hopInterval"] ?: params["hop_interval"] ?: params["hop-interval"]))
        }
        if (udp.length() > 0) mask.put("udp", udp)
        val up = bandwidth(params["upmbps"] ?: params["up"])
        val down = bandwidth(params["downmbps"] ?: params["down"])
        if (up != null) {
            mask.put("quicParams", JSONObject().put("congestion", "brutal").put("brutalUp", up).apply {
                if (down != null) put("brutalDown", down)
            })
        }

        val pin = normalizePin(params["pinSHA256"] ?: params["pinsha256"] ?: params["pcs"])
        return VlessProfile(
            name = parts.fragment.ifBlank { "Hysteria2-${parts.host}" },
            address = parts.host,
            port = firstPort,
            uuid = parts.userInfo,
            encryption = "none",
            transport = "hysteria",
            security = "tls",
            sni = params["sni"]?.takeIf { it.isNotBlank() } ?: parts.host,
            alpn = params["alpn"]?.takeIf { it.isNotBlank() } ?: "h3",
            allowInsecure = params["insecure"].isTruthy() || params["allowInsecure"].isTruthy(),
            pinnedPeerCertSha256 = pin,
            finalMask = if (mask.length() > 0) mask.toString() else "",
            profileType = ProfileType.VLESS,
            protocolType = ProtocolType.HYSTERIA2,
            engineType = EngineType.XRAY
        )
    }

    /**
     * Parses `wireguard://privateKey@host:port?publickey=..&address=10.0.0.2/32&reserved=1,2,3&mtu=1280#name`
     * (also `wg://`), the form v2rayN, NekoBox and sing-box export.
     */
    fun parseWireGuard(uri: String): VlessProfile {
        val parts = splitUri(uri, "wireguard", "wg")
        require(parts.host.isNotBlank()) { "WireGuard link is missing the server host" }
        val params = parts.params
        // Keys are base64, which has no spaces: a space is a '+' the link left unescaped.
        fun key(raw: String?) = raw.orEmpty().trim().replace(' ', '+')
        val secretKey = key(parts.userInfo.ifBlank { params["privatekey"] ?: params["privateKey"] ?: params["secretKey"] })
        require(secretKey.isNotBlank()) { "WireGuard link is missing the private key" }
        val peerKey = key(params["publickey"] ?: params["publicKey"] ?: params["peer_public_key"])
        require(peerKey.isNotBlank()) { "WireGuard link is missing the peer public key" }
        val address = params["address"] ?: params["ip"] ?: params["local_address"] ?: "172.16.0.2/32"
        val extras = JSONObject().put(ProfileExtras.WG_ADDRESS, address)
        params["reserved"]?.takeIf { it.isNotBlank() }?.let { extras.put(ProfileExtras.WG_RESERVED, it) }
        params["mtu"]?.toIntOrNull()?.let { extras.put(ProfileExtras.WG_MTU, it) }
        key(params["presharedkey"] ?: params["preSharedKey"] ?: params["psk"]).takeIf { it.isNotBlank() }
            ?.let { extras.put(ProfileExtras.WG_PRESHARED_KEY, it) }
        params["keepalive"]?.toIntOrNull()?.let { extras.put(ProfileExtras.WG_KEEPALIVE, it) }
        return VlessProfile(
            name = parts.fragment.ifBlank { "WireGuard-${parts.host}" },
            address = parts.host,
            port = firstPort(parts.portSpec.ifBlank { "51820" }),
            uuid = secretKey,
            encryption = "none",
            transport = "udp",
            security = "none",
            publicKey = peerKey,
            extraSettings = extras.toString(),
            profileType = ProfileType.VLESS,
            protocolType = ProtocolType.WIREGUARD,
            engineType = EngineType.XRAY
        )
    }

    /** A udphop mask that changes the local socket and the server port every few seconds, like Hysteria's own client. */
    fun hopMask(ports: String, interval: String?): JSONObject {
        val cleanPorts = ports.replace(" ", "")
        require(cleanPorts.matches(Regex("""\d+(-\d+)?(,\d+(-\d+)?)*"""))) { "Invalid hop ports '$ports'" }
        val seconds = interval?.trim()?.removeSuffix("s")?.takeIf { it.matches(Regex("""\d+(-\d+)?""")) }
            ?: DEFAULT_HOP_INTERVAL
        // Xray needs at least five seconds between hops.
        val safe = seconds.split('-').joinToString("-") { maxOf(5, it.toInt()).toString() }
        return JSONObject().put("type", "udphop").put(
            "settings",
            JSONObject().put("remotePorts", cleanPorts).put("interval", safe).put("mode", "intervalLocal,intervalRemote")
        )
    }

    /** "100", "100 mbps", "50Mbps" -> "100 mbps"; Xray needs a unit and at least 1 mbps for brutal. */
    internal fun bandwidth(raw: String?): String? {
        val m = Regex("""^\s*(\d+(?:\.\d+)?)\s*([kmgt]?)(?:bps|b)?\s*$""", RegexOption.IGNORE_CASE).find(raw ?: return null)
            ?: return null
        val value = m.groupValues[1].toDouble()
        val unit = m.groupValues[2].lowercase().ifBlank { "m" }
        val mbps = when (unit) { "k" -> value / 1000; "g" -> value * 1000; "t" -> value * 1_000_000; else -> value }
        if (mbps < 1) return null
        return "${mbps.toLong()} mbps"
    }

    /** Hex SHA-256 pins, with or without colons, as Xray's pinnedPeerCertSha256 wants them. */
    internal fun normalizePin(raw: String?): String =
        raw.orEmpty().split(',').map { it.replace(":", "").trim().lowercase() }.filter { it.isNotBlank() }.joinToString(",")

    private fun String?.isTruthy() = this != null && (this == "1" || equals("true", ignoreCase = true))

    private fun firstPort(spec: String): Int {
        val first = spec.split(',').first().split('-').first().trim().toIntOrNull()
        require(first != null && first in 1..65535) { "Invalid port '$spec'" }
        return first
    }

    internal data class UriParts(
        val userInfo: String,
        val host: String,
        val portSpec: String,
        val params: Map<String, String>,
        val fragment: String
    )

    /** Splits a share link without java.net.URI, which rejects port lists and many real-world auth strings. */
    internal fun splitUri(uri: String, vararg schemes: String): UriParts {
        val trimmed = uri.trim()
        val sep = trimmed.indexOf("://")
        require(sep > 0 && schemes.any { it.equals(trimmed.substring(0, sep), ignoreCase = true) }) { "Not a ${schemes.first()} link" }
        var rest = trimmed.substring(sep + 3)
        val fragment = rest.substringAfter('#', "").let { decode(it) }
        rest = rest.substringBefore('#')
        val query = rest.substringAfter('?', "")
        rest = rest.substringBefore('?').trimEnd('/')
        val at = rest.lastIndexOf('@')
        val userInfo = if (at >= 0) decode(rest.substring(0, at).replace("+", "%2B")) else ""
        val hostPort = if (at >= 0) rest.substring(at + 1) else rest
        val host: String
        val portSpec: String
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            require(close > 0) { "Invalid IPv6 address" }
            host = hostPort.substring(1, close)
            portSpec = hostPort.substring(close + 1).removePrefix(":")
        } else {
            // A port list ("443,20000-50000") holds no ':', so the first ':' ends the host.
            host = hostPort.substringBefore(':')
            portSpec = hostPort.substringAfter(':', "")
        }
        val params = query.split('&').filter { it.contains('=') }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('='))
        }
        return UriParts(userInfo, host, portSpec, params, fragment)
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}

/** Keys of [VlessProfile.extraSettings], a JSON object for settings that have no column of their own. */
object ProfileExtras {
    const val XHTTP_MODE = "xhttpMode"
    const val XHTTP_EXTRA = "xhttpExtra"
    const val MLDSA65_VERIFY = "mldsa65Verify"
    const val WG_ADDRESS = "wgAddress"
    const val WG_RESERVED = "wgReserved"
    const val WG_MTU = "wgMtu"
    const val WG_PRESHARED_KEY = "wgPreSharedKey"
    const val WG_KEEPALIVE = "wgKeepAlive"

    val XHTTP_MODES = listOf("auto", "packet-up", "stream-up", "stream-one")

    fun read(profile: VlessProfile): JSONObject =
        profile.extraSettings.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun with(profile: VlessProfile, key: String, value: Any?): VlessProfile {
        val json = read(profile)
        if (value == null || value == "") json.remove(key) else json.put(key, value)
        return profile.copy(extraSettings = if (json.length() == 0) "" else json.toString())
    }

    /** Adds the XHTTP `mode` and `extra` and the REALITY `pqv` of a share link's query to [profile]. */
    fun fromQuery(profile: VlessProfile, params: Map<String, String>): VlessProfile {
        var p = profile
        val transport = p.transport.lowercase()
        if (transport == "xhttp" || transport == "splithttp") {
            params["mode"]?.takeIf { it.isNotBlank() }?.let { p = with(p, XHTTP_MODE, it) }
            params["extra"]?.takeIf { it.isNotBlank() }?.let { raw ->
                val extra = runCatching { JSONObject(raw) }.getOrNull()
                if (extra != null) p = with(p, XHTTP_EXTRA, extra)
            }
        }
        if (p.security.equals("reality", ignoreCase = true)) {
            params["pqv"]?.takeIf { it.isNotBlank() }?.let { p = with(p, MLDSA65_VERIFY, it) }
        }
        return p
    }
}
