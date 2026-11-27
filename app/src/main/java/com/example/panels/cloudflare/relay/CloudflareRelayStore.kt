package com.example.panels.cloudflare.relay

import android.content.Context
import com.example.data.security.SecureStorage
import org.json.JSONObject

/**
 * The relay on the phone. The key and the relay record (it holds the VLESS id and path) are encrypted with the app's
 * Keystore key ([SecureStorage]) under their own names; nothing secret is in plain prefs and nothing here is logged.
 * Plain prefs keep only the one-time sheet flag and the quota numbers.
 */
class CloudflareRelayStore(context: Context) {
    private val secure = SecureStorage(context.applicationContext)
    private val prefs = context.applicationContext.getSharedPreferences("maximus_cf_relay", Context.MODE_PRIVATE)

    fun token(): String = secure.getAndDecrypt(TOKEN_KEY)
    fun saveToken(token: String) = secure.encryptAndSave(TOKEN_KEY, token.trim())

    fun relay(): CloudflareRelay.Relay? = CloudflareRelay.fromJson(secure.getAndDecrypt(RELAY_KEY).takeIf { it.isNotBlank() })
    fun saveRelay(relay: CloudflareRelay.Relay) = secure.encryptAndSave(RELAY_KEY, CloudflareRelay.toJson(relay))

    /** Disconnect: the key and the relay record leave the phone (the Worker was deleted first). */
    fun clear() {
        secure.encryptAndSave(TOKEN_KEY, "")
        secure.encryptAndSave(RELAY_KEY, "")
        prefs.edit().remove(QUOTA_KEY).remove(LOCAL_KEY).apply()
    }

    var promptShown: Boolean
        get() = prefs.getBoolean(PROMPT_KEY, false)
        set(value) { prefs.edit().putBoolean(PROMPT_KEY, value).apply() }

    fun quota(): CloudflareRelay.Quota? = runCatching {
        val o = JSONObject(prefs.getString(QUOTA_KEY, null) ?: return null)
        CloudflareRelay.Quota(o.getLong("used"), o.getString("day"), o.optBoolean("estimate"))
    }.getOrNull()

    fun saveQuota(q: CloudflareRelay.Quota) {
        prefs.edit().putString(QUOTA_KEY, JSONObject().put("used", q.used).put("day", q.day).put("estimate", q.estimate).toString()).apply()
    }

    /** One relay connection started by this phone today (the estimate when the key cannot read analytics). */
    @Synchronized
    fun countLocal(now: Long = System.currentTimeMillis()): CloudflareRelay.Quota {
        val day = CloudflareRelay.utcDay(now)
        val o = runCatching { JSONObject(prefs.getString(LOCAL_KEY, null) ?: "{}") }.getOrElse { JSONObject() }
        val used = (if (o.optString("day") == day) o.optLong("used") else 0L) + 1
        prefs.edit().putString(LOCAL_KEY, JSONObject().put("day", day).put("used", used).toString()).apply()
        return CloudflareRelay.Quota(used, day, estimate = true)
    }

    fun localToday(now: Long = System.currentTimeMillis()): CloudflareRelay.Quota {
        val day = CloudflareRelay.utcDay(now)
        val o = runCatching { JSONObject(prefs.getString(LOCAL_KEY, null) ?: "{}") }.getOrElse { JSONObject() }
        return CloudflareRelay.Quota(if (o.optString("day") == day) o.optLong("used") else 0L, day, estimate = true)
    }

    private companion object {
        const val TOKEN_KEY = "cloudflare_relay_token_v1"
        const val RELAY_KEY = "cloudflare_relay_record_v1"
        const val PROMPT_KEY = "prompt_shown_v1"
        const val QUOTA_KEY = "quota_v1"
        const val LOCAL_KEY = "local_count_v1"
    }
}
