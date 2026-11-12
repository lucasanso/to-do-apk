package com.taskflow.domain

import com.taskflow.FakeCategories
import com.taskflow.FakeHistory
import com.taskflow.FakeReminders
import com.taskflow.FakeTaskRepository
import com.taskflow.NOW
import com.taskflow.TODAY
import com.taskflow.core.AppError
import com.taskflow.core.AppResult
import com.taskflow.core.FixedTimeProvider
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.HistoryType
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import com.taskflow.domain.model.Subtask
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.usecase.ArchiveTaskUseCase
import com.taskflow.domain.usecase.DeleteTaskUseCase
import com.taskflow.domain.usecase.DuplicateTaskUseCase
import com.taskflow.domain.usecase.PostponeTaskUseCase
import com.taskflow.domain.usecase.PromoteSubtaskUseCase
import com.taskflow.domain.usecase.QuickAddTaskUseCase
import com.taskflow.domain.usecase.RestoreDeletedTaskUseCase
import com.taskflow.domain.usecase.SaveTaskUseCase
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import com.taskflow.domain.usecase.TaskMutationSupport
import com.taskflow.task
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TaskUseCasesTest {
    private val repo = FakeTaskRepository()
    private val history = FakeHistory()
    private val reminders = FakeReminders()
    private val time = FixedTimeProvider(NOW)
    private val support = TaskMutationSupport(repo, history, reminders, time)
    private val save = SaveTaskUseCase(support)

    private fun <T> AppResult<T>.ok(): T = (this as AppResult.Success).value

    @Test fun `save validates title and logs creation`() = runTest {
        val r = save(task(0, title = "   "))
        assertTrue(r is AppResult.Failure && r.error is AppError.Validation)
        val id = save(task(0, title = "  Estudar  ", due = TODAY)).ok()
        assertEquals("Estudar", repo.get(id)!!.title)
        assertEquals(HistoryType.CREATED, history.entries.single().type)
    }

    @Test fun `original due date is kept when editing deadline`() = runTest {
        val id = save(task(0, due = TODAY)).ok()
        save(repo.get(id)!!.copy(dueDate = TODAY.plusDays(5))).ok()
        val t = repo.get(id)!!
        assertEquals(TODAY, t.originalDueDate)
        assertEquals(TODAY.plusDays(5), t.dueDate)
        assertTrue(history.entries.last().details.contains("prazo"))
    }

    @Test fun `circular dependency is rejected`() = runTest {
        val a = save(task(0, "A")).ok()
        val b = save(task(0, "B", dependsOn = listOf(a))).ok()
        val r = save(repo.get(a)!!.copy(dependsOn = listOf(b)))
        assertTrue(r is AppResult.Failure)
        assertTrue(repo.get(a)!!.dependsOn.isEmpty())
    }

    @Test fun `reminder is scheduled only for open tasks with a deadline and reminder`() = runTest {
        val id = save(task(0, due = TODAY.plusDays(1)).copy(reminderMinutesBefore = 30, dueTime = LocalTime.NOON)).ok()
        assertTrue(id in reminders.scheduled)
        SetTaskDoneUseCase(support)(id, true).ok()
        assertFalse(id in reminders.scheduled)
    }

    @Test fun `completing marks subtasks done and reopening clears completion`() = runTest {
        val id = save(task(0).copy(subtasks = listOf(Subtask(title = "a"), Subtask(title = "b")))).ok()
        val done = SetTaskDoneUseCase(support)
        done(id, true).ok()
        val t = repo.get(id)!!
        assertEquals(TaskStatus.DONE, t.status)
        assertNotNull(t.completedAt)
        assertTrue(t.subtasks.all { it.done })
        done(id, false).ok()
        assertNull(repo.get(id)!!.completedAt)
        assertEquals(TaskStatus.PENDING, repo.get(id)!!.status)
    }

    @Test fun `completing twice is idempotent`() = runTest {
        val id = save(task(0)).ok()
        val done = SetTaskDoneUseCase(support)
        done(id, true); done(id, true)
        assertEquals(1, history.entries.count { it.type == HistoryType.COMPLETED })
        assertEquals(1, repo.getAll().size)
    }

    @Test fun `completing a recurring task creates the next occurrence`() = runTest {
        val id = save(task(0, due = TODAY).copy(recurrence = RecurrenceRule(RecurrenceType.WEEKLY), subtasks = listOf(Subtask(title = "x")))).ok()
        val next = SetTaskDoneUseCase(support)(id, true).ok()
        assertNotNull(next)
        val n = repo.get(next!!)!!
        assertEquals(TODAY.plusDays(7), n.dueDate)
        assertEquals(TaskStatus.PENDING, n.status)
        assertFalse(n.subtasks.single().done)
    }

    @Test fun `late completion of recurring task skips to a future occurrence`() = runTest {
        val id = save(task(0, due = TODAY.minusDays(10)).copy(recurrence = RecurrenceRule(RecurrenceType.DAILY))).ok()
        val next = SetTaskDoneUseCase(support)(id, true).ok()!!
        assertEquals(TODAY, repo.get(next)!!.dueDate)
    }

    @Test fun `postpone moves deadline and counts`() = runTest {
        val id = save(task(0, due = TODAY)).ok()
        val p = PostponeTaskUseCase(support)
        assertEquals(TODAY.plusDays(1), p(id).ok())
        assertEquals(TODAY.plusDays(2), p(id).ok())
        val t = repo.get(id)!!
        assertEquals(2, t.postponeCount)
        assertEquals(TODAY, t.originalDueDate)
        assertTrue(p(id, TODAY.minusDays(1)) is AppResult.Failure)
    }

    @Test fun `postponing an overdue task moves it from today, not from the old date`() = runTest {
        val id = save(task(0, due = TODAY.minusDays(5))).ok()
        assertEquals(TODAY.plusDays(1), PostponeTaskUseCase(support)(id).ok())
    }

    @Test fun `delete removes task and dependency edges and undo restores id, dependents and plan`() = runTest {
        val plan = com.taskflow.FakePlan()
        val a = save(task(0, "A")).ok()
        val b = save(task(0, "B", dependsOn = listOf(a))).ok()
        plan.add(TODAY, a)
        val deleted = DeleteTaskUseCase(support, plan)(a).ok()
        plan.cascade(a)
        assertNull(repo.get(a))
        assertTrue(repo.get(b)!!.dependsOn.isEmpty())
        assertEquals(listOf(b), deleted.dependents)
        assertEquals(listOf(TODAY), deleted.plannedOn)

        val restored = RestoreDeletedTaskUseCase(support, plan)(deleted).ok()
        assertEquals(a, restored)                       // same id: history stays linked
        assertEquals("A", repo.get(a)!!.title)
        assertEquals(listOf(a), repo.get(b)!!.dependsOn) // dependent reconnected
        assertEquals(listOf(TODAY), plan.daysFor(a))     // back in "Meu dia"
        assertTrue(history.entries.any { it.type == HistoryType.DELETED && it.taskTitle == "A" })
        assertTrue(history.entries.any { it.type == HistoryType.RESTORED && it.taskId == a })
    }

    @Test fun `duplicate resets progress`() = runTest {
        val id = save(task(0, "X", postpone = 0).copy(subtasks = listOf(Subtask(title = "s", done = true)))).ok()
        SetTaskDoneUseCase(support)(id, true)
        val copy = repo.get(DuplicateTaskUseCase(support)(id).ok())!!
        assertEquals("X (cópia)", copy.title)
        assertEquals(TaskStatus.PENDING, copy.status)
        assertFalse(copy.subtasks.single().done)
    }

    @Test fun `promote subtask creates independent task and removes it from parent`() = runTest {
        val id = save(task(0, "Pai", priority = Priority.HIGH, due = TODAY).copy(subtasks = listOf(Subtask(title = "Filha"), Subtask(title = "Outra")))).ok()
        val subId = repo.get(id)!!.subtasks.first().id
        val newId = PromoteSubtaskUseCase(support)(id, subId).ok()
        val n = repo.get(newId)!!
        assertEquals("Filha", n.title); assertEquals(Priority.HIGH, n.priority); assertEquals(TODAY, n.dueDate)
        assertEquals(listOf("Outra"), repo.get(id)!!.subtasks.map { it.title })
    }

    @Test fun `archive and restore`() = runTest {
        val id = save(task(0)).ok()
        val a = ArchiveTaskUseCase(support)
        a(id, true); assertEquals(TaskStatus.ARCHIVED, repo.get(id)!!.status)
        a(id, false); assertEquals(TaskStatus.PENDING, repo.get(id)!!.status)
    }

    @Test fun `quick add parses text and resolves category`() = runTest {
        val q = QuickAddTaskUseCase(save, FakeCategories(listOf(Category(7, "Engenharia de Dados", 0, "data"))), time)
        val id = q("Criar pipeline amanhã 9h !critica @engenharia #airflow").ok()
        val t = repo.get(id)!!
        assertEquals("Criar pipeline", t.title)
        assertEquals(7L, t.categoryId)
        assertEquals(Priority.CRITICAL, t.priority)
        assertEquals(TODAY.plusDays(1), t.dueDate)
        assertEquals(0, t.reminderMinutesBefore)
        assertEquals(listOf("airflow"), t.tags)
        assertTrue(q("   !alta") is AppResult.Failure)
    }
}
