package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.PatternihaFields
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.safety.DnsResolvers
import com.example.vpn.tunnel.ProxyDnsTransport
import com.example.xray.XrayConfigBuilder
import com.example.xray.XrayConfigParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs a saved server whose share link carries patterniha-fork-only fields (a valid `dialMode`, or an `fm` with
 * fork-only mask types or family lists) on patterniha/Xray-core v26.11.24: the same native program as the Serverless
 * engine (`libserverless.so`, scripts/engines/serverless.sh). Stock libXray has no `dialMode` and rejects the masks,
 * so the fork runs the server's one outbound behind an authenticated loopback SOCKS port and the app's Xray routes the
 * TUN through it, like every other sidecar. `tut`, `esr` and `cp` are not fork-only: stock Xray v26.11.9 has
 * `tcpUserTimeout`, `enableSessionResumption` and `curvePreferences`, and the app's builder writes them on every core
 * ([com.example.vpn.engine.PatternihaFields.applyStock]).
 *
 * Not in [Sidecars.ENGINES]: the profile stays an ordinary saved server everywhere else (tests, Smart Connect, LAB).
 * Only the connect path picks it ([applies]) and falls back to [stockProfile] when the fork does not start.
 *
 * JSON names were checked in the fork's infra/conf at 41a9fa6, 40fd599 (v26.11.23) and 7131abd (v26.11.24) against XTLS/Xray-core v26.11.9: fork-only are sockopt
 * `dialMode` (string, parsed before every dial: an invalid one fails the dial, so it is checked first), the TCP masks
 * `merge`, `borrow` and `proxy-protocol` (the last is upstream only after v26.11.9), and the `tcp4` / `tcp6` / `udp4` /
 * `udp6` family lists, which replace `tcp` / `udp` for a remote of that IP family.
 * The link's `echOutbound` (PattN) becomes a second outbound that carries the ECH config lookup (`echSockopt.dialerProxy`).
 */
object ForkXraySidecar : SidecarEngine {
    const val ID = "fork-xray"
    override val id = ID
    /** Same program as the Serverless engine. */
    override val binary = ServerlessSidecar.ID

    const val TAG = "fork-proxy"
    const val ECH_TAG = "fork-ech"

    /** finalmask mask types the fork knows and stock Xray v26.11.9 does not (all TCP masks). */
    val FORK_MASK_TYPES = setOf("merge", "borrow", "proxy-protocol")
    /** finalmask lists only the fork knows (chosen after the dial by the remote's IP family). */
    val FORK_MASK_LISTS = listOf("tcp4", "tcp6", "udp4", "udp6")

    /** The fork-only link keys [profile] carries, in a stable order; empty when stock Xray can run it as it is. */
    fun forkFields(profile: VlessProfile): List<String> {
        val out = mutableListOf<String>()
        if (forkOnlyMask(profile.finalMask)) out += "fm"
        // An invalid dialMode would fail every dial of the fork, so it does not count (stock Xray runs without it).
        if (PatternihaFields.dialMode(profile) != null) out += "dialMode"
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
     * [profile] for stock Xray: `dialMode` removed and the fork-only parts of its finalmask dropped (stock libXray
     * rejects an unknown mask type, which would fail the whole connect). `tut`, `esr` and `cp` stay: stock Xray has
     * them. Unchanged otherwise.
     */
    fun stockProfile(profile: VlessProfile): VlessProfile {
        if (forkFields(profile).isEmpty()) return profile
        val host = profile.address.trim().removePrefix("[").removeSuffix("]")
        val ipv6 = ProxyDnsTransport.isLiteralAddress(host) && host.contains(':')
        return ProfileExtras.with(profile.copy(finalMask = stockMask(profile.finalMask, ipv6)), ProfileExtras.PATT_DIAL_MODE, null)
    }

    /**
     * [mask] without what stock Xray cannot run. A family list (`tcp4`/`tcp6`, `udp4`/`udp6`) replaces the generic
     * list on the fork (the two are exclusive there), so on stock Xray the list for the server's family ([ipv6]; the
     * fork also takes the v4 list for anything that is not IPv6) becomes the generic one, fork-only mask types removed.
     */
    internal fun stockMask(mask: String, ipv6: Boolean = false): String {
        if (!forkOnlyMask(mask)) return mask
        val root = JSONObject(mask)
        for (list in listOf("tcp", "udp")) {
            val family = root.optJSONArray(list + if (ipv6) "6" else "4")
            if (family != null && !root.has(list)) root.put(list, family)
        }
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

    /**
     * Puts the fork-only fields of [profile] into [outbound] (built by the app's own Xray builder, which already wrote
     * `tut`, `esr`, `cp` and the finalmask as it is, fork masks included) under the fork's keys.
     */
    fun applyForkFields(outbound: JSONObject, profile: VlessProfile): JSONObject {
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject().also { outbound.put("streamSettings", it) }
        PatternihaFields.dialMode(profile)?.let { mode ->
            (stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }).put("dialMode", mode)
        }
        // PattN's echOutbound: the ECH config lookup goes out through it, unless the link set its own echSockopt.
        stream.optJSONObject("tlsSettings")?.let { tls ->
            if (echOutbound(profile) != null && !tls.has("echSockopt")) {
                tls.put("echSockopt", JSONObject().put("dialerProxy", ECH_TAG))
            }
        }
        return outbound
    }

    /**
     * The link's `echOutbound` tagged [ECH_TAG], when it can be used: a JSON outbound with a protocol, on a TLS server
     * whose ECH config is looked up (`echConfigList` set) and that has no `echSockopt` of its own. Null otherwise.
     */
    fun echOutbound(profile: VlessProfile): JSONObject? {
        if (!profile.security.equals("tls", ignoreCase = true) || profile.echConfigList.isBlank() || profile.echSockopt.isNotBlank()) {
            return null
        }
        val raw = ProfileExtras.read(profile).optJSONObject(ProfileExtras.PATT_ECH_OUTBOUND) ?: return null
        if (raw.optString("protocol").isBlank()) return null
        return JSONObject(raw.toString()).put("tag", ECH_TAG)
    }

    /** The server's single outbound for the fork. Its address must already be literal (the fork runs outside the VPN). */
    fun outbound(profile: VlessProfile, settings: AppSettings): JSONObject {
        val root = JSONObject(XrayConfigBuilder.buildJson(profile, settings))
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        XrayConfigParser.requireExecutableOutbound(outbound)
        return applyForkFields(outbound, profile).put("tag", TAG)
    }

    /** The whole fork config: one authenticated SOCKS inlet on loopback, everything to [outbound]. */
    fun config(outbound: JSONObject, port: Int, user: String, password: String, dnsServer: String,
               echOutbound: JSONObject? = null): JSONObject {
        require(port in 1..65535 && user.isNotBlank() && password.isNotBlank())
        val inlet = JSONObject().put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "password").put("udp", true).put("accounts",
                JSONArray().put(JSONObject().put("user", user).put("pass", password))))
        val root = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inlet))
            .put("outbounds", JSONArray().put(outbound.put("tag", TAG)).apply { echOutbound?.let { put(it.put("tag", ECH_TAG)) } })
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
        val root = config(outbound(literal(profile), settings), context.socksPort, context.socksUser, context.socksPass,
            settings.dnsServer, echOutbound(profile))
        val file = File(context.workDir, "fork-xray.json").apply {
            parentFile?.mkdirs(); writeText(root.toString())
            setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true)
        }
        return SidecarLaunch(listOf(context.executable.absolutePath, "run", "-c", file.absolutePath),
            readyTimeoutMs = 15_000, logOutput = false)
    }
}
