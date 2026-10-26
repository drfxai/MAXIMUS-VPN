package com.example

import com.example.vpn.engine.UniversalImportEngine
import org.junit.Assert.*
import org.junit.Test

class HiddifyJsonSubscriptionTest {
    @Test fun importsFullXrayArrayAndDeduplicatesRepeatedConfigurations() {
        val config = """{"remarks":"Hiddify XHTTP","outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"server.example.com","port":443,"users":[{"id":"8b7f6b5a-1234-4567-89ab-1234567890ab","encryption":"none"}]}]},"streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{"serverName":"server.example.com"},"xhttpSettings":{"path":"/test","mode":"auto"}}}]}"""
        val result = UniversalImportEngine.importText("[$config,$config]", sourceSubscriptionUrl = "https://sub.example.com/sub/")
        assertEquals(1, result.validProfiles.size)
        assertEquals(1, result.duplicateCount)
        assertEquals("https://sub.example.com/sub/", result.validProfiles.single().sourceSubscription)
    }
}
