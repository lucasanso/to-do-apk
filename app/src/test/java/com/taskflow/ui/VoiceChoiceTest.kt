package com.taskflow.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.taskflow.presentation.voice.ReplyVoice
import com.taskflow.presentation.voice.VoiceInfo
import com.taskflow.presentation.voice.VoiceLabels
import com.taskflow.presentation.voice.VoiceReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/** Choosing the reply voice: labels, and that the chosen voice is really the one speaking (with safe fallbacks). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceChoiceTest {
    private val ptBR = Locale.forLanguageTag("pt-BR")

    // ---------- labels (plain data)

    @Test fun `google and samsung voices get friendly labels, portuguese only, brazil first`() {
        val list = listOf(
            VoiceInfo("en-us-x-sfg-local", "en", "US"),
            VoiceInfo("pt-pt-x-jfb-local", "pt", "PT"),
            VoiceInfo("pt-br-x-afs-network", "pt", "BR", networkRequired = true),
            VoiceInfo("pt-br-x-afs-local", "pt", "BR", quality = 400),
            VoiceInfo("pt-br-x-ptd-local", "pt", "BR"),
            VoiceInfo("pt-br-x-pte-local", "pt", "BR", notInstalled = true),
            VoiceInfo("pt-BR-language", "pt", "BR"),
            VoiceInfo("pt-BR-SMTf00", "pt", "BR"),
            VoiceInfo("pt-BR-SMTm00", "pt", "BR"),
        )
        val o = VoiceLabels.options(list)
        assertTrue(o.none { it.name.startsWith("en-") })
        // Google's "pt-BR-language" is the engine default, already offered as "Padrão do mecanismo".
        assertTrue(o.none { it.name == "pt-BR-language" })
        val byName = o.associateBy { it.name }
        // Numbering starts at 1 and follows offline voices first.
        assertEquals("Voz 1", byName["pt-br-x-afs-local"]!!.label)
        assertEquals("Voz 2", byName["pt-br-x-ptd-local"]!!.label)
        assertEquals("Voz 3", byName["pt-br-x-pte-local"]!!.label)
        assertEquals("Feminina 1", byName["pt-BR-SMTf00"]!!.label)
        assertEquals("Masculina 1", byName["pt-BR-SMTm00"]!!.label)
        // Same Google voice offline and online keeps the same number; offline listed first, online last.
        assertEquals(byName["pt-br-x-afs-local"]!!.label, byName["pt-br-x-afs-network"]!!.label)
        assertEquals("no aparelho · alta qualidade", byName["pt-br-x-afs-local"]!!.detail)
        assertTrue(byName["pt-br-x-afs-network"]!!.detail.startsWith("online"))
        assertFalse(byName["pt-br-x-pte-local"]!!.usable)
        assertEquals("Português (Portugal)", o.last().region)
        assertTrue(o.indexOf(byName["pt-br-x-afs-network"]) > o.indexOf(byName["pt-br-x-ptd-local"]))
    }

    // ---------- the voice that actually speaks

    private fun voice(name: String, locale: Locale = ptBR, features: Set<String> = emptySet()) =
        Voice(name, locale, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, features)

    private fun lastTts() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    private fun newReply(prefs: ReplyVoice): VoiceReply {
        val r = VoiceReply(ApplicationProvider.getApplicationContext(), prefs)
        r.speak("Hoje você tem 2 tarefas.")   // queued until the engine is ready
        r.onInit(TextToSpeech.SUCCESS)          // the engine finished starting
        return r
    }

    @Test fun `the chosen voice speaks`() {
        ShadowTextToSpeech.addVoice(voice("pt-br-x-afs-local"))
        ShadowTextToSpeech.addVoice(voice("pt-br-x-ptd-local"))
        val r = newReply(ReplyVoice(voice = "pt-br-x-ptd-local", rate = 1.4f, pitch = 0.8f))
        assertEquals(VoiceReply.State.READY, r.status.state)
        assertFalse(r.status.fellBack)
        assertEquals("pt-br-x-ptd-local", lastTts().currentVoice!!.name)
        assertEquals("Hoje você tem 2 tarefas.", lastTts().lastSpokenText)
    }

    @Test fun `a removed voice falls back to portuguese instead of going silent`() {
        ShadowTextToSpeech.addLanguageAvailability(ptBR)
        ShadowTextToSpeech.addVoice(voice("pt-br-x-afs-local"))
        val r = newReply(ReplyVoice(voice = "pt-br-x-gone-local"))
        assertEquals(VoiceReply.State.READY, r.status.state)
        assertTrue(r.status.fellBack)
        assertEquals(ptBR, lastTts().currentLanguage)
        assertEquals("Hoje você tem 2 tarefas.", lastTts().lastSpokenText)
    }

    @Test fun `a voice that is not downloaded is not used`() {
        ShadowTextToSpeech.addLanguageAvailability(ptBR)
        ShadowTextToSpeech.addVoice(voice("pt-br-x-pte-local", features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
        val r = newReply(ReplyVoice(voice = "pt-br-x-pte-local"))
        assertTrue(r.status.fellBack)
        // The engine's default Portuguese voice plays instead of the missing one.
        assertTrue(lastTts().currentVoice?.name != "pt-br-x-pte-local")
        assertEquals(ptBR, lastTts().currentLanguage)
        assertEquals("Hoje você tem 2 tarefas.", lastTts().lastSpokenText)
    }

    @Test fun `default settings use the engine's portuguese voice`() {
        ShadowTextToSpeech.addLanguageAvailability(ptBR)
        val r = newReply(ReplyVoice())
        assertEquals(VoiceReply.State.READY, r.status.state)
        assertFalse(r.status.fellBack)
        assertEquals(ptBR, lastTts().currentLanguage)
    }

    @Test fun `no portuguese at all is reported, not crashed`() {
        val r = newReply(ReplyVoice())
        assertEquals(VoiceReply.State.NO_PORTUGUESE, r.status.state)
        assertNull(lastTts().lastSpokenText)
    }

    @Test fun `engine that failed to start is reported`() {
        val r = VoiceReply(ApplicationProvider.getApplicationContext(), ReplyVoice(engine = "com.example.missing"))
        r.onInit(TextToSpeech.ERROR)
        assertEquals(VoiceReply.State.FAILED, r.status.state)
        r.speak("x")
        assertNull(lastTts().lastSpokenText)
    }
}
