package com.taskflow.presentation.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskflow.data.settings.AppSettings
import com.taskflow.domain.repository.AiDownload
import com.taskflow.domain.repository.OnDeviceAi
import com.taskflow.domain.repository.OnDeviceAiStatus
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.voice.SpeechInput
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceSettingsState(
    val aiStatus: OnDeviceAiStatus? = null,
    val modelName: String? = null,
    val downloadProgress: Float? = null,
    val downloading: Boolean = false,
    val aiMessage: String? = null,
    val launcherEnabled: Boolean = true,
    val speechEngine: String = "",
    val offlinePack: String? = null,
)

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ai: OnDeviceAi,
) : ViewModel() {
    private val _state = MutableStateFlow(VoiceSettingsState())
    val state: StateFlow<VoiceSettingsState> = _state

    private val launcher get() = ComponentName(context.packageName, "com.taskflow.VoiceLauncher")

    fun refresh() = viewModelScope.launch {
        val enabled = context.packageManager.getComponentEnabledSetting(launcher) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val onDevice = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val engine = when {
            onDevice -> "Reconhecimento no aparelho disponível"
            SpeechInput(context).isAvailable() -> "Usando o serviço de voz do sistema"
            else -> "Nenhum serviço de reconhecimento encontrado"
        }
        _state.update { it.copy(launcherEnabled = enabled, speechEngine = engine) }
        if (onDevice) checkOfflinePack()
        val status = ai.status()
        _state.update { it.copy(aiStatus = status, modelName = if (status == OnDeviceAiStatus.AVAILABLE) ai.modelName() else null) }
    }

    fun setLauncher(enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(launcher,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        _state.update { it.copy(launcherEnabled = enabled) }
    }

    fun downloadModel() = viewModelScope.launch {
        _state.update { it.copy(downloading = true, aiMessage = null, downloadProgress = null) }
        ai.download().collect { d ->
            when (d) {
                is AiDownload.Progress -> _state.update { it.copy(downloadProgress = d.fraction) }
                AiDownload.Done -> { _state.update { it.copy(downloading = false, aiMessage = "Modelo pronto") }; refresh() }
                is AiDownload.Failed -> _state.update { it.copy(downloading = false, aiMessage = d.message) }
            }
        }
    }

    /** Android 13+: ask the on-device recognizer which languages are installed. Main thread only. */
    private fun checkOfflinePack() {
        if (Build.VERSION.SDK_INT < 33) return
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return
        runCatching {
            r.checkRecognitionSupport(SpeechInput.recognizerIntent(), ContextCompat.getMainExecutor(context), object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    fun List<String>.hasPt() = any { it.startsWith("pt", ignoreCase = true) }
                    val label = when {
                        s.installedOnDeviceLanguages.hasPt() -> "Português instalado: voz funciona offline"
                        s.pendingOnDeviceLanguages.hasPt() -> "Baixando Português para uso offline…"
                        s.supportedOnDeviceLanguages.hasPt() -> "Português disponível para baixar (offline)"
                        else -> "Português offline não suportado; será usado o serviço de voz do sistema"
                    }
                    _state.update { it.copy(offlinePack = label) }
                    r.destroy()
                }
                override fun onError(error: Int) { r.destroy() }
            })
        }.onFailure { r.destroy() }
    }

    fun downloadOfflinePack() {
        if (Build.VERSION.SDK_INT < 33) return
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return
        runCatching { r.triggerModelDownload(SpeechInput.recognizerIntent()) }
        _state.update { it.copy(offlinePack = "Download solicitado ao sistema") }
        r.destroy()
    }
}

@Composable
fun VoiceSettingsSection(s: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit, vm: VoiceSettingsViewModel = hiltViewModel()) {
    val v by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    SectionHeader("Voz e IA")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gemini Nano (IA do aparelho)", style = MaterialTheme.typography.bodyLarge)
                Text(when (v.aiStatus) {
                    null -> "Verificando…"
                    OnDeviceAiStatus.AVAILABLE -> "Ativo" + (v.modelName?.let { " · $it" } ?: "") + " · roda no aparelho, sem internet"
                    else -> v.aiStatus!!.label + if (v.aiStatus == OnDeviceAiStatus.UNAVAILABLE) " — os comandos usam o interpretador local" else ""
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.useOnDeviceAi, { b -> onUpdate { it.copy(useOnDeviceAi = b) } }, enabled = v.aiStatus != OnDeviceAiStatus.UNAVAILABLE)
        }
        if (v.aiStatus == OnDeviceAiStatus.DOWNLOADABLE && !v.downloading) TextButton(onClick = vm::downloadModel) { Text("Baixar modelo (feito pelo sistema)") }
        if (v.downloading) LinearProgressIndicator(progress = { v.downloadProgress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        v.aiMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Row(Modifier.fillMaxWidth().clickable { onUpdate { it.copy(voiceFirst = !s.voiceFirst) } }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Microfone primeiro", style = MaterialTheme.typography.bodyLarge)
            Text("O botão de captura já começa ouvindo; desligado, abre o teclado", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(s.voiceFirst, { b -> onUpdate { it.copy(voiceFirst = b) } })
    }
    Row(Modifier.fillMaxWidth().clickable { onUpdate { it.copy(speakReplies = !s.speakReplies) } }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Responder em voz alta", style = MaterialTheme.typography.bodyLarge)
            Text("Quando você fala, o app lê a resposta (por exemplo, a lista de hoje) com a voz do aparelho", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(s.speakReplies, { b -> onUpdate { it.copy(speakReplies = b) } })
    }
    Row(Modifier.fillMaxWidth().clickable { vm.setLauncher(!v.launcherEnabled) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Atalho “TaskFlow Voz”", style = MaterialTheme.typography.bodyLarge)
            Text("Ícone separado que abre direto no microfone. Use no botão lateral (Configurações › Recursos avançados › Botão lateral › Pressionar duas vezes › Abrir app) ou diga ao Bixby/Gemini “abrir TaskFlow Voz”.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(v.launcherEnabled, vm::setLauncher)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Reconhecimento de voz", style = MaterialTheme.typography.bodyLarge)
        Text(listOfNotNull(v.speechEngine.ifEmpty { null }, v.offlinePack).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (v.offlinePack?.startsWith("Português disponível") == true) TextButton(onClick = vm::downloadOfflinePack) { Text("Baixar Português offline") }
    }
}
