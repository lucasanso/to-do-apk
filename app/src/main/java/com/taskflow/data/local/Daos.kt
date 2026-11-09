package com.taskflow.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Transaction @Query("SELECT * FROM tasks ORDER BY id")
    fun observeAll(): Flow<List<TaskWithRelations>>

    @Transaction @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: Long): Flow<TaskWithRelations?>

    @Transaction @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskWithRelations?

    @Transaction @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun getAll(): List<TaskWithRelations>

    @Insert suspend fun insert(task: TaskEntity): Long
    @Update suspend fun update(task: TaskEntity)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT * FROM subtasks WHERE taskId = :taskId") suspend fun subtasksOf(taskId: Long): List<SubtaskEntity>
    @Upsert suspend fun upsertSubtasks(items: List<SubtaskEntity>)
    @Query("DELETE FROM subtasks WHERE taskId = :taskId AND id NOT IN (:keep)") suspend fun deleteSubtasksExcept(taskId: Long, keep: List<Long>)

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun tagByName(name: String): TagEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(tag: TagEntity): Long
    @Query("DELETE FROM task_tags WHERE taskId = :taskId") suspend fun clearTags(taskId: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTaskTags(refs: List<TaskTagCrossRef>)
    @Query("SELECT t.name FROM tags t WHERE EXISTS (SELECT 1 FROM task_tags x WHERE x.tagId = t.id) ORDER BY t.name") suspend fun usedTagNames(): List<String>
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT tagId FROM task_tags)") suspend fun pruneTags()

    @Query("DELETE FROM task_dependencies WHERE taskId = :taskId") suspend fun clearDependencies(taskId: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDependencies(deps: List<TaskDependencyEntity>)
    @Query("SELECT id FROM tasks") suspend fun allIds(): List<Long>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY position, name") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories ORDER BY position, name") suspend fun getAll(): List<CategoryEntity>
    @Insert suspend fun insert(c: CategoryEntity): Long
    @Update suspend fun update(c: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM categories") suspend fun count(): Int
    @Insert suspend fun insertAll(list: List<CategoryEntity>)
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY archived, id") fun observeAll(): Flow<List<HabitEntity>>
    @Query("SELECT * FROM habits ORDER BY id") suspend fun getAll(): List<HabitEntity>
    @Query("SELECT * FROM habits WHERE id = :id") suspend fun get(id: Long): HabitEntity?
    @Insert suspend fun insert(h: HabitEntity): Long
    @Update suspend fun update(h: HabitEntity)
    @Query("DELETE FROM habits WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM habit_logs") fun observeLogs(): Flow<List<HabitLogEntity>>
    @Query("SELECT * FROM habit_logs") suspend fun allLogs(): List<HabitLogEntity>
    @Query("SELECT date FROM habit_logs WHERE habitId = :habitId") suspend fun logDates(habitId: Long): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLog(log: HabitLogEntity)
    @Delete suspend fun deleteLog(log: HabitLogEntity)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM day_plan ORDER BY date, position") fun observeAll(): Flow<List<DayPlanEntity>>
    @Query("SELECT * FROM day_plan ORDER BY date, position") suspend fun getAll(): List<DayPlanEntity>
    @Query("SELECT * FROM day_plan WHERE date = :date ORDER BY position") fun observeDay(date: Long): Flow<List<DayPlanEntity>>
    @Query("SELECT COALESCE(MAX(position), -1) FROM day_plan WHERE date = :date") suspend fun maxPosition(date: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: DayPlanEntity)
    @Query("DELETE FROM day_plan WHERE date = :date AND taskId = :taskId") suspend fun delete(date: Long, taskId: Long)
    @Query("UPDATE day_plan SET position = :position WHERE date = :date AND taskId = :taskId") suspend fun setPosition(date: Long, taskId: Long, position: Int)
    @Query("SELECT availableMinutes FROM day_meta WHERE date = :date") fun observeAvailable(date: Long): Flow<Int?>
    @Upsert suspend fun upsertMeta(meta: DayMetaEntity)
    @Query("SELECT * FROM day_meta") suspend fun allMeta(): List<DayMetaEntity>
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC, id DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<HistoryEntity>>
    @Query("SELECT * FROM history WHERE taskId = :taskId ORDER BY timestamp DESC, id DESC") fun observeForTask(taskId: Long): Flow<List<HistoryEntity>>
    @Query("SELECT * FROM history ORDER BY id") suspend fun getAll(): List<HistoryEntity>
    @Insert suspend fun insert(e: HistoryEntity): Long
}

/** Bulk operations used by backup restore. */
@Dao
interface BackupDao {
    @Query("DELETE FROM tasks") suspend fun clearTasks()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM tags") suspend fun clearTags()
    @Query("DELETE FROM habits") suspend fun clearHabits()
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM day_meta") suspend fun clearMeta()
    @Query("SELECT * FROM subtasks") suspend fun allSubtasks(): List<SubtaskEntity>
    @Query("SELECT * FROM tasks") suspend fun allTasks(): List<TaskEntity>
    @Query("SELECT * FROM tags") suspend fun allTags(): List<TagEntity>
    @Query("SELECT * FROM task_tags") suspend fun allTaskTags(): List<TaskTagCrossRef>
    @Query("SELECT * FROM task_dependencies") suspend fun allDependencies(): List<TaskDependencyEntity>
    @Insert suspend fun insertCategories(l: List<CategoryEntity>)
    @Insert suspend fun insertTasks(l: List<TaskEntity>)
    @Insert suspend fun insertSubtasks(l: List<SubtaskEntity>)
    @Insert suspend fun insertTags(l: List<TagEntity>)
    @Insert suspend fun insertTaskTags(l: List<TaskTagCrossRef>)
    @Insert suspend fun insertDependencies(l: List<TaskDependencyEntity>)
    @Insert suspend fun insertHabits(l: List<HabitEntity>)
    @Insert suspend fun insertHabitLogs(l: List<HabitLogEntity>)
    @Insert suspend fun insertPlan(l: List<DayPlanEntity>)
    @Insert suspend fun insertMeta(l: List<DayMetaEntity>)
    @Insert suspend fun insertHistory(l: List<HistoryEntity>)
}
