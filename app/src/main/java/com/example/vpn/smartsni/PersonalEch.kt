package com.example.vpn.smartsni

import com.example.data.model.VlessProfile
import com.example.vpn.connections.BuiltinMethodMemory
import org.json.JSONObject

/**
 * Build 181 (Phase 6): the user's own server with ECH (turned on in Panels → My Servers, see
 * [com.example.panels.servers.ech.ServerEch]), used wherever that server is used:
 *
 * - every config whose address and port are an ECH-enabled TLS inbound of the server gets the server's current
 *   ECHConfigList when Xray carries it (manual pick of that config, Smart Connect, failover) — a stale list from an
 *   older share link is replaced by the current one;
 * - the separately labelled manual pick "Personal server (ECH)" ([com.example.vpn.connections.BuiltinConnections]);
 * - a Smart Connect tier entry (heavy / whitelist, [com.example.vpn.smart.SmartTiers]). Like every built-in, it is
 *   never inserted inside another manual pick; cross-engine fallback happens only under Smart Connect.
 *
 * Fallback. When a connect with the server's ECH carries no verified traffic on a network, ECH is off for that server
 * on that network for [FAIL_COOLDOWN_MS] (the same 6 h as Smart ECH) and the server is used with plain TLS (logged);
 * the outer name goes on Smart SNI's cooldown so the next key suggestion avoids it. Our own keys change only when the
 * user rotates them, so there is no refetch.
 *
 * The registry ([entries]) is loaded from the encrypted server store by RayApplication ([load]) and refreshed after
 * every server change. It holds only public data plus the panel's share link (which the profile list has anyway).
 */
object PersonalEch {
    const val FAIL_COOLDOWN_MS = 6 * 60 * 60_000L

    data class Entry(
        val serverId: String,
        val serverName: String,
        val host: String,
        val port: Int,
        /** Inner (real) TLS name. */
        val sni: String,
        /** Hex SHA-256 of the server's self-signed certificate, when clients pin it; else "". */
        val pin: String,
        val config: String,
        val outer: String,
        /** The panel's share link for this inbound (may be ""). */
        val link: String = ""
    )

    /** Installed by RayApplication: reads every server's ECH record from ServerStore. */
    @Volatile var load: () -> List<Entry> = { emptyList() }
    /** Installed by RayApplication: a share link to a profile (UniversalImportEngine). */
    @Volatile var parseLink: (String) -> VlessProfile? = { null }
    @Volatile var entries: List<Entry> = emptyList()
        private set

    fun reload() { entries = runCatching { load() }.getOrDefault(entries) }

    /** For tests. */
    fun set(list: List<Entry>) { entries = list }

    /** The entries of one server's ECH record (tool settings), pure. */
    fun fromRecord(serverId: String, serverName: String, host: String, r: com.example.panels.servers.ech.ServerEch.Record?): List<Entry> =
        r?.inbounds?.map { Entry(serverId, serverName, host, it.port, it.sni.ifBlank { host }, it.pin, r.config, r.outer, it.link) }.orEmpty()

    private fun norm(h: String) = h.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')

    /** The ECH entry [p] connects to (TLS, same host and port), or null. Pure apart from [entries]. */
    fun match(p: VlessProfile, list: List<Entry> = entries): Entry? {
        if (!p.security.equals("tls", ignoreCase = true)) return null
        val h = norm(p.address)
        return list.firstOrNull { norm(it.host) == h && it.port == p.port }
    }

    data class Decision(val entry: Entry?, val applies: Boolean, val reason: String)

    private fun key(serverId: String, network: String) = "personalEch#$serverId#$network"

    fun failedUntil(serverId: String, network: String?): Long =
        if (network.isNullOrBlank()) 0L else runCatching {
            JSONObject(BuiltinMethodMemory.read(key(serverId, network)) ?: "{}").optLong("fail")
        }.getOrDefault(0L)

    fun okAt(serverId: String, network: String?): Long =
        if (network.isNullOrBlank()) 0L else runCatching {
            JSONObject(BuiltinMethodMemory.read(key(serverId, network)) ?: "{}").optLong("ok")
        }.getOrDefault(0L)

