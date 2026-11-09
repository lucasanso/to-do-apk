package com.taskflow.presentation.tasks

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.domain.engine.SmartFilter
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.EmptyState
import com.taskflow.presentation.components.LocalMessenger
import com.taskflow.presentation.components.SwipeableTaskRow
import com.taskflow.presentation.components.asColor

@Composable
fun TasksScreen(navigate: (String) -> Unit, startWithSearch: Boolean, vm: TasksViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    var searching by rememberSaveable { mutableStateOf(startWithSearch) }
    var sortMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                TextField(
                    value = s.query.search, onValueChange = vm::setSearch,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Buscar título, descrição, categoria, #tag") },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                IconButton(onClick = { vm.setSearch(""); searching = false }) { Icon(Icons.Outlined.Close, "Fechar busca") }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            } else {
                Text("Tarefas", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Buscar") }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Ordenar") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem({ Text(if (m == s.query.sort) "✓ ${m.label}" else m.label) }, { vm.setSort(m); sortMenu = false })
                        }
                    }
                }
            }
        }
        if (s.query.search.isBlank()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SmartFilter.entries) { f ->
                    val count = s.counts[f] ?: 0
                    FilterChip(selected = s.query.filter == f, onClick = { vm.setFilter(f) },
                        label = { Text(if (f == SmartFilter.DONE || f == SmartFilter.ARCHIVED) f.label else "${f.label} $count") })
                }
            }
        }
        if (s.categories.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.categories, key = { it.id }) { c ->
                    FilterChip(selected = s.query.categoryId == c.id, onClick = { vm.setCategory(c.id) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(c.colorArgb.asColor())) },
                        label = { Text(c.name) })
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (s.items.isEmpty() && !s.loading) {
            EmptyState(Icons.Outlined.Inbox,
                if (s.query.search.isNotBlank()) "Nenhum resultado" else "Nenhuma tarefa aqui",
                if (s.query.search.isNotBlank()) "Tente outros termos." else "Toque em + para criar. Deslize → para concluir e ← para adiar.")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(s.items, key = { it.task.id }) { ui ->
                SwipeableTaskRow(
                    ui, s.now,
                    onClick = { navigate(Routes.taskDetail(ui.task.id)) },
                    onToggle = { vm.toggle(ui.task) },
                    onPostpone = { vm.postpone(ui.task.id) { messenger.show(it) } },
                    modifier = Modifier.animateItem(),
                    showScore = s.query.sort == SortMode.SMART,
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}
