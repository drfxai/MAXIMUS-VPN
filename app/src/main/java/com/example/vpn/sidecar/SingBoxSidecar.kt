package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A separately distributed sing-box process; Xray retains TUN, DNS and traffic protection. */
object SingBoxSidecar : SidecarEngine {
    override val id = "sing-box"
    override val binary = "singbox"
    const val VERSION = "1.14.4"

    override fun handles(profile: VlessProfile): Boolean = profile.profileType != ProfileType.XRAY_JSON &&
        (profile.engineType == EngineType.SING_BOX || ProfileExtras.read(profile).optString("engine") == id)

    /** Refuse lossy conversions. In particular a leaf certificate pin is NOT an SPKI pin. */
    fun conversionProblem(p: VlessProfile): String? = when {
        p.profileType != ProfileType.VLESS || p.rawConfig.isNotBlank() -> "Raw engine configurations cannot be converted to sing-box."
        p.protocolType !in setOf(ProtocolType.VLESS, ProtocolType.VMESS, ProtocolType.TROJAN, ProtocolType.SHADOWSOCKS) -> "This protocol has no normalized sing-box adapter."
        p.address.isBlank() || p.port !in 1..65535 || p.uuid.isBlank() -> "Missing sing-box server, port or credentials."
        p.transport.lowercase() !in setOf("tcp", "ws", "grpc", "httpupgrade") -> "This transport must remain on its original engine."
        p.security.lowercase() !in setOf("", "none", "tls", "reality") -> "Unsupported sing-box security mode."
        p.allowInsecure || p.pinnedPeerCertSha256.isNotBlank() || p.verifyPeerCertByName.isNotBlank() -> "Certificate pin semantics require the original Xray engine."
        p.finalMask.isNotBlank() || p.echSockopt.isNotBlank() || p.echConfigList.isNotBlank() || p.fakeSniPool.isNotBlank() -> "Xray masking and ECH settings cannot be discarded during engine selection."
        p.fingerprint.equals("unsafe", true) -> "The unsafe fingerprint is not supported by this sing-box adapter."
        p.headerType.isNotBlank() && !p.headerType.equals("none", true) -> "TCP header settings require Xray."
        ProfileExtras.read(p).let { it.has(ProfileExtras.MLDSA65_VERIFY) || it.has("engine") && it.optString("engine") != id } -> "Engine-specific settings require their original engine."
        ProfileExtras.read(p).keys().asSequence().any { it !in setOf("engine", "alterId", "coreLocked", "subscriptionPresetCore", "subscriptionAutoExcluded") } -> "Unrecognized protocol extras require the original engine."
        p.security.equals("reality", true) && (p.publicKey.isBlank() || p.sni.isBlank()) -> "REALITY requires a public key and SNI."
        p.flow.isNotBlank() && (p.protocolType != ProtocolType.VLESS || p.flow != "xtls-rprx-vision") -> "Unsupported flow."
        p.protocolType == ProtocolType.VLESS && !p.encryption.let { it.isBlank() || it == "none" } -> "VLESS encryption requires Xray."
        else -> null
    }

    override fun problem(profile: VlessProfile): String? = conversionProblem(profile)

    fun select(profile: VlessProfile): VlessProfile {
        require(conversionProblem(profile) == null) { conversionProblem(profile).orEmpty() }
        return ProfileExtras.with(profile.copy(engineType = EngineType.SING_BOX, canonicalFingerprint = ""), "engine", id)
    }

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        problem(profile)?.let { error(it) }
        val file = File(context.workDir, "sing-box.json")
        PrivateEngineFile.write(file, config(profile, context).toString())
        return SidecarLaunch(listOf(context.executable.absolutePath, "run", "-D", context.workDir.absolutePath,
            "-c", file.absolutePath), readyTimeoutMs = 15_000L)
    }

    fun config(p: VlessProfile, c: SidecarContext): JSONObject {
        require(conversionProblem(p) == null) { conversionProblem(p).orEmpty() }
        require((c.upstreamUser == null) == (c.upstreamPass == null)) { "Incomplete carrier credentials" }
        val type = when (p.protocolType) {
            ProtocolType.VLESS -> "vless"
            ProtocolType.VMESS -> "vmess"
            ProtocolType.TROJAN -> "trojan"
            ProtocolType.SHADOWSOCKS -> "shadowsocks"
            else -> error("Unsupported sing-box protocol")
        }
        val outbound = JSONObject().put("type", type).put("tag", "node")
            .put("server", p.address.removePrefix("[").removeSuffix("]")).put("server_port", p.port)
            .put("domain_resolver", "bootstrap")
        when (p.protocolType) {
            ProtocolType.VLESS -> { outbound.put("uuid", p.uuid); if (p.flow.isNotBlank()) outbound.put("flow", p.flow) }
            ProtocolType.VMESS -> {
                outbound.put("uuid", p.uuid).put("security", p.encryption.ifBlank { "auto" })
                outbound.put("alter_id", ProfileExtras.read(p).optInt("alterId", 0))
            }
            ProtocolType.SHADOWSOCKS -> outbound.put("method", p.encryption).put("password", p.uuid)
            else -> outbound.put("password", p.uuid)
        }
        if (p.security.equals("tls", true) || p.security.equals("reality", true)) {
            val tls = JSONObject().put("enabled", true).put("insecure", false)
                .put("server_name", p.sni.ifBlank { p.host.ifBlank { p.address } })
            if (p.alpn.isNotBlank()) tls.put("alpn", JSONArray(p.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            if (p.cipherSuites.isNotBlank()) tls.put("cipher_suites", JSONArray(p.cipherSuites.split(',', ':').map { it.trim() }.filter { it.isNotEmpty() }))
            if (p.fingerprint.isNotBlank() || p.security.equals("reality", true))
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", p.fingerprint.ifBlank { "chrome" }))
            if (p.security.equals("reality", true)) tls.put("reality", JSONObject().put("enabled", true)
                .put("public_key", p.publicKey).put("short_id", p.shortId))
            outbound.put("tls", tls)
        }
        if (!p.transport.equals("tcp", true)) {
            val transport = JSONObject().put("type", p.transport.lowercase())
            if (p.transport.equals("grpc", true)) transport.put("service_name", p.serviceName)
            else {
                transport.put("path", p.path.ifBlank { "/" })
                if (p.host.isNotBlank()) transport.put("headers", JSONObject().put("Host", p.host))
            }
            outbound.put("transport", transport)
        }
        val outbounds = JSONArray().put(outbound)
        if (c.upstreamSocks != null) {
            outbound.put("detour", "carrier")
            val carrier = JSONObject().put("type", "socks").put("tag", "carrier")
                .put("server", "127.0.0.1").put("server_port", c.upstreamSocks).put("version", "5")
            c.upstreamUser?.let { carrier.put("username", it).put("password", c.upstreamPass) }
            outbounds.put(carrier)
        }
        return JSONObject().put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(JSONObject().put("type", "socks").put("tag", "local")
                .put("listen", "127.0.0.1").put("listen_port", c.socksPort)
                .put("users", JSONArray().put(JSONObject().put("username", c.socksUser).put("password", c.socksPass)))))
            .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "https")
                .put("tag", "bootstrap").put("server", "1.1.1.1").put("path", "/dns-query"))))
            .put("outbounds", outbounds).put("route", JSONObject().put("final", "node"))
    }
}
