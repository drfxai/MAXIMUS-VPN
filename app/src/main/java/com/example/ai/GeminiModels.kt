package com.example.ai

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    @Json(name = "contents") val contents: List<GeminiContent>,
    @Json(name = "generationConfig") val generationConfig: GeminiGenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: GeminiContent? = null,
    @Json(name = "tools") val tools: List<GeminiToolWrapper>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    @Json(name = "role") val role: String? = null,
    @Json(name = "parts") val parts: List<GeminiPart>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inlineData") val inlineData: GeminiInlineData? = null,
    @Json(name = "functionCall") val functionCall: GeminiFunctionCall? = null,
    @Json(name = "functionResponse") val functionResponse: GeminiFunctionResponse? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "data") val data: String
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionCall(
    @Json(name = "name") val name: String,
    @Json(name = "args") val args: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionResponse(
    @Json(name = "name") val name: String,
    @Json(name = "response") val response: Map<String, Any?>
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    @Json(name = "temperature") val temperature: Float? = null,
    @Json(name = "topP") val topP: Float? = null,
    @Json(name = "topK") val topK: Int? = null,
    @Json(name = "maxOutputTokens") val maxOutputTokens: Int? = null
)

@JsonClass(generateAdapter = true)
data class GeminiToolWrapper(
    @Json(name = "functionDeclarations") val functionDeclarations: List<GeminiFunctionDeclaration>
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionDeclaration(
    @Json(name = "name") val name: String,
    @Json(name = "description") val description: String,
    @Json(name = "parameters") val parameters: GeminiFunctionParameters? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionParameters(
    @Json(name = "type") val type: String = "OBJECT",
    @Json(name = "properties") val properties: Map<String, GeminiParameterProperty>? = null,
    @Json(name = "required") val required: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiParameterProperty(
    @Json(name = "type") val type: String,
    @Json(name = "description") val description: String,
    @Json(name = "enum") val enumValues: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    @Json(name = "candidates") val candidates: List<GeminiCandidate>? = null,
    @Json(name = "usageMetadata") val usageMetadata: GeminiUsageMetadata? = null,
    @Json(name = "error") val error: GeminiApiError? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    @Json(name = "content") val content: GeminiContent? = null,
    @Json(name = "finishReason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiUsageMetadata(
    @Json(name = "promptTokenCount") val promptTokenCount: Int = 0,
    @Json(name = "candidatesTokenCount") val candidatesTokenCount: Int = 0,
    @Json(name = "totalTokenCount") val totalTokenCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class GeminiApiError(
    @Json(name = "code") val code: Int? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "status") val status: String? = null
)

// Supported models conforming to gemini-api guidelines
object GeminiModelCatalog {
    const val GEMINI_2_5_FLASH = "gemini-2.5-flash"
    const val GEMINI_3_5_FLASH = "gemini-3.5-flash"
    const val GEMINI_3_1_PRO = "gemini-3.1-pro-preview"
    const val GEMINI_2_5_FLASH_IMAGE = "gemini-2.5-flash-image"

    val AVAILABLE_MODELS = listOf(
        ModelInfo(
            id = GEMINI_2_5_FLASH,
            displayName = "Gemini 2.5 Flash",
            badge = "Recommended",
            description = "High speed, low latency, ideal for live diagnostics, settings modification, and real-time chat."
        ),
        ModelInfo(
            id = GEMINI_3_5_FLASH,
            displayName = "Gemini 3.5 Flash",
            badge = "Next-Gen",
            description = "State-of-the-art reasoning for complex network and multi-turn autonomous co-pilot operations."
        ),
        ModelInfo(
            id = GEMINI_3_1_PRO,
            displayName = "Gemini 3.1 Pro",
            badge = "Deep Reasoning",
            description = "Advanced network reasoning, complex censorship diagnosis, and DPI analysis."
        )
    )
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    val badge: String,
    val description: String
)

enum class UsageMode(
    val id: String,
    val displayName: String,
    val emoji: String,
    val description: String
) {
    GAMING("gaming", "Gaming Mode", "🎮", "Ultra-low latency (<60ms), UDP direct, 15s keepalive, MTU 1400, 1.1.1.1 Gaming DNS"),
    STREAMING("streaming", "Streaming Mode", "🎬", "High-bitrate buffer (16KB), unlocked Netflix/Twitch/YouTube 4K, Balanced DPI bypass"),
    AI_TASKS("ai_tasks", "AI Tasks Mode", "🤖", "Direct zero-packet-loss routing for OpenAI, Gemini, Claude, and HuggingFace endpoints"),
    YOUTUBE("youtube", "YouTube Mode", "📺", "Google CDN acceleration, QUIC/UDP stream optimization, 4K fast video buffering"),
    DOWNLOADING("downloading", "Downloading Mode", "⚡", "Multi-connection TCP acceleration, max throughput MTU, high-speed edge proxy")
}
