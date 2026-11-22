package com.example.vpn.diagnostics.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Copy full report": every section reaches the clipboard, and no secret does. */
class FullReportTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"
    private val wgKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----"

    private val leaky = listOf(
        "vless://$uuid@203.0.113.7:443?security=reality&pbk=Zb4p0aPbKpUbXq&sid=6ba85179e30d4fc2#Home",
        "trojan://S3cretTrojanPass@example.com:443?sni=example.com",
        "hysteria2://hyPassw0rd@example.com:8443?obfs-password=obfsSecret99",
        "socks5://alice:sockspass@198.51.100.4:1080",
        "naive+https://bob:naivepass@proxy.example.com:443",
        "tuic://example.com:443?password=tuicPw123&uuid=$uuid",
        "[Interface]\nPrivateKey = $wgKey\nPresharedKey: $wgKey",
        "\"privateKey\": \"$wgKey\"",
        "server id $uuid failed",
        pem
    ).joinToString("\n")

    private val secrets = listOf(
        uuid, "Zb4p0aPbKpUbXq", "6ba85179e30d4fc2", "S3cretTrojanPass", "hyPassw0rd", "obfsSecret99",
        "sockspass", "alice", "naivepass", "tuicPw123", wgKey, "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC", "203.0.113.7"
    )

    @Test
    fun redactRemovesUuidsPasswordsAndPrivateKeys() {
        val out = FullReport.redact(leaky)
        secrets.forEach { assertFalse("leaked $it in:\n$out", out.contains(it)) }
        assertTrue(out.contains("[REDACTED_PEM_BLOCK]"))
        // Useful, non-secret parts survive.
        assertTrue(out.contains("vless://"))
        assertTrue(out.contains("example.com"))
    }

    @Test
    fun composeKeepsEverySectionAndHeaderAndRedactsThem() {
        val text = FullReport.compose(
            header = listOf("App: 1.0.4 (versionCode 169, build 169, commit f1216ef)", "Network type: Wi-Fi · unmetered"),
            sections = listOf(
                FullReport.Section("Overview", "Active Server: $uuid"),
                FullReport.Section("Engines", "xray: verified\nsingbox: bundled"),
                FullReport.Section("Detailed report (JSON)", "{\"password\": \"hunter2hunter2\"}"),
                FullReport.Section("Empty", "  ")
            )
        )
        assertTrue(text.contains("App: 1.0.4 (versionCode 169, build 169, commit f1216ef)"))
        assertTrue(text.contains("Network type: Wi-Fi · unmetered"))
        listOf("OVERVIEW", "ENGINES", "DETAILED REPORT (JSON)", "EMPTY").forEach { assertTrue(text.contains("===== $it =====")) }
        assertTrue(text.contains("singbox: bundled"))
        assertTrue(text.contains("(empty)"))
        assertTrue(text.contains("END OF FULL REPORT"))
        assertFalse(text.contains(uuid))
        assertFalse(text.contains("hunter2hunter2"))
    }

    @Test
    fun composeCapsTheClipboardSize() {
        val big = (1..40_000).joinToString("\n") { "line $it ok" }
        val text = FullReport.compose(emptyList(), listOf(FullReport.Section("Events", big)))
        assertEquals(FullReport.MAX_CHARS, text.length)
        assertTrue(text.trimEnd().endsWith("use Export Report for the complete JSON file.]"))
    }
}
