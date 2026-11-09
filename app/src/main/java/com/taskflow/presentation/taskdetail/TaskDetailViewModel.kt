package com.taskflow.presentation.taskdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.PriorityEngine
import com.taskflow.domain.engine.PriorityScore
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.HistoryEntry
import com.taskflow.domain.model.Task
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HistoryRepository
import com.taskflow.domain.repository.PlanRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.ArchiveTaskUseCase
import com.taskflow.domain.usecase.DeleteTaskUseCase
import com.taskflow.domain.usecase.DuplicateTaskUseCase
import com.taskflow.domain.usecase.PostponeTaskUseCase
import com.taskflow.domain.usecase.PromoteSubtaskUseCase
import com.taskflow.domain.usecase.RestoreDeletedTaskUseCase
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import com.taskflow.domain.usecase.StartTaskUseCase
import com.taskflow.domain.usecase.ToggleSubtaskUseCase
import com.taskflow.presentation.util.formatShortDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.taskflow.di.AppScope
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

data class DetailState(
    val loading: Boolean = true,
    val task: Task? = null,
    val category: Category? = null,
    val score: PriorityScore? = null,
    val dependencies: List<Task> = emptyList(),
    val dependents: List<Task> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val inMyDay: Boolean = false,
    val now: LocalDateTime = LocalDateTime.now(),
)

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    tasks: TaskRepository,
    categories: CategoryRepository,
    history: HistoryRepository,
    private val plan: PlanRepository,
    private val time: TimeProvider,
    private val setDone: SetTaskDoneUseCase,
    private val start: StartTaskUseCase,
    private val postpone: PostponeTaskUseCase,
    private val archive: ArchiveTaskUseCase,
    private val delete: DeleteTaskUseCase,
    private val restore: RestoreDeletedTaskUseCase,
    private val duplicate: DuplicateTaskUseCase,
    private val toggleSub: ToggleSubtaskUseCase,
    private val promote: PromoteSubtaskUseCase,
    @AppScope private val appScope: CoroutineScope,
) : ViewModel() {
    val id: Long = savedState.get<Long>("id") ?: 0L

    val state: StateFlow<DetailState> = combine(
        tasks.observeAll(), categories.observeAll(), history.observeForTask(id), plan.observeDay(time.today()),
    ) { all, cats, hist, today ->
        val now = time.nowDateTime()
        val t = all.firstOrNull { it.id == id }
        val byId = all.associateBy { it.id }
        DetailState(
            loading = false,
            task = t,
            category = t?.categoryId?.let { c -> cats.firstOrNull { it.id == c } },
            score = t?.takeIf { it.status.isOpen }?.let { PriorityEngine.score(it, byId, PriorityEngine.buildDependents(all), now) },
            dependencies = t?.dependsOn?.mapNotNull(byId::get).orEmpty(),
            dependents = all.filter { id in it.dependsOn },
            history = hist,
            inMyDay = today.any { it.taskId == id },
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailState())

    private fun run(msg: (String) -> Unit, block: suspend () -> AppResult<*>, ok: (Any?) -> String?) = viewModelScope.launch {
        when (val r = block()) {
            is AppResult.Success -> ok(r.value)?.let(msg)
            is AppResult.Failure -> msg(r.error.message)
        }
    }

    fun toggleDone(msg: (String) -> Unit) {
        val t = state.value.task ?: return
        val done = t.status != com.taskflow.domain.model.TaskStatus.DONE
        run(msg, { setDone(id, done) }) { next -> if (done) (if (next != null) "Concluída · próxima ocorrência criada" else "Concluída") else "Reaberta" }
    }
    fun start(msg: (String) -> Unit) = run(msg, { start.invoke(id) }) { "Tarefa iniciada" }
    fun postpone(date: LocalDate?, msg: (String) -> Unit) = run(msg, { postpone.invoke(id, date) }) { "Adiada para ${formatShortDate(it as LocalDate)}" }
    fun archive(archive: Boolean, msg: (String) -> Unit) = run(msg, { this.archive(id, archive) }) { if (archive) "Arquivada" else "Restaurada" }
    fun duplicate(msg: (String) -> Unit, open: (Long) -> Unit) = run(msg, { duplicate.invoke(id) }) { open(it as Long); "Tarefa duplicada" }
    fun toggleSubtask(subId: Long) = viewModelScope.launch { toggleSub(id, subId) }
    fun promote(subId: Long, msg: (String) -> Unit) = run(msg, { promote.invoke(id, subId) }) { "Subtarefa virou uma tarefa" }
    fun toggleMyDay() = viewModelScope.launch {
        if (state.value.inMyDay) plan.remove(time.today(), id) else plan.add(time.today(), id)
    }
    // Deleting closes this screen (the task disappears), which clears this ViewModel.
    // Delete and undo therefore run in the app scope so the undo snackbar and restore always complete.
    fun delete(onDeleted: (Task?) -> Unit) = appScope.launch { onDeleted((delete.invoke(id) as? AppResult.Success)?.value) }
    fun restore(t: Task) = appScope.launch { restore.invoke(t) }
}
