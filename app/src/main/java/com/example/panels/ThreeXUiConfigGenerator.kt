package com.example.panels

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Transmission protocol choices supported for 3X-UI inbound generation.
 */
enum class ThreeXUiProtocol(
    val displayName: String,
    val network: String,
    val tag: String,
    val description: String
) {
    WEBSOCKET("WebSocket", "ws", "WS", "Cloudflare CDN & HTTP/1.1 Upgrade"),
    XHTTP("xHTTP", "xhttp", "xHTTP", "Next-Gen Xray SplitHTTP / HTTP/3"),
    REALITY("Reality", "tcp", "REALITY", "Anti-censorship TLS camouflage direct to VPS"),
    RAW("RAW (TCP)", "tcp", "RAW", "Direct TCP stream, maximum throughput")
}

/**
 * Security types supported for 3X-UI inbound generation.
 */
enum class ThreeXUiSecurity(
    val displayName: String,
    val key: String,
    val description: String
) {
    NONE("None", "none", "Unencrypted / Cleartext (ideal for CDN fronting)"),
    TLS("TLS", "tls", "Standard TLS 1.3 with certificate"),
    REALITY("Reality", "reality", "X25519 reality handshake camouflage")
}

/**
 * Encapsulates the complete result of a 3X-UI automatic generation,
 * containing the local VlessProfile, server-side inbound JSON, and client URI.
 */
data class InboundGenerationResult(
    val profile: VlessProfile,
    val inboundJson: String,
    val clientUri: String,
    val protocol: ThreeXUiProtocol,
    val security: ThreeXUiSecurity,
    val port: Int,
    val uuid: String,
    val serverRemark: String,
    val publishedToPanel: Boolean,
    val panelHost: String,
    val statusMessage: String,
    /** True when the inbound port answered a TCP connect from this device. */
    val reachable: Boolean = true
)

/**
 * Generator engine for 3X-UI inbounds and matching Maximus VPN client configurations.
 *
 * Every configuration is created on the real panel, read back from the panel database, verified
 * against what was requested and probed over TCP from this device before it is handed to the app.
 */
