package com.taskflow

import com.taskflow.domain.model.Category
import com.taskflow.domain.model.DayPlanItem
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.HistoryEntry
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.Task
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HistoryRepository
import com.taskflow.domain.repository.ReminderScheduler
import com.taskflow.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

val NOW: LocalDateTime = LocalDateTime.of(2026, 11, 9, 10, 0)
val TODAY: LocalDate = NOW.toLocalDate()

fun task(
    id: Long, title: String = "T$id", priority: Priority = Priority.MEDIUM, due: LocalDate? = null,
    createdDaysAgo: Long = 0, estimate: Int? = null, postpone: Int = 0, dependsOn: List<Long> = emptyList(),
) = Task(
    id = id, title = title, priority = priority, dueDate = due, originalDueDate = due,
    createdAt = NOW.minusDays(createdDaysAgo).toInstant(ZoneOffset.UTC), estimatedMinutes = estimate,
    postponeCount = postpone, dependsOn = dependsOn,
)

class FakeTaskRepository : TaskRepository {
    private val data = MutableStateFlow<Map<Long, Task>>(emptyMap())
    private var nextId = 1L
    override fun observeAll(): Flow<List<Task>> = data.map { it.values.sortedBy(Task::id) }
    override fun observe(id: Long): Flow<Task?> = data.map { it[id] }
    override suspend fun get(id: Long) = data.value[id]
    override suspend fun getAll() = data.value.values.sortedBy { it.id }
    override suspend fun upsert(task: Task): Long {
        val id = if (task.id == 0L) nextId++ else task.id.also { nextId = maxOf(nextId, it + 1) }
        var sid = 1000L * id
        data.value = data.value + (id to task.copy(id = id, subtasks = task.subtasks.map { if (it.id == 0L) it.copy(id = ++sid, taskId = id) else it }))
        return id
    }
    override suspend fun delete(id: Long) {
        data.value = (data.value - id).mapValues { (_, t) -> t.copy(dependsOn = t.dependsOn - id) }
    }
    override suspend fun allTags() = data.value.values.flatMap { it.tags }.distinct().sorted()
}

class FakeHistory : HistoryRepository {
    val entries = mutableListOf<HistoryEntry>()
    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = MutableStateFlow(entries.toList())
    override fun observeForTask(taskId: Long): Flow<List<HistoryEntry>> = MutableStateFlow(entries.filter { it.taskId == taskId })
    override suspend fun add(entry: HistoryEntry) { entries += entry }
}

class FakeReminders : ReminderScheduler {
    val scheduled = mutableSetOf<Long>()
    override fun scheduleTask(task: Task) { scheduled += task.id }
    override fun cancelTask(taskId: Long) { scheduled -= taskId }
    override fun scheduleHabit(habit: Habit) {}
    override fun cancelHabit(habitId: Long) {}
}

class FakeCategories(private val list: List<Category>) : CategoryRepository {
    override fun observeAll(): Flow<List<Category>> = MutableStateFlow(list)
    override suspend fun getAll() = list
    override suspend fun upsert(category: Category) = category.id
    override suspend fun delete(id: Long) {}
    override suspend fun seedDefaultsIfEmpty() {}
}

fun plan(date: LocalDate, vararg ids: Long) = ids.mapIndexed { i, id -> DayPlanItem(date, id, i) }

class FakePlan : com.taskflow.domain.repository.PlanRepository {
    val items = MutableStateFlow<List<DayPlanItem>>(emptyList())
    override fun observeAll(): Flow<List<DayPlanItem>> = items
    override fun observeDay(date: LocalDate): Flow<List<DayPlanItem>> = items.map { l -> l.filter { it.date == date } }
    override fun observeAvailableMinutes(date: LocalDate): Flow<Int?> = MutableStateFlow(null)
    override suspend fun daysFor(taskId: Long) = items.value.filter { it.taskId == taskId }.map { it.date }
    override suspend fun add(date: LocalDate, taskId: Long) {
        if (items.value.none { it.date == date && it.taskId == taskId }) items.value = items.value + DayPlanItem(date, taskId, items.value.size)
    }
    override suspend fun remove(date: LocalDate, taskId: Long) { items.value = items.value.filterNot { it.date == date && it.taskId == taskId } }
    override suspend fun reorder(date: LocalDate, orderedTaskIds: List<Long>) {}
    override suspend fun setAvailableMinutes(date: LocalDate, minutes: Int) {}
    /** Mirrors the DB cascade when a task is deleted. */
    fun cascade(taskId: Long) { items.value = items.value.filterNot { it.taskId == taskId } }
}

class FakeHabitRepository(list: List<com.taskflow.domain.model.Habit> = emptyList()) : com.taskflow.domain.repository.HabitRepository {
    val habits = list.associateBy { it.id }.toMutableMap()
    val logs = MutableStateFlow<Map<Long, Set<LocalDate>>>(emptyMap())
    override fun observeAll(): Flow<List<com.taskflow.domain.model.Habit>> = MutableStateFlow(habits.values.toList())
    override fun observeLogs(): Flow<Map<Long, Set<LocalDate>>> = logs
    override suspend fun get(id: Long) = habits[id]
    override suspend fun getAll() = habits.values.toList()
    override suspend fun upsert(habit: com.taskflow.domain.model.Habit): Long {
        val id = if (habit.id == 0L) (habits.keys.maxOrNull() ?: 0) + 1 else habit.id
        habits[id] = habit.copy(id = id); return id
    }
    override suspend fun delete(id: Long) { habits.remove(id); logs.value = logs.value - id }
    override suspend fun setDone(habitId: Long, date: LocalDate, done: Boolean) {
        val cur = logs.value[habitId].orEmpty()
        logs.value = logs.value + (habitId to if (done) cur + date else cur - date)
    }
    override suspend fun logsFor(habitId: Long) = logs.value[habitId].orEmpty()
}
