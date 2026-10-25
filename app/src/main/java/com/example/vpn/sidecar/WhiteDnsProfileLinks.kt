package com.example.vpn.sidecar

import com.example.data.model.VlessProfile
import org.json.JSONObject
import java.util.Base64

/** WhiteDNS v1 interchange only; no third-party UI or credentials are bundled. */
object WhiteDnsProfileLinks {
    fun parse(link: String): VlessProfile {
        require(link.length <= 32768) { "DNS profile link is too large" }
        val scheme = link.substringBefore("://").lowercase()
        require(scheme in setOf("cottendns", "soar")) { "Unsupported DNS profile" }
        val payload = link.substringAfter("://", "")
        require(payload.isNotEmpty() && payload.all { it.isLetterOrDigit() && it.code < 128 || it in "-_/+=" }) { "Invalid DNS profile encoding" }
        val bytes = try { Base64.getUrlDecoder().decode(payload) } catch (_: IllegalArgumentException) { Base64.getDecoder().decode(payload) }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("schema") == "whitedns.profile" && root.getInt("version") == 1) { "Unsupported DNS profile schema" }
        val p = root.getJSONObject("profile")
        val server = p.getJSONObject("server")
        val a = p.getJSONArray("resolvers")
        require(a.length() in 1..64) { "Choose one to 64 explicit DNS resolvers" }
        val resolvers = (0 until a.length()).map { a.getString(it) }
        val name = p.optString("name", scheme).take(120)
        return if (scheme == "cottendns") {
            require(p.getString("engine") == "cottendns" && server.getInt("encryption_method") == 5) { "Only authenticated CottenDNS method 5 is supported" }
            val key = server.getString("encryption_key")
            require(key.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid CottenDNS key" }
            // Standard WhiteDNS Cotten links select the native Cotten protocol, not CottenMax.
            CottenDnsSidecar.profile(CottenDnsSidecar.Settings(server.getString("domain"), key, resolvers, mode = "legacy"), name)
        } else {
            require(!p.has("engine") || p.getString("engine") == "soar") { "Mismatched DNS engine" }
            SoarSidecar.profile(server.getString("domain"), server.getString("public_key"), resolvers,
                p.optJSONObject("soarSettings") ?: JSONObject(), name)
        }
    }
}
