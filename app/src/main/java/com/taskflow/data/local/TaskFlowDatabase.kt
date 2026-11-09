package com.taskflow.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CategoryEntity::class, TaskEntity::class, SubtaskEntity::class, TagEntity::class, TaskTagCrossRef::class,
        TaskDependencyEntity::class, HabitEntity::class, HabitLogEntity::class, DayPlanEntity::class,
        DayMetaEntity::class, HistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TaskFlowDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao
    abstract fun habitDao(): HabitDao
    abstract fun planDao(): PlanDao
    abstract fun historyDao(): HistoryDao
    abstract fun backupDao(): BackupDao

    companion object { const val NAME = "taskflow.db" }
}
