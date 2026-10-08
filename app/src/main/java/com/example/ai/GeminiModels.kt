package com.example.ai

/**
 * Gemini models offered in the AI Agent dialog as quick picks. Only display data: the gateway discovers the
 * provider's real models, and any picked id that the provider no longer has is skipped by the router.
 */
object GeminiModelCatalog {
    const val GEMINI_2_5_FLASH = "gemini-2.5-flash"
    const val GEMINI_3_8_FLASH = "gemini-3.8-flash"
    const val GEMINI_3_7_FLASH = "gemini-3.7-flash"
    const val GEMINI_3_5_FLASH = "gemini-3.5-flash"
    const val GEMINI_3_1_PRO = "gemini-3.1-pro-preview"
    const val GEMINI_2_5_FLASH_IMAGE = "gemini-2.5-flash-image"

    val AVAILABLE_MODELS = listOf(
        ModelInfo(
            id = GEMINI_3_8_FLASH,
            displayName = "Gemini 3.8 Flash",
            badge = "Newest",
            description = "Latest Flash model: the sharpest live diagnostics and fastest answers for co-pilot actions.",
            speed = 5,
            reasoning = 4
        ),
        ModelInfo(
            id = GEMINI_3_7_FLASH,
            displayName = "Gemini 3.7 Flash",
            badge = "Fast",
            description = "Quick, low-cost replies with strong reasoning for everyday tuning and troubleshooting.",
            speed = 5,
            reasoning = 3
        ),
        ModelInfo(
            id = GEMINI_3_5_FLASH,
            displayName = "Gemini 3.5 Flash",
            badge = "Default",
            description = "State-of-the-art reasoning for complex network and multi-turn autonomous co-pilot operations.",
            speed = 4,
            reasoning = 4
        ),
        ModelInfo(
            id = GEMINI_2_5_FLASH,
            displayName = "Gemini 2.5 Flash",
            badge = "Stable",
            description = "High speed, low latency, ideal for live diagnostics, settings modification, and real-time chat.",
            speed = 4,
            reasoning = 2
        ),
        ModelInfo(
            id = GEMINI_3_1_PRO,
            displayName = "Gemini 3.1 Pro",
            badge = "Deep Reasoning",
            description = "Advanced network reasoning, complex censorship diagnosis, and DPI analysis.",
            speed = 2,
            reasoning = 5,
            isPro = true
        )
    )
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    val badge: String,
    val description: String,
    /** Relative 1-5 scores shown in the model picker. */
    val speed: Int = 3,
    val reasoning: Int = 3,
    val isPro: Boolean = false
)

enum class UsageMode(
    val id: String,
    val displayName: String,
    val emoji: String,
    val description: String
) {
    GAMING("gaming", "Gaming Mode", "🎮", "Ultra-low latency (<60ms), UDP direct, 15s keepalive, MTU 1400, 1.1.1.1 Gaming DNS"),
    STREAMING("streaming", "Streaming Mode", "🎬", "High-bitrate buffer (16KB), unlocked Netflix/Twitch/YouTube 4K, Balanced DPI bypass"),
    AI_TASKS("ai_tasks", "AI Tasks Mode", "🤖", "Direct zero-packet-loss routing for AI service endpoints"),
    YOUTUBE("youtube", "YouTube Mode", "📺", "Google CDN acceleration, QUIC/UDP stream optimization, 4K fast video buffering"),
    DOWNLOADING("downloading", "Downloading Mode", "⚡", "Multi-connection TCP acceleration, max throughput MTU, high-speed edge proxy")
}
