package com.taskflow.presentation.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskflow.data.settings.AppSettings
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.shake.ShakeController
import com.taskflow.shake.ShakeDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShakeStatus(val canOpenDirectly: Boolean = false, val batteryUnrestricted: Boolean = false)

@HiltViewModel
class ShakeSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _status = MutableStateFlow(ShakeStatus())
    val status: StateFlow<ShakeStatus> = _status

    /** Re-read the permissions (the user may come back from the system screens). */
    fun refresh() {
        _status.value = ShakeStatus(ShakeController.canOpenDirectly(context), ShakeController.isBatteryUnrestricted(context))
    }

    /** Saves first, then starts/stops the service, so the service never reads the old value and quits. */
    fun setEnabled(on: Boolean) = viewModelScope.launch {
        repo.update { it.copy(shakeToTalk = on) }
        ShakeController.sync(context, repo.current())
    }

    fun setSensitivity(level: Int) = viewModelScope.launch { repo.update { it.copy(shakeSensitivity = level) } }
}

@Composable
fun ShakeSettingsSection(vm: ShakeSettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }

    SectionHeader("Balançar para falar")
    Row(Modifier.fillMaxWidth().clickable { vm.setEnabled(!s.shakeToTalk) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Balançar para falar", style = MaterialTheme.typography.bodyLarge)
            Text("Balance o celular, com a tela desbloqueada, para abrir o microfone do TaskFlow de qualquer app, sem abrir o TaskFlow.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(s.shakeToTalk, { vm.setEnabled(it) })
    }
    if (!s.shakeToTalk) return

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Sensibilidade", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShakeDetector.Sensitivity.entries.forEachIndexed { i, level ->
                FilterChip(s.shakeSensitivity == i, { vm.setSensitivity(i) }, label = { Text(level.label.substringBefore(" (")) })
            }
        }
    }
    PermissionRow(
        title = "Abrir o microfone direto",
        ok = status.canOpenDirectly,
        okText = "Permitido: o balanço abre o microfone na hora",
        missingText = "Permita “Aparecer sobre outros apps”. Sem isso, o balanço mostra um aviso no topo da tela e você toca nele para falar.",
        action = "Permitir",
    ) { context.startActivity(ShakeController.overlaySettingsIntent(context)) }
    PermissionRow(
        title = "Funcionar com o app fechado",
        ok = status.batteryUnrestricted,
        okText = "Bateria sem restrições para o TaskFlow",
        missingText = "Permita uso em segundo plano para o Samsung não desligar o recurso para economizar bateria.",
        action = "Permitir",
    ) { runCatching { context.startActivity(ShakeController.batteryIntent(context)) } }
    Text("Funciona com a tela ligada e o celular desbloqueado (não dispara no bolso nem durante ligações). " +
        "Enquanto estiver ativo, fica um aviso discreto na barra de notificações, exigência do Android para usar o sensor com o app fechado.",
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PermissionRow(title: String, ok: Boolean, okText: String, missingText: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(if (ok) "✓ $okText" else missingText, style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) FilledTonalButton(onClick = onAction, modifier = Modifier.padding(start = 8.dp)) { Text(action) }
    }
}
