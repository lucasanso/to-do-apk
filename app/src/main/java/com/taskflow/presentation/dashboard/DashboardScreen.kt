package com.taskflow.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.domain.engine.SmartFilter
import com.taskflow.domain.model.HabitWithStats
import com.taskflow.domain.model.TaskStatus
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.EmptyState
import com.taskflow.presentation.components.LinearBar
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.components.SwipeableTaskRow
import com.taskflow.presentation.theme.TaskFlowColors
import com.taskflow.presentation.util.formatLongDate
import com.taskflow.presentation.util.greeting

/** "Hoje": only what matters now — progress, focus and today's habits. */
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

        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                val p = if (s.progressTotal == 0) 0f else s.progressDone.toFloat() / s.progressTotal
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.progressTotal == 0) "Nada agendado para hoje" else "${s.progressDone} de ${s.progressTotal} feitas hoje",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (s.overdue > 0) TextButton(onClick = { navigate(Routes.tasks(SmartFilter.OVERDUE.name)) }) {
                        Text(if (s.overdue == 1) "1 atrasada" else "${s.overdue} atrasadas", color = TaskFlowColors.critical)
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinearBar(p, Modifier.fillMaxWidth())
            }
        }

        item { SectionHeader("Foco", action = { TextButton(onClick = { navigate(Routes.MY_DAY) }) { Text("Planejar dia") } }) }
        if (s.focus.isEmpty() && !s.loading) item {
            EmptyState(Icons.Outlined.Mic, "Tudo em dia", "Toque no microfone e diga o que precisa fazer — por exemplo, “estudar SQL amanhã às 19h”.")
        }
        items(s.focus, key = { "f" + it.task.id }) { ui ->
            SwipeableTaskRow(ui, s.now, onClick = { navigate(Routes.taskDetail(ui.task.id)) },
                onToggle = { vm.toggle(ui.task.id, ui.task.status != TaskStatus.DONE) }, onPostpone = { vm.postpone(ui.task.id) },
                modifier = Modifier.animateItem())
        }

        if (s.habitsToday.isNotEmpty()) {
            item { SectionHeader("Hábitos") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.habitsToday, key = { it.habit.id }) { h -> HabitChip(h) { vm.toggleHabit(h.habit.id) } }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun HabitChip(h: HabitWithStats, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(50),
        color = if (h.doneToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(if (h.doneToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center) {
                if (h.doneToday) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(h.habit.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
