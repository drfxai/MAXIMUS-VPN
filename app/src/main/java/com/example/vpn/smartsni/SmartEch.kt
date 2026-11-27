package com.example.vpn.smartsni

import com.example.data.model.VlessProfile
import com.example.vpn.connections.BuiltinMethodMemory
import org.json.JSONObject
import java.util.Base64

/**
 * Build 181 (Phase 5, B): Smart ECH. Encrypted Client Hello for configs fronted by Cloudflare, so the filter sees
 * only the ECH outer name instead of the config's own server name.
 *
 * What is covered (engines pinned in this branch):
 * - Xray (libXray v26.11.9): `tlsSettings.echConfigList` — base64 ECHConfigList. This is where Smart ECH is applied
 *   automatically (Cloudflare-fronted VLESS/VMess/Trojan over TLS with WS/HTTPUpgrade/XHTTP/gRPC/H2).
 * - sing-box 1.14.5 `tls.ech` and Mihomo v1.19.34 `ech-opts`: a config's own base64 ECHConfigList is passed through
 *   (SingBoxSidecar, MihomoParser); Smart ECH does not add ECH to them on its own.
 * - WARP MASQUE (Aether/usque): NOT covered. Neither engine sends ECH on its MASQUE handshake, so Aether gets Smart
 *   SNI only.
 *
 * Outer name. The ECH outer SNI is the `public_name` inside the ECHConfig. RFC 9849 binds the whole ECHConfig into
 * the HPKE context, so the name cannot be rewritten by the client, and no pinned engine has a separate outer-SNI
 * field. Smart ECH therefore picks the outer name by picking the config: it looks up the HTTPS record of Smart SNI's
 * winners (and `cloudflare-ech.com`) through pinned DoH, takes a config whose `public_name` is not on cooldown on
 * this network, and rotates (6 h cooldown, the same as Smart SNI) when ECH fails there. On Cloudflare today every
 * zone publishes the same shared config (`public_name` cloudflare-ech.com), so in practice ECH is used where that
 * name passes and skipped (cleanly, logged) where it is blocked (Irancell since ~2026-10-21, reportedly).
 *
 * Keys. Fetched by DoH ([doh], RFC 8484 GET to IP-literal resolvers over verified TLS; nothing about the user is
 * sent), cached per network up to the record's TTL ([MIN_TTL_MS]..[MAX_TTL_MS]). A connect that fails with ECH is
 * treated as a possible rejection (Cloudflare rotates keys often and Xray does not hand us retry_configs): the key
 * is fetched again at once, and if it changed the next attempt retries once with the new key; otherwise ECH is
 * turned off on that network for [FAIL_COOLDOWN_MS] and the connection falls back to plain TLS (logged).
 *
 * Manual. A manual ECHConfigList ([SmartSniSettings.manualEchConfig]) wins over everything until cleared; a manual
 * outer name ([SmartSniSettings.manualEchOuter]) is the name whose HTTPS record is looked up.
 */
object SmartEch {
    const val MIN_TTL_MS = 5 * 60_000L
    const val MAX_TTL_MS = 60 * 60_000L
    const val FAIL_COOLDOWN_MS = 6 * 60 * 60_000L
    const val ECH_VERSION = 0xfe0d
    const val CLOUDFLARE_OUTER = "cloudflare-ech.com"
    private const val TYPE_HTTPS = 65
    private const val ECH_PARAM = 5

    /** Pinned DoH resolvers (IP literals, so no poisoned lookup is needed to reach them). */
    val DOH = com.example.vpn.connectivity.RecoveryProfiles.CLOUDFLARE_ECH_DOH

    /** Installed by RayApplication: GET [url] on the phone's own network with verified TLS; the body, or null. */
    @Volatile var doh: (String) -> ByteArray? = { null }

    // ---- ECHConfigList ------------------------------------------------------------------------------------------

