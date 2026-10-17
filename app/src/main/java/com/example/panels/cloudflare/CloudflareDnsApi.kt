package com.example.panels.cloudflare

import com.example.panels.cloudflare.SmartDomains.Action
import com.example.panels.cloudflare.SmartDomains.Record
import com.example.panels.cloudflare.SmartDomains.Zone
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The few Cloudflare DNS calls smart subdomains need, with the user's own API token (Zone Read and DNS
 * Edit on their zone). The token is passed in for each call and never stored or logged here.
 * [base] is overridable for tests.
 */
class CloudflareDnsApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val base: String = "https://api.cloudflare.com/client/v4"
) {
    /** The zones this token can see (up to 50), active or not. */
    fun zones(token: String): List<Zone> {
        val r = call(get("$base/zones?per_page=50", token))
        val arr = r.optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Zone(it.optString("id"), it.optString("name").lowercase(), it.optString("status")) }
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    /** Every record at exactly [name] in [zone]. */
    fun records(token: String, zone: Zone, name: String): List<Record> {
        val url = "$base/zones/${zone.id}/dns_records".toHttpUrl().newBuilder()
            .addQueryParameter("name", SmartDomains.normalize(name)).addQueryParameter("per_page", "50").build().toString()
        val arr = call(get(url, token)).optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Record(it.optString("id"), it.optString("name"), it.optString("type"), it.optString("content"), it.optBoolean("proxied"))
            }
        }
    }

    /**
     * Makes [want] exist in [zone] without touching anything else at that name: creates it, turns the
     * proxy on or off on the same record, or leaves it. A conflict is returned unchanged.
     */
    fun ensure(token: String, zone: Zone, want: Record): Action {
        val action = SmartDomains.plan(want, records(token, zone, want.name))
        when (action) {
            is Action.Create -> call(send("POST", "$base/zones/${zone.id}/dns_records", token, body(want)))
            is Action.Update -> call(send("PATCH", "$base/zones/${zone.id}/dns_records/${action.id}", token,
                JSONObject().put("proxied", want.proxied)))
            is Action.Unchanged, is Action.Conflict -> Unit
        }
        return action
    }

    /** Deletes the record [want] describes, only when it still points where Maximus put it. */
    fun remove(token: String, zone: Zone, want: Record): Boolean {
        val match = records(token, zone, want.name).firstOrNull { it.type == want.type && it.content == want.content } ?: return false
        call(send("DELETE", "$base/zones/${zone.id}/dns_records/${match.id}", token, null))
        return true
    }

    private fun body(r: Record) = JSONObject().put("type", r.type).put("name", r.name).put("content", r.content)
        .put("proxied", r.proxied).put("ttl", 1).put("comment", "Maximus")

    private fun get(url: String, token: String) = Request.Builder().url(url).header("Authorization", "Bearer ${token.trim()}").get().build()

    private fun send(method: String, url: String, token: String, json: JSONObject?) = Request.Builder().url(url)
        .header("Authorization", "Bearer ${token.trim()}")
        .method(method, json?.toString()?.toRequestBody(JSON_TYPE))
        .build()

    private fun call(request: Request): JSONObject = http.newCall(request).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (!resp.isSuccessful || o?.optBoolean("success") != true) {
            val msg = o?.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
            error(msg ?: "Cloudflare answered HTTP ${resp.code}; check that the token has Zone Read and DNS Edit for this domain")
        }
        o
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}
