package com.taskflow.presentation.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskflow.domain.model.HistoryEntry
import com.taskflow.domain.model.HistoryType
import com.taskflow.domain.repository.HistoryRepository
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.EmptyState
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.util.formatLongDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(repo: HistoryRepository) : ViewModel() {
    val entries: StateFlow<List<HistoryEntry>?> = repo.observeRecent(500).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, navigate: (String) -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val list by vm.entries.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    Scaffold(topBar = {
        TopAppBar(title = { Text("Histórico") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } })
    }) { pad ->
        val l = list ?: return@Scaffold
        if (l.isEmpty()) { EmptyState(Icons.Outlined.History, "Sem histórico", "Criações, conclusões e alterações aparecerão aqui.", Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.padding(pad)) {
            l.groupBy { it.timestamp.atZone(zone).toLocalDate() }.forEach { (day, entries) ->
                item(key = "h$day") { SectionHeader(formatLongDate(day)) }
                items(entries, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = e.taskId != null && e.type != HistoryType.DELETED) { navigate(Routes.taskDetail(e.taskId!!)) }
                        .padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(e.timestamp.atZone(zone).format(fmt), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                        Column {
                            Text(e.taskTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(e.type.label + if (e.details.isNotBlank()) " · ${e.details}" else "", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
