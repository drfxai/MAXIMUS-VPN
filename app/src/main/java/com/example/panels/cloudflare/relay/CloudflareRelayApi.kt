package com.example.panels.cloudflare.relay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Cloudflare API calls for the relay (Build 181 Phase 7). OkHttp with the platform's certificate checks (no custom
 * trust manager, no hostname bypass). The token goes only into the Authorization header of api.cloudflare.com calls;
 * it is never logged and never part of an exception message (Cloudflare's own error text is used).
 */
class CloudflareRelayApi(private val token: String) {
    class ApiException(val code: Int, val cloudflareCode: Int?, message: String) : Exception(message) {
        /** Cloudflare's "Authentication error" (10000) or a 401/403: the key lacks this permission. */
        val notAllowed: Boolean get() = code == 401 || code == 403 || cloudflareCode == 10000 || cloudflareCode == 9109
    }

    data class Account(val id: String, val name: String)
    data class Zone(val id: String, val name: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun request(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${token.trim()}")

    private fun call(req: Request): JSONObject = http.newCall(req).execute().use { resp -> parse(resp) }

    private fun parse(resp: Response): JSONObject {
        val body = resp.body?.string().orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
        if (!resp.isSuccessful || (json.has("success") && !json.optBoolean("success"))) {
            val err = json.optJSONArray("errors")?.optJSONObject(0)
            val cfCode = err?.optInt("code")?.takeIf { it != 0 }
            val msg = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Cloudflare answered HTTP ${resp.code}"
            throw ApiException(resp.code, cfCode, msg)
        }
        return json
    }

    /** GET /user/tokens/verify: true when the key is active. */
    fun verify(): Boolean = runCatching {
        call(request(CloudflareRelay.verifyUrl()).get().build()).optJSONObject("result")?.optString("status") == "active"
    }.getOrElse { e -> if (e is ApiException && (e.code == 400 || e.code == 401 || e.code == 403)) false else throw e }

    fun accounts(): List<Account> {
        val arr = call(request(CloudflareRelay.accountsUrl()).get().build()).optJSONArray("result") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { Account(it.optString("id"), it.optString("name")) }
            .filter { CloudflareRelay.validAccountId(it.id) }
    }

    /** Workers access probe (read): lists the account's scripts. */
    fun canReadWorkers(accountId: String): Boolean = runCatching {
        call(request(CloudflareRelay.scriptsUrl(accountId)).get().build()); true
    }.getOrElse { e -> if (e is ApiException && e.notAllowed) false else throw e }

    /** The account's "<name>.workers.dev", creating a random one when the account has none yet. */
    fun workersSubdomain(accountId: String, newName: () -> String): String {
        val existing = runCatching {
            call(request(CloudflareRelay.subdomainUrl(accountId)).get().build()).optJSONObject("result")?.optString("subdomain")
        }.getOrElse { e -> if (e is ApiException && e.notAllowed) throw e else null }
        if (!existing.isNullOrBlank()) return "$existing.workers.dev"
        repeat(3) {
            val candidate = newName()
            val made = runCatching {
                call(request(CloudflareRelay.subdomainUrl(accountId))
                    .put(JSONObject().put("subdomain", candidate).toString().toRequestBody(JSON)).build())
                    .optJSONObject("result")?.optString("subdomain")
            }.getOrElse { e -> if (e is ApiException && e.notAllowed) throw e else null }
            if (made != null) return "${made.ifBlank { candidate }}.workers.dev"
        }
        error("Cloudflare did not create a workers.dev subdomain")
    }

    /** Uploads the (pinned) script as [name] with the per-user secret bindings. */
    fun upload(accountId: String, name: String, script: String, secrets: CloudflareRelay.Secrets) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", null, CloudflareRelay.metadataJson(secrets).toRequestBody("application/json".toMediaType()))
            .addFormDataPart("worker.js", "worker.js", script.toRequestBody("application/javascript+module".toMediaType()))
            .build()
        call(request(CloudflareRelay.scriptUrl(accountId, name)).put(body).build())
    }

    /** Serves [name] on workers.dev (no preview URLs). */
    fun enableWorkersDev(accountId: String, name: String) {
        call(request(CloudflareRelay.scriptSubdomainUrl(accountId, name))
            .post(JSONObject().put("enabled", true).put("previews_enabled", false).toString().toRequestBody(JSON)).build())
    }

    /** Deletes the Worker (and its custom-domain attachments). A Worker that is already gone counts as deleted. */
    fun delete(accountId: String, name: String) {
        runCatching { call(request(CloudflareRelay.deleteUrl(accountId, name)).delete().build()) }
            .onFailure { e -> if (!(e is ApiException && e.code == 404)) throw e }
    }

    fun zones(): List<Zone> = runCatching {
        val arr = call(request(CloudflareRelay.zonesUrl()).get().build()).optJSONArray("result") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Zone(it.optString("id"), it.optString("name")) }
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }.getOrElse { e -> if (e is ApiException && e.notAllowed) emptyList() else throw e }

    /** Workers Custom Domain: [hostname] on [zoneId] served by [service] (Cloudflare adds DNS and the certificate). */
    fun attachDomain(accountId: String, hostname: String, zoneId: String, service: String) {
        val body = JSONObject().put("environment", "production").put("hostname", hostname)
            .put("service", service).put("zone_id", zoneId)
        call(request(CloudflareRelay.domainsUrl(accountId)).put(body.toString().toRequestBody(JSON)).build())
    }

    /** Today's requests on the account, or null when the key cannot read analytics. */
    fun requestsToday(accountId: String, dayUtc: String): Long? = runCatching {
        http.newCall(request(CloudflareRelay.graphqlUrl())
            .post(CloudflareRelay.analyticsQuery(accountId, dayUtc).toRequestBody(JSON)).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else CloudflareRelay.requestsFrom(resp.body?.string().orEmpty())
        }
    }.getOrNull()

    companion object {
        private val JSON = "application/json".toMediaType()

        /**
         * End-to-end check of a deployed relay: a VLESS request over wss://[host]/path (verified TLS) for an HTTP HEAD
         * to www.google.com:80. True when the relay answers with VLESS's response header and an HTTP status line.
         * A brand-new workers.dev name can take a few minutes to reach every edge, so false is not a failure.
         */
        fun probe(host: String, path: String, uuid: String, timeoutMs: Long = 10_000): Boolean {
            val client = OkHttpClient.Builder().connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val done = CountDownLatch(1)
            val ok = AtomicReference(false)
            val got = StringBuilder()
            val target = "www.google.com"
            val header = java.io.ByteArrayOutputStream().apply {
                write(0)
                write(java.nio.ByteBuffer.allocate(16).also {
                    val u = java.util.UUID.fromString(uuid)
                    it.putLong(u.mostSignificantBits); it.putLong(u.leastSignificantBits)
                }.array())
                write(0); write(1); write(0); write(80); write(2); write(target.length); write(target.toByteArray())
                write("HEAD / HTTP/1.1\r\nHost: $target\r\nConnection: close\r\n\r\n".toByteArray())
            }.toByteArray()
            val ws = client.newWebSocket(Request.Builder().url("wss://$host/$path").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(header.toByteString()) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    got.append(bytes.utf8())
                    if (got.contains("HTTP/")) { ok.set(true); done.countDown() }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.countDown() }
            })
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            ws.cancel()
            client.dispatcher.executorService.shutdown()
            return ok.get()
        }
    }
}
