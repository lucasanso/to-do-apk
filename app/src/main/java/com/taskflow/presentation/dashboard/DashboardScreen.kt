package com.taskflow.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.domain.engine.Quadrant
import com.taskflow.domain.engine.SmartFilter
import com.taskflow.domain.model.HabitWithStats
import com.taskflow.domain.model.TaskStatus
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.EmptyState
import com.taskflow.presentation.components.ProgressRing
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.components.SwipeableTaskRow
import com.taskflow.presentation.components.TaskRow
import com.taskflow.presentation.theme.TaskFlowColors
import com.taskflow.presentation.util.formatLongDate
import com.taskflow.presentation.util.greeting

@Composable
fun DashboardScreen(navigate: (String) -> Unit, vm: DashboardViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(greeting(s.now.hour), style = MaterialTheme.typography.headlineMedium)
                    Text(formatLongDate(s.now.toLocalDate()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { navigate(Routes.tasks(search = true)) }) { Icon(Icons.Outlined.Search, "Buscar") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Mais opções") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Estatísticas") }, { menu = false; navigate(Routes.STATS) }, leadingIcon = { Icon(Icons.Outlined.BarChart, null) })
                        DropdownMenuItem({ Text("Categorias") }, { menu = false; navigate(Routes.CATEGORIES) }, leadingIcon = { Icon(Icons.Outlined.Category, null) })
                        DropdownMenuItem({ Text("Histórico") }, { menu = false; navigate(Routes.HISTORY) }, leadingIcon = { Icon(Icons.Outlined.History, null) })
                        DropdownMenuItem({ Text("Configurações") }, { menu = false; navigate(Routes.SETTINGS) }, leadingIcon = { Icon(Icons.Outlined.Settings, null) })
                    }
                }
            }
        }
        item { Overview(s, navigate) }

        item {
            SectionHeader("Foco de hoje", action = { TextButton(onClick = { navigate(Routes.MY_DAY) }) { Text("Planejar") } })
        }
        if (s.focus.isEmpty() && !s.loading) {
            item { EmptyState(Icons.Outlined.TaskAlt, "Nada urgente por aqui", "Adicione tarefas ou planeje seu dia em \"Meu dia\".") }
        }
        items(s.focus, key = { "f" + it.task.id }) { ui ->
            Column(Modifier.animateItem()) {
                SwipeableTaskRow(ui, s.now, onClick = { navigate(Routes.taskDetail(ui.task.id)) },
                    onToggle = { vm.toggle(ui.task.id, ui.task.status != TaskStatus.DONE) }, onPostpone = { vm.postpone(ui.task.id) }, showScore = true)
                ui.score?.let { sc ->
                    Text(sc.explanation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 62.dp, end = 16.dp, bottom = 6.dp))
                }
            }
        }

        if (s.habitsToday.isNotEmpty()) {
            item { SectionHeader("Hábitos de hoje", action = { TextButton(onClick = { navigate(Routes.HABITS) }) { Text("Ver todos") } }) }
            item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.habitsToday, key = { it.habit.id }) { h -> HabitChip(h) { vm.toggleHabit(h.habit.id) } }
                }
            }
        }

        if (s.upcoming.isNotEmpty()) {
            item { SectionHeader("Próximas", action = { TextButton(onClick = { navigate(Routes.CALENDAR) }) { Text("Agenda") } }) }
            items(s.upcoming, key = { "u" + it.task.id }) { ui ->
                TaskRow(ui, s.now, onClick = { navigate(Routes.taskDetail(ui.task.id)) }, onToggle = { vm.toggle(ui.task.id, true) }, modifier = Modifier.animateItem())
            }
        }

        if (s.quadrants.isNotEmpty()) {
            item { SectionHeader("Prioridades") }
            item { PrioritySummary(s, navigate) }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun Overview(s: DashboardState, navigate: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val p = if (s.progressTotal == 0) 0f else s.progressDone.toFloat() / s.progressTotal
            ProgressRing(p, size = 64.dp) {
                Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (s.progressTotal == 0) "Sem tarefas para hoje" else "${s.progressDone} de ${s.progressTotal} concluídas hoje",
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("${s.pending}", "pendentes") { navigate(Routes.tasks()) }
                    Stat("${s.doneToday}", "feitas hoje") { navigate(Routes.tasks(SmartFilter.DONE.name)) }
                    Stat("${s.overdue}", "atrasadas", if (s.overdue > 0) TaskFlowColors.critical else null) { navigate(Routes.tasks(SmartFilter.OVERDUE.name)) }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color? = null, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color ?: MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HabitChip(h: HabitWithStats, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(14.dp),
        color = if (h.doneToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(if (h.doneToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center) {
                if (h.doneToday) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(h.habit.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text("🔥 ${h.currentStreak}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrioritySummary(s: DashboardState, navigate: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuadrantCell(Quadrant.DO_NOW, s.quadrants[Quadrant.DO_NOW] ?: 0, TaskFlowColors.critical, Modifier.weight(1f))
            QuadrantCell(Quadrant.SCHEDULE, s.quadrants[Quadrant.SCHEDULE] ?: 0, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuadrantCell(Quadrant.QUICK, s.quadrants[Quadrant.QUICK] ?: 0, TaskFlowColors.high, Modifier.weight(1f))
            QuadrantCell(Quadrant.LATER, s.quadrants[Quadrant.LATER] ?: 0, TaskFlowColors.low, Modifier.weight(1f))
        }
        if (s.quickWins > 0 || s.procrastinated > 0) {
            Text(
                listOfNotNull(
                    if (s.quickWins > 0) "${s.quickWins} tarefa(s) rápida(s) para resolver já" else null,
                    if (s.procrastinated > 0) "${s.procrastinated} adiada(s) com frequência" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).clickable { navigate(Routes.tasks()) },
            )
        }
    }
}

@Composable
private fun QuadrantCell(q: Quadrant, count: Int, color: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(q.label, style = MaterialTheme.typography.labelLarge)
                Text(q.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text("$count", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}
