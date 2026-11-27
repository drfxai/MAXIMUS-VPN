package com.example.panels.servers.ech

import com.example.vpn.smartsni.SmartEch
import com.example.vpn.smartsni.SmartSni
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Build 181 (Phase 6): ECH on the user's own server, managed from Panels → My Servers through the server's 3X-UI panel.
 *
 * Why. Cloudflare's ECH outer name is fixed inside its key (`cloudflare-ech.com`) and is blocked on Irancell, where the
 * TLS SNI filter drops listed names while random names pass. With our own key on the user's Xray server the outer
 * (public) name is ours to choose, so Smart SNI can pick one that passes on the current network.
 *
 * Engines (verified against the sources pinned for this build, see work/build181/notes.md "Phase 6"):
 * - Server: 3X-UI v3.8.5 bundles Xray-core v26.9.9. Its `POST /panel/api/server/getNewEchCert` (form `sni`) runs
 *   `xray tls ech --serverName <sni>` on the server and answers `{echServerKeys, echConfigList}`; the inbound stores
 *   them as `streamSettings.tlsSettings.echServerKeys` and `tlsSettings.settings.echConfigList` (the panel's client
 *   block, used for its share links). Xray reads `echServerKeys` as base64 of repeated
 *   `u16 len · private key · u16 len · ECHConfig` sets (transport/internet/tls/ech.go ConvertToGoECHKeys), so several
 *   key sets can be served at once — that is how a rotation keeps the previous key working for a while.
 * - Client: libXray `tlsSettings.echConfigList` (base64 ECHConfigList), with uTLS fingerprints too.
 * - Only TCP-based TLS inbounds (VLESS / VMess / Trojan over RAW, WS, HTTPUpgrade, XHTTP, gRPC). REALITY has no ECH;
 *   Hysteria2 (QUIC) is not covered.
 *
 * Private keys. The key pair is made by the server's own Xray; the private half travels only inside the pinned-HTTPS
 * panel API call that stores it, and is never written to logs. On the phone only the public ECHConfigList is kept
 * (ServerStore, Android Keystore-encrypted); the previous inbound (which holds the previous private keys) is kept only
 * as an encrypted rollback copy ([com.example.panels.servers.ech.ServerEchProvisioner]).
 *
 * Everything here is pure, so the parsing, update and selection rules are unit-tested.
 */
object ServerEch {
    /** Tool settings on the 3X-UI [com.example.panels.servers.InstalledTool] (stored encrypted with the server). */
    const val K_ON = "ech.on"
    const val K_OUTER = "ech.outer"
    const val K_CONFIG = "ech.config"
    const val K_AT = "ech.at"
    const val K_ROTATE_DAYS = "ech.rotateDays"
    const val K_INBOUNDS = "ech.inbounds"

    /** Key sets served at once: the new one and the one before it (clients that have not refreshed yet). */
    const val MAX_KEY_SETS = 2
    const val DEFAULT_ROTATE_DAYS = 30
    val ROTATE_CHOICES = listOf(0, 7, 30)

    val TLS_NETWORKS = setOf("tcp", "raw", "ws", "httpupgrade", "xhttp", "splithttp", "grpc")
    val PROTOCOLS = setOf("vless", "vmess", "trojan")

    // ---- key material ---------------------------------------------------------------------------------------------

    /** One key set of an `echServerKeys` value (the private key is never exposed, only its length). */
    data class KeySet(val privateKeyLength: Int, val config: ByteArray) {
        override fun equals(other: Any?) = other is KeySet && other.privateKeyLength == privateKeyLength && other.config.contentEquals(config)
        override fun hashCode() = config.contentHashCode() * 31 + privateKeyLength
    }

