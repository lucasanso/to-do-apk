package com.taskflow.presentation.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wrapper over Android's SpeechRecognizer that tolerates the recognition service's hiccups.
 *
 * Prefers the on-device recognizer (Android 12+), so audio stays on the phone; falls back to the
 * phone's default recognition service when needed. Must be used from the main thread.
 *
 * Why the extra care — the intermittent errors 5 (ERROR_CLIENT) and 11 (ERROR_SERVER_DISCONNECTED):
 *  - Cancelling a session makes Android report an error for *that old* session. Every session has an id
 *    and callbacks from older sessions are ignored, so a stale "5" never reaches the screen.
 *  - Destroying a recognizer and immediately starting another, or restarting from inside its own
 *    callback, makes the service refuse (5) or drop the connection (11). Restarts are posted with a delay.
 *  - Transient failures (5, 8, 11) are retried automatically with a fresh recognizer; if the on-device
 *    service keeps failing, the next try uses the phone's default service. Only when everything fails
 *    does the user see a message (without error codes).
 *  - Stopping before the microphone is ready cancels quietly instead of calling stopListening (which
 *    is what produces ERROR_CLIENT in that situation).
 */
class SpeechInput(private val context: Context) {

    sealed interface Event {
        data object Ready : Event
        data class Level(val rms: Float) : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Error(val code: Int, val message: String) : Event
        /** The user stopped before anything was heard. */
        data object Cancelled : Event
    }

    enum class Engine(val label: String) { ON_DEVICE("no aparelho"), SYSTEM("serviço de voz do sistema"), NONE("indisponível") }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recognizerOnDevice = false
    private var session = 0
    private var onEvent: ((Event) -> Unit)? = null
    private var ready = false
    private var finished = true
    private var lastPartial = ""
    private var retries = 0
    private var forceSystem = false
    private var preferOffline = true

    var engine: Engine = Engine.NONE
        private set

    fun isAvailable(): Boolean = onDeviceAvailable() || SpeechRecognizer.isRecognitionAvailable(context)

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * Starts a new listening session. [delayMs] gives the audio a moment to be released (e.g. right after
     * the app finished speaking). Any previous session is cancelled and its late callbacks are ignored.
     */
    fun start(delayMs: Long = 0, onEvent: (Event) -> Unit) {
        this.onEvent = onEvent
        retries = 0
        preferOffline = true
        cancelCurrent()
        begin(maxOf(delayMs, if (recognizer != null) RESTART_DELAY_MS else 0))
    }

    private fun begin(delayMs: Long) {
        val my = ++session
        ready = false; finished = false; lastPartial = ""
        main.postDelayed({ if (my == session) open(my) }, delayMs)
    }

