package com.example

import com.example.core.AppResult
import com.example.data.model.ProtocolType
import com.example.panels.ManagedPanel
import com.example.panels.PanelType
import com.example.panels.ThreeXUiConfigGenerator
import com.example.panels.ThreeXUiProtocol
import com.example.panels.ThreeXUiSecurity
import com.example.panels.XuiApiClient
import com.example.vless.VlessParser
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * In-memory 3X-UI panel. It can be switched into the behaviours that differ between panel
 * releases so the generator is exercised against all of them.
 */
private class FakePanel(
    private val acceptNested: Boolean = true,
    private val bearerWorks: Boolean = true,
    private val withCertificate: Boolean = false,
    private val dropPublicKey: Boolean = false,
    private val corruptStoredClient: Boolean = false
) : Interceptor {
    val inbounds = mutableListOf<JSONObject>()
    var nextId = 1
    var loggedIn = false
    var loginCalls = 0
    val publicKey = "PUBKEY_0123456789abcdefghijklmnopqrstuvwxyzABCDE"
    val privateKey = "PRIVKEY_0123456789abcdefghijklmnopqrstuvwxyzABCD"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        val bodyText = buffer.readUtf8()
        val bearer = request.header("Authorization")?.startsWith("Bearer ") == true
        val hasCookie = loggedIn

        if (path.endsWith("/login")) {
            loginCalls++
            loggedIn = true
            return json(request, 200, JSONObject().put("success", true), cookie = "3x-ui=ok; Path=/")
        }

        val authorized = (bearer && bearerWorks) || hasCookie
        if (!authorized) return plain(request, 404, "404 page not found")

        val body: JSONObject = when {
            path.endsWith("/panel/api/inbounds/list") -> JSONObject().put("success", true)
                .put("obj", JSONArray(inbounds.map { it }))
            path.endsWith("/panel/api/inbounds/add") -> {
                val payload = JSONObject(bodyText)
                val nested = payload.opt("settings") is JSONObject
                if (nested && !acceptNested) {
                    return plain(request, 400, """{"success":false,"msg":"json: cannot unmarshal object into Go struct field"}""")
                }
                val port = payload.getInt("port")
                if (inbounds.any { it.getInt("port") == port }) {
                    JSONObject().put("success", false).put("msg", "Port already exists: $port")
                } else {
                    val stored = JSONObject(payload.toString())
                    stored.put("id", nextId++)
                    // The real panel always stores these three fields as strings.
                    for (k in listOf("settings", "streamSettings", "sniffing")) stored.put(k, payload.get(k).toString())
                    if (corruptStoredClient) {
                        val s = JSONObject(stored.getString("settings"))
                        s.getJSONArray("clients").getJSONObject(0).put("id", "00000000-0000-0000-0000-000000000000")
                        stored.put("settings", s.toString())
                    }
                    inbounds.add(stored)
                    JSONObject().put("success", true).put("obj", stored)
                }
            }
            path.contains("/panel/api/inbounds/del/") -> {
                val id = path.substringAfterLast('/').toInt()
                inbounds.removeAll { it.getInt("id") == id }
                JSONObject().put("success", true)
            }
            path.endsWith("/panel/api/server/getNewX25519Cert") -> JSONObject().put("success", true)
                .put("obj", JSONObject().put("privateKey", privateKey).put("publicKey", publicKey))
            path.endsWith("/panel/setting/all") -> JSONObject().put("success", true).put(
                "obj",
                JSONObject().apply {
                    if (withCertificate) put("webCertFile", "/etc/ssl/panel.crt").put("webKeyFile", "/etc/ssl/panel.key")
                    else put("webCertFile", "").put("webKeyFile", "")
                }
            )
            path.endsWith("/panel/api/inbounds/allLinks") -> {
                val links = JSONArray()
                for (ib in inbounds) {
                    val stream = JSONObject(ib.getString("streamSettings"))
                    val client = JSONObject(ib.getString("settings")).getJSONArray("clients").getJSONObject(0)
                    val q = mutableListOf("type=${stream.getString("network")}")
                    val sec = stream.optString("security", "none")
                    q += "security=$sec"
                    if (sec == "reality") {
                        val rs = stream.getJSONObject("realitySettings")
                        val pbk = rs.optJSONObject("settings")?.optString("publicKey").orEmpty()
                        if (!dropPublicKey && pbk.isNotBlank()) q += "pbk=$pbk"
                        q += "fp=chrome"
                        q += "sni=${rs.getJSONArray("serverNames").getString(0)}"
                        q += "sid=${rs.getJSONArray("shortIds").getString(0)}"
                        if (client.optString("flow").isNotBlank()) q += "flow=${client.getString("flow")}"
                    }
                    links.put("vless://${client.getString("id")}@${request.url.host}:${ib.getInt("port")}?${q.joinToString("&")}#${ib.getString("remark")}")
                }
                JSONObject().put("success", true).put("obj", links)
            }
            else -> JSONObject().put("success", false).put("msg", "Unexpected endpoint $path")
        }
        return json(request, 200, body)
    }

    private fun json(request: okhttp3.Request, code: Int, o: JSONObject, cookie: String? = null): Response =
        Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("OK")
            .apply { if (cookie != null) header("Set-Cookie", cookie) }
            .body(o.toString().toResponseBody("application/json".toMediaType())).build()

    private fun plain(request: okhttp3.Request, code: Int, text: String): Response =
        Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("ERR")
            .body(text.toResponseBody("text/plain".toMediaType())).build()
}

