package com.taskflow.data.repository

import androidx.room.withTransaction
import com.taskflow.data.local.CategoryEntity
import com.taskflow.data.local.DayMetaEntity
import com.taskflow.data.local.DayPlanEntity
import com.taskflow.data.local.HabitLogEntity
import com.taskflow.data.local.SubtaskEntity
import com.taskflow.data.local.TagEntity
import com.taskflow.data.local.TaskDependencyEntity
import com.taskflow.data.local.TaskFlowDatabase
import com.taskflow.data.local.TaskTagCrossRef
import com.taskflow.data.local.toDomain
import com.taskflow.data.local.toEntity
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.DayPlanItem
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.HistoryEntry
import com.taskflow.domain.model.Task
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.repository.HistoryRepository
import com.taskflow.domain.repository.PlanRepository
import com.taskflow.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepositoryImpl @Inject constructor(private val db: TaskFlowDatabase) : TaskRepository {
    private val dao = db.taskDao()

    override fun observeAll(): Flow<List<Task>> = dao.observeAll().map { l -> l.map { it.toDomain() } }.distinctUntilChanged()
    override fun observe(id: Long): Flow<Task?> = dao.observe(id).map { it?.toDomain() }.distinctUntilChanged()
    override suspend fun get(id: Long): Task? = dao.get(id)?.toDomain()
    override suspend fun getAll(): List<Task> = dao.getAll().map { it.toDomain() }
    override suspend fun allTags(): List<String> = dao.usedTagNames()

    override suspend fun upsert(task: Task): Long = db.withTransaction {
        val entity = task.toEntity()
        // A non-zero id that no longer exists (undo of a deletion) is re-inserted with that same id.
        val id = when {
            task.id == 0L -> dao.insert(entity.copy(id = 0))
            dao.get(task.id) == null -> dao.insert(entity)
            else -> { dao.update(entity); task.id }
        }

        // Subtasks: keep ids of existing rows, insert new ones, delete removed.
        val existingIds = dao.subtasksOf(id).map { it.id }.toSet()
        val rows = task.subtasks.mapIndexed { i, s ->
            SubtaskEntity(id = if (s.id in existingIds) s.id else 0, taskId = id, title = s.title, done = s.done, position = i)
        }
        dao.deleteSubtasksExcept(id, rows.map { it.id }.filter { it != 0L }.ifEmpty { listOf(-1L) })
        if (rows.isNotEmpty()) dao.upsertSubtasks(rows)

        // Tags
        dao.clearTags(id)
        val tagIds = task.tags.map { name ->
            dao.tagByName(name)?.id ?: dao.insertTag(TagEntity(name = name)).let { if (it == -1L) dao.tagByName(name)!!.id else it }
        }
        if (tagIds.isNotEmpty()) dao.insertTaskTags(tagIds.map { TaskTagCrossRef(id, it) })
        dao.pruneTags()

        // Dependencies (only to existing tasks, never self)
        dao.clearDependencies(id)
        val valid = dao.allIds().toSet()
        val deps = task.dependsOn.filter { it != id && it in valid }.distinct()
        if (deps.isNotEmpty()) dao.insertDependencies(deps.map { TaskDependencyEntity(id, it) })
        id
    }

    override suspend fun delete(id: Long) = db.withTransaction {
        dao.delete(id)
        dao.pruneTags()
    }
}

@Singleton
class CategoryRepositoryImpl @Inject constructor(private val db: TaskFlowDatabase) : CategoryRepository {
    private val dao = db.categoryDao()
    override fun observeAll(): Flow<List<Category>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override suspend fun getAll(): List<Category> = dao.getAll().map { it.toDomain() }
    override suspend fun upsert(category: Category): Long =
        if (category.id == 0L) dao.insert(category.toEntity().copy(position = dao.count())) else { dao.update(category.toEntity()); category.id }
    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun seedDefaultsIfEmpty() {
        if (dao.count() > 0) return
        dao.insertAll(DEFAULT_CATEGORIES.mapIndexed { i, (name, color, icon) -> CategoryEntity(0, name, color, icon, i) })
    }

