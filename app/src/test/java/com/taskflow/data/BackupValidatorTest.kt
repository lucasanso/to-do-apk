package com.taskflow.data

import com.taskflow.data.backup.BackupDto
import com.taskflow.data.backup.BackupValidator
import com.taskflow.data.backup.CategoryDto
import com.taskflow.data.backup.DayPlanDto
import com.taskflow.data.backup.HabitDto
import com.taskflow.data.backup.HabitLogDto
import com.taskflow.data.backup.TaskDto
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidatorTest {
    private val valid = BackupDto(
        exportedAt = 0,
        categories = listOf(CategoryDto(1, "Trabalho", 0xFF000000, "work")),
        tasks = listOf(TaskDto(1, "A", categoryId = 1, createdAt = 0, updatedAt = 0), TaskDto(2, "B", createdAt = 0, updatedAt = 0, dependsOn = listOf(1))),
        habits = listOf(HabitDto(1, "Ler", rule = "DAILY;1;", createdAt = 0, startDate = 0)),
        habitLogs = listOf(HabitLogDto(1, 5)),
        dayPlan = listOf(DayPlanDto(10, 1, 0)),
    )

    @Test fun `valid backup has no errors`() = assertTrue(BackupValidator.validate(valid).isEmpty())

    @Test fun `wrong format is rejected`() = assertTrue(BackupValidator.validate(valid.copy(format = "x")).isNotEmpty())
    @Test fun `future schema is rejected`() = assertTrue(BackupValidator.validate(valid.copy(schemaVersion = 99)).isNotEmpty())

    @Test fun `broken references are rejected`() {
        assertTrue(BackupValidator.validate(valid.copy(tasks = valid.tasks + TaskDto(3, "C", categoryId = 42, createdAt = 0, updatedAt = 0))).isNotEmpty())
        assertTrue(BackupValidator.validate(valid.copy(dayPlan = listOf(DayPlanDto(1, 99, 0)))).isNotEmpty())
        assertTrue(BackupValidator.validate(valid.copy(habitLogs = listOf(HabitLogDto(9, 1)))).isNotEmpty())
        assertTrue(BackupValidator.validate(valid.copy(tasks = listOf(TaskDto(1, "A", createdAt = 0, updatedAt = 0, dependsOn = listOf(7))))).isNotEmpty())
    }

    @Test fun `invalid values are rejected`() {
        assertTrue(BackupValidator.validate(valid.copy(tasks = listOf(TaskDto(1, "", createdAt = 0, updatedAt = 0)))).isNotEmpty())
        assertTrue(BackupValidator.validate(valid.copy(tasks = listOf(TaskDto(1, "A", priority = 9, createdAt = 0, updatedAt = 0)))).isNotEmpty())
        assertTrue(BackupValidator.validate(valid.copy(tasks = listOf(TaskDto(1, "A", recurrence = "BAD", createdAt = 0, updatedAt = 0)))).isNotEmpty())
        assertTrue(BackupValidator.validate(valid.copy(habits = listOf(HabitDto(1, "x", rule = "?", createdAt = 0, startDate = 0)))).isNotEmpty())
    }

    @Test fun `duplicates and cycles are rejected`() {
        assertTrue(BackupValidator.validate(valid.copy(tasks = valid.tasks + TaskDto(1, "dup", createdAt = 0, updatedAt = 0))).isNotEmpty())
        val cyc = listOf(TaskDto(1, "A", createdAt = 0, updatedAt = 0, dependsOn = listOf(2)), TaskDto(2, "B", createdAt = 0, updatedAt = 0, dependsOn = listOf(1)))
        assertTrue(BackupValidator.validate(valid.copy(tasks = cyc, dayPlan = emptyList())).any { it.contains("circulares") })
    }
}
