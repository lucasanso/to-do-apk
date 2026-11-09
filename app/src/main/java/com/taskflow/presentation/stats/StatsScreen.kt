package com.taskflow.presentation.stats

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.ProductivityCalculator
import com.taskflow.domain.engine.ProductivityReport
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.PlanRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.Bar
import com.taskflow.presentation.components.BarChart
import com.taskflow.presentation.components.LinearBar
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.components.asColor
import com.taskflow.presentation.theme.TaskFlowColors
import com.taskflow.presentation.util.PtBr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.format.TextStyle
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class StatsViewModel @Inject constructor(tasks: TaskRepository, categories: CategoryRepository, plan: PlanRepository, time: TimeProvider) : ViewModel() {
    val report: StateFlow<ProductivityReport?> = combine(tasks.observeAll(), categories.observeAll(), plan.observeAll()) { t, c, p ->
        ProductivityCalculator.report(t, c, p, time.today(), time.zone())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, navigate: (String) -> Unit, vm: StatsViewModel = hiltViewModel()) {
    val r by vm.report.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Produtividade") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } })
    }) { pad ->
        val rep = r ?: return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kpi("${(rep.completionRate * 100).toInt()}%", "taxa de conclusão (14 dias)", Modifier.weight(1f))
                Kpi("${rep.completedPerDay.sumOf { it.count }}", "concluídas em 14 dias", Modifier.weight(1f))
            }
            val trend = rep.trendDelta
            Text(
                when {
                    rep.completedPerDay.all { it.count == 0 } -> "Conclua tarefas para ver sua evolução."
                    abs(trend) < 0.15 -> "Ritmo estável em relação à semana anterior."
                    trend > 0 -> "Você está concluindo %.1f tarefa(s)/dia a mais que na semana anterior.".format(PtBr, trend)
                    else -> "Você está concluindo %.1f tarefa(s)/dia a menos que na semana anterior.".format(PtBr, -trend)
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            SectionHeader("Concluídas por dia")
            BarChart(rep.completedPerDay.map { Bar(it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, PtBr), it.count.toFloat()) },
                Modifier.padding(horizontal = 16.dp))

            SectionHeader("Concluídas por semana")
            BarChart(rep.completedPerWeek.map { Bar("${it.weekStart.dayOfMonth}/${it.weekStart.monthValue}", it.count.toFloat()) },
                Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.tertiary)

            SectionHeader("Planejado × concluído (Meu dia)")
            if (rep.planVsDone.all { it.planned == 0 }) Text("Use \"Meu dia\" para comparar planejamento e execução.",
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                BarChart(rep.planVsDone.map { Bar(it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, PtBr), it.done.toFloat(), it.planned.toFloat()) },
                    Modifier.padding(horizontal = 16.dp))
                Text("Barra clara = planejadas · barra cheia = concluídas", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionHeader("Categorias")
            rep.categories.take(8).forEach { v ->
                Column(Modifier.fillMaxWidth().clickable(enabled = v.category != null) { navigate(Routes.tasks(category = v.category?.id)) }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(v.category?.colorArgb?.asColor() ?: TaskFlowColors.low))
                        Spacer(Modifier.width(8.dp))
                        Text(v.category?.name ?: "Sem categoria", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${v.done}/${v.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearBar(v.rate.toFloat(), Modifier.fillMaxWidth().padding(top = 6.dp), v.category?.colorArgb?.asColor() ?: TaskFlowColors.low)
                }
            }

            if (rep.mostPostponed.isNotEmpty()) {
                SectionHeader("Frequentemente adiadas")
                rep.mostPostponed.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { navigate(Routes.taskDetail(t.id)) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(t.title, Modifier.weight(1f), maxLines = 1)
                        Text("${t.postponeCount}x", color = TaskFlowColors.high, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text("Considere dividir essas tarefas em subtarefas menores ou reavaliar se ainda são necessárias.",
                    Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Kpi(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
