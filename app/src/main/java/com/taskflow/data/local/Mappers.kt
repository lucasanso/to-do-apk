package com.taskflow.data.local

import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.HistoryEntry
import com.taskflow.domain.model.HistoryType
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import com.taskflow.domain.model.Subtask
import com.taskflow.domain.model.Task
import com.taskflow.domain.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute
fun Int.toLocalTime(): LocalTime = LocalTime.of(this / 60 % 24, this % 60)

fun TaskWithRelations.toDomain(): Task = Task(
    id = task.id,
    title = task.title,
    description = task.description,
    categoryId = task.categoryId,
    priority = Priority.fromLevel(task.priority),
    status = TaskStatus.fromCode(task.status),
    createdAt = Instant.ofEpochMilli(task.createdAt),
    updatedAt = Instant.ofEpochMilli(task.updatedAt),
    dueDate = task.dueDate?.let(LocalDate::ofEpochDay),
    dueTime = task.dueTime?.toLocalTime(),
    originalDueDate = task.originalDueDate?.let(LocalDate::ofEpochDay),
    estimatedMinutes = task.estimatedMinutes,
    completedAt = task.completedAt?.let(Instant::ofEpochMilli),
    startedAt = task.startedAt?.let(Instant::ofEpochMilli),
    postponeCount = task.postponeCount,
    reminderMinutesBefore = task.reminderMinutesBefore,
    recurrence = RecurrenceRule.decode(task.recurrence),
    tags = tags.map { it.name }.sorted(),
    subtasks = subtasks.sortedBy { it.position }.map { Subtask(it.id, it.taskId, it.title, it.done, it.position) },
    dependsOn = dependencies.map { it.dependsOnId },
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id, title = title, description = description, categoryId = categoryId, priority = priority.level,
    status = status.code, createdAt = createdAt.toEpochMilli(), updatedAt = updatedAt.toEpochMilli(),
    dueDate = dueDate?.toEpochDay(), dueTime = dueTime?.toMinuteOfDay(), originalDueDate = originalDueDate?.toEpochDay(),
    estimatedMinutes = estimatedMinutes, completedAt = completedAt?.toEpochMilli(), startedAt = startedAt?.toEpochMilli(),
    postponeCount = postponeCount, reminderMinutesBefore = reminderMinutesBefore, recurrence = recurrence?.encode(),
)

fun CategoryEntity.toDomain() = Category(id, name, colorArgb, icon, position)
fun Category.toEntity() = CategoryEntity(id, name, colorArgb, icon, position)

fun HabitEntity.toDomain() = Habit(
    id = id, title = title, description = description, categoryId = categoryId,
    rule = RecurrenceRule.decode(rule) ?: RecurrenceRule(RecurrenceType.DAILY),
    reminderTime = reminderTime?.toLocalTime(), createdAt = Instant.ofEpochMilli(createdAt),
    startDate = LocalDate.ofEpochDay(startDate), archived = archived,
)
fun Habit.toEntity() = HabitEntity(id, title, description, categoryId, rule.encode(), reminderTime?.toMinuteOfDay(),
    createdAt.toEpochMilli(), startDate.toEpochDay(), archived)

fun HistoryEntity.toDomain() = HistoryEntry(id, taskId, taskTitle,
    runCatching { HistoryType.valueOf(type) }.getOrDefault(HistoryType.UPDATED), details, Instant.ofEpochMilli(timestamp))
fun HistoryEntry.toEntity() = HistoryEntity(id, taskId, taskTitle, type.name, details, timestamp.toEpochMilli())
