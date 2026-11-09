package com.taskflow.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.taskflow.core.TimeProvider
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.domain.engine.DueRules
import com.taskflow.domain.engine.PriorityEngine
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.repository.TaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
    private val scheduler: AlarmReminderScheduler,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) {
    suspend fun rescheduleAll() {
        tasks.getAll().filter { it.status.isOpen && it.reminderMinutesBefore != null }.forEach(scheduler::scheduleTask)
        habits.getAll().filter { !it.archived && it.reminderTime != null }.forEach(scheduler::scheduleHabit)
        schedulePeriodicWork()
    }

    suspend fun schedulePeriodicWork() {
        val s = settings.current()
        val wm = WorkManager.getInstance(context)
        if (s.dailySummary) {
            val target = LocalTime.of(s.dailySummaryTime / 60, s.dailySummaryTime % 60)
            val now = time.nowDateTime()
            var next = now.toLocalDate().atTime(target)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMinutes()
            val req = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(DailySummaryWorker.NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, req)
        } else wm.cancelUniqueWork(DailySummaryWorker.NAME)

        if (s.overdueAlerts) {
            val req = PeriodicWorkRequestBuilder<OverdueCheckWorker>(4, TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(OverdueCheckWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } else wm.cancelUniqueWork(OverdueCheckWorker.NAME)
    }
}

@HiltWorker
class DailySummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
    private val notifier: Notifier,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) : CoroutineWorker(context, params) {
    companion object { const val NAME = "daily_summary" }

    override suspend fun doWork(): Result {
        if (!settings.current().dailySummary) return Result.success()
        val now = time.nowDateTime(); val today = now.toLocalDate()
        val all = tasks.getAll()
        val dueToday = all.count { it.status.isOpen && it.dueDate == today }
        val overdue = all.count { DueRules.isOverdue(it, now) }
        val habitsToday = habits.getAll().count { !it.archived && RecurrenceCalculator.occursOn(it.rule, it.startDate, today) }
        val top = PriorityEngine.focusToday(all, emptySet(), now, 3)
        if (dueToday + overdue + habitsToday + top.size == 0) return Result.success()
        val lines = buildList {
            add("$dueToday para hoje · $overdue atrasada(s) · $habitsToday hábito(s)")
            top.forEach { (t, _) -> add("• ${t.title}") }
        }
        notifier.summary("Seu dia", lines)
        return Result.success()
    }
}

@HiltWorker
class OverdueCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tasks: TaskRepository,
    private val notifier: Notifier,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) : CoroutineWorker(context, params) {
    companion object { const val NAME = "overdue_check"; private const val PREF = "overdue_state" }

    override suspend fun doWork(): Result {
        if (!settings.current().overdueAlerts) return Result.success()
        val now = time.nowDateTime()
        val overdue = tasks.getAll().filter { DueRules.isOverdue(it, now) }
        val prefs = applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val signature = "${now.toLocalDate()}:${overdue.map { it.id }.sorted().joinToString(",")}"
        // Notify at most once per day per distinct set of overdue tasks.
        if (overdue.isNotEmpty() && prefs.getString("last", null) != signature) {
            notifier.overdue(overdue.size, overdue.map { it.title })
            prefs.edit().putString("last", signature).apply()
        }
        return Result.success()
    }
}
