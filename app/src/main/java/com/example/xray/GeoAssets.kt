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
        ready?.let { return it }
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
        val current = stamp.isFile && stamp.readText() == manifest && FILES.values.all { File(dir, it).length() > 0 }
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

    private fun tagOf(manifest: String): String = runCatching { JSONObject(manifest).optString("tag") }.getOrDefault("")

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
