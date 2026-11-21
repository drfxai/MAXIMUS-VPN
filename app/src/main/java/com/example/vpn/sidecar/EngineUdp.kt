package com.example.vpn.sidecar

import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONArray
import org.json.JSONObject

/**
 * What an engine program's local proxy can carry besides TCP, and what Xray in front of it must do about it.
 *
 * Xray owns the Android TUN and forwards every flow to the engine's local SOCKS5 port ([SidecarChain.xrayProfile]).
 * Some engines answer only SOCKS CONNECT:
 * - Psiphon's ConsoleClient: its SOCKS server (Psiphon-Labs/goptlib socks5ReadCommand) accepts CONNECT only and
 *   answers UDP ASSOCIATE with "command not supported"; its udpgw UDP channel exists only in its own TUN mode,
 *   which MAXIMUS does not use.
 * - Tor: SOCKS CONNECT/RESOLVE only; Tor carries no UDP.
 *
 * Through such an engine every QUIC (UDP 443) packet an app sends is lost. Xray cannot answer it with an ICMP
 * unreachable, so the closest it can do is end the flow locally at once ([applyQuicBlock]) instead of opening a
 * SOCKS handshake per packet that can only be refused. YouTube, Chrome and Cronet then use HTTPS over TCP.
 *
 * Psiphon also resolves names on its own server when it gets a host name, so app DNS is answered locally with
 * FakeDNS and the restored name is handed to Psiphon ([remoteDns]): nothing depends on a DNS-over-HTTPS round
 * trip through the tunnel, and a plain-UDP resolver that a TCP-only engine cannot carry is never needed by apps.
 */
object EngineUdp {
    enum class Capability {
        /** The engine's local proxy relays UDP (SOCKS UDP ASSOCIATE or an equivalent). */
        CARRIES_UDP,
        /** CONNECT only: UDP never leaves the phone through it. */
        TCP_ONLY,
        /** Not established for this engine; nothing is changed. */
        UNKNOWN
    }

    /** Engines whose local proxy is known (from their source) to accept SOCKS CONNECT only. */
    private val TCP_ONLY_ENGINES = setOf(PsiphonSidecar.ID, TorSidecar.ID)

    /** Engines that resolve a host name on their own servers when Xray hands it to them. */
    private val REMOTE_DNS_ENGINES = setOf(PsiphonSidecar.ID)

    /** Xray outbound tag of the local QUIC drop. */
    const val QUIC_BLOCK_TAG = "quic-block"

    fun engineId(profile: VlessProfile): String =
        runCatching { ProfileExtras.read(profile).optString("engine") }.getOrDefault("")

    fun of(profile: VlessProfile): Capability = of(engineId(profile))

    fun of(engineId: String): Capability = when (engineId) {
        in TCP_ONLY_ENGINES -> Capability.TCP_ONLY
        else -> Capability.UNKNOWN
    }

    /** QUIC is ended locally for engines that can never carry it. */
    fun blocksQuic(profile: VlessProfile): Boolean = of(profile) == Capability.TCP_ONLY

    /** App DNS answered by FakeDNS and resolved by the engine on its server. */
    fun remoteDns(profile: VlessProfile): Boolean = engineId(profile) in REMOTE_DNS_ENGINES

    /** One line for diagnostics: what UDP does on this engine's path. */
    fun describe(engineId: String): String = when (of(engineId)) {
        Capability.TCP_ONLY -> "none (local proxy is SOCKS CONNECT only); QUIC/UDP 443 ended locally so apps use TCP"
        Capability.CARRIES_UDP -> "carried"
        Capability.UNKNOWN -> "not established"
    }

    /**
     * Ends UDP 443 (QUIC/HTTP3) inside Xray before any routing rule can send it to the engine. Idempotent.
     * DNS (UDP 53) is untouched.
     */
    fun applyQuicBlock(root: JSONObject) {
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        if ((0 until outbounds.length()).none { outbounds.optJSONObject(it)?.optString("tag") == QUIC_BLOCK_TAG }) {
            outbounds.put(JSONObject().put("tag", QUIC_BLOCK_TAG).put("protocol", "blackhole")
                .put("settings", JSONObject().put("response", JSONObject().put("type", "none"))))
        }
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val old = routing.optJSONArray("rules") ?: JSONArray()
        val alreadyFirst = old.optJSONObject(0)?.optString("outboundTag") == QUIC_BLOCK_TAG
        if (alreadyFirst) return
        val rules = JSONArray().put(JSONObject().put("type", "field").put("network", "udp").put("port", "443")
            .put("outboundTag", QUIC_BLOCK_TAG))
        for (i in 0 until old.length()) {
            val rule = old.optJSONObject(i)
            if (rule?.optString("outboundTag") == QUIC_BLOCK_TAG) continue
            rules.put(old.get(i))
        }
        routing.put("rules", rules)
    }

    /**
     * A bare resolver address means DNS over UDP/53, which a TCP-only engine cannot carry: Xray's own lookups
     * would time out. The same resolver is asked over TCP instead. DoH and other URL forms are left alone.
     */
    fun dnsOverTcp(root: JSONObject) {
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return
        for (i in 0 until servers.length()) {
            val value = servers.opt(i)
            if (value is String && com.example.vpn.tunnel.ProxyDnsTransport.isLiteralAddress(value)) {
                val host = if (value.contains(':')) "[${value.removePrefix("[").removeSuffix("]")}]" else value
                servers.put(i, "tcp://$host:53")
            }
        }
    }
}
