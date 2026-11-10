package com.taskflow.presentation.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper over Android's SpeechRecognizer.
 *
 * Prefers the on-device recognizer (Android 12+), so audio is not sent anywhere. If the on-device
 * service lacks Portuguese, it falls back to the device's default recognition service (on the S26,
 * Google's or Samsung's speech service), which may use the network on its own — this app still
 * never touches the network. Must be used from the main thread.
 */
class SpeechInput(private val context: Context) {

    sealed interface Event {
        data object Ready : Event
        data class Level(val rms: Float) : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Error(val code: Int, val message: String) : Event
    }

    enum class Engine(val label: String) { ON_DEVICE("no aparelho"), SYSTEM("serviço de voz do sistema"), NONE("indisponível") }

    private var recognizer: SpeechRecognizer? = null
    private var forceSystem = false
    var engine: Engine = Engine.NONE
        private set

    fun isAvailable(): Boolean = onDeviceAvailable() || SpeechRecognizer.isRecognitionAvailable(context)

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    fun start(onEvent: (Event) -> Unit) {
        stop()
        val useOnDevice = !forceSystem && onDeviceAvailable()
        val r = runCatching {
            if (useOnDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (r == null) { engine = Engine.NONE; onEvent(Event.Error(-1, "Reconhecimento de voz indisponível neste aparelho")); return }
        engine = if (useOnDevice) Engine.ON_DEVICE else Engine.SYSTEM
        recognizer = r
        var lastPartial = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(Event.Ready)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onEvent(Event.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { lastPartial = it; onEvent(Event.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                val text = best(results) ?: lastPartial
                if (text.isBlank()) onEvent(Event.Error(SpeechRecognizer.ERROR_NO_MATCH, message(SpeechRecognizer.ERROR_NO_MATCH)))
                else onEvent(Event.Final(text))
            }
            override fun onError(error: Int) {
                // On-device service without Portuguese: retry once with the system service.
                if (engine == Engine.ON_DEVICE && error in LANGUAGE_ERRORS) {
                    forceSystem = true
                    start(onEvent)
                    return
                }
                if (lastPartial.isNotBlank() && error in listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    onEvent(Event.Final(lastPartial)); return
                }
                onEvent(Event.Error(error, message(error)))
            }
        })
        r.startListening(recognizerIntent())
    }

    /** Stops listening and delivers what was heard so far. */
    fun finish() = recognizer?.stopListening()

    fun stop() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    companion object {
        private val LANGUAGE_ERRORS = setOf(12 /* LANGUAGE_NOT_SUPPORTED */, 13 /* LANGUAGE_UNAVAILABLE */, 11 /* SERVER_DISCONNECTED */)

        fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga o que fazer ou o que já fez")
        }

        private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        fun message(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi. Tente de novo."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada."
            SpeechRecognizer.ERROR_AUDIO -> "Problema com o microfone."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permita o uso do microfone."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O reconhecimento de voz está ocupado. Tente de novo."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Reconhecimento offline indisponível. Baixe o idioma Português (Brasil) para uso offline nas configurações de voz do aparelho."
            12, 13 -> "Português (Brasil) não está instalado para reconhecimento offline."
            else -> "Não foi possível ouvir agora (erro $code)."
        }
    }
}
