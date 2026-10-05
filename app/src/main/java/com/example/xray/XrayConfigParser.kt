package com.example.xray

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.xray.XrayLogManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object XrayConfigParser {

    /**
     * Parses an arbitrary Xray JSON configuration into a list of VlessProfile / Proxy configurations.
     */
    fun parseJson(jsonString: String): List<VlessProfile> {
        val profiles = mutableListOf<VlessProfile>()
        try {
            val root = JSONObject(jsonString)
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray()

            var primaryProfile: VlessProfile? = null

            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                val protocol = outbound.optString("protocol", "").lowercase()
                val tag = outbound.optString("tag", "outbound-$i")

                if (protocol == "freedom" || protocol == "blackhole" || protocol == "loopback") {
                    continue
                }
                // Do not silently reinterpret unrelated Xray outbounds (dns, wireguard,
                // socks, etc.) as VLESS profiles.
                if (protocol !in setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "hysteria2", "socks", "http")) {
                    continue
                }

                val settings = outbound.optJSONObject("settings")
                val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()

                val transport = streamSettings.optString("network", "tcp")
                val security = streamSettings.optString("security", "none")

                // Extract address and port based on protocol
                var address = "127.0.0.1"
                var port = 443
                var uuid = ""
                var encryption = "none"
                var flow = ""

                val protocolType = when (protocol) {
                    "vless" -> ProtocolType.VLESS
                    "vmess" -> ProtocolType.VMESS
                    "trojan" -> ProtocolType.TROJAN
                    "shadowsocks" -> ProtocolType.SHADOWSOCKS
                    "hysteria", "hysteria2" -> ProtocolType.HYSTERIA2
                    "socks" -> ProtocolType.SOCKS5
                    "http" -> ProtocolType.HTTP
                    else -> ProtocolType.VLESS
                }

                if (settings != null) {
                    val vnext = settings.optJSONArray("vnext")
                    if (vnext != null && vnext.length() > 0) {
                        val serverObj = vnext.optJSONObject(0) ?: continue
                        address = serverObj.optString("address", address)
                        port = serverObj.optInt("port", port)
                        val users = serverObj.optJSONArray("users")
                        if (users != null && users.length() > 0) {
                            val userObj = users.optJSONObject(0) ?: continue
                            uuid = userObj.optString("id", "")
                            // VMess calls this field `security`; VLESS calls it
                            // `encryption`. Preserve either spelling when importing.
                            encryption = userObj.optString(
                                "encryption",
                                userObj.optString("security", "none")
                            )
                            flow = userObj.optString("flow", "")
                        }
                    }

                    if (protocolType == ProtocolType.HYSTERIA2) {
                        address = settings.optString("address", address)
                        port = settings.optInt("port", port)
                        uuid = streamSettings.optJSONObject("hysteriaSettings")?.optString("auth", "").orEmpty()
                    }

                    val servers = settings.optJSONArray("servers")
                    if (servers != null && servers.length() > 0) {
                        val serverObj = servers.optJSONObject(0) ?: continue
                        address = serverObj.optString("address", address)
                        port = serverObj.optInt("port", port)
                        uuid = serverObj.optString("password", "")
                        encryption = serverObj.optString("method", "none")
                    }
                }

                // Stream settings TLS / REALITY
                var sni = ""
                var fingerprint = ""
                var alpn = ""
                var publicKey = ""
                var shortId = ""
                var spiderX = ""
                var pinnedCert = ""

                if (security == "tls") {
                    val tls = streamSettings.optJSONObject("tlsSettings")
                    if (tls != null) {
                        sni = tls.optString("serverName", "")
                        fingerprint = tls.optString("fingerprint", "")
                        pinnedCert = tls.optString("pinnedPeerCertSha256", "")
                        val alpnArr = tls.optJSONArray("alpn")
                        if (alpnArr != null) {
                            alpn = (0 until alpnArr.length()).mapNotNull { index ->
                                alpnArr.optString(index).takeIf { it.isNotBlank() }
                            }.joinToString(",")
                        }
                    }
                } else if (security == "reality") {
                    val reality = streamSettings.optJSONObject("realitySettings")
                    if (reality != null) {
                        sni = reality.optString("serverName", "")
                        fingerprint = reality.optString("fingerprint", "")
                        publicKey = reality.optString("publicKey", "")
                        shortId = reality.optString("shortId", "")
                        spiderX = reality.optString("spiderX", "")
                    }
                }

                // Transport settings
                var path = ""
                var host = ""
                var serviceName = ""
                var headerType = ""

                when (transport.lowercase()) {
                    "ws" -> {
                        val ws = streamSettings.optJSONObject("wsSettings")
                        if (ws != null) {
                            path = ws.optString("path", "")
                            val headers = ws.optJSONObject("headers")
                            host = headers?.optString("Host", "") ?: headers?.optString("host", "") ?: ""
                        }
                    }
                    "grpc" -> {
                        val grpc = streamSettings.optJSONObject("grpcSettings")
                        if (grpc != null) {
                            serviceName = grpc.optString("serviceName", "")
                        }
                    }
                    "http", "h2" -> {
                        val http = streamSettings.optJSONObject("httpSettings")
                        if (http != null) {
                            path = http.optString("path", "")
                            val hostArr = http.optJSONArray("host")
                            if (hostArr != null && hostArr.length() > 0) {
                                host = hostArr.optString(0)
                            }
                        }
                    }
                    "tcp" -> {
                        val tcp = streamSettings.optJSONObject("tcpSettings")
                        val header = tcp?.optJSONObject("header")
                        if (header != null) {
                            headerType = header.optString("type", "")
                        }
                    }
                }

                val effectiveSni = if (sni.isNotBlank()) sni else host
                val effectiveHost = if (host.isNotBlank()) host else effectiveSni

                val profile = VlessProfile(
                    id = UUID.randomUUID().toString(),
                    name = if (tag.isNotBlank() && tag != "proxy") tag else "$protocol-$address",
                    address = address,
                    port = port,
                    uuid = uuid,
                    encryption = encryption,
                    transport = transport,
                    security = security,
                    sni = effectiveSni,
                    host = effectiveHost,
                    path = path,
                    serviceName = serviceName,
                    flow = flow,
                    fingerprint = fingerprint,
                    publicKey = publicKey,
                    shortId = shortId,
                    spiderX = spiderX,
                    alpn = alpn,
                    headerType = headerType,
                    pinnedPeerCertSha256 = pinnedCert,
                    finalMask = streamSettings.optJSONObject("finalmask")?.toString().orEmpty(),
                    profileType = ProfileType.XRAY_JSON,
                    protocolType = protocolType,
                    engineType = EngineType.XRAY,
                    rawConfig = "",
                    nodeCount = 1
                )

                if (primaryProfile == null) primaryProfile = profile
                profiles.add(profile)
            }

            // Also create a master Xray JSON Profile representing the whole config
            if (profiles.isNotEmpty()) {
                val masterProfile = VlessProfile(
                    id = UUID.randomUUID().toString(),
                    name = "Xray Full Config (${profiles.size} Outbounds)",
                    address = primaryProfile?.address ?: "127.0.0.1",
                    port = primaryProfile?.port ?: 443,
                    uuid = primaryProfile?.uuid ?: "",
                    encryption = primaryProfile?.encryption ?: "none",
                    transport = primaryProfile?.transport ?: "tcp",
                    security = primaryProfile?.security ?: "none",
                    sni = primaryProfile?.sni ?: "",
                    host = primaryProfile?.host ?: "",
                    path = primaryProfile?.path ?: "",
                    serviceName = primaryProfile?.serviceName ?: "",
                    flow = primaryProfile?.flow ?: "",
                    fingerprint = primaryProfile?.fingerprint ?: "",
                    publicKey = primaryProfile?.publicKey ?: "",
                    shortId = primaryProfile?.shortId ?: "",
                    spiderX = primaryProfile?.spiderX ?: "",
                    alpn = primaryProfile?.alpn ?: "",
                    profileType = ProfileType.XRAY_JSON,
                    protocolType = primaryProfile?.protocolType ?: ProtocolType.VLESS,
                    engineType = EngineType.XRAY,
                    rawConfig = jsonString,
                    nodeCount = profiles.size
                )
                profiles.add(0, masterProfile)
            }
        } catch (e: Exception) {
            XrayLogManager.appendLog("Failed to parse Xray JSON: ${e.message}", "ERROR")
        }
        return profiles
    }

    /**
     * Sanitizes and prepares a user-provided raw Xray JSON config for execution by:
     * - Removing network listeners; the native engine attaches its TUN inbound.
     * - Preserving all user outbounds, routing rules, DNS, policy, stats.
     */
    fun sanitizeForExecution(rawJson: String, settings: com.example.data.model.AppSettings): String {
        return try {
            val root = JSONObject(rawJson)

            // Native TUN owns forwarding. No unauthenticated local network listeners are needed.
            root.put("inbounds", JSONArray())
            root.remove("api")
            root.remove("metrics")
            // Reject imported certificate bypasses instead of silently weakening them.
            fun rejectInsecure(value: Any?) {
                when (value) {
                    is JSONObject -> value.keys().forEach { key ->
                        require(!(key.equals("allowInsecure", true) && value.optBoolean(key))) {
                            "Imported config disables TLS certificate verification"
                        }
                        rejectInsecure(value.opt(key))
                    }
                    is JSONArray -> (0 until value.length()).forEach { rejectInsecure(value.opt(it)) }
                }
            }
            rejectInsecure(root)
            fun rejectHostnameEndpoints(value: Any?) {
                when (value) {
                    is JSONObject -> value.keys().forEach { key ->
                        if (key == "address") require(com.example.vpn.tunnel.ProxyDnsTransport.isLiteralAddress(value.optString(key))) {
                            "Imported proxy endpoints require literal IPs for private bootstrap"
                        }
                        // WireGuard peers name their server as "host:port".
                        if (key == "endpoint" && value.opt(key) is String) {
                            val host = value.optString(key).substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                            require(com.example.vpn.tunnel.ProxyDnsTransport.isLiteralAddress(host)) {
                                "Imported proxy endpoints require literal IPs for private bootstrap"
                            }
                        }
                        rejectHostnameEndpoints(value.opt(key))
                    }
                    is JSONArray -> (0 until value.length()).forEach { rejectHostnameEndpoints(value.opt(it)) }
                }
            }
            rejectHostnameEndpoints(root.optJSONArray("outbounds"))
            // The first outbound carries everything no rule matches: a direct or blocking one there
            // would send all traffic around the tunnel (or nowhere) while the app shows "connected".
            val firstProtocol = root.optJSONArray("outbounds")?.optJSONObject(0)?.optString("protocol").orEmpty()
            require(firstProtocol in PROXY_PROTOCOLS) {
                "The config's first outbound must be a proxy, not '$firstProtocol'"
            }
            XrayConfigBuilder.applyPrivateDns(root, settings)

            // Ensure log exists
            if (!root.has("log")) {
                root.put("log", JSONObject().apply {
                    put("loglevel", settings.logLevel.lowercase())
                })
            }

            // Ensure stats and policy exist
            if (!root.has("stats")) {
                root.put("stats", JSONObject())
            }
            if (!root.has("policy")) {
                root.put("policy", JSONObject().apply {
                    put("system", JSONObject().apply {
                        put("statsInboundUplink", true)
                        put("statsInboundDownlink", true)
                        put("statsOutboundUplink", true)
                        put("statsOutboundDownlink", true)
                    })
                })
            }

            root.toString(2)
        } catch (e: Exception) {
            throw IllegalArgumentException("Xray configuration rejected by security validation", e)
        }
    }

    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "wireguard", "socks", "http")
}