    /** Base64 (standard or URL alphabet, padding optional) to bytes; null when not base64. Pure. */
    fun decode(text: String): ByteArray? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 8192) return null
        return runCatching { Base64.getDecoder().decode(t) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(t) }.getOrNull()
    }

    /**
     * The `public_name` of each ECHConfig (version 0xfe0d) in an ECHConfigList; empty when the list is malformed.
     * Pure.
     */
    fun publicNames(list: ByteArray): List<String> = runCatching {
        fun u8(i: Int) = list[i].toInt() and 0xff
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        val total = u16(0)
        if (total + 2 != list.size || total == 0) return emptyList()
        val names = mutableListOf<String>()
        var i = 2
        while (i < list.size) {
            val version = u16(i)
            val length = u16(i + 2)
            val start = i + 4
            val end = start + length
            if (end > list.size) return emptyList()
            if (version == ECH_VERSION) {
                var j = start + 1 + 2                    // config_id, kem_id
                j += 2 + u16(j)                          // public_key
                j += 2 + u16(j)                          // cipher_suites
                j += 1                                   // maximum_name_length
                val nameLen = u8(j)
                if (nameLen == 0 || j + 1 + nameLen > end) return emptyList()
                names += String(list, j + 1, nameLen, Charsets.US_ASCII).lowercase()
            }
            i = end
        }
        names
    }.getOrDefault(emptyList())

    /** True for a base64 ECHConfigList holding at least one usable (0xfe0d) config with a valid outer name. Pure. */
    fun validConfigList(text: String): Boolean =
        decode(text)?.let { b -> publicNames(b).let { n -> n.isNotEmpty() && n.all(SmartSni::validName) } } ?: false

    // ---- DoH -----------------------------------------------------------------------------------------------------

    /** RFC 8484 GET url for the HTTPS record of [name] at [resolver]. Pure. */
    fun url(resolver: String, name: String): String =
        resolver + (if ('?' in resolver) "&" else "?") + "dns=" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(com.example.vpn.connectivity.EchKeyCheck.query(name))

    /** The ECH SvcParam (an ECHConfigList) of the first HTTPS answer in [response], with the answer's TTL (s). Pure. */
    fun echParam(response: ByteArray?): Pair<ByteArray, Long>? = runCatching {
        val r = response ?: return null
        fun u16(i: Int) = ((r[i].toInt() and 0xff) shl 8) or (r[i + 1].toInt() and 0xff)
        if (r.size < 12 || (r[2].toInt() and 0x80) == 0 || (r[3].toInt() and 0x0f) != 0) return null
        val questions = u16(4)
        val answers = u16(6)
        var i = 12
        fun skipName() {
            while (true) {
                val len = r[i].toInt() and 0xff
                if (len == 0) { i += 1; return }
                if (len and 0xc0 == 0xc0) { i += 2; return }
                i += 1 + len
            }
        }
        repeat(questions) { skipName(); i += 4 }
        repeat(answers) {
            skipName()
            val type = u16(i)
            val ttl = (u16(i + 4).toLong() shl 16) or u16(i + 6).toLong()
            val rdLength = u16(i + 8)
            val start = i + 10
            if (type == TYPE_HTTPS) {
                var j = start + 2
                while (r[j].toInt() != 0) j += 1 + (r[j].toInt() and 0xff)
                j += 1
                while (j + 4 <= start + rdLength) {
                    val key = u16(j)
                    val len = u16(j + 2)
                    if (key == ECH_PARAM && len > 0) return r.copyOfRange(j + 4, j + 4 + len) to ttl
                    j += 4 + len
                }
            }
            i = start + rdLength
        }
        null
    }.getOrNull()

    data class Fetched(val config: String, val outer: String, val ttlMs: Long, val queried: String)

    /**
     * Looks up each of [names] at each pinned resolver until a usable ECHConfigList comes back whose outer name is
     * not in [avoid]. Null when none did.
     */
    fun fetch(names: List<String>, avoid: Set<String> = emptySet(), log: (String) -> Unit = {}): Fetched? {
        for (name in names.distinct().filter(SmartSni::validName)) {
            for (resolver in DOH) {
                val got = echParam(runCatching { doh(url(resolver, name)) }.getOrNull()) ?: continue
                val outer = publicNames(got.first).firstOrNull { it !in avoid && SmartSni.validName(it) }
                if (outer == null) { log("Smart ECH: $name's key names an outer name that is cooling down here"); break }
                val ttl = (got.second * 1000).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
                return Fetched(Base64.getEncoder().encodeToString(got.first), outer, ttl, name)
            }
        }
        return null
    }

    // ---- per-network state ---------------------------------------------------------------------------------------

    data class State(
        val config: String? = null,
        val outer: String? = null,
        val fetchedAt: Long = 0,
        val ttlMs: Long = 0,
        /** The config a retry after a failure is using (each rejection is retried once). */
        val retriedWith: String? = null,
        /** ECH stays off on this network until then. */
        val failedUntil: Long = 0,
        val okAt: Long = 0
    ) {
        fun fresh(now: Long) = config != null && now - fetchedAt in 0 until ttlMs
        fun failed(now: Long) = failedUntil > now
    }

    private fun key(network: String) = "smartEch#$network"

    @Synchronized
    fun state(network: String?): State {
        if (network.isNullOrBlank()) return State()
        val o = runCatching { JSONObject(BuiltinMethodMemory.read(key(network)) ?: return State()) }.getOrNull() ?: return State()
        return State(o.optString("cfg").takeIf { validConfigList(it) }, o.optString("outer").takeIf { SmartSni.validName(it) },
            o.optLong("at"), o.optLong("ttl"), o.optString("retry").ifBlank { null }, o.optLong("fail"), o.optLong("ok"))
    }

    @Synchronized
    fun save(network: String?, s: State) {
        if (network.isNullOrBlank()) return
        val o = JSONObject().put("at", s.fetchedAt).put("ttl", s.ttlMs).put("fail", s.failedUntil).put("ok", s.okAt)
        s.config?.let { o.put("cfg", it) }
        s.outer?.let { o.put("outer", it) }
        s.retriedWith?.let { o.put("retry", it) }
        runCatching { BuiltinMethodMemory.write(key(network), o.toString()) }
    }

    /** The names whose HTTPS record is looked up: the manual outer name, else Cloudflare's and Smart SNI's winners. */
    fun queryNames(network: String?): List<String> = SmartSniSettings.manualEchOuter.takeIf { it.isNotBlank() }?.let { listOf(it) }
        ?: (listOf(CLOUDFLARE_OUTER) + SmartSni.ranked(network)).distinct().take(4)

    /** Fetches a key for [network] when the cached one is stale (or [force]); keeps the old one when DoH fails. */
    fun refresh(network: String?, force: Boolean = false, now: Long = System.currentTimeMillis(), log: (String) -> Unit = {}): State {
        val s = state(network)
        if (!force && s.fresh(now)) return s
        val f = fetch(queryNames(network), SmartSni.cooling(network, now), log)
        if (f == null) { log("Smart ECH: no resolver returned an ECH key on this network"); return s }
        if (f.config != s.config) log("Smart ECH: new ECH key (outer name ${f.outer}, via ${f.queried})")
        return s.copy(config = f.config, outer = f.outer, fetchedAt = now, ttlMs = f.ttlMs).also { save(network, it) }
    }

    // ---- decision ------------------------------------------------------------------------------------------------

    data class Decision(val config: String?, val outer: String?, val manual: Boolean, val reason: String) {
        val applies: Boolean get() = config != null
    }

    /** A Cloudflare-fronted TLS config (literal Cloudflare address, or a workers.dev / pages.dev name). Pure. */
    fun cloudflareFronted(p: VlessProfile): Boolean {
        if (!com.example.vpn.cleanip.CdnEdgeScanner.fronted(p)) return false
        val address = p.address.trim().removePrefix("[").removeSuffix("]")
        if (com.example.vpn.cleanip.CdnRanges.ipv4(address) != null)
            return com.example.vpn.cleanip.CdnRanges.cdnOf(address) == com.example.vpn.cleanip.Cdn.CLOUDFLARE
        return listOf(address, p.sni, p.host).any { n ->
            val h = n.trim().lowercase().trimEnd('.')
            h.endsWith(".workers.dev") || h.endsWith(".pages.dev")
        }
    }

    /**
     * Whether and with which ECHConfigList [p] connects on [network]. Never touches a config's own ECH. A manual
     * ECHConfigList wins (any TLS-fronted config); otherwise Smart ECH needs: the setting on, a Cloudflare front, no
     * recent ECH failure here, a cached key, and its outer name not on cooldown here. Pure apart from memory reads.
     */
    fun decide(p: VlessProfile, network: String?, now: Long = System.currentTimeMillis()): Decision {
        if (p.echConfigList.isNotBlank()) return Decision(null, null, false, "the config has its own ECH")
        val manual = SmartSniSettings.manualEchConfig
        if (manual.isNotBlank()) {
            if (!com.example.vpn.cleanip.CdnEdgeScanner.fronted(p)) return Decision(null, null, true, "not a CDN-fronted TLS config")
            return Decision(manual, decode(manual)?.let { publicNames(it).firstOrNull() }, true, "manual ECHConfigList")
        }
        if (!SmartSniSettings.smartEch) return Decision(null, null, false, "Smart ECH is off")
        if (!cloudflareFronted(p)) return Decision(null, null, false, "not fronted by Cloudflare")
        val s = state(network)
        if (s.failed(now)) return Decision(null, s.outer, false, "ECH failed on this network recently; plain TLS")
        val cfg = s.retriedWith ?: s.config ?: return Decision(null, null, false, "no ECH key for this network yet")
        val outer = decode(cfg)?.let { publicNames(it).firstOrNull() } ?: return Decision(null, null, false, "cached key unreadable")
        if (outer in SmartSni.cooling(network, now)) return Decision(null, outer, false, "outer name $outer is cooling down here (blocked)")
        return Decision(cfg, outer, false, if (s.retriedWith != null) "retry with the refreshed key" else "Smart ECH")
    }

    /** [p] with [d]'s ECHConfigList (Xray `echConfigList`, base64) and a browser fingerprint when it had none. Pure. */
    fun applyTo(p: VlessProfile, d: Decision): VlessProfile = if (!d.applies || p.echConfigList.isNotBlank()) p
        else p.copy(echConfigList = d.config!!, fingerprint = p.fingerprint.ifBlank { "chrome" })

    enum class Next { OK, RETRY_NEW_KEY, PLAIN_TLS }

    /**
     * After a connect with automatic ECH ([d]) on [network]: success keeps it; a failure is treated as a possible
     * rejection — the key is fetched again; a changed key is retried once ([Next.RETRY_NEW_KEY]); otherwise ECH is off
     * here for [FAIL_COOLDOWN_MS] and the outer name goes on Smart SNI's cooldown ([Next.PLAIN_TLS]). A manual
     * ECHConfigList is never turned off automatically.
     */
    fun onResult(network: String?, d: Decision, verified: Boolean, now: Long = System.currentTimeMillis(),
                 refetch: () -> State = { refresh(network, force = true, now = now) }, log: (String) -> Unit = {}): Next {
        if (!d.applies) return Next.OK
        val s = state(network)
        if (verified) {
            save(network, s.copy(okAt = now, retriedWith = null, failedUntil = 0))
            d.outer?.let { SmartSni.recordSuccess(network, it, now) }
            return Next.OK
        }
        if (d.manual) { log("Smart ECH: the manual ECHConfigList did not connect; it stays until cleared in Settings"); return Next.PLAIN_TLS }
        val alreadyRetried = s.retriedWith != null
        if (!alreadyRetried) {
            val fresh = refetch()
            if (fresh.config != null && fresh.config != d.config) {
                save(network, fresh.copy(retriedWith = fresh.config))
                log("Smart ECH: the server may have rotated its key; retrying once with the refreshed key")
                return Next.RETRY_NEW_KEY
            }
        }
        save(network, state(network).copy(retriedWith = null, failedUntil = now + FAIL_COOLDOWN_MS))
        d.outer?.let { SmartSni.rotate(network, it, now) }
        log("Smart ECH: ECH did not connect here; plain TLS for ${FAIL_COOLDOWN_MS / 3_600_000} h" +
            (d.outer?.let { ", outer name $it cooling down" } ?: ""))
        return Next.PLAIN_TLS
    }

    /** What LAB shows. */
    fun status(network: String?, now: Long = System.currentTimeMillis()): String {
        val manual = SmartSniSettings.manualEchConfig
        if (manual.isNotBlank()) return "on (manual ECHConfigList, outer ${decode(manual)?.let { publicNames(it).firstOrNull() } ?: "?"})"
        if (!SmartSniSettings.smartEch) return "off"
        val s = state(network)
        return when {
            s.failed(now) -> "off on this network (failed; outer ${s.outer ?: "?"})"
            s.outer != null && s.outer in SmartSni.cooling(network, now) -> "off on this network (outer ${s.outer} blocked)"
            s.config != null -> "on (outer ${s.outer})" + if (s.fresh(now)) "" else ", key will be refreshed"
            else -> "on, no key fetched on this network yet"
        }
    }
}
