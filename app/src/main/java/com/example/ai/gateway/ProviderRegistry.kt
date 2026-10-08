package com.example.ai.gateway

import okhttp3.OkHttpClient

/**
 * Builds provider adapters from the user's [AiProviderConfig]s. The only holder of the vault's
 * [CredentialReader]: it passes it to adapters and to nothing else. New provider kinds are added with
 * [register]; the six built-in ones are registered by default.
 */
class ProviderRegistry(
    vault: AiCredentialVault,
    private val client: OkHttpClient = ProviderHttp.sharedClient
) {
    fun interface Factory {
        fun create(config: AiProviderConfig, credentials: CredentialReader, client: OkHttpClient): AiProviderAdapter
    }

    private val credentials: CredentialReader = vault.reader()
    private val factories = java.util.concurrent.ConcurrentHashMap<AiProviderKind, Factory>()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<AiProviderConfig, AiProviderAdapter>>()

    init {
        register(AiProviderKind.GEMINI) { c, k, h -> GeminiAdapter(c, k, h) }
        register(AiProviderKind.OPENAI) { c, k, h -> OpenAiAdapter(c, k, h) }
        register(AiProviderKind.OPENROUTER) { c, k, h -> OpenRouterAdapter(c, k, h) }
        register(AiProviderKind.NVIDIA_NIM) { c, k, h -> NvidiaNimAdapter(c, k, h) }
        register(AiProviderKind.NINE_ROUTER) { c, k, h -> NineRouterAdapter(c, k, h) }
        register(AiProviderKind.OPENAI_COMPATIBLE) { c, k, h -> GenericOpenAiCompatibleAdapter(c, k, h) }
    }

    fun register(kind: AiProviderKind, factory: Factory) {
        factories[kind] = factory
        cache.values.removeAll { it.first.kind == kind }
    }

    fun isRegistered(kind: AiProviderKind): Boolean = factories.containsKey(kind)

    /** The adapter for [config], rebuilt when the config changed. */
    fun adapter(config: AiProviderConfig): AiProviderAdapter {
        cache[config.id]?.let { (c, a) -> if (c == config) return a }
        val factory = factories[config.kind] ?: throw AiException(AiError(AiErrorKind.NOT_CONFIGURED, "No adapter for ${config.kind.displayName}"))
        val adapter = factory.create(config, credentials, client)
        cache[config.id] = config to adapter
        return adapter
    }
}
