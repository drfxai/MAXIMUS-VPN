package com.example.panels.servers.tunnel

import com.example.core.AppResult
import com.example.data.model.VlessProfile
import com.example.panels.servers.ManagedServer
import com.example.vless.VlessParser
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The Xray and Hysteria2 configuration files of both tunnel servers. Xray reads a directory of
 * JSON files (`run -confdir`) and appends their inbounds and outbounds, so every rotating port is
 * its own small file made from a template with `@PORT@` and `@TAG@`; the rotate script on the
 * server fills them in. Nothing here turns off certificate checks: REALITY authenticates with the
 * server's public key, and Hysteria2 trusts only the exit's own certificate.
 */
object TunnelConfigs {
    const val PORT = "@PORT@"
    const val TAG = "@TAG@"
    const val PROBE_URL = "https://www.gstatic.com/generate_204"

    /** Addresses an exit must never open for a client (the server itself and private networks). */
    val PRIVATE_NETS = listOf(
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
        "0.0.0.0/8", "::1/128", "fc00::/7", "fe80::/10"
    )

    private fun JSONObject.text(): String = toString(2).replace("\"$PORT\"", PORT)

    /** Server-side REALITY settings; the private key is filled in on the server, which created it. */
    private fun reality(target: String, shortId: String) = JSONObject()
        .put("target", "$target:443").put("serverNames", JSONArray().put(target))
        .put("privateKey", TunnelScripts.KEY_PLACEHOLDER).put("shortIds", JSONArray().put(shortId))

    private fun blockPrivate() = JSONObject().put("ip", JSONArray(PRIVATE_NETS)).put("outboundTag", "block")

    // ------------------------------------------------------------------ server abroad (exit)

