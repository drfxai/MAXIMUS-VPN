package com.example.panels.cloudflare

import android.content.Context
import com.example.data.security.SecureStorage

/**
 * Smart domains on the phone. The DNS token is encrypted with the app's Keystore key ([SecureStorage]),
 * under its own key so it never mixes with the Worker token. The names made and the chosen zone hold no
 * secrets and stay in plain prefs. Nothing here is logged.
 */
class SmartDomainsStore(context: Context) {
    private val secure = SecureStorage(context.applicationContext)
    private val prefs = context.applicationContext.getSharedPreferences("maximus_smart_domains", Context.MODE_PRIVATE)

    fun token(): String = secure.getAndDecrypt(TOKEN_KEY)

    fun saveToken(token: String) = secure.encryptAndSave(TOKEN_KEY, token.trim())

    /** "Forget token": removes it; the names made stay in Cloudflare and in the list. */
    fun forgetToken() = secure.encryptAndSave(TOKEN_KEY, "")

    fun names(): List<SmartNames.Saved> = SmartNames.fromJson(prefs.getString(NAMES_KEY, null) ?: "[]")

    fun saveNames(list: List<SmartNames.Saved>) {
        prefs.edit().putString(NAMES_KEY, SmartNames.toJson(list)).apply()
    }

    fun zoneId(): String = prefs.getString(ZONE_KEY, null).orEmpty()

    fun saveZoneId(id: String) {
        prefs.edit().putString(ZONE_KEY, id).apply()
    }

    private companion object {
        const val TOKEN_KEY = "cloudflare_dns_token_v1"
        const val NAMES_KEY = "names_v1"
        const val ZONE_KEY = "zone_id_v1"
    }
}
