package com.example.ai.gateway

import kotlinx.coroutines.flow.Flow

/**
 * One AI provider's API. Every provider-specific request body, header and error format stays inside its
 * adapter; the gateway, router and agents only see the types in AiTypes.kt.
 *
 * Failures are thrown as [AiException] carrying a normalized [AiError]. Adapters read the provider's key
 * from their [CredentialReader] immediately before each request and never return, log or store it.
 */
interface AiProviderAdapter {
    val config: AiProviderConfig
    val providerId: String get() = config.id

    /** Checks the key with the cheapest call the provider offers (listing models). */
    suspend fun validateCredentials()

    /** The provider's chat models, from its own model API. Throws when the provider has no such API. */
    suspend fun discoverModels(): List<AiModelDescriptor>

    suspend fun sendChat(modelId: String, request: AiChatRequest): AiChatResponse

    /** Text deltas as they arrive. */
    fun streamChat(modelId: String, request: AiChatRequest): Flow<String>

    /** A health check: one small call (models list), measured. */
    suspend fun getProviderHealth(): ProviderHealth

    /** The normalized error for an HTTP status and body, or a transport exception. */
    fun normalizeError(httpStatus: Int?, body: String?, cause: Throwable?, retryAfterHeader: String? = null): AiError

    fun getCapabilityMetadata(): ProviderCapabilities
}

/**
 * Hands a provider's raw key to its adapter at request time. Only [ProviderRegistry] gets one from the vault,
 * and only adapters receive it; agents, the router and prompts never do.
 */
fun interface CredentialReader {
    fun read(providerId: String): String?
}

/**
 * Where a provider may be reached. Keys only travel over HTTPS; plain HTTP is accepted only for a router
 * running on this phone (127.0.0.1 / localhost, e.g. 9Router in a terminal app).
 */
object EndpointPolicy {
    fun problem(endpoint: String): String? {
        val e = endpoint.trim()
        if (e.isEmpty()) return "Enter the provider's address"
        if (e.any { it.isWhitespace() || it.code < 0x20 }) return "The address has spaces or hidden characters"
        val scheme = e.substringBefore("://", "").lowercase()
        val rest = e.substringAfter("://", "")
        val host = rest.substringBefore('/').substringBefore('?').let { h ->
            if (h.startsWith("[")) h.substringBefore(']') + "]" else h.substringBefore(':')
        }.lowercase()
        if (host.isEmpty()) return "The address has no host"
        if (rest.substringBefore('/').contains('@')) return "The address may not carry a user name or password"
        return when (scheme) {
            "https" -> null
            "http" -> if (host == "127.0.0.1" || host == "localhost" || host == "[::1]") null
                else "Use https:// (plain http is only allowed for a router on this phone)"
            else -> "The address must start with https://"
        }
    }

    /** The endpoint without a trailing slash, for joining paths. */
    fun normalize(endpoint: String): String = endpoint.trim().trimEnd('/')
}
