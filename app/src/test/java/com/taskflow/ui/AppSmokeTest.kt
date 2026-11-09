package com.taskflow.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    @Test fun mainFlows() {
        // Dashboard
        waitText("FOCO DE HOJE")

        // Quick capture from the FAB
        rule.onNodeWithContentDescription("Nova tarefa").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Estudar SQL hoje !alta 30min #sql")
        rule.onNodeWithContentDescription("Adicionar").performClick()
        waitText("Estudar SQL")

        // Task detail with priority explanation
        rule.onAllNodesWithText("Estudar SQL").onFirst().performClick()
        waitText("Por que esta prioridade?")
        rule.onNodeWithText("Concluir").performClick()
        waitText("Reabrir")
        rule.onNodeWithContentDescription("Voltar").performClick()

        // Bottom navigation
        rule.onNodeWithText("Tarefas").performClick(); waitText("Esta semana", substring = true)
        rule.onNodeWithText("Meu dia").performClick(); waitText("SUGESTÕES")
        rule.onNodeWithText("Hábitos").performClick(); waitText("Nenhum hábito ainda")
        rule.onNodeWithContentDescription("Novo hábito").performClick()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Praticar Python")
        rule.onNodeWithText("Salvar").performClick()
        waitText("Praticar Python")
        rule.onNodeWithText("Agenda").performClick(); waitText("Semana")

        // Secondary screens through the dashboard menu
        rule.onNodeWithText("Início").performClick()
        for (screen in listOf("Estatísticas", "Categorias", "Histórico", "Configurações")) {
            rule.onNodeWithContentDescription("Mais opções").performClick()
            rule.onNodeWithText(screen).performClick()
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Voltar").assertIsDisplayed().performClick()
        }
        waitText("FOCO DE HOJE")
    }

    @Test fun editPlanAndDelete() {
        waitText("FOCO DE HOJE")
        rule.onNodeWithContentDescription("Nova tarefa").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Relatório mensal 45min")
        rule.onNodeWithContentDescription("Adicionar").performClick()

        // Plan it in "Meu dia"
        rule.onNodeWithText("Meu dia").performClick()
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
        rule.onAllNodesWithText("Excluir").onFirst().performClick()
        waitText("Desfazer")
    }
}
