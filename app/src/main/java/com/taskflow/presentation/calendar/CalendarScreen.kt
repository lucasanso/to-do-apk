package com.taskflow.presentation.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.PriorityEngine
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import com.taskflow.domain.usecase.ToggleHabitUseCase
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.components.TaskRow
import com.taskflow.presentation.components.TaskUi
import com.taskflow.presentation.theme.TaskFlowColors
import com.taskflow.presentation.util.PtBr
import com.taskflow.presentation.util.formatLongDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.TextStyle
import javax.inject.Inject

data class DayMarks(val open: Int = 0, val done: Int = 0, val habits: Int = 0, val overdue: Boolean = false)
data class HabitOnDay(val habit: Habit, val done: Boolean)

data class CalendarState(
    val weekMode: Boolean = false,
    val month: YearMonth = YearMonth.now(),
    val selected: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val now: LocalDateTime = LocalDateTime.now(),
    val days: List<LocalDate?> = emptyList(),
    val marks: Map<LocalDate, DayMarks> = emptyMap(),
    val dueOnSelected: List<TaskUi> = emptyList(),
    val doneOnSelected: List<TaskUi> = emptyList(),
    val habitsOnSelected: List<HabitOnDay> = emptyList(),
)

private data class CalUi(val weekMode: Boolean, val month: YearMonth, val selected: LocalDate)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    tasks: TaskRepository,
    habits: HabitRepository,
    categories: CategoryRepository,
    private val time: TimeProvider,
    private val setDone: SetTaskDoneUseCase,
    private val toggleHabit: ToggleHabitUseCase,
) : ViewModel() {
    private val ui = MutableStateFlow(CalUi(false, YearMonth.from(time.today()), time.today()))

    val state: StateFlow<CalendarState> = combine(ui, tasks.observeAll(), habits.observeAll(), habits.observeLogs(), categories.observeAll()) { u, all, hs, logs, cats ->
        val now = time.nowDateTime(); val today = now.toLocalDate(); val zone = time.zone()
        val days: List<LocalDate?> = if (u.weekMode) {
            val start = u.selected.minusDays((u.selected.dayOfWeek.value - 1).toLong())
            (0..6).map { start.plusDays(it.toLong()) }
        } else {
            val first = u.month.atDay(1)
            val lead = first.dayOfWeek.value - 1
            List(lead) { null } + (1..u.month.lengthOfMonth()).map { u.month.atDay(it) }
        }
        val visible = days.filterNotNull()
        val from = visible.first(); val to = visible.last()
        val activeHabits = hs.filter { !it.archived }
        val marks = visible.associateWith { d ->
            DayMarks(
                open = all.count { it.status.isOpen && it.dueDate == d },
                done = all.count { it.status == TaskStatus.DONE && it.completedAt?.atZone(zone)?.toLocalDate() == d },
                habits = activeHabits.count { RecurrenceCalculator.occursOn(it.rule, it.startDate, d) },
                overdue = d.isBefore(today) && all.any { it.status.isOpen && it.dueDate == d },
            )
        }
        val catMap = cats.associateBy { it.id }
        val scores = PriorityEngine.rank(all, now).associate { it.first.id to it.second }
        fun ui(t: com.taskflow.domain.model.Task) = TaskUi(t, scores[t.id], t.categoryId?.let(catMap::get))
        val sel = u.selected
        CalendarState(
            weekMode = u.weekMode, month = u.month, selected = sel, today = today, now = now, days = days, marks = marks,
            dueOnSelected = all.filter { it.status.isOpen && it.dueDate == sel }.sortedBy { it.dueTime ?: java.time.LocalTime.MAX }.map(::ui),
            doneOnSelected = all.filter { it.status == TaskStatus.DONE && it.completedAt?.atZone(zone)?.toLocalDate() == sel }.map(::ui),
            habitsOnSelected = activeHabits.filter { RecurrenceCalculator.occursOn(it.rule, it.startDate, sel) }
                .map { HabitOnDay(it, sel in logs[it.id].orEmpty()) },
        ).also { if (from > to) error("invalid range") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CalendarState())

    fun select(d: LocalDate) = ui.update { it.copy(selected = d, month = YearMonth.from(d)) }
    fun setWeekMode(w: Boolean) = ui.update { it.copy(weekMode = w) }
    fun shift(delta: Long) = ui.update {
        if (it.weekMode) { val d = it.selected.plusWeeks(delta); it.copy(selected = d, month = YearMonth.from(d)) }
        else { val m = it.month.plusMonths(delta); it.copy(month = m, selected = if (YearMonth.from(it.selected) == m) it.selected else m.atDay(1)) }
    }
    fun goToday() = select(time.today())
    fun toggle(id: Long, done: Boolean) = viewModelScope.launch { setDone(id, done) }
    fun toggleHabit(id: Long, date: LocalDate) = viewModelScope.launch { toggleHabit.invoke(id, date) }
}

@Composable
fun CalendarScreen(navigate: (String) -> Unit, vm: CalendarViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.month.month.getDisplayName(TextStyle.FULL, PtBr).replaceFirstChar { it.uppercase() } + " ${s.month.year}",
                    style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Anterior") }
                IconButton(onClick = { vm.shift(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Próximo") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(!s.weekMode, { vm.setWeekMode(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Mês") }
                    SegmentedButton(s.weekMode, { vm.setWeekMode(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Semana") }
                }
                TextButton(onClick = vm::goToday) { Text("Hoje") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                DayOfWeek.entries.forEach { d ->
                    Text(d.getDisplayName(TextStyle.NARROW, PtBr), Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                s.days.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { d -> DayCell(d, s, Modifier.weight(1f)) { vm.select(it) } }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item {
            SectionHeader(formatLongDate(s.selected), action = {
                IconButton(onClick = { navigate(Routes.taskEdit(date = s.selected)) }) { Icon(Icons.Outlined.Add, "Nova tarefa neste dia") }
            })
        }
        if (s.dueOnSelected.isEmpty() && s.doneOnSelected.isEmpty() && s.habitsOnSelected.isEmpty()) item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EventAvailable, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Dia livre", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(s.dueOnSelected, key = { "d" + it.task.id }) { ui ->
            TaskRow(ui, s.now, { navigate(Routes.taskDetail(ui.task.id)) }, { vm.toggle(ui.task.id, true) }, Modifier.animateItem())
        }
        if (s.habitsOnSelected.isNotEmpty()) item { SectionHeader("Hábitos") }
        items(s.habitsOnSelected, key = { "h" + it.habit.id }) { h ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !s.selected.isAfter(s.today)) { vm.toggleHabit(h.habit.id, s.selected) }
                .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (h.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center) {
                    if (h.done) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(14.dp))
                Text(h.habit.title, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (s.doneOnSelected.isNotEmpty()) item { SectionHeader("Concluídas") }
        items(s.doneOnSelected, key = { "c" + it.task.id }) { ui ->
            TaskRow(ui, s.now, { navigate(Routes.taskDetail(ui.task.id)) }, { vm.toggle(ui.task.id, false) }, Modifier.animateItem())
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun DayCell(d: LocalDate?, s: CalendarState, modifier: Modifier, onClick: (LocalDate) -> Unit) {
    if (d == null) { Spacer(modifier.aspectRatio(1f)); return }
    val m = s.marks[d] ?: DayMarks()
    val selected = d == s.selected
    val isToday = d == s.today
    val desc = "${d.dayOfMonth}: ${m.open} pendente(s), ${m.done} concluída(s), ${m.habits} hábito(s)"
    Column(
        modifier.aspectRatio(if (s.weekMode) 0.8f else 1f).padding(2.dp).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onClick(d) }.semantics { contentDescription = desc },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("${d.dayOfMonth}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
            color = when { isToday -> MaterialTheme.colorScheme.primary; d.month != s.month.month && !s.weekMode -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface })
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp).padding(top = 2.dp)) {
            if (m.open > 0) Dot(if (m.overdue) TaskFlowColors.critical else MaterialTheme.colorScheme.primary)
            if (m.habits > 0) Dot(MaterialTheme.colorScheme.tertiary)
            if (m.done > 0) Dot(TaskFlowColors.success)
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) = Box(Modifier.size(4.dp).clip(CircleShape).background(color))
