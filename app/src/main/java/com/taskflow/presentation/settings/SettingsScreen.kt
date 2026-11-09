package com.taskflow.presentation.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskflow.core.AppResult
import com.taskflow.data.backup.BackupManager
import com.taskflow.data.settings.AppSettings
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.data.settings.ThemeMode
import com.taskflow.notifications.AlarmReminderScheduler
import com.taskflow.notifications.Notifier
import com.taskflow.notifications.ReminderSync
import com.taskflow.presentation.components.LocalMessenger
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.components.TimePickerModal
import com.taskflow.presentation.util.formatMinutes
import com.taskflow.presentation.util.hhmm
import com.taskflow.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val repo: SettingsRepository,
    private val backup: BackupManager,
    private val sync: ReminderSync,
    private val widgets: WidgetUpdater,
    val notifier: Notifier,
    val scheduler: AlarmReminderScheduler,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun update(f: (AppSettings) -> AppSettings) = viewModelScope.launch {
        repo.update(f)
        sync.schedulePeriodicWork()
    }

    fun export(uri: Uri, msg: (String) -> Unit) = viewModelScope.launch {
        runCatching {
            val json = backup.exportJson()
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } ?: error("Não foi possível abrir o arquivo")
            }
        }.onSuccess { msg("Backup exportado") }.onFailure { msg("Falha ao exportar: ${it.message}") }
    }

    fun import(uri: Uri, msg: (String) -> Unit) = viewModelScope.launch {
        val raw = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    val bytes = s.readBytes()
                    require(bytes.size <= 50 * 1024 * 1024) { "Arquivo grande demais" }
                    String(bytes)
                } ?: error("Não foi possível ler o arquivo")
            }
        }.getOrElse { msg("Falha ao ler: ${it.message}"); return@launch }
        when (val r = backup.importJson(raw)) {
            is AppResult.Success -> {
                sync.rescheduleAll(); widgets.updateAll()
                msg("Restaurado: ${r.value.tasks} tarefas, ${r.value.habits} hábitos, ${r.value.categories} categorias")
            }
            is AppResult.Failure -> msg("Backup rejeitado: ${r.error.message}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, requestNotificationPermission: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val messenger = LocalMessenger.current
    var pickSummaryTime by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.export(it) { m -> messenger.show(m) } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmImport = uri }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Configurações") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Aparência")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(s.themeMode == m, { vm.update { it.copy(themeMode = m) } }, SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(m.label) }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) ToggleRow("Cores do sistema (Material You)", "Usa a paleta do papel de parede", s.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }

            SectionHeader("Notificações")
            if (!vm.notifier.canPost()) ActionRow("Permitir notificações", "O Android está bloqueando as notificações do app", requestNotificationPermission)
            if (!vm.scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= 31) ActionRow("Lembretes pontuais", "Permita \"Alarmes e lembretes\" para disparos no minuto exato") {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }
            ToggleRow("Lembretes de tarefas", "Antes do prazo ou no horário programado", s.taskReminders) { v -> vm.update { it.copy(taskReminders = v) } }
            ToggleRow("Tarefas atrasadas", "Aviso quando algo passa do prazo", s.overdueAlerts) { v -> vm.update { it.copy(overdueAlerts = v) } }
            ToggleRow("Resumo diário", "Às ${LocalTime.of(s.dailySummaryTime / 60, s.dailySummaryTime % 60).hhmm()} · toque para alterar o horário", s.dailySummary,
                onClick = { pickSummaryTime = true }) { v -> vm.update { it.copy(dailySummary = v) } }
            ToggleRow("Lembretes de hábitos", "No horário definido em cada hábito", s.habitReminders) { v -> vm.update { it.copy(habitReminders = v) } }

            SectionHeader("Planejamento")
            StepperRow("Tempo disponível padrão", formatMinutes(s.defaultAvailableMinutes),
                { vm.update { it.copy(defaultAvailableMinutes = it.defaultAvailableMinutes - 30) } },
                { vm.update { it.copy(defaultAvailableMinutes = it.defaultAvailableMinutes + 30) } })
            StepperRow("Lembrete padrão ao definir prazo", if (s.defaultReminderMinutes == 0) "No horário" else "${formatMinutes(s.defaultReminderMinutes)} antes",
                { vm.update { it.copy(defaultReminderMinutes = (it.defaultReminderMinutes - 15).coerceAtLeast(0)) } },
                { vm.update { it.copy(defaultReminderMinutes = it.defaultReminderMinutes + 15) } })

            SectionHeader("Dados")
            ActionRow("Exportar backup (JSON)", "Salva todas as tarefas, hábitos, categorias e histórico em um arquivo") {
                exportLauncher.launch("taskflow-backup-${LocalDate.now()}.json")
            }
            ActionRow("Importar backup", "Substitui todos os dados atuais após validação do arquivo") {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
            Text("Seus dados ficam apenas neste dispositivo. O app não usa internet, contas ou analytics.",
                Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickSummaryTime) TimePickerModal(LocalTime.of(s.dailySummaryTime / 60, s.dailySummaryTime % 60), { pickSummaryTime = false }) { t ->
        if (t != null) vm.update { it.copy(dailySummaryTime = t.hour * 60 + t.minute, dailySummary = true) }
    }
    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Substituir dados?") },
            text = { Text("Todos os dados atuais serão substituídos pelo conteúdo do backup. Se o arquivo for inválido, nada será alterado.") },
            confirmButton = { TextButton(onClick = { confirmImport = null; vm.import(uri) { messenger.show(it) } }) { Text("Importar") } },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onClick: (() -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick?.invoke() ?: onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepperRow(title: String, value: String, minus: () -> Unit, plus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = minus) { Text("−") }
        TextButton(onClick = plus) { Text("+") }
    }
}
