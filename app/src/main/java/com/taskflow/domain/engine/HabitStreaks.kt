package com.taskflow.domain.engine

import com.taskflow.domain.model.RecurrenceRule
import java.time.LocalDate

object HabitStreaks {
    /**
     * Current streak = consecutive scheduled occurrences completed, walking back from today.
     * Today's occurrence only counts if done; leaving it pending does not break the streak.
     */
    fun current(rule: RecurrenceRule, anchor: LocalDate, done: Set<LocalDate>, today: LocalDate): Int {
        var streak = 0
        var d: LocalDate? = RecurrenceCalculator.previousOrSame(rule, anchor, today)
        if (d == today && today !in done) d = RecurrenceCalculator.previousOrSame(rule, anchor, today.minusDays(1))
        while (d != null && d in done) {
            streak++
            d = RecurrenceCalculator.previousOrSame(rule, anchor, d.minusDays(1))
        }
        return streak
    }

    fun best(rule: RecurrenceRule, anchor: LocalDate, done: Set<LocalDate>, today: LocalDate): Int {
        if (done.isEmpty()) return 0
        var best = 0; var run = 0
        for (d in RecurrenceCalculator.occurrencesBetween(rule, anchor, anchor, today)) {
            if (d in done) { run++; if (run > best) best = run } else if (d != today) run = 0
        }
        return best
    }

    /** Share of scheduled occurrences in the last [days] days (excluding pending today) that were done. */
    fun completionRate(rule: RecurrenceRule, anchor: LocalDate, done: Set<LocalDate>, today: LocalDate, days: Long = 30): Double {
        val occ = RecurrenceCalculator.occurrencesBetween(rule, anchor, today.minusDays(days - 1), today)
            .filter { it != today || it in done }
        if (occ.isEmpty()) return 0.0
        return occ.count { it in done }.toDouble() / occ.size
    }
}
