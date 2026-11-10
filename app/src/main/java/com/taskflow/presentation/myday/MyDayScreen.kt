package com.taskflow.presentation.myday

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.domain.model.TaskStatus
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.EmptyState
import com.taskflow.presentation.components.LinearBar
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.components.TaskRow
import com.taskflow.presentation.components.TaskUi
import com.taskflow.presentation.theme.TaskFlowColors
import com.taskflow.presentation.util.formatLongDate
import com.taskflow.presentation.util.formatMinutes

@Composable
fun MyDayScreen(navigate: (String) -> Unit, onBack: () -> Unit, vm: MyDayViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var editAvailable by remember { mutableStateOf(false) }

    // Local order that follows the DB but can be mutated during a drag.
    val order = remember { mutableStateListOf<TaskUi>() }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(s.planned) { if (draggingId == null) { order.clear(); order.addAll(s.planned) } }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    fun move(from: Int, to: Int) {
        if (from == to || to !in order.indices) return
        order.add(to, order.removeAt(from))
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            Row(Modifier.padding(start = 4.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") }
                Column(Modifier.padding(start = 4.dp)) {
                    Text("Meu dia", style = MaterialTheme.typography.headlineSmall)
                    Text(formatLongDate(s.date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                onClick = { editAvailable = true }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${formatMinutes(s.plannedMinutes)} planejadas", style = MaterialTheme.typography.titleMedium)
                            Text("de ${formatMinutes(s.availableMinutes)} disponíveis · toque para ajustar", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${s.doneCount}/${s.planned.size}", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearBar(s.plannedMinutes / s.availableMinutes.toFloat(), Modifier.fillMaxWidth(),
                        color = if (s.overloaded) TaskFlowColors.critical else MaterialTheme.colorScheme.primary)
                    if (s.overloaded) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WarningAmber, null, tint = TaskFlowColors.critical)
                        Spacer(Modifier.width(6.dp))
                        Text("Plano excede seu tempo em ${formatMinutes(s.overloadMinutes)}. Remova ou adie algo.",
                            style = MaterialTheme.typography.bodySmall, color = TaskFlowColors.critical)
                    }
                    if (s.unestimated > 0) Text("${s.unestimated} tarefa(s) sem duração estimada não entram no cálculo.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        if (order.isEmpty()) item {
            EmptyState(Icons.Outlined.WbSunny, "Planeje seu dia", "Escolha tarefas abaixo ou use o planejamento automático.")
        } else item {
            SectionHeader("Ordem de execução", action = { Text("segure e arraste", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) })
        }
        items(order, key = { it.task.id }) { ui ->
            val id = ui.task.id
            val dragging = draggingId == id
            val index = order.indexOf(ui)
            val moveUp = CustomAccessibilityAction("Mover para cima") { move(index, index - 1); vm.reorder(order.map { it.task.id }); true }
            val moveDown = CustomAccessibilityAction("Mover para baixo") { move(index, index + 1); vm.reorder(order.map { it.task.id }); true }
            Row(
                (if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }.shadow(8.dp) else Modifier.animateItem())
                    .semantics { customActions = listOf(moveUp, moveDown) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DragIndicator, "Arrastar para reordenar", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); draggingId = id; dragOffset = 0f },
                            onDragEnd = { draggingId = null; dragOffset = 0f; vm.reorder(order.map { it.task.id }) },
                            onDragCancel = { draggingId = null; dragOffset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val info = listState.layoutInfo.visibleItemsInfo
                                val cur = info.firstOrNull { it.key == id } ?: return@detectDragGesturesAfterLongPress
                                val center = cur.offset + dragOffset + cur.size / 2
                                val target = info.firstOrNull { it.key is Long && it.key != id && center.toInt() in it.offset..(it.offset + it.size) }
                                    ?: return@detectDragGesturesAfterLongPress
                                val from = order.indexOfFirst { it.task.id == id }
                                val to = order.indexOfFirst { it.task.id == target.key }
                                if (from >= 0 && to >= 0) {
                                    move(from, to)
                                    dragOffset += (cur.offset - target.offset).toFloat()
                                }
                            },
                        )
                    })
                TaskRow(ui, s.now, onClick = { navigate(Routes.taskDetail(id)) }, onToggle = { vm.toggle(id, ui.task.status != TaskStatus.DONE) },
                    modifier = Modifier.weight(1f), trailing = {
                        if (ui.task.status == TaskStatus.PENDING) IconButton(onClick = { vm.start(id) }) { Icon(Icons.Outlined.PlayArrow, "Iniciar") }
                        IconButton(onClick = { vm.remove(id) }) { Icon(Icons.Outlined.Close, "Remover do dia") }
                    })
            }
        }
        item {
            SectionHeader("Sugestões", action = {
                if (s.suggestions.isNotEmpty()) TextButton(onClick = vm::autoPlan) {
                    Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(4.dp)); Text("Planejar automaticamente")
                }
            })
        }
        if (s.suggestions.isEmpty()) item {
            Text("Sem tarefas abertas para sugerir.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(s.suggestions, key = { "s" + it.task.id }) { ui ->
            TaskRow(ui, s.now, onClick = { navigate(Routes.taskDetail(ui.task.id)) }, onToggle = { vm.toggle(ui.task.id, true) },
                modifier = Modifier.animateItem(),
                trailing = { IconButton(onClick = { vm.add(ui.task.id) }) { Icon(Icons.Outlined.Add, "Adicionar ao dia") } })
        }
        item { Spacer(Modifier.height(96.dp)) }
    }

    if (editAvailable) {
        var v by remember { mutableFloatStateOf(s.availableMinutes.toFloat()) }
        AlertDialog(
            onDismissRequest = { editAvailable = false },
            title = { Text("Tempo disponível hoje") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatMinutes((v / 15).toInt() * 15), style = MaterialTheme.typography.headlineSmall)
                    Slider(v, { v = it }, valueRange = 15f..(16 * 60f), steps = (16 * 60 - 15) / 15 - 1)
                }
            },
            confirmButton = { TextButton(onClick = { vm.setAvailable((v / 15).toInt() * 15); editAvailable = false }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { editAvailable = false }) { Text("Cancelar") } },
        )
    }
}
