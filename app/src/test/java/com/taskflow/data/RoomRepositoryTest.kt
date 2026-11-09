package com.taskflow.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskflow.NOW
import com.taskflow.TODAY
import com.taskflow.core.AppResult
import com.taskflow.core.FixedTimeProvider
import com.taskflow.data.backup.BackupManager
import com.taskflow.data.local.TaskFlowDatabase
import com.taskflow.data.repository.CategoryRepositoryImpl
import com.taskflow.data.repository.HabitRepositoryImpl
import com.taskflow.data.repository.PlanRepositoryImpl
import com.taskflow.data.repository.TaskRepositoryImpl
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import com.taskflow.domain.model.Subtask
import com.taskflow.task
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskFlowDatabase::class.java)
        .allowMainThreadQueries().build()
    private val tasks = TaskRepositoryImpl(db)
    private val categories = CategoryRepositoryImpl(db)
    private val plan = PlanRepositoryImpl(db)
    private val habits = HabitRepositoryImpl(db)

    @After fun close() = db.close()

    @Test fun `upsert persists tags, subtasks and dependencies and updates in place`() = runTest {
        categories.seedDefaultsIfEmpty()
        val catId = categories.getAll().first().id
        val a = tasks.upsert(task(0, "A"))
        val id = tasks.upsert(task(0, "B", due = TODAY, dependsOn = listOf(a)).copy(
            categoryId = catId, tags = listOf("sql", "dados"), subtasks = listOf(Subtask(title = "s1"), Subtask(title = "s2"))))
        val t = tasks.get(id)!!
        assertEquals(listOf("dados", "sql"), t.tags)
        assertEquals(listOf("s1", "s2"), t.subtasks.map { it.title })
        assertEquals(listOf(a), t.dependsOn)
        assertEquals(TODAY, t.dueDate)

        // Edit: drop a subtask, rename tag; subtask ids are preserved.
        val keep = t.subtasks.first()
        tasks.upsert(t.copy(subtasks = listOf(keep.copy(done = true)), tags = listOf("sql")))
        val e = tasks.get(id)!!
        assertEquals(listOf(keep.id), e.subtasks.map { it.id })
        assertTrue(e.subtasks.single().done)
        assertEquals(listOf("sql"), tasks.allTags())
    }

    @Test fun `deleting a task cascades subtasks, plan entries and dependency edges`() = runTest {
        val a = tasks.upsert(task(0, "A").copy(subtasks = listOf(Subtask(title = "x")), tags = listOf("t")))
        val b = tasks.upsert(task(0, "B", dependsOn = listOf(a)))
        plan.add(TODAY, a); plan.add(TODAY, b)
        tasks.delete(a)
        assertNull(tasks.get(a))
        assertTrue(tasks.get(b)!!.dependsOn.isEmpty())
        assertEquals(listOf(b), plan.observeDay(TODAY).first().map { it.taskId })
        assertTrue(tasks.allTags().isEmpty())
    }

    @Test fun `deleting a category keeps tasks without category`() = runTest {
        categories.seedDefaultsIfEmpty()
        val c = categories.getAll().first()
        val id = tasks.upsert(task(0).copy(categoryId = c.id))
        categories.delete(c.id)
        assertNull(tasks.get(id)!!.categoryId)
    }

    @Test fun `plan keeps order after reorder`() = runTest {
        val ids = (1..3).map { tasks.upsert(task(0, "T$it")) }
        ids.forEach { plan.add(TODAY, it) }
        plan.reorder(TODAY, ids.reversed())
        assertEquals(ids.reversed(), plan.observeDay(TODAY).first().map { it.taskId })
    }

    @Test fun `backup export then import restores identical data`() = runTest {
        categories.seedDefaultsIfEmpty()
        val a = tasks.upsert(task(0, "A", due = TODAY).copy(tags = listOf("x"), subtasks = listOf(Subtask(title = "s")),
            recurrence = RecurrenceRule(RecurrenceType.DAILY), categoryId = categories.getAll()[1].id))
        tasks.upsert(task(0, "B", dependsOn = listOf(a)))
        val h = habits.upsert(Habit(title = "Ler", rule = RecurrenceRule(RecurrenceType.DAILY), createdAt = NOW.toInstant(ZoneOffset.UTC), startDate = TODAY))
        habits.setDone(h, TODAY, true)
        plan.add(TODAY, a)

        val manager = BackupManager(db, FixedTimeProvider(NOW))
        val json = manager.exportJson()
        val before = tasks.getAll()

        tasks.delete(a)
        val r = manager.importJson(json)
        assertTrue(r is AppResult.Success)
        // Subtask row ids are regenerated on import; everything else must be identical.
        fun norm(l: List<com.taskflow.domain.model.Task>) = l.map { t -> t.copy(subtasks = t.subtasks.map { it.copy(id = 0) }) }
        assertEquals(norm(before), norm(tasks.getAll()))
        assertEquals(setOf(TODAY), habits.logsFor(h))
        assertEquals(listOf(a), plan.observeDay(TODAY).first().map { it.taskId })
    }

    @Test fun `invalid import leaves data untouched`() = runTest {
        val id = tasks.upsert(task(0, "Keep"))
        val manager = BackupManager(db, FixedTimeProvider(NOW))
        assertTrue(manager.importJson("{ not json") is AppResult.Failure)
        assertTrue(manager.importJson("""{"format":"taskflow-backup","schemaVersion":1,"exportedAt":0,"tasks":[{"id":1,"title":"","createdAt":0,"updatedAt":0}]}""") is AppResult.Failure)
        assertEquals("Keep", tasks.get(id)!!.title)
    }
}