    private fun write(serverId: String, network: String?, ok: Long, fail: Long) {
        if (network.isNullOrBlank()) return
        runCatching { BuiltinMethodMemory.write(key(serverId, network), JSONObject().put("ok", ok).put("fail", fail).toString()) }
    }

    /** Whether [p] connects with its server's ECH on [network]. Pure apart from memory reads. */
    fun decide(p: VlessProfile, network: String?, now: Long = System.currentTimeMillis(), list: List<Entry> = entries): Decision {
        val e = match(p, list) ?: return Decision(null, false, "not an ECH-enabled personal server")
        if (failedUntil(e.serverId, network) > now) return Decision(e, false, "ECH failed on this network recently; plain TLS")
        return Decision(e, true, "personal server ECH, outer name ${e.outer}")
    }

    /**
     * [p] as it connects: with the server's ECH ([d] applies), or as plain TLS (the panel's ECH list from its share link removed). The
     * certificate check is never relaxed: the pin is added only for the server's own self-signed certificate. Pure.
     */
    fun applyTo(p: VlessProfile, d: Decision): VlessProfile {
        val e = d.entry ?: return p
        val pinned = p.copy(pinnedPeerCertSha256 = p.pinnedPeerCertSha256.ifBlank { e.pin }, sni = p.sni.ifBlank { e.sni })
        return if (d.applies) pinned.copy(echConfigList = e.config, fingerprint = p.fingerprint.ifBlank { "chrome" })
            // An ECH list on a config of this inbound came from the panel's share link (ours, maybe an older key).
            else pinned.copy(echConfigList = "")
    }

    /**
     * After a connect with [d] on [network]: success is remembered; a failure turns ECH off for this server here for
     * [FAIL_COOLDOWN_MS] (plain TLS next time) and puts the outer name on Smart SNI's cooldown.
     */
    fun onResult(network: String?, d: Decision, verified: Boolean, now: Long = System.currentTimeMillis(), log: (String) -> Unit = {}) {
        val e = d.entry ?: return
        if (!d.applies) return
        if (verified) { write(e.serverId, network, now, 0); runCatching { SmartSni.recordSuccess(network, e.outer, now) }; return }
        write(e.serverId, network, okAt(e.serverId, network), now + FAIL_COOLDOWN_MS)
        runCatching { SmartSni.rotate(network, e.outer, now) }
        log("Personal server ECH: no verified traffic with outer name ${e.outer} on this network; plain TLS to ${e.serverName} for ${FAIL_COOLDOWN_MS / 3_600_000} h")
    }

    /**
     * The config for the "Personal server (ECH)" pick: the server not failing here first, then the one that worked
     * here most recently; parsed from the panel's share link. ECH itself is added at connect ([decide]/[applyTo]).
     */
    fun profile(network: String?, now: Long = System.currentTimeMillis(), list: List<Entry> = entries): VlessProfile? =
        ranked(network, now, list).firstNotNullOfOrNull { e ->
            e.link.takeIf { it.isNotBlank() }?.let { runCatching { parseLink(it) }.getOrNull() }
                ?.takeIf { match(it, listOf(e)) != null }
        }

    fun ranked(network: String?, now: Long = System.currentTimeMillis(), list: List<Entry> = entries): List<Entry> =
        list.sortedWith(compareBy<Entry>({ failedUntil(it.serverId, network) > now }, { -okAt(it.serverId, network) }))

    /** Whether the pick has something to connect to. */
    fun available(list: List<Entry> = entries): Boolean = list.any { it.link.isNotBlank() }

    /** What LAB shows: each server's ECH state on this network. */
    fun status(network: String?, now: Long = System.currentTimeMillis(), list: List<Entry> = entries): String {
        if (list.isEmpty()) return "no personal server with ECH"
        return list.distinctBy { it.serverId }.joinToString("; ") { e ->
            val until = failedUntil(e.serverId, network)
            "${e.serverName}: " + if (until > now) "plain TLS on this network (ECH failed, retry in ${((until - now) / 60_000).coerceAtLeast(1)} min)"
                else "on, outer ${e.outer}" + if (okAt(e.serverId, network) > 0) " (worked here)" else ""
        }
    }
}
