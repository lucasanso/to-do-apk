package com.taskflow.presentation.taskedit

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import com.taskflow.domain.model.Subtask
import com.taskflow.domain.model.TaskStatus
import com.taskflow.presentation.components.DatePickerModal
import com.taskflow.presentation.components.LocalMessenger
import com.taskflow.presentation.components.TimePickerModal
import com.taskflow.presentation.components.asColor
import com.taskflow.presentation.theme.SectionLabel
import com.taskflow.presentation.theme.TaskFlowColors
import com.taskflow.presentation.util.formatLongDate
import com.taskflow.presentation.util.formatMinutes
import com.taskflow.presentation.util.hhmm
import java.time.DayOfWeek

@Composable
fun FieldLabel(text: String) {
    Text(text.uppercase(), style = SectionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditScreen(onBack: () -> Unit, vm: TaskEditViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val f = s.form
    val messenger = LocalMessenger.current
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickDeps by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    var subInput by remember { mutableStateOf("") }
    var customEstimate by remember { mutableStateOf("") }

    LaunchedEffect(s.saved) { if (s.saved) { messenger.show(if (s.isNew) "Tarefa criada" else "Alterações salvas"); onBack() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.isNew) "Nova tarefa" else "Editar tarefa") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } },
                actions = { Button(onClick = vm::submit, enabled = s.loaded && f.title.isNotBlank(), modifier = Modifier.padding(end = 8.dp)) { Text("Salvar") } },
            )
        },
    ) { pad ->
        if (!s.loaded) return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            OutlinedTextField(f.title, { v -> vm.update { it.copy(title = v) } }, Modifier.fillMaxWidth(), label = { Text("Título") },
                isError = s.error != null, supportingText = s.error?.let { e -> { Text(e) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            OutlinedTextField(f.description, { v -> vm.update { it.copy(description = v) } }, Modifier.fillMaxWidth().heightIn(min = 90.dp).padding(top = 8.dp),
                label = { Text("Descrição") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))

            FieldLabel("Prioridade")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Priority.entries.forEach { p ->
                    FilterChip(f.priority == p, { vm.update { it.copy(priority = p) } }, label = { Text(p.label) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(TaskFlowColors.priority(p))) })
                }
            }

            FieldLabel("Categoria")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.categories.forEach { c ->
                    FilterChip(f.categoryId == c.id, { vm.update { it.copy(categoryId = if (it.categoryId == c.id) null else c.id) } }, label = { Text(c.name) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(c.colorArgb.asColor())) })
                }
            }

            FieldLabel("Prazo")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { pickDate = true }, label = { Text(f.dueDate?.let(::formatLongDate) ?: "Sem prazo") }, leadingIcon = { Icon(Icons.Outlined.Event, null) })
                if (f.dueDate != null) AssistChip(onClick = { pickTime = true }, label = { Text(f.dueTime?.hhmm() ?: "Horário") }, leadingIcon = { Icon(Icons.Outlined.Schedule, null) })
            }

            if (f.dueDate != null) {
                FieldLabel("Lembrete")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Nenhum", 0 to "No horário", 15 to "15 min antes", 30 to "30 min antes", 60 to "1 h antes", 1440 to "1 dia antes").forEach { (v, l) ->
                        FilterChip(f.reminderMinutesBefore == v, { vm.update { it.copy(reminderMinutesBefore = v) } }, label = { Text(l) })
                    }
                }
                if (f.reminderMinutesBefore != null && f.dueTime == null)
                    Text("Sem horário definido, o lembrete usa 09:00 como referência.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                FieldLabel("Repetir")
                RecurrenceEditor(f.recurrence) { r -> vm.update { it.copy(recurrence = r) } }
            }

            FieldLabel("Duração estimada")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null, 5, 15, 30, 60, 120, 240).forEach { m ->
                    FilterChip(f.estimatedMinutes == m, { vm.update { it.copy(estimatedMinutes = m) } }, label = { Text(m?.let(::formatMinutes) ?: "—") })
                }
            }
            OutlinedTextField(customEstimate, { v -> customEstimate = v.filter(Char::isDigit).take(4); customEstimate.toIntOrNull()?.let { m -> vm.update { it.copy(estimatedMinutes = m) } } },
                Modifier.padding(top = 4.dp), label = { Text("Outro (min)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

            if (!s.isNew) {
                FieldLabel("Status")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskStatus.entries.forEach { st -> FilterChip(f.status == st, { vm.update { it.copy(status = st) } }, label = { Text(st.label) }) }
                }
            }

            FieldLabel("Subtarefas")
            f.subtasks.forEachIndexed { i, st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(st.done, { d -> vm.update { it.copy(subtasks = it.subtasks.toMutableList().also { l -> l[i] = st.copy(done = d) }) } })
                    Text(st.title, Modifier.weight(1f))
                    IconButton(onClick = { vm.update { it.copy(subtasks = it.subtasks.filterIndexed { j, _ -> j != i }) } }) { Icon(Icons.Outlined.Close, "Remover subtarefa") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(subInput, { subInput = it }, Modifier.weight(1f), placeholder = { Text("Adicionar subtarefa") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (subInput.isNotBlank()) { vm.update { it.copy(subtasks = it.subtasks + Subtask(title = subInput.trim())) }; subInput = "" }
                    }))
                IconButton(onClick = { if (subInput.isNotBlank()) { vm.update { it.copy(subtasks = it.subtasks + Subtask(title = subInput.trim())) }; subInput = "" } }) {
                    Icon(Icons.Outlined.Add, "Adicionar subtarefa")
                }
            }

            FieldLabel("Tags")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                f.tags.forEach { t -> InputChip(true, { vm.update { it.copy(tags = it.tags - t) } }, label = { Text("#$t") }, trailingIcon = { Icon(Icons.Outlined.Close, "Remover", Modifier.size(16.dp)) }) }
            }
            fun addTag() { val t = tagInput.trim().removePrefix("#"); if (t.isNotEmpty() && t !in f.tags) vm.update { it.copy(tags = it.tags + t) }; tagInput = "" }
            OutlinedTextField(tagInput, { tagInput = it.replace(" ", "") }, Modifier.fillMaxWidth(), placeholder = { Text("Nova tag") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addTag() }))
            val suggestions = s.knownTags.filter { it !in f.tags && (tagInput.isBlank() || it.startsWith(tagInput, true)) }.take(8)
            if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { t -> AssistChip(onClick = { vm.update { it.copy(tags = it.tags + t) } }, label = { Text("#$t") }) }
            }

            FieldLabel("Depende de")
            val depTitles = s.candidates.filter { it.id in f.dependsOn }
            depTitles.forEach { d -> Text("• ${d.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            TextButton(onClick = { pickDeps = true }) { Text(if (f.dependsOn.isEmpty()) "Selecionar tarefas" else "Alterar") }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (pickDate) DatePickerModal(f.dueDate, { pickDate = false }, { d -> vm.update { it.copy(dueDate = d) } })
    if (pickTime) TimePickerModal(f.dueTime, { pickTime = false }, { t -> vm.update { it.copy(dueTime = t) } })
    if (pickDeps) {
        var sel by remember { mutableStateOf(f.dependsOn.toSet()) }
        AlertDialog(
            onDismissRequest = { pickDeps = false },
            title = { Text("Depende de") },
            text = {
                if (s.candidates.isEmpty()) Text("Não há outras tarefas abertas.")
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(s.candidates, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.id in sel, { on -> sel = if (on) sel + c.id else sel - c.id })
                            Text(c.title, maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.update { it.copy(dependsOn = sel.toList()) }; pickDeps = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDeps = false }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecurrenceEditor(rule: RecurrenceRule?, allowNone: Boolean = true, onChange: (RecurrenceRule?) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (allowNone) FilterChip(rule == null, { onChange(null) }, label = { Text("Não repetir") })
            RecurrenceType.entries.forEach { t ->
                FilterChip(rule?.type == t, {
                    onChange(when (t) {
                        RecurrenceType.WEEKDAYS -> RecurrenceRule(t, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
                        RecurrenceType.CUSTOM -> RecurrenceRule(t, 2)
                        else -> RecurrenceRule(t, 1)
                    })
                }, label = { Text(t.label) })
            }
        }
        if (rule?.type == RecurrenceType.WEEKDAYS) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(d in rule.days, {
                        val days = if (d in rule.days) rule.days - d else rule.days + d
                        if (days.isNotEmpty()) onChange(rule.copy(days = days))
                    }, label = { Text(RecurrenceRule.shortDay(d)) })
                }
            }
        }
        if (rule != null && rule.type != RecurrenceType.WEEKDAYS) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Intervalo: ", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { if (rule.interval > 1) onChange(rule.copy(interval = rule.interval - 1)) }) { Text("−") }
                Text("${rule.interval}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { if (rule.interval < 365) onChange(rule.copy(interval = rule.interval + 1)) }) { Text("+") }
                Spacer(Modifier.width(8.dp))
                Text(rule.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
