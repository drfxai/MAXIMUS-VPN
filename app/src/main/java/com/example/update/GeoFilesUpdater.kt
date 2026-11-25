package com.example.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.xray.GeoAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Updates the Iran GeoFiles (see [GeoAssets]) from the latest Chocolate4U/Iran-v2ray-rules release.
 *
 * Only the three files the app bundles are fetched, from the release's own download URLs. Each must match the
 * SHA-256 in the release's published `<name>.sha256sum` file (and the GitHub asset digest when the API gives
 * one); any mismatch, missing checksum, wrong size or oversize body rejects the whole update and leaves the
 * current files untouched. Verified files are swapped in atomically by [GeoAssets.installUpdate].
 *
 * "Update via proxy" binds the download to Android's VPN network, so it travels through the active tunnel and
 * never falls back to the direct network.
 */
object GeoFilesUpdater {
    const val REPO = "Chocolate4U/Iran-v2ray-rules"
    const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    /**
     * The release pages on github.com: `releases/latest` redirects to `releases/tag/<tag>` and every asset is at
     * `releases/download/<tag>/<name>`. Used when the API refuses or fails: its unauthenticated limit (60 requests an
     * hour per address) is shared by everyone behind the same carrier NAT or WARP / Lantern exit.
     */
    const val WEB_BASE = "https://github.com/$REPO"
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_CHECKSUM_BYTES = 4L * 1024
    private const val PREFS = "maximus_geo_update"
    private const val KEY_VIA_VPN = "via_vpn"
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    data class Asset(val url: String, val size: Long, val digest: String?)
    data class Release(val tag: String, val assets: Map<String, Asset>)

    sealed class Outcome {
        abstract val tag: String
        data class UpToDate(override val tag: String) : Outcome()
        data class Updated(override val tag: String, val previous: String) : Outcome()
    }

