package com.example

import com.example.panels.CloudflareInstallRequest
import com.example.panels.PanelProvisioner
import com.example.panels.PanelType
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Drives the BPB deployment against an in-memory Cloudflare/Worker so the generated login, the KV
 * write and the embedded settings can be checked without network access.
 */
class BpbProvisioningTest {

    private class FakeCloudflare(private val rejectLogin: Boolean = false) : Interceptor {
        val accountId = "0123456789abcdef0123456789abcdef"
        var workerSource = ""
        var kvPassword: String? = null
        var kvDeleted = false
        var loginAttempts = 0
        var lastLogin: JSONObject? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val url = req.url.toString()
            val path = req.url.encodedPath
            val body = bodyOf(req)

            fun ok(result: Any?): Response = json(req, JSONObject().put("success", true).put("result", result))

            return when {
                url.contains("/user/tokens/verify") -> ok(JSONObject().put("status", "active"))
                url.contains("/accounts?per_page") -> ok(JSONArray().put(JSONObject().put("id", accountId)))
                path.endsWith("/storage/kv/namespaces") && req.method == "POST" ->
                    ok(JSONObject().put("id", "ns-123"))
                path.endsWith("/storage/kv/namespaces/ns-123") && req.method == "DELETE" -> {
                    kvDeleted = true
                    ok(JSONObject())
                }
                path.endsWith("/workers/subdomain") && req.method == "GET" ->
                    ok(JSONObject().put("subdomain", "acme"))
                url.endsWith("/worker.js") -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(("x".repeat(2000)).toResponseBody("application/javascript".toMediaType())).build()
                path.contains("/workers/scripts/") && path.endsWith("/subdomain") -> ok(JSONObject())
                path.contains("/workers/scripts/") && req.method == "PUT" -> {
                    workerSource = body
                    ok(JSONObject())
                }
                path.endsWith("/values/pwd") && req.method == "PUT" -> {
                    // multipart form: the "value" part carries the password
                    kvPassword = Regex("name=\"value\"\\r\\n(?:[A-Za-z-]+: [^\\r]*\\r\\n)*\\r\\n(.*?)\\r\\n--", RegexOption.DOT_MATCHES_ALL)
                        .find(body)?.groupValues?.get(1)
                    ok(JSONObject())
                }
                path.endsWith("/values/pwd") && req.method == "GET" ->
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body((kvPassword ?: "").toResponseBody("text/plain".toMediaType())).build()
                path.endsWith("/login/authenticate") -> {
                    loginAttempts++
                    val sent = JSONObject(body)
                    lastLogin = sent
                    val settings = embeddedSettings()
                    val good = !rejectLogin &&
                        sent.getString("username").lowercase() == settings.getString("accEmail") &&
                        sent.getString("password") == kvPassword
                    json(req, JSONObject().put("success", good), code = if (good) 200 else 401)
                }
                else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody("text/plain".toMediaType())).build()
            }
        }

        fun embeddedSettings(): JSONObject {
            val start = workerSource.indexOf("const EMBEDED_SETTINGS = ") + "const EMBEDED_SETTINGS = ".length
            val end = workerSource.indexOf(";\n", start)
            return JSONObject(workerSource.substring(start, end))
        }

        private fun bodyOf(req: Request): String {
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            return buffer.readUtf8()
        }

        private fun json(req: Request, o: JSONObject, code: Int = 200): Response =
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("OK")
                .body(o.toString().toResponseBody("application/json".toMediaType())).build()
    }

    private fun provisioner(fake: FakeCloudflare) =
        PanelProvisioner(OkHttpClient.Builder().addInterceptor(fake).build(), retryDelayMs = 0L)

    @Test
    fun generatesLoginStoresItInKvAndEmbedsTheSameIdentity() {
        val fake = FakeCloudflare()
        val panel = provisioner(fake).deployBpbWorker(CloudflareInstallRequest(apiToken = "tok"))

        assertEquals(PanelType.BPB_WORKER, panel.type)
        assertTrue(panel.username.startsWith("maximus-"))
        assertTrue(panel.password.length >= 16)
        assertEquals(panel.password, fake.kvPassword)

        val settings = fake.embeddedSettings()
        assertEquals(panel.username, settings.getString("accEmail"))
        assertEquals(panel.vlessUuid, settings.getString("vlUUID"))
        assertEquals(panel.securePath, settings.getString("securePath"))
        assertTrue(panel.url.endsWith("/${panel.securePath}/panel"))
        assertTrue(panel.url.startsWith("https://${panel.host}/"))

        // The generated credentials were really tried against the worker's login endpoint.
        assertTrue(fake.loginAttempts >= 1)
        assertEquals(panel.username, fake.lastLogin?.getString("username"))
        assertTrue(panel.healthNote.contains("Login", ignoreCase = true))
    }

    @Test
    fun honoursUserSuppliedUsernameAndPassword() {
        val fake = FakeCloudflare()
        val panel = provisioner(fake).deployBpbWorker(
            CloudflareInstallRequest(apiToken = "tok", accountEmail = "Me@Example.com", panelPassword = "S3cret-pass")
        )
        assertEquals("me@example.com", panel.username)
        assertEquals("S3cret-pass", panel.password)
        assertEquals("S3cret-pass", fake.kvPassword)
    }

    @Test
    fun aRejectedLoginIsReportedInsteadOfBeingHidden() {
        val fake = FakeCloudflare(rejectLogin = true)
        val panel = provisioner(fake).deployBpbWorker(CloudflareInstallRequest(apiToken = "tok"))
        assertTrue(fake.loginAttempts >= 1)
        assertTrue(panel.healthNote.contains("propagate", ignoreCase = true))
    }

    @Test
    fun differentDeploymentsGetDifferentCredentials() {
        val a = provisioner(FakeCloudflare()).deployBpbWorker(CloudflareInstallRequest(apiToken = "tok"))
        val b = provisioner(FakeCloudflare()).deployBpbWorker(CloudflareInstallRequest(apiToken = "tok"))
        assertTrue(a.password != b.password)
        assertTrue(a.vlessUuid != b.vlessUuid)
        assertTrue(a.securePath != b.securePath)
        if (a.username == b.username) fail("usernames should be random")
    }
}
