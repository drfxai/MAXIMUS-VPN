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
 *  [-utls SPEC] [-qps N] DOMAIN LOCALADDR`. Exactly one resolver option; the key is the server's 32-byte
 * Noise public key as 64 hex digits; -utls picks the TLS fingerprint for DoH/DoT (a weighted random
 * choice by default, "none" for Go's own). -qps comes from this app's patch
 * (scripts/engines/dnstt-query-rate.patch): it caps DNS queries per second, data and polls together.
 *
 * Some networks block a client that sends more than about 5 DNS queries a second, so the app always
 * passes a cap of [MAX_QPS] or less ([DEFAULT_QPS] unless the profile asks for fewer).
 *
 * scripts/engines/dnstt-multipath.patch adds the rest: the resolver options may be repeated (queries
 * are spread over up to [MAX_RESOLVERS] resolvers, and one that stops answering is rested for a while,
 * never over "name does not exist" answers alone, which some networks fake); -domains lists up to
 * [MAX_BACKUP_DOMAINS] backup tunnel domains the client moves to when the current one stops answering
 * (the session carries on when the server answers them all, as the Install Center's server does); and
 * -qps-per-resolver applies the rate limit to each resolver instead of to all of them together.
 *
 * It listens on LOCALADDR before it has talked to the server (the Noise handshake happens after), so
 * the port check passes as soon as the program starts. If the session fails it exits.
 *
 * The resolver must be reachable without a DNS lookup of its own (see [DnsResolvers]): a DoH URL
 * whose host is an IP address (well-known DoH names are swapped for their addresses), or an IP
 * address for DoT and UDP.
 *
 * Link format, chosen for this app (resolver parameters may be repeated, in order of preference):
 * `dnstt://<server public key hex>@<tunnel domain>?doh=<url>|dot=<ip[:port]>|udp=<ip[:port]>[&...][&utls=<spec>][&qps=<1-4>]
 *  [&perresolver=1][&domains=<backup>,<backup>]#<name>`
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

    /** One resolver: the DoH URL, or HOST:PORT for DoT and UDP, with an IP-address host. */
    data class Resolver(val kind: ResolverKind, val address: String)

    data class Settings(
        val pubkey: String,
        val domain: String,
        /** At least one, at most [MAX_RESOLVERS], in order of preference. */
        val resolvers: List<Resolver>,
        val utls: String = "",
        /** DNS queries per second, 1 to [MAX_QPS]: over all resolvers, or each one's with [perResolver]. */
        val qps: Int = DEFAULT_QPS,
        /** Tunnel domains on the same server to move to when [domain] stops answering. */
        val backupDomains: List<String> = emptyList(),
        /** The rate limit counts per resolver, so the total is [qps] times the number of resolvers. */
        val perResolver: Boolean = false
    ) {
        val kind: ResolverKind get() = resolvers.first().kind
        val resolver: String get() = resolvers.first().address
        /** The most queries a second this profile may send in all. */
        val totalQps: Int get() = if (perResolver) qps * resolvers.size else qps
    }

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
        const val KEY_QPS = "qps"
        /** Every resolver, as [{"kind": "doh", "address": "..."}]; the first is also in [KEY_RESOLVER] for older builds. */
        const val KEY_RESOLVERS = "resolvers"
        const val KEY_BACKUP_DOMAINS = "backupDomains"
        const val KEY_PER_RESOLVER = "qpsPerResolver"
        const val MAX_RESOLVERS = 6
        const val MAX_BACKUP_DOMAINS = 3
        /** Queries per second when a profile names none: under the ~5 a second that gets blocked. */
        const val DEFAULT_QPS = 4
        const val MAX_QPS = 4
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
            utls: String = "",
            qps: String = "",
            /** Resolvers after the first, in order. */
            moreResolvers: List<Pair<ResolverKind, String>> = emptyList(),
            backupDomains: List<String> = emptyList(),
            perResolver: Boolean = false
        ): VlessProfile {
            val s = validate(pubkey, domain, listOf(kind to resolver) + moreResolvers, utls, qps, backupDomains, perResolver)
            return withSettings(VlessProfile(name = "", address = s.domain, port = 53, uuid = ""), s).copy(
                name = name.trim().ifEmpty { "DNS tunnel ${s.domain}" },
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.MIXED
            )
        }

        /** [profile] with its DNS tunnel settings replaced by [s] (already validated). */
        fun withSettings(profile: VlessProfile, s: Settings): VlessProfile {
            // Other extras on the profile are kept; the DNS tunnel's own keys are rewritten.
            val extras = JSONObject(ProfileExtras.read(profile).toString())
            for (k in listOf(KEY_RESOLVERS, KEY_UTLS, KEY_QPS, KEY_BACKUP_DOMAINS, KEY_PER_RESOLVER)) extras.remove(k)
            extras
                .put(KEY_ENGINE, ENGINE_MARKER)
                .put(KEY_PUBKEY, s.pubkey)
                .put(KEY_DOMAIN, s.domain)
                .put(KEY_RESOLVER_KIND, s.kind.name.lowercase())
                .put(KEY_RESOLVER, s.resolver)
            if (s.resolvers.size > 1) {
                extras.put(KEY_RESOLVERS, org.json.JSONArray(s.resolvers.map {
                    JSONObject().put("kind", it.kind.name.lowercase()).put("address", it.address)
                }))
            }
            if (s.utls.isNotEmpty()) extras.put(KEY_UTLS, s.utls)
            if (s.qps != DEFAULT_QPS) extras.put(KEY_QPS, s.qps)
            if (s.backupDomains.isNotEmpty()) extras.put(KEY_BACKUP_DOMAINS, org.json.JSONArray(s.backupDomains))
            if (s.perResolver) extras.put(KEY_PER_RESOLVER, true)
            return profile.copy(address = s.domain, port = 53, extraSettings = extras.toString())
        }

        /**
         * [fresh] (from a server install) with the phone-side choices of [old] kept: its resolvers, rate,
         * per-resolver setting and uTLS. The key, domain and backup domains come from [fresh]. When [old]
         * is not a usable DNS tunnel profile, [fresh] is returned as it is.
         */
        fun carryOver(old: VlessProfile, fresh: VlessProfile): VlessProfile {
            val before = runCatching { settings(old) }.getOrNull() ?: return fresh
            val now = settings(fresh)
            return withSettings(fresh, now.copy(resolvers = before.resolvers, utls = before.utls, qps = before.qps,
                perResolver = before.perResolver))
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
            val pairs = query.split('&').filter { it.isNotEmpty() }.map {
                decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', ""))
            }
            val params = pairs.toMap()
            // Resolvers in link order; none means Cloudflare's DoH.
            val resolvers = pairs.mapNotNull { (k, v) ->
                ResolverKind.values().firstOrNull { it.name.lowercase() == k }?.takeIf { v.isNotBlank() }?.let { it to v }
            }.ifEmpty { listOf(ResolverKind.DOH to DEFAULT_DOH) }
            val backups = params["domains"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val perResolver = params["perresolver"].let { it == "1" || it.equals("true", true) }
            val (kind, resolver) = resolvers.first()
            return profile(pubkey, domain, resolver, kind, name, params["utls"].orEmpty(), params["qps"].orEmpty(),
                resolvers.drop(1), backups, perResolver)
        }

        fun parseOrNull(link: String): VlessProfile? = runCatching { parse(link) }.getOrNull()

        /** A resolver as one line of the editor: a DoH URL, `tls://HOST:PORT` for DoT, or `HOST:PORT` for UDP. */
        fun resolverLine(r: Resolver): String = when (r.kind) {
            ResolverKind.DOH -> r.address
            ResolverKind.DOT -> "tls://${r.address}"
            ResolverKind.UDP -> r.address
        }

        /** Resolvers typed one per line (see [resolverLine]); `udp://` is accepted too. Not yet validated. */
        fun parseResolverLines(text: String): List<Pair<ResolverKind, String>> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                when {
                    line.startsWith("https://", true) || line.startsWith("http://", true) -> ResolverKind.DOH to line
                    line.startsWith("tls://", true) -> ResolverKind.DOT to line.substring(6)
                    line.startsWith("udp://", true) -> ResolverKind.UDP to line.substring(6)
                    else -> ResolverKind.UDP to line
                }
            }

        /** The share link for a DNS tunnel profile. */
        fun toLink(profile: VlessProfile): String {
            val s = settings(profile)
            val query = StringBuilder(s.resolvers.joinToString("&") { "${it.kind.name.lowercase()}=${encode(it.address)}" })
            if (s.utls.isNotEmpty()) query.append("&utls=").append(encode(s.utls))
            if (s.qps != DEFAULT_QPS) query.append("&qps=").append(s.qps)
            if (s.perResolver) query.append("&perresolver=1")
            if (s.backupDomains.isNotEmpty()) query.append("&domains=").append(s.backupDomains.joinToString(",") { encode(it) })
            return "$SCHEME://${s.pubkey}@${s.domain}?$query#${encode(profile.name)}"
        }

        /** The validated settings stored in [profile]; throws with a user-facing message. */
        fun settings(profile: VlessProfile): Settings {
            val extras = ProfileExtras.read(profile)
            val kind = ResolverKind.values().firstOrNull { it.name.equals(extras.optString(KEY_RESOLVER_KIND), true) }
                ?: throw IllegalArgumentException("This DNS tunnel has no resolver type (doh, dot or udp).")
            val list = extras.optJSONArray(KEY_RESOLVERS)
            val resolvers = if (list == null || list.length() == 0) listOf(kind to extras.optString(KEY_RESOLVER)) else
                (0 until list.length()).map { i ->
                    val o = list.optJSONObject(i) ?: JSONObject()
                    val k = ResolverKind.values().firstOrNull { it.name.equals(o.optString("kind"), true) }
                        ?: throw IllegalArgumentException("A DNS tunnel resolver has no type (doh, dot or udp).")
                    k to o.optString("address")
                }
            val backups = extras.optJSONArray(KEY_BACKUP_DOMAINS)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return validate(
                extras.optString(KEY_PUBKEY), extras.optString(KEY_DOMAIN), resolvers,
                extras.optString(KEY_UTLS), extras.optString(KEY_QPS), backups, extras.optBoolean(KEY_PER_RESOLVER, false)
            )
        }

        fun command(s: Settings, executable: String, port: Int): List<String> {
            val args = mutableListOf(executable)
            for (r in s.resolvers) args += listOf(r.kind.flag, r.address)
            args += listOf("-pubkey", s.pubkey)
            if (s.utls.isNotEmpty()) args += listOf("-utls", s.utls)
            args += listOf("-qps", s.qps.coerceIn(1, MAX_QPS).toString())
            if (s.perResolver && s.resolvers.size > 1) args += "-qps-per-resolver"
            if (s.backupDomains.isNotEmpty()) args += listOf("-domains", s.backupDomains.joinToString(","))
            args += listOf(s.domain, "127.0.0.1:$port")
            return args
        }

        fun validate(pubkey: String, domain: String, kind: ResolverKind, resolver: String, utls: String, qps: String = ""): Settings =
            validate(pubkey, domain, listOf(kind to resolver), utls, qps)

        fun validate(
            pubkey: String,
            domain: String,
            resolvers: List<Pair<ResolverKind, String>>,
            utls: String,
            qps: String = "",
            backupDomains: List<String> = emptyList(),
            perResolver: Boolean = false
        ): Settings {
            val key = pubkey.trim().lowercase()
            require(PUBKEY.matches(key)) { "The DNS tunnel server public key must be 64 hex digits." }
            val zone = domain.trim().trimEnd('.').lowercase()
            require(validDomain(zone)) { "The DNS tunnel domain is not a valid domain name." }
            val fingerprint = utls.trim()
            require(fingerprint.isEmpty() || UTLS.matches(fingerprint)) { "The uTLS setting is not valid." }
            val rate = if (qps.isBlank()) DEFAULT_QPS else qps.trim().toIntOrNull() ?: -1
            require(rate in 1..MAX_QPS) {
                "The DNS query rate must be 1 to $MAX_QPS per second; networks block more than about 5 a second."
            }
            require(resolvers.isNotEmpty()) { "Add at least one DNS resolver." }
            require(resolvers.size <= MAX_RESOLVERS) { "A DNS tunnel may use up to $MAX_RESOLVERS resolvers." }
            val list = resolvers.map { (k, v) -> Resolver(k, resolverAddress(k, v)) }
            require(list.distinct().size == list.size) { "The same resolver is listed twice." }
            val backups = backupDomains.map { it.trim().trimEnd('.').lowercase() }.filter { it.isNotEmpty() }
            require(backups.size <= MAX_BACKUP_DOMAINS) { "A DNS tunnel may have up to $MAX_BACKUP_DOMAINS backup domains." }
            backups.forEach { require(validDomain(it)) { "The backup domain $it is not a valid domain name." } }
            require((backups + zone).distinct().size == backups.size + 1) { "A backup domain repeats the tunnel domain or another backup." }
            return Settings(key, zone, list, fingerprint, rate, backups, perResolver && list.size > 1)
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
