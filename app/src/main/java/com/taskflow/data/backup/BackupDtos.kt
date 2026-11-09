package com.taskflow.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupDto(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val categories: List<CategoryDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
    val habits: List<HabitDto> = emptyList(),
    val habitLogs: List<HabitLogDto> = emptyList(),
    val dayPlan: List<DayPlanDto> = emptyList(),
    val dayMeta: List<DayMetaDto> = emptyList(),
    val history: List<HistoryDto> = emptyList(),
) {
    companion object {
        const val FORMAT = "taskflow-backup"
        const val SCHEMA_VERSION = 1
    }
}

@Serializable data class CategoryDto(val id: Long, val name: String, val colorArgb: Long, val icon: String, val position: Int = 0)

@Serializable
data class TaskDto(
    val id: Long, val title: String, val description: String = "", val categoryId: Long? = null,
    val priority: Int = 1, val status: Int = 0, val createdAt: Long, val updatedAt: Long,
    val dueDate: Long? = null, val dueTime: Int? = null, val originalDueDate: Long? = null,
    val estimatedMinutes: Int? = null, val completedAt: Long? = null, val startedAt: Long? = null,
    val postponeCount: Int = 0, val reminderMinutesBefore: Int? = null, val recurrence: String? = null,
    val tags: List<String> = emptyList(), val subtasks: List<SubtaskDto> = emptyList(), val dependsOn: List<Long> = emptyList(),
)

@Serializable data class SubtaskDto(val title: String, val done: Boolean = false)

@Serializable
data class HabitDto(
    val id: Long, val title: String, val description: String = "", val categoryId: Long? = null, val rule: String,
    val reminderTime: Int? = null, val createdAt: Long, val startDate: Long, val archived: Boolean = false,
)

@Serializable data class HabitLogDto(val habitId: Long, val date: Long)
@Serializable data class DayPlanDto(val date: Long, val taskId: Long, val position: Int)
@Serializable data class DayMetaDto(val date: Long, val availableMinutes: Int)
@Serializable data class HistoryDto(val taskId: Long? = null, val taskTitle: String, val type: String, val details: String = "", val timestamp: Long)
