package com.taskflow.domain.repository

import com.taskflow.domain.model.Category
import com.taskflow.domain.model.DayPlanItem
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.HistoryEntry
import com.taskflow.domain.model.Task
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface TaskRepository {
    fun observeAll(): Flow<List<Task>>
    fun observe(id: Long): Flow<Task?>
    suspend fun get(id: Long): Task?
    suspend fun getAll(): List<Task>
    /** Inserts or updates the task with its tags, subtasks and dependencies atomically. Returns the id. */
    suspend fun upsert(task: Task): Long
    suspend fun delete(id: Long)
    suspend fun allTags(): List<String>
}

interface CategoryRepository {
    fun observeAll(): Flow<List<Category>>
    suspend fun getAll(): List<Category>
    suspend fun upsert(category: Category): Long
    /** Deletes the category; tasks and habits keep existing without category. */
    suspend fun delete(id: Long)
    suspend fun seedDefaultsIfEmpty()
}

interface HabitRepository {
    fun observeAll(): Flow<List<Habit>>
    fun observeLogs(): Flow<Map<Long, Set<LocalDate>>>
    suspend fun get(id: Long): Habit?
    suspend fun getAll(): List<Habit>
    suspend fun upsert(habit: Habit): Long
    suspend fun delete(id: Long)
    suspend fun setDone(habitId: Long, date: LocalDate, done: Boolean)
    suspend fun logsFor(habitId: Long): Set<LocalDate>
}

interface PlanRepository {
    fun observeAll(): Flow<List<DayPlanItem>>
    fun observeDay(date: LocalDate): Flow<List<DayPlanItem>>
    fun observeAvailableMinutes(date: LocalDate): Flow<Int?>
    suspend fun add(date: LocalDate, taskId: Long)
    suspend fun remove(date: LocalDate, taskId: Long)
    suspend fun reorder(date: LocalDate, orderedTaskIds: List<Long>)
    suspend fun setAvailableMinutes(date: LocalDate, minutes: Int)
}

interface HistoryRepository {
    fun observeRecent(limit: Int = 300): Flow<List<HistoryEntry>>
    fun observeForTask(taskId: Long): Flow<List<HistoryEntry>>
    suspend fun add(entry: HistoryEntry)
}
