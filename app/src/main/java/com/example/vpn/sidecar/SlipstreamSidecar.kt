package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.safety.DnsResolvers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * A DNS tunnel that carries QUIC: slipstream-client (EndPositive/slipstream, Apache-2.0). Like the
 * [DnsttSidecar] it carries one local TCP port to the user's own server inside DNS queries sent through
 * ordinary resolvers, so it keeps working when the server's IP is blocked but DNS still resolves. Unlike
 * dnstt it runs QUIC with multipath congestion control inside the tunnel, so it can combine several
 * resolvers and recovers on its own when one path degrades.
 *
 * The server end (`slipstream-server`, set up from the Panels Install Center) forwards the tunnel to a
 * SOCKS5 helper with no login, so for this app the client's local port is a SOCKS5 port, exactly as for
 * dnstt. Xray still owns the TUN, DNS and kill switch; its only proxy becomes that loopback port.
 *
 * slipstream-client at the pinned build (see scripts/engines/slipstream.sh and its patch):
 * `slipstream-client --tcp-listen-port N --resolver IP[:PORT] [--resolver ...] --domain DOMAIN
 *   [--congestion-control bbr|dcubic] [--cert PEM] [--tcp-listen-host 127.0.0.1] [--keep-alive-interval MS]`
 * - `--resolver` may be repeated; the client spreads the QUIC connection over all of them (multipath) and
 *   keeps going when one stops answering. Each is a plain-UDP DNS resolver address; a bare IP uses port 53.
 * - `--cert` is this app's patch: it pins the server's certificate, so a resolver on the path cannot read
 *   or change the tunnel. Without it the tunnel still works but is unauthenticated, so the app always
 *   passes one when the profile carries it and marks a profile without one as unverified.
 * - The patched client listens on 127.0.0.1 only and exits non-zero if the tunnel never comes up.
 *
 * Link format chosen for this app (resolvers repeated, in order of preference):
 * `slipstream://<domain>?udp=<ip[:port]>[&udp=...][&cc=bbr|dcubic][&cert=<base64url PEM>]#<name>`
 */
class SlipstreamSidecar : SidecarEngine {
    override val id: String = ID
    override val binary: String = BINARY

    override fun handles(profile: VlessProfile): Boolean =
        ProfileExtras.read(profile).optString(KEY_ENGINE) == ENGINE_MARKER

