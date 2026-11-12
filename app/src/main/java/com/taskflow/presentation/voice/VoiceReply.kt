package com.taskflow.presentation.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads answers aloud with the device's text-to-speech engine (Samsung or Google TTS on the S26),
 * in Portuguese (Brazil). Works offline when the voice data is installed; if Portuguese is missing,
 * it stays silent — the answer is always on screen too.
 */
class VoiceReply(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var failed = false
    private var pending: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { failed = true; return }
        val r = runCatching { tts.setLanguage(Locale.forLanguageTag("pt-BR")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        failed = !ready
        pending?.let { if (ready) speak(it) }
        pending = null
    }

    fun speak(text: String) {
        if (failed || text.isBlank()) return
        if (!ready) { pending = text; return }
        runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "taskflow-reply") }
    }

    /** Stop talking (e.g. before listening again, so the microphone does not hear the app). */
    fun stop() { pending = null; runCatching { tts.stop() } }

    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}
