package com.taskflow.domain.voice

import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceRule
import java.time.LocalDate
import java.time.LocalTime

/** What a spoken (or typed) sentence asks the app to do. */
sealed interface VoiceIntent {
    /** Create a task from the sentence. */
    data class Create(val draft: TaskDraft) : VoiceIntent

    /** Mark an existing task or habit as done; [query] is the part of the sentence that names it. */
    data class Complete(val query: String) : VoiceIntent

    data class Unknown(val text: String) : VoiceIntent
}

/** Fields extracted from a sentence; everything except [title] is optional. */
data class TaskDraft(
    val title: String,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val estimatedMinutes: Int? = null,
    val priority: Priority? = null,
    val categoryHint: String? = null,
    val tags: List<String> = emptyList(),
    val recurrence: RecurrenceRule? = null,
    val reminderMinutesBefore: Int? = null,
)

/** Something that can be completed by voice. */
data class CompletionCandidate(
    val id: Long,
    val kind: Kind,
    val title: String,
    /** Due today/overdue (tasks) or scheduled today (habits): small tie-break boost. */
    val relevantToday: Boolean = false,
) {
    enum class Kind { TASK, HABIT }
}

data class ScoredCandidate(val candidate: CompletionCandidate, val score: Double)

sealed interface TaskMatch {
    data class Unique(val match: ScoredCandidate) : TaskMatch
    data class Ambiguous(val options: List<ScoredCandidate>) : TaskMatch
    data object NotFound : TaskMatch
}
