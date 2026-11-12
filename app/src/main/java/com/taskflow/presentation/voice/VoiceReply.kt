package com.taskflow.presentation.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Reads answers aloud with a text-to-speech engine on the phone (Samsung or Google on the S26).
 *
 * Uses the engine/voice/rate/pitch chosen in Settings ([prefs]). It never goes silent because of a bad
 * choice: if the engine is gone Android falls back to the default engine, and if the voice is missing
 * or not downloaded it falls back to the engine's Portuguese (Brazil) voice. [status] says what is in use.
 * The same class plays the preview in Settings and the real replies, so what you hear there is what you get.
 */
class VoiceReply(context: Context, val prefs: ReplyVoice = ReplyVoice()) : TextToSpeech.OnInitListener {

    enum class State { STARTING, READY, NO_PORTUGUESE, FAILED }

    data class Status(
        val state: State,
        /** Voice actually in use (engine name for it), when known. */
        val voiceName: String? = null,
        /** The chosen voice could not be used and the default Portuguese voice is playing instead. */
        val fellBack: Boolean = false,
    )

    private val tts: TextToSpeech =
        if (prefs.engine != null) TextToSpeech(context.applicationContext, this, prefs.engine)
        else TextToSpeech(context.applicationContext, this)

    var status = Status(State.STARTING)
        private set

    /** Called once the engine is ready (or failed), e.g. to show "Usando agora: …". */
    var onStatus: ((Status) -> Unit)? = null

    private var pending: String? = null

    override fun onInit(code: Int) {
        status = if (code == TextToSpeech.SUCCESS) configure() else Status(State.FAILED)
        onStatus?.invoke(status)
        pending?.let { text -> pending = null; if (status.state == State.READY) talk(text) }
    }

    private fun configure(): Status {
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        val wanted = prefs.voice?.let { name -> voices.firstOrNull { it.name == name } }
        val wantedUsable = wanted != null && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in wanted.features.orEmpty()
        var ok = wantedUsable && runCatching { tts.setVoice(wanted) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS
        val fellBack = prefs.voice != null && !ok
        if (!ok) ok = setPortuguese()
        prefs.rate?.let { runCatching { tts.setSpeechRate(it) } }
        prefs.pitch?.let { runCatching { tts.setPitch(it) } }
        if (!ok) return Status(State.NO_PORTUGUESE, fellBack = fellBack)
        val inUse = runCatching { tts.voice?.name }.getOrNull() ?: if (!fellBack) wanted?.name else null
        return Status(State.READY, inUse, fellBack)
    }

    private fun setPortuguese(): Boolean {
        for (locale in listOf(Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("pt"))) {
            val r = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (r >= TextToSpeech.LANG_AVAILABLE) return true
        }
        return false
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        when (status.state) {
            State.STARTING -> pending = text
            State.READY -> talk(text)
            else -> Unit
        }
    }

    private fun talk(text: String) {
        runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "taskflow-reply") }
    }

    fun isSpeaking(): Boolean = runCatching { tts.isSpeaking }.getOrDefault(false)

    /** Stop talking (e.g. before listening again, so the microphone does not hear the app). */
    fun stop() { pending = null; runCatching { tts.stop() } }

    fun shutdown() { pending = null; runCatching { tts.stop(); tts.shutdown() } }

    companion object {
        const val SAMPLE = "Olá! Esta é a voz do TaskFlow. Hoje você tem 2 tarefas: Reunião com cliente, às 15 horas; e Pagar fatura."
    }
}
