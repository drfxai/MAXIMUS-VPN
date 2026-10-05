package com.example

import com.example.core.SecretRedactor
import com.example.data.model.AppSettings
import com.example.data.model.ProtocolType
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.warp.Curve25519
import com.example.vpn.warp.WarpAccount
import com.example.vpn.warp.WarpRegistration
import com.example.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Date

class WarpRegistrationTest {

    private class FakeConnection(url: URL, private val code: Int, private val body: String) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() { connected = true }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sent
        override fun getResponseCode(): Int = code
        override fun getInputStream(): InputStream =
            if (code in 200..299) ByteArrayInputStream(body.toByteArray()) else throw IOException("HTTP $code")
        override fun getErrorStream(): InputStream? = if (code in 200..299) null else ByteArrayInputStream(body.toByteArray())
    }

    private val response = """
        {
          "id": "11111111-2222-3333-4444-555555555555",
          "type": "a",
          "token": "99999999-8888-7777-6666-000000000000",
          "key_type": "curve25519",
          "tunnel_type": "wireguard",
          "account": {"id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "account_type": "free", "warp_plus": false},
          "config": {
            "client_id": "Zx6c",
            "peers": [{
              "public_key": "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
              "endpoint": {
                "v4": "162.159.192.7:0",
                "v6": "[2606:4700:d0::a29f:c007]:0",
                "host": "engage.cloudflareclient.com:2408",
                "ports": [2408, 500, 1701, 4500]
              }
            }],
            "interface": {"addresses": {"v4": "172.16.0.2", "v6": "2606:4700:110:8a36:df92:102a:9602:fa18"}},
            "services": {"http_proxy": "172.16.0.1:2480"}
          }
        }
    """.trimIndent()

    private fun fixedKey(): ByteArray = Curve25519.clamp(ByteArray(32) { (it * 7 + 3).toByte() })

    private fun registerWithFake(code: Int = 200, body: String = response): Pair<WarpAccount, FakeConnection> {
        var connection: FakeConnection? = null
        val account = WarpRegistration.register(
            open = { url -> FakeConnection(url, code, body).also { connection = it } },
            privateKey = fixedKey(),
            now = Date(0)
        )
        return account to connection!!
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun curve25519MatchesRfc7748Vectors() {
        assertArrayEquals(
            hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"),
            Curve25519.publicKey(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        )
        assertArrayEquals(
            hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"),
            Curve25519.publicKey(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        )
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertArrayEquals(shared, Curve25519.scalarMult(
            hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
            hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        ))
    }

    @Test
    fun newPrivateKeyIsClamped() {
        val key = Curve25519.newPrivateKey()
        assertEquals(32, key.size)
        assertEquals(0, key[0].toInt() and 7)
        assertEquals(64, key[31].toInt() and 0xc0)
    }

    @Test
    fun postsTheRegistrationRequestTheAppSends() {
        val (_, connection) = registerWithFake()
        assertEquals("https://api.cloudflareclient.com/v0a5641/reg", connection.url.toString())
        assertEquals("POST", connection.requestMethod)
        assertTrue(connection.connectTimeout in 1..30_000)
        assertTrue(connection.readTimeout in 1..30_000)
        assertEquals(WarpRegistration.CLIENT_VERSION, connection.getRequestProperty("CF-Client-Version"))
        assertEquals(WarpRegistration.USER_AGENT, connection.getRequestProperty("User-Agent"))
        assertTrue(connection.getRequestProperty("Content-Type").startsWith("application/json"))
        assertTrue(connection.disconnected)

        val body = JSONObject(connection.sent.toString(Charsets.UTF_8.name()))
        val publicKey = Base64.getEncoder().encodeToString(Curve25519.publicKey(fixedKey()))
        assertEquals(publicKey, body.getString("key"))
        assertEquals("curve25519", body.getString("key_type"))
        assertEquals("wireguard", body.getString("tunnel_type"))
        assertEquals("en_US", body.getString("locale"))
        assertEquals("1970-01-01T00:00:00.000Z", body.getString("tos"))
        for (field in listOf("fcm_token", "install_id", "serial_number")) assertEquals("", body.getString(field))
        val privateKey = Base64.getEncoder().encodeToString(fixedKey())
        assertFalse("the private key must never be sent", body.toString().contains(privateKey))
    }

    @Test
    fun parsesTheRegistrationAnswer() {
        val (account, _) = registerWithFake()
        assertEquals("11111111-2222-3333-4444-555555555555", account.deviceId)
        assertEquals("99999999-8888-7777-6666-000000000000", account.token)
        assertEquals(Base64.getEncoder().encodeToString(fixedKey()), account.privateKey)
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", account.peerPublicKey)
        assertEquals("172.16.0.2", account.addressV4)
        assertEquals("2606:4700:110:8a36:df92:102a:9602:fa18", account.addressV6)
        assertEquals(account, WarpAccount.fromJson(account.toJson()))
    }

    @Test
    fun reservedBytesComeFromClientId() {
        val (account, _) = registerWithFake()
        assertEquals(listOf(0x67, 0x1e, 0x9c), account.reserved)
        assertEquals(listOf(1, 2, 3), WarpRegistration.reservedBytes("AQID"))
        assertEquals(emptyList<Int>(), WarpRegistration.reservedBytes(""))
        assertEquals(emptyList<Int>(), WarpRegistration.reservedBytes("not base64!"))
    }

    @Test
    fun choosesTheIpv4LiteralEndpoint() {
        val (account, _) = registerWithFake()
        assertEquals("162.159.192.7", account.endpointHost)
        assertEquals(2408, account.endpointPort)

        val v6Only = JSONObject("""{"v4": "", "v6": "[2606:4700:d0::a29f:c007]:0", "host": "engage.cloudflareclient.com:2408"}""")
        assertEquals("2606:4700:d0::a29f:c007" to 2408, WarpRegistration.endpoint(v6Only))
        val hostOnly = JSONObject("""{"host": "engage.cloudflareclient.com:500"}""")
        assertEquals(WarpRegistration.FALLBACK_ENDPOINT_V4 to 500, WarpRegistration.endpoint(hostOnly))
    }

    @Test
    fun profileRunsOnTheXrayWireGuardOutbound() {
        val (account, _) = registerWithFake()
        val profile = WarpRegistration.toProfile(account)
        assertNull(RuntimeCapabilities.unsupportedReason(profile))
        assertEquals("Cloudflare WARP", profile.name)
        assertEquals(ProtocolType.WIREGUARD, profile.protocolType)
        assertEquals("162.159.192.7", profile.address)
        assertEquals(2408, profile.port)
        assertEquals(account.privateKey, profile.uuid)
        assertEquals(account.peerPublicKey, profile.publicKey)
        val extras = ProfileExtras.read(profile)
        assertEquals("172.16.0.2/32,2606:4700:110:8a36:df92:102a:9602:fa18/128", extras.getString(ProfileExtras.WG_ADDRESS))
        assertEquals("103,30,156", extras.getString(ProfileExtras.WG_RESERVED))
        assertEquals(1280, extras.getInt(ProfileExtras.WG_MTU))
        assertEquals(profile.id, WarpRegistration.toProfile(account).id)

        val outbound = JSONObject(XrayConfigBuilder.buildJson(profile, AppSettings())).getJSONArray("outbounds")
        val wg = (0 until outbound.length()).map { outbound.getJSONObject(it) }.first { it.optString("protocol") == "wireguard" }
        val settings = wg.getJSONObject("settings")
        assertEquals(account.privateKey, settings.getString("secretKey"))
        assertEquals("[103,30,156]", settings.getJSONArray("reserved").toString())
        assertEquals(1280, settings.getInt("mtu"))
        assertEquals("162.159.192.7:2408", settings.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
    }

    @Test
    fun privateKeyAndTokenNeverAppearInLogs() {
        val (account, _) = registerWithFake()
        val profile = WarpRegistration.toProfile(account)
        val logged = SecretRedactor.redact(profile.toString())
        assertFalse(logged.contains(account.privateKey))
        assertFalse(logged.contains(account.privateKey.trimEnd('=')))
        assertFalse(account.toString().contains(account.privateKey))
        assertFalse(account.toString().contains(account.token))
    }

    @Test
    fun failedRegistrationThrowsWithoutLeakingTheBody() {
        val error = runCatching {
            registerWithFake(code = 429, body = """{"success":false,"token":"abcdef0123456789"}""")
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("429"))
        assertFalse(error.message!!.contains("abcdef0123456789"))
    }

    @Test
    fun registersOnceAndReusesTheStoredAccount() {
        var stored = ""
        var registrations = 0
        val register = { registrations++; registerWithFake().first }
        val first = WarpRegistration.loadOrRegister(stored, { stored = it }, register)
        val second = WarpRegistration.loadOrRegister(stored, { stored = it }, register)
        assertEquals(1, registrations)
        assertEquals(first, second)
        WarpRegistration.loadOrRegister("{broken", { stored = it }, register)
        assertEquals(2, registrations)
    }
}
