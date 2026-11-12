package com.taskflow.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.taskflow.MainActivity
import com.taskflow.data.local.TaskFlowDatabase
import com.taskflow.data.repository.CategoryRepositoryImpl
import com.taskflow.data.repository.HabitRepositoryImpl
import com.taskflow.data.repository.PlanRepositoryImpl
import com.taskflow.data.repository.TaskRepositoryImpl
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import com.taskflow.domain.model.Subtask
import com.taskflow.domain.model.Task
import com.taskflow.domain.model.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Renders preview screenshots of the real app filled with sample data.
 * Run with: ./gradlew :app:testDebugUnitTest --tests "*ScreenshotTest*" -Pscreenshots
 * Output: app/build/screenshots/
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }

    @Before fun enabled() = assumeTrue(System.getProperty("taskflow.screenshots") == "true")

    @Test fun light() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { sc -> scenario = sc
            waitText("FOCO"); shot("01-hoje")
            tab("Tarefas"); waitText("Abertas"); shot("02-tarefas")
            rule.onAllNodesWithText("Corrigir pipeline de ingestão no Airflow").onFirst().performClick()
            waitText("Por que esta prioridade?")
            rule.onNodeWithText("Por que esta prioridade?").performClick()
            waitText("Prioridade crítica"); shot("03-detalhe-prioridade")
            rule.onNodeWithContentDescription("Voltar").performClick(); waitText("Abertas")
            tab("Hábitos"); waitText("HOJE"); shot("05-habitos")
            tab("Agenda"); waitText("Semana"); shot("06-agenda")
            tab("Hoje"); waitText("FOCO")
            rule.onNodeWithText("Planejar dia").performClick(); waitText("ORDEM DE EXECUÇÃO"); shot("04-meu-dia")
            rule.onNodeWithContentDescription("Voltar").performClick(); waitText("FOCO")
            rule.onNodeWithContentDescription("Mais opções").performClick()
            rule.onNodeWithText("Estatísticas").performClick(); waitText("CONCLUÍDAS POR DIA"); shot("07-estatisticas")
            rule.onNodeWithContentDescription("Voltar").performClick(); waitText("FOCO")

            // Voice capture (typed here: there is no microphone under Robolectric; same interpreter).
            openCapture()
            say("Reunião com o time amanhã às 3 da tarde por uma hora"); waitText("Tarefa criada"); shot("10-voz-criar")
            say("já paguei a fatura do cartão"); waitText("Concluída"); shot("11-voz-concluir")
            say("apaga a reunião com o time de amanhã"); waitText("Apagar esta tarefa?"); shot("12-voz-apagar")
            rule.onNodeWithText("Cancelar").performClick()
            say("o que eu tenho hoje?"); waitText("ATRASADAS"); shot("13-voz-listar")
        }
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun dark() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { sc -> scenario = sc
            waitText("FOCO"); shot("08-hoje-escuro")
            tab("Tarefas"); waitText("Abertas"); shot("09-tarefas-escuro")
        }
    }

    private fun waitText(text: String, substring: Boolean = false) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tab(name: String) = rule.onNode(hasText(name) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()

    private fun openCapture() {
        rule.onNodeWithContentDescription("Falar ou digitar uma tarefa").performClick()
        fun has(desc: String) = rule.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
        rule.waitUntil(5_000) { has("Digitar") || has("Enviar") }
        if (!has("Enviar")) rule.onNodeWithContentDescription("Digitar").performClick()
        rule.waitUntil(5_000) { has("Enviar") }
    }

    private fun say(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.onNodeWithContentDescription("Enviar").performClick()
    }

    private lateinit var scenario: ActivityScenario<MainActivity>

    /**
     * Draws every window of the app (activity + bottom sheets/dialogs, in z-order) into one bitmap,
     * using Robolectric's native graphics.
     */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400) // let sheet/fade animations settle
        rule.waitForIdle()
        scenario.onActivity { a ->
            val decor = a.window.decorView
            val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val roots = runCatching {
                val wmg = Class.forName("android.view.WindowManagerGlobal")
                val inst = wmg.getMethod("getInstance").invoke(null)
                val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") (f.get(inst) as List<android.view.View>).toList()
            }.getOrDefault(listOf(decor))
            roots.filter { it.isShown && it.width > 0 }.forEach { v ->
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                canvas.save(); canvas.translate(loc[0].toFloat(), loc[1].toFloat()); v.draw(canvas); canvas.restore()
            }
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Sample data written straight to the app database before the UI starts. */
    private fun seed() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(ctx, TaskFlowDatabase::class.java, TaskFlowDatabase.NAME).build()
        try {
            val cats = CategoryRepositoryImpl(db)
            // The app seeds default categories on start; wait for it instead of racing it.
            repeat(100) { if (cats.getAll().size >= 9) return@repeat; Thread.sleep(50) }
            cats.seedDefaultsIfEmpty()
            val c = cats.getAll().associate { it.name to it.id }
            val tasks = TaskRepositoryImpl(db)
            val habits = HabitRepositoryImpl(db)
            val plan = PlanRepositoryImpl(db)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            fun at(daysAgo: Long, hour: Int = 10) = today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant()
            fun t(title: String, cat: String, p: Priority, due: LocalDate?, est: Int?, created: Long = 2) =
                Task(title = title, categoryId = c[cat], priority = p, dueDate = due, originalDueDate = due, estimatedMinutes = est,
                    createdAt = at(created), updatedAt = at(created))

            val pipeline = tasks.upsert(t("Corrigir pipeline de ingestão no Airflow", "Engenharia de Dados", Priority.CRITICAL, today.minusDays(1), 120, 4)
                .copy(postponeCount = 1, originalDueDate = today.minusDays(2), tags = listOf("airflow"), description = "DAG de vendas falhando no passo de carga incremental."))
            val relatorio = tasks.upsert(t("Entregar relatório de Banco de Dados", "Universidade", Priority.HIGH, today, 90, 6)
                .copy(dueTime = LocalTime.of(18, 0), reminderMinutesBefore = 60, tags = listOf("bd"),
                    subtasks = listOf(Subtask(title = "Modelagem ER", done = true), Subtask(title = "Normalização", done = true), Subtask(title = "Consultas SQL"))))
            val fatura = tasks.upsert(t("Pagar fatura do cartão", "Finanças", Priority.MEDIUM, today, 10, 3))
            tasks.upsert(t("Revisar PR do time de dados", "Trabalho", Priority.MEDIUM, today.plusDays(1), 30, 1).copy(dueTime = LocalTime.of(10, 30)))
            tasks.upsert(t("Estudar window functions em SQL", "Estudos", Priority.HIGH, today.plusDays(3), 60, 1).copy(tags = listOf("sql")))
            tasks.upsert(t("Deploy dos modelos dbt em produção", "Engenharia de Dados", Priority.HIGH, today.plusDays(2), 45, 1).copy(dependsOn = listOf(pipeline)))
            tasks.upsert(t("Agendar consulta médica", "Saúde e exercícios", Priority.MEDIUM, today.plusDays(5), 15, 1))
            tasks.upsert(t("Atualizar portfólio no GitHub", "Projetos pessoais", Priority.LOW, null, 120, 14).copy(postponeCount = 4))
            tasks.upsert(t("Ler cap. 4 de Designing Data-Intensive Applications", "Estudos", Priority.MEDIUM, today.plusDays(6), 50, 2))
            tasks.upsert(t("Trocar filtro da água", "Casa", Priority.LOW, today.plusDays(9), 15, 1))

            val doneTitles = listOf("Lista de exercícios de Cálculo", "Configurar alertas do Grafana", "Reunião de alinhamento", "Revisar orçamento do mês",
                "Treino de pernas", "Resumo de Estatística", "Script de backup do Postgres", "Responder e-mails", "Comprar mantimentos",
                "Documentar pipeline de clientes", "Corrigir testes do ETL", "Revisão para a prova", "Planejar sprint", "Limpar a casa")
            val doneDays = listOf(0L, 0, 1, 1, 1, 2, 3, 3, 4, 6, 8, 9, 10, 12)
            val doneCats = listOf("Universidade", "Engenharia de Dados", "Trabalho", "Finanças", "Saúde e exercícios", "Universidade",
                "Engenharia de Dados", "Trabalho", "Casa", "Engenharia de Dados", "Engenharia de Dados", "Universidade", "Trabalho", "Casa")
            doneTitles.forEachIndexed { i, title ->
                val d = doneDays[i]
                tasks.upsert(t(title, doneCats[i], Priority.MEDIUM, today.minusDays(d), 30, d + 2)
                    .copy(status = TaskStatus.DONE, completedAt = at(d, 9 + i % 8)))
            }

            listOf(pipeline, relatorio, fatura).forEach { plan.add(today, it) }
            plan.setAvailableMinutes(today, 240)

            suspend fun habit(title: String, cat: String, rule: RecurrenceRule, time: LocalTime?, doneDaysAgo: List<Long>) {
                val id = habits.upsert(Habit(title = title, categoryId = c[cat], rule = rule, reminderTime = time,
                    createdAt = at(30), startDate = today.minusDays(30)))
                doneDaysAgo.forEach { habits.setDone(id, today.minusDays(it), true) }
            }
            habit("Estudar SQL", "Estudos", RecurrenceRule(RecurrenceType.DAILY), LocalTime.of(20, 0), (1L..9L).toList() + listOf(11L, 12L, 13L))
            habit("Praticar Python", "Estudos", RecurrenceRule(RecurrenceType.WEEKDAYS, 1,
                setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)), LocalTime.of(19, 0),
                (0L..20L).filter { today.minusDays(it).dayOfWeek.value <= 5 && it != 6L })
            habit("Exercícios", "Saúde e exercícios", RecurrenceRule(RecurrenceType.DAILY), LocalTime.of(7, 0), listOf(0L, 1L, 2L, 4L, 5L, 7L, 8L, 9L))
            habit("Organizar a semana", "Outros", RecurrenceRule(RecurrenceType.WEEKLY), null, listOf(2L, 9L, 16L, 23L))
        } finally {
            db.close()
        }
    }
}
