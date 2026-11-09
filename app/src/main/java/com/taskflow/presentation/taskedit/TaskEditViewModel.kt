package com.taskflow.presentation.taskedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.Subtask
import com.taskflow.domain.model.Task
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.SaveTaskUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class EditForm(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val categoryId: Long? = null,
    val priority: Priority = Priority.MEDIUM,
    val status: TaskStatus = TaskStatus.PENDING,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val estimatedMinutes: Int? = null,
    val reminderMinutesBefore: Int? = null,
    val recurrence: RecurrenceRule? = null,
    val tags: List<String> = emptyList(),
    val subtasks: List<Subtask> = emptyList(),
    val dependsOn: List<Long> = emptyList(),
)

data class EditState(
    val form: EditForm = EditForm(),
    val categories: List<Category> = emptyList(),
    val candidates: List<Task> = emptyList(),
    val knownTags: List<String> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val isNew: Boolean = true,
)

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val tasks: TaskRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val save: SaveTaskUseCase,
    private val time: TimeProvider,
) : ViewModel() {
    private val id: Long = savedState.get<Long>("id") ?: 0L
    private val presetDate: LocalDate? = savedState.get<Long>("date")?.takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
    private var original: Task? = null

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val cats = categories.getAll()
            val all = tasks.getAll()
            val t = if (id != 0L) tasks.get(id) else null
            original = t
            val defaultReminder = settings.current().defaultReminderMinutes
            val form = t?.let {
                EditForm(it.id, it.title, it.description, it.categoryId, it.priority, it.status, it.dueDate, it.dueTime,
                    it.estimatedMinutes, it.reminderMinutesBefore, it.recurrence, it.tags, it.subtasks, it.dependsOn)
            } ?: EditForm(dueDate = presetDate, reminderMinutesBefore = if (presetDate != null) defaultReminder else null)
            _state.value = EditState(form, cats, all.filter { it.status.isOpen && it.id != id }, tasks.allTags(), loaded = true, isNew = t == null)
        }
    }

    fun update(transform: (EditForm) -> EditForm) = _state.update { it.copy(form = transform(it.form), error = null) }

    fun submit() = viewModelScope.launch {
        val f = _state.value.form
        val base = original ?: Task(title = f.title, createdAt = time.now())
        val task = base.copy(
            title = f.title, description = f.description, categoryId = f.categoryId, priority = f.priority, status = f.status,
            dueDate = f.dueDate, dueTime = if (f.dueDate == null) null else f.dueTime, estimatedMinutes = f.estimatedMinutes,
            reminderMinutesBefore = if (f.dueDate == null) null else f.reminderMinutesBefore,
            recurrence = if (f.dueDate == null) null else f.recurrence,
            tags = f.tags, subtasks = f.subtasks, dependsOn = f.dependsOn,
        )
        when (val r = save(task)) {
            is AppResult.Success -> _state.update { it.copy(saved = true) }
            is AppResult.Failure -> _state.update { it.copy(error = r.error.message) }
        }
    }
}
