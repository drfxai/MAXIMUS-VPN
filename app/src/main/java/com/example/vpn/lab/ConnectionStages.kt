package com.example.vpn.lab

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile

/**
 * How far a connection method got. Only [APPLICATION_REQUEST_PASSED] or later means traffic really went
 * through the intended path; a started process, an open local port or a TLS handshake never does.
 */
enum class ConnectionStage(val title: String) {
    NOT_TESTED("Not tested"),
    ENGINE_READY("Engine ready"),
    LOCAL_PROXY_READY("Local proxy ready"),
    TCP_REACHED("TCP reached"),
    TLS_NEGOTIATED("TLS negotiated"),
    PROTOCOL_AUTHENTICATED("Protocol authenticated"),
    TUNNEL_ESTABLISHED("Tunnel established"),
    REMOTE_EGRESS_CONFIRMED("Remote egress confirmed"),
    APPLICATION_REQUEST_PASSED("Real request passed"),
    DNS_THROUGH_TUNNEL_PASSED("DNS through tunnel passed"),
    STABILITY_VERIFIED("Stable"),
    VERIFIED("Verified");

    /** Real application traffic went through the path. */
    val carriesTraffic: Boolean get() = this >= APPLICATION_REQUEST_PASSED
}

/** Status words the LAB shows; never a vague "works". */
enum class PathStatus(val title: String) {
    NOT_TESTED("Not tested"),
    QUEUED("Queued"),
    TESTING("Testing"),
    SUPPORTED("Supported"),
    EXPERIMENTAL("Experimental"),
    CANDIDATE("Candidate"),
    VERIFIED("Verified"),
    DEGRADED("Degraded"),
    FAILED("Failed"),
    /** Silence or resets that point to blocking, without the repeated evidence that would prove it. */
    BLOCKED_SUSPECTED("Blocked (suspected)"),
    /** No answer, and nothing shows whether that is blocking or an endpoint problem. */
    UNRESPONSIVE("Unresponsive"),
    BLOCKED("Blocked"),
    UNSUPPORTED("Unsupported"),
    SECURITY_REJECTED("Security rejected"),
    EXPIRED("Expired"),
    AVAILABLE("Available"),
    /** Not tested because a verified path already makes it unnecessary (for example DNS tunnels on an open network). */
    NOT_REQUIRED("Not required")
}

/**
 * Connection method families the LAB reasons about. [udp] marks families that need UDP to the server,
 * [engine] the families carried by their own engine program rather than the Xray core.
 */
enum class PathFamily(val title: String, val udp: Boolean = false, val engine: Boolean = false) {
    VLESS_REALITY("VLESS REALITY"),
    VLESS_TLS("VLESS TLS"),
    XHTTP("XHTTP"),
    WEBSOCKET("WebSocket"),
    GRPC("gRPC"),
    HTTP2("HTTP/2"),
    TROJAN("Trojan"),
    VMESS("VMess"),
    SHADOWSOCKS("Shadowsocks"),
    PLAIN("Unencrypted proxy"),
    HYSTERIA2("Hysteria2", udp = true),
    TUIC("TUIC", udp = true),
    WIREGUARD("WireGuard", udp = true),
    /** AmneziaWG through the bundled Mihomo (amnezia-wg-option). */
    AMNEZIAWG("AmneziaWG", udp = true, engine = true),
    PSIPHON("Psiphon", engine = true),
    TOR_WEBTUNNEL("Tor WebTunnel", engine = true),
    TOR_OBFS4("Tor obfs4", engine = true),
    TOR_SNOWFLAKE("Tor Snowflake", engine = true),
    /** Tor with other bridges (meek). */
    TOR("Tor", engine = true),
    DNS_TUNNEL("DNS tunnel", engine = true),
    MIHOMO("Mihomo config", engine = true),
    OTHER("Other");

    companion object {
        /** Tor families, most likely to pass first: these are the order emergency recovery tries them in. */
        val TOR_FAMILIES = listOf(TOR_WEBTUNNEL, TOR_OBFS4, TOR_SNOWFLAKE, TOR)

        /**
         * The family of a saved config, from its fields only (never its name). [detail] refines an engine
         * profile: the first Tor bridge transport ("webtunnel"), or the Mihomo proxy type ("wireguard",
         * "wireguard+awg", "hysteria2"...).
         */
        fun of(p: VlessProfile, engineId: String? = null, detail: String? = null): PathFamily = when {
            engineId == "psiphon" -> PSIPHON
            engineId == "tor" -> when (detail) {
                "webtunnel" -> TOR_WEBTUNNEL
                "obfs4" -> TOR_OBFS4
                "snowflake", null -> TOR_SNOWFLAKE
                else -> TOR
            }
            engineId == "dns-tunnel" -> DNS_TUNNEL
            engineId != null -> when (detail) {
                "wireguard+awg" -> AMNEZIAWG
                "wireguard" -> WIREGUARD
                "hysteria2" -> HYSTERIA2
                "tuic" -> TUIC
                else -> MIHOMO
            }
            p.protocolType == ProtocolType.HYSTERIA2 -> HYSTERIA2
            p.protocolType == ProtocolType.TUIC -> TUIC
            p.protocolType == ProtocolType.WIREGUARD -> WIREGUARD
            p.protocolType == ProtocolType.TROJAN -> TROJAN
            p.protocolType == ProtocolType.VMESS -> VMESS
            p.protocolType == ProtocolType.SHADOWSOCKS -> SHADOWSOCKS
            p.protocolType in setOf(ProtocolType.HTTP, ProtocolType.SOCKS5, ProtocolType.MIXED) -> PLAIN
            p.security.equals("reality", true) -> VLESS_REALITY
            p.transport.equals("xhttp", true) || p.transport.equals("splithttp", true) -> XHTTP
            p.transport.equals("ws", true) -> WEBSOCKET
            p.transport.equals("grpc", true) -> GRPC
            p.transport.equals("h2", true) || p.transport.equals("http", true) -> HTTP2
            p.security.equals("tls", true) -> VLESS_TLS
            else -> OTHER
        }
    }
}

/** One row of LIVE CONNECTIVITY PATHS. Unknown values stay null; nothing is filled in by guess. */
data class LivePath(
    val key: String,
    val title: String,
    val status: PathStatus,
    val stage: ConnectionStage = ConnectionStage.NOT_TESTED,
    val confidence: Double? = null,
    val latencyMs: Long? = null,
    val checkedAt: Long? = null,
    /** Why it has this status: what was measured, or why it was not tested. */
    val reason: String = "",
    /** How many saved configs of this family were tried, and how many passed a real request. */
    val tried: Int = 0,
    val passed: Int = 0,
    /** Real requests sent for this row in this run. */
    val attempts: Int = 0,
    /** The network session the row belongs to; rows of another session are never shown as current. */
    val sessionId: String? = null
)
