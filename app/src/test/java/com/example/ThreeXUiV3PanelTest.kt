package com.example

import com.example.core.AppResult
import com.example.panels.ManagedPanel
import com.example.panels.PanelType
import com.example.panels.ThreeXUiConfigGenerator
import com.example.panels.ThreeXUiProtocol
import com.example.panels.ThreeXUiSecurity
import com.example.vless.VlessParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Real-HTTP stand-in for 3X-UI v3 (the release PanelProvisioner installs), reproducing the
 * behaviours that broke in-app config creation:
 *  - client tgId must be a number; a string fails the whole request;
 *  - /login and session-cookie POSTs need the X-CSRF-Token from /csrf-token;
 *  - rejected requests answer with an empty body labelled gzip;
 *  - panel certificate paths live under /panel/api, /panel/setting/all is gone.
 */
private class V3Panel(
    private val token: String,
    private val certFile: String = ""
) : Dispatcher() {
    val inbounds = mutableListOf<JSONObject>()
    var logins = 0
    private val csrf = "csrf-0123456789"
    private val cookie = "3x-ui=session"

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        val hasSession = request.getHeader("Cookie")?.contains(cookie) == true
        val bearer = request.getHeader("Authorization")
        val csrfOk = request.getHeader("X-CSRF-Token") == csrf

        if (path.endsWith("/csrf-token")) {
            return MockResponse().addHeader("Set-Cookie", "$cookie; Path=/")
                .setBody(JSONObject().put("success", true).put("obj", csrf).toString())
        }
        if (path.endsWith("/login")) {
            if (!csrfOk) return rejected(403)
            logins++
            return MockResponse().addHeader("Set-Cookie", "$cookie; Path=/").setBody("""{"success":true}""")
        }
        val tokenOk = bearer == "Bearer $token"
        if (!tokenOk && !hasSession) return rejected(if (bearer != null) 401 else 404)
        if (!tokenOk && request.method == "POST" && !csrfOk) return rejected(403)

        val body: JSONObject = when {
            path.endsWith("/panel/api/inbounds/list") -> ok(JSONArray(inbounds))
            path.endsWith("/panel/api/server/getNewX25519Cert") ->
                ok(JSONObject().put("privateKey", "PRIV_KEY_v3").put("publicKey", "PUB_KEY_v3"))
            path.endsWith("/panel/api/server/getWebCertFiles") ->
                ok(JSONObject().put("webCertFile", certFile).put("webKeyFile", if (certFile.isBlank()) "" else "$certFile.key"))
            path.endsWith("/panel/api/inbounds/allLinks") -> ok(JSONArray())
            path.endsWith("/panel/api/inbounds/add") -> add(JSONObject(request.body.readUtf8()))
            path.contains("/panel/api/inbounds/del/") -> {
                val id = path.substringAfterLast('/').toInt()
                inbounds.removeAll { it.getInt("id") == id }
                ok(id)
            }
            else -> return MockResponse().setResponseCode(404).setBody("404 page not found")
        }
        return MockResponse().setBody(body.toString())
    }

    private fun add(payload: JSONObject): JSONObject {
        // Gin binds settings/streamSettings/sniffing into strings only.
        if (payload.opt("settings") !is String) {
            return JSONObject().put("success", false).put("msg", "request body failed validation")
        }
        val client = JSONObject(payload.getString("settings")).getJSONArray("clients").getJSONObject(0)
        if (client.opt("tgId") is String) {
            return JSONObject().put("success", false)
                .put("msg", "Something went wrong (json: cannot unmarshal string into Go struct field .0.tgId of type int64)")
        }
        val stored = JSONObject(payload.toString()).put("id", inbounds.size + 1)
        inbounds.add(stored)
        return ok(stored)
    }

    private fun ok(obj: Any) = JSONObject().put("success", true).put("msg", "").put("obj", obj)

    /** 3X-UI aborts with no body but still labels the response as gzip. */
    private fun rejected(code: Int) = MockResponse().setResponseCode(code).addHeader("Content-Encoding", "gzip")
}

class ThreeXUiV3PanelTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun panel(token: String) = ManagedPanel(
        id = "v3", type = PanelType.XUI, name = "v3",
        url = server.url("/base/").toString(), host = "203.0.113.7",
        username = "admin", password = "secret", apiToken = token
    )

    private fun generator() = ThreeXUiConfigGenerator(OkHttpClient(), reachabilityProbe = { _, _ -> true })

    @Test
    fun quickConfigWorksWithTheInstalledApiToken() {
        val fake = V3Panel(token = "good-token")
        server.dispatcher = fake
        val result = generator().generateRecommended(panel("good-token"))

        assertEquals(1, fake.inbounds.size)
        assertEquals(0, fake.logins)
        val client = JSONObject(fake.inbounds[0].getString("settings")).getJSONArray("clients").getJSONObject(0)
        assertEquals(0, client.get("tgId"))
        assertTrue(result.clientUri.contains("pbk=PUB_KEY_v3"))
        assertTrue(VlessParser.parse(result.clientUri) is AppResult.Success)
    }

    @Test
    fun staleTokenFallsBackToCsrfProtectedLogin() {
        val fake = V3Panel(token = "current-token")
        server.dispatcher = fake
        val result = generator().generateInbound(panel("old-token"), ThreeXUiProtocol.XHTTP, ThreeXUiSecurity.NONE)

        assertEquals(1, fake.logins)
        assertEquals(1, fake.inbounds.size)
        assertTrue(VlessParser.parse(result.clientUri) is AppResult.Success)
    }

    @Test
    fun loginWithoutTokenWorks() {
        val fake = V3Panel(token = "unused")
        server.dispatcher = fake
        generator().generateRapidVlessTcp(panel(""))
        assertEquals(1, fake.inbounds.size)
    }

    @Test
    fun tlsUsesCertificateFromTheV3Route() {
        val fake = V3Panel(token = "t", certFile = "/root/cert/example.com/fullchain.pem")
        server.dispatcher = fake
        val result = generator().generateInbound(
            panel("t"), ThreeXUiProtocol.WEBSOCKET, ThreeXUiSecurity.TLS, customHostOrSni = "example.com"
        )
        val tls = JSONObject(fake.inbounds[0].getString("streamSettings")).getJSONObject("tlsSettings")
        assertEquals("/root/cert/example.com/fullchain.pem", tls.getJSONArray("certificates").getJSONObject(0).getString("certificateFile"))
        assertEquals("tls", result.profile.security)
    }

    @Test
    fun tlsOnTheSelfSignedPanelCertificateIsRefused() {
        val fake = V3Panel(token = "t", certFile = "/etc/x-ui/maximus-tls/panel.crt")
        server.dispatcher = fake
        try {
            generator().generateInbound(panel("t"), ThreeXUiProtocol.WEBSOCKET, ThreeXUiSecurity.TLS)
            fail("a TLS inbound on the pinned panel certificate cannot be trusted by VPN clients")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("self-signed"))
        }
        assertTrue(fake.inbounds.isEmpty())
    }

    @Test
    fun webSocketRealityNeverReachesThePanel() {
        val fake = V3Panel(token = "t")
        server.dispatcher = fake
        try {
            generator().generateInbound(panel("t"), ThreeXUiProtocol.WEBSOCKET, ThreeXUiSecurity.REALITY)
            fail("Xray rejects WebSocket + REALITY")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("WebSocket"))
        }
        assertTrue(fake.inbounds.isEmpty())
    }
}
