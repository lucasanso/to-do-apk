package com.taskflow.domain.repository

import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.Task

/** Domain-facing port for scheduling local reminders (implemented in the notifications layer). */
interface ReminderScheduler {
    fun scheduleTask(task: Task)
    fun cancelTask(taskId: Long)
    fun scheduleHabit(habit: Habit)
    fun cancelHabit(habitId: Long)
}
