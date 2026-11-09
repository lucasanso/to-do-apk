package com.taskflow.domain.usecase

import com.taskflow.core.AppError
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.core.appRunCatching
import com.taskflow.core.fail
import com.taskflow.domain.engine.HabitStreaks
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.HabitWithStats
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.repository.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import javax.inject.Inject

class SaveHabitUseCase @Inject constructor(
    private val habits: HabitRepository,
    private val reminders: ReminderScheduler,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(habit: Habit): AppResult<Long> = appRunCatching {
        if (habit.title.isBlank()) fail(AppError.Validation("O nome do hábito é obrigatório"))
        val h = habit.copy(title = habit.title.trim(), createdAt = if (habit.id == 0L) time.now() else habit.createdAt)
        val id = habits.upsert(h)
        val saved = h.copy(id = id)
        if (saved.reminderTime != null && !saved.archived) reminders.scheduleHabit(saved) else reminders.cancelHabit(id)
        id
    }
}

class DeleteHabitUseCase @Inject constructor(private val habits: HabitRepository, private val reminders: ReminderScheduler) {
    suspend operator fun invoke(id: Long): AppResult<Unit> = appRunCatching { reminders.cancelHabit(id); habits.delete(id) }
}

class ToggleHabitUseCase @Inject constructor(private val habits: HabitRepository, private val time: TimeProvider) {
    suspend operator fun invoke(habitId: Long, date: LocalDate = time.today()): AppResult<Boolean> = appRunCatching {
        if (date.isAfter(time.today())) fail(AppError.Validation("Não é possível registrar datas futuras"))
        val done = date in habits.logsFor(habitId)
        habits.setDone(habitId, date, !done)
        !done
    }
}

class ObserveHabitsWithStatsUseCase @Inject constructor(private val habits: HabitRepository, private val time: TimeProvider) {
    operator fun invoke(includeArchived: Boolean = false): Flow<List<HabitWithStats>> =
        combine(habits.observeAll(), habits.observeLogs()) { list, logs ->
            val today = time.today()
            list.filter { includeArchived || !it.archived }.map { h ->
                val done = logs[h.id].orEmpty()
                HabitWithStats(
                    habit = h,
                    doneToday = today in done,
                    dueToday = RecurrenceCalculator.occursOn(h.rule, h.startDate, today),
                    currentStreak = HabitStreaks.current(h.rule, h.startDate, done, today),
                    bestStreak = HabitStreaks.best(h.rule, h.startDate, done, today),
                    completionRate30d = HabitStreaks.completionRate(h.rule, h.startDate, done, today),
                    recentLog = done,
                )
            }
        }
}

class SaveCategoryUseCase @Inject constructor(private val repo: CategoryRepository) {
    suspend operator fun invoke(c: Category): AppResult<Long> = appRunCatching {
        val name = c.name.trim()
        if (name.isEmpty()) fail(AppError.Validation("Nome obrigatório"))
        if (repo.getAll().any { it.id != c.id && it.name.equals(name, ignoreCase = true) }) fail(AppError.Validation("Já existe uma categoria com esse nome"))
        repo.upsert(c.copy(name = name))
    }
}
