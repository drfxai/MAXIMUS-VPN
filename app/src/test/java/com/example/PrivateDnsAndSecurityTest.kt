package com.example

import com.example.ai.AiAgentTools
import kotlinx.coroutines.runBlocking
import com.example.chat.CryptoE2ee
import com.example.core.AiPrivacyFilter
import com.example.data.model.AppSettings
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.panels.CloudflareTokenHelper
import com.example.vpn.tunnel.ProxyDnsTransport
import com.example.xray.XrayConfigBuilder
import com.example.xray.XrayConfigParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateDnsAndSecurityTest {
    private val uuid = "12345678-1234-1234-1234-123456789abc"
    private val settings = AppSettings(dnsServer = "https://8.8.8.8/dns-query", customDns = "1.1.1.1")
    private fun profile(protocol: ProtocolType = ProtocolType.VLESS, port: Int = 443) =
        VlessProfile(name = "test", address = "127.0.0.1", port = port,
            uuid = if (protocol == ProtocolType.VLESS) uuid else "", protocolType = protocol)

    @Test fun modelCannotAuthorizeMutationsOrUnknownTools() = runBlocking {
        val attempts = listOf("configure_usage_mode", "modify_vpn_settings", "connect_vpn_server",
            "select_and_connect_server", "find_and_apply_clean_ip", "revive_profile_with_clean_ip",
            "disconnect_vpn", "approve_action", "future_mutation")
        for (name in attempts) {
            val result = AiAgentTools.executeTool(name, mapOf(
                "userConfirmed" to true, "approved" to true, "confirmation" to "yes",
                "approval_token" to "forged", "kill_switch_enabled" to false))
            assertFalse(result.success)
            assertTrue(result.details.isEmpty())
        }
        assertEquals(listOf("get_app_diagnostics_and_logs"),
            AiAgentTools.TOOL_DECLARATIONS.flatMap { it.functionDeclarations }.map { it.name })
    }

    @Test fun plainVlessDohUsesProxyTlsAndRejectsUntrustedOrMismatchedCertificates() {
        val keyStore = java.security.KeyStore.getInstance("PKCS12").apply {
            val fixture = java.util.Base64.getMimeDecoder().decode(
                PrivateDnsAndSecurityTest::class.java.getResourceAsStream("/doh-test-fixture.p12.base64")!!.readBytes())
            load(ByteArrayInputStream(fixture), "testfixture".toCharArray())
        }
        val km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "testfixture".toCharArray())
        }
        val tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        val trusted = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
        val original = javax.net.ssl.SSLContext.getDefault()
        try {
            for ((trust, host) in listOf(true to "127.0.0.1", false to "127.0.0.1", true to "127.0.0.2")) {
                javax.net.ssl.SSLContext.setDefault(if (trust) trusted else original)
                ServerSocket(0).use { proxy ->
                    proxy.soTimeout = 4000
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val server = executor.submit {
                            proxy.accept().use { socket ->
                                socket.soTimeout = 4000
                                val expected = com.example.vless.VlessHeader.encodeRequest(
                                    com.example.vless.VlessHeader.uuidToBytes(uuid),
                                    com.example.vless.VlessHeader.COMMAND_TCP, 443, host)
                                val received = ByteArray(expected.size)
                                DataInputStream(socket.getInputStream()).readFully(received)
                                assertArrayEquals(expected, received)
                                // A real server may wait for payload before returning VLESS response.
                                val deadline = System.nanoTime() + 3_000_000_000L
                                while (socket.getInputStream().available() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
                                check(socket.getInputStream().available() > 0) { "Client waited for header before ClientHello" }
                                socket.getOutputStream().write(byteArrayOf(0, 2, 42, 43))
                                socket.getOutputStream().flush()
                                val tls = trusted.socketFactory.createSocket(socket, host, 443, false) as javax.net.ssl.SSLSocket
                                tls.use {
                                    tls.useClientMode = false
                                    tls.startHandshake()
                                    val input = tls.inputStream
                                    val header = StringBuilder()
                                    while (!header.endsWith("\r\n\r\n")) header.append(input.read().also { check(it >= 0) }.toChar())
                                    check(header.startsWith("POST /dns-query HTTP/1.1"))
                                    val query = ByteArray(12)
                                    DataInputStream(input).readFully(query)
                                    check(query[0] == 42.toByte())
                                    tls.outputStream.write(("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 12\r\n\r\n").toByteArray() + query)
                                    tls.outputStream.flush()
                                }
                            }
                        }
                        val query = ByteArray(12).also { it[0] = 42 }
                        var protected = 0
                        val exchange = {
                            ProxyDnsTransport.exchange(profile(port = proxy.localPort), "https://$host/dns-query", query) { protected++; true }
                        }
                        if (trust && host == "127.0.0.1") {
                            assertArrayEquals(query, exchange())
                            server.get(5, TimeUnit.SECONDS)
                        } else assertThrows(Exception::class.java) { exchange() }
                        assertEquals(1, protected)
                    } finally { executor.shutdownNow() }
                }
            }
        } finally { javax.net.ssl.SSLContext.setDefault(original) }
    }

    @Test fun selectedDohHasNoPlaintextOrLocalFallbacksOrNetworkListeners() {
        val root = JSONObject(XrayConfigBuilder.buildJson(profile(), settings))
        assertEquals(0, root.getJSONArray("inbounds").length())
        assertFalse(root.has("api"))
        assertFalse(root.has("metrics"))
        val dns = root.getJSONObject("dns")
        assertEquals(1, dns.getJSONArray("servers").length())
        assertEquals(settings.dnsServer, dns.getJSONArray("servers").getString(0))
        assertTrue(dns.getBoolean("disableFallback"))
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertEquals("proxy", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("53", rules.getJSONObject(1).getString("port"))
    }

    @Test fun disabledIpv6IsBlockedInsideNativeTunnel() {
        val root = JSONObject(XrayConfigBuilder.buildJson(profile(), settings.copy(ipv6Enabled = false)))
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertEquals("::/0", rules.getJSONObject(2).getJSONArray("ip").getString(0))
        assertEquals("private-ipv6-block", rules.getJSONObject(2).getString("outboundTag"))
    }

    @Test fun unsafeFingerprintUsesStandardTlsWithoutDisablingVerification() {
        val mask = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["0","104","1"],"delays":["0"],"maxSplit":"0"}}]}"""
        val root = JSONObject(
            XrayConfigBuilder.buildJson(
                profile().copy(
                    fingerprint = "unsafe", security = "tls", transport = "ws", sni = "w.example.com",
                    cipherSuites = "TLS_AES_256_GCM_SHA384", alpn = "http/1.1", finalMask = mask
                ),
                settings
            )
        )
        val outbounds = root.getJSONArray("outbounds")
        val proxy = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.first { it.optString("tag") == "proxy" }
        val stream = proxy.getJSONObject("streamSettings")
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals("unsafe", tls.getString("fingerprint"))
        assertEquals("TLS_AES_256_GCM_SHA384", tls.getString("cipherSuites"))
        assertFalse(tls.optBoolean("allowInsecure", false))
        assertEquals("fragment", stream.getJSONObject("finalmask").getJSONArray("tcp").getJSONObject(0).getString("type"))
    }

    @Test fun malformedFinalMaskIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigBuilder.buildJson(profile().copy(security = "tls", finalMask = "{broken"), settings)
        }
    }

    @Test fun malformedImportNeverRunsUnchanged() {
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigParser.sanitizeForExecution("{invalid", settings)
        }
    }

    @Test fun importedInsecureTlsIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigParser.sanitizeForExecution("""{"outbounds":[{"protocol":"vless","streamSettings":{"tlsSettings":{"allowInsecure":true}}}]}""", settings)
        }
    }

    @Test fun importedListenersAndFallbacksAreRemoved() {
        val raw = """{"api":{"tag":"api"},"metrics":{"listen":"0.0.0.0:1234"},"inbounds":[{"port":10808,"protocol":"socks"}],"dns":{"servers":["localhost"]},"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4"}]}}]}"""
        val root = JSONObject(XrayConfigParser.sanitizeForExecution(raw, settings))
        assertEquals(0, root.getJSONArray("inbounds").length())
        assertFalse(root.has("api"))
        assertFalse(root.has("metrics"))
        assertEquals(settings.dnsServer, root.getJSONObject("dns").getJSONArray("servers").getString(0))
    }

    @Test fun publicIdsCannotProduceEncryptionKeys() {
        assertThrows(UnsupportedOperationException::class.java) { CryptoE2ee.deriveSharedKey("public1", "public2") }
    }

    @Test fun cloudflareTemplatePrefillsWorkerAndKvPermissions() {
        val url = android.net.Uri.decode(CloudflareTokenHelper.buildOneClickTokenUrl())
        assertTrue(url.startsWith("https://dash.cloudflare.com/profile/api-tokens?"))
        assertTrue(url.contains("workers_scripts"))
        assertTrue(url.contains("workers_kv_storage"))
        assertTrue(url.contains("accountId=*"))
        assertTrue(url.contains("zoneId=all"))
        assertFalse(url.contains("\"pages\""))
        assertFalse(url.contains("\"dns\""))
    }

    @Test fun aiBoundaryRemovesSecretsEndpointsAndNestedValues() {
        val text = "apiToken=supersecret adminPassword=topsecret https://example.com/token/hidden 1.2.3.4:443 [2001:db8::1] node.example.org Bearer abcdef"
        val clean = AiPrivacyFilter.redact(text)
        for (secret in listOf("supersecret", "topsecret", "example.com", "1.2.3.4", "2001:db8", "node.example.org", "abcdef")) assertFalse(clean.contains(secret))
        assertEquals(listOf("[REDACTED_IP]"), AiPrivacyFilter.sanitize(listOf("1.2.3.4")))
    }

    @Test fun dohParserRejectsTruncationDuplicateLengthsAndWrongMimeType() {
        for (raw in listOf(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 12\r\n\r\n012345678901",
            "HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 12\r\nContent-Length: 12\r\n\r\n012345678901",
            "HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 12\r\n\r\nshort")) {
            assertThrows(Exception::class.java) { ProxyDnsTransport.readHttpResponse(ByteArrayInputStream(raw.toByteArray())) }
        }
    }

    @Test fun dohParserAcceptsBoundedChunkedAndFixedResponses() {
        val payload = "012345678901"
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\n"
        for (raw in listOf(headers + "Content-Length: 12\r\n\r\n" + payload,
            headers + "Transfer-Encoding: chunked\r\n\r\nc\r\n" + payload + "\r\n0\r\n\r\n")) {
            assertArrayEquals(payload.toByteArray(), ProxyDnsTransport.readHttpResponse(ByteArrayInputStream(raw.toByteArray())))
        }
    }

    @Test fun plainDnsConnectsOnlyToHttpProxyAndUsesConfiguredResolver() {
        ServerSocket(0).use { proxy ->
            proxy.soTimeout = 3000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val job = executor.submit<ByteArray> {
                    proxy.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) header.append(input.read().also { check(it >= 0) }.toChar())
                        check(header.startsWith("CONNECT 9.9.9.9:53 HTTP/1.1"))
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                        val data = DataInputStream(input)
                        val query = ByteArray(data.readUnsignedShort()).also(data::readFully)
                        val response = query.copyOf().also { it[2] = 0x80.toByte() }
                        socket.getOutputStream().write(byteArrayOf(0, response.size.toByte()) + response)
                        response
                    }
                }
                val query = ByteArray(12).also { it[0] = 42 }
                var protected = 0
                val response = ProxyDnsTransport.exchange(profile(ProtocolType.HTTP, proxy.localPort), "9.9.9.9", query) { protected++; true }
                assertArrayEquals(job.get(4, TimeUnit.SECONDS), response)
                assertEquals(1, protected)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun protectionFailurePreventsAnyProxyConnection() {
        assertThrows(IllegalStateException::class.java) {
            ProxyDnsTransport.exchange(profile(ProtocolType.HTTP), "9.9.9.9", ByteArray(12)) { false }
        }
    }
}
