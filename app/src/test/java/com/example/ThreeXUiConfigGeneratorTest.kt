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
    private val corruptStoredClient: Boolean = false,
    private val certFile: String = "/etc/ssl/panel.crt"
) : Interceptor {
    val inbounds = mutableListOf<JSONObject>()
    var nextId = 1
    var loggedIn = false
    var loginCalls = 0
    // Unpadded URL-safe base64 of 32 bytes, the form Xray prints X25519 keys in.
    val publicKey = "PUBKEY_0123456789abcdefghijklmnopqrstuvwx-A"
    val privateKey = "PRIVKEY_0123456789abcdefghijklmnopqrstuvw_A"
    val vlessDecryption = "mlkem768x25519plus.native.600s.SERVERKEY"
    val vlessEncryption = "mlkem768x25519plus.native.0rtt.CLIENTKEY"

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
                // Xray binds TCP and UDP separately, so only a clash on the same transport is refused.
                val udp = payload.getString("protocol") in setOf("hysteria", "wireguard")
                if (inbounds.any { it.getInt("port") == port && (it.getString("protocol") in setOf("hysteria", "wireguard")) == udp }) {
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
            path.endsWith("/panel/api/server/getNewVlessEnc") -> JSONObject().put("success", true).put(
                "obj",
                JSONObject().put("auths", JSONArray()
                    .put(JSONObject().put("id", "mlkem768").put("decryption", "mlkem768x25519plus.native.600s.PQDEC").put("encryption", "mlkem768x25519plus.native.0rtt.PQENC"))
                    .put(JSONObject().put("id", "x25519").put("decryption", vlessDecryption).put("encryption", vlessEncryption)))
            )
            path.endsWith("/panel/api/server/getNewX25519Cert") -> JSONObject().put("success", true)
                .put("obj", JSONObject().put("privateKey", privateKey).put("publicKey", publicKey))
            path.endsWith("/panel/setting/all") -> JSONObject().put("success", true).put(
                "obj",
                JSONObject().apply {
                    if (withCertificate) put("webCertFile", certFile).put("webKeyFile", certFile.replace(".crt", ".key"))
                    else put("webCertFile", "").put("webKeyFile", "")
                }
            )
            path.endsWith("/panel/api/inbounds/allLinks") -> {
                val links = JSONArray()
                for (ib in inbounds) {
                    if (ib.getString("protocol") != "vless") continue
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
        assertEquals(fake.vlessEncryption, result.profile.encryption)
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
    fun hysteria2PinsTheSelfSignedPanelCertificateAndSharesPort443WithReality() {
        val fake = FakePanel(withCertificate = true, certFile = "/etc/x-ui/maximus-tls/panel.crt")
        val pin = "ab".repeat(32)
        val panel = httpPanel.copy(certSha256 = pin)
        val gen = generator(fake) { _, _ -> true }
        assertEquals(443, gen.generateRecommended(panel).port)

        val result = gen.generateInbound(panel, ThreeXUiProtocol.HYSTERIA2, ThreeXUiSecurity.NONE)
        // UDP 443 is free even though REALITY holds TCP 443.
        assertEquals(443, result.port)
        assertEquals(ThreeXUiSecurity.TLS, result.security)
        assertFalse(result.portChecked)
        assertEquals(ProtocolType.HYSTERIA2, result.profile.protocolType)
        assertEquals(pin, result.profile.pinnedPeerCertSha256)
        assertEquals("198.51.100.25", result.profile.sni)
        assertTrue(result.clientUri.startsWith("hysteria2://"))
        assertTrue(result.clientUri.contains("pinSHA256=$pin"))

        val stored = fake.inbounds.last()
        assertEquals("hysteria", stored.getString("protocol"))
        assertEquals(result.profile.uuid, JSONObject(stored.getString("settings")).getJSONArray("clients").getJSONObject(0).getString("auth"))
        val stream = JSONObject(stored.getString("streamSettings"))
        assertEquals(2, stream.getJSONObject("hysteriaSettings").getInt("version"))
        assertEquals(
            "/etc/x-ui/maximus-tls/panel.crt",
            stream.getJSONObject("tlsSettings").getJSONArray("certificates").getJSONObject(0).getString("certificateFile")
        )
    }

    @Test
    fun hysteria2IsRefusedWithoutACertificateOrAnUnpinnableOne() {
        for (fake in listOf(FakePanel(), FakePanel(withCertificate = true, certFile = "/etc/x-ui/maximus-tls/panel.crt"))) {
            try {
                generator(fake).generateInbound(httpPanel, ThreeXUiProtocol.HYSTERIA2, ThreeXUiSecurity.TLS)
                fail("Hysteria2 needs a certificate the client can trust")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("certificate"))
            }
            assertTrue(fake.inbounds.isEmpty())
        }
        // A real certificate needs no pin.
        val result = generator(FakePanel(withCertificate = true)).generateInbound(httpPanel, ThreeXUiProtocol.HYSTERIA2, ThreeXUiSecurity.TLS)
        assertEquals("", result.profile.pinnedPeerCertSha256)
    }

    @Test
    fun wireGuardUsesPanelKeysAndATunnelAddressNoOtherClientHas() {
        val fake = FakePanel()
        val gen = generator(fake)
        val first = gen.generateInbound(httpPanel, ThreeXUiProtocol.WIREGUARD, ThreeXUiSecurity.NONE)
        val second = gen.generateInbound(httpPanel, ThreeXUiProtocol.WIREGUARD, ThreeXUiSecurity.NONE)

        assertEquals(51820, first.port)
        assertEquals(2408, second.port)
        assertEquals(ProtocolType.WIREGUARD, first.profile.protocolType)
        val wgPublic = ThreeXUiConfigGenerator.wireGuardKey(fake.publicKey)
        val wgPrivate = ThreeXUiConfigGenerator.wireGuardKey(fake.privateKey)
        assertEquals(wgPublic, first.profile.publicKey)
        assertEquals(wgPrivate, first.profile.uuid)
        assertTrue(first.clientUri.contains("address=10.0.0.2%2F32"))
        assertTrue(second.clientUri.contains("address=10.0.0.3%2F32"))

        val settings = JSONObject(fake.inbounds[0].getString("settings"))
        assertEquals(wgPrivate, settings.getString("secretKey"))
        val client = settings.getJSONArray("clients").getJSONObject(0)
        assertEquals(wgPublic, client.getString("publicKey"))
        assertEquals("10.0.0.2/32", client.getJSONArray("allowedIPs").getString(0))
    }

    @Test
    fun wireGuardKeysAreConvertedFromXrayToWireGuardBase64() {
        val raw = ByteArray(32) { (it * 7 + 250).toByte() }
        val xray = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertEquals(java.util.Base64.getEncoder().encodeToString(raw), ThreeXUiConfigGenerator.wireGuardKey(xray))
        try {
            ThreeXUiConfigGenerator.wireGuardKey("c2hvcnQ")
            fail("short keys must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("32"))
        }
    }

    @Test
    fun httpUpgradeLinkCarriesPathAndHost() {
        val fake = FakePanel()
        val result = generator(fake).generateInbound(
            httpPanel, ThreeXUiProtocol.HTTPUPGRADE, ThreeXUiSecurity.NONE, customHostOrSni = "cdn.example.com"
        )
        assertEquals(8080, result.port)
        assertEquals("httpupgrade", result.profile.transport)
        assertEquals("/maximus-hu", result.profile.path)
        assertEquals("cdn.example.com", result.profile.host)
        val stream = JSONObject(fake.inbounds[0].getString("streamSettings"))
        assertEquals("/maximus-hu", stream.getJSONObject("httpupgradeSettings").getString("path"))
        // Without TLS the inbound carries VLESS Encryption, which Xray needs for a public server.
        val settings = JSONObject(fake.inbounds[0].getString("settings"))
        assertEquals(fake.vlessDecryption, settings.getString("decryption"))
        assertFalse("Xray will not start with fallbacks next to a decryption key", settings.has("fallbacks"))
        assertEquals(fake.vlessEncryption, result.profile.encryption)
        assertTrue(result.clientUri.contains("encryption=mlkem768x25519plus.native.0rtt.CLIENTKEY"))
        val parsed = VlessParser.parse(result.clientUri)
        assertTrue(parsed is AppResult.Success)
        assertEquals("httpupgrade", (parsed as AppResult.Success).data.transport)
    }

    @Test
    fun realityIsRefusedOnTransportsThatCannotCarryIt() {
        for (protocol in listOf(ThreeXUiProtocol.WEBSOCKET, ThreeXUiProtocol.HTTPUPGRADE)) {
            val fake = FakePanel()
            try {
                generator(fake).generateInbound(httpPanel, protocol, ThreeXUiSecurity.REALITY)
                fail("$protocol + REALITY must never reach the panel")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("Reality"))
            }
            assertTrue(fake.inbounds.isEmpty())
        }
    }

    @Test
    fun panelBaseUrlIsNormalised() {
        assertEquals("http://1.2.3.4:2053/abc", XuiApiClient.normalizeBase("http://1.2.3.4:2053/abc/panel/"))
        assertEquals("https://h:1/abc", XuiApiClient.normalizeBase("https://h:1/abc/"))
        assertEquals("http://h:2053", XuiApiClient.normalizeBase("http://h:2053/"))
    }
}
