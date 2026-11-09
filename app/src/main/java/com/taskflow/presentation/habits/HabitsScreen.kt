package com.taskflow.presentation.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.HabitWithStats
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.usecase.DeleteHabitUseCase
import com.taskflow.domain.usecase.ObserveHabitsWithStatsUseCase
import com.taskflow.domain.usecase.SaveHabitUseCase
import com.taskflow.domain.usecase.ToggleHabitUseCase
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.EmptyState
import com.taskflow.presentation.components.LocalMessenger
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.components.TimePickerModal
import com.taskflow.presentation.components.asColor
import com.taskflow.presentation.taskedit.FieldLabel
import com.taskflow.presentation.taskedit.RecurrenceEditor
import com.taskflow.presentation.util.hhmm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class HabitsState(val today: LocalDate = LocalDate.now(), val habits: List<HabitWithStats> = emptyList(), val categories: Map<Long, Category> = emptyMap(), val loading: Boolean = true)

@HiltViewModel
class HabitsViewModel @Inject constructor(
    observe: ObserveHabitsWithStatsUseCase,
    categories: CategoryRepository,
    private val toggle: ToggleHabitUseCase,
    private val time: TimeProvider,
) : ViewModel() {
    val state: StateFlow<HabitsState> = combine(observe(includeArchived = true), categories.observeAll()) { h, c ->
        HabitsState(time.today(), h, c.associateBy { it.id }, false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HabitsState())

    fun toggle(id: Long, date: LocalDate) = viewModelScope.launch { toggle.invoke(id, date) }
}

@Composable
fun HabitsScreen(navigate: (String) -> Unit, vm: HabitsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val active = s.habits.filter { !it.habit.archived }
    val today = active.filter { it.dueToday }
    val other = active.filter { !it.dueToday }
    val archived = s.habits.filter { it.habit.archived }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                Text("Hábitos", style = MaterialTheme.typography.headlineMedium)
                Text(if (today.isEmpty()) "Rotinas recorrentes, separadas das tarefas pontuais"
                     else "${today.count { it.doneToday }} de ${today.size} feitos hoje",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (active.isEmpty() && !s.loading) item {
            EmptyState(Icons.Outlined.Loop, "Nenhum hábito ainda", "Crie rotinas como \"Estudar SQL\" ou \"Exercícios\" e acompanhe sua sequência.")
        }
        if (today.isNotEmpty()) {
            item { SectionHeader("Hoje") }
            items(today, key = { it.habit.id }) { h -> HabitCard(h, s, vm, navigate, Modifier.animateItem()) }
        }
        if (other.isNotEmpty()) {
            item { SectionHeader("Outros dias") }
            items(other, key = { it.habit.id }) { h -> HabitCard(h, s, vm, navigate, Modifier.animateItem()) }
        }
        if (archived.isNotEmpty()) {
            item { SectionHeader("Pausados") }
            items(archived, key = { it.habit.id }) { h -> HabitCard(h, s, vm, navigate, Modifier.animateItem()) }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun HabitCard(h: HabitWithStats, s: HabitsState, vm: HabitsViewModel, navigate: (String) -> Unit, modifier: Modifier) {
    val cat = h.habit.categoryId?.let(s.categories::get)
    Surface(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, onClick = { navigate(Routes.habitEdit(h.habit.id)) }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.habit.title, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(h.habit.rule.describe(), h.habit.reminderTime?.hhmm(), cat?.name).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (h.dueToday || h.doneToday) {
                    val color = cat?.colorArgb?.asColor() ?: MaterialTheme.colorScheme.primary
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (h.doneToday) color else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { vm.toggle(h.habit.id, s.today) }
                        .semantics { contentDescription = if (h.doneToday) "Desmarcar hoje" else "Marcar como feito hoje" },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Check, null, tint = if (h.doneToday) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Last 14 days strip: filled = done, outlined = scheduled & missed, faint = not scheduled.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val color = cat?.colorArgb?.asColor() ?: MaterialTheme.colorScheme.primary
                (13 downTo 0).forEach { off ->
                    val d = s.today.minusDays(off.toLong())
                    val done = d in h.recentLog
                    val scheduled = RecurrenceCalculator.occursOn(h.habit.rule, h.habit.startDate, d)
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))
                        .background(when { done -> color; scheduled -> MaterialTheme.colorScheme.surfaceContainerHighest; else -> MaterialTheme.colorScheme.surfaceContainer })
                        .clickable(enabled = scheduled || done) { vm.toggle(h.habit.id, d) })
                }
                Spacer(Modifier.weight(1f))
                Text("🔥 ${h.currentStreak}  ·  recorde ${h.bestStreak}  ·  ${(h.completionRate30d * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------- Edit ----------

data class HabitForm(val id: Long = 0, val title: String = "", val description: String = "", val categoryId: Long? = null,
                     val rule: RecurrenceRule = RecurrenceRule(RecurrenceType.DAILY), val reminderTime: LocalTime? = null,
                     val archived: Boolean = false, val startDate: LocalDate? = null)

data class HabitEditState(val form: HabitForm = HabitForm(), val categories: List<Category> = emptyList(), val loaded: Boolean = false,
                          val error: String? = null, val done: Boolean = false)

@HiltViewModel
class HabitEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val habits: HabitRepository,
    private val categories: CategoryRepository,
    private val save: SaveHabitUseCase,
    private val delete: DeleteHabitUseCase,
    private val time: TimeProvider,
) : ViewModel() {
    private val id = savedState.get<Long>("id") ?: 0L
    private var original: Habit? = null
    private val _state = MutableStateFlow(HabitEditState())
    val state: StateFlow<HabitEditState> = _state

    init {
        viewModelScope.launch {
            val h = if (id != 0L) habits.get(id) else null
            original = h
            _state.value = HabitEditState(
                h?.let { HabitForm(it.id, it.title, it.description, it.categoryId, it.rule, it.reminderTime, it.archived, it.startDate) } ?: HabitForm(),
                categories.getAll(), true)
        }
    }

    fun update(f: (HabitForm) -> HabitForm) = _state.update { it.copy(form = f(it.form), error = null) }

    fun submit() = viewModelScope.launch {
        val f = _state.value.form
        val h = Habit(f.id, f.title, f.description, f.categoryId, f.rule, f.reminderTime,
            original?.createdAt ?: time.now(), f.startDate ?: time.today(), f.archived)
        when (val r = save(h)) {
            is AppResult.Success -> _state.update { it.copy(done = true) }
            is AppResult.Failure -> _state.update { it.copy(error = r.error.message) }
        }
    }

    fun remove() = viewModelScope.launch { delete(id); _state.update { it.copy(done = true) } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HabitEditScreen(onBack: () -> Unit, vm: HabitEditViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val f = s.form
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val messenger = LocalMessenger.current
    LaunchedEffect(s.done) { if (s.done) { messenger.show("Hábito salvo"); onBack() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (f.id == 0L) "Novo hábito" else "Editar hábito") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } },
            actions = {
                if (f.id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Excluir hábito") }
                Button(onClick = vm::submit, enabled = f.title.isNotBlank(), modifier = Modifier.padding(end = 8.dp)) { Text("Salvar") }
            },
        )
    }) { pad ->
        if (!s.loaded) return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            OutlinedTextField(f.title, { v -> vm.update { it.copy(title = v) } }, Modifier.fillMaxWidth(), label = { Text("Nome") },
                isError = s.error != null, supportingText = s.error?.let { e -> { Text(e) } }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            OutlinedTextField(f.description, { v -> vm.update { it.copy(description = v) } }, Modifier.fillMaxWidth(), label = { Text("Notas") })
            FieldLabel("Frequência")
            RecurrenceEditor(f.rule, allowNone = false) { r -> if (r != null) vm.update { it.copy(rule = r) } }
            FieldLabel("Lembrete")
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { pickTime = true }, label = { Text(f.reminderTime?.hhmm() ?: "Sem lembrete") }, leadingIcon = { Icon(Icons.Outlined.Schedule, null) })
            }
            FieldLabel("Categoria")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.categories.forEach { c ->
                    FilterChip(f.categoryId == c.id, { vm.update { it.copy(categoryId = if (it.categoryId == c.id) null else c.id) } }, label = { Text(c.name) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(c.colorArgb.asColor())) })
                }
            }
            if (f.id != 0L) Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pausar hábito", Modifier.weight(1f))
                Switch(f.archived, { v -> vm.update { it.copy(archived = v) } })
            }
            Spacer(Modifier.height(48.dp))
        }
    }
    if (pickTime) TimePickerModal(f.reminderTime ?: LocalTime.of(20, 0), { pickTime = false }) { t -> vm.update { it.copy(reminderTime = t) } }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Excluir hábito?") },
        text = { Text("Todo o histórico de execução deste hábito será apagado. Para manter o histórico, use \"Pausar\".") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.remove() }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
    )
}