    fun viaVpn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VIA_VPN, true)

    fun setViaVpn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VIA_VPN, value).apply()
    }

    /** The tag of the GeoFiles Xray reads now (installed update or bundled set). */
    fun currentTag(context: Context): String = GeoAssets.activeManifest(context)?.let(GeoAssets::tagOf).orEmpty()

    suspend fun update(context: Context, viaVpn: Boolean): Outcome = withContext(Dispatchers.IO) {
        val client = client(context, viaVpn)
        val manifest = GeoAssets.activeManifest(context) ?: error("Bundled GeoFiles manifest missing")
        val stagingDir = File(context.filesDir, GeoAssets.ASSET_DIR).also { it.mkdirs() }
        val result = download(client, LATEST_API, manifest, stagingDir, webBase = WEB_BASE) {
            if (!viaVpn) UpdateNetworkPolicy.requireAllowed(context)
        }
        when (result) {
            is Downloaded.Current -> Outcome.UpToDate(result.tag)
            is Downloaded.Staged -> try {
                GeoAssets.installUpdate(context, result.manifest, result.files)
                Outcome.Updated(result.tag, GeoAssets.tagOf(manifest))
            } finally {
                result.files.values.forEach { it.delete() }
            }
        }
    }

    sealed class Downloaded {
        data class Current(val tag: String) : Downloaded()
        data class Staged(val tag: String, val manifest: String, val files: Map<String, File>) : Downloaded()
    }

    /**
     * Network part, testable with a mock server: reads the release at [apiUrl], and when it is newer than
     * [currentManifest] downloads and verifies the three files into [stagingDir]. Throws on any failure, with
     * the staged files removed.
     */
    suspend fun download(
        client: OkHttpClient,
        apiUrl: String,
        currentManifest: String,
        stagingDir: File,
        /** The repository's github.com base ([WEB_BASE]); when set, a failed API read falls back to the release pages. */
        webBase: String? = null,
        guard: () -> Unit = {}
    ): Downloaded {
        guard()
        val release = try {
            parseRelease(fetchText(client, apiUrl, 512 * 1024))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (webBase == null) throw e
            currentCoroutineContext().ensureActive()
            guard()
            try {
                releaseFromWeb(client, webBase)
            } catch (web: kotlinx.coroutines.CancellationException) {
                throw web
            } catch (web: Exception) {
                throw IllegalStateException("Release API: ${e.message}; release page: ${web.message}", web)
            }
        }
        val newManifest = manifestFor(currentManifest, release.tag, emptyMap())
        if (!GeoAssets.isNewer(newManifest, currentManifest)) return Downloaded.Current(GeoAssets.tagOf(currentManifest))

        val staged = linkedMapOf<String, File>()
        try {
            val hashes = linkedMapOf<String, String>()
            for (name in GeoAssets.FILES.keys) {
                currentCoroutineContext().ensureActive()
                guard()
                val asset = release.assets[name] ?: error("Release ${release.tag} has no $name")
                val sumAsset = release.assets["$name.sha256sum"] ?: error("Release ${release.tag} publishes no checksum for $name")
                val published = parseChecksum(fetchText(client, sumAsset.url, MAX_CHECKSUM_BYTES), name)
                asset.digest?.removePrefix("sha256:")?.let { apiDigest ->
                    check(apiDigest.equals(published, ignoreCase = true)) { "$name: release checksum file and GitHub digest disagree" }
                }
                val file = File(stagingDir, "$name.download")
                staged[name] = file
                val actual = fetchFile(client, asset.url, asset.size, file, guard)
                check(actual.equals(published, ignoreCase = true)) {
                    "$name checksum mismatch: got $actual, release publishes $published"
                }
                hashes[name] = published.lowercase()
            }
            return Downloaded.Staged(release.tag, manifestFor(currentManifest, release.tag, hashes), staged)
        } catch (e: Throwable) {
            staged.values.forEach { it.delete() }
            throw e
        }
    }

    /** Pure: the latest-release API JSON → tag and assets. */
    fun parseRelease(json: String): Release {
        val root = JSONObject(json)
        val tag = root.optString("tag_name")
        check(tag.isNotBlank() && tag.all(Char::isDigit)) { "Unexpected GeoFiles release tag '$tag'" }
        val list = root.optJSONArray("assets") ?: error("Release $tag has no assets")
        val assets = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.associate { a ->
            a.optString("name") to Asset(
                url = a.optString("browser_download_url"),
                size = a.optLong("size", -1),
                digest = a.optString("digest").takeIf { it.startsWith("sha256:") }
            )
        }
        assets.values.forEach { check(it.url.startsWith("https://")) { "Insecure GeoFiles download URL" } }
        return Release(tag, assets)
    }

    /** Pure: the tag in a `.../releases/tag/<tag>` redirect target; only the numeric tags this repository publishes. */
    fun tagFromLocation(location: String): String {
        val raw = location.substringAfter("/releases/tag/", "").substringBefore('?').substringBefore('#').trimEnd('/')
        val tag = java.net.URLDecoder.decode(raw, "UTF-8")
        check(tag.isNotBlank() && tag.length <= 32 && tag.all(Char::isDigit)) { "Unexpected GeoFiles release tag '${tag.take(40)}'" }
        return tag
    }

    /**
     * Pure: the release [tag] as its github.com download URLs under [webBase]. Sizes are unknown here (-1): a download
     * is then capped at the largest accepted size and still has to match the published SHA-256.
     */
    fun webRelease(webBase: String, tag: String): Release {
        check(webBase.startsWith("https://")) { "Insecure GeoFiles download URL" }
        val assets = GeoAssets.FILES.keys.flatMap { name ->
            listOf(name, "$name.sha256sum").map { it to Asset("$webBase/releases/download/$tag/$it", -1, null) }
        }.toMap()
        return Release(tag, assets)
    }

    private fun releaseFromWeb(client: OkHttpClient, webBase: String): Release {
        val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        noRedirect.newCall(Request.Builder().url("$webBase/releases/latest").build()).execute().use { reply ->
            check(reply.code in 300..399) { "HTTP ${reply.code} from ${reply.request.url.host}" }
            val location = reply.header("Location") ?: error("The latest-release page did not name a release")
            return webRelease(webBase, tagFromLocation(location))
        }
    }

    /**
     * Pure: the SHA-256 for [name] in a `sha256sum` file ("<hex>  [path/]<name>" lines, or a bare hash).
     * Throws when it is absent or not a SHA-256.
     */
    fun parseChecksum(text: String, name: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val hash = lines.firstNotNullOfOrNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            val file = parts.getOrNull(1)?.trimStart('*')?.substringAfterLast('/')
            parts[0].takeIf { file == name || (file == null && lines.size == 1) }
        } ?: error("No checksum for $name")
        check(SHA256.matches(hash)) { "Malformed checksum for $name" }
        return hash.lowercase()
    }

    /** Pure: [base] with the new tag and file hashes, keeping its source and license. */
    fun manifestFor(base: String, tag: String, hashes: Map<String, String>): String {
        val old = runCatching { JSONObject(base) }.getOrDefault(JSONObject())
        val files = JSONObject()
        hashes.forEach { (k, v) -> files.put(k, v) }
        return JSONObject()
            .put("source", old.optString("source", REPO).ifBlank { REPO })
            .put("license", old.optString("license", "GPL-3.0"))
            .put("tag", tag)
            .put("files", files)
            .toString(2)
    }

    private fun fetchText(client: OkHttpClient, url: String, max: Long): String {
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { reply ->
            check(reply.isSuccessful) { "HTTP ${reply.code} from ${reply.request.url.host}" }
            val body = reply.body ?: error("Empty reply")
            check(body.contentLength() <= max) { "Reply too large" }
            val bytes = body.byteStream().use { it.readNBytesCompat(max + 1) }
            check(bytes.size <= max) { "Reply too large" }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private suspend fun fetchFile(client: OkHttpClient, url: String, size: Long, out: File, guard: () -> Unit): String {
        // -1: size unknown (release pages); the download is then capped at MAX_FILE_BYTES and checked by its hash.
        check(size == -1L || size in 1..MAX_FILE_BYTES) { "Unexpected GeoFiles size $size" }
        val limit = if (size < 0) MAX_FILE_BYTES else size
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(url).build()).execute().use { reply ->
            check(reply.isSuccessful) { "HTTP ${reply.code} downloading ${out.name.removeSuffix(".download")}" }
            val body = reply.body ?: error("Empty download")
            check(body.contentLength() <= limit) { "Download larger than published" }
            var count = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        count += n
                        check(count <= limit) { "Download larger than published" }
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                }
            }
            guard()
            check(count > 0 && (size < 0 || count == size)) { "Truncated download" }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size.toLong(), limit - out.size()).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The engine's local SOCKS port while the app is left out of its own VPN; null when the VPN network is usable. */
    private fun engineProxy(): com.example.vpn.verify.Socks5.Endpoint? =
        com.example.vpn.verify.TunPathStatus.engineProxy?.takeIf { com.example.vpn.verify.TunPathStatus.selfExcluded }

    /**
     * A client whose connections go through an engine's local SOCKS5 port, names resolved by the engine.
     *
     * HTTP/1.1 only. Build 175 report: the GeoFiles update still failed through Serverless and Aether after the SOCKS
     * change. Every name had the same placeholder address (0.0.0.0), and OkHttp coalesces HTTP/2 connections whose
     * address matches and whose certificate covers the new name: api.github.com's certificate covers github.com, so
     * the asset requests to github.com were sent on the api.github.com connection, to a server that does not serve
     * them. Placeholders now differ per name ([com.example.vpn.verify.Socks5SocketFactory.remoteDns]) and HTTP/2 is
     * off for this client, so a connection only ever carries requests for the name it was opened to.
     */
    internal fun socksClient(proxy: com.example.vpn.verify.Socks5.Endpoint, builder: OkHttpClient.Builder): OkHttpClient =
        builder.proxy(java.net.Proxy.NO_PROXY)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .socketFactory(com.example.vpn.verify.Socks5SocketFactory(proxy))
            .dns(object : Dns {
                override fun lookup(hostname: String) = com.example.vpn.verify.Socks5SocketFactory.remoteDns(hostname)
            }).build()

    private fun client(context: Context, viaVpn: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        if (viaVpn) {
            // Build 174: with an engine program (Aether, Serverless, Psiphon…) the app is left out of its own VPN, so
            // binding to the VPN network is refused and the upkeep's GeoFiles update failed while connected. The
            // engine's local port carries the download through the tunnel instead; names are resolved by the engine.
            engineProxy()?.let { proxy -> return socksClient(proxy, builder) }
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: error("Cannot determine network routing")
            val vpn = manager.allNetworks.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } ?: error("Update via proxy needs an active VPN connection. Connect first, or turn off \"Update via proxy\".")
            // A bind refusal (EPERM) here means this connection mode keeps the app outside its own VPN.
            runCatching { vpn.socketFactory.createSocket().close() }.onFailure {
                error("This connection mode keeps the app outside its VPN, so it cannot download through it. Turn off \"Update via proxy\" to download directly.")
            }
            builder.socketFactory(vpn.socketFactory).dns(object : Dns {
                override fun lookup(hostname: String) = vpn.getAllByName(hostname).toList()
            })
        }
        return builder.build()
    }
}
