package com.example.vpn.engine

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * WireGuard and AmneziaWG `.conf` files ([Interface] and [Peer] sections) and Clash/Mihomo
 * `wireguard` proxies, run on the bundled Xray WireGuard client.
 *
 * AmneziaWG's junk packets (Jc, Jmin, Jmax) are sent by the client only, before the handshake, so
 * the server never needs to match them: they become an Xray UDP `noise` mask. Its header changes
 * (S1–S4 padding, H1–H4 message types) must match the server and change the WireGuard wire format,
 * which Xray cannot speak, so such a file is refused at import instead of failing at connect.
 */
object WireGuardConf {
    /** H1–H4 values of a server that keeps WireGuard's own message types. */
    private val STANDARD_HEADERS = mapOf("h1" to "1", "h2" to "2", "h3" to "3", "h4" to "4")

    class Unsupported(message: String) : IllegalArgumentException(message)

    fun looksLikeConf(text: String): Boolean =
        text.contains("[Interface]", ignoreCase = true) && text.contains("[Peer]", ignoreCase = true)

    /** Parses one `.conf` file. [name] is used when the file names no peer. */
    fun parse(text: String, name: String? = null): VlessProfile {
        val iface = mutableMapOf<String, String>()
        val peer = mutableMapOf<String, String>()
        var section: MutableMap<String, String>? = null
        var peers = 0
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            when {
                line.equals("[Interface]", ignoreCase = true) -> section = iface
                line.equals("[Peer]", ignoreCase = true) -> {
                    peers++
                    // Only the first peer is used; a second one would be a different server.
                    section = if (peers == 1) peer else null
                }
                line.contains('=') -> section?.let {
                    val key = line.substringBefore('=').trim().lowercase()
                    val value = line.substringAfter('=').trim()
                    // Address and AllowedIPs may be repeated; keep every value.
                    it[key] = it[key]?.let { old -> "$old,$value" } ?: value
                }
            }
        }
        val endpoint = peer["endpoint"].orEmpty()
        require(endpoint.isNotBlank()) { "The WireGuard file has no peer Endpoint" }
        val (host, port) = splitEndpoint(endpoint)
        return build(
            name = name?.takeIf { it.isNotBlank() } ?: "WireGuard-$host",
            host = host,
            port = port,
            privateKey = iface["privatekey"].orEmpty(),
            publicKey = peer["publickey"].orEmpty(),
            address = iface["address"].orEmpty(),
            preSharedKey = peer["presharedkey"].orEmpty(),
            mtu = iface["mtu"]?.toIntOrNull(),
            keepAlive = peer["persistentkeepalive"]?.toIntOrNull(),
            reserved = "",
            amnezia = iface.filterKeys { it in AMNEZIA_KEYS }
        )
    }

    /** A Clash/Mihomo `type: wireguard` proxy, including its `amnezia-wg-option`. */
    fun fromMihomo(map: Map<*, *>): VlessProfile {
        fun str(key: String) = map[key]?.toString()?.trim().orEmpty()
        val addresses = listOf(str("ip"), str("ipv6")).filter { it.isNotBlank() }.map { ip ->
            if (ip.contains('/')) ip else if (ip.contains(':')) "$ip/128" else "$ip/32"
        }
        val reserved = when (val r = map["reserved"]) {
            is List<*> -> r.joinToString(",") { it.toString().trim() }
            null -> ""
            else -> r.toString()
        }
        val awg = (map["amnezia-wg-option"] as? Map<*, *>)
            ?.entries?.associate { (k, v) -> k.toString().lowercase() to v.toString().trim() }
            ?.filterKeys { it in AMNEZIA_KEYS }
            .orEmpty()
        val server = str("server")
        val port = map["port"]?.toString()?.trim()?.toIntOrNull()
        require(server.isNotBlank() && port != null && port in 1..65535) { "The WireGuard proxy has no server or port" }
        return build(
            name = str("name").ifBlank { "WireGuard-$server" },
            host = server,
            port = port,
            privateKey = str("private-key"),
            publicKey = str("public-key"),
            address = addresses.joinToString(","),
            preSharedKey = str("pre-shared-key"),
            mtu = map["mtu"]?.toString()?.trim()?.toIntOrNull(),
            keepAlive = map["persistent-keepalive"]?.toString()?.trim()?.toIntOrNull(),
            reserved = reserved,
            amnezia = awg
        )
    }

    private val AMNEZIA_KEYS = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5"
    )

    private fun build(
        name: String,
        host: String,
        port: Int,
        privateKey: String,
        publicKey: String,
        address: String,
        preSharedKey: String,
        mtu: Int?,
        keepAlive: Int?,
        reserved: String,
        amnezia: Map<String, String>
    ): VlessProfile {
        require(privateKey.isNotBlank()) { "The WireGuard config has no PrivateKey" }
        require(publicKey.isNotBlank()) { "The WireGuard config has no peer PublicKey" }
        require(address.isNotBlank()) { "The WireGuard config has no interface Address" }
        checkAmneziaCompatible(amnezia)

        val extras = JSONObject().put(ProfileExtras.WG_ADDRESS, address.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))
        if (reserved.isNotBlank()) extras.put(ProfileExtras.WG_RESERVED, reserved)
        mtu?.let { extras.put(ProfileExtras.WG_MTU, it) }
        if (preSharedKey.isNotBlank()) extras.put(ProfileExtras.WG_PRESHARED_KEY, preSharedKey)
        keepAlive?.takeIf { it > 0 }?.let { extras.put(ProfileExtras.WG_KEEPALIVE, it) }

        return VlessProfile(
            name = name,
            address = host,
            port = port,
            uuid = privateKey,
            encryption = "none",
            transport = "udp",
            security = "none",
            publicKey = publicKey,
            extraSettings = extras.toString(),
            finalMask = junkMask(amnezia)?.toString().orEmpty(),
            profileType = ProfileType.VLESS,
            protocolType = ProtocolType.WIREGUARD,
            engineType = EngineType.XRAY
        )
    }

    /** Throws [Unsupported] when the server expects AmneziaWG's changed packet format. */
    internal fun checkAmneziaCompatible(awg: Map<String, String>) {
        val padding = listOf("s1", "s2", "s3", "s4").filter { (awg[it]?.toIntOrNull() ?: 0) != 0 }
        val headers = STANDARD_HEADERS.keys.filter { key -> awg[key]?.let { it != STANDARD_HEADERS[key] } == true }
        if (padding.isNotEmpty() || headers.isNotEmpty()) {
            val changed = (padding + headers).joinToString(", ") { it.uppercase() }
            throw Unsupported(
                "This AmneziaWG server changes WireGuard's packet format ($changed). MAXIMUS runs WireGuard on " +
                    "the Xray core, which only speaks the standard format. Ask for a config with S1/S2 = 0 and " +
                    "H1–H4 = 1–4 (junk packets Jc/Jmin/Jmax are fine), or a REALITY or Hysteria2 link."
            )
        }
    }

    /** Jc junk packets of Jmin..Jmax random bytes before each handshake, as AmneziaWG sends them. */
    internal fun junkMask(awg: Map<String, String>): JSONObject? {
        val count = awg["jc"]?.toIntOrNull()?.coerceIn(0, 128) ?: 0
        if (count == 0) return null
        val min = (awg["jmin"]?.toIntOrNull() ?: 40).coerceIn(1, 1280)
        val max = (awg["jmax"]?.toIntOrNull() ?: 70).coerceIn(min, 1280)
        val items = JSONArray()
        repeat(count) { items.put(JSONObject().put("rand", "$min-$max")) }
        // WireGuard re-handshakes every two minutes; send the junk again after that long without traffic.
        val noise = JSONObject().put("reset", 120).put("noise", items)
        return JSONObject().put("udp", JSONArray().put(JSONObject().put("type", "noise").put("settings", noise)))
    }

    private fun splitEndpoint(endpoint: String): Pair<String, Int> {
        val host: String
        val port: String
        if (endpoint.startsWith("[")) {
            host = endpoint.substringAfter('[').substringBefore(']')
            port = endpoint.substringAfter("]:", "")
        } else {
            host = endpoint.substringBeforeLast(':')
            port = endpoint.substringAfterLast(':', "")
        }
        val p = port.toIntOrNull()
        require(host.isNotBlank() && p != null && p in 1..65535) { "Invalid WireGuard Endpoint '$endpoint'" }
        return host to p
    }
}
