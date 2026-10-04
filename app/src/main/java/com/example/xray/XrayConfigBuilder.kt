package com.example.xray

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.RoutingMode
import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    const val DEFAULT_SOCKS_PORT = 10808
    const val DEFAULT_DOKODEMO_PORT = 10809
    const val SO_MARK_VPN = 255
    const val DNS_TIMEOUT_MS = 10000

    /**
     * Builds a complete, valid Xray-core JSON configuration object from a VlessProfile and AppSettings.
     * If the profile has a raw JSON config, it sanitizes and preserves it directly.
     */
    fun buildJson(profile: VlessProfile, settings: AppSettings): String {
        if (profile.profileType == ProfileType.XRAY_JSON && profile.rawConfig.isNotBlank()) {
            return XrayConfigParser.sanitizeForExecution(profile.rawConfig, settings)
        }

        val root = JSONObject()

        // 1. Logging
        val logObj = JSONObject().apply {
            put("loglevel", settings.logLevel.lowercase())
            put("access", "")
            put("error", "")
        }
        root.put("log", logObj)

        // 3. Inbounds (Local SOCKS & Dokodemo-Door for TUN forwarder)
        val inbounds = JSONArray()

        root.put("inbounds", inbounds)

        // 4. Outbounds (Proxy Outbound based on Protocol, Direct Freedom, Block Blackhole)
        val outbounds = JSONArray()

        val proxyOutbound = buildOutboundForProfile(profile)
        outbounds.put(proxyOutbound)


        // Freedom (Direct) Outbound with socket protection mark
        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", "UseIP")
            })
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("mark", SO_MARK_VPN)
                })
            })
        }
        outbounds.put(directOutbound)

        // Blackhole (Block) Outbound
        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "none") })
            })
        }
        outbounds.put(blockOutbound)

        root.put("outbounds", outbounds)

        // 5. Routing
        val routingObj = JSONObject()
        routingObj.put("domainStrategy", "IPIfNonMatch")
        val rulesArray = JSONArray()

        // Block QUIC (HTTP/3 over UDP 443). Many servers - Cloudflare Worker panels such as BPB in
        // particular - cannot relay it, and YouTube/Chrome then stall instead of using TCP. Dropping
        // it makes them fall back to HTTPS over TCP immediately. DNS (UDP 53) is unaffected.
        rulesArray.put(JSONObject().apply {
            put("type", "field")
            put("network", "udp")
            put("port", "443")
            put("outboundTag", "block")
        })


        when (settings.routingMode) {
            RoutingMode.GLOBAL -> {
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            }
            RoutingMode.RULE_BYPASS_LAN -> {
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("10.0.0.0/8")
                        put("100.64.0.0/10")
                        put("127.0.0.0/8")
                        put("169.254.0.0/16")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                        put("198.18.0.0/15")
                        put("fc00::/7")
                        put("fe80::/10")
                        put("::1/128")
                    })
                })
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply {
                        // Exact suffixes: plain words are keyword matches in Xray ("lan" would
                        // also match "planet.com") and domains are restored by FakeDNS.
                        put("domain:local")
                        put("full:localhost")
                        put("domain:lan")
                    })
                })
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            }
            RoutingMode.BYPASS_SELECTED -> {
                if (settings.customBypassRules.isNotBlank()) {
                    val customDomains = JSONArray()
                    settings.customBypassRules.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { customDomains.put(it) }

                    if (customDomains.length() > 0) {
                        rulesArray.put(JSONObject().apply {
                            put("type", "field")
                            put("outboundTag", "direct")
                            put("domain", customDomains)
                        })
                    }
                }
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("10.0.0.0/8")
                        put("100.64.0.0/10")
                        put("127.0.0.0/8")
                        put("169.254.0.0/16")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                        put("198.18.0.0/15")
                    })
                })
                rulesArray.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            }
        }

        routingObj.put("rules", rulesArray)
        root.put("routing", routingObj)

        // 6. Policy & Stats
        root.put("stats", JSONObject())
        root.put("policy", JSONObject().apply {
            put("system", JSONObject().apply {
                put("statsInboundUplink", true)
                put("statsInboundDownlink", true)
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        applyPrivateDns(root, settings)
        applyFakeDns(root, settings)
        return root.toString(2)
    }

    /**
     * FakeDNS: apps get an instant placeholder address from 198.18.0.0/15 (fc00::/18 for IPv6) and
     * the TUN inbound restores the real domain (see XrayEngine), so the server resolves names
     * itself. Browsing then no longer depends on a DNS-over-HTTPS round trip through the proxy,
     * which often exceeds Xray's lookup timeout on Cloudflare Worker (BPB) paths and left every
     * website unreachable while IP-based apps kept working. Xray's own lookups skip FakeDNS and
     * still use the private resolver.
     */
    internal fun applyFakeDns(root: JSONObject, settings: AppSettings) {
        val pools = JSONArray().put(JSONObject().put("ipPool", "198.18.0.0/15").put("poolSize", 65535))
        if (settings.ipv6Enabled) pools.put(JSONObject().put("ipPool", "fc00::/18").put("poolSize", 65535))
        root.put("fakedns", pools)
        val dns = root.getJSONObject("dns")
        val resolver = dns.getJSONArray("servers").getString(0)
        dns.put("servers", JSONArray().put("fakedns")
            .put(JSONObject().put("address", resolver).put("timeoutMs", DNS_TIMEOUT_MS)))
        if (!settings.ipv6Enabled) dns.put("queryStrategy", "UseIPv4")
        // Routing must see the restored domain, not resolve it again (that would bring back the
        // slow lookup, and a placeholder address would match the 198.18.0.0/15 bypass rule).
        root.getJSONObject("routing").put("domainStrategy", "AsIs")
    }

    internal fun applyPrivateDns(root: JSONObject, settings: AppSettings) {
        val resolver = settings.dnsServer.trim()
        val host = if (resolver.startsWith("https://")) java.net.URI(resolver).host.orEmpty() else resolver
        require(com.example.vpn.tunnel.ProxyDnsTransport.isLiteralAddress(host)) {
            "Resolver bootstrap requires a literal IP; plaintext or system fallback is never substituted"
        }
        val outbounds = root.getJSONArray("outbounds")
        val proxy = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("protocol") in setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "wireguard", "socks", "http") }
            ?: error("A supported proxy outbound is required for private DNS")
        if (proxy.optString("tag").isBlank()) proxy.put("tag", "private-dns-proxy")
        val proxyTag = proxy.getString("tag")
        require(proxyTag != "private-dns-out")
        root.put("dns", JSONObject().put("servers", JSONArray().put(resolver))
            .put("tag", "private-dns-query").put("disableFallback", true))
        require((0 until outbounds.length()).none { outbounds.optJSONObject(it)?.optString("tag") == "private-dns-out" })
        outbounds.put(JSONObject().put("tag", "private-dns-out").put("protocol", "dns")
            .put("settings", JSONObject().put("nonIPQuery", "drop")))
        if (!settings.ipv6Enabled) {
            require((0 until outbounds.length()).none { outbounds.optJSONObject(it)?.optString("tag") == "private-ipv6-block" })
            outbounds.put(JSONObject().put("tag", "private-ipv6-block").put("protocol", "blackhole"))
        }
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val rules = JSONArray()
        rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("private-dns-query"))
            .put("outboundTag", proxyTag))
        rules.put(JSONObject().put("type", "field").put("port", "53").put("outboundTag", "private-dns-out"))
        if (!settings.ipv6Enabled) rules.put(JSONObject().put("type", "field")
            .put("ip", JSONArray().put("::/0")).put("outboundTag", "private-ipv6-block"))
        routing.optJSONArray("rules")?.let { old -> (0 until old.length()).forEach { rules.put(old.get(it)) } }
        routing.put("rules", rules)
    }

    private fun buildOutboundForProfile(profile: VlessProfile): JSONObject {
        val proxyOutbound = JSONObject()
        proxyOutbound.put("tag", "proxy")
        if (profile.targetStrategy.isNotBlank() && !profile.targetStrategy.equals("AsIs", ignoreCase = true)) {
            proxyOutbound.put("targetStrategy", profile.targetStrategy)
        }

        val isTlsOrReality = profile.security.equals("tls", ignoreCase = true) ||
                profile.security.equals("reality", ignoreCase = true)
        val normalizedTransport = when (profile.transport.trim().lowercase()) {
            "h2" -> "http"
            "websocket" -> "ws"
            "splithttp" -> "xhttp"
            else -> profile.transport.trim().lowercase().ifBlank { "tcp" }
        }
        val isTcpTransport = normalizedTransport == "tcp"
        val extras = com.example.vpn.engine.ProfileExtras.read(profile)

        if (profile.protocolType == ProtocolType.WIREGUARD) {
            return buildWireGuardOutbound(proxyOutbound, profile, extras)
        }
        if (profile.protocolType == ProtocolType.HYSTERIA2) {
            return buildHysteria2Outbound(proxyOutbound, profile)
        }

        when (profile.protocolType) {
            ProtocolType.SHADOWSOCKS -> {
                proxyOutbound.put("protocol", "shadowsocks")
                val serversArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put("method", if (profile.encryption.isNotBlank() && profile.encryption != "none") profile.encryption else "aes-128-gcm")
                        put("password", profile.uuid)
                        put("ota", false)
                    })
                }
                proxyOutbound.put("settings", JSONObject().apply { put("servers", serversArr) })
            }
            ProtocolType.TROJAN -> {
                proxyOutbound.put("protocol", "trojan")
                val serversArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put("password", profile.uuid)
                    })
                }
                proxyOutbound.put("settings", JSONObject().apply { put("servers", serversArr) })
            }
            ProtocolType.VMESS -> {
                proxyOutbound.put("protocol", "vmess")
                val userObj = JSONObject().apply {
                    put("id", profile.uuid)
                    put("alterId", 0)
                    put("security", if (profile.encryption.isNotBlank()) profile.encryption else "auto")
                }
                val vnextArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put("users", JSONArray().apply { put(userObj) })
                    })
                }
                proxyOutbound.put("settings", JSONObject().apply { put("vnext", vnextArr) })
            }
            else -> {
                // Default: VLESS
                proxyOutbound.put("protocol", "vless")
                val userObj = JSONObject().apply {
                    put("id", profile.uuid)
                    put("encryption", if (profile.encryption.isNotBlank()) profile.encryption else "none")
                    if (profile.flow.isNotBlank() && isTlsOrReality && isTcpTransport) {
                        put("flow", profile.flow)
                    }
                    put("level", 0)
                }
                val vnextArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put("users", JSONArray().apply { put(userObj) })
                    })
                }
                proxyOutbound.put("settings", JSONObject().apply { put("vnext", vnextArr) })
            }
        }

        // Stream Settings (Transport & Security)
        val streamSettings = JSONObject().apply {
            put("network", normalizedTransport)

            val sec = profile.security.lowercase()
            put("security", if (sec.isNotBlank()) sec else "none")

            // TLS / REALITY Settings
            if (sec == "tls") {
                val tlsObj = JSONObject().apply {
                    if (profile.sni.isNotBlank()) put("serverName", profile.sni)
                    if (profile.fingerprint.isNotBlank()) {
                        // Xray's "unsafe" fingerprint means "use Go's standard TLS instead of a uTLS
                        // browser imitation" (needed for custom cipher suites). Certificates are still
                        // verified: Xray removed allowInsecure, so a self-signed server is trusted only
                        // through its pinned SHA-256 below.
                        put("fingerprint", profile.fingerprint.lowercase())
                    }
                    if (profile.pinnedPeerCertSha256.isNotBlank()) {
                        put("pinnedPeerCertSha256", profile.pinnedPeerCertSha256.trim())
                    }
                    if (profile.verifyPeerCertByName.isNotBlank()) {
                        put("verifyPeerCertByName", profile.verifyPeerCertByName.trim())
                    }
                    if (profile.echConfigList.isNotBlank()) {
                        put("echConfigList", profile.echConfigList.trim())
                    }
                    if (profile.echSockopt.isNotBlank()) {
                        put("echSockopt", runCatching { JSONObject(profile.echSockopt) }.getOrElse {
                            throw IllegalArgumentException("The profile's echSockopt is not valid JSON")
                        })
                    }
                    if (profile.cipherSuites.isNotBlank()) {
                        put("cipherSuites", profile.cipherSuites)
                    }
                    if (profile.alpn.isNotBlank()) {
                        val alpnArray = JSONArray()
                        profile.alpn.split(",").forEach { alpnArray.put(it.trim()) }
                        put("alpn", alpnArray)
                    }
                }
                put("tlsSettings", tlsObj)
            } else if (sec == "reality") {
                val realityObj = JSONObject().apply {
                    put("show", false)
                    if (profile.fingerprint.isNotBlank()) {
                        // REALITY does not accept TLS's allowInsecure field.
                        put("fingerprint", if (profile.fingerprint.equals("unsafe", ignoreCase = true)) "chrome" else profile.fingerprint)
                    }
                    if (profile.cipherSuites.isNotBlank()) {
                        put("cipherSuites", profile.cipherSuites)
                    }
                    if (profile.sni.isNotBlank()) put("serverName", profile.sni)
                    if (profile.publicKey.isNotBlank()) put("publicKey", profile.publicKey)
                    if (profile.shortId.isNotBlank()) put("shortId", profile.shortId)
                    if (profile.spiderX.isNotBlank()) put("spiderX", profile.spiderX)
                    extras.optString(com.example.vpn.engine.ProfileExtras.MLDSA65_VERIFY).takeIf { it.isNotBlank() }?.let {
                        put("mldsa65Verify", it)
                    }
                }
                put("realitySettings", realityObj)
            }

            // Final mask (for example TLS ClientHello fragmentation), stored as raw JSON.
            if (profile.finalMask.isNotBlank()) {
                val mask = runCatching { JSONObject(profile.finalMask) }.getOrElse {
                    throw IllegalArgumentException("The profile's finalMask is not valid JSON")
                }
                put("finalmask", mask)
            }

            // Transport Specific Settings
            when (normalizedTransport) {
                "ws" -> {
                    val wsObj = JSONObject().apply {
                        put("path", if (profile.path.isNotBlank()) profile.path else "/")
                        val headers = JSONObject()
                        if (profile.host.isNotBlank()) headers.put("Host", profile.host)
                        put("headers", headers)
                    }
                    put("wsSettings", wsObj)
                }
                "grpc" -> {
                    val grpcObj = JSONObject().apply {
                        put("serviceName", if (profile.serviceName.isNotBlank()) profile.serviceName else "")
                        put("multiMode", true)
                    }
                    put("grpcSettings", grpcObj)
                }
                "http", "h2" -> {
                    val httpObj = JSONObject().apply {
                        put("path", if (profile.path.isNotBlank()) profile.path else "/")
                        if (profile.host.isNotBlank()) {
                            put("host", JSONArray().apply { put(profile.host) })
                        }
                    }
                    put("httpSettings", httpObj)
                }
                "xhttp", "splithttp" -> {
                    val xhttpObj = JSONObject().apply {
                        put("path", if (profile.path.isNotBlank()) profile.path else "/")
                        if (profile.host.isNotBlank()) put("host", profile.host)
                        put("mode", extras.optString(com.example.vpn.engine.ProfileExtras.XHTTP_MODE).ifBlank { "auto" })
                        extras.optJSONObject(com.example.vpn.engine.ProfileExtras.XHTTP_EXTRA)?.let { put("extra", it) }
                    }
                    put("xhttpSettings", xhttpObj)
                }
                "httpupgrade" -> {
                    put("httpupgradeSettings", JSONObject().apply {
                        put("path", if (profile.path.isNotBlank()) profile.path else "/")
                        if (profile.host.isNotBlank()) put("host", profile.host)
                    })
                }
                "tcp" -> {
                    if (profile.headerType.equals("http", ignoreCase = true)) {
                        val tcpObj = JSONObject().apply {
                            put("header", JSONObject().apply {
                                put("type", "http")
                                put("request", JSONObject().apply {
                                    put("path", JSONArray().apply { put(if (profile.path.isNotBlank()) profile.path else "/") })
                                    if (profile.host.isNotBlank()) {
                                        put("headers", JSONObject().apply {
                                            put("Host", JSONArray().apply { put(profile.host) })
                                        })
                                    }
                                })
                            })
                        }
                        put("tcpSettings", tcpObj)
                    }
                }
            }

            put("sockopt", JSONObject().apply {
                put("mark", SO_MARK_VPN)
            })
        }

        proxyOutbound.put("streamSettings", streamSettings)
        return proxyOutbound
    }

    /** Hysteria2 runs on QUIC; salamander obfs, port hopping and brutal bandwidth live in finalmask. */
    private fun buildHysteria2Outbound(outbound: JSONObject, profile: VlessProfile): JSONObject {
        outbound.put("protocol", "hysteria")
        outbound.put("settings", JSONObject().apply {
            put("version", 2)
            put("address", profile.address)
            put("port", profile.port)
        })
        outbound.put("streamSettings", JSONObject().apply {
            put("network", "hysteria")
            put("hysteriaSettings", JSONObject().put("version", 2).put("auth", profile.uuid))
            put("security", "tls")
            put("tlsSettings", JSONObject().apply {
                put("serverName", profile.sni.ifBlank { profile.address })
                put("alpn", JSONArray().apply {
                    profile.alpn.ifBlank { "h3" }.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
                })
                if (profile.pinnedPeerCertSha256.isNotBlank()) put("pinnedPeerCertSha256", profile.pinnedPeerCertSha256.trim())
                if (profile.verifyPeerCertByName.isNotBlank()) put("verifyPeerCertByName", profile.verifyPeerCertByName.trim())
            })
            putFinalMask(this, profile)
            put("sockopt", JSONObject().put("mark", SO_MARK_VPN))
        })
        return outbound
    }

    private fun buildWireGuardOutbound(outbound: JSONObject, profile: VlessProfile, extras: JSONObject): JSONObject {
        val host = if (profile.address.contains(':') && !profile.address.startsWith("[")) "[${profile.address}]" else profile.address
        outbound.put("protocol", "wireguard")
        outbound.put("settings", JSONObject().apply {
            put("secretKey", profile.uuid)
            put("address", JSONArray().apply {
                extras.optString(com.example.vpn.engine.ProfileExtras.WG_ADDRESS).split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
            })
            put("peers", JSONArray().put(JSONObject().apply {
                put("publicKey", profile.publicKey)
                put("endpoint", "$host:${profile.port}")
                extras.optString(com.example.vpn.engine.ProfileExtras.WG_PRESHARED_KEY).takeIf { it.isNotBlank() }?.let { put("preSharedKey", it) }
                extras.optInt(com.example.vpn.engine.ProfileExtras.WG_KEEPALIVE, 0).takeIf { it > 0 }?.let { put("keepAlive", it) }
            }))
            put("mtu", extras.optInt(com.example.vpn.engine.ProfileExtras.WG_MTU, 1280))
            wireGuardReserved(extras.optString(com.example.vpn.engine.ProfileExtras.WG_RESERVED))?.let { put("reserved", it) }
            // Android apps cannot create a kernel TUN for WireGuard; use the userspace one.
            put("noKernelTun", true)
        })
        outbound.put("streamSettings", JSONObject().apply {
            putFinalMask(this, profile)
            put("sockopt", JSONObject().put("mark", SO_MARK_VPN))
        })
        return outbound
    }

    /** "1,2,3" or base64 of three bytes (both are used in links) to a JSON byte array. */
    internal fun wireGuardReserved(raw: String): JSONArray? {
        if (raw.isBlank()) return null
        val bytes = if (raw.contains(',') || raw.trim().all { it.isDigit() }) {
            raw.split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { b -> b in 0..255 } }
        } else {
            runCatching { java.util.Base64.getDecoder().decode(raw.trim()).map { it.toInt() and 0xff } }.getOrNull()
        }
        return bytes?.takeIf { it.isNotEmpty() }?.let { list -> JSONArray().apply { list.forEach { put(it) } } }
    }

    private fun putFinalMask(stream: JSONObject, profile: VlessProfile) {
        if (profile.finalMask.isBlank()) return
        stream.put("finalmask", runCatching { JSONObject(profile.finalMask) }.getOrElse {
            throw IllegalArgumentException("The profile's finalMask is not valid JSON")
        })
    }
}

