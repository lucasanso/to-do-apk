package com.taskflow.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class Priority(val level: Int, val label: String) {
    LOW(0, "Baixa"), MEDIUM(1, "Média"), HIGH(2, "Alta"), CRITICAL(3, "Crítica");

    companion object {
        fun fromLevel(level: Int): Priority = entries.firstOrNull { it.level == level } ?: MEDIUM
    }
}

enum class TaskStatus(val code: Int, val label: String) {
    PENDING(0, "Pendente"), IN_PROGRESS(1, "Em andamento"), DONE(2, "Concluída"), ARCHIVED(3, "Arquivada");

    val isOpen: Boolean get() = this == PENDING || this == IN_PROGRESS

    companion object {
        fun fromCode(code: Int): TaskStatus = entries.firstOrNull { it.code == code } ?: PENDING
    }
}

data class Category(
    val id: Long = 0,
    val name: String,
    val colorArgb: Long,
    val icon: String,
    val position: Int = 0,
)

data class Subtask(
    val id: Long = 0,
    val taskId: Long = 0,
    val title: String,
    val done: Boolean = false,
    val position: Int = 0,
)

data class Task(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val categoryId: Long? = null,
    val priority: Priority = Priority.MEDIUM,
    val status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    /** First deadline ever set. Used to measure how far a task slipped. */
    val originalDueDate: LocalDate? = null,
    val estimatedMinutes: Int? = null,
    val completedAt: Instant? = null,
    val startedAt: Instant? = null,
    val postponeCount: Int = 0,
    /** Minutes before due date/time to remind; null = no reminder. */
    val reminderMinutesBefore: Int? = null,
    val recurrence: RecurrenceRule? = null,
    val tags: List<String> = emptyList(),
    val subtasks: List<Subtask> = emptyList(),
    val dependsOn: List<Long> = emptyList(),
)

data class Habit(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val categoryId: Long? = null,
    val rule: RecurrenceRule,
    val reminderTime: LocalTime? = null,
    val createdAt: Instant,
    val startDate: LocalDate,
    val archived: Boolean = false,
)

data class HabitWithStats(
    val habit: Habit,
    val doneToday: Boolean,
    val dueToday: Boolean,
    val currentStreak: Int,
    val bestStreak: Int,
    val completionRate30d: Double,
    val recentLog: Set<LocalDate>,
)

enum class HistoryType(val label: String) {
    CREATED("Criada"), UPDATED("Editada"), COMPLETED("Concluída"), REOPENED("Reaberta"),
    POSTPONED("Adiada"), ARCHIVED("Arquivada"), DELETED("Excluída"), DUPLICATED("Duplicada"),
    STARTED("Iniciada"), PROMOTED("Subtarefa promovida"), RESTORED("Restaurada"),
}

data class HistoryEntry(
    val id: Long = 0,
    val taskId: Long?,
    val taskTitle: String,
    val type: HistoryType,
    val details: String = "",
    val timestamp: Instant,
)

data class DayPlanItem(val date: LocalDate, val taskId: Long, val position: Int)
