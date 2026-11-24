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
        val result = download(client, LATEST_API, manifest, stagingDir) {
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
        guard: () -> Unit = {}
    ): Downloaded {
        guard()
        val release = parseRelease(fetchText(client, apiUrl, 512 * 1024))
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
        check(size in 1..MAX_FILE_BYTES) { "Unexpected GeoFiles size $size" }
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(url).build()).execute().use { reply ->
            check(reply.isSuccessful) { "HTTP ${reply.code} downloading ${out.name.removeSuffix(".download")}" }
            val body = reply.body ?: error("Empty download")
            check(body.contentLength() <= size) { "Download larger than published" }
            var count = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        count += n
                        check(count <= size) { "Download larger than published" }
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                }
            }
            guard()
            check(count == size) { "Truncated download" }
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

    private fun client(context: Context, viaVpn: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        if (viaVpn) {
            // Build 174: with an engine program (Aether, Serverless, Psiphon…) the app is left out of its own VPN, so
            // binding to the VPN network is refused and the upkeep's GeoFiles update failed while connected. The
            // engine's local port carries the download through the tunnel instead; names are resolved by the engine.
            engineProxy()?.let { proxy ->
                val factory = com.example.vpn.verify.Socks5SocketFactory(proxy)
                return builder.proxy(java.net.Proxy.NO_PROXY).socketFactory(factory).dns(object : Dns {
                    override fun lookup(hostname: String) = com.example.vpn.verify.Socks5SocketFactory.remoteDns(hostname)
                }).build()
            }
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
