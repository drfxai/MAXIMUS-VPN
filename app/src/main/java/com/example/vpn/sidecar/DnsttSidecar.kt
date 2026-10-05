package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.safety.DnsResolvers
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A DNS tunnel through dnstt-client (public domain). The client carries one local TCP port to the
 * server inside DNS queries sent through a public resolver (DoH, DoT or plain UDP DNS); the server
 * end forwards it to whatever its operator runs there. For this app that has to be a SOCKS5 server
 * without a login, since Xray's only proxy becomes the local port.
 *
 * dnstt-client usage at the pinned version:
 * `dnstt-client [-doh URL | -dot HOST:PORT | -udp HOST:PORT] [-pubkey HEX | -pubkey-file FILE]
 *  [-utls SPEC] DOMAIN LOCALADDR`. Exactly one resolver option; the key is the server's 32-byte Noise
 * public key as 64 hex digits; -utls picks the TLS fingerprint for DoH/DoT (a weighted random choice
 * by default, "none" for Go's own).
 *
 * It listens on LOCALADDR before it has talked to the server (the Noise handshake happens after), so
 * the port check passes as soon as the program starts. If the session fails it exits.
 *
 * The resolver must be reachable without a DNS lookup of its own (see [DnsResolvers]): a DoH URL
 * whose host is an IP address (well-known DoH names are swapped for their addresses), or an IP
 * address for DoT and UDP.
 *
 * Link format, chosen for this app:
 * `dnstt://<server public key hex>@<tunnel domain>?doh=<url>|dot=<ip[:port]>|udp=<ip[:port]>[&utls=<spec>]#<name>`
 */
class DnsttSidecar : SidecarEngine {
    override val id: String = ID
    override val binary: String = BINARY

    override fun handles(profile: VlessProfile): Boolean =
        ProfileExtras.read(profile).optString(KEY_ENGINE) == ENGINE_MARKER

    override fun problem(profile: VlessProfile): String? = runCatching { settings(profile) }.exceptionOrNull()?.message

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        val s = settings(profile)
        return SidecarLaunch(
            command = command(s, context.executable.absolutePath, context.socksPort),
            // Listening happens before the handshake; this only covers a slow program start.
            readyTimeoutMs = READY_TIMEOUT_MS,
            // The server end decides; the app cannot know whether it checks a SOCKS login.
            socksAuth = false
        )
    }

    enum class ResolverKind(val flag: String, val defaultPort: Int) { DOH("-doh", 443), DOT("-dot", 853), UDP("-udp", 53) }

    data class Settings(
        val pubkey: String,
        val domain: String,
        val kind: ResolverKind,
        /** The DoH URL, or HOST:PORT for DoT and UDP, with an IP-address host. */
        val resolver: String,
        val utls: String = ""
    )

    companion object {
        /** Matches the EngineRegistry id. */
        const val ID = "dns-tunnel"
        const val BINARY = "dnstt"
        const val SCHEME = "dnstt"
        const val ENGINE_MARKER = "dnstt"
        const val KEY_ENGINE = "engine"
        const val KEY_PUBKEY = "pubkey"
        const val KEY_DOMAIN = "domain"
        const val KEY_RESOLVER_KIND = "resolverKind"
        const val KEY_RESOLVER = "resolver"
        const val KEY_UTLS = "utls"
        const val READY_TIMEOUT_MS = 15_000L
        /** dnstt's default DoH resolver choice for a new profile. */
        const val DEFAULT_DOH = "https://1.1.1.1/dns-query"

        private val PUBKEY = Regex("^[0-9a-fA-F]{64}$")
        private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
        private val UTLS = Regex("^[A-Za-z0-9_.,*]{1,200}$")

        /** A new DNS tunnel profile; throws [IllegalArgumentException] with a user-facing message. */
        fun profile(
            pubkey: String,
            domain: String,
            resolver: String = DEFAULT_DOH,
            kind: ResolverKind = ResolverKind.DOH,
            name: String = "",
            utls: String = ""
        ): VlessProfile {
            val s = validate(pubkey, domain, kind, resolver, utls)
            val extras = JSONObject()
                .put(KEY_ENGINE, ENGINE_MARKER)
                .put(KEY_PUBKEY, s.pubkey)
                .put(KEY_DOMAIN, s.domain)
                .put(KEY_RESOLVER_KIND, s.kind.name.lowercase())
                .put(KEY_RESOLVER, s.resolver)
            if (s.utls.isNotEmpty()) extras.put(KEY_UTLS, s.utls)
            return VlessProfile(
                name = name.trim().ifEmpty { "DNS tunnel ${s.domain}" },
                address = s.domain,
                port = 53,
                uuid = "",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.MIXED,
                extraSettings = extras.toString()
            )
        }

        /** Parses a `dnstt://` link; throws [IllegalArgumentException] with a user-facing message. */
        fun parse(link: String): VlessProfile {
            val trimmed = link.trim()
            require(trimmed.startsWith("$SCHEME://", ignoreCase = true)) { "Not a dnstt:// link." }
            val rest = trimmed.substring(SCHEME.length + 3)
            val name = rest.substringAfter('#', "").let { decode(it) }
            val beforeFragment = rest.substringBefore('#')
            val authority = beforeFragment.substringBefore('?').removeSuffix("/")
            val query = beforeFragment.substringAfter('?', "")
            require(authority.contains('@')) { "The link needs the server public key before @ and the tunnel domain after it." }
            val pubkey = decode(authority.substringBefore('@'))
            val domain = decode(authority.substringAfter('@'))
            val params = query.split('&').filter { it.isNotEmpty() }.associate {
                decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', ""))
            }
            val given = ResolverKind.values().filter { !params[it.name.lowercase()].isNullOrBlank() }
            require(given.size <= 1) { "A DNS tunnel link may name only one resolver (doh, dot or udp)." }
            val kind = given.firstOrNull() ?: ResolverKind.DOH
            val resolver = params[kind.name.lowercase()]?.takeIf { it.isNotBlank() } ?: DEFAULT_DOH
            return profile(pubkey, domain, resolver, kind, name, params["utls"].orEmpty())
        }

        fun parseOrNull(link: String): VlessProfile? = runCatching { parse(link) }.getOrNull()

        /** The share link for a DNS tunnel profile. */
        fun toLink(profile: VlessProfile): String {
            val s = settings(profile)
            val query = StringBuilder("${s.kind.name.lowercase()}=${encode(s.resolver)}")
            if (s.utls.isNotEmpty()) query.append("&utls=").append(encode(s.utls))
            return "$SCHEME://${s.pubkey}@${s.domain}?$query#${encode(profile.name)}"
        }

        /** The validated settings stored in [profile]; throws with a user-facing message. */
        fun settings(profile: VlessProfile): Settings {
            val extras = ProfileExtras.read(profile)
            val kind = ResolverKind.values().firstOrNull { it.name.equals(extras.optString(KEY_RESOLVER_KIND), true) }
                ?: throw IllegalArgumentException("This DNS tunnel has no resolver type (doh, dot or udp).")
            return validate(
                extras.optString(KEY_PUBKEY), extras.optString(KEY_DOMAIN), kind,
                extras.optString(KEY_RESOLVER), extras.optString(KEY_UTLS)
            )
        }

        fun command(s: Settings, executable: String, port: Int): List<String> {
            val args = mutableListOf(executable, s.kind.flag, s.resolver, "-pubkey", s.pubkey)
            if (s.utls.isNotEmpty()) args += listOf("-utls", s.utls)
            args += listOf(s.domain, "127.0.0.1:$port")
            return args
        }

        fun validate(pubkey: String, domain: String, kind: ResolverKind, resolver: String, utls: String): Settings {
            val key = pubkey.trim().lowercase()
            require(PUBKEY.matches(key)) { "The DNS tunnel server public key must be 64 hex digits." }
            val zone = domain.trim().trimEnd('.').lowercase()
            require(validDomain(zone)) { "The DNS tunnel domain is not a valid domain name." }
            val fingerprint = utls.trim()
            require(fingerprint.isEmpty() || UTLS.matches(fingerprint)) { "The uTLS setting is not valid." }
            return Settings(key, zone, kind, resolverAddress(kind, resolver), fingerprint)
        }

        /**
         * The resolver as dnstt-client takes it, with an IP-address host. Known DoH names become their
         * addresses; any other host name is refused, since looking it up would leak or loop.
         */
        fun resolverAddress(kind: ResolverKind, resolver: String): String {
            val value = resolver.trim()
            return when (kind) {
                ResolverKind.DOH -> {
                    require(value.startsWith("https://", ignoreCase = true)) { "A DoH resolver must be an https:// URL." }
                    val literal = DnsResolvers.literal(value)
                    require(DnsResolvers.isUsable(literal)) {
                        "The DoH resolver must use an IP address (for example https://1.1.1.1/dns-query), not a host name."
                    }
                    literal
                }
                ResolverKind.DOT, ResolverKind.UDP -> {
                    val (host, port) = splitHostPort(value.removePrefix("tls://").removePrefix("udp://"), kind.defaultPort)
                    require(host.isNotEmpty() && DnsResolvers.isUsable(host) && !host.startsWith("https://")) {
                        "The ${kind.name} resolver must be an IP address (for example 1.1.1.1), not a host name."
                    }
                    require(port in 1..65535) { "The ${kind.name} resolver port is not valid." }
                    if (host.contains(':')) "[$host]:$port" else "$host:$port"
                }
            }
        }

        private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int> {
            if (value.startsWith("[")) {
                val host = value.substring(1).substringBefore(']')
                val port = value.substringAfter("]", "").removePrefix(":")
                return host to (if (port.isEmpty()) defaultPort else port.toIntOrNull() ?: -1)
            }
            return when (value.count { it == ':' }) {
                0 -> value to defaultPort
                1 -> value.substringBefore(':') to (value.substringAfter(':').toIntOrNull() ?: -1)
                else -> value to defaultPort // a bare IPv6 address
            }
        }

        private fun validDomain(domain: String): Boolean {
            if (domain.isEmpty() || domain.length > 253) return false
            val labels = domain.split('.')
            return labels.size >= 2 && labels.all { LABEL.matches(it) }
        }

        private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
