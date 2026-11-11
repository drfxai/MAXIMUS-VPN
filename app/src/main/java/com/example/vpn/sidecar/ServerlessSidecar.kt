package com.example.vpn.sidecar

import com.example.data.model.*
import com.example.vpn.engine.ProfileExtras
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Direct anti-DPI access. Never claims a foreign exit or silently enables a VPN fallback. */
object ServerlessSidecar : SidecarEngine {
    const val ID = "serverless"
    const val CONFIG_REVISION = "df113f5b0fafc5bbe552e903f43249110510bc34"
    override val id = ID
    override val binary = ID
    val resolvers = listOf("auto", "cloudflare", "google")
    val variants = listOf("auto", "fragA", "fragB", "oob")
    override fun handles(profile: VlessProfile) = ProfileExtras.read(profile).optString("engine") == ID
    override fun problem(profile: VlessProfile): String? = when {
        ProfileExtras.read(profile).optString("variant", "auto") !in variants -> "Unsupported serverless profile"
        ProfileExtras.read(profile).optString("family", "auto") !in AetherSidecar.families -> "Unsupported IP family"
        ProfileExtras.read(profile).optString("resolver", "auto") !in resolvers -> "Unsupported encrypted DNS resolver"
        else -> AetherSettings.read(ProfileExtras.read(profile)).problem()
    }
    fun profile(variant: String = "auto", family: String = "auto", vpnFallback: Boolean = false, resolver: String = "auto", advanced: AetherSettings = AetherSettings()) : VlessProfile {
        require(variant in variants && family in AetherSidecar.families && resolver in resolvers && advanced.problem() == null)
        return VlessProfile(id = "maximus-serverless", name = advanced.alias.ifBlank { "Direct Serverless" }, address = ID, port = 0, uuid = "",
            protocolType = ProtocolType.MIXED, extraSettings = advanced.write(JSONObject()).put("engine", ID)
                .put("variant", variant).put("family", family).put("vpnFallback", vpnFallback).put("resolver", resolver).toString())
    }
    /** Keep DPI masks, repairing fragB's empty TLS record; replace the inlet and geo classifications. */
    fun config(source: String, port: Int, user: String, password: String, family: String, resolver: String = "auto", advanced: AetherSettings = AetherSettings(),
        exitOutbound: JSONObject? = null): JSONObject {
        require(port in 1..65535 && user.isNotBlank() && password.isNotBlank())
        require(family in AetherSidecar.families && resolver in resolvers)
        val root = JSONObject(source)
        root.remove("__Credits__"); root.remove("remarks"); root.remove("version")
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        inbound.put("listen", "127.0.0.1").put("port", port)
        inbound.getJSONObject("settings").put("auth", "password")
            .put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", password)))
        // Geo categories require external mutable files and do not define the anti-DPI method.
        // Explicit domains/IP blocks remain; DNS is always DoH through the fragmented path.
        val dns = root.getJSONObject("dns")
        val upstream = dns.getJSONArray("servers").getJSONObject(1)
        // A domain alias still needs DNS. With the domestic resolver removed,
        // resolving challenges.cloudflare.com recursively asked this very DoH
        // path. Pin bootstrap IPs while retaining the authenticated TLS hostname.
        fun bootstrap(v4: List<String>, v6: List<String>) = JSONArray(when (family) {
            "ipv4" -> v4
            "ipv6" -> v6
            else -> v4 + v6
        })
        dns.put("hosts", JSONObject()
            .put("cloudflare-dns.com", bootstrap(com.example.vpn.BundledResolvers.known("1.1.1.1", "1.0.0.1"),
                listOf("2606:4700:4700::1111", "2606:4700:4700::1001")))
            .put("dns.google", bootstrap(com.example.vpn.BundledResolvers.known("8.8.8.8", "8.8.4.4"),
                listOf("2001:4860:4860::8888", "2001:4860:4860::8844"))))
        upstream.remove("finalQuery")
        upstream.put("timeoutMs", 4_000)
        dns.put("servers", JSONArray().put(upstream).put(JSONObject().put("tag", "no-filter-dns-ip")
            .put("address", "https+one://dns.google/dns-query").put("finalQuery", true).put("timeoutMs", 4_000)))
        if (resolver == "google") {
            val servers = dns.getJSONArray("servers")
            val cloudflare = servers.getJSONObject(0).put("finalQuery", true)
            val google = servers.getJSONObject(1)
            google.remove("finalQuery")
            dns.put("servers", JSONArray().put(google).put(cloudflare))
        }
        dns.put("queryStrategy", when(family) { "ipv4" -> "UseIPv4"; "ipv6" -> "UseIPv6"; else -> "UseIP" })
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        val kept = JSONArray()
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val tags = r.optJSONArray("inboundTag")
            if (tags?.optString(0) == "domestic-dns") continue
            if (tags?.optString(0) == "no-filter-dns") tags.put("no-filter-dns-ip")
            for (key in listOf("domain", "ip")) r.optJSONArray(key)?.let { entries ->
                val clean = JSONArray()
                for (j in 0 until entries.length()) if (!entries.getString(j).startsWith("geo")) clean.put(entries.getString(j))
                r.put(key, clean)
            }
            if (listOf("domain", "ip").any { r.has(it) && r.getJSONArray(it).length() == 0 }) continue
            kept.put(r)
        }
        root.getJSONObject("routing").put("rules", kept)
        val out = root.getJSONArray("outbounds")
        for (i in 0 until out.length()) out.getJSONObject(i).optJSONObject("streamSettings")?.let { stream ->
            // The pinned fragB pattern [0,105,1] emits an empty Handshake record.
            // Standards-compliant TLS servers reject it with unexpected_message.
            // Move one byte into that record, retaining the next split's offset.
            stream.optJSONObject("finalmask")?.optJSONArray("tcp")?.let { masks ->
                for (j in 0 until masks.length()) {
                    val mask = masks.getJSONObject(j)
                    val config = mask.optJSONObject("settings")
                    val lengths = config?.optJSONArray("lengths")
                    if (mask.optString("type") == "fragment" && config?.optString("packets") == "tlshello" &&
                        lengths?.optString(0) == "0" && lengths.optString(1) == "105") {
                        lengths.put(0, "1").put(1, "104")
                    }
                }
            }
            stream.optJSONObject("sockopt")?.let { sock ->
                if (family != "auto") { sock.remove("happyEyeballs"); sock.put("domainStrategy", if (family == "ipv4") "ForceIPv4" else "ForceIPv6") }
            }
        }
        if (exitOutbound != null) {
            val exitTag = "serverless-exit"
            exitOutbound.put("tag", exitTag)
            for (i in 0 until out.length()) {
                val outbound = out.getJSONObject(i)
                if (outbound.optString("protocol") !in setOf("freedom", "direct")) continue
                val stream = outbound.optJSONObject("streamSettings") ?: JSONObject().also { outbound.put("streamSettings", it) }
                val sock = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
                sock.put("dialerProxy", exitTag)
            }
            out.put(exitOutbound)
        } else if (advanced.exitFinalMask.isNotBlank() || advanced.exitDialMode.isNotBlank()) {
            for (i in 0 until out.length()) {
                val outbound = out.getJSONObject(i)
                if (outbound.optString("protocol") !in setOf("freedom", "direct")) continue
                val stream = outbound.optJSONObject("streamSettings") ?: JSONObject().also { outbound.put("streamSettings", it) }
                if (advanced.exitFinalMask.isNotBlank()) stream.put("finalmask", JSONObject(advanced.exitFinalMask))
                if (advanced.exitDialMode.isNotBlank()) {
                    val sock = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
                    sock.put("dialMode", advanced.exitDialMode)
                }
            }
        }
        return root
    }
    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        require(context.upstreamSocks == null) { "Direct mode cannot silently skip a requested hop" }
        problem(profile)?.let { error(it) }
        val e = ProfileExtras.read(profile)
        val advanced = AetherSettings.read(e)
        val exit = BuiltinExitNode.selected(advanced, settings)
        val variant = e.optString("variant", "auto").let { if (it == "auto") "fragA" else it }
        val source = Sidecars.openAsset?.invoke("serverless/Serverless-$variant.jsonc")?.bufferedReader()?.use { it.readText() }
            ?: error("Pinned serverless configuration is missing")
        val file = File(context.workDir, "serverless.json").apply {
            parentFile?.mkdirs(); writeText(config(source, context.socksPort, context.socksUser, context.socksPass, e.optString("family", "auto"), e.optString("resolver", "auto"), advanced, exit).toString())
            setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true)
        }
        return SidecarLaunch(listOf(context.executable.absolutePath, "run", "-c", file.absolutePath),
            readyTimeoutMs = 15_000, logOutput = false)
    }
}
