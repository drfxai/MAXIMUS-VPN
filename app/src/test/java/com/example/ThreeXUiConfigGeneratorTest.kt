package com.example

import com.example.data.model.ProtocolType
import com.example.panels.ManagedPanel
import com.example.panels.PanelType
import com.example.panels.ThreeXUiConfigGenerator
import com.example.panels.ThreeXUiProtocol
import com.example.panels.ThreeXUiSecurity
import com.example.vless.VlessParser
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThreeXUiConfigGeneratorTest {

    private lateinit var generator: ThreeXUiConfigGenerator
    private val testPanel = ManagedPanel(
        id = "test-panel-1",
        type = PanelType.XUI,
        name = "My 3X-UI VPS",
        url = "https://198.51.100.25:2053/panel",
        host = "198.51.100.25",
        apiToken = "test_api_token"
    )

    @Before
    fun setUp() {
        var lastUuid = ""
        var lastPort = 0
        var lastRemark = ""
        var lastQuery = "type=tcp"
        val fakePanelClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val body = when {
                    path.endsWith("/panel/api/inbounds/add") -> {
                        val buffer = okio.Buffer()
                        request.body?.writeTo(buffer)
                        val payload = JSONObject(buffer.readUtf8())
                        lastPort = payload.getInt("port")
                        lastRemark = payload.getString("remark")
                        val settings = JSONObject(payload.getString("settings"))
                        lastUuid = settings.getJSONArray("clients").getJSONObject(0).getString("id")
                        val stream = JSONObject(payload.getString("streamSettings"))
                        val network = stream.optString("network", "tcp")
                        val security = stream.optString("security", "none")
                        val query = mutableListOf("type=$network")
                        if (security != "none") query += "security=$security"
                        when (network) {
                            "ws" -> {
                                val ws = stream.optJSONObject("wsSettings")
                                ws?.optString("path")?.takeIf { it.isNotBlank() }?.let {
                                    query += "path=" + java.net.URLEncoder.encode(it, "UTF-8")
                                }
                                ws?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() }?.let {
                                    query += "host=" + java.net.URLEncoder.encode(it, "UTF-8")
                                }
                            }
                            "xhttp" -> {
                                val xh = stream.optJSONObject("xhttpSettings")
                                xh?.optString("path")?.takeIf { it.isNotBlank() }?.let {
                                    query += "path=" + java.net.URLEncoder.encode(it, "UTF-8")
                                }
                                xh?.optString("host")?.takeIf { it.isNotBlank() }?.let {
                                    query += "host=" + java.net.URLEncoder.encode(it, "UTF-8")
                                }
                                query += "mode=auto"
                            }
                        }
                        if (security == "reality") {
                            val rs = stream.getJSONObject("realitySettings")
                            query += "flow=xtls-rprx-vision"
                            query += "pbk=jX3yqP_1234567890_1234567890abcdef"
                            query += "fp=chrome"
                            query += "sni=" + java.net.URLEncoder.encode(rs.getJSONArray("serverNames").getString(0), "UTF-8")
                            query += "sid=" + rs.getJSONArray("shortIds").getString(0)
                        } else if (security == "tls") {
                            val tls = stream.getJSONObject("tlsSettings")
                            query += "sni=" + java.net.URLEncoder.encode(tls.getString("serverName"), "UTF-8")
                            query += "fp=chrome"
                        }
                        lastQuery = query.joinToString("&")
                        """{"success":true,"msg":"","obj":null}"""
                    }
                    path.endsWith("/panel/api/inbounds/allLinks") -> {
                        val link = "vless://$lastUuid@${request.url.host}:$lastPort?$lastQuery#${java.net.URLEncoder.encode(lastRemark, "UTF-8")}"
                        JSONObject().put("success", true).put("obj", org.json.JSONArray().put(link)).toString()
                    }
                    path.endsWith("/panel/api/server/getNewX25519Cert") ->
                        """{"success":true,"obj":{"publicKey":"jX3yqP_1234567890_1234567890abcdef","privateKey":"mK9vLw_1234567890_1234567890abcdef"}}"""
                    else -> """{"success":false,"msg":"Unexpected test endpoint"}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
        generator = ThreeXUiConfigGenerator(fakePanelClient)
    }

    @Test
    fun testRapidVlessTcpGeneration() {
        val result = generator.generateRapidVlessTcp(
            panel = testPanel,
            targetPort = 31280,
            targetUuid = "11111111-2222-3333-4444-555555555555"
        )

        assertNotNull(result)
        assertEquals("198.51.100.25", result.profile.address)
        assertEquals(31280, result.profile.port)
        assertEquals("11111111-2222-3333-4444-555555555555", result.profile.uuid)
        assertEquals("tcp", result.profile.transport)
        assertEquals("none", result.profile.security)
        assertEquals("", result.profile.flow)
        assertEquals(ProtocolType.VLESS, result.profile.protocolType)
        assertTrue(result.profile.name.startsWith("X-"))

        // Inbound JSON verification
        val inboundJson = JSONObject(result.inboundJson)
        assertEquals("vless", inboundJson.getString("protocol"))
        assertEquals(31280, inboundJson.getInt("port"))
        assertTrue(inboundJson.getBoolean("enable"))

        val streamSettings = JSONObject(inboundJson.getString("streamSettings"))
        assertEquals("tcp", streamSettings.getString("network"))
        assertEquals("none", streamSettings.getString("security"))

        val settings = JSONObject(inboundJson.getString("settings"))
        val clients = settings.getJSONArray("clients")
        assertEquals(1, clients.length())
        assertEquals("11111111-2222-3333-4444-555555555555", clients.getJSONObject(0).getString("id"))

        // Client URI check matching vless://uuid@host:port?type=tcp#X-...
        assertTrue(result.clientUri.startsWith("vless://11111111-2222-3333-4444-555555555555@198.51.100.25:31280?type=tcp#X-"))
        assertTrue(!result.clientUri.contains("security="))
        assertTrue(!result.clientUri.contains("flow="))

        // Parse with VlessParser
        val parsed = VlessParser.parse(result.clientUri)
        assertTrue(parsed is com.example.core.AppResult.Success)
    }

    @Test
    fun testQuickConfigMatchesUserFormat() {
        val panel = ManagedPanel(
            id = "user-panel-1",
            type = PanelType.XUI,
            name = "User 3X-UI VPS",
            url = "https://178.83.46.36:2053/panel",
            host = "178.83.46.36",
            password = "test_password",
            apiToken = "test_token"
        )
        val result = generator.generateRapidVlessTcp(
            panel = panel,
            targetPort = 55282,
            targetUuid = "aea2db5a-b860-4497-8322-3386c346da87"
        )

        // Exact pattern verification matching: vless://aea2db5a-b860-4497-8322-3386c346da87@178.83.46.36:55282?type=tcp#X-524k5r8yvd
        assertTrue(result.clientUri.startsWith("vless://aea2db5a-b860-4497-8322-3386c346da87@178.83.46.36:55282?type=tcp#X-"))
        val parsed = VlessParser.parse(result.clientUri)
        assertTrue(parsed is com.example.core.AppResult.Success)
        val profile = (parsed as com.example.core.AppResult.Success).data
        assertEquals("aea2db5a-b860-4497-8322-3386c346da87", profile.uuid)
        assertEquals("178.83.46.36", profile.address)
        assertEquals(55282, profile.port)
        assertEquals("tcp", profile.transport)
        assertEquals("none", profile.security)
        assertTrue(profile.name.startsWith("X-"))
    }

    @Test
    fun testWebSocketTlsGeneration() {
        val result = generator.generateInbound(
            panel = testPanel,
            protocol = ThreeXUiProtocol.WEBSOCKET,
            security = ThreeXUiSecurity.TLS,
            customPort = 443,
            customHostOrSni = "cdn.example.com",
            customPath = "/custom-ws-path"
        )

        assertEquals("ws", result.profile.transport)
        assertEquals("tls", result.profile.security)
        assertEquals("cdn.example.com", result.profile.sni)
        assertEquals("cdn.example.com", result.profile.host)
        assertEquals("/custom-ws-path", result.profile.path)

        val inboundJson = JSONObject(result.inboundJson)
        val streamSettings = JSONObject(inboundJson.getString("streamSettings"))
        assertEquals("ws", streamSettings.getString("network"))
        assertEquals("tls", streamSettings.getString("security"))

        val wsSettings = streamSettings.getJSONObject("wsSettings")
        assertEquals("/custom-ws-path", wsSettings.getString("path"))
        assertEquals("cdn.example.com", wsSettings.getJSONObject("headers").getString("Host"))

        val tlsSettings = streamSettings.getJSONObject("tlsSettings")
        assertEquals("cdn.example.com", tlsSettings.getString("serverName"))

        assertTrue(result.clientUri.contains("type=ws"))
        assertTrue(result.clientUri.contains("security=tls"))
    }

    @Test
    fun testXhttpGeneration() {
        val result = generator.generateInbound(
            panel = testPanel,
            protocol = ThreeXUiProtocol.XHTTP,
            security = ThreeXUiSecurity.NONE,
            customPort = 8080,
            customHostOrSni = "xhttp.example.com",
            customPath = "/xhttp-stream"
        )

        assertEquals("xhttp", result.profile.transport)
        assertEquals("none", result.profile.security)
        assertEquals("xhttp.example.com", result.profile.host)
        assertEquals("/xhttp-stream", result.profile.path)

        val inboundJson = JSONObject(result.inboundJson)
        val streamSettings = JSONObject(inboundJson.getString("streamSettings"))
        assertEquals("xhttp", streamSettings.getString("network"))
        assertEquals("none", streamSettings.getString("security"))

        val xhttpSettings = streamSettings.getJSONObject("xhttpSettings")
        assertEquals("/xhttp-stream", xhttpSettings.getString("path"))
        assertEquals("xhttp.example.com", xhttpSettings.getString("host"))

        assertTrue(result.clientUri.contains("type=xhttp"))
        assertTrue(result.clientUri.contains("mode=auto"))
    }

    @Test
    fun testRealityGeneration() {
        val result = generator.generateInbound(
            panel = testPanel,
            protocol = ThreeXUiProtocol.REALITY,
            security = ThreeXUiSecurity.REALITY,
            customPort = 34500,
            customHostOrSni = "www.microsoft.com"
        )

        assertEquals("tcp", result.profile.transport)
        assertEquals("reality", result.profile.security)
        assertEquals("xtls-rprx-vision", result.profile.flow)
        assertEquals("www.microsoft.com", result.profile.sni)
        assertTrue(result.profile.publicKey.isNotBlank())
        assertTrue(result.profile.shortId.isNotBlank())

        val inboundJson = JSONObject(result.inboundJson)
        val streamSettings = JSONObject(inboundJson.getString("streamSettings"))
        assertEquals("reality", streamSettings.getString("security"))

        val realitySettings = streamSettings.getJSONObject("realitySettings")
        assertEquals("www.microsoft.com:443", realitySettings.getString("dest"))
        assertTrue(realitySettings.getString("privateKey").isNotBlank())

        assertTrue(result.clientUri.contains("security=reality"))
        assertTrue(result.clientUri.contains("flow=xtls-rprx-vision"))
        assertTrue(result.clientUri.contains("pbk="))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testThrowsWhenNoPanelProvidedOrHostBlank() {
        generator.generateRapidVlessTcp(
            panel = ManagedPanel(id = "dummy", type = PanelType.XUI, name = "", url = "", host = "")
        )
    }
}
