package com.taskflow.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.Task
import com.taskflow.domain.repository.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses AlarmManager so reminders fire at the right minute even in Doze.
 * Exact alarms are used when the user granted "Alarms & reminders"; otherwise an
 * inexact while-idle alarm is used (may be delayed by a few minutes).
 */
@Singleton
class AlarmReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeProvider,
) : ReminderScheduler {
    private val am = context.getSystemService(AlarmManager::class.java)

    companion object {
        /** Used for tasks without a time: reminder anchored at 09:00 of the due date. */
        val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
        private const val HABIT_OFFSET = 1_500_000_000L
    }

    fun triggerAtMillis(task: Task): Long? {
        val date = task.dueDate ?: return null
        val minutes = task.reminderMinutesBefore ?: return null
        val at = date.atTime(task.dueTime ?: DEFAULT_TIME).minusMinutes(minutes.toLong()).atZone(time.zone()).toInstant().toEpochMilli()
        return at.takeIf { it > time.now().toEpochMilli() }
    }

    override fun scheduleTask(task: Task) {
        val at = triggerAtMillis(task) ?: return cancelTask(task.id)
        set(at, pending(ReminderReceiver.TYPE_TASK, task.id, task.id))
    }

    override fun cancelTask(taskId: Long) {
        am.cancel(pending(ReminderReceiver.TYPE_TASK, taskId, taskId))
    }

    override fun scheduleHabit(habit: Habit) {
        val t = habit.reminderTime ?: return cancelHabit(habit.id)
        if (habit.archived) return cancelHabit(habit.id)
        val now = time.nowDateTime()
        var date = if (RecurrenceCalculator.occursOn(habit.rule, habit.startDate, now.toLocalDate()) && t.isAfter(now.toLocalTime()))
            now.toLocalDate() else RecurrenceCalculator.nextOccurrence(habit.rule, habit.startDate, now.toLocalDate())
        if (date.isBefore(habit.startDate)) date = habit.startDate
        val at = date.atTime(t).atZone(time.zone()).toInstant().toEpochMilli()
        set(at, pending(ReminderReceiver.TYPE_HABIT, habit.id, habit.id + HABIT_OFFSET))
    }

    override fun cancelHabit(habitId: Long) {
        am.cancel(pending(ReminderReceiver.TYPE_HABIT, habitId, habitId + HABIT_OFFSET))
    }

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun set(at: Long, pi: PendingIntent) {
        if (canScheduleExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun pending(type: String, id: Long, code: Long): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction("com.taskflow.REMINDER.$type")
            .putExtra(ReminderReceiver.EXTRA_TYPE, type).putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, (code % Int.MAX_VALUE).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
