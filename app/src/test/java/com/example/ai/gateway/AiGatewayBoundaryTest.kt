package com.example.ai.gateway

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guarantees for the AI layer (com.example.ai and below; AiBoundaryTest already keeps all of it
 * away from connection control):
 * - the raw key leaves the vault only through ProviderRegistry, to adapters;
 * - no consumer (agents, view models, screens) touches the vault's reader or a CredentialReader;
 * - no code outside the gateway calls a provider API directly;
 * - nothing logs a key.
 */
class AiGatewayBoundaryTest {
    private fun dir(path: String) = listOf("src/main/java/$path", "app/src/main/java/$path").map(::File).first { it.isDirectory }
    private fun sources(path: String) = dir(path).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val adapterFiles = setOf("OpenAiCompatibleAdapterBase.kt", "GeminiAdapter.kt")

    @Test fun onlyTheRegistryReadsTheVault() {
        val hits = (sources("com/example/ai") + sources("com/example/ui") + sources("com/example/vpn")).filter { f ->
            f.name != "AiCredentialVault.kt" && f.name != "ProviderRegistry.kt" && Regex("\\.reader\\(\\)").containsMatchIn(f.readText())
        }
        assertTrue("vault reader used in $hits", hits.isEmpty())
    }

    @Test fun onlyAdaptersAndTheRegistryHoldACredentialReader() {
        val allowed = adapterFiles + setOf("AiProviderAdapter.kt", "AiCredentialVault.kt", "ProviderRegistry.kt")
        val hits = (sources("com/example") ).filter { it.name !in allowed && it.readText().contains("CredentialReader") }
        assertTrue("CredentialReader used in $hits", hits.isEmpty())
    }

    @Test fun providerApisAreOnlyCalledInsideTheGateway() {
        val apiHosts = listOf("generativelanguage.googleapis.com", "api.openai.com", "openrouter.ai", "integrate.api.nvidia.com", "chat/completions", ":generateContent")
        val gateway = dir("com/example/ai/gateway").canonicalPath
        val hits = sources("com/example").filter { f -> !f.canonicalPath.startsWith(gateway) }.flatMap { f ->
            val t = f.readText()
            apiHosts.filter { t.contains(it) }.map { "${f.name}: $it" }
        }
        assertTrue("provider API outside the gateway: $hits", hits.isEmpty())
    }

    @Test fun keysAreNeverLogged() {
        val hits = sources("com/example/ai").flatMap { f ->
            f.readLines().withIndex().filter { (_, line) ->
                Regex("(XrayLogManager|Log\\.|println|log\\()").containsMatchIn(line) && Regex("(?i)\\b(key|apiKey|rawKey|credential)\\b\\s*[})]").containsMatchIn(line)
            }.map { "${f.name}:${it.index + 1}" }
        }
        assertTrue("possible key logging at $hits", hits.isEmpty())
    }
}
