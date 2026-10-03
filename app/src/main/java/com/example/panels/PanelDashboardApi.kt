package com.example.panels

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Management calls never follow redirects with bearer credentials. */
class PanelDashboardApi {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    private fun call(url: String, token: String, body: JSONObject? = null): JSONObject {
        require(url.startsWith("https://", ignoreCase = true)) { "Panel management API requires HTTPS" }
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(builder.build()).execute().use {
            check(it.isSuccessful) { "Request failed (HTTP ${it.code}). Check connectivity and permissions." }
            val text = it.body?.string().orEmpty()
            if (text.isBlank()) return JSONObject()
            val result = JSONObject(text)
            check(result.optBoolean("success", true)) { "API rejected this operation: ${result.optString("msg")}" }
            result
        }
    }

    fun serverSnapshot(panel: ManagedPanel): PanelSnapshot {
        require(panel.apiToken.isNotBlank()) { "This panel needs its 3x-ui API token" }
        val base = panel.url.trimEnd('/').removeSuffix("/panel")
        val status = call("$base/panel/api/server/status", panel.apiToken).getJSONObject("obj")
        val inbounds = call("$base/panel/api/inbounds/list", panel.apiToken).optJSONArray("obj") ?: JSONArray()
        val mem = status.optJSONObject("mem")
        val disk = status.optJSONObject("disk")
        return PanelSnapshot("Online", listOf(
            "CPU" to "%.1f%%".format(status.optDouble("cpu", 0.0)),
            "Memory" to usage(mem), "Disk" to usage(disk),
            "Inbounds" to inbounds.length().toString(),
            "Uptime" to "${status.optLong("uptime") / 3600} h"
        ), (status.optDouble("cpu", 0.0) / 100).toFloat().coerceIn(0f, 1f))
    }

    fun workerSnapshot(panel: ManagedPanel, account: String, token: String): PanelSnapshot {
        val root = workerRoot(panel, account)
        val sub = call("$root/subdomain", token).getJSONObject("result")
        val settings = call("$root/settings", token).getJSONObject("result")
        return PanelSnapshot(if (sub.optBoolean("enabled")) "Published" else "Disabled", listOf(
            "Worker" to workerName(panel),
            "Bindings" to (settings.optJSONArray("bindings")?.length() ?: 0).toString(),
            "Compatibility" to settings.optString("compatibility_date", "Unknown"),
            "Preview URLs" to if (sub.optBoolean("previews_enabled")) "Enabled" else "Disabled"
        ), if (sub.optBoolean("enabled")) 1f else 0f)
    }

    fun setWorkerEnabled(panel: ManagedPanel, account: String, token: String, enabled: Boolean) {
        call("${workerRoot(panel, account)}/subdomain", token,
            JSONObject().put("enabled", enabled).put("previews_enabled", false))
    }

    /**
     * Cloudflare requires a bootstrap token with API Tokens Write. The bootstrap
     * token is supplied for this operation only and is never persisted.
     */
    fun createDeploymentToken(parent: String, account: String): CloudflareTokenResult {
        require(parent.isNotBlank()) { "Paste the Cloudflare Create Additional Tokens bootstrap token" }
        require(account.matches(Regex("[a-fA-F0-9]{32}"))) { "Enter the 32-character Cloudflare account ID" }
        call("$CF/user/tokens/verify", parent)

        val groups = call("$CF/user/tokens/permission_groups", parent).getJSONArray("result")
        val needed = listOf("Workers Scripts Write", "Workers KV Storage Write", "Account Settings Read")
        val permissions = JSONArray()
        needed.forEach { name ->
            val group = (0 until groups.length()).map { groups.getJSONObject(it) }
                .firstOrNull { it.optString("name") == name }
                ?: error("Cloudflare did not expose permission: $name")
            permissions.put(JSONObject().put("id", group.getString("id")))
        }

        val policy = JSONObject().put("effect", "allow")
            .put("resources", JSONObject().put("com.cloudflare.api.account.$account", "*"))
            .put("permission_groups", permissions)
        val expiresOn = Instant.now().plusSeconds(30L * 86400).toString()
        val result = call("$CF/user/tokens", parent, JSONObject()
            .put("name", "Maximus BPB deployment")
            .put("expires_on", expiresOn)
            .put("policies", JSONArray().put(policy))).getJSONObject("result")

        return CloudflareTokenResult(
            value = result.getString("value"),
            accountId = account,
            expiresOn = expiresOn
        )
    }

    private fun workerName(panel: ManagedPanel): String {
        val host = java.net.URI(panel.url).host.orEmpty()
        require(host.endsWith(".workers.dev")) { "Open custom-domain Workers in Cloudflare dashboard" }
        return host.substringBefore('.').also { require(it.matches(Regex("[a-z0-9-]+"))) }
    }

    private fun workerRoot(panel: ManagedPanel, account: String): String {
        val acc = account.ifBlank { panel.accountId }
        require(acc.matches(Regex("[a-fA-F0-9]{32}"))) { "Enter a valid Cloudflare account ID" }
        return "$CF/accounts/$acc/workers/scripts/${workerName(panel)}"
    }

    private fun usage(o: JSONObject?): String = if (o == null) "Unavailable" else
        "${o.optLong("current") / 1048576} / ${o.optLong("total") / 1048576} MB"

    companion object { private const val CF = "https://api.cloudflare.com/client/v4" }
}

data class CloudflareTokenResult(
    val value: String,
    val accountId: String,
    val expiresOn: String
)

data class PanelSnapshot(
    val status: String,
    val metrics: List<Pair<String, String>>,
    val utilization: Float,
    val checkedAt: Long = System.currentTimeMillis()
)
