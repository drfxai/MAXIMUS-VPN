package com.example

import com.example.core.SecretRedactor
import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.vpn.EndpointResolver
import com.example.vpn.safety.DnsResolvers
import com.example.vpn.safety.FailClosedPolicy
import com.example.vpn.safety.FailClosedPolicy.Failure
import com.example.xray.XrayConfigParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** V1.0.1 step 2: the leaks and gaps found by the step 1 audit (docs/V101_AUDIT.md) stay closed. */
class SecurityBaselineTest {
    private val settings = AppSettings(dnsServer = "https://8.8.8.8/dns-query")

    @Test fun anUnreachableServerNeverReleasesTheTrafficBlock() {
        for (mode in OperationalMode.values()) for (byUser in listOf(true, false)) {
            assertFalse(FailClosedPolicy.mayReleaseBlock(Failure.UNRESOLVABLE_SERVER, byUser, mode))
        }
    }

    @Test fun onlyAnInvalidProfileTheUserPickedInDailyModeReleasesIt() {
        assertTrue(FailClosedPolicy.mayReleaseBlock(Failure.INVALID_PROFILE, startedByUser = true, mode = OperationalMode.DAILY))
        assertFalse(FailClosedPolicy.mayReleaseBlock(Failure.INVALID_PROFILE, startedByUser = false, mode = OperationalMode.DAILY))
        assertFalse(FailClosedPolicy.mayReleaseBlock(Failure.INVALID_PROFILE, startedByUser = true, mode = OperationalMode.GOD_MODE))
    }

    @Test fun namedDnsOverHttpsPresetsRunWithoutALookup() {
        assertEquals("https://8.8.8.8/dns-query", DnsResolvers.literal("https://dns.google/dns-query"))
        assertEquals("https://9.9.9.9/dns-query", DnsResolvers.literal("https://dns.quad9.net/dns-query"))
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4"}]}}]}"""
        val root = JSONObject(XrayConfigParser.sanitizeForExecution(raw, AppSettings(dnsServer = "https://dns.quad9.net/dns-query")))
        assertEquals("https://9.9.9.9/dns-query", root.getJSONObject("dns").getJSONArray("servers").getString(0))
    }

    @Test fun aResolverThatNeedsALookupIsRefused() {
        assertTrue(DnsResolvers.isUsable("1.1.1.1"))
        assertTrue(DnsResolvers.isUsable("https://[2606:4700:4700::1111]/dns-query"))
        assertTrue(DnsResolvers.isUsable("https://dns.google/dns-query"))
        assertFalse(DnsResolvers.isUsable("https://doh.example.com/dns-query"))
        assertFalse(DnsResolvers.isUsable("resolver.example"))
        assertFalse(DnsResolvers.isUsable(""))
    }

    @Test fun anImportedConfigThatSendsEverythingDirectIsRefused() {
        val direct = """{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4"}]}}]}"""
        assertThrows(IllegalArgumentException::class.java) { XrayConfigParser.sanitizeForExecution(direct, settings) }
        val empty = """{"outbounds":[]}"""
        assertThrows(IllegalArgumentException::class.java) { XrayConfigParser.sanitizeForExecution(empty, settings) }
    }

    @Test fun anImportedWireGuardPeerNamedByHostIsRefused() {
        fun wg(endpoint: String) = """{"outbounds":[{"tag":"wg","protocol":"wireguard","settings":{"secretKey":"k","peers":[{"publicKey":"p","endpoint":"$endpoint"}]}}]}"""
        assertThrows(IllegalArgumentException::class.java) { XrayConfigParser.sanitizeForExecution(wg("wg.example.com:51820"), settings) }
        XrayConfigParser.sanitizeForExecution(wg("203.0.113.5:51820"), settings)
        XrayConfigParser.sanitizeForExecution(wg("[2001:db8::5]:51820"), settings)
    }

    @Test fun filteringAnswersInReservedRangesAreRecognised() {
        listOf("100.64.0.1", "100.127.255.1", "198.18.0.1", "198.19.1.1", "240.0.0.1", "255.255.255.255")
            .forEach { assertTrue(it, EndpointResolver.isBlockedAnswer(InetAddress.getByName(it))) }
        listOf("100.63.0.1", "100.128.0.1", "198.20.0.1", "104.16.1.2")
            .forEach { assertFalse(it, EndpointResolver.isBlockedAnswer(InetAddress.getByName(it))) }
    }

    @Test fun logsHideBase64LinksSocksLoginsAndWireGuardKeys() {
        val vmess = "vmess://eyJ2IjoiMiIsImlkIjoiMTExMSIsImFkZCI6IjEuMi4zLjQifQ=="
        assertFalse(SecretRedactor.redact("import $vmess failed").contains("eyJ2Ijoi"))
        assertFalse(SecretRedactor.redact("ss://YWVzLTI1Ni1nY206cGFzcw== bad").contains("YWVzLTI1"))
        assertFalse(SecretRedactor.redact("socks5://alice:hunter2@203.0.113.1:1080").contains("hunter2"))
        val json = SecretRedactor.redact("""{"preSharedKey":"psk-value","auth":"hy2-pass"}""")
        assertFalse(json.contains("psk-value"))
        assertFalse(json.contains("hy2-pass"))
        // Ordinary https URLs and words are left alone.
        assertEquals("open https://example.com/ss/page", SecretRedactor.redact("open https://example.com/ss/page"))
    }
}
