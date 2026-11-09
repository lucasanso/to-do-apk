package com.taskflow.domain.usecase

import com.taskflow.core.AppError
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.core.appRunCatching
import com.taskflow.core.fail
import com.taskflow.domain.engine.QuickCaptureParser
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.model.HistoryEntry
import com.taskflow.domain.model.HistoryType
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.Task
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HistoryRepository
import com.taskflow.domain.repository.ReminderScheduler
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.engine.TaskFilters
import java.time.LocalDate
import javax.inject.Inject

/** Shared helpers for task mutations: history logging and reminder sync. */
class TaskMutationSupport @Inject constructor(
    val tasks: TaskRepository,
    private val history: HistoryRepository,
    private val reminders: ReminderScheduler,
    val time: TimeProvider,
) {
    suspend fun log(task: Task, type: HistoryType, details: String = "") {
        history.add(HistoryEntry(taskId = task.id, taskTitle = task.title, type = type, details = details, timestamp = time.now()))
    }

    fun syncReminder(task: Task) {
        if (task.status.isOpen && task.reminderMinutesBefore != null && task.dueDate != null) reminders.scheduleTask(task)
        else reminders.cancelTask(task.id)
    }

    suspend fun require(id: Long): Task = tasks.get(id) ?: fail(AppError.NotFound("Tarefa não encontrada"))
}

class SaveTaskUseCase @Inject constructor(private val s: TaskMutationSupport) {
    suspend operator fun invoke(input: Task): AppResult<Long> = appRunCatching {
        val title = input.title.trim()
        if (title.isEmpty()) fail(AppError.Validation("O título é obrigatório"))
        if (title.length > 200) fail(AppError.Validation("Título muito longo (máx. 200)"))
        if (input.estimatedMinutes != null && input.estimatedMinutes !in 1..24 * 60) fail(AppError.Validation("Duração deve estar entre 1 min e 24 h"))
        if (input.dependsOn.contains(input.id) && input.id != 0L) fail(AppError.Validation("Uma tarefa não pode depender de si mesma"))
        val now = s.time.now()
        val existing = if (input.id != 0L) s.require(input.id) else null
        if (existing != null && input.dependsOn.isNotEmpty()) ensureNoCycle(input)
        val task = input.copy(
            title = title,
            description = input.description.trim(),
            tags = input.tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
            subtasks = input.subtasks.filter { it.title.isNotBlank() }.mapIndexed { i, st -> st.copy(position = i, title = st.title.trim()) },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            originalDueDate = existing?.originalDueDate ?: input.dueDate,
            completedAt = if (input.status == TaskStatus.DONE) (existing?.completedAt ?: now) else if (input.status == TaskStatus.ARCHIVED) existing?.completedAt else null,
        )
        val id = s.tasks.upsert(task)
        val saved = task.copy(id = id)
        s.log(saved, if (existing == null) HistoryType.CREATED else HistoryType.UPDATED, changeSummary(existing, saved))
        s.syncReminder(saved)
        id
    }

    private suspend fun ensureNoCycle(task: Task) {
        val all = s.tasks.getAll().associateBy { it.id }.toMutableMap()
        all[task.id] = task
        val visiting = ArrayDeque(task.dependsOn)
        val seen = mutableSetOf<Long>()
        while (visiting.isNotEmpty()) {
            val cur = visiting.removeFirst()
            if (cur == task.id) fail(AppError.Validation("Dependência circular detectada"))
            if (seen.add(cur)) all[cur]?.dependsOn?.let { visiting.addAll(it) }
        }
    }

    private fun changeSummary(old: Task?, new: Task): String {
        if (old == null) return ""
        val changes = buildList {
            if (old.title != new.title) add("título")
            if (old.priority != new.priority) add("prioridade: ${old.priority.label} → ${new.priority.label}")
            if (old.dueDate != new.dueDate) add("prazo: ${old.dueDate ?: "—"} → ${new.dueDate ?: "—"}")
            if (old.status != new.status) add("status: ${old.status.label} → ${new.status.label}")
            if (old.categoryId != new.categoryId) add("categoria")
            if (old.description != new.description) add("descrição")
        }
        return changes.joinToString("; ")
    }
}

