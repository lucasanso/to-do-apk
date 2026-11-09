package com.taskflow.domain.engine

import com.taskflow.domain.model.Task
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Deadline rules shared by filters, dashboard, notifications and the priority engine. */
object DueRules {
    fun isOverdue(task: Task, now: LocalDateTime): Boolean {
        if (!task.status.isOpen) return false
        val due = task.dueDate ?: return false
        val today = now.toLocalDate()
        if (due.isBefore(today)) return true
        if (due == today && task.dueTime != null) return task.dueTime.isBefore(now.toLocalTime())
        return false
    }

    fun isDueOn(task: Task, date: LocalDate): Boolean = task.dueDate == date

    fun daysUntilDue(task: Task, today: LocalDate): Long? = task.dueDate?.let { ChronoUnit.DAYS.between(today, it) }

    fun daysOverdue(task: Task, today: LocalDate): Long =
        task.dueDate?.let { ChronoUnit.DAYS.between(it, today).coerceAtLeast(0) } ?: 0

    /** Days the current deadline slipped from the first deadline. */
    fun slipDays(task: Task): Long {
        val o = task.originalDueDate ?: return 0
        val d = task.dueDate ?: return 0
        return ChronoUnit.DAYS.between(o, d).coerceAtLeast(0)
    }

    fun isThisWeek(date: LocalDate, today: LocalDate): Boolean {
        val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return !date.isBefore(start) && !date.isAfter(start.plusDays(6))
    }
}
