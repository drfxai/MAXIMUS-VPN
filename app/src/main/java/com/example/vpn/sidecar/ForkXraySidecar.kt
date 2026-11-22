package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.safety.DnsResolvers
import com.example.vpn.tunnel.ProxyDnsTransport
import com.example.xray.XrayConfigBuilder
import com.example.xray.XrayConfigParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs a saved server whose share link carries patterniha-fork-only fields (`dialMode`, `tut`, `esr`, `cp`, or an
 * `fm` with fork-only mask types) on patterniha/Xray-core v26.11.22: the same native program as the Serverless engine
 * (`libserverless.so`, scripts/engines/serverless.sh). Stock libXray ignores those fields (or rejects the masks), so
 * the fork runs the server's one outbound behind an authenticated loopback SOCKS port and the app's Xray routes the
 * TUN through it, like every other sidecar.
 *
 * Not in [Sidecars.ENGINES]: the profile stays an ordinary saved server everywhere else (tests, Smart Connect, LAB).
 * Only the connect path picks it ([applies]) and falls back to [stockProfile] when the fork does not start.
 *
 * JSON names were checked in the fork's infra/conf at 41a9fa6: sockopt `dialMode` (string), sockopt
 * `tcpUserTimeout` (ms), tlsSettings `enableSessionResumption` (bool), tlsSettings `curvePreferences` (StringList),
 * finalmask `merge` / `borrow` masks and `tcp4` / `tcp6` / `udp4` / `udp6` family lists.
 */
object ForkXraySidecar : SidecarEngine {
    const val ID = "fork-xray"
    override val id = ID
    /** Same program as the Serverless engine. */
    override val binary = ServerlessSidecar.ID

    const val TAG = "fork-proxy"

    /** finalmask mask types only the fork knows. */
    val FORK_MASK_TYPES = setOf("merge", "borrow")
    /** finalmask lists only the fork knows (chosen after the dial by the remote's IP family). */
    val FORK_MASK_LISTS = listOf("tcp4", "tcp6", "udp4", "udp6")

    /** The fork-only link keys [profile] carries, in a stable order; empty when stock Xray can run it as it is. */
    fun forkFields(profile: VlessProfile): List<String> {
        val extras = ProfileExtras.read(profile)
        val out = mutableListOf<String>()
        if (forkOnlyMask(profile.finalMask)) out += "fm"
        if (extras.optString(ProfileExtras.PATT_DIAL_MODE).isNotBlank()) out += "dialMode"
        if (extras.optInt(ProfileExtras.PATT_TCP_USER_TIMEOUT, 0) > 0) out += "tut"
        if (extras.optBoolean(ProfileExtras.PATT_SESSION_RESUMPTION, false)) out += "esr"
        if (extras.optString(ProfileExtras.PATT_CURVE_PREFERENCES).isNotBlank()) out += "cp"
        return out
    }

    /** True when [mask] (a finalmask JSON) uses a fork-only list or mask type. */
    fun forkOnlyMask(mask: String): Boolean {
        val root = mask.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
        if (FORK_MASK_LISTS.any { root.has(it) }) return true
        for (list in listOf("tcp", "udp")) {
            val masks = root.optJSONArray(list) ?: continue
            for (i in 0 until masks.length()) {
                if (masks.optJSONObject(i)?.optString("type")?.lowercase() in FORK_MASK_TYPES) return true
            }
        }
        return false
    }

    /** A plain saved server (not an engine, chain or whole Xray config) with at least one fork-only field. */
    fun applies(profile: VlessProfile): Boolean = profile.profileType != ProfileType.XRAY_JSON &&
        profile.address.isNotBlank() && !Sidecars.isEngineProfile(profile) && forkFields(profile).isNotEmpty()

    override fun handles(profile: VlessProfile) = applies(profile)

    /**
     * [profile] for stock Xray: the fork-only extras removed and the fork-only parts of its finalmask dropped
     * (stock libXray rejects an unknown mask type, which would fail the whole connect). Unchanged otherwise.
     */
    fun stockProfile(profile: VlessProfile): VlessProfile {
        if (forkFields(profile).isEmpty()) return profile
        var p = profile.copy(finalMask = stockMask(profile.finalMask))
        for (key in listOf(ProfileExtras.PATT_DIAL_MODE, ProfileExtras.PATT_TCP_USER_TIMEOUT,
                ProfileExtras.PATT_SESSION_RESUMPTION, ProfileExtras.PATT_CURVE_PREFERENCES)) {
            p = ProfileExtras.with(p, key, null)
        }
        return p
    }

