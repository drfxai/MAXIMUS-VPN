package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Psiphon (psiphon-tunnel-core's ConsoleClient, GPL-3.0) as a separate engine program.
 *
 * Psiphon finds its own servers. What it needs from us is the build's Psiphon network settings
 * (PropagationChannelId, SponsorId, the remote server list URLs and signing key...), which come from
 * Psiphon Inc. and are not part of this repository: CI writes them to the asset [ASSET_PATH] from a
 * secret. [configProvider] returns that JSON, or null when the build has none.
 *
 * What ConsoleClient does, from its source at the pinned version:
 * - `-config FILE` (required), `-dataRootDirectory DIR` (overrides the config's DataRootDirectory),
 *   `-formatNotices` (human-readable notices), plus `-serverList`, `-pushPayload`, `-listenInterface`,
 *   `-notices`, `-useNoticeFiles`, `-tunDevice`, `-feedbackUpload` and `-version`, which we do not use.
 *   Notices go to stderr; SIGTERM shuts it down cleanly.
 * - The local SOCKS proxy (SOCKS4a/SOCKS5, CONNECT only, no UDP) listens on 127.0.0.1 at
 *   LocalSocksProxyPort as soon as the controller starts, before any tunnel exists, and prints a
 *   `ListeningSocksProxyPort` notice. If that port is taken it exits instead of picking another one.
 *   Until a `Tunnels` notice with count >= 1, every CONNECT fails ("no active tunnels"), so traffic
 *   stays blocked until the SOCKS port and a positive tunnel notice are both observed.
 * - Without Android's DNS server list (only the Java library supplies one), its resolver falls back
 *   to Go's own, which on Android without cgo has no /etc/resolv.conf and asks 127.0.0.1:53. We
 *   therefore give it DNSResolverAlternateServers (plain IPs) unless the build's settings do.
 */
class PsiphonSidecar(
    private val configProvider: () -> String?,
    private val privateConfigProvider: (String) -> String? = { null }
) : SidecarEngine {
    /** Preserve call sites using PsiphonSidecar { publicSettings } or PsiphonSidecar(provider). */
    constructor(configProvider: () -> String?) : this(configProvider, { null })

    override val id: String = ID
    override val binary: String = BINARY

    override fun handles(profile: VlessProfile): Boolean =
        ProfileExtras.read(profile).optString(KEY_ENGINE) == ID

    override fun problem(profile: VlessProfile): String? {
        val privateId = privateId(profile)
        if (privateId.isNotEmpty()) {
            if (!PrivatePsiphonStore.validId(privateId)) return "Invalid private Psiphon profile identity."
            val config = privateSettings(privateId)
                ?: return "Private Psiphon bootstrap unavailable in encrypted storage."
            return validatePrivateSettings(config)
        }
        val settings = networkSettings() ?: return MISSING_SETTINGS
        validateNetworkSettings(settings.toString())?.let { return it }
        if (!validRegion(region(profile))) return "The Psiphon region must be a two-letter country code, or empty for any region."
        return null
    }

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        problem(profile)?.let { throw IllegalStateException(it) }
        val dataDir = File(context.workDir, DATA_DIR).apply { mkdirs() }
        val privateId = privateId(profile)
        val source = if (privateId.isNotEmpty()) requireNotNull(privateSettings(privateId))
            else requireNotNull(networkSettings())
        val config = buildConfig(source, if (privateId.isNotEmpty()) "" else region(profile),
            context.socksPort, dataDir, context.upstreamSocks, context.upstreamUser,
            context.upstreamPass, tcpOnly(profile) || context.upstreamSocks != null,
            fronted = privateId.isEmpty() && isFrontedOnly(profile))
        // ConsoleClient otherwise shares tactics and dial history under UNKNOWN across
        // unrelated networks. Upstream uses this identifier locally and logs its prefix.
        val link = runCatching { Sidecars.psiphonNetworkIdentity?.invoke() }.getOrNull()
        val metered = runCatching { Sidecars.psiphonLinkMetered?.invoke() }.getOrNull() == true
        networkId(link, context.upstreamSocks != null)?.let { config.put("NetworkID", it) }
        val configFile = File(context.workDir, CONFIG_FILE)
        PrivateEngineFile.write(configFile, config.toString())
        val linkDns = if (context.upstreamSocks == null && !source.has("DNSResolverAlternateServers"))
            runCatching { Sidecars.psiphonLinkDns?.invoke().orEmpty() }.getOrDefault(emptyList()) else emptyList()
        return SidecarLaunch(
            command = listOf(
                context.executable.absolutePath,
                "-config", configFile.absolutePath,
                "-dataRootDirectory", dataDir.absolutePath,
                "-formatNotices"
            ),
            environment = if (linkDns.isEmpty()) emptyMap() else mapOf("MAXIMUS_PSIPHON_LINK_DNS" to linkDns.joinToString(",")),
            readyTimeoutMs = readyTimeoutMs(link, metered, context.upstreamSocks != null),
            // Psiphon's SOCKS server takes any user name and password as transport arguments; it
            // cannot check a login, so none is sent.
            socksAuth = false,
            // The SOCKS port opens at once; traffic only passes once a tunnel is up.
            readyLine = TUNNEL_UP
        )
    }

    /**
     * How many servers a Psiphon start dialed, by TCP and other (QUIC, in-proxy, refraction) protocols, how many
     * it connected to, the last CandidateServers count and the UpstreamProxyError notices. Counts and protocol
     * names only. Needs EmitDiagnosticNotices (ConnectingServer, ConnectedServer and CandidateServers are
     * diagnostic notices in psiphon-tunnel-core's notice.go).
     */
    data class StartupDials(
        val tcp: Int = 0, val other: Int = 0, val connected: Int = 0, val candidates: Int? = null,
        val upstreamErrors: Int = 0, val byProtocol: Map<String, Int> = emptyMap()
    ) {
        /** One word for a report: why no tunnel came up, as far as the notices tell. */
        val verdict: String get() = when {
            upstreamErrors > 0 -> "upstream_proxy_error"
            connected > 0 -> "connected_no_tunnel"
            tcp + other > 0 -> "dials_failed"
            candidates == 0 -> "no_candidates"
            else -> "no_dials"
        }

        /** "dials_failed: candidates=37; dialed OSSH×12 TLS-OSSH×5; connected 0". */
        fun summary(): String = buildString {
            append(verdict).append(':')
            candidates?.let { append(" candidates=").append(it).append(';') }
            append(" dialed ")
            append(if (byProtocol.isEmpty()) "none" else byProtocol.entries.joinToString(" ") { "${it.key}×${it.value}" })
            append("; connected ").append(connected)
            if (upstreamErrors > 0) append("; upstream proxy errors ").append(upstreamErrors)
        }
    }

    /** Only structured, known notices can move diagnostics beyond server discovery. */
    internal class StartupProgress {
        @Volatile private var stage = 0
        /** The last SkipServerEntry reason, reduced to its fixed text and a short token (never an address). */
        @Volatile private var lastSkip: String? = null
        @Volatile private var skips = 0
        /** The last notice name seen (names only; notice data can hold addresses). */
        @Volatile private var lastNotice: String? = null
        /** The last [RECENT_NOTICES] notices, as type plus whitelisted, sanitized data ([summarize]). */
        private val recent = ArrayDeque<String>()
        /** ConnectingServer notices per tunnel protocol (protocol names only, never addresses). */
        private val dialed = java.util.concurrent.ConcurrentHashMap<String, Int>()
        @Volatile private var connected = 0
        @Volatile private var candidates: Int? = null
        @Volatile private var upstreamErrors = 0
        /** The regions of the last AvailableEgressRegions notice (two-letter codes only); null until one arrives. */
        @Volatile private var availableRegions: List<String>? = null
        /** The region of the server the tunnel connected to (ConnectedServerRegion); null until then. */
        @Volatile private var connectedRegion: String? = null

        /** 0 nothing yet, 1 region list in, 2 a server dialed, 3 a server connected. */
        val progressStage: Int get() = stage

        /** The core listed no egress region (its data store holds no server entry) and dialed nothing since. */
        val noServersKnown: Boolean get() = stage == 0 && skips == 0 && availableRegions?.isEmpty() == true && dialed.isEmpty() &&
            (candidates ?: 0) == 0

        fun availableRegions(): List<String>? = availableRegions

        fun connectedRegion(): String? = connectedRegion

        fun observe(line: String) {
            if (line.length > 32_768) return
            val start = line.indexOf('{')
            if (start < 0) return
            val notice = runCatching { JSONObject(line.substring(start)) }.getOrNull() ?: return
            // ConsoleClient can emit either raw JSON or a timestamp, notice name, then its JSON data.
            val kind = notice.optString("noticeType").ifBlank {
                line.substring(0, start).trimEnd().substringAfterLast(' ')
            }
            val data = notice.optJSONObject("data") ?: notice
            if (!NOTICE_NAME.matches(kind)) return
            // NetworkID is printed every time the core re-reads the network identity (each dial round): it says
            // nothing about why no tunnel came up, so it never becomes "the last notice" (build 170 report).
            if (kind !in NOISE) lastNotice = kind
            remember(summarize(kind, data))
            when (kind) {
                "ConnectingServer" -> data.optString("protocol").takeIf { PROTOCOL_NAME.matches(it) }
                    ?.let { dialed.merge(it, 1) { a, b -> a + b } }
                "ConnectedServer" -> connected++
                // CandidateServers (diagnostic) reports how many server entries the current limits allow.
                "CandidateServers" -> if (data.has("count")) candidates = data.optInt("count")
                "UpstreamProxyError" -> upstreamErrors++
                "AvailableEgressRegions" -> data.optJSONArray("regions")?.let { a ->
                    availableRegions = (0 until a.length()).map { a.optString(it).trim().uppercase() }
                        .filter { REGION_CODE.matches(it) }.distinct()
                }
                "ConnectedServerRegion" -> data.optString("serverRegion").trim().uppercase()
                    .takeIf { REGION_CODE.matches(it) }?.let { connectedRegion = it }
            }
            if (kind == "SkipServerEntry") {
                skips++
                skipReason(data.optString("reason"))?.let { lastSkip = it }
            }
            val observed = when (kind) {
                "AvailableEgressRegions" -> if ((data.optJSONArray("regions")?.length() ?: 0) > 0) 1 else 0
                "ConnectingServer" -> 2
                "ConnectedServer" -> 3
                else -> 0
            }
            stage = maxOf(stage, observed)
        }

        private fun remember(entry: String) = synchronized(recent) {
            // Consecutive repeats (NetworkID every round) collapse into one entry with a count.
            val last = recent.lastOrNull()
            val base = last?.substringBefore(REPEAT)
            if (base == entry) {
                val n = (last!!.substringAfter(REPEAT, "1").toIntOrNull() ?: 1) + 1
                recent.removeLast()
                recent.addLast("$entry$REPEAT$n")
            } else {
                recent.addLast(entry)
                while (recent.size > RECENT_NOTICES) recent.removeFirst()
            }
        }

        /** What this start dialed, from its notices ([StartupDials]). */
        fun dials(): PsiphonSidecar.StartupDials {
            val counts = HashMap(dialed)
            return PsiphonSidecar.StartupDials(
                tcp = counts.filterKeys { it in PsiphonSidecar.TCP_PROTOCOLS }.values.sum(),
                other = counts.filterKeys { it !in PsiphonSidecar.TCP_PROTOCOLS }.values.sum(),
                connected = connected, candidates = candidates, upstreamErrors = upstreamErrors,
                byProtocol = counts.toSortedMap())
        }

        /** The last notices, oldest first, joined for a diagnostics event; null when none were seen. */
        fun recentNotices(): String? = synchronized(recent) {
            recent.takeIf { it.isNotEmpty() }?.joinToString(" | ")?.take(MAX_NOTICES_TEXT)
        }

        fun failureAfter(timeoutMs: Long, label: String = "Psiphon"): String {
            val d = dials()
            val dialedCount = d.tcp + d.other
            val regionCount = availableRegions?.size
            val phase = when {
                // Build 180 (A4): the stage-3 text lost the dial counts the stage-2 text gives.
                stage == 3 -> "server connected but the tunnel did not become ready" + if (dialedCount == 0) "" else
                    " (dialed $dialedCount server(s): " + d.byProtocol.entries.joinToString(", ") { "${it.key}×${it.value}" } +
                    "; connected ${d.connected})"
                // Build 178: say how many servers were tried and over which protocols, so "Psiphon is blocked here"
                // and "Psiphon never tried" read differently.
                stage == 2 -> "server dialing did not establish a tunnel" + if (dialedCount == 0) "" else
                    ": dialed $dialedCount server(s) (" + d.byProtocol.entries.joinToString(", ") { "${it.key}×${it.value}" } +
                    ") and none answered on this network"
                stage == 1 -> "server candidates found" + (regionCount?.let { " ($it regions)" } ?: "") +
                    " but no server was dialed yet" + (d.candidates?.let { " ($it allowed by the protocol limits)" } ?: "") +
                    "; no tunnel became ready"
                // Build 175 (Shir o Khorshid): an empty region list and no dial at all means the data store held no
                // server entry and the server list could not be downloaded on this network.
                noServersKnown -> "no Psiphon servers known yet (empty server list) and the server list could not be " +
                    "downloaded on this network"
                else -> "server discovery or bootstrap did not produce a ready tunnel"
            }
            val detail = buildList {
                lastSkip?.let { add("last skipped server: $it" + if (skips > 1) " ($skips skips)" else "") }
                lastNotice?.let { add("last notice: $it") }
            }
            val suffix = if (detail.isEmpty()) "" else " (${detail.joinToString("; ")})"
            return "$label startup timed out after ${timeoutMs / 1000}s: $phase$suffix; traffic is unverified."
        }

        internal companion object {
            const val RECENT_NOTICES = 20
            const val MAX_NOTICES_TEXT = 2_000
            private const val REPEAT = " ×"
            private val NOISE = setOf("NetworkID")
            /** Notice data fields that hold counts, protocol names or regions, never addresses or keys. */
            private val SAFE_FIELDS = listOf("protocol", "region", "count", "initialCount", "candidateNumber",
                "networkType", "isReplay", "establishedTunnelsCount", "port", "duration")
            private val MESSAGE_FIELDS = listOf("message", "error", "reason")
            private val GO_TRACE = Regex("[A-Za-z0-9_./()*-]+#\\d+: ")
            private val URL = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+")
            private val IPV4 = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b")
            private val IPV6 = Regex("\\[?\\b(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}\\b\\]?(?::\\d+)?")
            private val HOST = Regex("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,24}\\b(?::\\d+)?")
            private val LONG_TOKEN = Regex("\\b[A-Za-z0-9+/=_-]{20,}\\b")
            private val PATH = Regex("(?:/[A-Za-z0-9._-]+){2,}")

            /** Free text from a notice, without addresses, host names, URLs, paths or long tokens. Pure. */
            fun sanitizeMessage(text: String): String = text.take(400)
                .replace(GO_TRACE, "")
                .replace(URL, "<url>")
                .replace(IPV4, "<ip>")
                .replace(IPV6) { m -> if (m.value.count { it == ':' } >= 2 && m.value.any { it.isDigit() || it in "abcdefABCDEF" }) "<ip>" else m.value }
                .replace(HOST, "<host>")
                .replace(LONG_TOKEN, "<id>")
                .replace(PATH, "<path>")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(140)

            /** "Type key=value …" for one notice: whitelisted fields plus a sanitized message. Pure. */
            fun summarize(kind: String, data: JSONObject): String {
                val parts = mutableListOf<String>()
                for (key in SAFE_FIELDS) {
                    val value = data.opt(key) ?: continue
                    val text = value.toString()
                    if (text.isNotEmpty() && text.length <= 32 && SAFE_VALUE.matches(text)) parts += "$key=$text"
                }
                (data.optJSONArray("regions"))?.let { parts += "regions=${it.length()}" }
                if (kind == "SkipServerEntry") skipReason(data.optString("reason"))?.let { parts += "reason=$it" }
                else for (key in MESSAGE_FIELDS) {
                    val raw = data.optString(key)
                    if (raw.isNotBlank()) { parts += sanitizeMessage(raw); break }
                }
                return if (parts.isEmpty()) kind else "$kind " + parts.joinToString(" ")
            }
            private val SAFE_VALUE = Regex("^[A-Za-z0-9_.:-]+$")
            private val REGION_CODE = Regex("^[A-Z]{2}$")
            private val PROTOCOL_NAME = Regex("^[A-Z0-9-]{2,48}$")

            val NOTICE_NAME = Regex("^[A-Za-z][A-Za-z0-9]{0,47}$")
            val SKIP_TEXT = Regex("^[A-Za-z][A-Za-z ]{2,79}$")
            val SKIP_TOKEN = Regex("^[A-Za-z0-9_*.-]{1,48}$")

            /**
             * Psiphon's skip reasons are a fixed text, then ": " and one value (a server entry source,
             * provider ID or protocol name). Anything that is not that shape is dropped, so an address
             * can never reach a report.
             */
            fun skipReason(reason: String): String? {
                val text = reason.substringBefore(':').trim()
                if (!SKIP_TEXT.matches(text)) return null
                val token = reason.substringAfter(':', "").trim()
                return if (token.isNotEmpty() && SKIP_TOKEN.matches(token)) "$text: $token" else text
            }
        }
    }

    private fun privateSettings(id: String): JSONObject? {
        if (!PrivatePsiphonStore.validId(id)) return null
        return runCatching { privateConfigProvider(id)?.let(::JSONObject) }.getOrNull()
    }

    private fun networkSettings(): JSONObject? {
        val raw = runCatching { configProvider() }.getOrNull()?.trim()
        if (raw.isNullOrEmpty()) return null
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put(INVALID_MARKER, true) }
    }

    companion object {
        const val ID = "psiphon"
        const val BINARY = "psiphon"
        /** Asset CI writes from a secret; absent in builds without Psiphon network settings. */
        const val ASSET_PATH = "psiphon/config.json"
        private const val PUBLIC_ASSET_PATH = "psiphon/public-config.json"
        const val SETTINGS_STORAGE_KEY = "psiphon_network_settings_v1"
        const val MAX_SETTINGS_BYTES = 512 * 1024
        const val KEY_ENGINE = "engine"
        const val KEY_REGION = "region"
        const val KEY_PRIVATE_PROFILE_ID = "privateProfileId"
        const val CONFIG_FILE = "psiphon.config"
        const val DATA_DIR = "data"
        /** The port opens early (see the class comment); this only covers a slow first start (Wi-Fi). */
        const val READY_TIMEOUT_MS = 60_000L
        /**
         * Cellular or metered links (Iranian mobile carriers throttle and reset handshakes) and chain hops
         * (every Psiphon dial goes through the next hop first) need longer to find a working server.
         */
        const val SLOW_READY_TIMEOUT_MS = 120_000L

        /** [link] is [Sidecars.psiphonNetworkIdentity]'s "wifi:…" / "cell:…" key. Pure. */
        internal fun readyTimeoutMs(link: String?, metered: Boolean, chained: Boolean): Long =
            if (chained || metered || link?.substringBefore(':') == "cell") SLOW_READY_TIMEOUT_MS else READY_TIMEOUT_MS

        /**
         * TCP dials the first (UDP+TCP) start must have made, none connecting, before the TCP-only retry is skipped in
         * favour of Psiphon over Lantern.
         */
        const val TCP_DIALS_BEFORE_CHAIN = 6

        /**
         * Whether direct Psiphon's TCP-only retry is skipped after the first start: the chain over Lantern is allowed for
         * this connect ([chainAllowed]) and the first start already dialed at least [TCP_DIALS_BEFORE_CHAIN] TCP
         * servers with none connecting and no upstream error. The retry would only repeat those protocols for another
         * 120 s; build 170 on Irancell failed both starts (4 min) while the chain connected at once. Pure.
         */
        internal fun skipTcpRetry(first: StartupDials, chainAllowed: Boolean): Boolean =
            chainAllowed && first.connected == 0 && first.upstreamErrors == 0 && first.tcp >= TCP_DIALS_BEFORE_CHAIN

        /** The notice Psiphon prints when it has at least one tunnel. */
        val TUNNEL_UP = Regex("Tunnels.*\"count\":\\s*[1-9]")

        const val MISSING_SETTINGS =
            "Psiphon needs this build's Psiphon network settings, which are not included. Import a Psiphon.config file in Settings → Engine updates."

        /** Plain DNS servers Psiphon may use to find its server lists when Android gives it none. */
        val DEFAULT_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

        private const val INVALID_MARKER = "\u0000invalid"
        private val REQUIRED = listOf("PropagationChannelId", "SponsorId")
        private val REGION = Regex("^[A-Z]{2}$")

        /** Fields the app decides; anything the build's settings say about them is replaced. */
        private val LOCAL_FIELDS = listOf(
            "LocalSocksProxyPort", "LocalHttpProxyPort", "DisableLocalSocksProxy", "DisableLocalHTTPProxy",
            "DataRootDirectory", "MigrateDataStoreDirectory", "ListenInterface", "UseUnixDomainSockets",
            "LocalSocksProxyUnixPath", "LocalHttpProxyUnixPath", "EgressRegion", "EnableUpgradeDownload",
            "UpgradeDownloadURLs", "UpgradeDownloadClientVersionHeader", "UseNoticeFiles", "DisableTunnels",
            "UpstreamProxyURL", "UpstreamProxyUrl", "ClientPlatform", "ClientAPILevel", "NetworkID", "EmitDiagnosticNetworkParameters", "EmitSensitiveNetworkParameters",
            // Build 178 field report: the bundled public settings say EmitDiagnosticNotices=false, so no ConnectingServer
            // notice ever arrived; every start looked "silent" ("no_dials"), any region was cut at 40 s and two 25 s region
            // starts followed, each restart throwing establishment away. The app reads these notices; it decides them.
            "EmitDiagnosticNotices",
            // Split tunnel sends "own region" destinations around Psiphon, classified from a lookup on the
            // phone's own (filtered) network. In Iran that returns 10.10.34.x for blocked names such as
            // YouTube's, so nothing may go direct: MAXIMUS sends all app traffic into Psiphon.
            "SplitTunnelOwnRegion", "SplitTunnelRegions"
        )

        /** A new Psiphon profile; [region] is a two-letter country code, or empty for any. */
        fun profile(region: String = "", tcpOnly: Boolean = false): VlessProfile {
            val code = region.trim().uppercase()
            val extras = JSONObject().put(KEY_ENGINE, ID).put(KEY_REGION, code)
            if (tcpOnly) extras.put("tcpOnly", true)
            return VlessProfile(
                name = if (code.isEmpty()) "Psiphon" else "Psiphon ($code)",
                address = "psiphon",
                port = 0,
                uuid = "",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.MIXED,
                extraSettings = extras.toString()
            )
        }

        /** Creates a node with an opaque reference, never the raw server entry. */
        fun privateProfile(id: String, serverName: String, serverAddress: String): VlessProfile {
            require(PrivatePsiphonStore.validId(id)) { "Invalid private Psiphon reference" }
            require(serverAddress.isNotBlank())
            val extras = JSONObject().put(KEY_ENGINE, ID).put(KEY_PRIVATE_PROFILE_ID, id)
            return VlessProfile(
                name = "Psiphon Private · " + serverName.take(48),
                address = serverAddress, port = 0, uuid = "",
                profileType = ProfileType.VLESS, protocolType = ProtocolType.MIXED,
                extraSettings = extras.toString()
            )
        }

        fun privateId(profile: VlessProfile): String =
            ProfileExtras.read(profile).optString(KEY_PRIVATE_PROFILE_ID)

        /** TargetServerEntry exclusively selects the private VPS, without public server lists. */
        fun validatePrivateSettings(source: JSONObject): String? {
            if (!PrivatePsiphonStore.validEntry(source.optString("TargetServerEntry")))
                return "Invalid or missing private Psiphon server entry."
            if (!PrivatePsiphonStore.validIdentifier(source.optString("SponsorId")) ||
                !PrivatePsiphonStore.validIdentifier(source.optString("PropagationChannelId")))
                return "Missing private Psiphon channel or sponsor identifier."
            if (source.has("RemoteServerListURLs") || source.has("ObfuscatedServerListRootURLs"))
                return "Private Psiphon bootstrap must not include public server lists."
            if (source.optBoolean("SkipVerify") || source.optBoolean("DisableTLSValidation"))
                return "Private Psiphon cannot disable server verification."
            return null
        }

        fun tcpOnly(profile: VlessProfile): Boolean = ProfileExtras.read(profile).optBoolean("tcpOnly")

        const val KEY_FRONTED = "frontedOnly"

        /** The profile limited to Psiphon's CDN-fronted meek protocols ([FRONTED_PROTOCOLS]). */
        fun frontedOnly(profile: VlessProfile): VlessProfile = ProfileExtras.with(profile, KEY_FRONTED, true)

        fun isFrontedOnly(profile: VlessProfile): Boolean = ProfileExtras.read(profile).optBoolean(KEY_FRONTED)

        /**
         * Psiphon's CDN-fronted meek protocols (psiphon-tunnel-core common/protocol): the tunnel and the server-list
         * fetch go to a CDN edge, so a carrier that drops Psiphon server addresses still sees ordinary CDN traffic.
         */
        val FRONTED_PROTOCOLS = listOf("FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH", "FRONTED-MEEK-QUIC-OSSH")

        internal fun tcpStartupRetry(profile: VlessProfile): VlessProfile? =
            if (privateId(profile).isEmpty() && !tcpOnly(profile)) ProfileExtras.with(profile, "tcpOnly", true)
            else null

        // Protocol identifiers from the pinned Psiphon common/protocol source. QUIC and refraction
        // transports are deliberately absent from the TCP-only / upstream-SOCKS recipe.
        /** Set only for a chain hop (see [buildConfig]); a build's settings can never turn it on. */
        private val CHAIN_ONLY_FIELDS = listOf("UpstreamProxyAllowAllServerEntrySources")

        val TCP_PROTOCOLS = setOf("SSH", "OSSH", "TLS-OSSH", "SHADOWSOCKS-OSSH", "UNFRONTED-MEEK-OSSH",
            "UNFRONTED-MEEK-HTTPS-OSSH", "UNFRONTED-MEEK-SESSION-TICKET-OSSH", "FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH")

        internal fun networkId(link: String?, relayed: Boolean): String? {
            link ?: return null
            val prefix = when (link.substringBefore(':')) {
                "wifi" -> "WIFI"
                "cell" -> "MOBILE"
                "ethernet" -> "ETHERNET"
                else -> return null
            }
            val identity = link.substringAfter(':', "unknown")
                .takeIf { it == "unknown" || it.matches(Regex("[0-9a-f]{5,12}")) }
                ?: return null
            val route = if (relayed) "relay" else "direct"
            return "$prefix-$identity-$route"
        }

        fun region(profile: VlessProfile): String =
            ProfileExtras.read(profile).optString(KEY_REGION).trim().uppercase()

        fun validRegion(region: String): Boolean = region.isEmpty() || REGION.matches(region)

        /** Prefer the distributor settings included by CI, then the attributed public profile. */
        fun assetProvider(open: (String) -> InputStream): () -> String? = {
            fun read(path: String): String? = try {
                open(path).use { it.readBytes().toString(Charsets.UTF_8) }.takeIf { it.isNotBlank() }
            } catch (_: FileNotFoundException) {
                null
            }
            read(ASSET_PATH) ?: read(PUBLIC_ASSET_PATH)
        }

        internal fun incompleteReason(settings: JSONObject): String? {
            if (settings.has(INVALID_MARKER)) return "This build's Psiphon network settings are not valid JSON."
            val missing = REQUIRED.filter { settings.optString(it).isBlank() }
            if (missing.isNotEmpty()) return "This build's Psiphon network settings are incomplete (missing ${missing.joinToString()})."
            // ConsoleClient has no built-in server list; without a remote list it can never find a server.
            if (!hasUrls(settings, "RemoteServerListURLs") && !hasUrls(settings, "ObfuscatedServerListRootURLs")) {
                return "This build's Psiphon network settings have no server list address."
            }
            if (settings.optString("RemoteServerListSignaturePublicKey").isBlank()) {
                return "This build's Psiphon network settings are incomplete (missing RemoteServerListSignaturePublicKey)."
            }
            return null
        }

        fun validateNetworkSettings(raw: String): String? {
            if (raw.length > MAX_SETTINGS_BYTES) return "Psiphon settings exceed 512 KiB."
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return "Psiphon settings must be a JSON object."
            incompleteReason(obj)?.let { return it }
            for (key in listOf("RemoteServerListURLs", "ObfuscatedServerListRootURLs")) {
                val urls = obj.optJSONArray(key) ?: continue
                for (i in 0 until urls.length()) {
                    val item = urls.opt(i)
                    if (item is JSONObject && item.optBoolean("SkipVerify")) return "Psiphon list TLS verification cannot be disabled."
                    val encoded = if (item is JSONObject) item.optString("URL") else item?.toString().orEmpty()
                    // Psiphon's TransferURL encodes its URL as base64. Validate the
                    // decoded destination without changing what the engine receives.
                    val value = if (encoded.startsWith("https://")) encoded else
                        runCatching { String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrDefault("")
                    val uri = runCatching { java.net.URI(value) }.getOrNull()
                    if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null)
                        return "Psiphon server lists must use HTTPS without URL credentials."
                }
            }
            return null
        }

        private fun hasUrls(settings: JSONObject, key: String): Boolean =
            (settings.opt(key) as? JSONArray)?.length()?.let { it > 0 } == true

        /** The config file ConsoleClient reads: the build's settings plus what this connection needs. */
        fun buildConfig(networkSettings: JSONObject, region: String, socksPort: Int, dataDir: File, upstreamSocks: Int? = null, upstreamUser: String? = null, upstreamPass: String? = null, tcpOnly: Boolean = false,
                        /** Only [FRONTED_PROTOCOLS] (TCP ones when [tcpOnly]); the egress region is never pinned then. */
                        fronted: Boolean = false): JSONObject {
            val config = JSONObject(networkSettings.toString())
            val owned = (LOCAL_FIELDS + CHAIN_ONLY_FIELDS).map { it.lowercase() }.toSet()
            config.keys().asSequence().filter { it.lowercase() in owned }.toList().forEach { config.remove(it) }
            config.put("LocalSocksProxyPort", socksPort)
            // ConsoleClient defaults to 127.0.0.1 when ListenInterface is absent.
            // Avoid enumerating Android interfaces just to bind that same address.
            // Plain "Android", as before build 168 (#258). Servers normalise any platform string containing
            // "Android" to the same value (server/api.go normalizeClientPlatform), so the longer
            // "Android_<release>_<package>" form gained nothing there, and ClientAPILevel only switches anet's
            // interface enumeration (utils.go applyClientAPILevel), which this SOCKS-only client never needed.
            config.put("ClientPlatform", "Android")
            config.put("EmitDiagnosticNetworkParameters", false)
            // Establishment pacing is left to psiphon-tunnel-core's defaults (10 workers, 2 s pause) and the
            // network's tactics. #258 forced 16 (12 in a chain) workers and a 1 s pause; on Irancell (build 170,
            // metered) direct Psiphon then never brought a tunnel up within 120 s, twice per connect, while the
            // same core chained over Lantern connected at once. config.go recommends omitting the pool size.
            // Nothing here overrides a build's own values for these fields.
            // Xray only needs SOCKS; an HTTP proxy would be one more open local port.
            config.put("DisableLocalHTTPProxy", true)
            config.put("DataRootDirectory", dataDir.absolutePath)
            // The program is pinned and license-tracked; it must not replace itself.
            config.put("EnableUpgradeDownload", false)
            // Everything is tunneled (see LOCAL_FIELDS): no destination is dialed outside Psiphon.
            config.put("SplitTunnelOwnRegion", false)
            // A fronted start is never pinned: few regions host fronted servers, and a pin would empty the candidates.
            if (region.isNotEmpty() && !fronted) config.put("EgressRegion", region)
            if (!config.has("DNSResolverAlternateServers")) {
                config.put("DNSResolverAlternateServers", JSONArray(DEFAULT_DNS_SERVERS))
            }
            // Chain hop: Psiphon reaches its own servers through the next hop toward the exit.
            if (upstreamSocks != null) {
                val auth = if (upstreamUser != null && upstreamPass != null) {
                    fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                    "${encode(upstreamUser)}:${encode(upstreamPass)}@"
                } else ""
                config.put("UpstreamProxyURL", "socks5://${auth}127.0.0.1:$upstreamSocks")
                // With an upstream proxy, psiphon-tunnel-core (dialParameters.go) only dials server
                // entries whose source is EMBEDDED or REMOTE, so entries learned from obfuscated lists,
                // discovery, DSL or exchange are skipped ("server entry source disallowed with upstream
                // proxy") and a chain can end with no candidates at all. That rule protects against a
                // hostile upstream proxy enumerating Psiphon servers. Here the upstream is our own local
                // hop, so the party who could enumerate them is that hop's operator (e.g. Lantern), which
                // sees the Psiphon server addresses it is asked to reach. We accept that for chains.
                config.put("UpstreamProxyAllowAllServerEntrySources", true)
            }
            if (!config.has("ClientPlatform")) config.put("ClientPlatform", "Android")
            config.put("EmitSensitiveNetworkParameters", false)
            // Diagnostic notices (ConnectingServer, Warning, Error…) are what explains a start that never gets a
            // tunnel; without them only NetworkID repeats. psiphon-tunnel-core redacts IP addresses in every
            // notice (notice.go outputNotice), and StartupProgress keeps only whitelisted, sanitized fields.
            config.put("EmitDiagnosticNotices", true)
            if (fronted) {
                config.remove("InitialLimitTunnelProtocols")
                config.remove("InitialLimitTunnelProtocolsCandidateCount")
                config.remove("TunnelProtocol")
                val previous = config.optJSONArray("LimitTunnelProtocols")
                val restricted = previous?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
                val base = if (restricted.isEmpty()) FRONTED_PROTOCOLS else restricted.filter { it in FRONTED_PROTOCOLS }
                    .ifEmpty { FRONTED_PROTOCOLS }
                val protocols = if (tcpOnly) base.filter { it in TCP_PROTOCOLS } else base
                config.put("LimitTunnelProtocols", JSONArray(protocols))
                // Tactics may narrow the protocol set again; the fronted step decides it, as the fork's CDN mode does.
                config.put("DisableTactics", true)
            } else if (tcpOnly) {
                config.remove("InitialLimitTunnelProtocols")
                config.remove("InitialLimitTunnelProtocolsCandidateCount")
                config.remove("TunnelProtocol")
                val previous = config.optJSONArray("LimitTunnelProtocols")
                val restricted = previous?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
                val protocols = if (restricted.isEmpty()) TCP_PROTOCOLS.toList() else restricted.filter { it in TCP_PROTOCOLS }
                require(protocols.isNotEmpty()) { "These settings allow no TCP Psiphon protocol." }
                config.put("LimitTunnelProtocols", JSONArray(protocols))
            }
            return config
        }
    }
}