    companion object {
        val DEFAULT_CATEGORIES = listOf(
            Triple("Universidade", 0xFF5B6CF0, "school"),
            Triple("Trabalho", 0xFF2F80ED, "work"),
            Triple("Engenharia de Dados", 0xFF0EA5A4, "data"),
            Triple("Projetos pessoais", 0xFF8B5CF6, "rocket"),
            Triple("Finanças", 0xFF16A34A, "money"),
            Triple("Saúde e exercícios", 0xFFE5484D, "fitness"),
            Triple("Estudos", 0xFFF59E0B, "book"),
            Triple("Casa", 0xFFB7791F, "home"),
            Triple("Outros", 0xFF71717A, "dots"),
        )
    }
}

@Singleton
class HabitRepositoryImpl @Inject constructor(db: TaskFlowDatabase) : HabitRepository {
    private val dao = db.habitDao()
    override fun observeAll(): Flow<List<Habit>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override fun observeLogs(): Flow<Map<Long, Set<LocalDate>>> = dao.observeLogs().map { logs ->
        logs.groupBy({ it.habitId }, { LocalDate.ofEpochDay(it.date) }).mapValues { it.value.toSet() }
    }
    override suspend fun get(id: Long): Habit? = dao.get(id)?.toDomain()
    override suspend fun getAll(): List<Habit> = dao.getAll().map { it.toDomain() }
    override suspend fun upsert(habit: Habit): Long = when {
        habit.id == 0L -> dao.insert(habit.toEntity())
        dao.get(habit.id) == null -> dao.insert(habit.toEntity()) // restoring a deleted habit keeps its id
        else -> { dao.update(habit.toEntity()); habit.id }
    }
    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun setDone(habitId: Long, date: LocalDate, done: Boolean) {
        val log = HabitLogEntity(habitId, date.toEpochDay())
        if (done) dao.insertLog(log) else dao.deleteLog(log)
    }
    override suspend fun logsFor(habitId: Long): Set<LocalDate> = dao.logDates(habitId).map(LocalDate::ofEpochDay).toSet()
}

@Singleton
class PlanRepositoryImpl @Inject constructor(private val db: TaskFlowDatabase) : PlanRepository {
    private val dao = db.planDao()
    override fun observeAll(): Flow<List<DayPlanItem>> = dao.observeAll().map { l -> l.map { DayPlanItem(LocalDate.ofEpochDay(it.date), it.taskId, it.position) } }
    override fun observeDay(date: LocalDate): Flow<List<DayPlanItem>> =
        dao.observeDay(date.toEpochDay()).map { l -> l.map { DayPlanItem(date, it.taskId, it.position) } }
    override fun observeAvailableMinutes(date: LocalDate): Flow<Int?> = dao.observeAvailable(date.toEpochDay())
    override suspend fun daysFor(taskId: Long): List<LocalDate> = dao.datesFor(taskId).map(LocalDate::ofEpochDay)
    override suspend fun add(date: LocalDate, taskId: Long) = db.withTransaction {
        dao.insert(DayPlanEntity(date.toEpochDay(), taskId, dao.maxPosition(date.toEpochDay()) + 1))
    }
    override suspend fun remove(date: LocalDate, taskId: Long) = dao.delete(date.toEpochDay(), taskId)
    override suspend fun reorder(date: LocalDate, orderedTaskIds: List<Long>) = db.withTransaction {
        orderedTaskIds.forEachIndexed { i, id -> dao.setPosition(date.toEpochDay(), id, i) }
    }
    override suspend fun setAvailableMinutes(date: LocalDate, minutes: Int) = dao.upsertMeta(DayMetaEntity(date.toEpochDay(), minutes))
}

@Singleton
class HistoryRepositoryImpl @Inject constructor(db: TaskFlowDatabase) : HistoryRepository {
    private val dao = db.historyDao()
    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = dao.observeRecent(limit).map { l -> l.map { it.toDomain() } }
    override fun observeForTask(taskId: Long): Flow<List<HistoryEntry>> = dao.observeForTask(taskId).map { l -> l.map { it.toDomain() } }
    override suspend fun add(entry: HistoryEntry) { dao.insert(entry.toEntity()) }
}
