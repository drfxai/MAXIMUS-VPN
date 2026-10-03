package com.example.panels

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CloudflareAccountInfo(
    val id: String,
    val name: String
)

object CloudflareTokenHelper {
    const val CLOUDFLARE_SIGNUP_URL = "https://dash.cloudflare.com/sign-up"

    /** Open token creation without silently granting all accounts/zones or unrelated edits.
     * A scoped template is available only after an account has been explicitly selected.
     */
    fun buildOneClickTokenUrl(tokenName: String = "Maximus-BPB-Worker"): String {
        // Official Cloudflare user-token template URL. Cloudflare pre-fills the
        // permissions/resources; the user only reviews and confirms creation.
        val permissions = """[{"key":"workers_scripts","type":"edit"},{"key":"workers_kv_storage","type":"edit"},{"key":"account_settings","type":"read"}]"""
        return "https://dash.cloudflare.com/profile/api-tokens" +
            "?permissionGroupKeys=${Uri.encode(permissions)}" +
            "&accountId=%2A" +
            "&zoneId=all" +
            "&name=${Uri.encode(tokenName)}"
    }


    /**
     * Opens the 1-Click Cloudflare token creation screen directly in the default browser.
     */
    fun openOneClickTokenUrl(context: Context, tokenName: String = "Maximus-BPB-Worker") {
        val url = buildOneClickTokenUrl(tokenName)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Opens the Cloudflare account registration page.
     */
    fun openSignUpUrl(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CLOUDFLARE_SIGNUP_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Verifies the token and lists accessible accounts.
     */
    fun verifyAndFetchAccounts(apiToken: String): Result<List<CloudflareAccountInfo>> = runCatching {
        val trimmed = apiToken.trim()
        require(trimmed.isNotBlank()) { "API Token cannot be empty" }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        // 1. Verify token
        val verifyReq = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/user/tokens/verify")
            .header("Authorization", "Bearer $trimmed")
            .get()
            .build()

        client.newCall(verifyReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            if (!resp.isSuccessful || !json.optBoolean("success", false)) {
                val errorMsg = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                    ?: "Invalid or expired Cloudflare API token (HTTP ${resp.code})"
                error(errorMsg)
            }
        }

        // 2. Discover accounts
        val accountsReq = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts?per_page=50")
            .header("Authorization", "Bearer $trimmed")
            .get()
            .build()

        client.newCall(accountsReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            if (!resp.isSuccessful || !json.optBoolean("success", false)) {
                error("Could not fetch accounts: ${json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")}")
            }
            val arr = json.optJSONArray("result") ?: return@use emptyList()
            val list = mutableListOf<CloudflareAccountInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name", "Account #${i + 1}")
                if (id.isNotBlank()) {
                    list.add(CloudflareAccountInfo(id = id, name = name))
                }
            }
            list
        }
    }
}
