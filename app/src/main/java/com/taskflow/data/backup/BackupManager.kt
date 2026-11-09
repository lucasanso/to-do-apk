package com.taskflow.data.backup

import androidx.room.withTransaction
import com.taskflow.core.AppError
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.core.appRunCatching
import com.taskflow.core.fail
import com.taskflow.data.local.CategoryEntity
import com.taskflow.data.local.DayMetaEntity
import com.taskflow.data.local.DayPlanEntity
import com.taskflow.data.local.HabitEntity
import com.taskflow.data.local.HabitLogEntity
import com.taskflow.data.local.HistoryEntity
import com.taskflow.data.local.SubtaskEntity
import com.taskflow.data.local.TagEntity
import com.taskflow.data.local.TaskDependencyEntity
import com.taskflow.data.local.TaskEntity
import com.taskflow.data.local.TaskFlowDatabase
import com.taskflow.data.local.TaskTagCrossRef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class ImportSummary(val tasks: Int, val habits: Int, val categories: Int)

@Singleton
class BackupManager @Inject constructor(private val db: TaskFlowDatabase, private val time: TimeProvider) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun exportJson(): String = db.withTransaction {
        val b = db.backupDao()
        val tags = b.allTags().associateBy { it.id }
        val taskTags = b.allTaskTags().groupBy({ it.taskId }, { tags[it.tagId]?.name })
        val subs = b.allSubtasks().groupBy { it.taskId }
        val deps = b.allDependencies().groupBy({ it.taskId }, { it.dependsOnId })
        val dto = BackupDto(
            exportedAt = time.now().toEpochMilli(),
            categories = db.categoryDao().getAll().map { CategoryDto(it.id, it.name, it.colorArgb, it.icon, it.position) },
            tasks = b.allTasks().map { t ->
                TaskDto(t.id, t.title, t.description, t.categoryId, t.priority, t.status, t.createdAt, t.updatedAt, t.dueDate, t.dueTime,
                    t.originalDueDate, t.estimatedMinutes, t.completedAt, t.startedAt, t.postponeCount, t.reminderMinutesBefore, t.recurrence,
                    taskTags[t.id].orEmpty().filterNotNull(), subs[t.id].orEmpty().sortedBy { it.position }.map { SubtaskDto(it.title, it.done) },
                    deps[t.id].orEmpty())
            },
            habits = db.habitDao().getAll().map { HabitDto(it.id, it.title, it.description, it.categoryId, it.rule, it.reminderTime, it.createdAt, it.startDate, it.archived) },
            habitLogs = db.habitDao().allLogs().map { HabitLogDto(it.habitId, it.date) },
            dayPlan = db.planDao().getAll().map { DayPlanDto(it.date, it.taskId, it.position) },
            dayMeta = db.planDao().allMeta().map { DayMetaDto(it.date, it.availableMinutes) },
            history = db.historyDao().getAll().map { HistoryDto(it.taskId, it.taskTitle, it.type, it.details, it.timestamp) },
        )
        json.encodeToString(BackupDto.serializer(), dto)
    }

    fun parse(raw: String): AppResult<BackupDto> = appRunCatching {
        val dto = try { json.decodeFromString(BackupDto.serializer(), raw) }
        catch (e: SerializationException) { fail(AppError.InvalidBackup("JSON inválido ou incompatível: ${e.message?.take(120)}")) }
        catch (e: IllegalArgumentException) { fail(AppError.InvalidBackup("JSON inválido: ${e.message?.take(120)}")) }
        val errors = BackupValidator.validate(dto)
        if (errors.isNotEmpty()) fail(AppError.InvalidBackup(errors.take(5).joinToString("\n")))
        dto
    }

    /** Validates first, then replaces all data in one transaction (all-or-nothing). */
    suspend fun importJson(raw: String): AppResult<ImportSummary> {
        val dto = when (val r = parse(raw)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value }
        return appRunCatching {
            db.withTransaction {
                val b = db.backupDao()
                b.clearTasks(); b.clearHabits(); b.clearTags(); b.clearCategories(); b.clearHistory(); b.clearMeta()
                b.insertCategories(dto.categories.map { CategoryEntity(it.id, it.name, it.colorArgb, it.icon, it.position) })
                b.insertTasks(dto.tasks.map { t ->
                    TaskEntity(t.id, t.title, t.description, t.categoryId, t.priority, t.status, t.createdAt, t.updatedAt, t.dueDate, t.dueTime,
                        t.originalDueDate, t.estimatedMinutes, t.completedAt, t.startedAt, t.postponeCount, t.reminderMinutesBefore, t.recurrence)
                })
                b.insertSubtasks(dto.tasks.flatMap { t -> t.subtasks.mapIndexed { i, s -> SubtaskEntity(0, t.id, s.title, s.done, i) } })
                val tagNames = dto.tasks.flatMap { it.tags }.distinctBy { it.lowercase() }
                b.insertTags(tagNames.mapIndexed { i, n -> TagEntity(i + 1L, n) })
                val tagIdByName = tagNames.mapIndexed { i, n -> n.lowercase() to i + 1L }.toMap()
                b.insertTaskTags(dto.tasks.flatMap { t -> t.tags.mapNotNull { tagIdByName[it.lowercase()] }.distinct().map { TaskTagCrossRef(t.id, it) } })
                b.insertDependencies(dto.tasks.flatMap { t -> t.dependsOn.distinct().map { TaskDependencyEntity(t.id, it) } })
                b.insertHabits(dto.habits.map { HabitEntity(it.id, it.title, it.description, it.categoryId, it.rule, it.reminderTime, it.createdAt, it.startDate, it.archived) })
                b.insertHabitLogs(dto.habitLogs.map { HabitLogEntity(it.habitId, it.date) })
                b.insertPlan(dto.dayPlan.map { DayPlanEntity(it.date, it.taskId, it.position) })
                b.insertMeta(dto.dayMeta.map { DayMetaEntity(it.date, it.availableMinutes) })
                b.insertHistory(dto.history.map { HistoryEntity(0, it.taskId, it.taskTitle, it.type, it.details, it.timestamp) })
            }
            ImportSummary(dto.tasks.size, dto.habits.size, dto.categories.size)
        }
    }
}
