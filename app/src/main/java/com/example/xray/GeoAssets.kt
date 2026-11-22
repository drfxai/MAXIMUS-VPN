package com.example.xray

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The bundled Iran GeoFiles (Chocolate4U/Iran-v2ray-rules, see THIRD_PARTY_NOTICES.md) copied from
 * `assets/geo/` into `filesDir/geo/`, the directory Xray reads `geoip:` / `geosite:` rules from.
 *
 * They are stored under the names Xray looks up by default, so `geoip:ir` and `geosite:category-ir` work
 * in our presets and in imported configs:
 * - `geoip.dat`          ← `geoip-lite.dat`     (IR, PRIVATE, IRGFW-*-INJECTED-IPS)
 * - `geosite.dat`        ← `geosite-lite.dat`   (IR, CATEGORY-IR, ADS)
 * - `geoip-services.dat` ← `geoip-services.dat` (CLOUDFLARE, GOOGLE, TELEGRAM, ...; use `ext:geoip-services.dat:<tag>`)
 *
 * The copy happens only when a file is missing or `assets/geo/manifest.json` changed (a new bundle), and is
 * written to a temporary file first, so a half-written file is never used.
 *
 * Settings › Updates can replace them with a newer verified release ([installUpdate], see
 * `update/GeoFilesUpdater`). A downloaded set is kept until the APK bundles a newer tag; Xray reads it on the
 * next connect.
 */
object GeoAssets {
    const val ASSET_DIR = "geo"
    const val MANIFEST = "manifest.json"

    /** Asset name → name in [directory]. */
    val FILES: Map<String, String> = linkedMapOf(
        "geoip-lite.dat" to "geoip.dat",
        "geosite-lite.dat" to "geosite.dat",
        "geoip-services.dat" to "geoip-services.dat"
    )

    /** Categories in the bundled files, as Xray rule values. */
    const val GEOIP_IR = "geoip:ir"
    const val GEOIP_PRIVATE = "geoip:private"
    const val GEOSITE_IR = "geosite:ir"
    const val GEOSITE_CATEGORY_IR = "geosite:category-ir"
    const val GEOSITE_ADS = "geosite:ads"

    private const val STAMP = ".manifest"

    @Volatile private var appContext: Context? = null
    @Volatile private var ready: File? = null
    @Volatile private var reloadPending: String? = null
    private val lock = Any()

    /** Called from Application.onCreate; the copy itself runs on the caller's background thread via [ensureReady]. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The directory holding the GeoFiles, copying them first when needed, or null if they could not be
     * prepared (no context yet, or the copy failed). Safe to call from any thread; cheap once ready.
     */
    fun ensureReady(): File? {
        ready?.let { dir ->
            // Called on every Xray start: an update installed since the last connect is read from here now.
            reloadPending?.let { tag ->
                reloadPending = null
                XrayLogManager.i("GEO", "GeoFiles $tag loaded for this connection")
            }
            return dir
        }
        val context = appContext ?: return null
        synchronized(lock) {
            ready?.let { return it }
            val result = runCatching { prepare(context) }
                .onFailure { XrayLogManager.w("GEO", "GeoFiles not prepared: ${it.message}") }
                .getOrNull()
            ready = result
            return result
        }
    }

    private fun prepare(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR)
        val manifest = context.assets.open("$ASSET_DIR/$MANIFEST").use { it.readBytes() }.toString(Charsets.UTF_8)
        val expected = expectedHashes(manifest)
        val stamp = File(dir, STAMP)
        val installed = stamp.takeIf { it.isFile }?.readText()
        val current = installed != null && FILES.values.all { File(dir, it).length() > 0 } &&
            (installed == manifest || isNewer(installed, manifest))
        if (current) return dir

