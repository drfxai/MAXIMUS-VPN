package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.experimental.FrontedTunnel
import com.example.xray.XrayConfigBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

/** Native retransmission of original bytes after a TTL-limited decoy; the TLS identity is unchanged. */
object ByedpiSidecar : SidecarEngine {
    const val ID = "byedpi"
    override val id = ID
    override val binary = "byedpi"
    override fun handles(profile: VlessProfile) = ProfileExtras.read(profile).optString("engine") == ID
    override fun problem(profile: VlessProfile): String? = runCatching { validate(profile) }.exceptionOrNull()?.message

    private fun validate(p: VlessProfile) {
        require(p.profileType != ProfileType.XRAY_JSON && p.rawConfig.isBlank()) { "Choose a structured TLS profile" }
        require(p.protocolType in setOf(ProtocolType.VLESS, ProtocolType.VMESS, ProtocolType.TROJAN))
        require(p.security == "tls" && !p.allowInsecure && p.sni.isNotBlank()) { "Verified TLS is required" }
        require(p.transport.lowercase() in setOf("tcp", "raw", "ws", "httpupgrade", "grpc", "h2", "xhttp", "splithttp"))
        require(!FrontedTunnel.publicAddress(p.address).contains(':')) { "Choose a public IPv4 endpoint" }
        require(p.port in 1..65535 && p.finalMask.isBlank()) { "Use a valid port and disable other fragmentation for this test" }
        val e = ProfileExtras.read(p)
        require(e.optInt("fakeTtl") in 1..32) { "Fake packet TTL must be 1–32" }
        domain(e.getString("fakeSni"))
    }

    private fun domain(s: String): String = IDN.toASCII(s.trim()).lowercase().also {
        require(it.length in 3..253 && it.contains('.') && it.split('.').all { label ->
            label.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
        }) { "Invalid decoy SNI" }
    }

    fun enable(p: VlessProfile, decoy: String, ttl: Int): VlessProfile {
        val extra = ProfileExtras.read(p)
        require(extra.optString("engine").isBlank()) { "An engine is already selected" }
        extra.put("engine", ID).put("fakeSni", domain(decoy)).put("fakeTtl", ttl)
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${p.effectiveFingerprint}|$ID|${domain(decoy)}|$ttl".toByteArray()).joinToString("") { "%02x".format(it) }
        return p.copy(canonicalFingerprint = fingerprint, extraSettings = extra.toString(), lastLatencyMs = null, lastTestedTimestamp = null).also(::validate)
    }

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        validate(profile)
        require(context.upstreamSocks == null) { "Fake SNI cannot be nested in another carrier" }
        val e = ProfileExtras.read(profile)
        return SidecarLaunch(listOf(context.executable.absolutePath, "--ip", "127.0.0.1", "--port", context.socksPort.toString(),
            "--conn-ip", "0.0.0.0", "--no-domain", "--no-udp", "--max-conn", "64", "--timeout", "4",
            "--fake", "-1", "--ttl", e.getInt("fakeTtl").toString(), "--fake-sni", domain(e.getString("fakeSni"))),
            environment = mapOf("MAXIMUS_PEER_IP" to profile.address, "MAXIMUS_PEER_PORT" to profile.port.toString()), socksAuth = false)
    }

    fun healthEndpoint(profile: VlessProfile): com.example.vpn.verify.Socks5.Endpoint? {
        if (!handles(profile) || profile.profileType != ProfileType.XRAY_JSON) return null
        val e = ProfileExtras.read(profile)
        val port = e.optInt("healthPort")
        val user = e.optString("healthUser"); val pass = e.optString("healthPass")
        require(port in 1024..65535 && user.length >= 8 && pass.length >= 16)
        return com.example.vpn.verify.Socks5.Endpoint("127.0.0.1", port, user, pass)
    }

    fun addHealthInbound(config: JSONObject, profile: VlessProfile): JSONObject = config.apply {
        val e = healthEndpoint(profile) ?: return@apply
        val inbound = JSONObject().put("tag", "native-health").put("listen", e.host).put("port", e.port)
            .put("protocol", "socks").put("settings", JSONObject().put("auth", "password").put("udp", false)
                .put("accounts", JSONArray().put(JSONObject().put("user", e.user).put("pass", e.pass))))
        val inbounds = optJSONArray("inbounds") ?: JSONArray()
        put("inbounds", inbounds.put(inbound))
    }

    /** Xray still performs encryption/authentication; only its TCP dial goes through ByeDPI. */
    fun wrap(original: VlessProfile, context: SidecarContext): VlessProfile {
        validate(original)
        val extra = ProfileExtras.read(original).apply { remove("engine"); remove("fakeSni"); remove("fakeTtl") }
        val plain = original.copy(extraSettings = extra.toString())
        val out = JSONObject(XrayConfigBuilder.buildJson(plain, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
        val stream = out.getJSONObject("streamSettings")
        val socket = stream.optJSONObject("sockopt") ?: JSONObject()
        stream.put("sockopt", socket.put("dialerProxy", "fake-sni-dialer"))
        val dialer = JSONObject().put("tag", "fake-sni-dialer").put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", context.socksPort))))
        val runtime = ProfileExtras.read(original).put("healthPort", Sidecars.freeLoopbackPort())
            .put("healthUser", context.socksUser).put("healthPass", context.socksPass)
        return original.copy(profileType = ProfileType.XRAY_JSON, extraSettings = runtime.toString(),
            rawConfig = JSONObject().put("outbounds", JSONArray().put(out).put(dialer)).toString())
    }
}
