package com.example.update

import com.example.vpn.subscription.SubscriptionSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Finds out whether a newer release of the app is published on GitHub.
 *
 * A release is newer when its version number is higher, or when it has the same version and a higher
 * release build number (V1.0.0 is rebuilt under the same name). The release workflow stamps its run number into the APK (BuildConfig.RELEASE_BUILD), into the
 * release notes ("maximus-build: N") and into update.json on the update-info branch, which the
 * jsDelivr/Statically mirrors serve when GitHub is blocked.
 *
 * The check is a plain GET of public release information: no identifiers, cookies or device data
 * are sent. The app downloads APKs only when an official SHA-256 digest is available.
 * Android still requires user consent for installation.
 */
object AppUpdateChecker {

    const val RELEASES_URL = "https://github.com/drfxai/MAXIMUS-VPN/releases/latest"
    private const val API_URL = "https://api.github.com/repos/drfxai/MAXIMUS-VPN/releases/latest"
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/drfxai/MAXIMUS-VPN/update-info/update.json"
    private const val PAGE_PREFIX = "https://github.com/drfxai/MAXIMUS-VPN/releases"
    private const val MAX_BYTES = 256 * 1024

    /** How long "Later" hides the same release. */
    const val SNOOZE_MS = 24L * 60 * 60 * 1000

    data class ApkAsset(val url: String, val sha256: String, val size: Long)
    data class ReleaseInfo(val version: String, val build: Int, val pageUrl: String, val apk: ApkAsset? = null)

    private const val MAX_APK_BYTES = 700L * 1024 * 1024
    private val digestPattern = Regex("[a-fA-F0-9]{64}")
    private val apkNames = listOf("arm64-v8a", "universal")

    /** Refuse externally hosted or mismatched release files, even when metadata is compromised. */
    fun safeApkAsset(tag: String, flavor: String, url: String, digest: String, size: Long): ApkAsset? {
        if (!versionPattern.matches(tag) || flavor !in apkNames || size !in 1..MAX_APK_BYTES) return null
        val expected = "https://github.com/drfxai/MAXIMUS-VPN/releases/download/$tag/MAXIMUSVPN-$tag-$flavor.apk"
        val sha = digest.removePrefix("sha256:")
        return if (url == expected && digestPattern.matches(sha)) ApkAsset(url, sha.lowercase(), size) else null
    }

    private fun preferredApk(tag: String, assets: List<Pair<String, JSONObject>>): ApkAsset? {
        val preference = if (android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) apkNames else listOf("universal")
        for (flavor in preference) {
            val asset = assets.firstOrNull { it.first == flavor }?.second ?: continue
            safeApkAsset(tag, flavor, asset.optString("browser_download_url"), asset.optString("digest"), asset.optLong("size"))
                ?.let { return it }
        }
        return null
    }

    private val buildMarker = Regex("""maximus-build:\s*(\d{1,9})""")
    private val versionPattern = Regex("""[vV]?\d{1,4}(\.\d{1,4}){1,3}""")

    /** The GitHub API's latest-release JSON, or null when it carries no build number. */
    fun parseGitHubRelease(json: String): ReleaseInfo? = runCatching {
        val o = JSONObject(json)
        val build = buildMarker.find(o.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val tag = o.optString("tag_name")
        val assets = o.optJSONArray("assets")
        val candidates = (0 until (assets?.length() ?: 0)).mapNotNull { index ->
            val asset = assets?.optJSONObject(index) ?: return@mapNotNull null
            val flavor = apkNames.firstOrNull { asset.optString("name") == "MAXIMUSVPN-$tag-$it.apk" }
            flavor?.let { it to asset }
        }
        release(tag, build, o.optString("html_url"), preferredApk(tag, candidates))
    }.getOrNull()

    /** update.json: {"version": "V1.0.0", "build": 142, "url": "https://github.com/..."} */
    fun parseManifest(json: String): ReleaseInfo? = runCatching {
        val o = JSONObject(json)
        val build = o.optInt("build", 0)
        val tag = o.optString("version")
        val apks = o.optJSONObject("apks")
        val candidates = apkNames.mapNotNull { flavor ->
            val metadata = apks?.optJSONObject(flavor) ?: return@mapNotNull null
            flavor to JSONObject().put("browser_download_url", metadata.optString("url"))
                .put("digest", metadata.optString("sha256")).put("size", metadata.optLong("size"))
        }
        release(tag, build, o.optString("url"), preferredApk(tag, candidates))
    }.getOrNull()

    private fun release(version: String, build: Int, url: String, apk: ApkAsset? = null): ReleaseInfo? {
        if (build <= 0) return null
        val v = version.trim().takeIf { versionPattern.matches(it) } ?: return null
        return ReleaseInfo("V" + v.trimStart('v', 'V'), build, safePageUrl(url), apk)
    }

    /** Only this repository's release pages may be opened; anything else falls back to the releases page. */
    fun safePageUrl(url: String): String {
        val u = url.trim()
        val ok = u.startsWith("$PAGE_PREFIX/") && u.none { it.isWhitespace() || it == '"' || it == '<' || it == '>' } && !u.contains("..")
        return if (ok) u else RELEASES_URL
    }

    /**
     * Whether to show the pop-up: when the published version number is higher than the installed one,
     * or the version is the same and its release build is newer (a rebuilt release). Never for an
     * older version, and not while "Later" snoozes that same build.
     */
    fun shouldShow(
        release: ReleaseInfo,
        installedVersion: String,
        installedBuild: Int,
        snoozedBuild: Int,
        snoozedUntil: Long,
        now: Long
    ): Boolean {
        val byVersion = compareVersions(release.version, installedVersion)
        val newer = byVersion > 0 || (byVersion == 0 && installedBuild > 0 && release.build > installedBuild)
        if (!newer) return false
        return !(snoozedBuild == release.build && now < snoozedUntil)
    }

    /** Compares "V1.0.10" with "1.0.9-debug" by number: negative, zero or positive. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Every address that can answer, in order: the API first, then update.json and its CDN copies. */
    fun sources(): List<Pair<String, (String) -> ReleaseInfo?>> =
        listOf<Pair<String, (String) -> ReleaseInfo?>>(API_URL to ::parseGitHubRelease) +
            (listOf(MANIFEST_URL) + SubscriptionSources.derivedMirrors(MANIFEST_URL)).map { it to ::parseManifest }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** The newest published release, or null when no source answered. */
    suspend fun fetchLatest(download: (String) -> String? = ::get): ReleaseInfo? = withContext(Dispatchers.IO) {
        for ((url, parse) in sources()) {
            val body = runCatching { download(url) }.getOrNull() ?: continue
            parse(body)?.let { return@withContext it }
        }
        null
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val source = body.source()
            // request() is true when more than MAX_BYTES arrived: too big to be release info.
            if (source.request(MAX_BYTES + 1L)) return null
            return source.buffer.readUtf8()
        }
    }
}
