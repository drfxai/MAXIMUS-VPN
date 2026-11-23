package com.example.vpn.engine

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the patterniha (PattN / Free-Configs) share-link fields stored in [ProfileExtras] go in an Xray outbound.
 *
 * Checked against the JSON names in infra/conf of XTLS/Xray-core v26.11.9 (the core in the bundled libXray) and of
 * patterniha/Xray-core 41a9fa6 (v26.11.22, the Serverless program):
 * - `tut` → `sockopt.tcpUserTimeout`, `esr` → `tlsSettings.enableSessionResumption`, `cp` → `tlsSettings.curvePreferences`
 *   exist in both cores, so [applyStock] writes them on stock Xray too; they do not need the fork.
 * - `dialMode` → `sockopt.dialMode` exists only in the fork. The fork parses it before every dial and fails the dial
 *   when it does not parse ([validDialMode] follows that grammar), so an invalid value never reaches it.
 * - finalmask `merge`, `borrow` and `proxy-protocol` TCP masks and the `tcp4`/`tcp6`/`udp4`/`udp6` family lists are
 *   fork-only too (see vpn/sidecar/ForkXraySidecar).
 */
object PatternihaFields {
    private val ATOI = Regex("[+-]?[0-9]+")

    /**
     * Whether the outbound of [profile] connects over TCP, the only place `tcpUserTimeout` means anything (the rule
     * PattN applies too): not Hysteria2, WireGuard or TUIC (UDP), not mKCP, and not XHTTP with TLS whose only ALPN is
     * `h3`, which Xray dials over QUIC.
     */
    fun tcpUserTimeoutApplies(profile: VlessProfile): Boolean {
        if (profile.protocolType in setOf(ProtocolType.HYSTERIA2, ProtocolType.WIREGUARD, ProtocolType.TUIC)) return false
        val transport = profile.transport.trim().lowercase()
        if (transport == "kcp" || transport == "mkcp") return false
        if ((transport == "xhttp" || transport == "splithttp") && profile.security.equals("tls", ignoreCase = true)) {
            val alpn = profile.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (alpn == listOf("h3")) return false
        }
        return true
    }

    /** The link's `tut` in milliseconds where it applies ([tcpUserTimeoutApplies]), else null. */
    fun tcpUserTimeout(profile: VlessProfile): Int? =
        ProfileExtras.read(profile).optInt(ProfileExtras.PATT_TCP_USER_TIMEOUT, 0)
            .takeIf { it > 0 && tcpUserTimeoutApplies(profile) }

    /** The link's `cp` as a list: split on ',' (PattN) or ':' (OpenSSL style), trimmed, blanks dropped. */
    fun curvePreferences(profile: VlessProfile): List<String> =
        ProfileExtras.read(profile).optString(ProfileExtras.PATT_CURVE_PREFERENCES)
            .split(',', ':').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Writes `tut`, `esr` and `cp` of [profile] into [stream] (an outbound's streamSettings). Session resumption and
     * curve preferences are TLS settings; REALITY has neither, so they are only written into an existing tlsSettings.
     */
    fun applyStock(stream: JSONObject, profile: VlessProfile): JSONObject {
        tcpUserTimeout(profile)?.let { timeout ->
            val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
            sockopt.put("tcpUserTimeout", timeout)
        }
        stream.optJSONObject("tlsSettings")?.let { tls ->
            if (ProfileExtras.read(profile).optBoolean(ProfileExtras.PATT_SESSION_RESUMPTION, false)) {
                tls.put("enableSessionResumption", true)
            }
            curvePreferences(profile).takeIf { it.isNotEmpty() }?.let { tls.put("curvePreferences", JSONArray(it)) }
        }
        return stream
    }

    /** The link's `dialMode` when the fork would accept it ([validDialMode]), else null. */
    fun dialMode(profile: VlessProfile): String? =
        ProfileExtras.read(profile).optString(ProfileExtras.PATT_DIAL_MODE).trim().takeIf { validDialMode(it) }

    /**
     * The `dialMode` grammar of the fork (transport/internet/dialmode_oob.go): ';'-separated options, each `oob.`,
     * `oob4.` or `oob6.` followed by a ',' list of bytes 0..255 or `_`; each prefix at most once, and `oob` not
     * together with `oob4`/`oob6`.
     */
    fun validDialMode(mode: String): Boolean {
        if (mode.isEmpty()) return false
        var any = false
        var v4 = false
        var v6 = false
        for (entry in mode.split(';')) {
            val list = when {
                entry.startsWith("oob4.") -> { if (v4) return false; v4 = true; entry.removePrefix("oob4.") }
                entry.startsWith("oob6.") -> { if (v6) return false; v6 = true; entry.removePrefix("oob6.") }
                entry.startsWith("oob.") -> { if (any) return false; any = true; entry.removePrefix("oob.") }
                else -> return false
            }
            if (list.isEmpty()) return false
            for (part in list.split(',')) {
                if (part == "_") continue
                // Go's strconv.Atoi: optional sign, ASCII digits only.
                if (!ATOI.matches(part)) return false
                if ((part.toIntOrNull() ?: return false) !in 0..255) return false
            }
        }
        return !(any && (v4 || v6))
    }
}