        dir.mkdirs()
        for ((asset, target) in FILES) {
            val out = File(dir, target)
            val tmp = File(dir, "$target.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("$ASSET_DIR/$asset").use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            expected[asset]?.let { want ->
                if (!hash.equals(want, ignoreCase = true)) {
                    tmp.delete()
                    error("$asset hash $hash does not match manifest $want")
                }
            }
            if (!tmp.renameTo(out)) {
                out.delete()
                check(tmp.renameTo(out)) { "could not move $target into place" }
            }
        }
        stamp.writeText(manifest)
        XrayLogManager.i("GEO", "GeoFiles ${tagOf(manifest)} copied to ${dir.path}")
        return dir
    }

    /** Pure, for tests: asset name → sha256 from the bundled manifest. */
    fun expectedHashes(manifest: String): Map<String, String> = runCatching {
        val files = JSONObject(manifest).optJSONObject("files") ?: return emptyMap()
        files.keys().asSequence().associateWith { files.optString(it) }.filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    fun tagOf(manifest: String): String = runCatching { JSONObject(manifest).optString("tag") }.getOrDefault("")

    /** Pure: [candidate] is a later release of the same source than [base] (tags are yyyyMMddHHmm). */
    fun isNewer(candidate: String, base: String): Boolean {
        val a = runCatching { JSONObject(candidate) }.getOrNull() ?: return false
        val b = runCatching { JSONObject(base) }.getOrNull() ?: return false
        if (a.optString("source") != b.optString("source")) return false
        val ta = a.optString("tag").toLongOrNull() ?: return false
        val tb = b.optString("tag").toLongOrNull() ?: return false
        return ta > tb
    }

    /** The manifest of the files Xray reads now: the installed stamp, else the bundled one; null before install. */
    fun activeManifest(context: Context): String? {
        val stamp = File(File(context.filesDir, ASSET_DIR), STAMP)
        stamp.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { context.assets.open("$ASSET_DIR/$MANIFEST").use { it.readBytes() }.toString(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * Installs a verified update into the GeoFiles directory of the running app: the bundled copy is prepared
     * first so the update is never overwritten by it, then [installFiles] swaps the files in.
     */
    fun installUpdate(context: Context, manifest: String, staged: Map<String, File>) {
        install(context)
        synchronized(lock) {
            val dir = ensureReady() ?: File(context.filesDir, ASSET_DIR).also { it.mkdirs() }
            installFiles(dir, manifest, staged)
            ready = dir
            reloadPending = tagOf(manifest)
        }
        XrayLogManager.i("GEO", "GeoFiles ${tagOf(manifest)} installed; Xray reads them on the next connect")
    }

    /**
     * Pure file work, for tests: every [staged] file (asset name → verified temporary file in [dir]) must hash
     * to [manifest]'s `files` entry, else nothing changes. The stamp is removed before the files move and
     * written last (temporary file + rename), so an interrupted install makes [prepare] recopy the bundle
     * instead of leaving a mixed set.
     */
    fun installFiles(dir: File, manifest: String, staged: Map<String, File>) {
        val expected = expectedHashes(manifest)
        require(staged.keys == FILES.keys && expected.keys.containsAll(FILES.keys)) { "Incomplete GeoFiles set" }
        for ((asset, file) in staged) {
            val hash = sha256(file)
            check(hash.equals(expected.getValue(asset), ignoreCase = true)) { "$asset checksum mismatch" }
        }
        dir.mkdirs()
        val stamp = File(dir, STAMP)
        if (stamp.exists()) check(stamp.delete()) { "could not clear GeoFiles stamp" }
        for ((asset, file) in staged) {
            val out = File(dir, FILES.getValue(asset))
            if (!file.renameTo(out)) {
                out.delete()
                check(file.renameTo(out)) { "could not move ${out.name} into place" }
            }
        }
        val tmp = File(dir, "$STAMP.tmp")
        tmp.writeText(manifest)
        if (!tmp.renameTo(stamp)) {
            stamp.delete()
            check(tmp.renameTo(stamp)) { "could not write GeoFiles stamp" }
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Points Xray at [dir] through the config's `env` block, which Xray-core applies (os.Setenv) before it
     * builds the routing rules. A location the config already sets is kept.
     */
    fun applyAssetLocation(config: JSONObject, dir: File) {
        val env = config.optJSONObject("env") ?: JSONObject().also { config.put("env", it) }
        if (env.has("xray.location.asset") || env.has("XRAY_LOCATION_ASSET")) return
        env.put("xray.location.asset", dir.absolutePath)
        env.put("XRAY_LOCATION_ASSET", dir.absolutePath)
    }
}
