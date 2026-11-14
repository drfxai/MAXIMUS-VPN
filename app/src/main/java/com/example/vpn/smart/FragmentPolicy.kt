package com.example.vpn.smart

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a TCP fragment mask (Xray `streamSettings.finalmask.tcp[{type:"fragment"}]`) may look like on the wire,
 * applied to every Xray outbound right before its config is written, whatever set the mask (the user, a
 * subscription, a recovery recipe or a failover rescue).
 *
 * - **ECH:** no fragment at all. Splitting the ClientHello breaks ECH (Cloudflare ECH configs on Iranian mobile
 *   work only with fragment off), so every fragment entry is dropped when a TLS profile carries an ECH config.
 * - **REALITY:** never `tlshello`, never tiny pieces, never per-piece arrays. REALITY already borrows a clean
 *   name, so fragmenting only adds a fingerprint, and `tlshello` splits stop REALITY outright (v2rayN #10407;
 *   field log: a 5-byte/5 ms split on a REALITY server carried no traffic). A fragment entry is kept only as
 *   `packets: "1-3"` with pieces of at least [MIN_REALITY_LENGTH] bytes; anything else becomes [REALITY_SAFE].
 *   At most one fragment entry is kept.
 * - Everything else (plain TLS, non-fragment mask entries such as noise or UDP masks) is left unchanged.
 *
 * Pure.
 */
object FragmentPolicy {
    const val REALITY_PACKETS = "1-3"
    const val MIN_REALITY_LENGTH = 50
    /** The one fragment REALITY may run: the first three TCP packets in 100–200 byte pieces, 10–20 ms apart. */
    const val REALITY_SAFE = """{"packets":"1-3","length":"100-200","delay":"10-20"}"""

    /**
     * [mask] made to fit the rules above for a profile with [security] and [echConfigList]. Returns "" when
     * nothing is left; returns [mask] unchanged when it is not a JSON object (the config builder reports that).
     */
    fun apply(mask: String, security: String, echConfigList: String): String {
        if (mask.isBlank()) return mask
        val root = runCatching { JSONObject(mask) }.getOrNull() ?: return mask
        val tcp = root.optJSONArray("tcp") ?: return mask
        // Xray writes an ECH config only into TLS settings; REALITY ignores it.
        val ech = echConfigList.isNotBlank() && security.equals("tls", ignoreCase = true)
        val reality = security.equals("reality", ignoreCase = true)
        if (!ech && !reality) return mask
        val out = JSONArray()
        var changed = false
        var fragmentKept = false
        for (i in 0 until tcp.length()) {
            val entry = tcp.opt(i)
            if (entry !is JSONObject || !entry.optString("type").equals("fragment", ignoreCase = true)) {
                out.put(entry)
                continue
            }
            when {
                ech || fragmentKept -> changed = true
                realitySafe(entry.optJSONObject("settings")) -> { out.put(entry); fragmentKept = true }
                else -> {
                    out.put(JSONObject().put("type", "fragment").put("settings", JSONObject(REALITY_SAFE)))
                    fragmentKept = true
                    changed = true
                }
            }
        }
        if (!changed) return mask
        if (out.length() > 0) root.put("tcp", out) else root.remove("tcp")
        return if (root.length() == 0) "" else root.toString()
    }

    /** `packets: "1-3"`, no per-piece arrays, and every piece at least [MIN_REALITY_LENGTH] bytes. */
    fun realitySafe(settings: JSONObject?): Boolean {
        if (settings == null) return false
        if (settings.optString("packets") != REALITY_PACKETS) return false
        if (settings.has("lengths") || settings.has("delays")) return false
        val length = settings.optString("length")
        if (length.isBlank()) return false
        val bounds = length.split('-').map { it.trim().toIntOrNull() ?: return false }
        return bounds.isNotEmpty() && bounds.all { it >= MIN_REALITY_LENGTH }
    }

    /** Whether [mask] carries any TCP fragment entry. */
    fun hasFragment(mask: String): Boolean {
        val tcp = runCatching { JSONObject(mask).optJSONArray("tcp") }.getOrNull() ?: return false
        return (0 until tcp.length()).any { (tcp.opt(it) as? JSONObject)?.optString("type").equals("fragment", ignoreCase = true) }
    }
}
