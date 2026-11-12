package com.taskflow.presentation.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.domain.model.Priority
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.ExecuteVoiceCommandUseCase
import com.taskflow.domain.usecase.InterpretVoiceCommandUseCase
import com.taskflow.domain.usecase.QuickAddTaskUseCase
import com.taskflow.domain.usecase.VoiceOutcome
import com.taskflow.domain.voice.CompletionCandidate
import com.taskflow.domain.voice.Interpretation
import com.taskflow.domain.voice.InterpretationSource
import com.taskflow.domain.voice.TaskDraft
import com.taskflow.domain.voice.TaskMatch
import com.taskflow.domain.voice.VoiceCommandParser
import com.taskflow.presentation.util.formatDue
import com.taskflow.presentation.util.formatMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VoiceUi {
    data object Idle : VoiceUi
    data class Listening(val partial: String = "", val level: Float = 0f) : VoiceUi
    data class Thinking(val text: String) : VoiceUi
    data class Done(val text: String, val outcome: VoiceOutcome, val source: InterpretationSource, val undone: Boolean = false) : VoiceUi
    data class Choose(val text: String, val options: List<CompletionCandidate>, val action: Action = Action.COMPLETE) : VoiceUi
    /** Deleting always asks first: speech recognition and fuzzy matching can pick the wrong item. */
    data class ConfirmDelete(val text: String, val candidate: CompletionCandidate, val source: InterpretationSource) : VoiceUi
    /** [offerCreate]: the sentence could also be a new task ("cancela a assinatura") — offer that. */
    data class Message(val text: String?, val message: String, val offerCreate: Boolean = false) : VoiceUi

    enum class Action { COMPLETE, DELETE }
}

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val interpret: InterpretVoiceCommandUseCase,
    private val exec: ExecuteVoiceCommandUseCase,
    private val quickAdd: QuickAddTaskUseCase,
    private val tasks: TaskRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    val time: TimeProvider,
) : ViewModel() {
    private val _ui = MutableStateFlow<VoiceUi>(VoiceUi.Idle)
    val ui: StateFlow<VoiceUi> = _ui.asStateFlow()

    suspend fun voiceFirst(): Boolean = settings.current().voiceFirst

    fun listening(partial: String, level: Float) {
        val cur = _ui.value
        _ui.value = VoiceUi.Listening(partial.ifEmpty { (cur as? VoiceUi.Listening)?.partial.orEmpty() }, level)
    }

    fun idle() { _ui.value = VoiceUi.Idle }
    fun error(message: String) { _ui.value = VoiceUi.Message(null, message) }

    /** Typed text with quick-capture tokens (!alta, #tag, @categoria) keeps the compact syntax. */
    private fun usesQuickSyntax(text: String) = text.split(' ').any { it.length > 1 && (it[0] == '!' || it[0] == '#' || it[0] == '@') }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _ui.value = VoiceUi.Thinking(t)
        viewModelScope.launch {
            if (usesQuickSyntax(t)) {
                _ui.value = when (val r = quickAdd(t)) {
                    is AppResult.Success -> tasks.get(r.value)?.let { task ->
                        VoiceUi.Done(t, VoiceOutcome.Created(task.id, task.title,
                            TaskDraft(task.title, task.dueDate, task.dueTime, task.estimatedMinutes, task.priority, null, task.tags),
                            task.categoryId?.let { id -> categories.getAll().firstOrNull { it.id == id }?.name }), InterpretationSource.RULES)
                    } ?: VoiceUi.Message(t, "Não foi possível criar a tarefa")
                    is AppResult.Failure -> VoiceUi.Message(t, r.error.message)
                }
                return@launch
            }
            val result = interpret(t, settings.current().useOnDeviceAi)
            _ui.value = when (result) {
                is Interpretation.Create -> done(t, exec.create(result.draft), result.source)
                is Interpretation.Complete -> when (val m = result.match) {
                    is TaskMatch.Unique -> done(t, exec.complete(m.match.candidate), result.source)
                    is TaskMatch.Ambiguous -> VoiceUi.Choose(t, m.options.map { it.candidate })
                    TaskMatch.NotFound -> VoiceUi.Message(t, "Não encontrei nada aberto parecido com “${result.query}”.")
                }
                is Interpretation.Delete -> when (val m = result.match) {
                    is TaskMatch.Unique -> VoiceUi.ConfirmDelete(t, m.match.candidate, result.source)
                    is TaskMatch.Ambiguous -> VoiceUi.Choose(t, m.options.map { it.candidate }, VoiceUi.Action.DELETE)
                    TaskMatch.NotFound -> VoiceUi.Message(t, "Não encontrei nada parecido com “${result.query}” para apagar.", offerCreate = true)
                }
                is Interpretation.Unknown -> VoiceUi.Message(t, "Não entendi o que fazer. Diga, por exemplo, “reunião amanhã às 15h”, “já paguei a fatura” ou “apaga a tarefa X”.")
            }
        }
    }

    /** Picking an item from the list is itself the confirmation, also for deleting. */
    fun choose(text: String, c: CompletionCandidate, action: VoiceUi.Action) {
        viewModelScope.launch {
            _ui.value = done(text, if (action == VoiceUi.Action.DELETE) exec.deleteItem(c) else exec.complete(c), InterpretationSource.RULES)
        }
    }

    fun confirmDelete() {
        val cur = _ui.value as? VoiceUi.ConfirmDelete ?: return
        viewModelScope.launch { _ui.value = done(cur.text, exec.deleteItem(cur.candidate), cur.source) }
    }

    /** "Criar como nova tarefa" when a delete request found nothing to delete. */
    fun createInstead(text: String) {
        viewModelScope.launch {
            val draft = VoiceCommandParser.parseAsCreate(text, time.nowDateTime(), categories.getAll().map { it.name })
            _ui.value = if (draft.title.isBlank()) VoiceUi.Message(text, "Não foi possível criar a tarefa")
            else done(text, exec.create(draft), InterpretationSource.RULES)
        }
    }

    fun undo() {
        val cur = _ui.value as? VoiceUi.Done ?: return
        if (cur.undone) return
        viewModelScope.launch {
            exec.undo(cur.outcome)
            _ui.value = cur.copy(undone = true)
        }
    }

    private fun done(text: String, o: VoiceOutcome, source: InterpretationSource): VoiceUi =
        if (o is VoiceOutcome.Failed) VoiceUi.Message(text, o.message) else VoiceUi.Done(text, o, source)

    /** One-line summary of what was understood for a created task. */
    fun summary(o: VoiceOutcome.Created): String = buildList {
        val d = o.draft
        formatDue(d.dueDate, d.dueTime, time.today())?.let(::add)
        d.recurrence?.let { add(it.describe()) }
        d.estimatedMinutes?.let { add(formatMinutes(it)) }
        d.priority?.takeIf { it != Priority.MEDIUM }?.let { add("Prioridade ${it.label.lowercase()}") }
        o.categoryName?.let(::add)
        d.reminderMinutesBefore?.takeIf { it > 0 }?.let { add("lembrete ${formatMinutes(it)} antes") }
    }.joinToString(" · ").ifEmpty { "Sem prazo" }
}
