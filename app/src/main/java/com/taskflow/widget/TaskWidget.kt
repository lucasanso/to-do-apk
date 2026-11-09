package com.taskflow.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.taskflow.MainActivity
import com.taskflow.R
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.DueRules
import com.taskflow.domain.engine.PriorityEngine
import com.taskflow.domain.engine.TaskFlag
import com.taskflow.domain.model.Priority
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun tasks(): TaskRepository
    fun time(): TimeProvider
    fun setDone(): SetTaskDoneUseCase
}

@Singleton
class WidgetUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun updateAll() = runCatching { TaskWidget().updateAll(context) }.let { }
}

private data class WidgetRow(val id: Long, val title: String, val subtitle: String, val priority: Priority, val urgent: Boolean)

class TaskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val now = ep.time().nowDateTime()
        val rows = PriorityEngine.rank(ep.tasks().getAll(), now)
            .filter { TaskFlag.BLOCKED !in it.second.flags }.take(8)
            .map { (t, s) ->
                val due = t.dueDate?.let { d ->
                    when (d) { now.toLocalDate() -> "Hoje"; now.toLocalDate().plusDays(1) -> "Amanhã"; else -> d.format(DateTimeFormatter.ofPattern("dd/MM")) } +
                        (t.dueTime?.let { " ${it.format(DateTimeFormatter.ofPattern("HH:mm"))}" } ?: "")
                }
                val sub = listOfNotNull(if (DueRules.isOverdue(t, now)) "Atrasada" else due, s.quadrant.label).joinToString(" · ")
                WidgetRow(t.id, t.title, sub, t.priority, TaskFlag.URGENT in s.flags)
            }
        provideContent { GlanceTheme { Content(rows) } }
    }

    @Composable
    private fun Content(rows: List<WidgetRow>) {
        val ctx = androidx.glance.LocalContext.current
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Prioridades", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(Intent(ctx, MainActivity::class.java))))
                Image(ImageProvider(R.drawable.ic_add), "Nova tarefa", GlanceModifier.size(28.dp).clickable(
                    actionStartActivity(Intent(ctx, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_QUICK_ADD, true).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))))
            }
            Spacer(GlanceModifier.height(8.dp))
            if (rows.isEmpty()) {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tudo em dia ✓", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                }
            } else LazyColumn {
                items(rows, itemId = { it.id }) { r ->
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(ImageProvider(R.drawable.ic_check_circle), "Concluir ${r.title}",
                            GlanceModifier.size(26.dp).clickable(actionRunCallback<CompleteTaskAction>(actionParametersOf(CompleteTaskAction.KEY to r.id))))
                        Spacer(GlanceModifier.width(10.dp))
                        Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(
                            Intent(ctx, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_ROUTE, "task/${r.id}").setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)))) {
                            Text(r.title, maxLines = 1, style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface))
                            Text(r.subtitle, maxLines = 1, style = TextStyle(fontSize = 11.sp,
                                color = if (r.urgent) ColorProvider(Color(0xFFE5484D)) else GlanceTheme.colors.onSurfaceVariant))
                        }
                        Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(priorityColor(r.priority))) {}
                    }
                }
            }
        }
    }

    private fun priorityColor(p: Priority) = when (p) {
        Priority.LOW -> Color(0xFF9CA3AF); Priority.MEDIUM -> Color(0xFF3B82F6)
        Priority.HIGH -> Color(0xFFF59E0B); Priority.CRITICAL -> Color(0xFFE5484D)
    }
}

class CompleteTaskAction : ActionCallback {
    companion object { val KEY = ActionParameters.Key<Long>("taskId") }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[KEY] ?: return
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).setDone()(id, true)
        TaskWidget().updateAll(context)
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}
