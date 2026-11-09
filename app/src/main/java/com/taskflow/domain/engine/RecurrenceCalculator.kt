package com.taskflow.domain.engine

import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure recurrence math, used for recurring tasks, habits and the calendar. */
object RecurrenceCalculator {

    /** True if a series anchored at [anchor] has an occurrence on [date]. */
    fun occursOn(rule: RecurrenceRule, anchor: LocalDate, date: LocalDate): Boolean {
        if (date.isBefore(anchor)) return false
        return when (rule.type) {
            RecurrenceType.DAILY, RecurrenceType.CUSTOM ->
                ChronoUnit.DAYS.between(anchor, date) % rule.interval == 0L
            RecurrenceType.WEEKDAYS -> date.dayOfWeek in rule.days
            RecurrenceType.WEEKLY -> {
                val days = ChronoUnit.DAYS.between(anchor, date)
                days % 7 == 0L && (days / 7) % rule.interval == 0L
            }
            RecurrenceType.MONTHLY -> {
                val months = ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), date.withDayOfMonth(1))
                months % rule.interval == 0L && date == monthlyOccurrence(anchor, months)
            }
        }
    }

    /** Next occurrence strictly after [after]. */
    fun nextOccurrence(rule: RecurrenceRule, anchor: LocalDate, after: LocalDate): LocalDate {
        if (after.isBefore(anchor)) return anchor
        return when (rule.type) {
            RecurrenceType.DAILY, RecurrenceType.CUSTOM -> {
                val elapsed = ChronoUnit.DAYS.between(anchor, after)
                anchor.plusDays((elapsed / rule.interval + 1) * rule.interval)
            }
            RecurrenceType.WEEKLY -> {
                val step = 7L * rule.interval
                val elapsed = ChronoUnit.DAYS.between(anchor, after)
                anchor.plusDays((elapsed / step + 1) * step)
            }
            RecurrenceType.WEEKDAYS -> {
                var d = after.plusDays(1)
                while (d.dayOfWeek !in rule.days) d = d.plusDays(1)
                d
            }
            RecurrenceType.MONTHLY -> {
                var months = ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), after.withDayOfMonth(1))
                months -= months % rule.interval
                var candidate = monthlyOccurrence(anchor, months)
                while (!candidate.isAfter(after)) {
                    months += rule.interval
                    candidate = monthlyOccurrence(anchor, months)
                }
                candidate
            }
        }
    }

    /** All occurrences within [from]..[to] inclusive. */
    fun occurrencesBetween(rule: RecurrenceRule, anchor: LocalDate, from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var d = if (from.isAfter(anchor)) from else anchor
        if (!occursOn(rule, anchor, d)) d = nextOccurrence(rule, anchor, d)
        while (!d.isAfter(to)) {
            out += d
            d = nextOccurrence(rule, anchor, d)
        }
        return out
    }

    /** Most recent occurrence on or before [date], or null if none since anchor. */
    fun previousOrSame(rule: RecurrenceRule, anchor: LocalDate, date: LocalDate): LocalDate? {
        var d = date
        var guard = 0
        while (!d.isBefore(anchor) && guard < 800) {
            if (occursOn(rule, anchor, d)) return d
            d = d.minusDays(1); guard++
        }
        return null
    }

    private fun monthlyOccurrence(anchor: LocalDate, monthsFromAnchor: Long): LocalDate {
        val month = anchor.withDayOfMonth(1).plusMonths(monthsFromAnchor)
        return month.withDayOfMonth(minOf(anchor.dayOfMonth, month.lengthOfMonth()))
    }
}
