package com.example.panels.servers.panel

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Official HiddifyPanel v14.0.5 API contract, audited at a3a08d66.
 * No redirects: an admin key must never be forwarded to another origin.
 * Mutations are never automatically retried after an ambiguous response. */
class HiddifyQuickSetup(client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

    data class Admin(val origin: String, val path: String, val key: String)
    data class TelegramLink(val title: String, val link: String, val web: Boolean)

    fun createUser(adminUrl: String, name: String, days: Int, quotaGb: Int, userId: String): String {
        require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "Enter a user name (up to 80 characters)" }
        require(days in 1..10000 && quotaGb in 1..1000000) { "Enter valid days and traffic quota" }
        require(UUID.fromString(userId).toString() == userId)
        val admin = admin(adminUrl)
        val endpoint = "${admin.origin}/${admin.path}/api/v2/admin/users/"
        val metadata = json(Request.Builder().url(endpoint).header("Hiddify-API-Key", admin.key).build())
        // Reusing the operation UUID lets a retry after a lost response recover, without creating another user.
        val existing = metadata.optJSONArray("users")
        var found = false
        if (existing != null) for (i in 0 until existing.length()) {
            if (existing.optJSONObject(i)?.optString("uuid") == userId) found = true
        }
        require(found || metadata.optBoolean("can_add", false)) { "The panel does not permit creating another user" }
        val domains = metadata.optJSONArray("link_domains") ?: error("Panel did not provide a user subscription domain")
        val base = (0 until domains.length()).mapNotNull { domains.optJSONObject(it)?.optString("base") }
            .firstOrNull { sameOriginBase(it, admin.origin) } ?: error("No same-origin user domain; configure a valid panel domain first")
        val userUrl = "${base.trimEnd('/')}/$userId/"
        if (!found) {
            val body = JSONObject().put("uuid", userId).put("name", name.trim())
                .put("package_days", days).put("usage_limit_GB", quotaGb).put("enable", true)
                .toString().toRequestBody("application/json".toMediaType())
            val result = json(Request.Builder().url(endpoint).header("Hiddify-API-Key", admin.key).post(body).build())
            check(result.optJSONObject("user")?.optString("uuid") == userId) {
                "User creation was not confirmed. Retry with the same form to check its status."
            }
        }
        return userUrl
    }

    fun telegram(userUrl: String): List<TelegramLink> {
        val base = userBase(userUrl)
        val request = Request.Builder().url("${base}api/v2/user/mtproxies/").build()
        val payload = requestText(request)
        val array = org.json.JSONArray(payload)
        require(array.length() <= 200) { "Too many Telegram links" }
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val link = item.optString("link")
            val uri = runCatching { URI(link) }.getOrNull() ?: return@mapNotNull null
            if (link.length > 8192 || link.any { it.isISOControl() } || uri.scheme != "tg" || uri.host !in setOf("proxy", "webproxy", "socks")) null
            else TelegramLink(if (uri.host == "webproxy") "Telegram Web proxy" else "Telegram ${uri.host}", link, uri.host == "webproxy")
        }.distinctBy { it.link }
    }

    private fun json(request: Request) = JSONObject(requestText(request))
    private fun requestText(request: Request): String = try {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(when (response.code) {
                401, 403 -> "Hiddify denied access. Recover the admin link and check account permissions."
                404 -> "This Hiddify API or Telegram service is unavailable. Open the official panel to configure it."
                else -> "Hiddify request failed (HTTP ${response.code}). Check status in the panel before retrying a creation."
            })
            val input = response.body?.byteStream() ?: throw IOException("Empty panel response")
            val bytes = input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= 1024 * 1024) { "Panel response exceeds limit" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            require(bytes.size <= 1024 * 1024) { "Panel response exceeds limit" }
            bytes.toString(Charsets.UTF_8)
        }
    } catch (e: javax.net.ssl.SSLException) {
        throw IOException(com.example.vpn.subscription.SubscriptionInput.failure(e))
    }

    companion object {
        fun admin(url: String): Admin {
            require(HiddifyPanelProvider.validatedAdminUrl(url) != null) { "Recover a valid HTTPS admin link first" }
            val uri = URI(url)
            val parts = uri.path.split('/').filter { it.isNotEmpty() }
            require(parts.size == 2 && runCatching { UUID.fromString(parts[1]).toString() == parts[1] }.getOrDefault(false)) {
                "This admin link does not contain an API identity. Recover the original link over SSH."
            }
            return Admin("https://${uri.rawAuthority}", parts[0], parts[1])
        }

        fun userBase(url: String): String {
            val uri = URI(url.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in listOf(-1, 443)) { "Enter a secure Hiddify user link" }
            val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            require(parts.size >= 2 && parts[0].matches(Regex("[A-Za-z0-9_-]{1,256}")) &&
                runCatching { UUID.fromString(parts[1]).toString() == parts[1] }.getOrDefault(false)) { "Enter the Hiddify user page, not the admin panel" }
            return "https://${uri.rawAuthority}/${parts[0]}/${parts[1]}/"
        }

        internal fun sameOriginBase(base: String, origin: String): Boolean = runCatching {
            val uri = URI(base)
            uri.scheme == "https" && "https://${uri.rawAuthority}" == origin && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.matches(Regex("/[A-Za-z0-9_-]{1,256}/"))
        }.getOrDefault(false)
    }
}
