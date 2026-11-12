package com.taskflow.presentation.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.PriorityEngine
import com.taskflow.domain.engine.SmartFilter
import com.taskflow.domain.engine.TaskFilters
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Task
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.DeleteTaskUseCase
import com.taskflow.domain.usecase.DeletedTask
import com.taskflow.domain.usecase.PostponeTaskUseCase
import com.taskflow.domain.usecase.RestoreDeletedTaskUseCase
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import com.taskflow.presentation.components.TaskUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

enum class SortMode(val label: String) { SMART("Inteligente"), DUE("Prazo"), PRIORITY("Prioridade"), CREATED("Recentes") }

data class TasksQuery(
    val filter: SmartFilter = SmartFilter.ALL,
    val categoryId: Long? = null,
    val search: String = "",
    val sort: SortMode = SortMode.SMART,
)

data class TasksState(
    val query: TasksQuery = TasksQuery(),
    val items: List<TaskUi> = emptyList(),
    val categories: List<Category> = emptyList(),
    val counts: Map<SmartFilter, Int> = emptyMap(),
    val now: LocalDateTime = LocalDateTime.now(),
    val loading: Boolean = true,
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    savedState: SavedStateHandle,
    tasks: TaskRepository,
    categories: CategoryRepository,
    private val time: TimeProvider,
    private val setDone: SetTaskDoneUseCase,
    private val postpone: PostponeTaskUseCase,
    private val delete: DeleteTaskUseCase,
    private val restore: RestoreDeletedTaskUseCase,
) : ViewModel() {

    private val query = MutableStateFlow(
        TasksQuery(
            filter = savedState.get<String>("filter")?.takeIf { it.isNotBlank() }?.let { runCatching { SmartFilter.valueOf(it) }.getOrNull() } ?: SmartFilter.ALL,
            categoryId = savedState.get<Long>("category")?.takeIf { it >= 0 },
        )
    )

    val state: StateFlow<TasksState> = combine(tasks.observeAll(), categories.observeAll(), query) { all, cats, q ->
        val now = time.nowDateTime()
        val catMap = cats.associateBy { it.id }
        val scores = PriorityEngine.rank(all, now).associate { it.first.id to it.second }
        // A search covers every status so finished items are findable too.
        val base = if (q.search.isNotBlank()) TaskFilters.search(all, q.search, catMap) else TaskFilters.apply(all, q.filter, now)
        val filtered = base.filter { q.categoryId == null || it.categoryId == q.categoryId }
        val sorted = when (q.sort) {
            SortMode.SMART -> filtered.sortedWith(compareByDescending<Task> { it.status.isOpen }.thenByDescending { scores[it.id]?.score ?: -1 }.thenByDescending { it.completedAt })
            SortMode.DUE -> filtered.sortedWith(compareBy<Task>({ it.dueDate ?: java.time.LocalDate.MAX }, { it.dueTime ?: java.time.LocalTime.MAX }))
            SortMode.PRIORITY -> filtered.sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.dueDate ?: java.time.LocalDate.MAX })
            SortMode.CREATED -> filtered.sortedByDescending { it.createdAt }
        }
        TasksState(
            query = q,
            items = sorted.map { TaskUi(it, scores[it.id], it.categoryId?.let(catMap::get)) },
            categories = cats,
            counts = SmartFilter.entries.associateWith { f -> TaskFilters.apply(all, f, now).count { q.categoryId == null || it.categoryId == q.categoryId } },
            now = now,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TasksState())

    fun setFilter(f: SmartFilter) = query.update { it.copy(filter = f) }
    fun setCategory(id: Long?) = query.update { it.copy(categoryId = if (it.categoryId == id) null else id) }
    fun setSearch(s: String) = query.update { it.copy(search = s) }
    fun setSort(s: SortMode) = query.update { it.copy(sort = s) }

    fun toggle(task: Task) = viewModelScope.launch { setDone(task.id, task.status != com.taskflow.domain.model.TaskStatus.DONE) }
    fun postpone(id: Long, onDone: (String) -> Unit) = viewModelScope.launch {
        when (val r = postpone.invoke(id)) {
            is AppResult.Success -> onDone("Adiada para ${com.taskflow.presentation.util.formatShortDate(r.value)}")
            is AppResult.Failure -> onDone(r.error.message)
        }
    }
    fun delete(id: Long, onDeleted: (DeletedTask?) -> Unit) = viewModelScope.launch {
        onDeleted((delete(id) as? AppResult.Success)?.value)
    }
    fun restore(t: DeletedTask) = viewModelScope.launch { restore.invoke(t) }
}
