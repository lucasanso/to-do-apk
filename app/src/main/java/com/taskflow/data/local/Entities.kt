package com.taskflow.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Long,
    val icon: String,
    val position: Int,
)

@Entity(
    tableName = "tasks",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("categoryId"), Index("status"), Index("dueDate")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String,
    val categoryId: Long?,
    val priority: Int,
    val status: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** epochDay */
    val dueDate: Long?,
    /** minute of day */
    val dueTime: Int?,
    val originalDueDate: Long?,
    val estimatedMinutes: Int?,
    val completedAt: Long?,
    val startedAt: Long?,
    val postponeCount: Int,
    val reminderMinutesBefore: Int?,
    val recurrence: String?,
)

@Entity(
    tableName = "subtasks",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class SubtaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val title: String,
    val done: Boolean,
    val position: Int,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "task_tags",
    primaryKeys = ["taskId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class TaskTagCrossRef(val taskId: Long, val tagId: Long)

@Entity(
    tableName = "task_dependencies",
    primaryKeys = ["taskId", "dependsOnId"],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["dependsOnId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("dependsOnId")],
)
data class TaskDependencyEntity(val taskId: Long, val dependsOnId: Long)

data class TaskWithRelations(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "id", entityColumn = "taskId") val subtasks: List<SubtaskEntity>,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(TaskTagCrossRef::class, parentColumn = "taskId", entityColumn = "tagId"))
    val tags: List<TagEntity>,
    @Relation(parentColumn = "id", entityColumn = "taskId") val dependencies: List<TaskDependencyEntity>,
)

@Entity(
    tableName = "habits",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("categoryId")],
)
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String,
    val categoryId: Long?,
    val rule: String,
    val reminderTime: Int?,
    val createdAt: Long,
    val startDate: Long,
    val archived: Boolean,
)

@Entity(
    tableName = "habit_logs",
    primaryKeys = ["habitId", "date"],
    foreignKeys = [ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE)],
)
data class HabitLogEntity(val habitId: Long, val date: Long)

@Entity(
    tableName = "day_plan",
    primaryKeys = ["date", "taskId"],
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class DayPlanEntity(val date: Long, val taskId: Long, val position: Int)

@Entity(tableName = "day_meta")
data class DayMetaEntity(@PrimaryKey val date: Long, val availableMinutes: Int)

/** History has no FK on purpose: it survives task deletion. */
@Entity(tableName = "history", indices = [Index("taskId"), Index("timestamp")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    val taskTitle: String,
    val type: String,
    val details: String,
    val timestamp: Long,
)
