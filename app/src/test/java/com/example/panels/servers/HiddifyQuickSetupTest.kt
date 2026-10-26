package com.example.panels.servers

import com.example.panels.servers.panel.HiddifyQuickSetup
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class HiddifyQuickSetupTest {
    private val adminId = "8b7f6b5a-1234-4567-89ab-1234567890ab"
    private val userId = "1b7f6b5a-1234-4567-89ab-1234567890ab"
    private val admin = "https://panel.example/AdminPath/$adminId/"

    @Test fun creationUsesOfficialContractAndDoesNotForwardAdminIdentityToSubscription() {
        val requests = mutableListOf<okhttp3.Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests += req
            assertEquals("https://panel.example/AdminPath/api/v2/admin/users/", req.url.toString())
            assertEquals(adminId, req.header("Hiddify-API-Key"))
            val payload = if (req.method == "GET")
                """{"can_add":true,"users":[],"link_domains":[{"base":"https://other.example/wrong/"},{"base":"https://panel.example/UserPath/"}]}"""
            else {
                val buffer = okio.Buffer()
                req.body!!.writeTo(buffer)
                val body = org.json.JSONObject(buffer.readUtf8())
                assertEquals(userId, body.getString("uuid"))
                assertEquals(30, body.getInt("package_days"))
                assertEquals(100, body.getInt("usage_limit_GB"))
                """{"user":{"uuid":"$userId"}}"""
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(payload.toResponseBody()).build()
        }.build()
        val url = HiddifyQuickSetup(http).createUser(admin, "Maximus", 30, 100, userId)
        assertEquals("https://panel.example/UserPath/$userId/", url)
        assertFalse(url.contains(adminId))
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
    }

    @Test fun pendingOperationRecoversExistingUserWithoutAnotherPost() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("GET", chain.request().method)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"can_add":false,"users":[{"uuid":"$userId"}],"link_domains":[{"base":"https://panel.example/UserPath/"}]}""".toResponseBody()).build()
        }.build()
        assertTrue(HiddifyQuickSetup(http).createUser(admin, "Maximus", 30, 100, userId).contains(userId))
        assertEquals(1, calls)
    }

    @Test fun deniedOrRedirectedRequestsNeverCreateUsersOrExposeResponseSecrets() {
        for (code in listOf(301, 401, 403, 404)) {
            var calls = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Denied")
                    .header("Location", "https://other.example/").body("secret-response-body".toResponseBody()).build()
            }.build()
            val error = assertThrows(java.io.IOException::class.java) {
                HiddifyQuickSetup(http).createUser(admin, "Maximus", 30, 100, userId)
            }
            assertEquals(1, calls)
            assertFalse(error.message.orEmpty().contains("secret-response-body"))
        }
    }

    @Test fun telegramLinksAreExplicitlyTypedAndUnsafeSchemesAreRejected() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Hiddify-API-Key"))
            assertEquals("/UserPath/$userId/api/v2/user/mtproxies/", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""[{"link":"tg://proxy?server=example.com&port=443&secret=test"},{"link":"tg://webproxy?server=example.com/path"},{"link":"javascript:alert(1)"}]""".toResponseBody()).build()
        }.build()
        val links = HiddifyQuickSetup(http).telegram("https://panel.example/UserPath/$userId/?allConfigs=true")
        assertEquals(2, links.size)
        assertFalse(links[0].web)
        assertTrue(links[1].web)
    }

    @Test fun linkValidationRejectsCrossOriginAndEncodedPaths() {
        assertFalse(HiddifyQuickSetup.sameOriginBase("https://other.example/path/", "https://panel.example"))
        assertFalse(HiddifyQuickSetup.sameOriginBase("https://panel.example/%2e%2e/", "https://panel.example"))
        assertThrows(IllegalArgumentException::class.java) { HiddifyQuickSetup.admin("https://panel.example/path/admin/v2/") }
        assertThrows(IllegalArgumentException::class.java) { HiddifyQuickSetup.userBase("http://panel.example/path/$userId/") }
    }

    @Test fun pinnedCottenGeneratedKeyPassesActualShellGate() {
        val script = DnsServerScripts.cotten("tun.example.com", "amd64").text
        val gate = script.lines().first { it.contains("grep -Eq '^[a-fA-F0-9]") }
        for (size in listOf(32, 64)) {
            val p = ProcessBuilder("sh", "-c", gate).redirectErrorStream(true).apply { environment()["key"] = "a".repeat(size) }.start()
            val output = p.inputStream.bufferedReader().readText()
            assertEquals(if (size == 32) 0 else 40, p.waitFor())
            assertFalse(output.contains("a".repeat(size)))
        }
        assertTrue(script.contains("ENCRYPTION_AUTO_DETECT = false"))
    }
}
