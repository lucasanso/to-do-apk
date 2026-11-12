package com.taskflow.domain

import com.taskflow.FakeCategories
import com.taskflow.FakeHabitRepository
import com.taskflow.FakeTaskRepository
import com.taskflow.NOW
import com.taskflow.TODAY
import com.taskflow.core.FixedTimeProvider
import com.taskflow.domain.engine.SmartFilter
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.usecase.AnswerVoiceQueryUseCase
import com.taskflow.domain.voice.VoiceCommandParser
import com.taskflow.domain.voice.VoiceIntent
import com.taskflow.task
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneOffset

/** NOW = Monday 2026-11-09 10:00 (UTC). */
class VoiceAnswerTest {
    private val repo = FakeTaskRepository()
    private val habits = FakeHabitRepository(listOf(
        Habit(1, "Estudar SQL", rule = RecurrenceRule(RecurrenceType.DAILY), createdAt = NOW.toInstant(ZoneOffset.UTC), startDate = TODAY.minusDays(10)),
        Habit(2, "Exercícios", rule = RecurrenceRule(RecurrenceType.DAILY), createdAt = NOW.toInstant(ZoneOffset.UTC), startDate = TODAY.minusDays(10)),
    ))
    private val cats = listOf(Category(1, "Trabalho", 0, "work"), Category(2, "Casa", 0, "home"))
    private val answer = AnswerVoiceQueryUseCase(repo, habits, FakeCategories(cats), FixedTimeProvider(NOW))

    private suspend fun ask(text: String) =
        answer((VoiceCommandParser.parse(text, NOW, cats.map { it.name }) as VoiceIntent.Query).query)

    private suspend fun seed() {
        repo.upsert(task(0, "Pagar fatura", due = TODAY).copy(categoryId = 2))
        repo.upsert(task(0, "Reunião com cliente", due = TODAY).copy(dueTime = LocalTime.of(15, 0), categoryId = 1))
        repo.upsert(task(0, "Corrigir pipeline", priority = Priority.CRITICAL, due = TODAY.minusDays(1)).copy(categoryId = 1))
        repo.upsert(task(0, "Revisar contrato", due = TODAY.plusDays(1)).copy(categoryId = 1))
        repo.upsert(task(0, "Organizar fotos", priority = Priority.LOW))
        repo.upsert(task(0, "Lista de cálculo", due = TODAY).copy(status = TaskStatus.DONE, completedAt = NOW.minusHours(1).toInstant(ZoneOffset.UTC)))
        habits.setDone(2, TODAY, true)
    }

    @Test fun `today lists due tasks, overdue and pending habits`() = runTest {
        seed()
        val a = ask("o que eu tenho hoje?")
        assertEquals(listOf("Atrasadas", "Tarefas", "Hábitos"), a.sections.map { it.title })
        // Timed items first, in order; then the rest of the day.
        assertEquals(listOf("Reunião com cliente", "Pagar fatura"), a.sections[1].items.map { it.title })
        assertEquals("15:00 · Trabalho", a.sections[1].items[0].detail)
        assertEquals(
            "Hoje você tem 2 tarefas: Reunião com cliente, às 15:00; e Pagar fatura. Também 1 atrasada: Corrigir pipeline. Hábitos que faltam: Estudar SQL.",
            a.spoken,
        )
        assertEquals(SmartFilter.TODAY, a.smartFilter)
    }

    @Test fun `other day, empty day, category`() = runTest {
        seed()
        assertEquals("Amanhã você tem 1 tarefa: Revisar contrato. Hábitos: Estudar SQL e Exercícios.", ask("o que tenho amanhã?").spoken)
        val trabalho = ask("quais tarefas de trabalho tenho hoje")
        assertEquals(listOf("Reunião com cliente"), trabalho.sections.first { it.title == "Tarefas" }.items.map { it.title })
        assertEquals(1L, trabalho.categoryId)
        repo.getAll().forEach { repo.delete(it.id) }
        habits.delete(1); habits.delete(2)
        assertEquals("Hoje você não tem nada pendente.", ask("o que tenho hoje").spoken)
        assertEquals("Na sexta você não tem nada agendado.", ask("tenho algo na sexta?").spoken)
        assertEquals("No sábado você não tem nada agendado.", ask("tenho algo no sábado?").spoken)
    }

    @Test fun `overdue, done, open and habits`() = runTest {
        seed()
        assertEquals("Você tem 1 tarefa atrasada: Corrigir pipeline, ontem.", ask("o que está atrasado?").spoken)
        assertEquals("Hoje você concluiu 1 tarefa: Lista de cálculo. E fez 1 hábito: Exercícios.", ask("o que eu fiz hoje?").spoken)
        val open = ask("quais são minhas tarefas")
        assertEquals(5, open.total)
        assertTrue(open.spoken.startsWith("Você tem 5 tarefas abertas. As principais: Corrigir pipeline"))
        assertEquals("Falta 1 hábito: Estudar SQL.", ask("quais hábitos faltam hoje").spoken)
        assertTrue(ask("quais hábitos faltam hoje").habitsOnly)
    }

    @Test fun `what should I do now explains the first pick`() = runTest {
        seed()
        val a = ask("o que devo fazer agora?")
        assertEquals("Corrigir pipeline", a.sections.single().items.first().title)
        assertTrue(a.spoken, a.spoken.startsWith("Comece por Corrigir pipeline: prioridade crítica e atrasada há 1 dia(s). Depois,"))
    }
}
