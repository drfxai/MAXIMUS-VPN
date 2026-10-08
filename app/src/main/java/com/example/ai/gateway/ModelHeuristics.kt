package com.example.ai.gateway

/**
 * Fills capability fields a provider's model list does not state, from the model's own name. Every value it
 * sets is marked [MetadataSource.INFERRED] and is only a ranking hint: a model is never shown as able to do
 * something because of its name alone, and an unknown flag stays null.
 */
object ModelHeuristics {
    private val FAST = Regex("(?i)(flash|mini|lite|nano|small|haiku|turbo|instant|[^0-9]([1-9]|1[0-4])b\\b)")
    private val SLOW = Regex("(?i)(\\bpro\\b|-pro|large|ultra|(7[0-9]|[1-9][0-9]{2})b\\b|405b|opus)")
    private val REASONING = Regex("(?i)(reason|thinking|\\br1\\b|-r1|\\bo[1-9]\\b|o[1-9]-|gpt-5|\\bpro\\b|-pro|qwq|deepseek-r)")
    private val VISION = Regex("(?i)(vision|\\bvl\\b|-vl|gemini|gpt-4o|gpt-4\\.1|gpt-5|llava|pixtral|multimodal|omni)")
    private val NOT_CHAT = Regex("(?i)(embed|rerank|tts|whisper|transcribe|dall-e|image-gen|imagen|moderation|guard|aqa|realtime|audio-preview|search-preview|computer-use|veo|lyria)")

    /** False for ids that name an embedding, speech, image or moderation model rather than a chat model. */
    fun isChatModel(modelId: String): Boolean = !NOT_CHAT.containsMatchIn(modelId)

    fun speed(modelId: String): RelativeLevel = when {
        FAST.containsMatchIn(modelId) -> RelativeLevel.HIGH
        SLOW.containsMatchIn(modelId) -> RelativeLevel.LOW
        else -> RelativeLevel.UNKNOWN
    }

    fun reasoning(modelId: String): Boolean? = if (REASONING.containsMatchIn(modelId)) true else null

    fun vision(modelId: String): Boolean? = if (VISION.containsMatchIn(modelId)) true else null

    /** Cheaper models are usually the fast ones; null pricing stays unknown. */
    fun cost(modelId: String): RelativeLevel = when (speed(modelId)) {
        RelativeLevel.HIGH -> RelativeLevel.LOW
        RelativeLevel.LOW -> RelativeLevel.HIGH
        else -> RelativeLevel.UNKNOWN
    }

    fun describe(providerId: String, modelId: String, displayName: String = modelId, now: Long, contextWindow: Int? = null): AiModelDescriptor =
        AiModelDescriptor(
            providerId = providerId, modelId = modelId, displayName = displayName, contextWindow = contextWindow,
            supportsReasoning = reasoning(modelId), supportsVision = vision(modelId),
            relativeSpeed = speed(modelId), relativeCost = cost(modelId), discoveredAt = now,
            metadata = MetadataSource.INFERRED
        )
}
