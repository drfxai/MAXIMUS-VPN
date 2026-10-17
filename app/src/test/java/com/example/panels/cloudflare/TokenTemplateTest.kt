package com.example.panels.cloudflare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** Cloudflare token template links and the copied-token check. */
class TokenTemplateTest {
    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun smartDomainsLinkPrefillsZoneReadAndDnsEditOnly() {
        val url = TokenTemplate.url(TokenTemplate.SMART_DOMAINS, TokenTemplate.SMART_DOMAINS_NAME)
        assertTrue(url.startsWith("https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys="))
        val q = query(url)
        assertEquals("""[{"key":"zone","type":"read"},{"key":"dns","type":"edit"}]""", q["permissionGroupKeys"])
        assertEquals("*", q["accountId"])
        assertEquals("all", q["zoneId"])
        assertEquals("Maximus smart domains", q["name"])
        assertFalse(url.contains("workers"))
    }

    @Test fun linkIsFullyEncodedWithPercent20ForSpaces() {
        val url = TokenTemplate.url(TokenTemplate.SMART_DOMAINS, TokenTemplate.SMART_DOMAINS_NAME)
        assertTrue(url.endsWith("&name=Maximus%20smart%20domains"))
        assertTrue(url.contains("&accountId=%2A&zoneId=all&"))
        assertTrue(url.contains("%5B%7B%22key%22%3A%22zone%22%2C%22type%22%3A%22read%22%7D%2C"))
        assertFalse(url.contains('+'))
        assertFalse(url.contains(' '))
        assertFalse(url.substringAfter('?').contains('"'))
        assertFalse(url.substringAfter('?').contains('['))
    }

    @Test fun workerLinkKeepsItsPermissions() {
        val q = query(TokenTemplate.url(TokenTemplate.WORKER, TokenTemplate.WORKER_NAME))
        assertEquals(
            """[{"key":"workers_scripts","type":"edit"},{"key":"workers_kv_storage","type":"edit"},{"key":"account_settings","type":"read"}]""",
            q["permissionGroupKeys"]
        )
        assertEquals("Maximus-BPB-Worker", q["name"])
        assertFalse(q.getValue("permissionGroupKeys").contains("\"dns\""))
    }

    @Test fun chipsNameEveryPermission() {
        assertEquals(listOf("Zone · Read", "DNS · Edit"), TokenTemplate.SMART_DOMAINS.map { it.label })
        assertEquals(3, TokenTemplate.WORKER.size)
    }

    @Test fun tokenShapeIs40UrlSafeCharacters() {
        val token = "aB3_-" + "x".repeat(35)
        assertTrue(TokenTemplate.looksLikeToken(token))
        assertTrue(TokenTemplate.looksLikeToken("  $token\n"))
        assertFalse(TokenTemplate.looksLikeToken(null))
        assertFalse(TokenTemplate.looksLikeToken(""))
        assertFalse(TokenTemplate.looksLikeToken(token.dropLast(1)))
        assertFalse(TokenTemplate.looksLikeToken(token + "a"))
        assertFalse(TokenTemplate.looksLikeToken(token.dropLast(1) + "="))
        assertFalse(TokenTemplate.looksLikeToken(token.dropLast(1) + " "))
        assertFalse(TokenTemplate.looksLikeToken("vless://" + "a".repeat(32)))
        assertFalse(TokenTemplate.looksLikeToken("https://example.com/" + "a".repeat(20)))
    }

    @Test fun maskedShowsOnlyTheLastFour() {
        val token = "a".repeat(36) + "Xy9z"
        val m = TokenTemplate.masked(token)
        assertEquals("••••Xy9z", m)
        assertFalse(m.contains("aaaa"))
    }
}