    private fun b64(text: String): ByteArray? = text.trim().takeIf { it.isNotEmpty() && it.length <= 16_384 }
        ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /** The key sets of an `echServerKeys` value, in Xray's format; null when malformed. Pure. */
    fun keySets(serverKeys: String): List<KeySet>? {
        val d = b64(serverKeys) ?: return null
        val out = mutableListOf<KeySet>()
        var i = 0
        fun u16(at: Int) = ((d[at].toInt() and 0xff) shl 8) or (d[at + 1].toInt() and 0xff)
        while (i < d.size) {
            if (i + 2 > d.size) return null
            val keyLen = u16(i)
            if (keyLen !in 16..512 || i + 2 + keyLen + 2 > d.size) return null
            val cfgAt = i + 2 + keyLen
            val cfgLen = u16(cfgAt)
            if (cfgLen < 8 || cfgAt + 2 + cfgLen > d.size) return null
            out += KeySet(keyLen, d.copyOfRange(cfgAt + 2, cfgAt + 2 + cfgLen))
            i = cfgAt + 2 + cfgLen
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The configs of an ECHConfigList (u16 total length, then ECHConfig entries); null when malformed. Pure. */
    fun configs(configList: String): List<ByteArray>? {
        val d = b64(configList) ?: return null
        if (d.size < 4) return null
        fun u16(at: Int) = ((d[at].toInt() and 0xff) shl 8) or (d[at + 1].toInt() and 0xff)
        if (u16(0) != d.size - 2) return null
        val out = mutableListOf<ByteArray>()
        var i = 2
        while (i < d.size) {
            if (i + 4 > d.size) return null
            val len = u16(i + 2)
            if (i + 4 + len > d.size) return null
            out += d.copyOfRange(i, i + 4 + len)
            i += 4 + len
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The outer (public) name of an ECHConfigList's first config; null when unreadable. Pure. */
    fun outerName(configList: String): String? = b64(configList)?.let { SmartEch.publicNames(it).firstOrNull() }

    /**
     * Checks a freshly generated pair before it goes anywhere: both parse, the list's config is the first key set's
     * config, and its outer name is the one asked for. Returns the problem, or null. Never includes key bytes. Pure.
     */
    fun checkGenerated(serverKeys: String, configList: String, outer: String): String? {
        val sets = keySets(serverKeys) ?: return "the server returned unreadable ECH server keys"
        val cfgs = configs(configList) ?: return "the server returned an unreadable ECH config list"
        if (sets.size != 1 || cfgs.size != 1) return "the server returned more than one ECH key"
        if (!sets[0].config.contentEquals(cfgs[0])) return "the ECH config list does not match the server keys"
        val name = outerName(configList) ?: return "the ECH config has no outer name"
        if (!name.equals(outer, ignoreCase = true)) return "the ECH outer name is $name, not $outer"
        return null
    }

    /**
     * The `echServerKeys` value to install: [fresh] first, then at most [MAX_KEY_SETS] - 1 earlier key sets from
     * [previous] (a rotation keeps the last key working for clients that have not refreshed). A malformed [previous]
     * is dropped. Pure.
     */
    fun combine(fresh: String, previous: String?): String {
        val f = b64(fresh) ?: error("fresh ECH keys unreadable")
        requireNotNull(keySets(fresh)) { "fresh ECH keys unreadable" }
        val p = previous?.takeIf { it.isNotBlank() }?.let { prev -> keySets(prev)?.let { b64(prev) } } ?: return fresh
        val old = splitRaw(p).take(MAX_KEY_SETS - 1)
        val out = java.io.ByteArrayOutputStream()
        out.write(f)
        old.forEach { if (!it.contentEquals(f)) out.write(it) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun splitRaw(d: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        fun u16(at: Int) = ((d[at].toInt() and 0xff) shl 8) or (d[at + 1].toInt() and 0xff)
        while (i + 2 <= d.size) {
            val keyLen = u16(i)
            val cfgAt = i + 2 + keyLen
            if (cfgAt + 2 > d.size) break
            val end = cfgAt + 2 + u16(cfgAt)
            if (end > d.size) break
            out += d.copyOfRange(i, end)
            i = end
        }
        return out
    }

    // ---- inbounds -------------------------------------------------------------------------------------------------

    internal fun obj(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    /** Why [inbound] cannot carry ECH, or null when it can (TLS over a TCP transport, VLESS/VMess/Trojan, a certificate). Pure. */
    fun ineligible(inbound: JSONObject): String? {
        if (!inbound.optBoolean("enable", true)) return "disabled"
        val protocol = inbound.optString("protocol")
        if (protocol !in PROTOCOLS) return "protocol $protocol"
        val stream = obj(inbound.opt("streamSettings")) ?: return "no stream settings"
        val security = stream.optString("security", "none")
        if (security != "tls") return "security $security"
        val network = stream.optString("network", "tcp")
        if (network !in TLS_NETWORKS) return "transport $network"
        val tls = stream.optJSONObject("tlsSettings") ?: return "no TLS settings"
        val certs = tls.optJSONArray("certificates")
        if (certs == null || certs.length() == 0) return "no certificate"
        return null
    }

    fun eligible(inbounds: JSONArray): List<JSONObject> =
        (0 until inbounds.length()).mapNotNull { inbounds.optJSONObject(it) }.filter { ineligible(it) == null }

    /** The `echServerKeys` an inbound serves now, or null. Pure. */
    fun currentServerKeys(inbound: JSONObject): String? =
        obj(inbound.opt("streamSettings"))?.optJSONObject("tlsSettings")?.optString("echServerKeys")?.takeIf { it.isNotBlank() }

    /** The ECHConfigList stored in the inbound's client block, or null. Pure. */
    fun storedConfigList(inbound: JSONObject): String? =
        obj(inbound.opt("streamSettings"))?.optJSONObject("tlsSettings")?.optJSONObject("settings")
            ?.optString("echConfigList")?.takeIf { it.isNotBlank() }

    /**
     * [inbound] with ECH set ([serverKeys] and [configList]) or removed (both null), in the same representation the
     * panel gave it (stringified or nested JSON). Every other field is kept. Pure; [inbound] is not changed.
     */
    fun withEch(inbound: JSONObject, serverKeys: String?, configList: String?): JSONObject {
        val copy = JSONObject(inbound.toString())
        val raw = copy.opt("streamSettings")
        val stream = obj(raw) ?: error("stream settings unreadable")
        val tls = stream.optJSONObject("tlsSettings") ?: JSONObject().also { stream.put("tlsSettings", it) }
        val client = tls.optJSONObject("settings") ?: JSONObject().also { tls.put("settings", it) }
        if (serverKeys == null) tls.remove("echServerKeys") else tls.put("echServerKeys", serverKeys)
        if (configList == null) client.remove("echConfigList") else client.put("echConfigList", configList)
        copy.put("streamSettings", if (raw is String) stream.toString() else stream)
        return copy
    }

    /** The fields 3X-UI's update binds; panel-only fields like clientStats are left out. Pure. */
    fun updatePayload(inbound: JSONObject, stringified: Boolean): JSONObject {
        val o = JSONObject()
        for (k in listOf("id", "up", "down", "total", "allTime", "remark", "enable", "expiryTime", "trafficReset",
            "listen", "port", "protocol", "tag", "subSortIndex")) if (inbound.has(k)) o.put(k, inbound.get(k))
        for (k in listOf("settings", "streamSettings", "sniffing")) {
            val v = inbound.opt(k) ?: continue
            val asObj = obj(v)
            o.put(k, if (stringified) (asObj?.toString() ?: v.toString()) else (asObj ?: v))
        }
        return o
    }

    /** The inner (real) TLS name an inbound's clients use: its serverName, else the server's [host]. Pure. */
    fun innerName(inbound: JSONObject, host: String): String =
        obj(inbound.opt("streamSettings"))?.optJSONObject("tlsSettings")?.optString("serverName")?.trim()
            ?.takeIf { it.isNotBlank() } ?: host

    // ---- outer name -----------------------------------------------------------------------------------------------

    /** Names never offered as our outer name: Cloudflare's own (blocklisted on Irancell) and the Smart SNI CDN names. */
    private val NOT_OURS = listOf("cloudflare", "cloudflareclient.com", "cloudflare-ech.com", "workers.dev", "pages.dev")

    private val RANDOM_STYLE = Regex("^[a-z]{8,16}\\.com$")

    /**
     * Outer-name suggestions for the current network, best first: Smart SNI winners here that look random (they passed
     * the SNI filter and point at no real site), then other winners that are not Cloudflare names, then [random]. The
     * first is the default; the user can edit it. Nothing cooling down here is offered. Pure.
     */
    fun suggestions(ranked: List<String>, cooling: Set<String>, random: () -> String, max: Int = 4): List<String> {
        val usable = ranked.map { it.lowercase() }.filter { n ->
            SmartSni.validName(n) && n !in cooling && NOT_OURS.none { n.contains(it) }
        }.distinct()
        val randomStyle = usable.filter { RANDOM_STYLE.matches(it) }
        val others = usable - randomStyle.toSet()
        val fallback = generateSequence { random() }.take(8).firstOrNull { it !in cooling } ?: random()
        val list = (randomStyle.ifEmpty { listOf(fallback) } + others + fallback).distinct()
        return list.take(max)
    }

    /** Why [name] cannot be our outer name, or null. Pure. */
    fun outerProblem(name: String): String? {
        val n = name.trim().lowercase()
        if (!SmartSni.validName(n)) return "not a valid domain name"
        if (NOT_OURS.any { n.contains(it) }) return "Cloudflare names are blocked on some networks; pick another name"
        return null
    }

    // ---- stored record (tool settings) ----------------------------------------------------------------------------

    /** One ECH-enabled inbound as the phone keeps it (no private key). [link] is the panel's own share link for it. */
    data class Inbound(val id: Int, val port: Int, val sni: String, val pin: String = "", val link: String = "")

    data class Record(
        val on: Boolean,
        val outer: String,
        val config: String,
        val at: Long,
        val rotateDays: Int,
        val inbounds: List<Inbound>
    )

    /** The record kept in the 3X-UI tool's [settings]; null when ECH is off or the record is unreadable. Pure. */
    fun record(settings: Map<String, String>): Record? {
        if (settings[K_ON] != "1") return null
        val config = settings[K_CONFIG].orEmpty().takeIf { configs(it) != null } ?: return null
        val list = runCatching { JSONArray(settings[K_INBOUNDS] ?: "[]") }.getOrNull() ?: return null
        val inbounds = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { o ->
            val port = o.optInt("port")
            if (port !in 1..65535) null else Inbound(o.optInt("id"), port, o.optString("sni"), o.optString("pin"), o.optString("link"))
        }
        if (inbounds.isEmpty()) return null
        return Record(true, settings[K_OUTER].orEmpty().ifBlank { outerName(config).orEmpty() }, config,
            settings[K_AT]?.toLongOrNull() ?: 0L, settings[K_ROTATE_DAYS]?.toIntOrNull() ?: 0, inbounds)
    }

    /** [settings] with [r] written in (or ECH cleared when [r] is null). The rotation choice survives turning ECH off. Pure. */
    fun withRecord(settings: Map<String, String>, r: Record?): Map<String, String> {
        val rest = settings.filterKeys { it !in setOf(K_ON, K_OUTER, K_CONFIG, K_AT, K_INBOUNDS) }
        if (r == null) return rest
        val list = JSONArray().also { a ->
            r.inbounds.forEach { a.put(JSONObject().put("id", it.id).put("port", it.port).put("sni", it.sni).put("pin", it.pin).put("link", it.link)) }
        }
        return rest + mapOf(K_ON to "1", K_OUTER to r.outer, K_CONFIG to r.config, K_AT to r.at.toString(),
            K_ROTATE_DAYS to r.rotateDays.toString(), K_INBOUNDS to list.toString())
    }

    /** Whether a periodic rotation is due for [r]. Pure. */
    fun rotationDue(r: Record?, now: Long): Boolean =
        r != null && r.rotateDays > 0 && r.at > 0 && now - r.at >= r.rotateDays * 86_400_000L
}
