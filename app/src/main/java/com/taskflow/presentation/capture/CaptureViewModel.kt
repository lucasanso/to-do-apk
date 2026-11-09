package com.taskflow.presentation.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.ExtractedTask
import com.taskflow.domain.engine.ExtractionEngine
import com.taskflow.domain.repository.AiStatus
import com.taskflow.domain.repository.TaskExtractor
import com.taskflow.domain.usecase.ExtractTasksUseCase
import com.taskflow.domain.usecase.SaveExtractedTasksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewItem(val key: Int, val task: ExtractedTask, val selected: Boolean = true)

data class CaptureUiState(
    val input: String = "",
    val sourceLabel: String? = null,
    val aiStatus: AiStatus? = null,
    val loading: Boolean = false,
    val items: List<ReviewItem>? = null,
    val engine: ExtractionEngine? = null,
    val note: String? = null,
    val saving: Boolean = false,
    val savedCount: Int? = null,
    val error: String? = null,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val extract: ExtractTasksUseCase,
    private val saveTasks: SaveExtractedTasksUseCase,
    private val extractor: TaskExtractor,
    val time: TimeProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state
    private var started = false

    init { viewModelScope.launch { _state.update { it.copy(aiStatus = extractor.aiStatus()) } } }

    /** Called once with the shared text; extraction starts right away so the review is ready fast. */
    fun start(text: String, sourceLabel: String?) {
        if (started) return
        started = true
        _state.update { it.copy(input = text, sourceLabel = sourceLabel) }
        if (text.isNotBlank()) analyze()
    }

    fun onInput(text: String) = _state.update { it.copy(input = text, error = null) }

    fun analyze() {
        val text = _state.value.input
        if (text.isBlank()) { _state.update { it.copy(error = "Cole ou dite algum texto") }; return }
        _state.update { it.copy(loading = true, error = null, items = null) }
        viewModelScope.launch {
            val r = extract(text)
            _state.update {
                it.copy(
                    loading = false,
                    items = r.tasks.mapIndexed { i, t -> ReviewItem(i, t) },
                    engine = r.engine, note = r.note,
                    aiStatus = extractor.aiStatus(),
                )
            }
        }
    }

    fun toggle(key: Int) = updateItem(key) { it.copy(selected = !it.selected) }
    fun rename(key: Int, title: String) = updateItem(key) { it.copy(task = it.task.copy(title = title)) }
    fun setDate(key: Int, date: java.time.LocalDate?) = updateItem(key) {
        it.copy(task = it.task.copy(dueDate = date, dueTime = if (date == null) null else it.task.dueTime))
    }
    fun clearDate(key: Int) = updateItem(key) { it.copy(task = it.task.copy(dueDate = null, dueTime = null)) }
    fun removeSubtask(key: Int, index: Int) = updateItem(key) {
        it.copy(task = it.task.copy(subtasks = it.task.subtasks.filterIndexed { i, _ -> i != index }))
    }
    fun backToEdit() = _state.update { it.copy(items = null, engine = null, note = null) }

    private fun updateItem(key: Int, f: (ReviewItem) -> ReviewItem) =
        _state.update { s -> s.copy(items = s.items?.map { if (it.key == key) f(it) else it }) }

    fun save() {
        val chosen = _state.value.items.orEmpty().filter { it.selected && it.task.title.isNotBlank() }.map { it.task }
        if (chosen.isEmpty()) { _state.update { it.copy(error = "Selecione ao menos uma tarefa") }; return }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = saveTasks(chosen, _state.value.sourceLabel)) {
                is AppResult.Success -> _state.update { it.copy(saving = false, savedCount = r.value) }
                is AppResult.Failure -> _state.update { it.copy(saving = false, error = r.error.message) }
            }
        }
    }
}