    internal fun stockMask(mask: String): String {
        if (!forkOnlyMask(mask)) return mask
        val root = JSONObject(mask)
        FORK_MASK_LISTS.forEach { root.remove(it) }
        for (list in listOf("tcp", "udp")) {
            val masks = root.optJSONArray(list) ?: continue
            val kept = JSONArray()
            for (i in 0 until masks.length()) {
                val m = masks.opt(i)
                if ((m as? JSONObject)?.optString("type")?.lowercase() !in FORK_MASK_TYPES) kept.put(m)
            }
            if (kept.length() == 0) root.remove(list) else root.put(list, kept)
        }
        return if (root.length() == 0) "" else root.toString()
    }

    /** Puts the fork-only fields of [profile] into [outbound] (built by the app's own Xray builder) under the fork's keys. */
    fun applyForkFields(outbound: JSONObject, profile: VlessProfile): JSONObject {
        val extras = ProfileExtras.read(profile)
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject().also { outbound.put("streamSettings", it) }
        fun sockopt() = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
        extras.optString(ProfileExtras.PATT_DIAL_MODE).trim().takeIf { it.isNotEmpty() }?.let { sockopt().put("dialMode", it) }
        extras.optInt(ProfileExtras.PATT_TCP_USER_TIMEOUT, 0).takeIf { it > 0 }?.let { sockopt().put("tcpUserTimeout", it) }
        // Session resumption and curve preferences are TLS settings; REALITY has neither.
        stream.optJSONObject("tlsSettings")?.let { tls ->
            if (extras.optBoolean(ProfileExtras.PATT_SESSION_RESUMPTION, false)) tls.put("enableSessionResumption", true)
            extras.optString(ProfileExtras.PATT_CURVE_PREFERENCES).split(',', ':').map { it.trim() }.filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }?.let { tls.put("curvePreferences", JSONArray(it)) }
        }
        // The builder already wrote the profile's finalmask as it is (fork masks included).
        return outbound
    }

    /** The server's single outbound for the fork. Its address must already be literal (the fork runs outside the VPN). */
    fun outbound(profile: VlessProfile, settings: AppSettings): JSONObject {
        val root = JSONObject(XrayConfigBuilder.buildJson(profile, settings))
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        XrayConfigParser.requireExecutableOutbound(outbound)
        return applyForkFields(outbound, profile).put("tag", TAG)
    }

    /** The whole fork config: one authenticated SOCKS inlet on loopback, everything to [outbound]. */
    fun config(outbound: JSONObject, port: Int, user: String, password: String, dnsServer: String): JSONObject {
        require(port in 1..65535 && user.isNotBlank() && password.isNotBlank())
        val inlet = JSONObject().put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "password").put("udp", true).put("accounts",
                JSONArray().put(JSONObject().put("user", user).put("pass", password))))
        val root = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inlet)).put("outbounds", JSONArray().put(outbound.put("tag", TAG)))
            .put("dns", JSONObject().put("servers", JSONArray().put(DnsResolvers.literal(dnsServer)))
                .put("tag", "fork-dns-query").put("disableFallback", true))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray().put(
                JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", TAG))))
        return XrayConfigBuilder.stripSocketMarks(root)
    }

    /** Pins a host name to the address it resolves to outside the VPN (same resolver as the exit hop). */
    internal fun literal(profile: VlessProfile): VlessProfile {
        val host = profile.address.trim().removePrefix("[").removeSuffix("]")
        if (ProxyDnsTransport.isLiteralAddress(host)) return profile
        val address = BuiltinExitNode.resolveAddress(host)
        check(ProxyDnsTransport.isLiteralAddress(address)) { "fork-xray: server address could not be resolved" }
        return com.example.vpn.smart.RouteRecipe.pinEndpoint(profile, address)
    }

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        require(context.upstreamSocks == null) { "fork-xray is not a chain hop" }
        val root = config(outbound(literal(profile), settings), context.socksPort, context.socksUser, context.socksPass, settings.dnsServer)
        val file = File(context.workDir, "fork-xray.json").apply {
            parentFile?.mkdirs(); writeText(root.toString())
            setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true)
        }
        return SidecarLaunch(listOf(context.executable.absolutePath, "run", "-c", file.absolutePath),
            readyTimeoutMs = 15_000, logOutput = false)
    }
}
