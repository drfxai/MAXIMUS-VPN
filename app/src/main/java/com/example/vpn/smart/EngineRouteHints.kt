package com.example.vpn.smart

import android.content.Context
import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.PathRef
import org.json.JSONArray
import org.json.JSONObject

/** Only profile references, never credentials. Historical success orders tests; it cannot verify a connection. */
object EngineRouteHints {
    private const val TTL_MS = 24 * 60 * 60_000L
    private fun prefs(context: Context) = context.getSharedPreferences("engine-route-hints", Context.MODE_PRIVATE)
    private fun key(network: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(network.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun rows(context: Context): List<JSONObject> = runCatching {
        val a = JSONArray(prefs(context).getString("hints", "[]"))
        (0 until minOf(a.length(), 32)).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
    @Synchronized fun record(context: Context, network: String, profile: VlessProfile, now: Long = System.currentTimeMillis()) {
        val net = key(network); val ref = PathRef.of(profile)
        val kept = rows(context).filter { it.optString("net") != net || it.optString("ref") != ref }
            .filter { now - it.optLong("at") in 0..TTL_MS }.takeLast(31)
        val a = JSONArray()
        kept.forEach { a.put(it) }
        a.put(JSONObject().put("net", net).put("ref", ref).put("at", now))
        prefs(context).edit().putString("hints", a.toString()).apply()
    }
    fun preferred(context: Context, network: String, profiles: List<VlessProfile>, now: Long = System.currentTimeMillis()): List<VlessProfile> {
        val net = key(network)
        val refs = rows(context).filter { it.optString("net") == net && now - it.optLong("at") in 0..TTL_MS }
            .sortedByDescending { it.optLong("at") }.map { it.optString("ref") }
        return refs.mapNotNull { ref -> profiles.firstOrNull { PathRef.of(it) == ref } }.distinctBy { PathRef.of(it) }.take(1)
    }
}
