package com.example.panels

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProtocolLinks
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
    val description: String,
    /** Security this protocol always uses; null when the user picks it. */
    val fixedSecurity: ThreeXUiSecurity? = null
) {
    WEBSOCKET("WebSocket", "ws", "WS", "Cloudflare CDN & HTTP/1.1 Upgrade"),
    XHTTP("xHTTP", "xhttp", "xHTTP", "Next-Gen Xray SplitHTTP / HTTP/3"),
    REALITY("Reality", "tcp", "REALITY", "Anti-censorship TLS camouflage direct to VPS", ThreeXUiSecurity.REALITY),
    RAW("RAW (TCP)", "tcp", "RAW", "Direct TCP stream, maximum throughput"),
    HTTPUPGRADE("HTTPUpgrade", "httpupgrade", "HU", "Lean HTTP/1.1 Upgrade, CDN friendly and lighter than WebSocket"),
    HYSTERIA2("Hysteria2", "hysteria", "HY2", "QUIC over UDP, fast on lossy networks", ThreeXUiSecurity.TLS),
    WIREGUARD("WireGuard", "wireguard", "WG", "Classic UDP VPN tunnel, simple and quick", ThreeXUiSecurity.NONE);

    /** Hysteria2 and WireGuard listen on UDP, so they can share a port number with a TCP inbound. */
    val isUdp: Boolean get() = this == HYSTERIA2 || this == WIREGUARD

    /** Security choices that make a working inbound with this protocol. */
    val allowedSecurities: List<ThreeXUiSecurity>
        get() = fixedSecurity?.let { listOf(it) } ?: when (this) {
            XHTTP -> ThreeXUiSecurity.values().toList()
            else -> listOf(ThreeXUiSecurity.NONE, ThreeXUiSecurity.TLS)
        }
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
    val reachable: Boolean = true,
    /**
     * False for UDP inbounds (Hysteria2, WireGuard): a UDP port gives no answer to a connect, so
     * only a real request through the config (the protocol test) shows that it works.
     */
    val portChecked: Boolean = true,
    /** The panel's id for the inbound, so a test inbound can be removed again. */
    val inboundId: Int = -1
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
        val effectiveSecurity = protocol.fixedSecurity ?: security
        // Xray refuses to start with a WebSocket + REALITY inbound, which takes every inbound on
        // the server down with it, so that combination must never reach the panel.
        require(effectiveSecurity in protocol.allowedSecurities) {
            "Reality works with RAW (TCP) or xHTTP, not ${protocol.displayName}. " +
                "Pick ${protocol.allowedSecurities.joinToString(" or ") { it.displayName }} for ${protocol.displayName}."
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

        when (protocol) {
            ThreeXUiProtocol.HYSTERIA2 -> return generateHysteria2(api, panel, host, customPort, customUuid, customRemark, customHostOrSni)
            ThreeXUiProtocol.WIREGUARD -> return generateWireGuard(api, panel, host, customPort, customRemark)
            else -> Unit
        }

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

        val candidates = freePorts(api, panel, protocol, effectiveSecurity, customPort)

        // Xray clients refuse VLESS without TLS to a public address, so an inbound without
        // transport security gets VLESS Encryption instead. It also keeps the traffic private
        // from a CDN in front of the server.
        val vlessEncryption: Pair<String, String>? =
            if (effectiveSecurity == ThreeXUiSecurity.NONE) api.newVlessEncryption() else null

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
            val httpUpgradePath = customPath?.takeIf { it.isNotBlank() } ?: "/maximus-hu"
            val transportPath = when (protocol) {
                ThreeXUiProtocol.XHTTP -> xhttpPath
                ThreeXUiProtocol.HTTPUPGRADE -> httpUpgradePath
                ThreeXUiProtocol.WEBSOCKET -> wsPath
                else -> ""
            }
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
                .put("decryption", vlessEncryption?.first ?: "none")
                // Xray refuses "fallbacks" next to a decryption key, and that stops every inbound on the server.
                .apply { if (vlessEncryption == null) put("fallbacks", JSONArray()) }

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
                ThreeXUiProtocol.HTTPUPGRADE -> stream.put(
                    "httpupgradeSettings",
                    JSONObject().put("acceptProxyProtocol", false).put("path", httpUpgradePath).put("host", hostHeader)
                )
                ThreeXUiProtocol.RAW, ThreeXUiProtocol.REALITY -> stream.put(
                    "tcpSettings",
                    JSONObject().put("acceptProxyProtocol", false)
                        .put("header", JSONObject().put("type", "none"))
                )
                ThreeXUiProtocol.HYSTERIA2, ThreeXUiProtocol.WIREGUARD -> error("not a VLESS transport")
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

            val sniffing = defaultSniffing()

            val created = api.addInbound(remark, port, "vless", settings, stream, sniffing)

            // 1) Read it back and make sure the panel stored exactly what we asked for.
            val stored = api.readBack(port)
            val problem = verifyStored(stored, uuid, protocol, effectiveSecurity, vlessEncryption?.first ?: "none")
            if (problem != null) {
                api.deleteInbound(created.id)
                throw IllegalStateException("3X-UI stored a different inbound than requested: $problem")
            }

            // 2) Build the client link; prefer the panel's own export when it is complete.
            val ownUri = buildVlessUri(
                uuid, host, port, protocol, effectiveSecurity, flow, realitySni,
                transportPath,
                hostHeader, realityPublic, realityShortId, remark, vlessEncryption?.second ?: "none"
            )
            val panelUri = api.allLinks().firstOrNull { link ->
                link.startsWith("vless://") && link.contains(uuid, ignoreCase = true) &&
                    link.contains("@$host:$port") &&
                    (effectiveSecurity != ThreeXUiSecurity.REALITY || link.contains("pbk=")) &&
                    (vlessEncryption == null || link.contains("encryption=mlkem"))
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
                encryption = vlessEncryption?.second ?: "none",
                transport = protocol.network,
                security = effectiveSecurity.key,
                sni = when (effectiveSecurity) {
                    ThreeXUiSecurity.REALITY -> realitySni
                    ThreeXUiSecurity.TLS -> hostHeader
                    ThreeXUiSecurity.NONE -> ""
                },
                host = if (transportPath.isNotBlank()) hostHeader else "",
                path = transportPath,
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
                reachable = reachable,
                inboundId = created.id
            )
        }
        error("Could not create a reachable inbound ($lastFailure). Check the server firewall.")
    }

    /**
     * Hysteria2 (QUIC over UDP). It needs a TLS certificate: a real one installed in 3X-UI, or the
     * self-signed panel certificate Maximus created, which the client then pins by its SHA-256.
     */
    private fun generateHysteria2(
        api: XuiApiClient,
        panel: ManagedPanel,
        host: String,
        customPort: Int?,
        customAuth: String?,
        customRemark: String?,
        customSni: String?
    ): InboundGenerationResult {
        val cert = requireNotNull(api.panelCertificate()) {
            "Hysteria2 needs a TLS certificate on the server and this panel has none. " +
                "Reinstall the panel from Maximus (it creates one) or install a certificate in 3X-UI first."
        }
        val selfSigned = cert.first.contains(SELF_SIGNED_CERT_DIR)
        // Xray clients no longer skip certificate checks, so a self-signed certificate only works pinned.
        require(!selfSigned || panel.certSha256.isNotBlank()) {
            "The server uses the self-signed panel certificate but its fingerprint was not recorded. " +
                "Reinstall the panel from Maximus so the fingerprint can be pinned."
        }
        val pin = if (selfSigned) panel.certSha256.lowercase() else ""
        val sni = customSni?.trim().orEmpty().ifBlank { host }
        val auth = customAuth?.takeIf { it.isNotBlank() } ?: randomLower(24)
        val port = freePorts(api, panel, ThreeXUiProtocol.HYSTERIA2, ThreeXUiSecurity.TLS, customPort).first()
        val remark = customRemark ?: "Maximus-HY2-$port"

        val settings = JSONObject()
            .put("version", 2)
            .put("clients", JSONArray().put(newClient().put("auth", auth)))
        val stream = JSONObject()
            .put("network", "hysteria")
            .put("security", "tls")
            .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", "").put("udpIdleTimeout", 60))
            .put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", sni)
                    .put("alpn", JSONArray().put("h3"))
                    .put("certificates", JSONArray().put(
                        JSONObject().put("certificateFile", cert.first).put("keyFile", cert.second)
                    ))
            )
        val sniffing = defaultSniffing()
        val created = api.addInbound(remark, port, "hysteria", settings, stream, sniffing)

        val stored = api.readBack(port, udp = true)
        val problem = when {
            stored == null -> "the inbound is not listed by the panel"
            stored.optString("protocol") != "hysteria" -> "protocol is ${stored.optString("protocol")}"
            !clientStored(stored, "auth", auth) -> "client auth was not stored"
            else -> null
        }
        if (problem != null) {
            api.deleteInbound(created.id)
            throw IllegalStateException("3X-UI stored a different inbound than requested: $problem")
        }

        val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }
        val query = mutableListOf("sni=${enc(sni)}", "alpn=h3")
        if (pin.isNotBlank()) query.add("pinSHA256=${enc(pin)}")
        val uri = "hysteria2://${enc(auth)}@${authority(host)}:$port/?${query.joinToString("&")}#${enc(remark)}"
        val profile = ProtocolLinks.parseHysteria2(uri).copy(id = UUID.randomUUID().toString(), name = remark)
        return udpResult(profile, remark, port, auth, settings, stream, sniffing, "hysteria", uri, ThreeXUiProtocol.HYSTERIA2, ThreeXUiSecurity.TLS, host)
            .copy(inboundId = created.id)
    }

    /**
     * WireGuard. The server and client key pairs both come from the panel's own key generator.
     * 3X-UI does not give a client created with the inbound a tunnel address, so one is picked
     * here that no other WireGuard client on the server uses.
     */
    private fun generateWireGuard(
        api: XuiApiClient,
        panel: ManagedPanel,
        host: String,
        customPort: Int?,
        customRemark: String?
    ): InboundGenerationResult {
        val (serverPrivate, serverPublic) = api.newX25519().let { wireGuardKey(it.first) to wireGuardKey(it.second) }
        val (clientPrivate, clientPublic) = api.newX25519().let { wireGuardKey(it.first) to wireGuardKey(it.second) }
        val port = freePorts(api, panel, ThreeXUiProtocol.WIREGUARD, ThreeXUiSecurity.NONE, customPort).first()
        val remark = customRemark ?: "Maximus-WG-$port"
        val address = freeWireGuardAddress(api.listInbounds())

        val settings = JSONObject()
            .put("mtu", 1420)
            .put("secretKey", serverPrivate)
            .put("peers", JSONArray())
            .put("clients", JSONArray().put(
                newClient().put("privateKey", clientPrivate).put("publicKey", clientPublic)
                    .put("allowedIPs", JSONArray().put(address)).put("keepAlive", 25)
            ))
            .put("noKernelTun", false)
        // WireGuard has no selectable transport: 3X-UI stores its stream settings without a network.
        val stream = JSONObject().put("security", "none")
        val sniffing = defaultSniffing()
        val created = api.addInbound(remark, port, "wireguard", settings, stream, sniffing)

        val stored = api.readBack(port, udp = true)
        val storedClient = stored?.let { asObject(it.opt("settings")) }?.optJSONArray("clients")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .firstOrNull { it.optString("publicKey") == clientPublic }
        }
        val problem = when {
            stored == null -> "the inbound is not listed by the panel"
            stored.optString("protocol") != "wireguard" -> "protocol is ${stored.optString("protocol")}"
            storedClient == null -> "the client key was not stored"
            storedClient.optJSONArray("allowedIPs")?.optString(0) != address -> "the client tunnel address was not stored"
            else -> null
        }
        if (problem != null) {
            api.deleteInbound(created.id)
            throw IllegalStateException("3X-UI stored a different inbound than requested: $problem")
        }

        val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }
        val uri = "wireguard://${enc(clientPrivate)}@${authority(host)}:$port" +
            "?publickey=${enc(serverPublic)}&address=${enc(address)}&mtu=$WG_MTU&keepalive=25#${enc(remark)}"
        val profile = ProtocolLinks.parseWireGuard(uri).copy(id = UUID.randomUUID().toString(), name = remark)
        return udpResult(profile, remark, port, clientPublic, settings, stream, sniffing, "wireguard", uri, ThreeXUiProtocol.WIREGUARD, ThreeXUiSecurity.NONE, host)
            .copy(inboundId = created.id)
    }

    /** First 10.0.0.x/32 that no WireGuard client or peer on the server already uses. */
    private fun freeWireGuardAddress(inbounds: JSONArray): String {
        val used = mutableSetOf<String>()
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("protocol") != "wireguard") continue
            val settings = asObject(inbound.opt("settings")) ?: continue
            for (key in listOf("clients", "peers")) {
                val list = settings.optJSONArray(key) ?: continue
                for (j in 0 until list.length()) {
                    val ips = list.optJSONObject(j)?.optJSONArray("allowedIPs") ?: continue
                    for (k in 0 until ips.length()) used.add(ips.optString(k).substringBefore('/'))
                }
            }
        }
        val free = (2..254).firstOrNull { "10.0.0.$it" !in used } ?: error("Every WireGuard address on this server is taken")
        return "10.0.0.$free/32"
    }

    private fun udpResult(
        profile: VlessProfile,
        remark: String,
        port: Int,
        id: String,
        settings: JSONObject,
        stream: JSONObject,
        sniffing: JSONObject,
        protocolName: String,
        uri: String,
        protocol: ThreeXUiProtocol,
        security: ThreeXUiSecurity,
        host: String
    ) = InboundGenerationResult(
        profile = profile,
        inboundJson = JSONObject()
            .put("remark", remark).put("port", port).put("protocol", protocolName)
            .put("settings", settings.toString()).put("streamSettings", stream.toString())
            .put("sniffing", sniffing.toString()).toString(2),
        clientUri = uri,
        protocol = protocol,
        security = security,
        port = port,
        uuid = id,
        serverRemark = remark,
        publishedToPanel = true,
        panelHost = host,
        statusMessage = "Inbound created and verified in the panel ($host:$port UDP). " +
            "Make sure UDP $port is open in your VPS/cloud firewall.",
        reachable = true,
        portChecked = false
    )

    /** Ports to try, skipping ones already bound on the same transport (TCP and UDP may share a number). */
    private fun freePorts(
        api: XuiApiClient,
        panel: ManagedPanel,
        protocol: ThreeXUiProtocol,
        security: ThreeXUiSecurity,
        customPort: Int?
    ): List<Int> {
        val used = api.usedPorts(udp = protocol.isUdp) + if (protocol.isUdp) emptySet() else setOf(panelPort(panel))
        val candidates = if (customPort != null) {
            require(customPort in 1..65535) { "Invalid port" }
            require(customPort !in used) { "Port $customPort is already used on this server" }
            listOf(customPort)
        } else {
            candidatePorts(protocol, security).filter { it !in used }.distinct().take(MAX_PORT_ATTEMPTS)
        }
        check(candidates.isNotEmpty()) { "No free port available on the server" }
        return candidates
    }

    private fun newClient(): JSONObject = JSONObject()
        .put("email", "maximus-${randomLower(6)}")
        .put("limitIp", 0)
        .put("totalGB", 0)
        .put("expiryTime", 0)
        .put("enable", true)
        .put("tgId", 0)
        .put("subId", randomLower(16))
        .put("comment", "")
        .put("reset", 0)

    private fun defaultSniffing(): JSONObject = JSONObject()
        .put("enabled", true)
        .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
        .put("metadataOnly", false)
        .put("routeOnly", false)

    private fun clientStored(stored: JSONObject, key: String, value: String): Boolean {
        val clients = asObject(stored.opt("settings"))?.optJSONArray("clients") ?: return false
        return (0 until clients.length()).any { clients.optJSONObject(it)?.optString(key) == value }
    }

    private fun authority(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    /** Removes an inbound this generator created, e.g. a protocol-test inbound the user did not keep. */
    fun deleteInbound(panel: ManagedPanel, inboundId: Int) {
        XuiApiClient(
            panelUrl = panel.url,
            apiToken = panel.apiToken,
            username = panel.username,
            password = panel.password,
            httpClient = httpClient,
            pinnedCertSha256 = panel.certSha256
        ).deleteInbound(inboundId)
    }

    private fun verifyStored(
        stored: JSONObject?,
        uuid: String,
        protocol: ThreeXUiProtocol,
        security: ThreeXUiSecurity,
        decryption: String
    ): String? {
        if (stored == null) return "the inbound is not listed by the panel"
        if (!stored.optBoolean("enable", true)) return "the inbound is disabled"
        val settings = asObject(stored.opt("settings")) ?: return "settings are unreadable"
        val clients = settings.optJSONArray("clients") ?: return "no clients stored"
        val hasClient = (0 until clients.length()).any {
            clients.optJSONObject(it)?.optString("id").equals(uuid, ignoreCase = true)
        }
        if (!hasClient) return "client id was not stored"
        if (settings.optString("decryption", "none") != decryption) return "VLESS decryption was not stored"
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
            protocol == ThreeXUiProtocol.HYSTERIA2 -> listOf(443, 8443)
            protocol == ThreeXUiProtocol.WIREGUARD -> listOf(51820, 2408)
            protocol in CDN_PROTOCOLS && security == ThreeXUiSecurity.TLS -> listOf(443, 8443, 2053, 2083, 2087, 2096)
            protocol in CDN_PROTOCOLS ->
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
        remark: String,
        encryption: String
    ): String {
        val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }
        val q = mutableListOf("type=${protocol.network}")
        if (encryption != "none") q.add("encryption=${enc(encryption)}")
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
        if (protocol in CDN_PROTOCOLS) {
            if (path.isNotBlank()) q.add("path=${enc(path)}")
            if (hostHeader.isNotBlank()) q.add("host=${enc(hostHeader)}")
            if (protocol == ThreeXUiProtocol.XHTTP) q.add("mode=auto")
        }
        return "vless://$uuid@${authority(host)}:$port?${q.joinToString("&")}#${enc(remark)}"
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
        /**
         * Xray prints X25519 keys as unpadded URL-safe base64; WireGuard configs use standard
         * padded base64 of the same 32 bytes.
         */
        fun wireGuardKey(xrayKey: String): String {
            val raw = java.util.Base64.getUrlDecoder().decode(xrayKey.trim().trimEnd('=').replace('+', '-').replace('/', '_'))
            require(raw.size == 32) { "3X-UI returned a ${raw.size}-byte key; WireGuard needs 32 bytes" }
            return java.util.Base64.getEncoder().encodeToString(raw)
        }

        const val DEFAULT_REALITY_SNI = "www.microsoft.com"
        /** Where PanelProvisioner stores the pinned self-signed panel certificate. */
        private const val SELF_SIGNED_CERT_DIR = "/etc/x-ui/maximus-tls/"
        private const val MAX_PORT_ATTEMPTS = 4
        /** Transports Cloudflare's CDN can carry, so they get CDN ports and a path/host in the link. */
        private val CDN_PROTOCOLS = setOf(ThreeXUiProtocol.WEBSOCKET, ThreeXUiProtocol.XHTTP, ThreeXUiProtocol.HTTPUPGRADE)
        /** Default MTU for the WireGuard inbound; 1280 keeps mobile networks with small MTUs working. */
        private const val WG_MTU = 1280

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
