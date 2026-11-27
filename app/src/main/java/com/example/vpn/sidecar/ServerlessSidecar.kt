package com.example.vpn.sidecar

import com.example.data.model.*
import com.example.vpn.engine.ProfileExtras
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Direct anti-DPI access. Never claims a foreign exit or silently enables a VPN fallback. */
object ServerlessSidecar : SidecarEngine {
    const val ID = "serverless"
    const val CONFIG_REVISION = "9b62ddf9e8bc83432d4b0bcf8be4c963bc8cb300"
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
    /** Adapt the upstream profiles to an authenticated Android sidecar with end-to-end TLS. */
    fun config(source: String, port: Int, user: String, password: String, family: String, resolver: String = "auto", advanced: AetherSettings = AetherSettings(),
        exitOutbound: JSONObject? = null): JSONObject {
        require(port in 1..65535 && user.isNotBlank() && password.isNotBlank())
        require(family in AetherSidecar.families && resolver in resolvers)
        val root = JSONObject(source)
        root.remove("__Credits__"); root.remove("remarks"); root.remove("version")
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        inbound.put("listen", "127.0.0.1").put("port", port)
        // The outer tunnel restores names before SOCKS. Sniff here for routing without
        // replacing the destination explicitly requested by the authenticated client.
        inbound.getJSONObject("sniffing").put("routeOnly", true)
            .put("destOverride", JSONArray(listOf("tls", "http", "quic")))
        inbound.getJSONObject("settings").put("auth", "password")
            .put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", password)))
        val routing = root.getJSONObject("routing")
        val sourceOutbounds = root.getJSONArray("outbounds")
        val out = JSONArray()
        val removedTags = mutableSetOf("tls-repack-dns")
        val fallbackTags = mutableMapOf<String, String>()
        for (i in 0 until sourceOutbounds.length()) {
            val outbound = sourceOutbounds.getJSONObject(i)
            val tag = outbound.getString("tag")
            // The desktop profile can redirect selected destinations to shared edges.
            // Android uses the original destination and authenticates it end to end.
            if (tag == "tls-repack-dns" || outbound.optJSONObject("settings")?.has("redirect") == true) {
                removedTags.add(tag)
                continue
            }
            val fallbackTag = outbound.optJSONObject("streamSettings")?.optJSONObject("sockopt")
                ?.optJSONObject("fallback")?.optString("noResponseFallbackDialerProxy").orEmpty()
            if (fallbackTag.isNotEmpty()) fallbackTags[tag] = fallbackTag
            out.put(outbound)
        }
        root.put("outbounds", out)
        val maskedTcpTag = fallbackTags.values.single()

        val dns = root.getJSONObject("dns")
        dns.remove("skipHostsWhenFakeEnable")
        val upstream = JSONObject().put("tag", "no-filter-dns")
            .put("address", "https+one://cloudflare-dns.com/dns-query")
        // Pin bootstrap IPs to avoid needing another resolver to reach either DoH service.
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
        val rules = routing.getJSONArray("rules")
        val kept = JSONArray()
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val tags = r.optJSONArray("inboundTag")
            if (tags?.optString(0) == "domestic-dns") continue
            if (tags?.optString(0) == "no-filter-dns") {
                tags.put("no-filter-dns-ip")
                r.put("outboundTag", maskedTcpTag)
            }
            val tag = r.getString("outboundTag")
            if (tag in removedTags) continue
            // v65 blocks DNS-over-TLS so desktop apps fall back to the profile's repacked DNS. Android keeps
            // its own authenticated DoH here instead, and a blocked port 853 would break Private DNS (strict).
            if (tag == "block" && r.optString("port") == "853" && !r.has("domain") && !r.has("ip")) continue
            // Use the masked path immediately for port-only matches and for selected exits.
            fallbackTags[tag]?.let { fallback ->
                if (r.optJSONArray("protocol") == null || exitOutbound != null) r.put("outboundTag", fallback)
            }
            for (key in listOf("domain", "ip")) r.optJSONArray(key)?.let { entries ->
                val clean = JSONArray()
                for (j in 0 until entries.length()) if (!entries.getString(j).startsWith("geo")) clean.put(entries.getString(j))
                r.put(key, clean)
            }
            if (listOf("domain", "ip").any { r.has(it) && r.getJSONArray(it).length() == 0 }) continue
            kept.put(r)
        }
        routing.put("rules", kept)
        for (i in 0 until out.length()) out.getJSONObject(i).optJSONObject("streamSettings")?.let { stream ->
            // Move a byte into fragB's empty TLS record while preserving the next split offset.
            stream.optJSONObject("finalmask")?.optJSONArray("tcp")?.let { masks ->
                for (j in 0 until masks.length()) {
                    val mask = masks.getJSONObject(j)
                    val config = mask.optJSONObject("settings")
                    val lengths = config?.optJSONArray("lengths")
                    if (mask.optString("type") == "fragment" && config?.optString("packets") == "tlshello" &&
                        lengths?.optString(0) == "0") {
                        val nextLength = lengths.optInt(1)
                        if (nextLength > 1) lengths.put(0, "1").put(1, (nextLength - 1).toString())
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
                sock.remove("fallback")
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
    /** Extras key: sites the direct path cannot reach go through Lantern (Build 181, an explicit labelled pick only). */
    const val KEY_LANTERN_FALLBACK = "lanternFallback"
    const val LANTERN_TAG = "lantern-fallback"
    private const val CONFIG_FILE = "serverless.json"

    /**
     * Build 181 (3.4): patterniha Xray's sockopt.fallback (commit 62b18f6, in the pinned v26.11.24) re-dials the ORIGINAL
     * target through Lantern's SOCKS port when the direct dial fails, the site sends nothing back ([NO_RESPONSE_WAIT_MS]),
     * or the name resolves to no address. The profile's own first fallback (direct, then the masked TLS path) stays; the
     * masked path, and every TCP path without a fallback, fall back to Lantern. UDP, DNS and block are left alone. Pure
     * apart from changing [root] in place.
     */
    const val NO_RESPONSE_WAIT_MS = 4_000

    fun withLanternFallback(root: JSONObject, port: Int, user: String, pass: String): JSONObject {
        require(port in 1..65535 && user.isNotBlank() && pass.isNotBlank())
        val outbounds = root.getJSONArray("outbounds")
        for (i in 0 until outbounds.length()) {
            val o = outbounds.getJSONObject(i)
            if (o.optString("protocol") !in setOf("freedom", "direct") || !o.optString("tag").startsWith("tcp-")) continue
            val stream = o.optJSONObject("streamSettings") ?: JSONObject().also { o.put("streamSettings", it) }
            val sock = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
            if (sock.has("dialerProxy")) continue // a saved-server exit already carries this path
            val fallback = sock.optJSONObject("fallback")
            if (fallback != null) {
                fallback.put("failedDialFallbackDialerProxy", LANTERN_TAG).put("noIPFallbackDialerProxy", LANTERN_TAG)
            } else {
                sock.put("fallback", JSONObject().put("failedDialFallbackDialerProxy", LANTERN_TAG)
                    .put("noResponseFallbackDialerProxy", LANTERN_TAG).put("noIPFallbackDialerProxy", LANTERN_TAG)
                    .put("maxBufferSize", 16_384).put("waitMs", NO_RESPONSE_WAIT_MS))
            }
        }
        outbounds.put(JSONObject().put("tag", LANTERN_TAG).put("protocol", "socks").put("settings", JSONObject()
            .put("address", "127.0.0.1").put("port", port).put("user", user).put("pass", pass)))
        return root
    }

    /** "socks5://user:pass@127.0.0.1:port" (LanternRelayHop.upstream) as (port, user, pass). Pure. */
    internal fun parseUpstream(upstream: String): Triple<Int, String, String>? {
        val m = Regex("^socks5://([A-Za-z0-9_-]+):([A-Za-z0-9_-]+)@127\\.0\\.0\\.1:(\\d{1,5})$").matchEntire(upstream) ?: return null
        val port = m.groupValues[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return Triple(port, m.groupValues[1], m.groupValues[2])
    }

    override fun startPrepared(profile: VlessProfile, settings: AppSettings, launch: SidecarLaunch, context: SidecarContext): RunningEngine? {
        val extras = ProfileExtras.read(profile)
        if (!extras.optBoolean(KEY_LANTERN_FALLBACK) || AetherSettings.read(extras).exitNode.isNotBlank()) return startInApp(launch, context)
        val lantern = try {
            LanternRelayHop.start(settings, context, checkCloudflare = false)
        } catch (e: Exception) {
            // Direct still works for what it reaches; say so instead of failing the whole pick.
            runCatching { com.example.xray.XrayLogManager.w("VPN",
                "Serverless + Lantern: Lantern did not start (${e.message?.take(120)}); sites that do not answer directly will fail.") }
            com.example.vpn.diagnostics.EngineStages.record(ID, "lantern-fallback", "Lantern did not start; direct only", error = true)
            return startInApp(launch, context)
        }
        try {
            val (port, user, pass) = parseUpstream(lantern.upstream) ?: error("Lantern upstream is malformed")
            val file = File(context.workDir, CONFIG_FILE)
            val root = withLanternFallback(JSONObject(file.readText()), port, user, pass)
            file.writeText(root.toString())
            file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true)
            com.example.vpn.diagnostics.EngineStages.record(ID, "lantern-fallback",
                "sites that fail or send nothing within ${NO_RESPONSE_WAIT_MS / 1000}s go through Lantern")
            val child = SidecarProcess.start(ID, launch, context.workDir, context.socksPort)
            return object : RunningEngine {
                override val isAlive get() = child.isAlive && lantern.running.isAlive
                override val startupFailure get() = child.startupFailure
                override fun awaitReady(timeoutMs: Long) = child.awaitReadyWhile(timeoutMs) { lantern.running.isAlive }
                override fun stop() { try { child.stop() } finally { lantern.running.stop() } }
            }
        } catch (e: Throwable) {
            lantern.running.stop()
            throw e
        }
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
        val file = File(context.workDir, CONFIG_FILE).apply {
            parentFile?.mkdirs(); writeText(config(source, context.socksPort, context.socksUser, context.socksPass, e.optString("family", "auto"), e.optString("resolver", "auto"), advanced, exit).toString())
            setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true)
        }
        return SidecarLaunch(listOf(context.executable.absolutePath, "run", "-c", file.absolutePath),
            readyTimeoutMs = 15_000, logOutput = false)
    }
}
