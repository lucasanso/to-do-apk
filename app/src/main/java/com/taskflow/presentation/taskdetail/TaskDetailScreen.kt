package com.taskflow.presentation.taskdetail

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.domain.engine.DueRules
import com.taskflow.domain.engine.PriorityScore
import com.taskflow.domain.model.TaskStatus
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.DatePickerModal
import com.taskflow.presentation.components.LocalMessenger
import com.taskflow.presentation.components.asColor
import com.taskflow.presentation.taskedit.FieldLabel
import com.taskflow.presentation.theme.TaskFlowColors
import com.taskflow.presentation.util.formatDue
import com.taskflow.presentation.util.formatMinutes
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(onBack: () -> Unit, navigate: (String) -> Unit, vm: TaskDetailViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val msg: (String) -> Unit = { messenger.show(it) }
    var menu by remember { mutableStateOf(false) }
    var pickPostpone by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val t = s.task

    LaunchedEffect(s.loading, t) { if (!s.loading && t == null) onBack() }

    Scaffold(topBar = {
        TopAppBar(
            title = {},
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } },
            actions = {
                IconButton(onClick = { navigate(Routes.taskEdit(vm.id)) }) { Icon(Icons.Outlined.Edit, "Editar") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Mais") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Duplicar") }, { menu = false; vm.duplicate(msg) { navigate(Routes.taskDetail(it)) } }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                        if (t?.status == TaskStatus.ARCHIVED)
                            DropdownMenuItem({ Text("Desarquivar") }, { menu = false; vm.archive(false, msg) }, leadingIcon = { Icon(Icons.Outlined.Unarchive, null) })
                        else DropdownMenuItem({ Text("Arquivar") }, { menu = false; vm.archive(true, msg) }, leadingIcon = { Icon(Icons.Outlined.Archive, null) })
                        DropdownMenuItem({ Text("Excluir") }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                    }
                }
            },
        )
    }) { pad ->
        if (t == null) return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(TaskFlowColors.priority(t.priority)))
                Spacer(Modifier.width(8.dp))
                Text("${t.priority.label} · ${t.status.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(t.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp),
                textDecoration = if (t.status == TaskStatus.DONE) TextDecoration.LineThrough else null)
            if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                formatDue(t.dueDate, t.dueTime, s.now.toLocalDate())?.let {
                    AssistChip({}, { Text(if (DueRules.isOverdue(t, s.now)) "$it · atrasada" else it) })
                }
                s.category?.let { c -> AssistChip({}, { Text(c.name) }, leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(c.colorArgb.asColor())) }) }
                t.estimatedMinutes?.let { AssistChip({}, { Text(formatMinutes(it)) }) }
                t.recurrence?.let { AssistChip({}, { Text(it.describe()) }) }
                if (t.postponeCount > 0) AssistChip({}, { Text("Adiada ${t.postponeCount}x") })
                t.tags.forEach { AssistChip({ navigate(Routes.tasks(search = true)) }, { Text("#$it") }) }
            }

            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.toggleDone(msg) }) { Text(if (t.status == TaskStatus.DONE) "Reabrir" else "Concluir") }
                if (t.status == TaskStatus.PENDING) FilledTonalButton(onClick = { vm.start(msg) }) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Iniciar")
                }
                if (t.status.isOpen) OutlinedButton(onClick = { pickPostpone = true }) {
                    Icon(Icons.Outlined.Snooze, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Adiar")
                }
            }
            if (t.status.isOpen) TextButton(onClick = vm::toggleMyDay) {
                Icon(Icons.Outlined.WbSunny, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(if (s.inMyDay) "Remover do Meu dia" else "Adicionar ao Meu dia")
            }

            s.score?.let { ScoreCard(it) }

            if (t.subtasks.isNotEmpty()) {
                FieldLabel("Subtarefas · ${t.subtasks.count { it.done }}/${t.subtasks.size}")
                t.subtasks.forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(st.done, { vm.toggleSubtask(st.id) })
                        Text(st.title, Modifier.weight(1f), textDecoration = if (st.done) TextDecoration.LineThrough else null)
                        IconButton(onClick = { vm.promote(st.id, msg) }) { Icon(Icons.AutoMirrored.Outlined.CallSplit, "Transformar em tarefa") }
                    }
                }
            }

            if (s.dependencies.isNotEmpty()) {
                FieldLabel("Depende de")
                s.dependencies.forEach { d ->
                    Text((if (d.status.isOpen) "○ " else "✓ ") + d.title, Modifier.fillMaxWidth().clickable { navigate(Routes.taskDetail(d.id)) }.padding(vertical = 6.dp))
                }
            }
            if (s.dependents.isNotEmpty()) {
                FieldLabel("Bloqueia")
                s.dependents.forEach { d -> Text("○ " + d.title, Modifier.fillMaxWidth().clickable { navigate(Routes.taskDetail(d.id)) }.padding(vertical = 6.dp)) }
            }

            if (s.history.isNotEmpty()) {
                FieldLabel("Histórico")
                val fmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
                s.history.take(20).forEach { h ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(h.timestamp.atZone(ZoneId.systemDefault()).format(fmt), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
                        Text(h.type.label + if (h.details.isNotBlank()) " · ${h.details}" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Criada em " + t.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        }
    }

    if (pickPostpone) {
        AlertDialog(
            onDismissRequest = { pickPostpone = false },
            title = { Text("Adiar para") },
            text = {
                Column {
                    val today = s.now.toLocalDate()
                    listOf("Amanhã" to today.plusDays(1), "Em 2 dias" to today.plusDays(2), "Próxima semana" to today.plusDays(7)).forEach { (l, d) ->
                        TextButton(onClick = { pickPostpone = false; vm.postpone(d, msg) }) { Text(l) }
                    }
                    var custom by remember { mutableStateOf(false) }
                    TextButton(onClick = { custom = true }) { Text("Escolher data…") }
                    if (custom) DatePickerModal(t?.dueDate, { custom = false }, { d -> pickPostpone = false; if (d != null) vm.postpone(d, msg) }, allowClear = false)
                }
            },
            confirmButton = { TextButton(onClick = { pickPostpone = false }) { Text("Cancelar") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir tarefa?") },
            text = { Text("Subtarefas e vínculos serão removidos. Você poderá desfazer logo em seguida.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete { deleted -> if (deleted != null) messenger.show("Tarefa excluída", "Desfazer") { vm.restore(deleted) } }
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScoreCard(score: PriorityScore) {
    Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Por que esta prioridade?", style = MaterialTheme.typography.titleSmall)
                    Text("${score.quadrant.label} — ${score.quadrant.hint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${score.score}", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
            }
            if (score.flags.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                score.flags.forEach { AssistChip({}, { Text(it.label, style = MaterialTheme.typography.labelSmall) }) }
            }
            Spacer(Modifier.height(8.dp))
            score.factors.sortedByDescending { it.points }.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(f.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text((if (f.points > 0) "+" else "") + f.points, style = MaterialTheme.typography.bodySmall,
                        color = if (f.points < 0) TaskFlowColors.critical else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
