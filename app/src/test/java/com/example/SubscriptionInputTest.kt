package com.example

import com.example.vpn.subscription.SubscriptionInput
import com.example.vpn.subscription.SubscriptionSources
import org.junit.Assert.*
import org.junit.Test

class SubscriptionInputTest {
    @Test fun detectsDomainAndSubdomainLinksWithOrWithoutScheme() {
        assertEquals("https://sub.example.com/user/path?allConfigs=true", SubscriptionInput.url("sub.example.com/user/path?allConfigs=true"))
        assertEquals("https://sub.example.com/user/path#name", SubscriptionInput.url("https://sub.example.com/user/path#name"))
        assertEquals("https://sub.example.com", SubscriptionInput.url("sub.example.com"))
        val parsed = SubscriptionSources.parseInput("sub.example.com/path\nhttps://mirror.example.com/path")
        assertEquals("https://sub.example.com/path", parsed.first)
        assertEquals(listOf("https://mirror.example.com/path"), parsed.second)
    }
    @Test fun hiddifyAllConfigurationsUsesOfficialSubEndpointWithoutChangingHostname() {
        val id = "8b7f6b5a-1234-4567-89ab-1234567890ab"
        assertEquals("https://panel.example/UserPath/$id/sub/",
            SubscriptionInput.url("https://panel.example/UserPath/$id/?allConfigs=true"))
        assertEquals("https://panel.example/other?token=keep",
            SubscriptionInput.url("https://panel.example/other?token=keep"))
    }
    @Test fun configsCredentialsAndMultilinePayloadAreNotSubscriptionUrls() {
        for (input in listOf("vless://id@example.com:443", "javascript:alert(1)", "https://u:p@example.com/path", "one two", "[Peer]")) {
            assertNull(input, SubscriptionInput.url(input))
        }
    }
    @Test fun certificateFailureExplainsRepairWithoutEchoingSecretPath() {
        val message = SubscriptionInput.failure(javax.net.ssl.SSLPeerUnverifiedException("Hostname mismatch https://example.com/SecretPath/"))
        assertTrue(message.contains("hostname"))
        assertFalse(message.contains("SecretPath"))
    }
}
