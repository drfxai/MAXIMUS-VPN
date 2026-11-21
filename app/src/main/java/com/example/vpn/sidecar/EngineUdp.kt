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
 *
 * Aether (build 167: YouTube did not open over MASQUE HTTP/2 while every other app worked) does answer SOCKS UDP
 * ASSOCIATE, so it is [Capability.CARRIES_UDP], but QUIC through it does not work in practice:
 * - Aether's UDP relay (aether/src/socks.rs `handle_udp_associate`, patterniha/Aether 2.4.0) resolves a datagram's
 *   host name through the tunnel for every single datagram, with no cache, inside the one loop that also moves the
 *   replies. With FakeDNS in front (Xray restores the name before it hands the flow on) every QUIC packet to
 *   googlevideo.com carries a name, so each one waits for a DNS round trip through WARP and the replies wait behind
 *   it: the handshake limps through, the video never flows, and YouTube does not give up on QUIC quickly.
 * - On the HTTP/2 carriers (h2, h2-clean, h2-split, gool/mim over HTTP/2) the datagrams then ride one TCP stream
 *   over a throttled mobile link as well, so QUIC is strictly worse than TCP there.
 * QUIC is therefore ended locally for every Aether carrier ([quicEndedLocally]); YouTube, Chrome and Cronet use TCP
 * at once, which Aether carries well. Other UDP (calls, games, DNS by address) is untouched. When Aether hands the
 * SOCKS port to its Tor or Psiphon hop (chain / only modes), that hop is CONNECT-only: [Capability.TCP_ONLY].
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

    /** Aether carriers that run MASQUE (or WARP-in-WARP) over HTTP/2, i.e. over one TCP connection. */
    val AETHER_TCP_CARRIERS = setOf("h2", "gool", "mim")

    /** Xray outbound tag of the local QUIC drop. */
    const val QUIC_BLOCK_TAG = "quic-block"

    fun engineId(profile: VlessProfile): String =
        runCatching { ProfileExtras.read(profile).optString("engine") }.getOrDefault("")

    fun of(profile: VlessProfile): Capability {
        val id = engineId(profile)
        if (id != AetherSidecar.ID) return of(id)
        // Aether's Tor/Psiphon hop owns the SOCKS port Xray talks to in these modes: CONNECT only.
        val options = runCatching { AetherSettings.read(ProfileExtras.read(profile)) }.getOrNull()
        return if (options != null && (options.tor in AETHER_HOP_OWNS_PORT || options.psiphon in AETHER_HOP_OWNS_PORT))
            Capability.TCP_ONLY else Capability.CARRIES_UDP
    }

    fun of(engineId: String): Capability = when (engineId) {
        in TCP_ONLY_ENGINES -> Capability.TCP_ONLY
        // SOCKS UDP ASSOCIATE into its userspace stack (aether/src/socks.rs), on every carrier.
        AetherSidecar.ID -> Capability.CARRIES_UDP
        else -> Capability.UNKNOWN
    }

    /** Aether modes in which Xray's SOCKS port belongs to the Tor or Psiphon hop, not to Aether. */
    private val AETHER_HOP_OWNS_PORT = setOf("chain", "only")

    /** The Aether carrier in [profile] ("auto" until the session runner picks one). */
    fun aetherCarrier(profile: VlessProfile): String =
        runCatching { ProfileExtras.read(profile).optString("carrier", "auto") }.getOrDefault("auto").ifBlank { "auto" }

    /**
     * QUIC (UDP 443) is ended inside Xray: always for engines that can never carry it, and for Aether on every
     * carrier (its UDP relay looks the name up again for each datagram; see the class comment).
     */
    fun quicEndedLocally(profile: VlessProfile): Boolean =
        of(profile) == Capability.TCP_ONLY || engineId(profile) == AetherSidecar.ID

    /** Kept for callers that ask the old question: is QUIC ended locally on this profile? */
    fun blocksQuic(profile: VlessProfile): Boolean = quicEndedLocally(profile)

    /**
     * Xray's own fallback resolver is asked over TCP: through TCP-only engines (it cannot be carried otherwise) and
     * through Aether's HTTP/2 carriers, where a datagram gains nothing over a TCP query on the same TCP stream.
     */
    fun resolverOverTcp(profile: VlessProfile): Boolean = of(profile) == Capability.TCP_ONLY ||
        engineId(profile) == AetherSidecar.ID && aetherCarrier(profile) in AETHER_TCP_CARRIERS

    /** App DNS answered by FakeDNS and resolved by the engine on its server. */
    fun remoteDns(profile: VlessProfile): Boolean = engineId(profile) in REMOTE_DNS_ENGINES

    /** One line for diagnostics: what UDP does on this engine's path. */
    fun describe(engineId: String): String = when (of(engineId)) {
        Capability.TCP_ONLY -> "none (local proxy is SOCKS CONNECT only); QUIC/UDP 443 ended locally so apps use TCP"
        Capability.CARRIES_UDP -> if (engineId == AetherSidecar.ID)
            "carried (SOCKS UDP ASSOCIATE); QUIC/UDP 443 ended locally (Aether resolves names per datagram) so apps use TCP"
        else "carried"
        Capability.UNKNOWN -> "not established"
    }

    /** [describe] for a running profile (Aether's Tor/Psiphon-hop modes are CONNECT only). */
    fun describe(profile: VlessProfile): String =
        if (engineId(profile) == AetherSidecar.ID && of(profile) == Capability.TCP_ONLY)
            describe(TorSidecar.ID) else describe(engineId(profile))

    /** One line for diagnostics: who resolves app names on this engine's path. */
    fun dnsPath(profile: VlessProfile): String {
        val id = engineId(profile)
        return when {
            remoteDns(profile) -> "FakeDNS in the TUN; names resolved by $id on its server"
            id == AetherSidecar.ID -> "FakeDNS in the TUN; names resolved by aether through WARP" +
                if (resolverOverTcp(profile)) " (fallback resolver over TCP)" else ""
            else -> "Xray resolver through the engine"
        }
    }

    /** Engines whose sessions get the YouTube path probe ([com.example.vpn.verify.VideoPathProbe]). */
    fun videoProbed(engineId: String): Boolean = engineId == PsiphonSidecar.ID || engineId == AetherSidecar.ID

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
