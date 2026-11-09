package com.taskflow.di

import android.content.Context
import androidx.room.Room
import com.taskflow.core.SystemTimeProvider
import com.taskflow.core.TimeProvider
import com.taskflow.data.local.TaskFlowDatabase
import com.taskflow.data.repository.CategoryRepositoryImpl
import com.taskflow.data.repository.HabitRepositoryImpl
import com.taskflow.data.repository.HistoryRepositoryImpl
import com.taskflow.data.repository.PlanRepositoryImpl
import com.taskflow.data.repository.TaskRepositoryImpl
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.repository.HistoryRepository
import com.taskflow.domain.repository.PlanRepository
import com.taskflow.domain.repository.ReminderScheduler
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.notifications.AlarmReminderScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): TaskFlowDatabase =
        Room.databaseBuilder(context, TaskFlowDatabase::class.java, TaskFlowDatabase.NAME).build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun time(impl: SystemTimeProvider): TimeProvider
    @Binds abstract fun tasks(impl: TaskRepositoryImpl): TaskRepository
    @Binds abstract fun categories(impl: CategoryRepositoryImpl): CategoryRepository
    @Binds abstract fun habits(impl: HabitRepositoryImpl): HabitRepository
    @Binds abstract fun plan(impl: PlanRepositoryImpl): PlanRepository
    @Binds abstract fun history(impl: HistoryRepositoryImpl): HistoryRepository
    @Binds abstract fun reminders(impl: AlarmReminderScheduler): ReminderScheduler
}
