package com.taskflow.ui

import android.content.ComponentName
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.taskflow.presentation.voice.SpeechInput
import com.taskflow.presentation.voice.SpeechInput.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

/**
 * The intermittent errors 5 (ERROR_CLIENT) and 11 (ERROR_SERVER_DISCONNECTED): reproduced with
 * Robolectric's fake recognition service and checked that the user gets the text, not the error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechInputTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val events = mutableListOf<Event>()
    private val speech = SpeechInput(app)

    @Before fun setUp() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        // A default recognition service is installed (as on the S26).
        val service = ComponentName("com.google.android.tts", "Recognizer")
        shadowOf(app.packageManager).addServiceIfNotPresent(service)
        shadowOf(app.packageManager).addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))
    }

    private fun idle(ms: Long = 600) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun latest() = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    private fun results(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }
    private fun errors() = events.filterIsInstance<Event.Error>()
    private fun finals() = events.filterIsInstance<Event.Final>().map { it.text }

    private fun startListening(): SpeechRecognizer {
        speech.start { events += it }
        idle()
        return latest()
    }

    @Test fun `error 5 from the cancelled session never reaches the screen`() {
        val first = startListening()
        shadowOf(first).triggerOnReadyForSpeech(Bundle())
        // User taps the microphone again: the old session is cancelled and Android reports error 5 for it.
        speech.start { events += it }
        idle()
        val second = latest()
        assertNotSame(first, second)
        shadowOf(first).triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        shadowOf(second).triggerOnResults(results("comprar pão amanhã"))
        assertTrue(errors().isEmpty())
        assertEquals(listOf("comprar pão amanhã"), finals())
    }

    @Test fun `error 5 is retried automatically with a fresh recognizer`() {
        val first = startListening()
        shadowOf(first).triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        idle()
        val second = latest()
        assertNotSame(first, second)
        assertTrue(shadowOf(first).isDestroyed)
        shadowOf(second).triggerOnResults(results("o que eu tenho hoje"))
        assertTrue(errors().isEmpty())
        assertEquals(listOf("o que eu tenho hoje"), finals())
    }

    @Test fun `error 11 twice on the on-device service switches to the phone's default service`() {
        val first = startListening()
        assertEquals(SpeechInput.Engine.ON_DEVICE, speech.engine)
        shadowOf(first).triggerOnError(11)
        idle()
        assertEquals(SpeechInput.Engine.ON_DEVICE, speech.engine)
        shadowOf(latest()).triggerOnError(11)
        idle()
        assertEquals(SpeechInput.Engine.SYSTEM, speech.engine)
        shadowOf(latest()).triggerOnResults(results("já paguei a fatura"))
        assertTrue(errors().isEmpty())
        assertEquals(listOf("já paguei a fatura"), finals())
    }

    @Test fun `if the service keeps failing the user sees one clear message without codes`() {
        var r = startListening()
        repeat(SpeechInput.MAX_RETRIES + 1) {
            shadowOf(r).triggerOnError(SpeechRecognizer.ERROR_CLIENT)
            idle()
            r = latest()
        }
        assertEquals(1, errors().size)
        val msg = errors().single().message
        assertFalse(msg, msg.any { it.isDigit() })
        assertTrue(msg.contains("Toque no microfone"))
    }

    @Test fun `what was already heard is used when the service drops`() {
        val r = startListening()
        shadowOf(r).triggerOnPartialResults(results("pagar a luz amanhã"))
        shadowOf(r).triggerOnError(11)
        assertEquals(listOf("pagar a luz amanhã"), finals())
        assertTrue(errors().isEmpty())
    }

    @Test fun `stopping before the microphone is ready cancels quietly`() {
        val r = startListening()
        speech.finish()
        assertEquals(Event.Cancelled, events.last())
        shadowOf(r).triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        idle()
        assertTrue(errors().isEmpty())
    }

    @Test fun `on-device service without portuguese uses the default service`() {
        val r = startListening()
        shadowOf(r).triggerOnError(13)
        idle()
        assertEquals(SpeechInput.Engine.SYSTEM, speech.engine)
        shadowOf(latest()).triggerOnResults(results("estudar SQL às 20h"))
        assertEquals(listOf("estudar SQL às 20h"), finals())
    }

    @Test fun `nothing heard is a normal message, not an error code`() {
        val r = startListening()
        shadowOf(r).triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals("Não ouvi nada. Toque no microfone e fale.", errors().single().message)
    }
}
