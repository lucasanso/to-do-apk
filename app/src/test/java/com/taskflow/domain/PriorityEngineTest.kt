package com.taskflow.domain

import com.taskflow.NOW
import com.taskflow.TODAY
import com.taskflow.domain.engine.PriorityEngine
import com.taskflow.domain.engine.Quadrant
import com.taskflow.domain.engine.TaskFlag
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.TaskStatus
import com.taskflow.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityEngineTest {
    private fun score(vararg tasks: com.taskflow.domain.model.Task, id: Long = tasks.first().id) =
        PriorityEngine.score(tasks.first { it.id == id }, tasks.associateBy { it.id }, PriorityEngine.buildDependents(tasks.toList()), NOW)

    @Test fun `higher user priority scores higher when everything else is equal`() {
        val low = score(task(1, priority = Priority.LOW))
        val critical = score(task(2, priority = Priority.CRITICAL))
        assertTrue(critical.score > low.score)
    }

    @Test fun `closer deadline scores higher`() {
        val today = score(task(1, due = TODAY))
        val nextWeek = score(task(2, due = TODAY.plusDays(6)))
        val none = score(task(3))
        assertTrue(today.score > nextWeek.score)
        assertTrue(nextWeek.score > none.score)
    }

    @Test fun `overdue tasks are flagged and get more urgent the later they are`() {
        val oneDay = score(task(1, due = TODAY.minusDays(1)))
        val fiveDays = score(task(2, due = TODAY.minusDays(5)))
        assertTrue(TaskFlag.OVERDUE in oneDay.flags)
        assertTrue(fiveDays.score > oneDay.score)
    }

    @Test fun `urgency and importance are independent quadrants`() {
        assertEquals(Quadrant.DO_NOW, score(task(1, Priority.HIGH.name, Priority.HIGH, TODAY)).quadrant)
        assertEquals(Quadrant.SCHEDULE, score(task(2, priority = Priority.CRITICAL, due = TODAY.plusDays(10))).quadrant)
        assertEquals(Quadrant.QUICK, score(task(3, priority = Priority.LOW, due = TODAY)).quadrant)
        assertEquals(Quadrant.LATER, score(task(4, priority = Priority.MEDIUM)).quadrant)
    }

    @Test fun `quick tasks are flagged as quick wins`() {
        val s = score(task(1, estimate = 10))
        assertTrue(TaskFlag.QUICK_WIN in s.flags)
        assertTrue(s.score > score(task(2, estimate = 90)).score)
    }

    @Test fun `tasks pending for long get a waiting boost`() {
        assertTrue(score(task(1, createdDaysAgo = 15)).score > score(task(2, createdDaysAgo = 0)).score)
    }

    @Test fun `frequently postponed tasks are flagged`() {
        val s = score(task(1, postpone = 4))
        assertTrue(TaskFlag.PROCRASTINATED in s.flags)
        assertFalse(TaskFlag.PROCRASTINATED in score(task(2, postpone = 1)).flags)
    }

    @Test fun `blocked tasks lose score and blockers gain score`() {
        val blocker = task(1)
        val blocked = task(2, dependsOn = listOf(1))
        val sBlocked = score(blocker, blocked, id = 2)
        val sBlocker = score(blocker, blocked, id = 1)
        assertTrue(TaskFlag.BLOCKED in sBlocked.flags)
        assertTrue(TaskFlag.BLOCKING in sBlocker.flags)
        assertTrue(sBlocker.score > sBlocked.score)
    }

    @Test fun `dependency on a completed task does not block`() {
        val done = task(1).copy(status = TaskStatus.DONE)
        val t = task(2, dependsOn = listOf(1))
        assertFalse(TaskFlag.BLOCKED in score(done, t, id = 2).flags)
    }

    @Test fun `slipped deadline adds points`() {
        val slipped = task(1, due = TODAY.plusDays(3)).copy(originalDueDate = TODAY.minusDays(2))
        assertTrue(score(slipped).factors.any { it.label.startsWith("Prazo deslocado") })
    }

    @Test fun `score is clamped and explanation lists top factors`() {
        val s = score(task(1, priority = Priority.CRITICAL, due = TODAY.minusDays(30), createdDaysAgo = 90, estimate = 5, postpone = 10))
        assertTrue(s.score in 0..100)
        assertTrue(s.explanation.isNotBlank())
    }

    @Test fun `rank excludes closed tasks and puts blocked last`() {
        val a = task(1, priority = Priority.CRITICAL, dependsOn = listOf(3))
        val b = task(2, priority = Priority.LOW)
        val c = task(3, priority = Priority.LOW)
        val d = task(4).copy(status = TaskStatus.DONE)
        val ranked = PriorityEngine.rank(listOf(a, b, c, d), NOW).map { it.first.id }
        assertEquals(3, ranked.size)
        assertEquals(1L, ranked.last())
    }

    @Test fun `focus today includes planned and due tasks only`() {
        val due = task(1, due = TODAY)
        val planned = task(2, due = TODAY.plusDays(20))
        val later = task(3, priority = Priority.LOW, due = TODAY.plusDays(20))
        val focus = PriorityEngine.focusToday(listOf(due, planned, later), setOf(2L), NOW).map { it.first.id }
        assertEquals(setOf(1L, 2L), focus.toSet())
    }
}
