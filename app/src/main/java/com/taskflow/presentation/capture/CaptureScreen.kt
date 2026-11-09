package com.taskflow.presentation.capture

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.domain.engine.ExtractionEngine
import com.taskflow.domain.repository.AiStatus
import com.taskflow.presentation.components.DatePickerModal
import com.taskflow.presentation.util.formatDue
import com.taskflow.presentation.util.formatMinutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(vm: CaptureViewModel, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.items != null) "Revisar tarefas" else "Tarefas a partir de texto") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Fechar") } },
            )
        },
        bottomBar = {
            val items = s.items
            if (items != null && s.savedCount == null) {
                val n = items.count { it.selected }
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = vm::backToEdit, modifier = Modifier.weight(1f)) { Text("Editar texto") }
                        Button(onClick = vm::save, enabled = n > 0 && !s.saving, modifier = Modifier.weight(1.4f)) {
                            Text(if (n == 1) "Adicionar 1 tarefa" else "Adicionar $n tarefas")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                s.savedCount != null -> Done(s.savedCount!!, onClose, onOpenApp)
                s.loading -> Loading(s.aiStatus)
                s.items != null -> Review(s, vm)
                else -> Input(s, vm)
            }
        }
    }
}

@Composable
private fun Input(s: CaptureUiState, vm: CaptureViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AiStatusBanner(s.aiStatus)
        OutlinedTextField(
            value = s.input,
            onValueChange = vm::onInput,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 180.dp),
            placeholder = {
                Text("Cole uma anotação ou transcrição.\n\nEx.: Reunião com o João sexta às 15h, levar contrato. " +
                    "Preciso pagar o boleto da faculdade até dia 10, urgente. Revisar o relatório amanhã de manhã.")
            },
            isError = s.error != null,
            supportingText = s.error?.let { { Text(it) } },
        )
        Button(onClick = vm::analyze, modifier = Modifier.fillMaxWidth(), enabled = s.input.isNotBlank()) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Encontrar tarefas")
        }
        Text(
            "Dica: no Gravador de Voz da Samsung, abra a gravação › Transcrição › Compartilhar › TaskFlow. " +
                "Também funciona ao selecionar texto em qualquer app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun AiStatusBanner(status: AiStatus?) {
    status ?: return
    val ai = status == AiStatus.AVAILABLE
    Card(colors = CardDefaults.cardColors(containerColor = if (ai) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ai) Icons.Outlined.AutoAwesome else Icons.AutoMirrored.Outlined.Rule, null)
            Text(status.label + " · offline", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Loading(status: AiStatus?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(
            if (status == AiStatus.AVAILABLE) "Analisando com a IA do aparelho…" else "Procurando tarefas…",
            Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun Review(s: CaptureUiState, vm: CaptureViewModel) {
    val items = s.items.orEmpty()
    val today = vm.time.today()
    var pickingDateFor by remember { mutableStateOf<Int?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                buildString {
                    append(if (items.isEmpty()) "Nenhuma tarefa encontrada" else "${items.size} tarefa(s) encontrada(s)")
                    s.engine?.let { append(" · ${if (it == ExtractionEngine.ON_DEVICE_AI) "✨ " else ""}${it.label}") }
                },
                style = MaterialTheme.typography.titleSmall,
            )
            s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (items.isEmpty()) Text(
                "Tente frases com ação e prazo, como “enviar proposta até sexta”, ou uma tarefa por linha.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(items, key = { it.key }) { item -> ReviewCard(item, today, vm, onPickDate = { pickingDateFor = item.key }) }
        item { Spacer(Modifier.height(24.dp)) }
    }
    pickingDateFor?.let { key ->
        DatePickerModal(
            initial = items.firstOrNull { it.key == key }?.task?.dueDate,
            onDismiss = { pickingDateFor = null },
            onPick = { vm.setDate(key, it) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(item: ReviewItem, today: java.time.LocalDate, vm: CaptureViewModel, onPickDate: () -> Unit) {
    val t = item.task
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (item.selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = item.selected, onCheckedChange = { vm.toggle(item.key) })
                OutlinedTextField(
                    value = t.title, onValueChange = { vm.rename(item.key, it) },
                    modifier = Modifier.weight(1f), singleLine = false, maxLines = 3,
                    trailingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)) },
                    textStyle = MaterialTheme.typography.bodyLarge, enabled = item.selected,
                )
            }
            FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = onPickDate, enabled = item.selected,
                    leadingIcon = { Icon(Icons.Outlined.Event, null, Modifier.size(16.dp)) },
                    label = { Text(formatDue(t.dueDate, t.dueTime, today) ?: "Sem prazo") },
                )
                t.priority?.let { AssistChip(onClick = {}, label = { Text("Prioridade ${it.label.lowercase()}") }) }
                t.estimatedMinutes?.let { AssistChip(onClick = {}, label = { Text(formatMinutes(it)) }) }
                t.tags.forEach { AssistChip(onClick = {}, label = { Text("#$it") }) }
            }
            t.subtasks.forEachIndexed { i, sub ->
                InputChip(
                    selected = false, onClick = { vm.removeSubtask(item.key, i) }, enabled = item.selected,
                    label = { Text("☐ $sub") },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Remover subtarefa", Modifier.size(14.dp)) },
                    modifier = Modifier.padding(start = 48.dp),
                )
            }
            if (t.source.isNotBlank() && t.source.trim() != t.title.trim()) Text(
                "“${t.source.trim().take(160)}”", Modifier.padding(start = 48.dp, top = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            )
        }
    }
}

@Composable
private fun Done(count: Int, onClose: () -> Unit, onOpenApp: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (count == 1) "1 tarefa criada" else "$count tarefas criadas", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Button(onClick = onOpenApp, modifier = Modifier.padding(top = 24.dp).fillMaxWidth()) { Text("Abrir TaskFlow") }
        TextButton(onClick = onClose) { Text("Fechar") }
    }
}
