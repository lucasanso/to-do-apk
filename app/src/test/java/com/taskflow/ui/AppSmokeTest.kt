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
}