class ThreeXUiConfigGenerator(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val reachabilityProbe: (host: String, port: Int) -> Boolean = { h, p -> tcpReachable(h, p) }
) {

    fun generate3xuiRemark(): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val rnd = SecureRandom()
        val tag = (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        return "X-$tag"
    }

    /**
     * Rapid 1-click VLESS TCP configuration (no transport security). Kept for networks where the
     * plain link is wanted; [generateInbound] with Reality is the recommended default.
     */
    fun generateRapidVlessTcp(
        panel: ManagedPanel,
        targetPort: Int? = null,
        targetUuid: String? = null
    ): InboundGenerationResult {
        require(panel.host.isNotBlank() && (panel.password.isNotBlank() || panel.apiToken.isNotBlank())) {
            "A configured 3X-UI panel with server host and access details is required."
        }
        return generateInbound(
            panel = panel,
            protocol = ThreeXUiProtocol.RAW,
            security = ThreeXUiSecurity.NONE,
            customPort = targetPort,
            customUuid = targetUuid,
            customRemark = generate3xuiRemark()
        )
    }

    /**
     * Creates the best available configuration for a fresh server: VLESS + Reality + Vision on a
     * port that is free and, whenever possible, 443.
     */
    fun generateRecommended(panel: ManagedPanel, sni: String? = null): InboundGenerationResult =
        generateInbound(
            panel = panel,
            protocol = ThreeXUiProtocol.REALITY,
            security = ThreeXUiSecurity.REALITY,
            customHostOrSni = sni
        )

    fun generateInbound(
        panel: ManagedPanel,
        protocol: ThreeXUiProtocol,
        security: ThreeXUiSecurity,
        customPort: Int? = null,
        customUuid: String? = null,
        customRemark: String? = null,
        customHostOrSni: String? = null,
        customPath: String? = null
    ): InboundGenerationResult {
        require(panel.host.isNotBlank() && (panel.password.isNotBlank() || panel.apiToken.isNotBlank())) {
            "A configured 3X-UI server panel with valid access details is required."
        }
        val host = panel.host.trim()
        val uuid = customUuid ?: UUID.randomUUID().toString()
        val effectiveSecurity = if (protocol == ThreeXUiProtocol.REALITY) ThreeXUiSecurity.REALITY else security
        // Xray refuses to start with a WebSocket + REALITY inbound, which takes every inbound on
        // the server down with it, so that combination must never reach the panel.
        require(!(protocol == ThreeXUiProtocol.WEBSOCKET && effectiveSecurity == ThreeXUiSecurity.REALITY)) {
            "Reality works with RAW (TCP) or xHTTP, not WebSocket. Pick TLS or None for WebSocket."
        }

        val api = XuiApiClient(
            panelUrl = panel.url,
            apiToken = panel.apiToken,
            username = panel.username,
            password = panel.password,
            httpClient = httpClient,
            pinnedCertSha256 = panel.certSha256
        )
        api.requireAccess()

        // Reality material comes from the panel itself; it is never invented locally.
        var realityPrivate = ""
        var realityPublic = ""
        var realityShortId = ""
        var realitySni = ""
        if (effectiveSecurity == ThreeXUiSecurity.REALITY) {
            val keys = api.newX25519()
            realityPrivate = keys.first
            realityPublic = keys.second
            realityShortId = randomHex(8)
            realitySni = customHostOrSni?.trim().orEmpty().ifBlank { DEFAULT_REALITY_SNI }
        }

        val tlsCert: Pair<String, String>? = if (effectiveSecurity == ThreeXUiSecurity.TLS) {
            val cert = requireNotNull(api.panelCertificate()) {
                "TLS needs a certificate on the server and this panel has none. " +
                    "Use Reality (recommended) or None behind a CDN, or install a certificate in 3X-UI first."
            }
            // The self-signed certificate Maximus creates for the panel itself is only trusted by
            // pinning; VPN clients would reject it, so a TLS inbound built on it never connects.
            require(!cert.first.contains(SELF_SIGNED_CERT_DIR)) {
                "This server only has the self-signed panel certificate, which VPN clients do not trust. " +
                    "Use Reality (recommended) or None behind a CDN, or install a domain certificate in 3X-UI first."
            }
            cert
        } else null

        val used = api.usedPorts() + panelPort(panel)
        val candidates = if (customPort != null) {
            require(customPort in 1..65535) { "Invalid port" }
            require(customPort !in used) { "Port $customPort is already used on this server" }
            listOf(customPort)
        } else {
            candidatePorts(protocol, effectiveSecurity).filter { it !in used }.take(MAX_PORT_ATTEMPTS)
        }
        check(candidates.isNotEmpty()) { "No free port available on the server" }

        var lastFailure = ""
        for ((index, port) in candidates.withIndex()) {
            val remark = customRemark ?: when {
                protocol == ThreeXUiProtocol.RAW && effectiveSecurity == ThreeXUiSecurity.NONE -> generate3xuiRemark()
                else -> "Maximus-${protocol.tag}-${effectiveSecurity.key.uppercase()}-$port"
            }
            val email = "maximus-${randomLower(6)}"
            val flow = if (effectiveSecurity == ThreeXUiSecurity.REALITY && protocol.network == "tcp") "xtls-rprx-vision" else ""
            val wsPath = customPath?.takeIf { it.isNotBlank() } ?: "/maximus-ws"
            val xhttpPath = customPath?.takeIf { it.isNotBlank() } ?: "/maximus-xhttp"
            val hostHeader = customHostOrSni?.trim().orEmpty().ifBlank { host }

            val settings = JSONObject()
                .put("clients", JSONArray().put(
                    JSONObject()
                        .put("id", uuid)
                        .put("flow", flow)
                        .put("email", email)
                        .put("limitIp", 0)
                        .put("totalGB", 0)
                        .put("expiryTime", 0)
                        .put("enable", true)
                        .put("tgId", 0)
                        .put("subId", randomLower(16))
                        .put("comment", "")
                        .put("reset", 0)
                ))
                .put("decryption", "none")
                .put("fallbacks", JSONArray())

            val stream = JSONObject().put("network", protocol.network).put("security", effectiveSecurity.key)
            when (protocol) {
                ThreeXUiProtocol.WEBSOCKET -> stream.put(
                    "wsSettings",
                    // Current Xray reads "host" directly; the old headers.Host form is deprecated.
                    JSONObject().put("path", wsPath).put("host", hostHeader)
                )
                ThreeXUiProtocol.XHTTP -> stream.put(
                    "xhttpSettings",
                    JSONObject().put("path", xhttpPath).put("host", hostHeader).put("mode", "auto")
                )
                ThreeXUiProtocol.RAW, ThreeXUiProtocol.REALITY -> stream.put(
                    "tcpSettings",
                    JSONObject().put("acceptProxyProtocol", false)
                        .put("header", JSONObject().put("type", "none"))
                )
            }
            when (effectiveSecurity) {
                ThreeXUiSecurity.REALITY -> stream.put(
                    "realitySettings",
                    JSONObject()
                        .put("show", false)
                        .put("xver", 0)
                        .put("dest", "$realitySni:443")
                        .put("target", "$realitySni:443")
                        .put("serverNames", JSONArray().put(realitySni))
                        .put("privateKey", realityPrivate)
                        .put("minClient", "")
                        .put("maxClient", "")
                        .put("maxTimediff", 0)
                        .put("shortIds", JSONArray().put(realityShortId))
                        // 3X-UI builds client links from this block; without publicKey the link has no pbk.
                        .put(
                            "settings",
                            JSONObject()
                                .put("publicKey", realityPublic)
                                .put("fingerprint", "chrome")
                                .put("serverName", "")
                                .put("spiderX", "/")
                        )
                )
                ThreeXUiSecurity.TLS -> stream.put(
                    "tlsSettings",
                    JSONObject()
                        .put("serverName", hostHeader)
                        .put("alpn", JSONArray().put("h2").put("http/1.1"))
                        .put("certificates", JSONArray().put(
                            JSONObject()
                                .put("certificateFile", tlsCert?.first.orEmpty())
                                .put("keyFile", tlsCert?.second.orEmpty())
                        ))
                )
                ThreeXUiSecurity.NONE -> Unit
            }

            val sniffing = JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                .put("metadataOnly", false)
                .put("routeOnly", false)

            val created = api.addInbound(remark, port, "vless", settings, stream, sniffing)

            // 1) Read it back and make sure the panel stored exactly what we asked for.
            val stored = api.readBack(port)
            val problem = verifyStored(stored, uuid, protocol, effectiveSecurity)
            if (problem != null) {
                api.deleteInbound(created.id)
                throw IllegalStateException("3X-UI stored a different inbound than requested: $problem")
            }

            // 2) Build the client link; prefer the panel's own export when it is complete.
            val ownUri = buildVlessUri(
                uuid, host, port, protocol, effectiveSecurity, flow, realitySni,
                if (protocol == ThreeXUiProtocol.XHTTP) xhttpPath else wsPath,
                hostHeader, realityPublic, realityShortId, remark
            )
            val panelUri = api.allLinks().firstOrNull { link ->
                link.startsWith("vless://") && link.contains(uuid, ignoreCase = true) &&
                    link.contains("@$host:$port") &&
                    (effectiveSecurity != ThreeXUiSecurity.REALITY || link.contains("pbk="))
            }
            val clientUri = panelUri ?: ownUri

            // 3) The port must answer from this device; otherwise free it and try the next one.
            val reachable = reachabilityProbe(host, port)
            if (!reachable && index < candidates.lastIndex) {
                lastFailure = "port $port did not answer"
                api.deleteInbound(created.id)
                continue
            }

            val profile = VlessProfile(
                id = UUID.randomUUID().toString(),
                name = remark,
                address = host,
                port = port,
                uuid = uuid,
                encryption = "none",
                transport = protocol.network,
                security = effectiveSecurity.key,
                sni = when (effectiveSecurity) {
                    ThreeXUiSecurity.REALITY -> realitySni
                    ThreeXUiSecurity.TLS -> hostHeader
                    ThreeXUiSecurity.NONE -> ""
                },
                host = if (protocol == ThreeXUiProtocol.WEBSOCKET || protocol == ThreeXUiProtocol.XHTTP) hostHeader else "",
                path = when (protocol) {
                    ThreeXUiProtocol.WEBSOCKET -> wsPath
                    ThreeXUiProtocol.XHTTP -> xhttpPath
                    else -> ""
                },
                flow = flow,
                fingerprint = if (effectiveSecurity != ThreeXUiSecurity.NONE) "chrome" else "",
                publicKey = realityPublic,
                shortId = realityShortId,
                spiderX = if (effectiveSecurity == ThreeXUiSecurity.REALITY) "/" else "",
                protocolType = ProtocolType.VLESS,
                profileType = ProfileType.VLESS,
                engineType = EngineType.XRAY
            )
            val message = if (reachable) {
                "Inbound created, verified in the panel and reachable ($host:$port)"
            } else {
                "Inbound created and verified in the panel, but TCP $port does not answer from this device. " +
                    "Open TCP $port in your VPS/cloud firewall."
            }
            return InboundGenerationResult(
                profile = profile,
                inboundJson = JSONObject()
                    .put("remark", remark).put("port", port).put("protocol", "vless")
                    .put("settings", settings.toString()).put("streamSettings", stream.toString())
                    .put("sniffing", sniffing.toString()).toString(2),
                clientUri = clientUri,
                protocol = protocol,
                security = effectiveSecurity,
                port = port,
                uuid = uuid,
                serverRemark = remark,
                publishedToPanel = true,
                panelHost = host,
                statusMessage = message,
                reachable = reachable
            )
        }
        error("Could not create a reachable inbound ($lastFailure). Check the server firewall.")
    }

    private fun verifyStored(
        stored: JSONObject?,
        uuid: String,
        protocol: ThreeXUiProtocol,
        security: ThreeXUiSecurity
    ): String? {
        if (stored == null) return "the inbound is not listed by the panel"
        if (!stored.optBoolean("enable", true)) return "the inbound is disabled"
        val settings = asObject(stored.opt("settings")) ?: return "settings are unreadable"
        val clients = settings.optJSONArray("clients") ?: return "no clients stored"
        val hasClient = (0 until clients.length()).any {
            clients.optJSONObject(it)?.optString("id").equals(uuid, ignoreCase = true)
        }
        if (!hasClient) return "client id was not stored"
        val stream = asObject(stored.opt("streamSettings")) ?: return "stream settings are unreadable"
        if (stream.optString("network") != protocol.network) return "network is ${stream.optString("network")}"
        if (stream.optString("security", "none") != security.key) return "security is ${stream.optString("security")}"
        return null
    }

    private fun asObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun panelPort(panel: ManagedPanel): Int =
        runCatching { java.net.URI(panel.url).port }.getOrDefault(-1)

    private fun candidatePorts(protocol: ThreeXUiProtocol, security: ThreeXUiSecurity): List<Int> {
        val randoms = List(3) { 20000 + SecureRandom().nextInt(40000) }
        val preferred = when {
            security == ThreeXUiSecurity.REALITY -> listOf(443, 8443)
            protocol == ThreeXUiProtocol.WEBSOCKET && security == ThreeXUiSecurity.TLS -> listOf(443, 8443, 2053, 2083, 2087, 2096)
            protocol == ThreeXUiProtocol.WEBSOCKET || protocol == ThreeXUiProtocol.XHTTP ->
                listOf(8080, 8880, 2052, 2082, 2086, 2095)
            security == ThreeXUiSecurity.TLS -> listOf(443, 8443)
            else -> emptyList()
        }
        return preferred + randoms
    }

    private fun buildVlessUri(
        uuid: String,
        host: String,
        port: Int,
        protocol: ThreeXUiProtocol,
        security: ThreeXUiSecurity,
        flow: String,
        sni: String,
        path: String,
        hostHeader: String,
        publicKey: String,
        shortId: String,
        remark: String
    ): String {
        val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }
        val q = mutableListOf("type=${protocol.network}")
        if (security != ThreeXUiSecurity.NONE) q.add("security=${security.key}")
        if (flow.isNotBlank()) q.add("flow=${enc(flow)}")
        when (security) {
            ThreeXUiSecurity.REALITY -> {
                q.add("pbk=${enc(publicKey)}")
                q.add("fp=chrome")
                q.add("sni=${enc(sni)}")
                if (shortId.isNotBlank()) q.add("sid=${enc(shortId)}")
                q.add("spx=%2F")
            }
            ThreeXUiSecurity.TLS -> {
                q.add("sni=${enc(hostHeader)}")
                q.add("fp=chrome")
            }
            ThreeXUiSecurity.NONE -> Unit
        }
        if (protocol == ThreeXUiProtocol.WEBSOCKET || protocol == ThreeXUiProtocol.XHTTP) {
            if (path.isNotBlank()) q.add("path=${enc(path)}")
            if (hostHeader.isNotBlank()) q.add("host=${enc(hostHeader)}")
            if (protocol == ThreeXUiProtocol.XHTTP) q.add("mode=auto")
        }
        val authority = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "vless://$uuid@$authority:$port?${q.joinToString("&")}#${enc(remark)}"
    }

    private fun randomLower(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz"
        val rnd = SecureRandom()
        return (1..len).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        val rnd = SecureRandom()
        return (1..len).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        const val DEFAULT_REALITY_SNI = "www.microsoft.com"
        /** Where PanelProvisioner stores the pinned self-signed panel certificate. */
        private const val SELF_SIGNED_CERT_DIR = "/etc/x-ui/maximus-tls/"
        private const val MAX_PORT_ATTEMPTS = 4

        /** TCP connect probe used to prove the new inbound is reachable from this device. */
        fun tcpReachable(host: String, port: Int): Boolean {
            repeat(2) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), 5_000)
                        return true
                    }
                } catch (_: Exception) {
                    // retry once
                }
            }
            return false
        }
    }
}
