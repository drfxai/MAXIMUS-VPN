package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import com.example.RayApplication
import com.example.data.security.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AiAgentConfig(
    val apiKey: String = "",
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
    val configFlow: StateFlow<AiAgentConfig> = _configFlow.asStateFlow()

    private fun loadConfig(): AiAgentConfig {
        val encryptedKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val decryptedKey = if (encryptedKey.isNotBlank()) {
            SecureStorage.decrypt(encryptedKey).ifBlank { encryptedKey } // Support legacy unencrypted migration
        } else {
            ""
        }
        val effectiveKey = if (decryptedKey.isNotBlank()) decryptedKey else getFallbackKey()
        return AiAgentConfig(
            apiKey = effectiveKey,
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

    private fun getFallbackKey(): String {
        return try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            val key = (field.get(null) as? String)?.trim() ?: ""
            if (key.isBlank() || key.equals("DEFAULT_API_KEY", ignoreCase = true) || key.startsWith("YOUR_")) {
                ""
            } else {
                key
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        val encrypted = if (trimmed.isNotBlank()) SecureStorage.encrypt(trimmed) else ""
        prefs.edit().putString(KEY_API_KEY, encrypted).apply()
        _configFlow.value = _configFlow.value.copy(apiKey = trimmed)
    }

    fun setModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model).apply()
        _configFlow.value = _configFlow.value.copy(model = model)
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
        private const val KEY_API_KEY = "gemini_api_key"
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
