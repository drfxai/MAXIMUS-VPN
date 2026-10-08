package com.example.ai.gateway

/**
 * The shared vocabulary of the AI Gateway. Nothing here carries a credential: requests, responses, errors and
 * model descriptions are safe to log after redaction, and the LLM side never sees a key.
 */

/** What a consumer needs from a model. The router picks by these needs, never by brand name. */
enum class AiTaskClass(val needsVision: Boolean = false, val prefersSpeed: Boolean = false, val prefersReasoning: Boolean = false, val minContext: Int = 0) {
    FAST_CLASSIFICATION(prefersSpeed = true),
    GENERAL_CHAT,
    NETWORK_ANALYSIS(prefersReasoning = true),
    RESEARCH(prefersReasoning = true, minContext = 32_000),
    CODE_REASONING(prefersReasoning = true),
    DIAGNOSTIC_SUMMARY(prefersSpeed = true),
    MULTIMODAL_ANALYSIS(needsVision = true)
}

/** Who is asking; kept in every attempt record so a failure can be traced to its feature. */
enum class AiConsumer { MAIN_AGENT, LAB_AGENT, RESEARCH_AGENT, DIAGNOSTICS, SETTINGS_TEST }

enum class AiRoutingMode {
    /** The user picks provider and model; Maximus only follows the fallback chain the user set. */
    MANUAL,
    /** Maximus picks a capable model for each provider in the chain (a provider's own router first, where it has one). */
    AUTO,
    /** Like AUTO, and also ranks across the chain by capability, health, latency, failures, limits and cost. */
    SMART
}

/** Built-in provider kinds. Others can be added through [ProviderRegistry.register]. */
enum class AiProviderKind(val id: String, val displayName: String, val defaultEndpoint: String?, val keyHint: String) {
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "AIza…"),
    NINE_ROUTER("9router", "9Router", null, "Key from the 9Router dashboard"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "sk-or-…"),
    NVIDIA_NIM("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "nvapi-…"),
    OPENAI("openai", "OpenAI API", "https://api.openai.com/v1", "sk-…"),
    OPENAI_COMPATIBLE("custom", "OpenAI-compatible", null, "Provider key");

    val requiresEndpoint: Boolean get() = defaultEndpoint == null

    companion object {
        fun byId(id: String): AiProviderKind? = entries.firstOrNull { it.id == id }
    }
}

/** A provider the user set up. The key is not here; it lives in [AiCredentialVault] under [id]. */
data class AiProviderConfig(
    val id: String,
    val kind: AiProviderKind,
    val endpoint: String = kind.defaultEndpoint.orEmpty(),
    val enabled: Boolean = true,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** A model id the user typed (newer than discovery, or a provider without discovery). */
    val customModelId: String? = null
) {
    init {
        require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "Provider timeout out of range" }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 45_000L
        const val MIN_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 180_000L
    }
}

enum class RelativeLevel { LOW, MEDIUM, HIGH, UNKNOWN }

enum class ModelSource {
    /** Listed by the provider's own model API. */
    DISCOVERED,
    /** Typed by the user; capabilities unknown until used. */
    CUSTOM,
    /** The provider's own router (OpenRouter auto, a 9Router combo): the provider picks the model. */
    PROVIDER_ROUTER
}

/** How a capability flag was learned: stated by the provider's API, or read from the model's name. */
enum class MetadataSource { DECLARED, INFERRED, UNKNOWN }

data class AiModelDescriptor(
    val providerId: String,
    val modelId: String,
    val displayName: String = modelId,
    val contextWindow: Int? = null,
    val supportsStreaming: Boolean = true,
    val supportsReasoning: Boolean? = null,
    val supportsVision: Boolean? = null,
    val supportsAudio: Boolean? = null,
    val supportsTools: Boolean? = null,
    val supportsStructuredOutput: Boolean? = null,
    val relativeSpeed: RelativeLevel = RelativeLevel.UNKNOWN,
    val relativeCost: RelativeLevel = RelativeLevel.UNKNOWN,
    val health: ProviderHealthState = ProviderHealthState.HEALTHY,
    val discoveredAt: Long = 0,
    val source: ModelSource = ModelSource.DISCOVERED,
    val metadata: MetadataSource = MetadataSource.INFERRED
) {
    val key: String get() = "$providerId/$modelId"
}