class ThreeXUiConfigGeneratorTest {

    private val httpPanel = ManagedPanel(
        id = "p1", type = PanelType.XUI, name = "VPS",
        url = "http://198.51.100.25:2053/abcdef/", host = "198.51.100.25",
        username = "admin", password = "secret", apiToken = "token"
    )

    private fun generator(fake: FakePanel, reachable: (String, Int) -> Boolean = { _, _ -> true }): ThreeXUiConfigGenerator {
        val client = OkHttpClient.Builder()
            .addInterceptor(fake)
            .build()
        return ThreeXUiConfigGenerator(client, reachable)
    }

    @Test
    fun recommendedConfigIsRealityOn443OverPlainHttpPanel() {
        val fake = FakePanel()
        val result = generator(fake).generateRecommended(httpPanel)

        assertEquals(443, result.port)
        assertEquals("reality", result.profile.security)
        assertEquals("xtls-rprx-vision", result.profile.flow)
        assertEquals(fake.publicKey, result.profile.publicKey)
        assertTrue(result.clientUri.contains("pbk=${fake.publicKey}"))
        assertTrue(result.reachable)
        assertEquals(1, fake.inbounds.size)

        // The panel received a Reality block that carries the public key for link export.
        val stream = JSONObject(fake.inbounds[0].getString("streamSettings"))
        val rs = stream.getJSONObject("realitySettings")
        assertEquals(fake.publicKey, rs.getJSONObject("settings").getString("publicKey"))
        assertEquals(fake.privateKey, rs.getString("privateKey"))
        assertEquals("www.microsoft.com:443", rs.getString("dest"))

        val parsed = VlessParser.parse(result.clientUri)
        assertTrue(parsed is AppResult.Success)
    }

    @Test
    fun linkFallsBackToLocallyBuiltOneWhenPanelExportLacksPublicKey() {
        val fake = FakePanel(dropPublicKey = true)
        val result = generator(fake).generateRecommended(httpPanel)
        assertTrue(result.clientUri.contains("pbk=${fake.publicKey}"))
        assertTrue(VlessParser.parse(result.clientUri) is AppResult.Success)
    }

    @Test
    fun usesSessionLoginWhenTheApiTokenIsNotAccepted() {
        val fake = FakePanel(bearerWorks = false)
        val result = generator(fake).generateRecommended(httpPanel)
        assertEquals(1, fake.loginCalls)
        assertEquals(1, fake.inbounds.size)
        assertNotNull(result.clientUri)
    }

    @Test
    fun fallsBackToStringifiedPayloadWhenNestedObjectsAreRejected() {
        val fake = FakePanel(acceptNested = false)
        generator(fake).generateRecommended(httpPanel)
        assertEquals(1, fake.inbounds.size)
    }

    @Test
    fun skipsOccupiedPortsAndFreesUnreachableOnes() {
        val fake = FakePanel()
        val probed = mutableListOf<Int>()
        // 443 answers nothing (blocked), the next candidate does.
        val gen = generator(fake) { _, port -> probed += port; port != 443 }
        val result = gen.generateRecommended(httpPanel)
        assertEquals(8443, result.port)
        assertEquals(listOf(443, 8443), probed)
        assertEquals(1, fake.inbounds.size)
        assertEquals(8443, fake.inbounds[0].getInt("port"))
    }

    @Test
    fun keepsLastInboundAndWarnsWhenNoPortIsReachable() {
        val fake = FakePanel()
        val result = generator(fake, { _, _ -> false }).generateInbound(
            httpPanel, ThreeXUiProtocol.REALITY, ThreeXUiSecurity.REALITY
        )
        assertFalse(result.reachable)
        assertTrue(result.statusMessage.contains("firewall", ignoreCase = true))
        assertEquals(1, fake.inbounds.size)
    }

