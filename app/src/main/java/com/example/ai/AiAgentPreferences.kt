package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import com.example.RayApplication
import com.example.ai.gateway.AiCredentialVault
import com.example.ai.gateway.AiGatewayHolder
import com.example.ai.gateway.AiProviderConfig
import com.example.ai.gateway.AiProviderKind
import com.example.ai.gateway.MaximusAiGateway
import com.example.ai.gateway.RouteChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AiAgentConfig(
    /** At least one AI provider has a key (from the gateway; the raw key never reaches this layer). */
    val aiReady: Boolean = false,
    /** The Gemini key as masked text ("AIz••••••••3F9c"), or blank. */
    val keyMasked: String = "",
    val model: String = GeminiModelCatalog.GEMINI_3_5_FLASH,
    val autoVoiceEnabled: Boolean = false,
    val voiceSpeed: Float = 1.0f,
    val voicePitch: Float = 1.0f,
    val totalPromptTokens: Long = 0L,
    val totalCandidateTokens: Long = 0L,
    val lifetimeTokens: Long = 0L,
    val lastSessionTokens: Int = 0,
    val activeUsageMode: UsageMode? = null
)

class AiAgentPreferences(context: Context = RayApplication.instance) {
    private val prefs: SharedPreferences = context.getSharedPreferences("maximus_ai_agent_prefs", Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(loadConfig())

    init {
        runCatching { refreshGateway() }
    }
    val configFlow: StateFlow<AiAgentConfig> = _configFlow.asStateFlow()

    private fun loadConfig(): AiAgentConfig {
        return AiAgentConfig(
            model = prefs.getString(KEY_MODEL, GeminiModelCatalog.GEMINI_3_5_FLASH) ?: GeminiModelCatalog.GEMINI_3_5_FLASH,
            autoVoiceEnabled = prefs.getBoolean(KEY_AUTO_VOICE, false),
            voiceSpeed = prefs.getFloat(KEY_VOICE_SPEED, 1.0f),
            voicePitch = prefs.getFloat(KEY_VOICE_PITCH, 1.0f),
            totalPromptTokens = prefs.getLong(KEY_PROMPT_TOKENS, 0L),
            totalCandidateTokens = prefs.getLong(KEY_CANDIDATE_TOKENS, 0L),
            lifetimeTokens = prefs.getLong(KEY_LIFETIME_TOKENS, 0L),
            lastSessionTokens = prefs.getInt(KEY_LAST_SESSION_TOKENS, 0),
            activeUsageMode = prefs.getString(KEY_USAGE_MODE, null)?.let { name ->
                runCatching { UsageMode.valueOf(name) }.getOrNull()
            }
        )
    }

    /** Re-reads whether a provider is ready and the masked Gemini key, after any key or provider change. */
    fun refreshGateway(gateway: MaximusAiGateway = AiGatewayHolder.get()) {
        _configFlow.value = _configFlow.value.copy(
            aiReady = gateway.isConfigured(),
            keyMasked = gateway.maskedKey(AiProviderKind.GEMINI.id).orEmpty()
        )
    }

    /**
     * Saves a Gemini key into the gateway's vault (adding Gemini as a provider when it is not one yet).
     * Returns null when saved, or why the key was refused.
     */
    fun setApiKey(apiKey: String, gateway: MaximusAiGateway = AiGatewayHolder.get()): String? {
        val gemini = AiProviderKind.GEMINI.id
        if (apiKey.isBlank()) {
            gateway.removeKey(gemini)
            refreshGateway(gateway)
            return null
        }
        if (gateway.settings.value.provider(gemini) == null) {
            gateway.updateSettings { it.withProvider(AiProviderConfig(gemini, AiProviderKind.GEMINI)) }
        }
        val result = gateway.saveKey(gemini, apiKey)
        refreshGateway(gateway)
        return (result as? AiCredentialVault.SaveResult.Refused)?.reason
    }

    /** The Gemini model picked in the AI Agent dialog becomes the gateway's primary Gemini choice. */
    fun setModel(model: String, gateway: MaximusAiGateway = AiGatewayHolder.get()) {
        prefs.edit().putString(KEY_MODEL, model).apply()
        _configFlow.value = _configFlow.value.copy(model = model)
        val gemini = AiProviderKind.GEMINI.id
        if (gateway.settings.value.provider(gemini) != null) {
            gateway.updateSettings { s ->
                s.copy(primary = RouteChoice(gemini, model), fallbacks = (s.fallbacks.filter { it.providerId != gemini || !it.isAuto } + RouteChoice(gemini)).distinct())
            }
        }
    }

    fun setAutoVoice(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_VOICE, enabled).apply()
        _configFlow.value = _configFlow.value.copy(autoVoiceEnabled = enabled)
    }

    fun setVoicePitchSpeed(pitch: Float, speed: Float) {
        prefs.edit()
            .putFloat(KEY_VOICE_PITCH, pitch)
            .putFloat(KEY_VOICE_SPEED, speed)
            .apply()
        _configFlow.value = _configFlow.value.copy(voicePitch = pitch, voiceSpeed = speed)
    }

    fun setUsageMode(mode: UsageMode?) {
        prefs.edit().putString(KEY_USAGE_MODE, mode?.name).apply()
        _configFlow.value = _configFlow.value.copy(activeUsageMode = mode)
    }

    @Synchronized
    fun recordTokenUsage(promptTokens: Int, candidateTokens: Int) {
        val totalNew = promptTokens + candidateTokens
        val newPrompt = _configFlow.value.totalPromptTokens + promptTokens
        val newCandidate = _configFlow.value.totalCandidateTokens + candidateTokens
        val newLifetime = _configFlow.value.lifetimeTokens + totalNew

        prefs.edit()
            .putLong(KEY_PROMPT_TOKENS, newPrompt)
            .putLong(KEY_CANDIDATE_TOKENS, newCandidate)
            .putLong(KEY_LIFETIME_TOKENS, newLifetime)
            .putInt(KEY_LAST_SESSION_TOKENS, totalNew)
            .apply()

        _configFlow.value = _configFlow.value.copy(
            totalPromptTokens = newPrompt,
            totalCandidateTokens = newCandidate,
            lifetimeTokens = newLifetime,
            lastSessionTokens = totalNew
        )
    }

    fun resetTokens() {
        prefs.edit()
            .putLong(KEY_PROMPT_TOKENS, 0L)
            .putLong(KEY_CANDIDATE_TOKENS, 0L)
            .putLong(KEY_LIFETIME_TOKENS, 0L)
            .putInt(KEY_LAST_SESSION_TOKENS, 0)
            .apply()

        _configFlow.value = _configFlow.value.copy(
            totalPromptTokens = 0L,
            totalCandidateTokens = 0L,
            lifetimeTokens = 0L,
            lastSessionTokens = 0
        )
    }

    companion object {
        private const val KEY_MODEL = "gemini_model"
        private const val KEY_AUTO_VOICE = "gemini_auto_voice"
        private const val KEY_VOICE_SPEED = "voice_speed"
        private const val KEY_VOICE_PITCH = "voice_pitch"
        private const val KEY_PROMPT_TOKENS = "total_prompt_tokens"
        private const val KEY_CANDIDATE_TOKENS = "total_candidate_tokens"
        private const val KEY_LIFETIME_TOKENS = "lifetime_tokens"
        private const val KEY_LAST_SESSION_TOKENS = "last_session_tokens"
        private const val KEY_USAGE_MODE = "active_usage_mode"
    }
}
