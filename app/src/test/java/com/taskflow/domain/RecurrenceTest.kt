package com.taskflow.domain

import com.taskflow.domain.engine.HabitStreaks
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrenceTest {
    private val anchor = LocalDate.of(2026, 1, 31) // Saturday

    @Test fun `daily with interval`() {
        val r = RecurrenceRule(RecurrenceType.DAILY, 2)
        assertTrue(RecurrenceCalculator.occursOn(r, anchor, anchor.plusDays(4)))
        assertFalse(RecurrenceCalculator.occursOn(r, anchor, anchor.plusDays(3)))
        assertEquals(anchor.plusDays(2), RecurrenceCalculator.nextOccurrence(r, anchor, anchor))
        assertEquals(anchor.plusDays(6), RecurrenceCalculator.nextOccurrence(r, anchor, anchor.plusDays(5)))
    }

    @Test fun `specific weekdays`() {
        val r = RecurrenceRule(RecurrenceType.WEEKDAYS, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        assertEquals(LocalDate.of(2026, 2, 2), RecurrenceCalculator.nextOccurrence(r, anchor, anchor))
        assertEquals(LocalDate.of(2026, 2, 4), RecurrenceCalculator.nextOccurrence(r, anchor, LocalDate.of(2026, 2, 2)))
        assertFalse(RecurrenceCalculator.occursOn(r, anchor, LocalDate.of(2026, 1, 26))) // before anchor
    }

    @Test fun `weekly every two weeks`() {
        val r = RecurrenceRule(RecurrenceType.WEEKLY, 2)
        assertEquals(anchor.plusDays(14), RecurrenceCalculator.nextOccurrence(r, anchor, anchor))
        assertFalse(RecurrenceCalculator.occursOn(r, anchor, anchor.plusDays(7)))
        assertTrue(RecurrenceCalculator.occursOn(r, anchor, anchor.plusDays(28)))
    }

    @Test fun `monthly clamps to month length and returns to anchor day`() {
        val r = RecurrenceRule(RecurrenceType.MONTHLY, 1)
        val feb = RecurrenceCalculator.nextOccurrence(r, anchor, anchor)
        assertEquals(LocalDate.of(2026, 2, 28), feb)
        assertEquals(LocalDate.of(2026, 3, 31), RecurrenceCalculator.nextOccurrence(r, anchor, feb))
        assertTrue(RecurrenceCalculator.occursOn(r, anchor, LocalDate.of(2026, 4, 30)))
        assertFalse(RecurrenceCalculator.occursOn(r, anchor, LocalDate.of(2026, 4, 29)))
    }

    @Test fun `custom interval and range listing`() {
        val r = RecurrenceRule(RecurrenceType.CUSTOM, 3)
        val list = RecurrenceCalculator.occurrencesBetween(r, anchor, anchor.plusDays(1), anchor.plusDays(10))
        assertEquals(listOf(anchor.plusDays(3), anchor.plusDays(6), anchor.plusDays(9)), list)
    }

    @Test fun `encode and decode round trip, invalid input rejected`() {
        val r = RecurrenceRule(RecurrenceType.WEEKDAYS, 1, setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY))
        assertEquals(r, RecurrenceRule.decode(r.encode()))
        assertNull(RecurrenceRule.decode("WEEKDAYS;1;"))
        assertNull(RecurrenceRule.decode("NOPE;1;"))
        assertNull(RecurrenceRule.decode("DAILY;0;"))
    }

    @Test fun `streak counts consecutive scheduled completions and tolerates pending today`() {
        val r = RecurrenceRule(RecurrenceType.DAILY)
        val today = anchor.plusDays(10)
        val done = (7..9).map { anchor.plusDays(it.toLong()) }.toSet()
        assertEquals(3, HabitStreaks.current(r, anchor, done, today))
        assertEquals(4, HabitStreaks.current(r, anchor, done + today, today))
        assertEquals(0, HabitStreaks.current(r, anchor, done - anchor.plusDays(9), today))
    }

    @Test fun `streak skips non scheduled days`() {
        val r = RecurrenceRule(RecurrenceType.WEEKDAYS, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        val a = LocalDate.of(2026, 3, 2) // Monday
        val done = setOf(a, a.plusDays(4), a.plusDays(7)) // Mon, Fri, Mon
        assertEquals(3, HabitStreaks.current(r, a, done, a.plusDays(9))) // Wednesday
        assertEquals(3, HabitStreaks.best(r, a, done, a.plusDays(9)))
    }

    @Test fun `completion rate over window`() {
        val r = RecurrenceRule(RecurrenceType.DAILY)
        val today = anchor.plusDays(9)
        val done = (0..4).map { anchor.plusDays(it.toLong()) }.toSet()
        // 9 past occurrences counted (today pending excluded): 5/9
        assertEquals(5.0 / 9, HabitStreaks.completionRate(r, anchor, done, today, 30), 1e-9)
    }
}
