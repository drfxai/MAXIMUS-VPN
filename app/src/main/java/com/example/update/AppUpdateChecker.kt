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
 * are sent, and nothing is downloaded or installed. Download only opens the GitHub release page.
 */
object AppUpdateChecker {

    const val RELEASES_URL = "https://github.com/drfxai/MAXIMUS-VPN/releases/latest"
    private const val API_URL = "https://api.github.com/repos/drfxai/MAXIMUS-VPN/releases/latest"
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/drfxai/MAXIMUS-VPN/update-info/update.json"
    private const val PAGE_PREFIX = "https://github.com/drfxai/MAXIMUS-VPN/releases"
    private const val MAX_BYTES = 256 * 1024

    /** How long "Later" hides the same release. */
    const val SNOOZE_MS = 24L * 60 * 60 * 1000

    data class ReleaseInfo(val version: String, val build: Int, val pageUrl: String)

    private val buildMarker = Regex("""maximus-build:\s*(\d{1,9})""")
    private val versionPattern = Regex("""[vV]?\d{1,4}(\.\d{1,4}){1,3}""")

    /** The GitHub API's latest-release JSON, or null when it carries no build number. */
    fun parseGitHubRelease(json: String): ReleaseInfo? = runCatching {
        val o = JSONObject(json)
        val build = buildMarker.find(o.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        release(o.optString("tag_name"), build, o.optString("html_url"))
    }.getOrNull()

    /** update.json: {"version": "V1.0.0", "build": 142, "url": "https://github.com/..."} */
    fun parseManifest(json: String): ReleaseInfo? = runCatching {
        val o = JSONObject(json)
        val build = o.optInt("build", 0)
        release(o.optString("version"), build, o.optString("url"))
    }.getOrNull()

    private fun release(version: String, build: Int, url: String): ReleaseInfo? {
        if (build <= 0) return null
        val v = version.trim().takeIf { versionPattern.matches(it) } ?: return null
        return ReleaseInfo("V" + v.trimStart('v', 'V'), build, safePageUrl(url))
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
