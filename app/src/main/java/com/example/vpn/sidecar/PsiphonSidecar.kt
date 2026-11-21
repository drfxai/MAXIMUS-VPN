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
            context.upstreamPass, tcpOnly(profile) || context.upstreamSocks != null)
        // ConsoleClient otherwise shares tactics and dial history under UNKNOWN across
        // unrelated networks. Upstream uses this identifier locally and logs its prefix.
        val link = runCatching { Sidecars.psiphonNetworkIdentity?.invoke() }.getOrNull()
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
            readyTimeoutMs = READY_TIMEOUT_MS,
            // Psiphon's SOCKS server takes any user name and password as transport arguments; it
            // cannot check a login, so none is sent.
            socksAuth = false,
            // The SOCKS port opens at once; traffic only passes once a tunnel is up.
            readyLine = TUNNEL_UP
        )
    }

    /** Only structured, known notices can move diagnostics beyond server discovery. */
    internal class StartupProgress {
        @Volatile private var stage = 0

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
            val observed = when (kind) {
                "AvailableEgressRegions" -> if ((data.optJSONArray("regions")?.length() ?: 0) > 0) 1 else 0
                "ConnectingServer" -> 2
                "ConnectedServer" -> 3
                else -> 0
            }
            stage = maxOf(stage, observed)
        }

        fun failureAfter(timeoutMs: Long): String {
            val phase = when (stage) {
                3 -> "server connected but the tunnel did not become ready"
                2 -> "server dialing did not establish a tunnel"
                1 -> "server candidates found but no tunnel became ready"
                else -> "server discovery or bootstrap did not produce a ready tunnel"
            }
            return "Psiphon startup timed out after ${timeoutMs / 1000}s: $phase; traffic is unverified."
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
        /** The port opens early (see the class comment); this only covers a slow first start. */
        const val READY_TIMEOUT_MS = 60_000L
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
            "UpstreamProxyURL", "UpstreamProxyUrl", "ClientPlatform", "NetworkID", "EmitDiagnosticNetworkParameters", "EmitSensitiveNetworkParameters",
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

        internal fun tcpStartupRetry(profile: VlessProfile): VlessProfile? =
            if (privateId(profile).isEmpty() && !tcpOnly(profile)) ProfileExtras.with(profile, "tcpOnly", true)
            else null

        // Protocol identifiers from the pinned Psiphon common/protocol source. QUIC and refraction
        // transports are deliberately absent from the TCP-only / upstream-SOCKS recipe.
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
        fun buildConfig(networkSettings: JSONObject, region: String, socksPort: Int, dataDir: File, upstreamSocks: Int? = null, upstreamUser: String? = null, upstreamPass: String? = null, tcpOnly: Boolean = false): JSONObject {
            val config = JSONObject(networkSettings.toString())
            val owned = LOCAL_FIELDS.map { it.lowercase() }.toSet()
            config.keys().asSequence().filter { it.lowercase() in owned }.toList().forEach { config.remove(it) }
            config.put("LocalSocksProxyPort", socksPort)
            // ConsoleClient defaults to 127.0.0.1 when ListenInterface is absent.
            // Avoid enumerating Android interfaces just to bind that same address.
            config.put("ClientPlatform", "Android")
            config.put("EmitDiagnosticNetworkParameters", false)
            // Xray only needs SOCKS; an HTTP proxy would be one more open local port.
            config.put("DisableLocalHTTPProxy", true)
            config.put("DataRootDirectory", dataDir.absolutePath)
            // The program is pinned and license-tracked; it must not replace itself.
            config.put("EnableUpgradeDownload", false)
            // Everything is tunneled (see LOCAL_FIELDS): no destination is dialed outside Psiphon.
            config.put("SplitTunnelOwnRegion", false)
            if (region.isNotEmpty()) config.put("EgressRegion", region)
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
            }
            if (!config.has("ClientPlatform")) config.put("ClientPlatform", "Android")
            config.put("EmitSensitiveNetworkParameters", false)
            if (!config.has("EmitDiagnosticNotices")) config.put("EmitDiagnosticNotices", false)
            if (tcpOnly) {
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