/** Creates a task from quick-capture text with sensible defaults. */
class QuickAddTaskUseCase @Inject constructor(
    private val save: SaveTaskUseCase,
    private val categories: CategoryRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(text: String, defaultDate: LocalDate? = null): AppResult<Long> {
        val parsed = QuickCaptureParser.parse(text, time.today())
        if (parsed.title.isBlank()) return AppResult.Failure(AppError.Validation("Digite um título"))
        val categoryId = parsed.categoryHint?.let { hint ->
            val n = TaskFilters.norm(hint)
            categories.getAll().firstOrNull { TaskFilters.norm(it.name).replace(" ", "").startsWith(n) }?.id
        }
        return save(
            Task(
                title = parsed.title,
                createdAt = time.now(),
                dueDate = parsed.dueDate ?: defaultDate,
                dueTime = parsed.dueTime,
                priority = parsed.priority ?: Priority.MEDIUM,
                tags = parsed.tags,
                categoryId = categoryId,
                estimatedMinutes = parsed.estimatedMinutes,
                reminderMinutesBefore = if (parsed.dueTime != null) 0 else null,
                subtasks = parsed.subtasks.mapIndexed { i, t -> com.taskflow.domain.model.Subtask(title = t, position = i) },
            )
        )
    }
}

class SetTaskDoneUseCase @Inject constructor(private val s: TaskMutationSupport) {
    /** Marks a task done (creating the next occurrence when recurring) or reopens it. Returns id of next occurrence, if any. */
    suspend operator fun invoke(id: Long, done: Boolean): AppResult<Long?> = appRunCatching {
        val task = s.require(id)
        val now = s.time.now()
        if (done) {
            if (task.status == TaskStatus.DONE) return@appRunCatching null
            val updated = task.copy(status = TaskStatus.DONE, completedAt = now, updatedAt = now,
                subtasks = task.subtasks.map { it.copy(done = true) })
            s.tasks.upsert(updated)
            s.log(updated, HistoryType.COMPLETED)
            s.syncReminder(updated)
            val rule = task.recurrence ?: return@appRunCatching null
            val anchor = task.dueDate ?: s.time.today()
            var next = RecurrenceCalculator.nextOccurrence(rule, task.originalDueDate ?: anchor, anchor)
            val today = s.time.today()
            while (next.isBefore(today)) next = RecurrenceCalculator.nextOccurrence(rule, task.originalDueDate ?: anchor, next)
            val nextTask = task.copy(
                id = 0, status = TaskStatus.PENDING, createdAt = now, updatedAt = now, completedAt = null, startedAt = null,
                dueDate = next, postponeCount = 0, dependsOn = emptyList(),
                originalDueDate = task.originalDueDate ?: anchor,
                subtasks = task.subtasks.map { it.copy(id = 0, taskId = 0, done = false) },
            )
            val nextId = s.tasks.upsert(nextTask)
            s.syncReminder(nextTask.copy(id = nextId))
            nextId
        } else {
            if (task.status == TaskStatus.PENDING) return@appRunCatching null
            val updated = task.copy(status = TaskStatus.PENDING, completedAt = null, updatedAt = now)
            s.tasks.upsert(updated)
            s.log(updated, HistoryType.REOPENED)
            s.syncReminder(updated)
            null
        }
    }
}

class StartTaskUseCase @Inject constructor(private val s: TaskMutationSupport) {
    suspend operator fun invoke(id: Long): AppResult<Unit> = appRunCatching {
        val t = s.require(id)
        if (t.status != TaskStatus.PENDING) return@appRunCatching
        val now = s.time.now()
        val u = t.copy(status = TaskStatus.IN_PROGRESS, startedAt = now, updatedAt = now)
        s.tasks.upsert(u); s.log(u, HistoryType.STARTED)
    }
}

class PostponeTaskUseCase @Inject constructor(private val s: TaskMutationSupport) {
    /** Moves the deadline to [newDate] (default: tomorrow, or +1 day from current due). Counts as a postponement. */
    suspend operator fun invoke(id: Long, newDate: LocalDate? = null): AppResult<LocalDate> = appRunCatching {
        val t = s.require(id)
        if (!t.status.isOpen) fail(AppError.Validation("Somente tarefas abertas podem ser adiadas"))
        val today = s.time.today()
        val target = newDate ?: maxOf(today, t.dueDate ?: today).plusDays(1)
        if (target.isBefore(today)) fail(AppError.Validation("A nova data não pode estar no passado"))
        val u = t.copy(dueDate = target, postponeCount = t.postponeCount + 1, updatedAt = s.time.now(),
            originalDueDate = t.originalDueDate ?: t.dueDate ?: today)
        s.tasks.upsert(u)
        s.log(u, HistoryType.POSTPONED, "${t.dueDate ?: "sem prazo"} → $target")
        s.syncReminder(u)
        target
    }
}

