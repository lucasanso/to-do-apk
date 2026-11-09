package com.taskflow.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskflow.core.TimeProvider
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.PostponeTaskUseCase
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import com.taskflow.domain.usecase.ToggleHabitUseCase
import com.taskflow.widget.WidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try { block() } finally { pending.finish() }
    }
}

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val TYPE_TASK = "task"
        const val TYPE_HABIT = "habit"
    }

    @Inject lateinit var tasks: TaskRepository
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var scheduler: AlarmReminderScheduler
    @Inject lateinit var time: TimeProvider

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        runAsync {
            val s = settings.current()
            when (intent.getStringExtra(EXTRA_TYPE)) {
                TYPE_TASK -> {
                    val task = tasks.get(id) ?: return@runAsync
                    if (!task.status.isOpen || !s.taskReminders) return@runAsync
                    val text = buildString {
                        append(if (task.reminderMinutesBefore == 0 || task.reminderMinutesBefore == null) "Agora" else "Em ${task.reminderMinutesBefore} min")
                        task.dueTime?.let { append(" · ").append(it.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                    }
                    notifier.taskReminder(task, text)
                }
                TYPE_HABIT -> {
                    val habit = habits.get(id) ?: return@runAsync
                    val today = time.today()
                    if (s.habitReminders && !habit.archived && RecurrenceCalculator.occursOn(habit.rule, habit.startDate, today) &&
                        today !in habits.logsFor(id)) notifier.habitReminder(habit)
                    scheduler.scheduleHabit(habit) // next occurrence
                }
            }
        }
    }
}

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_ID = "id"
        const val ACTION_COMPLETE = "com.taskflow.action.COMPLETE"
        const val ACTION_POSTPONE = "com.taskflow.action.POSTPONE"
        const val ACTION_HABIT_DONE = "com.taskflow.action.HABIT_DONE"
    }

    @Inject lateinit var setDone: SetTaskDoneUseCase
    @Inject lateinit var postpone: PostponeTaskUseCase
    @Inject lateinit var toggleHabit: ToggleHabitUseCase
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var time: TimeProvider
    @Inject lateinit var widgets: WidgetUpdater

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        runAsync {
            when (intent.action) {
                ACTION_COMPLETE -> { setDone(id, true); notifier.cancel(Notifier.taskNotificationId(id)) }
                ACTION_POSTPONE -> { postpone(id); notifier.cancel(Notifier.taskNotificationId(id)) }
                ACTION_HABIT_DONE -> {
                    if (time.today() !in habits.logsFor(id)) toggleHabit(id)
                    notifier.cancel(Notifier.habitNotificationId(id))
                }
            }
            widgets.updateAll()
        }
    }
}

/** Alarms are cleared on reboot, app update and time-zone change; reschedule everything. */
@AndroidEntryPoint
class RescheduleReceiver : BroadcastReceiver() {
    @Inject lateinit var sync: ReminderSync

    override fun onReceive(context: Context, intent: Intent) {
        runAsync { sync.rescheduleAll() }
    }
}