    @Test
    fun rapidVlessTcpMatchesTheClassicLinkFormat() {
        val fake = FakePanel()
        val result = generator(fake).generateRapidVlessTcp(
            httpPanel, targetPort = 31280, targetUuid = "11111111-2222-3333-4444-555555555555"
        )
        assertEquals("tcp", result.profile.transport)
        assertEquals("none", result.profile.security)
        assertEquals(ProtocolType.VLESS, result.profile.protocolType)
        assertTrue(result.profile.name.startsWith("X-"))
        assertEquals(31280, result.profile.port)
        val settings = JSONObject(fake.inbounds[0].getString("settings"))
        assertEquals("11111111-2222-3333-4444-555555555555", settings.getJSONArray("clients").getJSONObject(0).getString("id"))
        assertTrue(VlessParser.parse(result.clientUri) is AppResult.Success)
    }

    @Test
    fun webSocketOverCdnPortWithoutTls() {
        val fake = FakePanel()
        val result = generator(fake).generateInbound(
            httpPanel, ThreeXUiProtocol.WEBSOCKET, ThreeXUiSecurity.NONE,
            customHostOrSni = "cdn.example.com", customPath = "/ws"
        )
        assertEquals(8080, result.port)
        assertEquals("ws", result.profile.transport)
        assertEquals("/ws", result.profile.path)
        assertEquals("cdn.example.com", result.profile.host)
        assertTrue(result.clientUri.contains("type=ws"))
    }

    @Test
    fun tlsIsRefusedWithoutAServerCertificateAndAllowedWithOne() {
        try {
            generator(FakePanel()).generateInbound(
                httpPanel, ThreeXUiProtocol.WEBSOCKET, ThreeXUiSecurity.TLS, customHostOrSni = "cdn.example.com"
            )
            fail("TLS without a certificate must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("certificate"))
        }
        val fake = FakePanel(withCertificate = true)
        val result = generator(fake).generateInbound(
            httpPanel, ThreeXUiProtocol.WEBSOCKET, ThreeXUiSecurity.TLS, customHostOrSni = "cdn.example.com"
        )
        val tls = JSONObject(fake.inbounds[0].getString("streamSettings")).getJSONObject("tlsSettings")
        assertEquals("/etc/ssl/panel.crt", tls.getJSONArray("certificates").getJSONObject(0).getString("certificateFile"))
        assertEquals("tls", result.profile.security)
    }

    @Test
    fun aMismatchBetweenRequestAndStoredInboundIsRolledBack() {
        val fake = FakePanel(corruptStoredClient = true)
        try {
            generator(fake).generateRecommended(httpPanel)
            fail("must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("different inbound"))
        }
        assertTrue(fake.inbounds.isEmpty())
    }

    @Test
    fun customPortInUseIsRejected() {
        val fake = FakePanel()
        val gen = generator(fake)
        gen.generateInbound(httpPanel, ThreeXUiProtocol.RAW, ThreeXUiSecurity.NONE, customPort = 40000)
        try {
            gen.generateInbound(httpPanel, ThreeXUiProtocol.RAW, ThreeXUiSecurity.NONE, customPort = 40000)
            fail("duplicate port")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("already used"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun throwsWhenNoPanelHostIsConfigured() {
        generator(FakePanel()).generateRapidVlessTcp(
            ManagedPanel(id = "d", type = PanelType.XUI, name = "", url = "", host = "")
        )
    }

    @Test
    fun sessionCookieIsReusedAgainstARealHttpServer() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val path = request.path.orEmpty()
                if (path.endsWith("/login")) {
                    return okhttp3.mockwebserver.MockResponse()
                        .addHeader("Set-Cookie", "3x-ui=abc123; Path=/; HttpOnly")
                        .setBody("""{"success":true,"msg":"ok"}""")
                }
                if (request.getHeader("Cookie")?.contains("3x-ui=abc123") != true) {
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(404).setBody("404 page not found")
                }
                return okhttp3.mockwebserver.MockResponse().setBody("""{"success":true,"obj":[{"port":2053}]}""")
            }
        }
        server.start()
        try {
            val api = XuiApiClient(
                panelUrl = server.url("/secret/panel/").toString(),
                apiToken = "token-the-panel-does-not-honour",
                username = "admin",
                password = "pw"
            )
            assertEquals(setOf(2053), api.usedPorts())
            assertTrue(server.requestCount >= 3) // token try, login, retry with cookie
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun panelBaseUrlIsNormalised() {
        assertEquals("http://1.2.3.4:2053/abc", XuiApiClient.normalizeBase("http://1.2.3.4:2053/abc/panel/"))
        assertEquals("https://h:1/abc", XuiApiClient.normalizeBase("https://h:1/abc/"))
        assertEquals("http://h:2053", XuiApiClient.normalizeBase("http://h:2053/"))
    }
}
