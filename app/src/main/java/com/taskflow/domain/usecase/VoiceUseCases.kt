package com.taskflow.domain.usecase

import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.DueRules
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.Task
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.repository.OnDeviceAi
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.voice.CompletionCandidate
import com.taskflow.domain.voice.Interpretation
import com.taskflow.domain.voice.TaskDraft
import com.taskflow.domain.voice.VoiceInterpreter
import javax.inject.Inject

/** Turns a sentence into an [Interpretation], using Gemini Nano when allowed and available. */
class InterpretVoiceCommandUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
    private val categories: CategoryRepository,
    private val ai: OnDeviceAi,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(text: String, useOnDeviceAi: Boolean): Interpretation {
        val now = time.nowDateTime()
        val catNames = categories.getAll().map { it.name }
        val candidates = candidates()
        val reading = if (useOnDeviceAi) ai.read(text, now.toLocalDate(), catNames, candidates) else null
        return VoiceInterpreter.interpret(text, now, catNames, candidates, reading)
    }

    /** Open tasks (most relevant first) and active habits. */
    suspend fun candidates(): List<CompletionCandidate> {
        val now = time.nowDateTime(); val today = now.toLocalDate()
        val open = tasks.getAll().filter { it.status.isOpen }
            .sortedWith(compareBy<Task>({ it.dueDate == null }, { it.dueDate }, { -it.priority.level }))
            .map { CompletionCandidate(it.id, CompletionCandidate.Kind.TASK, it.title,
                relevantToday = it.dueDate?.let { d -> !d.isAfter(today) } == true || DueRules.isOverdue(it, now)) }
        val habitItems = habits.getAll().filter { !it.archived }.map {
            CompletionCandidate(it.id, CompletionCandidate.Kind.HABIT, it.title,
                relevantToday = RecurrenceCalculator.occursOn(it.rule, it.startDate, today))
        }
        return habitItems.filter { it.relevantToday } + open + habitItems.filter { !it.relevantToday }
    }
}

sealed interface VoiceOutcome {
    data class Created(val taskId: Long, val title: String, val draft: TaskDraft, val categoryName: String?) : VoiceOutcome
    data class Completed(val candidate: CompletionCandidate, val nextOccurrenceId: Long?) : VoiceOutcome
    data class AlreadyDone(val candidate: CompletionCandidate) : VoiceOutcome
    data class Failed(val message: String) : VoiceOutcome
}

/** Applies an interpretation and knows how to undo it. */
class ExecuteVoiceCommandUseCase @Inject constructor(
    private val save: SaveTaskUseCase,
    private val setDone: SetTaskDoneUseCase,
    private val delete: DeleteTaskUseCase,
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
    private val categories: CategoryRepository,
    private val time: TimeProvider,
) {
    suspend fun create(draft: TaskDraft): VoiceOutcome {
        val category = draft.categoryHint?.let { hint -> categories.getAll().firstOrNull { it.name.equals(hint, ignoreCase = true) } }
        val task = Task(
            title = draft.title, createdAt = time.now(), categoryId = category?.id,
            priority = draft.priority ?: Priority.MEDIUM, dueDate = draft.dueDate, dueTime = draft.dueTime,
            estimatedMinutes = draft.estimatedMinutes, tags = draft.tags, recurrence = draft.recurrence,
            reminderMinutesBefore = draft.reminderMinutesBefore,
        )
        return when (val r = save(task, note = "por voz")) {
            is AppResult.Success -> VoiceOutcome.Created(r.value, task.title, draft, category?.name)
            is AppResult.Failure -> VoiceOutcome.Failed(r.error.message)
        }
    }

    suspend fun complete(c: CompletionCandidate): VoiceOutcome = when (c.kind) {
        CompletionCandidate.Kind.TASK -> {
            val t = tasks.get(c.id)
            when {
                t == null -> VoiceOutcome.Failed("Tarefa não encontrada")
                !t.status.isOpen -> VoiceOutcome.AlreadyDone(c)
                else -> when (val r = setDone(c.id, true)) {
                    is AppResult.Success -> VoiceOutcome.Completed(c, r.value)
                    is AppResult.Failure -> VoiceOutcome.Failed(r.error.message)
                }
            }
        }
        CompletionCandidate.Kind.HABIT -> {
            val today = time.today()
            if (habits.get(c.id) == null) VoiceOutcome.Failed("Hábito não encontrado")
            else if (today in habits.logsFor(c.id)) VoiceOutcome.AlreadyDone(c)
            else { habits.setDone(c.id, today, true); VoiceOutcome.Completed(c, null) }
        }
    }

    suspend fun undo(outcome: VoiceOutcome) {
        when (outcome) {
            is VoiceOutcome.Created -> delete(outcome.taskId)
            is VoiceOutcome.Completed -> when (outcome.candidate.kind) {
                CompletionCandidate.Kind.TASK -> {
                    outcome.nextOccurrenceId?.let { delete(it) }
                    setDone(outcome.candidate.id, false)
                }
                CompletionCandidate.Kind.HABIT -> habits.setDone(outcome.candidate.id, time.today(), false)
            }
            else -> Unit
        }
    }
}
