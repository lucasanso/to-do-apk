package com.taskflow.presentation.myday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.core.TimeProvider
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.domain.engine.PriorityEngine
import com.taskflow.domain.engine.TaskFlag
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.PlanRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import com.taskflow.domain.usecase.StartTaskUseCase
import com.taskflow.presentation.components.TaskUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

data class MyDayState(
    val date: LocalDate = LocalDate.now(),
    val now: LocalDateTime = LocalDateTime.now(),
    val planned: List<TaskUi> = emptyList(),
    val suggestions: List<TaskUi> = emptyList(),
    val availableMinutes: Int = 360,
    /** Remaining estimated minutes of open planned tasks. */
    val plannedMinutes: Int = 0,
    val unestimated: Int = 0,
    val doneCount: Int = 0,
) {
    val overloadMinutes: Int get() = plannedMinutes - availableMinutes
    val overloaded: Boolean get() = overloadMinutes > 0
}

@HiltViewModel
class MyDayViewModel @Inject constructor(
    tasks: TaskRepository,
    categories: CategoryRepository,
    settings: SettingsRepository,
    private val plan: PlanRepository,
    private val time: TimeProvider,
    private val setDone: SetTaskDoneUseCase,
    private val start: StartTaskUseCase,
) : ViewModel() {
    private val today = time.today()

    val state: StateFlow<MyDayState> = combine(
        tasks.observeAll(), categories.observeAll(), plan.observeDay(today), plan.observeAvailableMinutes(today), settings.settings,
    ) { all, cats, items, available, s ->
        val now = time.nowDateTime()
        val catMap = cats.associateBy { it.id }
        val byId = all.associateBy { it.id }
        val ranked = PriorityEngine.rank(all, now)
        val scores = ranked.associate { it.first.id to it.second }
        // Done tasks stay visible in the plan; archived ones are hidden.
        val planned = items.mapNotNull { byId[it.taskId] }.filter { it.status != TaskStatus.ARCHIVED }
        val plannedIds = planned.map { it.id }.toSet()
        val open = planned.filter { it.status.isOpen }
        MyDayState(
            date = today,
            now = now,
            planned = planned.map { TaskUi(it, scores[it.id], it.categoryId?.let(catMap::get)) },
            suggestions = ranked.filter { it.first.id !in plannedIds && TaskFlag.BLOCKED !in it.second.flags }.take(8)
                .map { (t, sc) -> TaskUi(t, sc, t.categoryId?.let(catMap::get)) },
            availableMinutes = available ?: s.defaultAvailableMinutes,
            plannedMinutes = open.sumOf { it.estimatedMinutes ?: 0 },
            unestimated = open.count { it.estimatedMinutes == null },
            doneCount = planned.count { it.status == TaskStatus.DONE },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MyDayState())

    fun add(id: Long) = viewModelScope.launch { plan.add(today, id) }
    fun remove(id: Long) = viewModelScope.launch { plan.remove(today, id) }
    fun reorder(ids: List<Long>) = viewModelScope.launch { plan.reorder(today, ids) }
    fun setAvailable(min: Int) = viewModelScope.launch { plan.setAvailableMinutes(today, min.coerceIn(15, 24 * 60)) }
    fun toggle(id: Long, done: Boolean) = viewModelScope.launch { setDone(id, done) }
    fun start(id: Long) = viewModelScope.launch { start.invoke(id) }
    /** Fills the plan with top suggestions until the available time is used. */
    fun autoPlan() = viewModelScope.launch {
        val s = state.value
        var budget = s.availableMinutes - s.plannedMinutes
        for (ui in s.suggestions) {
            val est = ui.task.estimatedMinutes ?: 30
            if (budget - est < 0) continue
            plan.add(today, ui.task.id); budget -= est
        }
    }
}