class ArchiveTaskUseCase @Inject constructor(private val s: TaskMutationSupport) {
    suspend operator fun invoke(id: Long, archive: Boolean = true): AppResult<Unit> = appRunCatching {
        val t = s.require(id)
        val u = t.copy(status = if (archive) TaskStatus.ARCHIVED else TaskStatus.PENDING, updatedAt = s.time.now())
        s.tasks.upsert(u)
        s.log(u, if (archive) HistoryType.ARCHIVED else HistoryType.RESTORED)
        s.syncReminder(u)
    }
}

class DeleteTaskUseCase @Inject constructor(private val s: TaskMutationSupport) {
    /** Deletes the task. Subtasks, tags links, plan entries and dependency edges cascade in the DB. */
    suspend operator fun invoke(id: Long): AppResult<Task> = appRunCatching {
        val t = s.require(id)
        s.syncReminder(t.copy(status = TaskStatus.ARCHIVED))
        s.tasks.delete(id)
        s.log(t, HistoryType.DELETED)
        t
    }
}

class RestoreDeletedTaskUseCase @Inject constructor(private val s: TaskMutationSupport) {
    /** Undo for deletion: re-inserts the snapshot (new id, dependencies kept only if they still exist). */
    suspend operator fun invoke(snapshot: Task): AppResult<Long> = appRunCatching {
        val existing = s.tasks.getAll().map { it.id }.toSet()
        val t = snapshot.copy(id = 0, dependsOn = snapshot.dependsOn.filter { it in existing },
            subtasks = snapshot.subtasks.map { it.copy(id = 0, taskId = 0) })
        val id = s.tasks.upsert(t)
        s.log(t.copy(id = id), HistoryType.RESTORED)
        s.syncReminder(t.copy(id = id))
        id
    }
}

class DuplicateTaskUseCase @Inject constructor(private val s: TaskMutationSupport) {
    suspend operator fun invoke(id: Long): AppResult<Long> = appRunCatching {
        val t = s.require(id)
        val now = s.time.now()
        val copy = t.copy(id = 0, title = "${t.title} (cópia)".take(200), status = TaskStatus.PENDING, createdAt = now, updatedAt = now,
            completedAt = null, startedAt = null, postponeCount = 0, originalDueDate = t.dueDate,
            subtasks = t.subtasks.map { it.copy(id = 0, taskId = 0, done = false) })
        val newId = s.tasks.upsert(copy)
        s.log(copy.copy(id = newId), HistoryType.DUPLICATED, "a partir de \"${t.title}\"")
        s.syncReminder(copy.copy(id = newId))
        newId
    }
}

class ToggleSubtaskUseCase @Inject constructor(private val s: TaskMutationSupport) {
    suspend operator fun invoke(taskId: Long, subtaskId: Long): AppResult<Unit> = appRunCatching {
        val t = s.require(taskId)
        s.tasks.upsert(t.copy(subtasks = t.subtasks.map { if (it.id == subtaskId) it.copy(done = !it.done) else it }, updatedAt = s.time.now()))
    }
}

class PromoteSubtaskUseCase @Inject constructor(private val s: TaskMutationSupport) {
    /** Turns a subtask into an independent task inheriting category, priority, deadline and tags. */
    suspend operator fun invoke(taskId: Long, subtaskId: Long): AppResult<Long> = appRunCatching {
        val parent = s.require(taskId)
        val sub = parent.subtasks.firstOrNull { it.id == subtaskId } ?: fail(AppError.NotFound("Subtarefa não encontrada"))
        val now = s.time.now()
        val newTask = Task(title = sub.title, createdAt = now, categoryId = parent.categoryId, priority = parent.priority,
            dueDate = parent.dueDate, originalDueDate = parent.dueDate, tags = parent.tags,
            status = if (sub.done) TaskStatus.DONE else TaskStatus.PENDING, completedAt = if (sub.done) now else null,
            description = "Originada de \"${parent.title}\"")
        val newId = s.tasks.upsert(newTask)
        s.tasks.upsert(parent.copy(subtasks = parent.subtasks.filter { it.id != subtaskId }, updatedAt = now))
        s.log(newTask.copy(id = newId), HistoryType.PROMOTED, "de \"${parent.title}\"")
        newId
    }
}
