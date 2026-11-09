package com.taskflow.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskflow.domain.engine.DueRules
import com.taskflow.domain.engine.PriorityScore
import com.taskflow.domain.engine.TaskFlag
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Task
import com.taskflow.domain.model.TaskStatus
import com.taskflow.presentation.theme.TaskFlowColors
import com.taskflow.presentation.util.formatDue
import com.taskflow.presentation.util.formatMinutes
import java.time.LocalDateTime

data class TaskUi(val task: Task, val score: PriorityScore?, val category: Category?)

@Composable
fun PriorityCheck(task: Task, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val color = TaskFlowColors.priority(task.priority)
    val done = task.status == TaskStatus.DONE
    val haptic = LocalHapticFeedback.current
    val bg by animateColorAsState(if (done) color else Color.Transparent, label = "check")
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggle() }
            .semantics { contentDescription = if (done) "Reabrir tarefa" else "Concluir tarefa" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(bg)
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            else if (task.status == TaskStatus.IN_PROGRESS) Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
fun TaskMeta(ui: TaskUi, now: LocalDateTime, showScore: Boolean) {
    val t = ui.task
    val overdue = DueRules.isOverdue(t, now)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        formatDue(t.dueDate, t.dueTime, now.toLocalDate())?.let {
            MetaItem(Icons.Outlined.Schedule, it, if (overdue) TaskFlowColors.critical else muted)
        }
        ui.category?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.colorArgb.asColor()))
                Spacer(Modifier.width(4.dp))
                Text(c.name, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (t.subtasks.isNotEmpty()) MetaItem(Icons.Outlined.Checklist, "${t.subtasks.count { it.done }}/${t.subtasks.size}", muted)
        t.estimatedMinutes?.let { Text(formatMinutes(it), style = MaterialTheme.typography.labelSmall, color = muted) }
        if (t.recurrence != null) Icon(Icons.Outlined.Repeat, "Recorrente", tint = muted, modifier = Modifier.size(13.dp))
        if (ui.score?.flags?.contains(TaskFlag.BLOCKED) == true) Icon(Icons.Outlined.Lock, "Bloqueada", tint = muted, modifier = Modifier.size(13.dp))
        if (ui.score?.flags?.contains(TaskFlag.PROCRASTINATED) == true) Icon(Icons.Outlined.Snooze, "Adiada com frequência", tint = TaskFlowColors.high, modifier = Modifier.size(13.dp))
        if (showScore && ui.score != null && t.status.isOpen) {
            Spacer(Modifier.weight(1f))
            Text("${ui.score.score}", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun MetaItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
fun TaskRow(
    ui: TaskUi,
    now: LocalDateTime,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    showScore: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = ui.task
    val done = t.status == TaskStatus.DONE
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick = onClick).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityCheck(t, onToggle)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            TaskMeta(ui, now, showScore)
        }
        trailing?.invoke()
    }
}

/** Swipe right = complete, swipe left = postpone to tomorrow. The row is not dismissed; data changes drive the list. */
@Composable
fun SwipeableTaskRow(
    ui: TaskUi,
    now: LocalDateTime,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onPostpone: () -> Unit,
    modifier: Modifier = Modifier,
    showScore: Boolean = false,
) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> onToggle()
            SwipeToDismissBoxValue.EndToStart -> if (ui.task.status.isOpen) onPostpone()
            else -> Unit
        }
        false
    })
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromEndToStart = ui.task.status.isOpen,
        backgroundContent = {
            val dir = state.dismissDirection
            val (color, icon, align) = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(TaskFlowColors.success, Icons.Outlined.Check, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(TaskFlowColors.high, Icons.Outlined.Snooze, Alignment.CenterEnd)
                else -> Triple(Color.Transparent, Icons.Outlined.Check, Alignment.Center)
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp), contentAlignment = align) {
                if (dir != SwipeToDismissBoxValue.Settled) Icon(icon, null, tint = Color.White)
            }
        },
    ) {
        TaskRow(ui, now, onClick, onToggle, showScore = showScore)
    }
}
