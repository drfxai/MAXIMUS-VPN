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
 *   stays blocked rather than leaking. The readiness check is a port check and therefore passes
 *   early; "ready" means "running", not "connected".
 * - Without Android's DNS server list (only the Java library supplies one), its resolver falls back
 *   to Go's own, which on Android without cgo has no /etc/resolv.conf and asks 127.0.0.1:53. We
 *   therefore give it DNSResolverAlternateServers (plain IPs) unless the build's settings do.
 */
class PsiphonSidecar(private val configProvider: () -> String?) : SidecarEngine {
    override val id: String = ID
    override val binary: String = BINARY

    override fun handles(profile: VlessProfile): Boolean =
        ProfileExtras.read(profile).optString(KEY_ENGINE) == ID

    override fun problem(profile: VlessProfile): String? {
        val settings = networkSettings() ?: return MISSING_SETTINGS
        incompleteReason(settings)?.let { return it }
        if (!validRegion(region(profile))) return "The Psiphon region must be a two-letter country code, or empty for any region."
        return null
    }

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        problem(profile)?.let { throw IllegalStateException(it) }
        val dataDir = File(context.workDir, DATA_DIR).apply { mkdirs() }
        val config = buildConfig(networkSettings()!!, region(profile), context.socksPort, dataDir)
        val configFile = File(context.workDir, CONFIG_FILE)
        configFile.writeText(config.toString())
        // The settings are not secret in the cryptographic sense, but no other app needs to read them.
        configFile.setReadable(false, false)
        configFile.setReadable(true, true)
        return SidecarLaunch(
            command = listOf(
                context.executable.absolutePath,
                "-config", configFile.absolutePath,
                "-dataRootDirectory", dataDir.absolutePath,
                "-formatNotices"
            ),
            readyTimeoutMs = READY_TIMEOUT_MS,
            // Psiphon's SOCKS server takes any user name and password as transport arguments; it
            // cannot check a login, so none is sent.
            socksAuth = false,
            // The SOCKS port opens at once; traffic only passes once a tunnel is up.
            readyLine = TUNNEL_UP
        )
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
        const val KEY_ENGINE = "engine"
        const val KEY_REGION = "region"
        const val CONFIG_FILE = "psiphon.config"
        const val DATA_DIR = "data"
        /** The port opens early (see the class comment); this only covers a slow first start. */
        const val READY_TIMEOUT_MS = 60_000L
        /** The notice Psiphon prints when it has at least one tunnel. */
        val TUNNEL_UP = Regex("Tunnels.*\"count\":\\s*[1-9]")

        const val MISSING_SETTINGS =
            "Psiphon needs this build's Psiphon network settings, which are not included. Use a build that ships them."

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
            "UpstreamProxyURL"
        )

        /** A new Psiphon profile; [region] is a two-letter country code, or empty for any. */
        fun profile(region: String = ""): VlessProfile {
            val code = region.trim().uppercase()
            val extras = JSONObject().put(KEY_ENGINE, ID).put(KEY_REGION, code)
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

        fun region(profile: VlessProfile): String =
            ProfileExtras.read(profile).optString(KEY_REGION).trim().uppercase()

        fun validRegion(region: String): Boolean = region.isEmpty() || REGION.matches(region)

        /** Reads [ASSET_PATH] through [open] (an AssetManager's `open`); null when the asset is absent. */
        fun assetProvider(open: (String) -> InputStream): () -> String? = {
            try {
                open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: FileNotFoundException) {
                null
            }
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

        private fun hasUrls(settings: JSONObject, key: String): Boolean =
            (settings.opt(key) as? JSONArray)?.length()?.let { it > 0 } == true

        /** The config file ConsoleClient reads: the build's settings plus what this connection needs. */
        fun buildConfig(networkSettings: JSONObject, region: String, socksPort: Int, dataDir: File): JSONObject {
            val config = JSONObject(networkSettings.toString())
            LOCAL_FIELDS.forEach { config.remove(it) }
            config.put("LocalSocksProxyPort", socksPort)
            // Xray only needs SOCKS; an HTTP proxy would be one more open local port.
            config.put("DisableLocalHTTPProxy", true)
            config.put("DataRootDirectory", dataDir.absolutePath)
            // The program is pinned and license-tracked; it must not replace itself.
            config.put("EnableUpgradeDownload", false)
            if (region.isNotEmpty()) config.put("EgressRegion", region)
            if (!config.has("DNSResolverAlternateServers")) {
                config.put("DNSResolverAlternateServers", JSONArray(DEFAULT_DNS_SERVERS))
            }
            if (!config.has("ClientPlatform")) config.put("ClientPlatform", "Android")
            if (!config.has("EmitDiagnosticNotices")) config.put("EmitDiagnosticNotices", false)
            return config
        }
    }
}
