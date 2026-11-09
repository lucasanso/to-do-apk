package com.taskflow.domain

import com.taskflow.NOW
import com.taskflow.TODAY
import com.taskflow.domain.engine.DueRules
import com.taskflow.domain.engine.ProductivityCalculator
import com.taskflow.domain.engine.SmartFilter
import com.taskflow.domain.engine.TaskFilters
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.TaskStatus
import com.taskflow.plan
import com.taskflow.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneOffset

class ProductivityAndRulesTest {
    private fun doneAt(id: Long, daysAgo: Long, categoryId: Long? = null) =
        task(id).copy(status = TaskStatus.DONE, categoryId = categoryId, completedAt = NOW.minusDays(daysAgo).toInstant(ZoneOffset.UTC))

    @Test fun `overdue rules respect date and time`() {
        assertTrue(DueRules.isOverdue(task(1, due = TODAY.minusDays(1)), NOW))
        assertFalse(DueRules.isOverdue(task(2, due = TODAY), NOW))
        assertTrue(DueRules.isOverdue(task(3, due = TODAY).copy(dueTime = LocalTime.of(9, 0)), NOW))
        assertFalse(DueRules.isOverdue(task(4, due = TODAY).copy(dueTime = LocalTime.of(11, 0)), NOW))
        assertFalse(DueRules.isOverdue(task(5, due = TODAY.minusDays(3)).copy(status = TaskStatus.DONE), NOW))
        assertEquals(3, DueRules.daysOverdue(task(6, due = TODAY.minusDays(3)), TODAY))
    }

    @Test fun `this week is monday to sunday`() {
        // 2026-11-09 is a Monday
        assertTrue(DueRules.isThisWeek(TODAY.plusDays(6), TODAY))
        assertFalse(DueRules.isThisWeek(TODAY.plusDays(7), TODAY))
        assertFalse(DueRules.isThisWeek(TODAY.minusDays(1), TODAY))
    }

    @Test fun `smart filters`() {
        val tasks = listOf(
            task(1, due = TODAY), task(2, due = TODAY.plusDays(1)), task(3, due = TODAY.minusDays(2)),
            task(4), task(5, priority = Priority.CRITICAL), doneAt(6, 0), task(7).copy(status = TaskStatus.ARCHIVED),
        )
        fun ids(f: SmartFilter) = TaskFilters.apply(tasks, f, NOW).map { it.id }.toSet()
        assertEquals(setOf(1L), ids(SmartFilter.TODAY))
        assertEquals(setOf(2L), ids(SmartFilter.TOMORROW))
        assertEquals(setOf(3L), ids(SmartFilter.OVERDUE))
        assertEquals(setOf(4L, 5L), ids(SmartFilter.NO_DATE))
        assertEquals(setOf(5L), ids(SmartFilter.HIGH_PRIORITY))
        assertEquals(setOf(6L), ids(SmartFilter.DONE))
        assertEquals(setOf(7L), ids(SmartFilter.ARCHIVED))
        assertEquals(setOf(1L, 2L), ids(SmartFilter.THIS_WEEK))
    }

    @Test fun `search is accent insensitive and covers category and tags`() {
        val cats = mapOf(1L to Category(1, "Finanças", 0, "money"))
        val tasks = listOf(
            task(1, "Revisão de cálculo"), task(2, "Pagar boleto").copy(categoryId = 1),
            task(3, "Pipeline").copy(tags = listOf("airflow")), task(4, "Outra").copy(description = "consultar SQL"),
        )
        assertEquals(listOf(1L), TaskFilters.search(tasks, "revisao calculo", cats).map { it.id })
        assertEquals(listOf(2L), TaskFilters.search(tasks, "financas", cats).map { it.id })
        assertEquals(listOf(3L), TaskFilters.search(tasks, "#airflow", cats).map { it.id })
        assertEquals(listOf(4L), TaskFilters.search(tasks, "sql", cats).map { it.id })
    }

    @Test fun `report counts completions per day and week`() {
        val tasks = listOf(doneAt(1, 0), doneAt(2, 0), doneAt(3, 1), doneAt(4, 20), task(5, due = TODAY))
        val r = ProductivityCalculator.report(tasks, emptyList(), emptyList(), TODAY, ZoneOffset.UTC)
        assertEquals(14, r.completedPerDay.size)
        assertEquals(2, r.completedPerDay.last().count)
        assertEquals(1, r.completedPerDay[12].count)
        assertEquals(8, r.completedPerWeek.size)
        assertEquals(2, r.completedPerWeek.last().count) // week starting Monday (today)
        assertEquals(4, r.completedPerWeek.sumOf { it.count })
    }

    @Test fun `completion rate uses tasks due or created in period`() {
        val tasks = listOf(doneAt(1, 0), doneAt(2, 1), task(3, due = TODAY), task(4, due = TODAY.plusDays(30)))
        val r = ProductivityCalculator.report(tasks, emptyList(), emptyList(), TODAY, ZoneOffset.UTC)
        // tasks 1,2 (created today) done; 3 open in period; 4 outside the period
        assertEquals(2.0 / 3, r.completionRate, 1e-9)
    }

    @Test fun `category volume and postponed list`() {
        val cats = listOf(Category(1, "A", 0, "x"), Category(2, "B", 0, "x"))
        val tasks = listOf(doneAt(1, 0, 1), task(2).copy(categoryId = 1), task(3).copy(categoryId = 2, postponeCount = 5), task(4, postpone = 1))
        val r = ProductivityCalculator.report(tasks, cats, emptyList(), TODAY, ZoneOffset.UTC)
        val a = r.categories.first { it.category?.id == 1L }
        assertEquals(2, a.total); assertEquals(1, a.done); assertEquals(0.5, a.rate, 1e-9)
        assertEquals(listOf(3L), r.mostPostponed.map { it.id })
    }

    @Test fun `plan vs done compares planned tasks with completions`() {
        val tasks = listOf(doneAt(1, 0), task(2))
        val r = ProductivityCalculator.report(tasks, emptyList(), plan(TODAY, 1, 2), TODAY, ZoneOffset.UTC)
        val today = r.planVsDone.last()
        assertEquals(2, today.planned); assertEquals(1, today.done)
    }

    @Test fun `daily progress counts done today against due, overdue and planned`() {
        val tasks = listOf(doneAt(1, 0), task(2, due = TODAY), task(3, due = TODAY.minusDays(1)), task(4), task(5, due = TODAY.plusDays(2)))
        val (done, total) = ProductivityCalculator.dailyProgress(tasks, setOf(4L), TODAY, ZoneOffset.UTC)
        assertEquals(1, done); assertEquals(4, total)
    }
}
