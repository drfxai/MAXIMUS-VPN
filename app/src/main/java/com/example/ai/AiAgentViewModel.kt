package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.RayApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class AiAgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val attachedBitmap: Bitmap? = null,
    val attachedFileName: String? = null,
    val attachedFileContent: String? = null,
    val showSetupDialog: Boolean = false,
    val showTokenStatsModal: Boolean = false,
    val testKeyStatus: String? = null,
    val isTestingKey: Boolean = false
)

class AiAgentViewModel(
    private val preferences: AiAgentPreferences = AiAgentPreferences(),
    private val voiceManager: VoiceManager = VoiceManager(),
    private val agentManager: AiAgentManager = AiAgentManager(preferences)
) : ViewModel() {

    val config: StateFlow<AiAgentConfig> = preferences.configFlow
    val isSpeaking: StateFlow<Boolean> = voiceManager.isSpeaking
    val isListening: StateFlow<Boolean> = voiceManager.isListening

    private val _uiState = MutableStateFlow(AiAgentUiState())
    val uiState: StateFlow<AiAgentUiState> = _uiState.asStateFlow()

    init {
        // Welcome message
        val welcomeMsg = ChatMessage(
            sender = MessageSender.AGENT,
            text = """
                👋 **Hello! I'm Maximus AI Agent**, your intelligent network & VPN co-pilot.

                I can explain privacy-filtered connection health and suggest VPN changes. Apply suggested changes manually in the app. AI tool access is read-only.

                🎯 **Tap any preset below or tell me what you need:**
                • 🎮 **Gaming** (Low ping, UDP direct, MTU 1400)
                • 🎬 **Streaming** (4K buffer, Unblock video CDNs)
                • 🤖 **AI Tasks** (direct TLS to AI services)
                • 📺 **YouTube** (QUIC/UDP fast stream acceleration)
                • ⚡ **Downloading** (High-throughput multi-stream)
                • 🔍 **Diagnose My Connection** (Review a privacy-filtered health summary)
            """.trimIndent()
        )
        _uiState.value = _uiState.value.copy(messages = listOf(welcomeMsg))
    }

    fun sendMessage(inputText: String) {
        val text = inputText.trim()
        val bitmap = _uiState.value.attachedBitmap
        val fileContent = _uiState.value.attachedFileContent
        val fileName = _uiState.value.attachedFileName

        if (text.isBlank() && bitmap == null && fileContent == null) return

        val userMessage = ChatMessage(
            sender = MessageSender.USER,
            text = text,
            imageBitmap = bitmap,
            fileName = fileName
        )

        val previousMessages = _uiState.value.messages
        val updatedMessages = previousMessages + userMessage
        _uiState.value = _uiState.value.copy(
            messages = updatedMessages,
            isProcessing = true,
            attachedBitmap = null,
            attachedFileName = null,
            attachedFileContent = null
        )

        viewModelScope.launch {
            val response = agentManager.sendMessage(
                userText = text,
                imageBitmap = bitmap,
                fileContent = fileContent,
                conversationHistory = previousMessages
            )

            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + response,
                isProcessing = false
            )

            // Auto-speak if enabled
            if (config.value.autoVoiceEnabled && response.text.isNotBlank()) {
                voiceManager.speak(
                    text = response.text,
                    pitch = config.value.voicePitch,
                    speed = config.value.voiceSpeed
                )
            }
        }
    }

    fun applyUsageMode(mode: UsageMode) {
        preferences.setUsageMode(mode)
        sendMessage("Configure my VPN for ${mode.displayName} (${mode.description}) and connect to the best node.")
    }

    fun runDiagnostics() {
        sendMessage("Review my privacy-filtered connection health summary and suggest troubleshooting steps.")
    }

    fun toggleVoiceSpeaking(text: String) {
        if (voiceManager.isSpeaking.value) {
            voiceManager.stopSpeaking()
        } else {
            voiceManager.speak(
                text = text,
                pitch = config.value.voicePitch,
                speed = config.value.voiceSpeed
            )
        }
    }

    fun startVoiceInput() {
        voiceManager.startListening { dictatedText ->
            if (dictatedText.isNotBlank()) {
                sendMessage(dictatedText)
            }
        }
    }

    fun stopVoiceInput() {
        voiceManager.stopListening()
    }

    fun attachImage(context: Context, uri: Uri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                _uiState.value = _uiState.value.copy(
                    attachedBitmap = bitmap,
                    attachedFileName = "image_${System.currentTimeMillis()}.jpg"
                )
            }
        } catch (_: Exception) {}
    }

    fun attachFile(context: Context, uri: Uri) {
        try {
            val name = uri.lastPathSegment ?: "document.txt"
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val content = stream.bufferedReader().use { reader ->
                    val chars = CharArray(15000)
                    val count = reader.read(chars)
                    if (count > 0) String(chars, 0, count) else ""
                }
                _uiState.value = _uiState.value.copy(
                    attachedFileName = name,
                    attachedFileContent = content.take(15000)
                )
            }
        } catch (_: Exception) {}
    }

    fun clearAttachment() {
        _uiState.value = _uiState.value.copy(
            attachedBitmap = null,
            attachedFileName = null,
            attachedFileContent = null
        )
    }

    fun setApiKey(key: String) {
        preferences.setApiKey(key)
    }

    fun setModel(model: String) {
        preferences.setModel(model)
    }

    fun setAutoVoice(enabled: Boolean) {
        preferences.setAutoVoice(enabled)
    }

    fun setVoicePitchSpeed(pitch: Float, speed: Float) {
        preferences.setVoicePitchSpeed(pitch, speed)
    }

    fun resetTokens() {
        preferences.resetTokens()
    }

    fun openSetupDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSetupDialog = show, testKeyStatus = null)
    }

    fun openTokenStatsModal(show: Boolean) {
        _uiState.value = _uiState.value.copy(showTokenStatsModal = show)
    }

    fun testApiKey(apiKey: String) {
        _uiState.value = _uiState.value.copy(isTestingKey = true, testKeyStatus = null)
        viewModelScope.launch {
            try {
                val service = GeminiApiClient.service
                val req = GeminiRequest(
                    contents = listOf(
                        GeminiContent(parts = listOf(GeminiPart(text = "Hello! Test connection.")))
                    ),
                    generationConfig = GeminiGenerationConfig(maxOutputTokens = 10)
                )
                val response = service.generateContent(
                    model = config.value.model.ifBlank { GeminiModelCatalog.GEMINI_3_5_FLASH },
                    apiKey = apiKey.trim(),
                    request = req
                )

                if (response.error == null) {
                    _uiState.value = _uiState.value.copy(
                        isTestingKey = false,
                        testKeyStatus = "✅ API Key is Valid & Ready!"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isTestingKey = false,
                        testKeyStatus = "❌ Invalid API Key: ${response.error.message}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTestingKey = false,
                    testKeyStatus = "❌ Test failed: ${e.message}"
                )
            }
        }
    }

    fun clearHistory() {
        val welcomeMsg = ChatMessage(
            sender = MessageSender.AGENT,
            text = """
                👋 **Maximus AI Agent Ready**

                Session cleared. I am ready to optimize your tunnel, switch routing modes, or diagnose connection issues.
            """.trimIndent()
        )
        _uiState.value = _uiState.value.copy(
            messages = listOf(welcomeMsg),
            attachedBitmap = null,
            attachedFileName = null,
            attachedFileContent = null
        )
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.release()
    }
}
