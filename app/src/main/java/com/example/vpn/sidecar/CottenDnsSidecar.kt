package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** Original MAXIMUS adapter for the pinned, separately built MIT CottenDNS CLI.
 * CottenMax and explicit native Cotten profiles remain distinct; neither auto-downgrades.
 * The upstream app's proprietary UI, credentials, binaries and assets are not used.
 */
class CottenDnsSidecar : SidecarEngine {
    override val id = ID
    override val binary = BINARY
    override fun handles(profile: VlessProfile) = ProfileExtras.read(profile).optString("engine") == ID
    override fun problem(profile: VlessProfile): String? = runCatching { settings(profile) }.exceptionOrNull()?.message

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        require(context.upstreamSocks == null) { "CottenDNS cannot dial through a carrier hop" }
        val s = Companion.settings(profile)
        context.workDir.mkdirs()
        val config = File(context.workDir, "cotten-client.toml")
        val resolvers = File(context.workDir, "cotten-resolvers.txt")
        // Do not put credentials in arguments or environment variables.
        for ((file, text) in listOf(config to configuration(s, context), resolvers to s.resolvers.joinToString("\n", postfix = "\n"))) {
            PrivateEngineFile.write(file, text)
        }
        return SidecarLaunch(
            command = listOf(context.executable.absolutePath, "-config", config.absolutePath, "-resolvers", resolvers.absolutePath),
            socksAuth = true, readyTimeoutMs = 45_000,
            // Engine startup progress is not application traffic proof: EngineProbe verifies egress.
            readyLine = Regex("WD_PROGRESS phase=connected percent=100(?:\\s|$)")
        )
    }

    data class Settings(val domain: String, val key: String, val resolvers: List<String>,
                        val transport: String = "auto", val backups: List<String> = emptyList(), val tlsName: String = "", val mode: String = "cottenmax")

    companion object {
        const val ID = "cotten-dns"
        const val BINARY = "cottendns"
        const val REVISION = "7db7b73658c791cce62300d29647e4d369d6e1e4"
        // MAXIMUS-specific round-trip format; WhiteDNS links use WhiteDnsProfileLinks.
        const val SCHEME = "maximus-cotten"
        internal fun domain(raw: String): String {
            val d = IDN.toASCII(raw.trim().trimEnd('.')).lowercase(Locale.ROOT)
            require(d.length in 3..253 && d.contains('.') && d.split('.').all {
                it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
            }) { "Invalid CottenDNS tunnel domain" }
            return d
        }
        internal fun resolver(raw: String): String {
            val s = raw.trim()
            val uri = runCatching { URI("tcp://" + if (s.count { it == ':' } > 1 && !s.startsWith('[')) "[$s]" else s) }
                .getOrElse { throw IllegalArgumentException("Invalid resolver address") }
            val host = requireNotNull(uri.host?.removePrefix("[")?.removeSuffix("]")) { "Use a resolver IP address" }
            require(uri.userInfo == null && uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null &&
                (host.matches(Regex("[0-9.]+")) || host.contains(':') && host.matches(Regex("[0-9a-fA-F:.]+")))) { "Use a literal resolver IP address" }
            if (!host.contains(':')) require(host.split('.').size == 4 && host.split('.').all { it.toIntOrNull() in 0..255 })
            val ip = runCatching { InetAddress.getByName(host) }.getOrElse { throw IllegalArgumentException("Invalid resolver IP address") }
            require(!ip.isAnyLocalAddress && !ip.isLoopbackAddress && !ip.isMulticastAddress && !ip.isLinkLocalAddress) { "Resolver address is not reachable as a DNS server" }
            val port = if (uri.port == -1) 53 else uri.port
            require(port in 1..65535) { "Invalid resolver port" }
            return if (host.contains(':')) "[$host]:$port" else "$host:$port"
        }
        fun validate(s: Settings): Settings {
            require(s.key.length in 16..512 && s.key.none { it.isISOControl() }) { "Paste the existing server key (16 to 512 characters)" }
            require(s.resolvers.size in 1..64) { "Choose one to 64 explicit DNS resolvers" }
            require(s.transport in setOf("auto", "udp", "tcp", "dot", "doh")) { "Unsupported DNS transport" }
            require(s.mode in setOf("cottenmax", "legacy")) { "Unsupported CottenDNS mode" }
            val d = domain(s.domain)
            val rs = s.resolvers.map(::resolver)
            require(rs.distinct().size == rs.size) { "Duplicate DNS resolver" }
            val backups = s.backups.map(::domain)
            require(backups.size <= 3 && (backups + d).distinct().size == backups.size + 1) { "Choose up to three different backup domains" }
            return s.copy(domain = d, resolvers = rs, backups = backups,
                tlsName = s.tlsName.takeIf { it.isNotBlank() }?.let(::domain).orEmpty())
        }
        fun profile(settings: Settings, name: String = "CottenDNS · Max beta"): VlessProfile {
            val s = validate(settings)
            return withSettings(VlessProfile(name = name, address = s.domain, port = 53, uuid = s.key,
                profileType = ProfileType.VLESS, protocolType = ProtocolType.MIXED), s)
        }
        fun withSettings(profile: VlessProfile, settings: Settings): VlessProfile {
            val s = validate(settings)
            val extras = JSONObject().put("engine", ID).put("mode", s.mode).put("domain", s.domain)
                .put("resolvers", JSONArray(s.resolvers)).put("transport", s.transport)
                .put("backups", JSONArray(s.backups)).put("tlsName", s.tlsName).toString()
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest((extras + "|" + s.key).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return profile.copy(address = s.domain, port = 53, uuid = s.key, extraSettings = extras, canonicalFingerprint = fingerprint,
                lastLatencyMs = null, lastTestedTimestamp = null, overallScore = 0.0, rawConfig = "",
                downloadMbps = 0.0, uploadMbps = 0.0, jitterMs = 0, packetLoss = 0.0, handshakeMs = 0,
                speedScore = 0.0, latencyScore = 0.0, stabilityScore = 0.0, reliabilityScore = 0.0)
        }
        fun settings(profile: VlessProfile): Settings {
            val o = ProfileExtras.read(profile)
            require(o.optString("engine") == ID && o.optString("mode") in setOf("cottenmax", "legacy")) { "Not a CottenDNS profile" }
            fun list(key: String): List<String> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            return validate(Settings(o.getString("domain"), profile.uuid, list("resolvers"), o.optString("transport", "auto"), list("backups"), o.optString("tlsName"), o.getString("mode")))
        }
        fun parse(link: String): VlessProfile {
            require(link.length <= 8192) { "CottenDNS link is too large" }
            val uri = runCatching { URI(link.trim()) }.getOrElse { throw IllegalArgumentException("Invalid CottenDNS URI") }
            require(uri.scheme.equals(SCHEME, true) && uri.userInfo == null && uri.port == -1 && uri.path.isNullOrEmpty()) { "Not a MAXIMUS CottenDNS link" }
            fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
            val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
            val single = pairs.filter { it.first !in setOf("resolver", "backup") }
            require(single.map { it.first }.distinct().size == single.size && pairs.all { it.first in setOf("key", "mode", "transport", "resolver", "backup", "tls") }) { "Duplicate or unknown CottenDNS option" }
            val p = single.toMap()
            require(p["mode"] in setOf("cottenmax", "legacy")) { "Explicit CottenDNS mode is required" }
            return profile(Settings(requireNotNull(uri.host) { "Missing tunnel domain" }, p["key"].orEmpty(), pairs.filter { it.first == "resolver" }.map { it.second },
                p["transport"] ?: "auto", pairs.filter { it.first == "backup" }.map { it.second }, p["tls"].orEmpty(), p.getValue("mode")), decode(uri.rawFragment ?: "CottenDNS · Max beta"))
        }
        fun toLink(profile: VlessProfile): String {
            val s = settings(profile)
            fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
            val pairs = listOf("mode" to s.mode, "key" to s.key, "transport" to s.transport) +
                s.resolvers.map { "resolver" to it } + s.backups.map { "backup" to it } + listOf("tls" to s.tlsName)
            return "$SCHEME://${s.domain}?" + pairs.joinToString("&") { (k,v) -> "$k=${enc(v)}" } + "#${enc(profile.name)}"
        }
        private fun quote(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun configuration(settings: Settings, context: SidecarContext): String {
            val s = validate(settings)
            require(context.socksPort in 1..65535 && context.socksUser.none { it.isISOControl() } && context.socksPass.none { it.isISOControl() })
            fun array(v: List<String>) = v.joinToString(",", "[", "]", transform = ::quote)
            return """
                TUNNEL_MODE = ${quote(s.mode)}
                CONFIG_PRESET = "survival"
                DOMAINS = ${array(listOf(s.domain))}
                STANDBY_DOMAINS = ${array(s.backups)}
                ENCRYPTION_KEY = ${quote(s.key)}
                DATA_ENCRYPTION_METHOD = 5
                RESOLVER_TRANSPORT = ${quote(s.transport)}
                RESOLVER_TLS_SERVER_NAME = ${quote(s.tlsName)}
                RESOLVER_TLS_INSECURE_SKIP_VERIFY = false
                RESOLVER_HTTP3_FALLBACK = false
                RESOLVER_DISCOVERY_ENABLED = false
                RESOLVER_DISCOVERY_SAMPLE_RANGES = 0
                QUERY_RATE_LIMIT_PER_SECOND = ${if (s.mode == "legacy") "4.0" else "0.0"}
                QUERY_RATE_LIMIT_BURST = ${if (s.mode == "legacy") "1" else "0"}
                PROTOCOL_TYPE = "SOCKS5"
                LISTEN_IP = "127.0.0.1"
                LISTEN_PORT = ${context.socksPort}
                SOCKS5_AUTH = true
                SOCKS5_USER = ${quote(context.socksUser)}
                SOCKS5_PASS = ${quote(context.socksPass)}
                LOCAL_DNS_ENABLED = false
                LOCAL_DNS_CACHE_PERSIST_TO_FILE = false
                RESOLVER_DISCOVERY_CACHE_FILE = ""
                STARTUP_MODE = "resolvers"
                LOG_BASED_MTU_VERIFY = true
                TERMINAL_UI = "plain"
                LOG_LEVEL = "INFO"
                LOG_TO_FILE = false
                STATS_REPORT_INTERVAL_SECONDS = 0.0
                TX_CHANNEL_SIZE = 256
                RX_CHANNEL_SIZE = 256
            """.trimIndent() + "\n"
        }
    }
}


