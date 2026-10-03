package com.example.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.RayApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceManager(
    private val context: Context = RayApplication.instance
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _speechInputText = MutableStateFlow<String?>(null)
    val speechInputText: StateFlow<String?> = _speechInputText.asStateFlow()

    init {
        val initialize = {
            if (tts == null) tts = TextToSpeech(context.applicationContext, this)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) initialize() else mainHandler.post(initialize)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.US)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsInitialized = true
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                })
            }
        }
    }

    fun speak(text: String, pitch: Float = 1.0f, speed: Float = 1.0f) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { speak(text, pitch, speed) }
            return
        }
        if (!isTtsInitialized || tts == null) return
        stopSpeaking()

        tts?.setPitch(pitch)
        tts?.setSpeechRate(speed)

        // Clean markdown symbols for natural speech
        val cleanedText = text
            .replace(Regex("[#*_`\\[\\]\\(\\)]"), " ")
            .replace(Regex("https?://\\S+"), "URL link")
            .take(1000)

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "maximus_ai_utterance_${System.currentTimeMillis()}")
        }

        tts?.speak(cleanedText, TextToSpeech.QUEUE_FLUSH, params, "maximus_ai_utterance")
    }

    fun stopSpeaking() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopSpeaking() }
            return
        }
        if (tts != null && _isSpeaking.value) {
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    fun startListening(onResult: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startListening(onResult) }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return
        }

        stopSpeaking()
        stopListening()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        _speechInputText.value = text
                        onResult(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            startListening(intent)
        }
    }

    fun stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopListening() }
            return
        }
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        _isListening.value = false
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release() }
            return
        }
        mainHandler.removeCallbacksAndMessages(null)
        stopSpeaking()
        tts?.shutdown()
        tts = null
        isTtsInitialized = false
        stopListening()
    }
}
