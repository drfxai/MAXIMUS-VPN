package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Mihomo (MetaCubeX, GPL-3.0) as a separate program, for the proxies the Xray core cannot speak:
 * TUIC, AmneziaWG servers that change WireGuard's packet format, and the other Clash proxy types
 * (AnyTLS, Mieru, Snell, SSH, Hysteria v1...).
 *
 * Such a profile is [ProfileType.MIHOMO_YAML] with [VlessProfile.rawConfig] holding one Mihomo proxy
 * as JSON. Mihomo gets that proxy and a single rule sending everything to it, so it never routes
 * anything around the tunnel in either mode, and its SOCKS port needs this connection's login.
 */
object MihomoSidecar : SidecarEngine {
    override val id = "mihomo"
    override val binary = "mihomo"
    const val VERSION = "v1.19.32"
    private const val NODE = "node"
    private const val UPSTREAM = "maximus-upstream"

    /** Proxy types Mihomo carries here; anything else in a Clash file stays on Xray or is skipped. */
    val TYPES = setOf(
        "tuic", "wireguard", "anytls", "mieru", "snell", "ssh", "hysteria", "hysteria2", "vless", "vmess",
        "trojan", "ss", "ssr", "socks5", "http"
    )

    /**
     * DNS over HTTPS by address, for the proxy's own server name only: nothing is asked of the
     * network's resolver, which filtering networks answer with block pages.
     */
    private val RESOLVERS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "https://9.9.9.9/dns-query")

    override fun handles(profile: VlessProfile): Boolean =
        profile.profileType == ProfileType.MIHOMO_YAML && proxyOf(profile) != null

    override fun problem(profile: VlessProfile): String? {
        val proxy = proxyOf(profile) ?: return "This Mihomo profile has no proxy."
        val type = proxy.optString("type").lowercase()
        return when {
            type !in TYPES -> "Mihomo proxy type '$type' is not supported."
            proxy.optString("server").isBlank() || proxy.optInt("port") !in 1..65535 -> "This Mihomo proxy has no server or port."
            // Same rule as the Xray profiles: an unchecked certificate needs its fingerprint pinned.
            proxy.optBoolean("skip-cert-verify") && proxy.optString("fingerprint").isBlank() ->
                "This server's certificate is not checked. Add its SHA-256 fingerprint to the proxy (fingerprint)."
            else -> null
        }
    }

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        val proxy = requireNotNull(proxyOf(profile)) { "This Mihomo profile has no proxy." }
        val config = config(proxy, context)
        val file = File(context.workDir, "config.yaml")
        file.writeText(config.toString())
        return SidecarLaunch(
            command = listOf(context.executable.absolutePath, "-d", context.workDir.absolutePath, "-f", file.absolutePath),
            // Mihomo only reads and writes files under its home directory.
            environment = mapOf("SAFE_PATHS" to context.workDir.absolutePath),
            readyTimeoutMs = 10_000
        )
    }

    /** The whole Mihomo configuration (JSON is valid YAML). */
    fun config(proxy: JSONObject, context: SidecarContext): JSONObject = JSONObject()
        .put("socks-port", context.socksPort)
        .put("bind-address", "127.0.0.1")
        .put("allow-lan", false)
        .put("authentication", JSONArray().put("${context.socksUser}:${context.socksPass}"))
        .put("skip-auth-prefixes", JSONArray())
        .put("mode", "rule")
        .put("log-level", "warning")
        .put("ipv6", true)
        .put("tcp-concurrent", true)
        .put("find-process-mode", "off")
        .put("geo-auto-update", false)
        .put("profile", JSONObject().put("store-selected", false).put("store-fake-ip", false))
        .put("dns", JSONObject()
            .put("enable", true)
            .put("ipv6", true)
            .put("nameserver", JSONArray(RESOLVERS))
            .put("proxy-server-nameserver", JSONArray(RESOLVERS)))
        .let { base ->
            val node = JSONObject(proxy.toString()).put("name", NODE)
            val up = context.upstreamSocks
            if (up == null) {
                base.put("proxies", JSONArray().put(node))
            } else {
                // Chain hop: the node dials out through the next hop toward the exit.
                node.put("dialer-proxy", UPSTREAM)
                val upstream = JSONObject().put("name", UPSTREAM).put("type", "socks5")
                    .put("server", "127.0.0.1").put("port", up)
                base.put("proxies", JSONArray().put(node).put(upstream))
            }
        }
        .put("rules", JSONArray().put("MATCH,$NODE"))

    fun proxyOf(profile: VlessProfile): JSONObject? =
        profile.rawConfig.takeIf { profile.profileType == ProfileType.MIHOMO_YAML && it.trimStart().startsWith("{") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.has("type") }

    /** A profile for one Mihomo proxy, from a Clash file or built from a share link. */
    fun profile(proxy: Map<*, *>, sourceUrl: String? = null, sourceFile: String? = null): VlessProfile {
        val json = JSONObject()
        proxy.forEach { (k, v) -> json.put(k.toString(), toJson(v)) }
        val type = json.optString("type").lowercase()
        val server = json.optString("server")
        return VlessProfile(
            name = json.optString("name").ifBlank { "${type.uppercase()}-$server" },
            address = server,
            port = json.optInt("port"),
            uuid = "",
            transport = "mihomo",
            security = "none",
            sni = json.optString("sni").ifBlank { json.optString("servername") },
            rawConfig = json.toString(),
            profileType = ProfileType.MIHOMO_YAML,
            protocolType = protocolOf(type),
            engineType = EngineType.MIHOMO,
            subscriptionUrl = sourceUrl,
            sourceFile = sourceFile,
            nodeCount = 1
        )
    }

    private fun protocolOf(type: String): ProtocolType = when (type) {
        "tuic" -> ProtocolType.TUIC
        "wireguard" -> ProtocolType.WIREGUARD
        "hysteria", "hysteria2" -> ProtocolType.HYSTERIA2
        "vless" -> ProtocolType.VLESS
        "vmess" -> ProtocolType.VMESS
        "trojan" -> ProtocolType.TROJAN
        "ss", "ssr" -> ProtocolType.SHADOWSOCKS
        "socks5" -> ProtocolType.SOCKS5
        "http" -> ProtocolType.HTTP
        else -> ProtocolType.MIXED
    }

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> value.forEach { (k, v) -> o.put(k.toString(), toJson(v)) } }
        is List<*> -> JSONArray().also { a -> value.forEach { a.put(toJson(it)) } }
        else -> value
    }

    /**
     * A `tuic://` share link: `tuic://<uuid>:<password>@host:port?sni=..&alpn=h3&congestion_control=bbr
     * &udp_relay_mode=native&allow_insecure=0#name` (TUIC v5), or a bare token for TUIC v4.
     */
    fun fromTuicLink(userInfo: String, host: String, port: Int, params: Map<String, String>, name: String): Map<String, Any> {
        val proxy = linkedMapOf<String, Any>("name" to name, "type" to "tuic", "server" to host, "port" to port)
        if (userInfo.contains(':')) {
            proxy["uuid"] = userInfo.substringBefore(':')
            proxy["password"] = userInfo.substringAfter(':')
        } else {
            proxy["token"] = userInfo
        }
        (params["sni"] ?: params["peer"])?.takeIf { it.isNotBlank() }?.let { proxy["sni"] = it }
        proxy["alpn"] = (params["alpn"] ?: "h3").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        params["congestion_control"]?.takeIf { it.isNotBlank() }?.let { proxy["congestion-controller"] = it }
        params["udp_relay_mode"]?.takeIf { it.isNotBlank() }?.let { proxy["udp-relay-mode"] = it }
        if (params["disable_sni"] in setOf("1", "true")) proxy["disable-sni"] = true
        if (params["allow_insecure"] in setOf("1", "true") || params["insecure"] in setOf("1", "true")) proxy["skip-cert-verify"] = true
        (params["pinsha256"] ?: params["fingerprint"])?.takeIf { it.isNotBlank() }?.let { proxy["fingerprint"] = it }
        proxy["reduce-rtt"] = true
        return proxy
    }
}
