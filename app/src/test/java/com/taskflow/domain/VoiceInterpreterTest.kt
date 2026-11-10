package com.taskflow.domain

import com.taskflow.FakeCategories
import com.taskflow.FakeHistory
import com.taskflow.FakeReminders
import com.taskflow.FakeTaskRepository
import com.taskflow.NOW
import com.taskflow.TODAY
import com.taskflow.core.FixedTimeProvider
import com.taskflow.data.ai.NanoPrompt
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.usecase.DeleteTaskUseCase
import com.taskflow.domain.usecase.ExecuteVoiceCommandUseCase
import com.taskflow.domain.usecase.SaveTaskUseCase
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import com.taskflow.domain.usecase.TaskMutationSupport
import com.taskflow.domain.usecase.VoiceOutcome
import com.taskflow.domain.voice.CompletionCandidate
import com.taskflow.domain.voice.CompletionCandidate.Kind
import com.taskflow.domain.voice.Interpretation
import com.taskflow.domain.voice.InterpretationSource
import com.taskflow.domain.voice.ModelReading
import com.taskflow.domain.voice.TaskDraft
import com.taskflow.domain.voice.TaskMatch
import com.taskflow.domain.voice.VoiceInterpreter
import com.taskflow.task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class VoiceInterpreterTest {
    private val cats = listOf("Trabalho", "Finanças", "Estudos")
    private val items = listOf(
        CompletionCandidate(1, Kind.TASK, "Pagar fatura do cartão", true),
        CompletionCandidate(2, Kind.TASK, "Entregar relatório de Banco de Dados", true),
        CompletionCandidate(3, Kind.HABIT, "Exercícios", true),
    )

    private fun interpret(text: String, model: ModelReading?) = VoiceInterpreter.interpret(text, NOW, cats, items, model)

    @Test fun `without model the rules decide`() {
        val r = interpret("pagar aluguel amanhã às 10h", null)
        assertTrue(r is Interpretation.Create)
        assertEquals(InterpretationSource.RULES, r.source)
        assertEquals("Pagar aluguel", (r as Interpretation.Create).draft.title)
    }

    @Test fun `model improves title and category but dates come from the rules`() {
        val r = interpret("ó, preciso lembrar daquele negócio de pagar o aluguel amanhã às 10h",
            ModelReading("create", title = "Pagar o aluguel", whenText = "amanhã às 10h", category = "finanças", target = null))
        r as Interpretation.Create
        assertEquals(InterpretationSource.ON_DEVICE_AI, r.source)
        assertEquals("Pagar o aluguel", r.draft.title)
        assertEquals("Finanças", r.draft.categoryHint)
        assertEquals(TODAY.plusDays(1), r.draft.dueDate)
        assertEquals(LocalTime.of(10, 0), r.draft.dueTime)
    }

    @Test fun `hallucinated title is rejected`() {
        val r = interpret("comprar pão amanhã", ModelReading("create", title = "Agendar reunião com diretoria"))
        assertEquals("Comprar pão", (r as Interpretation.Create).draft.title)
    }

    @Test fun `unknown category from model is ignored`() {
        val r = interpret("comprar pão", ModelReading("create", title = "Comprar pão", category = "Mercado"))
        assertNull((r as Interpretation.Create).draft.categoryHint)
    }

    @Test fun `model when is parsed only when the rules found no date`() {
        val r = interpret("lembra de ligar pro João", ModelReading("create", title = "Ligar pro João", whenText = "depois de amanhã à tarde"))
        r as Interpretation.Create
        assertEquals(TODAY.plusDays(2), r.draft.dueDate)
        assertEquals(LocalTime.of(15, 0), r.draft.dueTime)
    }

    @Test fun `model picks the completed item`() {
        val r = interpret("já resolvi aquela conta do cartão", ModelReading("complete", target = 1))
        r as Interpretation.Complete
        assertEquals(1L, (r.match as TaskMatch.Unique).match.candidate.id)
        assertEquals(InterpretationSource.ON_DEVICE_AI, r.source)
    }

    @Test fun `model target out of range falls back to rules`() {
        val r = interpret("paguei a fatura", ModelReading("complete", target = 99))
        r as Interpretation.Complete
        assertEquals(1L, (r.match as TaskMatch.Unique).match.candidate.id)
        assertEquals(InterpretationSource.RULES, r.source)
    }

    @Test fun `model completion without supporting words is not trusted`() {
        // The model claims "complete" for a creation sentence and points at an unrelated item.
        val r = interpret("comprar pão amanhã", ModelReading("complete", target = 2))
        assertTrue(r is Interpretation.Create)
    }

    @Test fun `explicit completion wording beats a model create`() {
        val r = interpret("concluí o relatório", ModelReading("create", title = "Relatório"))
        r as Interpretation.Complete
        assertEquals(2L, (r.match as TaskMatch.Unique).match.candidate.id)
    }

    @Test fun `prompt lists items and parses fenced json`() {
        val p = NanoPrompt.user("fiz exercícios", TODAY, cats, items)
        assertTrue(p.contains("1. Pagar fatura do cartão"))
        assertTrue(p.contains("3. Exercícios (hábito)"))
        val m = NanoPrompt.parse("```json\n{\"intent\":\"complete\",\"title\":null,\"when\":\"null\",\"target\":3,\"extra\":1}\n```")
        assertEquals(ModelReading("complete", null, null, null, null, null, 3), m)
        assertNull(NanoPrompt.parse("não sei"))
        assertNull(NanoPrompt.parse("{intent: create, title: }}"))
    }

    // ---------- execution and undo

    private class FakeHabits(list: List<Habit>) : HabitRepository {
        val habits = list.associateBy { it.id }
        val logs = MutableStateFlow<Map<Long, Set<LocalDate>>>(emptyMap())
        override fun observeAll(): Flow<List<Habit>> = MutableStateFlow(habits.values.toList())
        override fun observeLogs(): Flow<Map<Long, Set<LocalDate>>> = logs
        override suspend fun get(id: Long) = habits[id]
        override suspend fun getAll() = habits.values.toList()
        override suspend fun upsert(habit: Habit) = habit.id
        override suspend fun delete(id: Long) {}
        override suspend fun setDone(habitId: Long, date: LocalDate, done: Boolean) {
            val cur = logs.value[habitId].orEmpty()
            logs.value = logs.value + (habitId to if (done) cur + date else cur - date)
        }
        override suspend fun logsFor(habitId: Long) = logs.value[habitId].orEmpty()
    }

    private val repo = FakeTaskRepository()
    private val time = FixedTimeProvider(NOW)
    private val support = TaskMutationSupport(repo, FakeHistory(), FakeReminders(), time)
    private val habits = FakeHabits(listOf(Habit(3, "Exercícios", rule = RecurrenceRule(RecurrenceType.DAILY),
        createdAt = NOW.toInstant(ZoneOffset.UTC), startDate = TODAY.minusDays(5))))
    private val exec = ExecuteVoiceCommandUseCase(SaveTaskUseCase(support), SetTaskDoneUseCase(support), DeleteTaskUseCase(support),
        repo, habits, FakeCategories(listOf(Category(9, "Finanças", 0, "money"))), time)

    @Test fun `create applies the draft and undo removes it`() = runTest {
        val o = exec.create(TaskDraft("Pagar aluguel", TODAY.plusDays(1), LocalTime.of(10, 0), 15, Priority.HIGH, "finanças", reminderMinutesBefore = 0))
        o as VoiceOutcome.Created
        val t = repo.get(o.taskId)!!
        assertEquals(9L, t.categoryId); assertEquals(Priority.HIGH, t.priority); assertEquals(0, t.reminderMinutesBefore)
        exec.undo(o)
        assertNull(repo.get(o.taskId))
    }

    @Test fun `complete task, recurring next occurrence, and undo restores everything`() = runTest {
        val id = repo.upsert(task(0, "Pagar fatura", due = TODAY).copy(recurrence = RecurrenceRule(RecurrenceType.MONTHLY)))
        val o = exec.complete(CompletionCandidate(id, Kind.TASK, "Pagar fatura"))
        o as VoiceOutcome.Completed
        assertEquals(TaskStatus.DONE, repo.get(id)!!.status)
        assertNotNull(o.nextOccurrenceId)
        assertTrue(exec.complete(CompletionCandidate(id, Kind.TASK, "Pagar fatura")) is VoiceOutcome.AlreadyDone)
        exec.undo(o)
        assertEquals(TaskStatus.PENDING, repo.get(id)!!.status)
        assertNull(repo.get(o.nextOccurrenceId!!))
    }

    @Test fun `complete habit today and undo`() = runTest {
        val c = CompletionCandidate(3, Kind.HABIT, "Exercícios")
        val o = exec.complete(c)
        assertTrue(o is VoiceOutcome.Completed)
        assertTrue(TODAY in habits.logsFor(3))
        assertTrue(exec.complete(c) is VoiceOutcome.AlreadyDone)
        exec.undo(o)
        assertFalse(TODAY in habits.logsFor(3))
    }
}
