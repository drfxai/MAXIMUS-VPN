package com.example.vpn.subscription

import android.content.Context
import com.example.data.model.EngineType
import com.example.data.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

class SubscriptionPresetStore(context: Context) {
    private val secure = SecureStorage(context.applicationContext)
    private fun key(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return "subscription-preset-$id"
    }
    fun get(id: String): SubscriptionPreset = runCatching {
        val json = JSONObject(secure.getAndDecrypt(key(id), "{}"))
        val countries = json.optJSONArray("excluded") ?: JSONArray()
        SubscriptionPreset(json.optString("core").takeIf { it.isNotEmpty() }?.let { EngineType.valueOf(it) },
            (0 until countries.length()).map { countries.getString(it) }.toSet())
    }.getOrDefault(SubscriptionPreset())
    fun save(id: String, preset: SubscriptionPreset) {
        val json = JSONObject().put("excluded", JSONArray(preset.excludedCountries.sorted()))
        preset.core?.let { json.put("core", it.name) }
        secure.encryptAndSave(key(id), json.toString())
    }
}
