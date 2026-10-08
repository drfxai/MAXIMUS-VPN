package com.example.vpn.connectivity

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage 12: the AI layer stays advisory. Its code (com.example.ai) may not reach anything that marks
 * configs alive or dead, changes or connects configs, touches the kill switch, feeds intelligence rules,
 * recovery or endpoint scores, or runs commands. The runtime allowlist is tested in PrivateDnsAndSecurityTest.
 */
class AiBoundaryTest {
    private val forbidden = listOf(
        "serverRepository", "settingsRepository", "subscriptionManager", "SubscriptionManager",
        "freeConfigEvidence", "FreeConfigEvidence", "lastKnownGood", "LastKnownGood",
        "iranIntel", "IranIntelligence", "recoveryLedger", "RecoveryLedger", "bpbRecovery", "BpbRecoveryEngine",
        "dnsResilience", "endpointScores", "fragmentProfiles", "SmartFailoverPolicy", "FailoverManager",
        "killSwitch", "KillSwitch", "FailClosedPolicy", "VpnController.connect", "VpnController.disconnect",
        "Runtime.getRuntime", "ProcessBuilder", "DexClassLoader", "loadLibrary"
    )

    private fun aiSources(): List<File> {
        val dir = listOf("src/main/java/com/example/ai", "app/src/main/java/com/example/ai").map(::File).first { it.isDirectory }
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test fun theAiLayerCannotReachAnythingThatChangesConnections() {
        val sources = aiSources()
        assertTrue(sources.isNotEmpty())
        val hits = sources.flatMap { f ->
            val text = f.readText()
            forbidden.filter { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(text) }.map { "${f.name}: $it" }
        }
        assertTrue("AI code reaches: $hits", hits.isEmpty())
    }
}