    /** Log, the outbounds and the routing of the exit; the inbounds come from the templates. */
    fun abroadBase(): String = JSONObject()
        .put("log", JSONObject().put("loglevel", "warning"))
        .put("outbounds", JSONArray()
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
        // IPIfNonMatch resolves names, so a name pointing at 127.0.0.1 or a private network is blocked too.
        // The last rule names "direct" because the reverse link's outbound (a later file) would otherwise become the default.
        .put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", JSONArray().put(blockPrivate())
            .put(JSONObject().put("network", "tcp,udp").put("outboundTag", "direct"))))
        .text()

    /** File name of [abroadReverse] on the server abroad. */
    const val REVERSE_FILE = "40-rv.json"
    /** Tag of the traffic that arrives back over the reverse link on the server abroad. */
    const val REVERSE_IN = "rv-in"

    /**
     * The reverse link: the server abroad keeps connections open to the Iran server's phone port
     * (VLESS + REALITY, checked against the Iran server's public key), and the Iran server sends
     * traffic back through them. What arrives goes through the same routing as every other path,
     * so private networks stay blocked.
     */
    fun abroadReverse(spec: TunnelSpec, iranHost: String): String {
        require(spec.entryPublicKey.isNotEmpty()) { "Set up the Iran server first" }
        val outbound = JSONObject().put("tag", "rv-link").put("protocol", "vless")
            .put("settings", JSONObject().put("address", iranHost).put("port", spec.entryPort)
                .put("id", spec.reverseUuid).put("flow", "xtls-rprx-vision").put("encryption", "none")
                .put("reverse", JSONObject().put("tag", REVERSE_IN)))
            .put("streamSettings", JSONObject().put("network", "raw").put("security", "reality").put("realitySettings", JSONObject()
                .put("serverName", spec.entrySni).put("fingerprint", "chrome")
                .put("publicKey", spec.entryPublicKey).put("shortId", spec.entryShortId)))
        return JSONObject().put("outbounds", JSONArray().put(outbound)).toString(2)
    }

    /** One exit inbound for [t] (REALITY or XHTTP); the port and tag are filled in on the server. */
    fun abroadTemplate(spec: TunnelSpec, t: TunnelTransport): String {
        require(t in TunnelTransport.ROTATING)
        val client = JSONObject().put("id", spec.linkUuid)
        if (t == TunnelTransport.REALITY) client.put("flow", "xtls-rprx-vision")
        val stream = JSONObject().put("security", "reality")
            .put("realitySettings", reality(spec.exitSni, spec.exitShortId))
        if (t == TunnelTransport.REALITY) stream.put("network", "raw")
        else stream.put("network", "xhttp").put("xhttpSettings", JSONObject().put("path", spec.xhttpPath).put("mode", "auto"))
        val inbound = JSONObject().put("tag", TAG).put("port", PORT).put("protocol", "vless")
            .put("settings", JSONObject().put("clients", JSONArray().put(client)).put("decryption", "none"))
            .put("streamSettings", stream)
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic")).put("routeOnly", true))
        return JSONObject().put("inbounds", JSONArray().put(inbound)).text()
    }

    /** The exit's Hysteria2 server (own service and certificate, separate from the Hysteria2 tool). */
    fun abroadHysteria(spec: TunnelSpec, dir: String): String = (listOf(
        "listen: :${spec.hyPort}",
        "tls:", "  cert: $dir/cert.pem", "  key: $dir/key.pem",
        "auth:", "  type: password", "  password: ${spec.hyAuth}",
        "obfs:", "  type: salamander", "  salamander:", "    password: ${spec.hyObfs}",
        "acl:", "  inline:"
    ) + PRIVATE_NETS.map { "    - reject($it)" } + "    - direct(all)").joinToString("\n", postfix = "\n")

    // ------------------------------------------------------------------ Iran server (entry)

    /**
     * The Iran server's own part: the phone's REALITY inbound, the health observatory and the
     * balancer that sends everything to the fastest working path abroad. Nothing ever leaves the
     * Iran server directly; see [iranDefaultBlock].
     */
    fun iranBase(spec: TunnelSpec): String {
        val clients = JSONArray().put(JSONObject().put("id", spec.entryUuid).put("flow", "xtls-rprx-vision"))
        // The server abroad signs in on the same port; Xray turns its connections into the outbound
        // "t-rv" for the balancer and refuses any other use of this id.
        if (TunnelTransport.REVERSE in spec.transports) clients.put(JSONObject().put("id", spec.reverseUuid).put("flow", "xtls-rprx-vision")
            .put("reverse", JSONObject().put("tag", TunnelTransport.REVERSE.tagPrefix)))
        val inbound = JSONObject().put("tag", "entry").put("port", spec.entryPort).put("protocol", "vless")
            .put("settings", JSONObject().put("clients", clients).put("decryption", "none"))
            .put("streamSettings", JSONObject().put("network", "raw").put("security", "reality")
                .put("realitySettings", reality(spec.entrySni, spec.entryShortId)))
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic")).put("routeOnly", true))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("observatory", JSONObject().put("subjectSelector", JSONArray().put("t-"))
                .put("probeUrl", PROBE_URL).put("probeInterval", "30s").put("enableConcurrency", true))
            .put("routing", JSONObject().put("domainStrategy", "AsIs")
                .put("balancers", JSONArray().put(JSONObject().put("tag", "abroad")
                    .put("selector", JSONArray().put("t-"))
                    .put("strategy", JSONObject().put("type", "leastPing"))
                    .put("fallbackTag", fallbackTag(spec))))
                .put("rules", JSONArray()
                    .put(blockPrivate())
                    .put(JSONObject().put("inboundTag", JSONArray().put("entry")).put("balancerTag", "abroad"))))
            .text()
    }

    /** File name of [iranDefaultBlock]. Xray puts the outbounds of later files first, so this one sorts last. */
    const val IRAN_BLOCK_FILE = "90-default-block.json"

    /** A blackhole that becomes the Iran server's default outbound: traffic no rule claims is dropped, never sent from Iran. */
    fun iranDefaultBlock(): String = JSONObject()
        .put("outbounds", JSONArray().put(JSONObject().put("tag", "block").put("protocol", "blackhole"))).toString(2)

    /** The tag the balancer uses before the first health probe finishes. */
    fun fallbackTag(spec: TunnelSpec): String = when (val t = spec.fallbackTransport) {
        TunnelTransport.HYSTERIA2, TunnelTransport.REVERSE -> t.tagPrefix
        else -> "${t.tagPrefix}-now"
    }

    /** One Iran-server outbound to the exit over [t]; port and tag are filled in on the server. */
    fun iranTemplate(spec: TunnelSpec, abroadHost: String, t: TunnelTransport): String {
        require(t in TunnelTransport.ROTATING)
        val user = JSONObject().put("id", spec.linkUuid).put("encryption", "none")
        if (t == TunnelTransport.REALITY) user.put("flow", "xtls-rprx-vision")
        val stream = JSONObject().put("security", "reality").put("realitySettings", JSONObject()
            .put("serverName", spec.exitSni).put("fingerprint", "chrome")
            .put("publicKey", spec.exitPublicKey).put("shortId", spec.exitShortId))
        if (t == TunnelTransport.REALITY) stream.put("network", "raw")
        else stream.put("network", "xhttp").put("xhttpSettings", JSONObject().put("path", spec.xhttpPath).put("mode", "auto"))
        val outbound = JSONObject().put("tag", TAG).put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", abroadHost).put("port", PORT).put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)
        return JSONObject().put("outbounds", JSONArray().put(outbound)).text()
    }

    /** The Iran server's outbound into its local Hysteria2 client. */
    fun iranHysteriaOutbound(spec: TunnelSpec): String = JSONObject().put("outbounds", JSONArray().put(JSONObject()
        .put("tag", TunnelTransport.HYSTERIA2.tagPrefix).put("protocol", "socks")
        .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", spec.hyLocalPort))))))
        .toString(2)

    /** The Iran server's Hysteria2 client; it verifies the exit against that exit's own certificate. */
    fun iranHysteriaClient(spec: TunnelSpec, abroadHost: String, dir: String): String {
        val authority = if (abroadHost.contains(':')) "[$abroadHost]" else abroadHost
        return """
            server: $authority:${spec.hyPort}
            auth: ${spec.hyAuth}
            obfs:
              type: salamander
              salamander:
                password: ${spec.hyObfs}
            tls:
              sni: $abroadHost
              ca: $dir/abroad-cert.pem
            socks5:
              listen: 127.0.0.1:${spec.hyLocalPort}
        """.trimIndent()
    }

    // ------------------------------------------------------------------ phone

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun name(iran: ManagedServer, abroad: ManagedServer) = "Maximus Tunnel · ${iran.displayName} → ${abroad.displayName}"

    /** The phone's link to the Iran server (VLESS + REALITY + Vision). */
    fun entryLink(spec: TunnelSpec, iran: ManagedServer, abroad: ManagedServer): String {
        val host = iran.host.let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
        val q = listOf(
            "type=tcp", "encryption=none", "security=reality", "flow=xtls-rprx-vision",
            "sni=${enc(spec.entrySni)}", "fp=chrome", "pbk=${enc(spec.entryPublicKey)}", "sid=${spec.entryShortId}"
        ).joinToString("&")
        return "vless://${spec.entryUuid}@$host:${spec.entryPort}?$q#${enc(name(iran, abroad))}"
    }

    fun entryProfile(spec: TunnelSpec, iran: ManagedServer, abroad: ManagedServer): VlessProfile =
        when (val r = VlessParser.parse(entryLink(spec, iran, abroad))) {
            is AppResult.Success -> r.data.copy(name = name(iran, abroad))
            is AppResult.Error -> error(r.userFriendlyMessage)
        }
}
