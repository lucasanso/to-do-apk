package com.taskflow.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.DueRules
import com.taskflow.domain.engine.PriorityEngine
import com.taskflow.domain.engine.ProductivityCalculator
import com.taskflow.domain.engine.Quadrant
import com.taskflow.domain.engine.TaskFlag
import com.taskflow.domain.model.HabitWithStats
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.PlanRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.ObserveHabitsWithStatsUseCase
import com.taskflow.domain.usecase.PostponeTaskUseCase
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import com.taskflow.domain.usecase.ToggleHabitUseCase
import com.taskflow.presentation.components.TaskUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

data class DashboardState(
    val loading: Boolean = true,
    val now: LocalDateTime = LocalDateTime.now(),
    val pending: Int = 0,
    val doneToday: Int = 0,
    val overdue: Int = 0,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val focus: List<TaskUi> = emptyList(),
    val upcoming: List<TaskUi> = emptyList(),
    val quadrants: Map<Quadrant, Int> = emptyMap(),
    val quickWins: Int = 0,
    val procrastinated: Int = 0,
    val habitsToday: List<HabitWithStats> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    tasks: TaskRepository,
    categories: CategoryRepository,
    plan: PlanRepository,
    habits: ObserveHabitsWithStatsUseCase,
    private val time: TimeProvider,
    private val setDone: SetTaskDoneUseCase,
    private val postpone: PostponeTaskUseCase,
    private val toggleHabit: ToggleHabitUseCase,
) : ViewModel() {

    /** Emits the current day and re-emits every minute so greetings/overdue state stay fresh. */
    private val ticker = flow { while (true) { emit(time.nowDateTime()); delay(60_000) } }

    val state: StateFlow<DashboardState> = ticker.flatMapLatest { _ ->
        val today = time.today()
        combine(tasks.observeAll(), categories.observeAll(), plan.observeDay(today), habits()) { all, cats, planned, habitStats ->
            val now = time.nowDateTime()
            val catMap = cats.associateBy { it.id }
            val plannedIds = planned.map { it.taskId }.toSet()
            val ranked = PriorityEngine.rank(all, now)
            val scores = ranked.associate { it.first.id to it.second }
            fun ui(t: com.taskflow.domain.model.Task) = TaskUi(t, scores[t.id], t.categoryId?.let(catMap::get))
            val (done, total) = ProductivityCalculator.dailyProgress(all, plannedIds, today, time.zone())
            val focus = PriorityEngine.focusToday(all, plannedIds, now)
            val focusIds = focus.map { it.first.id }.toSet()
            DashboardState(
                loading = false,
                now = now,
                pending = all.count { it.status.isOpen },
                doneToday = done,
                overdue = all.count { DueRules.isOverdue(it, now) },
                progressDone = done,
                progressTotal = total,
                focus = focus.map { (t, s) -> TaskUi(t, s, t.categoryId?.let(catMap::get)) },
                upcoming = all.filter { it.status.isOpen && it.dueDate != null && it.id !in focusIds && !DueRules.isOverdue(it, now) && it.dueDate.isAfter(today.minusDays(1)) }
                    .sortedWith(compareBy({ it.dueDate }, { it.dueTime ?: java.time.LocalTime.MAX }))
                    .take(5).map(::ui),
                quadrants = ranked.filter { TaskFlag.BLOCKED !in it.second.flags }.groupingBy { it.second.quadrant }.eachCount(),
                quickWins = ranked.count { TaskFlag.QUICK_WIN in it.second.flags },
                procrastinated = ranked.count { TaskFlag.PROCRASTINATED in it.second.flags },
                habitsToday = habitStats.filter { it.dueToday },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    fun toggle(id: Long, done: Boolean) = viewModelScope.launch { setDone(id, done) }
    fun postpone(id: Long) = viewModelScope.launch { postpone.invoke(id) }
    fun toggleHabit(id: Long) = viewModelScope.launch { toggleHabit.invoke(id) }
    fun today(): LocalDate = time.today()
}
