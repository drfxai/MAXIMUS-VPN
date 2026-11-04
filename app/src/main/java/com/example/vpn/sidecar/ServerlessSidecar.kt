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
    val variants = listOf("auto", "fragA", "fragB", "oob")
    override fun handles(profile: VlessProfile) = ProfileExtras.read(profile).optString("engine") == ID
    override fun problem(profile: VlessProfile): String? = when {
        ProfileExtras.read(profile).optString("variant", "auto") !in variants -> "Unsupported serverless profile"
        ProfileExtras.read(profile).optString("family", "auto") !in AetherSidecar.families -> "Unsupported IP family"
        else -> null
    }
    fun profile(variant: String = "auto", family: String = "auto", vpnFallback: Boolean = false) : VlessProfile {
        require(variant in variants && family in AetherSidecar.families)
        return VlessProfile(id = "maximus-serverless", name = "Direct Serverless", address = ID, port = 0, uuid = "",
            protocolType = ProtocolType.MIXED, extraSettings = JSONObject().put("engine", ID)
                .put("variant", variant).put("family", family).put("vpnFallback", vpnFallback).toString())
    }
    /** Preserve finalmask/OOB exactly; replace host-app inlet and geo-dependent classifications. */
    fun config(source: String, port: Int, user: String, password: String, family: String): JSONObject {
        require(port in 1..65535 && user.isNotBlank() && password.isNotBlank())
        require(family in AetherSidecar.families)
        val root = JSONObject(source)
        root.remove("__Credits__"); root.remove("remarks"); root.remove("version")
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        inbound.put("listen", "127.0.0.1").put("port", port)
        inbound.getJSONObject("settings").put("auth", "password")
            .put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", password)))
        // Geo categories require external mutable files and do not define the anti-DPI method.
        // Explicit domains/IP blocks remain; DNS is always DoH through the fragmented path.
        val dns = root.getJSONObject("dns")
        dns.remove("hosts")
        dns.put("servers", JSONArray().put(JSONObject().put("tag", "no-filter-dns")
            .put("address", "https+one://1.1.1.1/dns-query").put("finalQuery", true).put("timeoutMs", 12000)))
        dns.put("queryStrategy", when(family) { "ipv4" -> "UseIPv4"; "ipv6" -> "UseIPv6"; else -> "UseIP" })
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        val kept = JSONArray()
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
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
        for (i in 0 until out.length()) out.getJSONObject(i).optJSONObject("streamSettings")?.optJSONObject("sockopt")?.let { sock ->
            if (family != "auto") { sock.remove("happyEyeballs"); sock.put("domainStrategy", if (family == "ipv4") "ForceIPv4" else "ForceIPv6") }
        }
        return root
    }
    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        require(context.upstreamSocks == null) { "Direct mode cannot silently skip a requested hop" }
        problem(profile)?.let { error(it) }
        val e = ProfileExtras.read(profile)
        val variant = e.optString("variant", "auto").let { if (it == "auto") "fragA" else it }
        val source = Sidecars.openAsset?.invoke("serverless/Serverless-$variant.jsonc")?.bufferedReader()?.use { it.readText() }
            ?: error("Pinned serverless configuration is missing")
        val file = File(context.workDir, "serverless.json").apply {
            parentFile?.mkdirs(); writeText(config(source, context.socksPort, context.socksUser, context.socksPass, e.optString("family", "auto")).toString())
            setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true)
        }
        return SidecarLaunch(listOf(context.executable.absolutePath, "run", "-c", file.absolutePath),
            readyTimeoutMs = 15_000, logOutput = false)
    }
}
