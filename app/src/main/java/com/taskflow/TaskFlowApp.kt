package com.taskflow

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.notifications.Notifier
import com.taskflow.notifications.ReminderSync
import com.taskflow.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TaskFlowApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var categories: CategoryRepository
    @Inject lateinit var tasks: TaskRepository
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var reminderSync: ReminderSync
    @Inject lateinit var widgets: WidgetUpdater

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        appScope.launch {
            categories.seedDefaultsIfEmpty()
            reminderSync.schedulePeriodicWork()
        }
        // Keep the home-screen widget in sync with any data change while the process is alive.
        appScope.launch {
            tasks.observeAll().drop(1).debounce(400).collect { widgets.updateAll() }
        }
    }
}