    override fun problem(profile: VlessProfile): String? =
        runCatching { settings(profile) }.exceptionOrNull()?.message

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        require(context.upstreamSocks == null) { "Slipstream cannot skip a requested carrier hop" }
        val s = settings(profile)
        val certFile = s.certPem.takeIf { it.isNotBlank() }?.let {
            File(context.workDir, CERT_FILE).apply {
                parentFile?.mkdirs()
                writeText(it)
                setReadable(false, false)
                setReadable(true, true)
            }
        }
        return SidecarLaunch(
            command = command(s, context.executable.absolutePath, context.socksPort, certFile?.absolutePath),
            // The port opens as the QUIC handshake begins; this covers a slow program start.
            readyTimeoutMs = READY_TIMEOUT_MS,
            // The server's SOCKS helper decides whether it checks a login; the app cannot know, so none is sent.
            socksAuth = false
        )
    }

    data class Settings(
        val domain: String,
        /** At least one, at most [MAX_RESOLVERS], in order of preference. Each is `ip` or `ip:port`. */
        val resolvers: List<String>,
        val congestionControl: String = DEFAULT_CC,
        /** The server certificate in PEM, pinned so the tunnel is authenticated; empty when not set. */
        val certPem: String = ""
    ) {
        val resolver: String get() = resolvers.first()
        val verified: Boolean get() = certPem.isNotBlank()
    }

    companion object {
        /** Matches the EngineRegistry id. */
        const val ID = "slipstream"
        const val BINARY = "slipstream"
        const val SCHEME = "slipstream"
        const val ENGINE_MARKER = "slipstream"
        const val KEY_ENGINE = "engine"
        const val KEY_DOMAIN = "domain"
        const val KEY_RESOLVERS = "resolvers"
        const val KEY_CC = "cc"
        const val KEY_CERT = "certPem"
        const val CERT_FILE = "slipstream-server.pem"
        const val MAX_RESOLVERS = 6
        const val DEFAULT_CC = "dcubic"
        const val READY_TIMEOUT_MS = 20_000L
        /** A sensible default public resolver for a new profile; the user can change it. */
        const val DEFAULT_RESOLVER = "1.1.1.1"
        private val CC = setOf("dcubic", "bbr")

        private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

        /** A new Slipstream profile; throws [IllegalArgumentException] with a user-facing message. */
        fun profile(
            domain: String,
            resolvers: List<String> = listOf(DEFAULT_RESOLVER),
            name: String = "",
            congestionControl: String = DEFAULT_CC,
            certPem: String = ""
        ): VlessProfile {
            val s = validate(domain, resolvers, congestionControl, certPem)
            return withSettings(VlessProfile(name = "", address = s.domain, port = 53, uuid = ""), s).copy(
                name = name.trim().ifEmpty { "Slipstream ${s.domain}" },
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.MIXED
            )
        }

        /** [profile] with its Slipstream settings replaced by [s] (already validated). */
        fun withSettings(profile: VlessProfile, s: Settings): VlessProfile {
            val extras = JSONObject(ProfileExtras.read(profile).toString())
            for (k in listOf(KEY_RESOLVERS, KEY_CC, KEY_CERT)) extras.remove(k)
            extras
                .put(KEY_ENGINE, ENGINE_MARKER)
                .put(KEY_DOMAIN, s.domain)
                .put(KEY_RESOLVERS, JSONArray(s.resolvers))
                .put(KEY_CC, s.congestionControl)
            if (s.certPem.isNotEmpty()) extras.put(KEY_CERT, s.certPem)
            return profile.copy(address = s.domain, port = 53, extraSettings = extras.toString())
        }

        /**
         * [fresh] (from a server install) with the phone-side choices of [old] kept: its resolvers and
         * congestion control. The domain and certificate come from [fresh]. When [old] is not a usable
         * Slipstream profile, [fresh] is returned as it is.
         */
        fun carryOver(old: VlessProfile, fresh: VlessProfile): VlessProfile {
            val before = runCatching { settings(old) }.getOrNull() ?: return fresh
            val now = settings(fresh)
            return withSettings(fresh, now.copy(resolvers = before.resolvers, congestionControl = before.congestionControl))
        }

        /** Parses a `slipstream://` link; throws [IllegalArgumentException] with a user-facing message. */
        fun parse(link: String): VlessProfile {
            val trimmed = link.trim()
            require(trimmed.startsWith("$SCHEME://", ignoreCase = true)) { "Not a slipstream:// link." }
            val rest = trimmed.substring(SCHEME.length + 3)
            val name = decode(rest.substringAfter('#', ""))
            val beforeFragment = rest.substringBefore('#')
            val domain = decode(beforeFragment.substringBefore('?').removeSuffix("/"))
            val query = beforeFragment.substringAfter('?', "")
            val pairs = query.split('&').filter { it.isNotEmpty() }.map {
                decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', ""))
            }
            val params = pairs.toMap()
            val resolvers = pairs.filter { it.first == "udp" && it.second.isNotBlank() }.map { it.second }
                .ifEmpty { listOf(DEFAULT_RESOLVER) }
            val cert = params["cert"].orEmpty().takeIf { it.isNotBlank() }?.let { decodeCert(it) }.orEmpty()
            return profile(domain, resolvers, name, params["cc"].orEmpty().ifBlank { DEFAULT_CC }, cert)
        }

        fun parseOrNull(link: String): VlessProfile? = runCatching { parse(link) }.getOrNull()

        /** The share link for a Slipstream profile. The certificate, when set, rides along base64url-encoded. */
        fun toLink(profile: VlessProfile): String {
            val s = settings(profile)
            val query = StringBuilder(s.resolvers.joinToString("&") { "udp=${encode(it)}" })
            query.append("&cc=").append(s.congestionControl)
            if (s.certPem.isNotEmpty()) query.append("&cert=").append(encodeCert(s.certPem))
            return "$SCHEME://${encode(s.domain)}?$query#${encode(profile.name)}"
        }

        /** The validated settings stored in [profile]; throws with a user-facing message. */
        fun settings(profile: VlessProfile): Settings {
            val extras = ProfileExtras.read(profile)
            val list = extras.optJSONArray(KEY_RESOLVERS)
            val resolvers = if (list == null) emptyList()
                else (0 until list.length()).map { list.optString(it) }.filter { it.isNotEmpty() }
            return validate(
                extras.optString(KEY_DOMAIN), resolvers,
                extras.optString(KEY_CC).ifBlank { DEFAULT_CC }, extras.optString(KEY_CERT)
            )
        }

        fun command(s: Settings, executable: String, port: Int, certPath: String?): List<String> {
            val args = mutableListOf(executable)
            args += listOf("--tcp-listen-host", "127.0.0.1")
            args += listOf("--tcp-listen-port", port.toString())
            for (r in s.resolvers) args += listOf("--resolver", r)
            args += listOf("--domain", s.domain)
            args += listOf("--congestion-control", s.congestionControl)
            if (!certPath.isNullOrBlank()) args += listOf("--cert", certPath)
            return args
        }

        fun validate(domain: String, resolvers: List<String>, congestionControl: String, certPem: String): Settings {
            val zone = domain.trim().trimEnd('.').lowercase()
            require(validDomain(zone)) { "The Slipstream domain is not a valid domain name." }
            require(resolvers.isNotEmpty()) { "Add at least one DNS resolver." }
            require(resolvers.size <= MAX_RESOLVERS) { "Slipstream may use up to $MAX_RESOLVERS resolvers." }
            val cleaned = resolvers.map { resolverAddress(it) }
            require(cleaned.distinct().size == cleaned.size) { "The same resolver is listed twice." }
            val cc = congestionControl.trim().lowercase().ifEmpty { DEFAULT_CC }
            require(cc in CC) { "Congestion control must be bbr or dcubic." }
            val cert = certPem.trim()
            require(cert.isEmpty() || cert.contains("-----BEGIN CERTIFICATE-----")) {
                "The pinned certificate must be PEM text (-----BEGIN CERTIFICATE-----)."
            }
            return Settings(zone, cleaned, cc, cert)
        }

        /** A resolver as slipstream-client takes it: an IP address, optionally with `:port`. Host names are refused. */
        fun resolverAddress(resolver: String): String {
            val value = resolver.trim().removePrefix("udp://")
            val (host, port) = splitHostPort(value)
            require(host.isNotEmpty() && DnsResolvers.isUsable(host)) {
                "A Slipstream resolver must be an IP address (for example 1.1.1.1), not a host name."
            }
            require(port == null || port in 1..65535) { "The resolver port is not valid." }
            val shown = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return if (port == null) shown else "$shown:$port"
        }

        /** Resolvers typed one per line; `udp://` is accepted too. Not yet validated. */
        fun parseResolverLines(text: String): List<String> =
            text.lines().map { it.trim().removePrefix("udp://") }.filter { it.isNotEmpty() }

        private fun splitHostPort(value: String): Pair<String, Int?> {
            if (value.startsWith("[")) {
                val host = value.substring(1).substringBefore(']')
                val port = value.substringAfter("]", "").removePrefix(":")
                return host to (if (port.isEmpty()) null else port.toIntOrNull() ?: -1)
            }
            return when (value.count { it == ':' }) {
                0 -> value to null
                1 -> value.substringBefore(':') to (value.substringAfter(':').toIntOrNull() ?: -1)
                else -> value to null // a bare IPv6 address
            }
        }

        private fun validDomain(domain: String): Boolean {
            if (domain.isEmpty() || domain.length > 253) return false
            val labels = domain.split('.')
            return labels.size >= 2 && labels.all { LABEL.matches(it) }
        }

        private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** The certificate PEM as it rides in a link: base64url of the raw PEM, so no inner encoding is needed. */
        private fun encodeCert(pem: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(pem.toByteArray(Charsets.UTF_8))

        private fun decodeCert(value: String): String = runCatching {
            String(Base64.getUrlDecoder().decode(value.trim()), Charsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("The pinned certificate in the link is not valid.") }
    }
}