data class AiImage(val mimeType: String, val base64: String) {
    init {
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp")) { "Unsupported image type" }
    }
}

data class AiMessage(val role: Role, val text: String, val image: AiImage? = null) {
    enum class Role { USER, ASSISTANT }
}

data class AiChatRequest(
    val task: AiTaskClass,
    val messages: List<AiMessage>,
    val system: String? = null,
    val temperature: Double = 0.4,
    val maxOutputTokens: Int = 2048,
    /** Ask for a JSON object answer where the model supports it. */
    val jsonOutput: Boolean = false
) {
    init {
        require(messages.isNotEmpty()) { "A chat needs at least one message" }
        require(maxOutputTokens in 1..32_768)
        require(temperature in 0.0..2.0)
    }

    val hasImage: Boolean get() = messages.any { it.image != null }
}

/** One provider/model the gateway tried for a request, and how it went. */
data class AiAttempt(
    val providerId: String,
    val modelId: String,
    val outcome: String,
    val error: AiErrorKind? = null,
    val latencyMs: Long = 0
)

data class AiChatResponse(
    val text: String,
    val providerId: String,
    val modelId: String,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val latencyMs: Long = 0,
    val attempts: List<AiAttempt> = emptyList()
)

enum class AiErrorKind {
    NOT_CONFIGURED, AUTH_FAILED, QUOTA_EXHAUSTED, RATE_LIMITED, TIMEOUT, UNREACHABLE, SERVER_ERROR,
    MODEL_NOT_FOUND, BAD_REQUEST, MALFORMED_RESPONSE, CIRCUIT_OPEN, CANCELLED, UNKNOWN;

    /** The fault is the provider's (or the account's), not one model's: try another provider next. */
    val providerWide: Boolean get() = this in setOf(AUTH_FAILED, QUOTA_EXHAUSTED, RATE_LIMITED, TIMEOUT, UNREACHABLE, SERVER_ERROR, CIRCUIT_OPEN, NOT_CONFIGURED)
}

/** A normalized provider error. [message] is already redacted and safe to show. */
data class AiError(val kind: AiErrorKind, val message: String, val httpStatus: Int? = null, val retryAfterMs: Long? = null) {
    /** A short sentence for the user. */
    fun userMessage(): String = when (kind) {
        AiErrorKind.NOT_CONFIGURED -> "No AI provider is set up. Add one in AI settings; the VPN works without it."
        AiErrorKind.AUTH_FAILED -> "The provider refused the API key. Replace it in AI settings."
        AiErrorKind.QUOTA_EXHAUSTED -> "The provider's quota for this key is used up."
        AiErrorKind.RATE_LIMITED -> "The provider asked to slow down. Try again shortly."
        AiErrorKind.TIMEOUT -> "The provider did not answer in time."
        AiErrorKind.UNREACHABLE -> "The provider could not be reached from this network."
        AiErrorKind.SERVER_ERROR -> "The provider had a server error."
        AiErrorKind.MODEL_NOT_FOUND -> "The chosen model is not available any more. Refresh models."
        AiErrorKind.BAD_REQUEST -> "The provider refused the request: $message"
        AiErrorKind.MALFORMED_RESPONSE -> "The provider sent an answer Maximus could not read."
        AiErrorKind.CIRCUIT_OPEN -> "The provider is paused after repeated failures."
        AiErrorKind.CANCELLED -> "Cancelled."
        AiErrorKind.UNKNOWN -> "The AI request failed: $message"
    }
}

class AiException(val error: AiError, val attempts: List<AiAttempt> = emptyList()) : Exception(error.message)

enum class ProviderHealthState { HEALTHY, DEGRADED, RATE_LIMITED, QUOTA_EXHAUSTED, AUTH_FAILED, UNREACHABLE, TEMPORARY_ERROR, DISABLED }

data class ProviderHealth(
    val providerId: String,
    val state: ProviderHealthState,
    val recentLatencyMs: Long? = null,
    val recentErrors: Int = 0,
    val failureStreak: Int = 0,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    /** When a paused provider is next tried; null when it is not paused. */
    val retryAt: Long? = null
)

/** What a provider adapter can do, for the settings screen and the router. */
data class ProviderCapabilities(
    val modelDiscovery: Boolean,
    val streaming: Boolean,
    /** The provider routes between models itself (and that routing is documented by it). */
    val nativeRouting: Boolean,
    val requiresEndpoint: Boolean
)
