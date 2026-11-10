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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.InputChip
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
private fun MenuLabel(text: String) = Text(text.uppercase(), style = com.taskflow.presentation.theme.SectionLabel,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))

private val MAIN_FILTERS = listOf(SmartFilter.ALL, SmartFilter.TODAY, SmartFilter.OVERDUE, SmartFilter.DONE)
private val MORE_FILTERS = listOf(SmartFilter.TOMORROW, SmartFilter.THIS_WEEK, SmartFilter.NO_DATE, SmartFilter.HIGH_PRIORITY, SmartFilter.ARCHIVED)

@Composable
fun TasksScreen(navigate: (String) -> Unit, startWithSearch: Boolean, vm: TasksViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    var searching by rememberSaveable { mutableStateOf(startWithSearch) }
    var filterMenu by remember { mutableStateOf(false) }
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
                    IconButton(onClick = { filterMenu = true }) { Icon(Icons.Outlined.FilterList, "Filtrar e ordenar") }
                    DropdownMenu(filterMenu, { filterMenu = false }) {
                        MenuLabel("Mostrar")
                        MORE_FILTERS.forEach { f ->
                            DropdownMenuItem({ Text(if (f == s.query.filter) "✓ ${f.label}" else f.label) }, { vm.setFilter(f); filterMenu = false })
                        }
                        if (s.categories.isNotEmpty()) { HorizontalDivider(); MenuLabel("Categoria") }
                        s.categories.forEach { c ->
                            DropdownMenuItem(
                                { Text(if (c.id == s.query.categoryId) "✓ ${c.name}" else c.name) },
                                { vm.setCategory(c.id); filterMenu = false },
                                leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(c.colorArgb.asColor())) },
                            )
                        }
                        HorizontalDivider(); MenuLabel("Ordenar por")
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem({ Text(if (m == s.query.sort) "✓ ${m.label}" else m.label) }, { vm.setSort(m); filterMenu = false })
                        }
                    }
                }
            }
        }
        if (s.query.search.isBlank()) {
            // Four everyday filters; the rest live in the filter menu.
            val extra = s.query.filter.takeIf { it in MORE_FILTERS }
            val category = s.categories.firstOrNull { it.id == s.query.categoryId }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MAIN_FILTERS) { f ->
                    FilterChip(selected = s.query.filter == f, onClick = { vm.setFilter(f) }, label = { Text(if (f == SmartFilter.ALL) "Abertas" else f.label) })
                }
                extra?.let { f -> item { InputChip(true, { vm.setFilter(SmartFilter.ALL) }, label = { Text(f.label) },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Limpar filtro", Modifier.size(16.dp)) }) } }
                category?.let { c -> item { InputChip(true, { vm.setCategory(c.id) }, label = { Text(c.name) },
                    leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(c.colorArgb.asColor())) },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Limpar categoria", Modifier.size(16.dp)) }) } }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (s.items.isEmpty() && !s.loading) {
            EmptyState(Icons.Outlined.Inbox,
                if (s.query.search.isNotBlank()) "Nenhum resultado" else "Nenhuma tarefa aqui",
                if (s.query.search.isNotBlank()) "Tente outros termos." else "Toque no microfone para criar. Deslize → para concluir e ← para adiar.")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(s.items, key = { it.task.id }) { ui ->
                SwipeableTaskRow(
                    ui, s.now,
                    onClick = { navigate(Routes.taskDetail(ui.task.id)) },
                    onToggle = { vm.toggle(ui.task) },
                    onPostpone = { vm.postpone(ui.task.id) { messenger.show(it) } },
                    modifier = Modifier.animateItem(),
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}
