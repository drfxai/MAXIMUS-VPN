package com.example.vpn.lab.research

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Release notes from one allowlisted GitHub repository, read through the public API (no token, no download of
 * release files). Only the repositories in [ALLOWED] can be read.
 */
class GitHubReleaseSource(
    val repo: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val base: String = "https://api.github.com"
) : ResearchSource {
    init { require(repo in ALLOWED) { "Not an allowlisted research source" } }

    override fun releases(): List<SourceRelease> {
        val request = Request.Builder().url("$base/repos/$repo/releases?per_page=5")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "MaximusVPN-LAB").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GitHub answered HTTP ${r.code}")
            val body = r.body?.string().orEmpty()
            require(body.length < 2_000_000) { "Response too large" }
            val a = JSONArray(body)
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { !it.optBoolean("draft") }.map { o ->
                SourceRelease(o.optString("tag_name"), o.optString("name"), o.optString("body").take(20_000),
                    o.optString("html_url").takeIf { it.startsWith("https://github.com/$repo/") }.orEmpty(), parse(o.optString("published_at")))
            }
        }
    }

    private fun parse(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    }.getOrDefault(0L)

    companion object {
        val ALLOWED = setOf("XTLS/Xray-core", "XTLS/libXray")
    }
}