    private fun open(my: Int) {
        val useOnDevice = !forceSystem && onDeviceAvailable()
        // A fresh recognizer per session: late callbacks of a cancelled session can only reach the old
        // recognizer's listener (which ignores them), never the new one.
        destroyRecognizer()
        recognizer = runCatching {
            if (useOnDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        recognizerOnDevice = useOnDevice
        val r = recognizer ?: run {
            engine = Engine.NONE
            fail(my, -1, "Reconhecimento de voz indisponível neste aparelho")
            return
        }
        engine = if (useOnDevice) Engine.ON_DEVICE else Engine.SYSTEM
        r.setRecognitionListener(Listener(my))
        val ok = runCatching { r.startListening(recognizerIntent(preferOffline)) }.isSuccess
        if (!ok) retryOrFail(my, SpeechRecognizer.ERROR_CLIENT)
    }

    private inner class Listener(private val my: Int) : RecognitionListener {
        private fun current() = my == session && !finished
        override fun onReadyForSpeech(params: Bundle?) { if (current()) { ready = true; emit(Event.Ready) } }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (current()) emit(Event.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            if (!current()) return
            best(partialResults)?.let { lastPartial = it; emit(Event.Partial(it)) }
        }
        override fun onResults(results: Bundle?) {
            if (!current()) return
            finished = true
            val text = best(results) ?: lastPartial
            if (text.isBlank()) emit(Event.Error(SpeechRecognizer.ERROR_NO_MATCH, message(SpeechRecognizer.ERROR_NO_MATCH)))
            else emit(Event.Final(text))
        }
        override fun onError(error: Int) {
            if (!current()) return  // stale session (e.g. the one just cancelled) or already answered
            // Something was heard before the error: use it instead of failing.
            if (lastPartial.isNotBlank() && error in HEARD_SOMETHING) {
                finished = true; emit(Event.Final(lastPartial)); return
            }
            retryOrFail(my, error)
        }
    }

    private fun retryOrFail(my: Int, error: Int) {
        if (my != session) return
        val onDevice = recognizerOnDevice
        when {
            // On-device service without Portuguese: use the phone's default service (not counted as a retry).
            onDevice && error in LANGUAGE_ERRORS -> { forceSystem = true; restart() }
            // Default service asked to stay offline but has no offline Portuguese: allow it to work normally.
            !onDevice && preferOffline && error in OFFLINE_ERRORS -> { preferOffline = false; restart() }
            error in TRANSIENT && retries < MAX_RETRIES -> {
                retries++
                // Second failure of the on-device service: try the phone's default service instead.
                if (onDevice && retries >= 2 && SpeechRecognizer.isRecognitionAvailable(context)) forceSystem = true
                restart()
            }
            else -> fail(my, error, message(error))
        }
    }

    /** Never restarts from inside a recognizer callback: the new session (and recognizer) is posted with a delay. */
    private fun restart() {
        cancelCurrent()
        begin(RETRY_DELAY_MS)
    }

    private fun fail(my: Int, code: Int, msg: String) {
        if (my != session) return
        finished = true
        emit(Event.Error(code, msg))
    }

    private fun emit(e: Event) { onEvent?.invoke(e) }

    /** Stops listening and delivers what was heard so far; before the mic is ready it just cancels. */
    fun finish() {
        if (finished) return
        if (ready) runCatching { recognizer?.stopListening() }
        else {
            cancelCurrent()
            emit(Event.Cancelled)
        }
    }

    /** Cancels everything; late callbacks are ignored. Call when the screen closes. */
    fun stop() {
        cancelCurrent()
        destroyRecognizer()
    }

    private fun cancelCurrent() {
        session++
        finished = true
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
    }

    private fun destroyRecognizer() {
        recognizer?.let { r -> runCatching { r.destroy() } }
        recognizer = null
    }

    companion object {
        const val RESTART_DELAY_MS = 150L
        const val RETRY_DELAY_MS = 400L
        const val MAX_RETRIES = 3
        private val LANGUAGE_ERRORS = setOf(12 /* LANGUAGE_NOT_SUPPORTED */, 13 /* LANGUAGE_UNAVAILABLE */)
        private val OFFLINE_ERRORS = setOf(12, 13, SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER)
        /** ERROR_CLIENT (5), ERROR_RECOGNIZER_BUSY (8), ERROR_SERVER_DISCONNECTED (11), ERROR_SERVER (4). */
        private val TRANSIENT = setOf(SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 11, SpeechRecognizer.ERROR_SERVER)
        private val HEARD_SOMETHING = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT, 11)

        fun recognizerIntent(preferOffline: Boolean = true): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga o que fazer ou o que já fez")
        }

        private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        /** Messages without error codes: the user can act on them. */
        fun message(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi. Tente de novo."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada. Toque no microfone e fale."
            SpeechRecognizer.ERROR_AUDIO -> "O microfone está ocupado por outro app. Tente de novo."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permita o uso do microfone."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "O reconhecimento offline não está disponível. Baixe Português (Brasil) para uso offline nas configurações de voz do celular."
            12, 13 -> "Português (Brasil) não está instalado no reconhecimento de voz do celular."
            else -> "O serviço de voz do celular não respondeu. Toque no microfone para tentar de novo."
        }
    }
}
