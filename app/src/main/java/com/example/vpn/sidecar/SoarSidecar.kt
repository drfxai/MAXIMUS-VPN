package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Independent Soar client process. Xray remains the only Android TUN owner. */
class SoarSidecar : SidecarEngine {
    override val id = ID
    override val binary = "soar"
    override fun handles(profile: VlessProfile) = ProfileExtras.read(profile).optString("engine") == ID
    override fun problem(profile: VlessProfile): String? = runCatching { config(profile) }.exceptionOrNull()?.let { "Invalid SOAR profile settings" }
    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        require(context.upstreamSocks == null) { "SOAR cannot skip a requested carrier hop" }
        val o = config(profile)
        require(context.socksPort in 1..65535)
        o.put("listen", "127.0.0.1:${context.socksPort}").put("username", context.socksUser).put("password", context.socksPass)
        context.workDir.mkdirs()
        val file = File(context.workDir, "soar.json")
        check(file.createNewFile()) { "SOAR work file already exists" }
        check(file.setReadable(false, false) && file.setWritable(false, false) && file.setReadable(true, true) && file.setWritable(true, true))
        file.writeText(o.toString())
        // Local listening is readiness only; EngineProbe and VpnService still verify real traffic.
        return SidecarLaunch(listOf(context.executable.absolutePath, "-config", file.absolutePath), socksAuth = true)
    }

    companion object {
        const val ID = "soar"
        const val REVISION = "a8af533d82284f5e6ab13b706552596c108bacf2"
        fun profile(domain: String, key: String, resolvers: List<String>, tuning: JSONObject, name: String): VlessProfile {
            val o = normalized(domain, key, resolvers, tuning)
            return withConfig(VlessProfile(name = name, address = o.getString("domain"), port = 53, uuid = key,
                profileType = ProfileType.VLESS, protocolType = ProtocolType.MIXED), o)
        }
        fun config(p: VlessProfile): JSONObject {
            val o = ProfileExtras.read(p)
            require(o.getString("engine") == ID)
            val rs = o.getJSONArray("resolvers")
            return normalized(o.getString("domain"), p.uuid, (0 until rs.length()).map { rs.getString(it) }, o.getJSONObject("tuning"))
        }
        fun withResolvers(p: VlessProfile, text: String): VlessProfile {
            val o = config(p)
            return withConfig(p, normalized(o.getString("domain"), p.uuid,
                text.lines().map { it.trim() }.filter { it.isNotEmpty() }, o.getJSONObject("tuning")))
        }
        private fun withConfig(p: VlessProfile, config: JSONObject): VlessProfile {
            val extras = JSONObject(config.toString()).apply { remove("publicKey"); put("engine", ID) }.toString()
            val fp = java.security.MessageDigest.getInstance("SHA-256").digest((extras + "|" + p.uuid).toByteArray())
                .joinToString("") { "%02x".format(it) }
            return p.copy(extraSettings = extras, canonicalFingerprint = fp, rawConfig = "", lastLatencyMs = null,
                lastTestedTimestamp = null, overallScore = 0.0, downloadMbps = 0.0, uploadMbps = 0.0,
                jitterMs = 0, packetLoss = 0.0, handshakeMs = 0, speedScore = 0.0, latencyScore = 0.0,
                stabilityScore = 0.0, reliabilityScore = 0.0)
        }
        private fun normalized(domain: String, key: String, resolvers: List<String>, input: JSONObject): JSONObject {
            require(Base64.getDecoder().decode(key).size == 32) { "SOAR requires a 32-byte Ed25519 public key" }
            require(resolvers.size in 1..64) { "Choose one to 64 explicit DNS resolvers" }
            val rs = resolvers.map { CottenDnsSidecar.resolver(it) }
            require(rs.distinct().size == rs.size) { "Duplicate resolver" }
            val tuning = JSONObject()
            val transport = input.optString("transport", "auto")
            require(transport in setOf("auto", "on", "only", "off")) { "Invalid SOAR transport" }
            tuning.put("transport", transport)
            fun number(key: String, default: Int, low: Int, high: Int) {
                val n = if (input.has(key)) input.getInt(key) else default
                require(n in low..high) { "Invalid SOAR $key" }
                tuning.put(key, n)
            }
            number("safeQName", 101, 101, 253)
            number("maxQName", 253, tuning.getInt("safeQName"), 253)
            number("maxAnswer", 1232, 512, 1232)
            number("holdMs", 500, 100, 1500)
            number("maxInflight", 128, 8, 1024)
            number("maxPathWindow", 32, 2, 128)
            number("maxStreams", 128, 8, 128)
            number("streamWindowKiB", 256, 64, 1024)
            number("sendBufferKiB", 64, 32, 1024)
            number("sessionIdleSeconds", 90, 15, 110)
            number("dialTimeoutSeconds", 90, 5, 120)
            // Manual resolver policy: never add built-in/range-discovered endpoints from a link.
            return JSONObject().put("domain", CottenDnsSidecar.domain(domain)).put("publicKey", key)
                .put("resolvers", JSONArray(rs)).put("tuning", tuning)
        }
    }
}
