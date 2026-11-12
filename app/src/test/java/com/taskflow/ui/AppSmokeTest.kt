package com.taskflow.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import androidx.test.espresso.Espresso
import com.taskflow.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Boots the real app (Hilt, Room, WorkManager, DataStore) and walks through the main screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitText(text: String, substring: Boolean = false) = rule.waitUntil(5_000) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    /** Opens the capture sheet in keyboard mode (Robolectric denies the microphone, so it falls back by itself). */
    private fun openCapture() {
        rule.onNodeWithContentDescription("Falar ou digitar uma tarefa").performClick()
        fun has(desc: String) = rule.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
        rule.waitUntil(5_000) { has("Digitar") || has("Enviar") }
        if (!has("Enviar")) rule.onNodeWithContentDescription("Digitar").performClick()
        rule.waitUntil(5_000) { has("Enviar") }
    }

    private fun tab(name: String) = rule.onNode(hasText(name) and
        androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()

    private fun say(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.onNodeWithContentDescription("Enviar").performClick()
    }

    @Test fun mainFlows() {
        waitText("FOCO")

        // Natural-language capture: create, complete by phrase, undo.
        openCapture()
        say("Estudar SQL hoje às 19h por uma hora")
        waitText("Tarefa criada")
        waitText("Hoje 19:00 · 1h", substring = true)
        say("já estudei SQL")
        waitText("Concluída")
        rule.onNodeWithText("Desfazer").performClick()
        waitText("Desfeito")
        Espresso.pressBack()

        // Task detail with priority explanation
        rule.onAllNodesWithText("Estudar SQL").onFirst().performClick()
        waitText("Por que esta prioridade?")
        rule.onNodeWithText("Concluir").performClick()
        waitText("Reabrir")
        rule.onNodeWithContentDescription("Voltar").performClick()

        // Bottom navigation (4 tabs)
        tab("Tarefas"); waitText("Abertas")
        tab("Hábitos"); waitText("Nenhum hábito ainda")
        rule.onNodeWithContentDescription("Novo hábito").performClick()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Praticar Python")
        rule.onNodeWithText("Salvar").performClick()
        // Back on the habits list (the FAB only exists there), not the edit screen.
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Novo hábito").fetchSemanticsNodes().isNotEmpty() }
        waitText("Praticar Python")
        tab("Agenda"); waitText("Semana")

        // Secondary screens through the menu
        tab("Hoje")
        for (screen in listOf("Estatísticas", "Categorias", "Histórico", "Configurações")) {
            rule.onNodeWithContentDescription("Mais opções").performClick()
            rule.onNodeWithText(screen).performClick()
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Voltar").assertIsDisplayed().performClick()
        }
        waitText("FOCO")
    }

    @Test fun editPlanAndDelete() {
        waitText("FOCO")
        openCapture()
        say("Relatório mensal 45min")
        waitText("Tarefa criada")
        Espresso.pressBack()

        // Plan it in "Meu dia"
        rule.onNodeWithText("Planejar dia").performClick()
        waitText("Relatório mensal")
        waitText("planejadas", substring = true)

        // Open and edit
        rule.onAllNodesWithText("Relatório mensal").onFirst().performClick()
        waitText("Por que esta prioridade?")
        rule.onNodeWithContentDescription("Editar").performClick()
        waitText("Editar tarefa")
        rule.onNodeWithText("Crítica").performClick()
        rule.onNodeWithText("Salvar").performClick()
        waitText("Crítica · Pendente")

        // Postpone, then delete with undo available
        rule.onNodeWithText("Adiar").performClick()
        rule.onNodeWithText("Amanhã").performClick()
        waitText("Adiada 1x")
        rule.onNodeWithContentDescription("Mais").performClick()
        rule.onNodeWithText("Excluir").performClick()
        waitText("Excluir tarefa?")
        rule.onNode(hasText("Excluir") and hasAnyAncestor(isDialog())).performClick()
        waitText("Desfazer")

        // Undo brings the task back even though the detail screen has closed.
        rule.onNodeWithText("Desfazer").performClick()
        rule.onNodeWithContentDescription("Voltar").performClick()
        tab("Tarefas")
        waitText("Relatório mensal")
    }

    @Test fun voiceDelete() {
        waitText("FOCO")
        openCapture()
        say("Comprar pão amanhã")
        waitText("Tarefa criada")

        // Delete asks first, then offers undo.
        say("apaga a tarefa comprar pão")
        waitText("Apagar esta tarefa?")
        rule.onNodeWithText("Apagar").performClick()
        waitText("Tarefa apagada")
        rule.onNodeWithText("Desfazer").performClick()
        waitText("Desfeito")

        // Cancel keeps it.
        say("exclui comprar pão")
        waitText("Apagar esta tarefa?")
        rule.onNodeWithText("Cancelar").performClick()

        // Not found: offer to create it as a new task instead.
        say("cancela a assinatura da academia")
        waitText("Criar como nova tarefa")
        rule.onNodeWithText("Criar como nova tarefa").performClick()
        waitText("Tarefa criada")

        // Delete for real and check the list.
        say("remove comprar pão")
        waitText("Apagar esta tarefa?")
        rule.onNodeWithText("Apagar").performClick()
        waitText("Tarefa apagada")
        Espresso.pressBack()
        tab("Tarefas")
        waitText("Cancela a assinatura da academia")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Comprar pão").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun voiceList() {
        waitText("FOCO")
        openCapture()
        say("Pagar boleto hoje às 23h")
        waitText("Tarefa criada")
        say("Revisar contrato amanhã")
        waitText("Tarefa criada")

        // Ask, see the answer, check an item off right there.
        say("o que eu tenho hoje?")
        waitText("Pagar boleto")
        waitText("1 item")
        rule.onNodeWithContentDescription("Concluir Pagar boleto").performClick()
        waitText("Hoje você não tem nada pendente.")

        // Another day, then open the same list in the Tasks screen.
        say("o que tenho amanhã?")
        waitText("Revisar contrato")
        rule.onNodeWithText("Abrir na lista").performClick()
        waitText("Abertas")
        waitText("Revisar contrato")
        // The "Amanhã" filter is active (it shows a clear button).
        rule.onNodeWithContentDescription("Limpar filtro").assertIsDisplayed()
    }

    /** Starts the most recently created text-to-speech engine (Robolectric does not do it by itself). */
    private fun startLatestTts(previous: android.speech.tts.TextToSpeech?): android.speech.tts.TextToSpeech {
        // waitForIdle lets the app's pending work (settings write, then creating the reader) run.
        repeat(100) {
            rule.waitForIdle()
            val t = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()
            if (t != null && t !== previous) return@repeat
            Thread.sleep(50)
        }
        val t = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()
        rule.runOnUiThread { org.robolectric.Shadows.shadowOf(t).onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS) }
        rule.waitForIdle()
        return t
    }

    @Test fun chooseReplyVoice() {
        val ptBR = java.util.Locale.forLanguageTag("pt-BR")
        fun v(name: String) = android.speech.tts.Voice(name, ptBR, android.speech.tts.Voice.QUALITY_HIGH, android.speech.tts.Voice.LATENCY_NORMAL, false, emptySet())
        org.robolectric.shadows.ShadowTextToSpeech.addVoice(v("pt-BR-SMTf00"))
        org.robolectric.shadows.ShadowTextToSpeech.addVoice(v("pt-BR-SMTm00"))
        org.robolectric.shadows.ShadowTextToSpeech.addLanguageAvailability(ptBR)

        waitText("FOCO")
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Configurações").performClick()
        rule.onNodeWithText("Voz das respostas").performScrollTo().performClick()
        waitText("MECANISMO DE VOZ")

        // The screen reads the installed voices from the engine.
        val catalog = startLatestTts(null)
        waitText("Masculina 1")
        waitText("Feminina 1")

        // Choosing a voice saves it and plays the sample with that exact voice.
        rule.onNodeWithText("Masculina 1").performClick()
        val preview = startLatestTts(catalog)
        waitText("Tocando: Masculina 1")
        val shadow = org.robolectric.Shadows.shadowOf(preview)
        assertEquals("pt-BR-SMTm00", shadow.currentVoice!!.name)
        assertEquals(com.taskflow.presentation.voice.VoiceReply.SAMPLE, shadow.lastSpokenText)

        // The choice is kept.
        rule.onNodeWithContentDescription("Voltar").performClick()
        waitText("Voz escolhida no app", substring = true)
    }
}
