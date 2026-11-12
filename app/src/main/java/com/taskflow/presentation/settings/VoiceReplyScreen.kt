package com.taskflow.presentation.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskflow.data.settings.AppSettings
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.presentation.components.SectionHeader
import com.taskflow.presentation.voice.EngineOption
import com.taskflow.presentation.voice.TtsCatalog
import com.taskflow.presentation.voice.VoiceLabels
import com.taskflow.presentation.voice.VoiceOption
import com.taskflow.presentation.voice.VoiceReply
import com.taskflow.presentation.voice.replyVoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class VoiceReplyUi(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val engines: List<EngineOption> = emptyList(),
    val defaultEngine: String? = null,
    /** Engine whose voices are listed (the chosen one, or the default if the chosen one is gone). */
    val engineInUse: String? = null,
    val voices: List<VoiceOption> = emptyList(),
    /** What the last preview actually used. */
    val playing: VoiceReply.Status? = null,
)

@HiltViewModel
class VoiceReplyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _ui = MutableStateFlow(VoiceReplyUi())
    val ui: StateFlow<VoiceReplyUi> = _ui
    private val catalog = TtsCatalog(context)
    private var preview: VoiceReply? = null

    init { reload() }

    fun reload() = viewModelScope.launch {
        _ui.update { it.copy(loading = true) }
        val snap = catalog.load(repo.current().ttsEngine)
        _ui.update {
            it.copy(loading = false, loadFailed = !snap.ok, engines = snap.engines, defaultEngine = snap.defaultEngine,
                engineInUse = snap.engine, voices = VoiceLabels.options(snap.voices))
        }
    }

    fun selectEngine(pkg: String?) = viewModelScope.launch {
        repo.update { it.copy(ttsEngine = pkg, ttsVoice = null) }
        reload().join()
        play()
    }

    fun selectVoice(option: VoiceOption?) = viewModelScope.launch {
        repo.update { it.copy(ttsVoice = option?.name) }
        play()
    }

    /** Saved when the slider is released, then the sample plays with the new value. */
    fun setRate(v: Float) = viewModelScope.launch { repo.update { it.copy(ttsRate = v) }; play() }
    fun setPitch(v: Float) = viewModelScope.launch { repo.update { it.copy(ttsPitch = v) }; play() }
    fun resetAdjustments() = viewModelScope.launch { repo.update { it.copy(ttsRate = null, ttsPitch = null) }; play() }

    /** Plays the sample with exactly the settings that replies will use. */
    fun preview() = viewModelScope.launch { play() }

    private suspend fun play() {
        val prefs = repo.current().replyVoice()
        val reply = preview?.takeIf { it.prefs == prefs } ?: run {
            preview?.shutdown()
            VoiceReply(context, prefs).also { r ->
                r.onStatus = { st -> _ui.update { it.copy(playing = st) } }
                preview = r
            }
        }
        _ui.update { it.copy(playing = reply.status) }
        reply.speak(VoiceReply.SAMPLE)
    }

    override fun onCleared() { preview?.shutdown() }
}

/** Opens the engine's "install voice data" screen, or the phone's text-to-speech settings. */
fun openVoiceInstall(context: Context, engine: String?) {
    val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { engine?.let(::setPackage) }
    try { context.startActivity(install) } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceReplyScreen(onBack: () -> Unit, vm: VoiceReplyViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val labelOf = { name: String? -> ui.voices.firstOrNull { it.name == name }?.label }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Voz das respostas") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                            val p = ui.playing
                            Text(when (p?.state) {
                                null -> "Toque para ouvir"
                                VoiceReply.State.STARTING -> "Preparando a voz…"
                                VoiceReply.State.READY -> "Tocando: " + (labelOf(p.voiceName) ?: if (s.ttsVoice == null) "padrão em português" else p.voiceName ?: "voz escolhida")
                                VoiceReply.State.NO_PORTUGUESE -> "Este mecanismo não tem voz em português"
                                VoiceReply.State.FAILED -> "Não foi possível iniciar o leitor de voz"
                            }, style = MaterialTheme.typography.titleSmall)
                            val note = when {
                                p?.fellBack == true -> "A voz escolhida não está disponível agora; tocando a voz padrão em português."
                                p?.state == VoiceReply.State.NO_PORTUGUESE -> "Baixe uma voz em português abaixo ou escolha outro mecanismo."
                                else -> "As respostas faladas usam exatamente esta voz."
                            }
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = vm::preview) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Ouvir")
                        }
                    }
                }
            }

            item { SectionHeader("Mecanismo de voz") }
            val defaultLabel = ui.engines.firstOrNull { it.packageName == ui.defaultEngine }?.label
            item {
                RadioRow("Padrão do celular", defaultLabel ?: "O mesmo das configurações do aparelho", s.ttsEngine == null) { vm.selectEngine(null) }
            }
            items(ui.engines, key = { it.packageName }) { e ->
                RadioRow(e.label, if (e.packageName == ui.defaultEngine) "padrão do celular" else e.packageName, s.ttsEngine == e.packageName) {
                    vm.selectEngine(e.packageName)
                }
            }
            if (s.ttsEngine != null && ui.engines.isNotEmpty() && ui.engines.none { it.packageName == s.ttsEngine }) item {
                Text("O mecanismo escolhido não está mais instalado; usando o padrão do celular.", Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            item { SectionHeader("Voz em português") }
            when {
                ui.loading -> item { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
                ui.loadFailed -> item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text("Não foi possível ler as vozes deste mecanismo.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.reload() }) { Text("Tentar de novo") }
                    }
                }
                else -> {
                    item { RadioRow("Padrão do mecanismo", "a voz em português escolhida nas configurações do celular", s.ttsVoice == null) { vm.selectVoice(null) } }
                    var lastRegion: String? = null
                    ui.voices.forEach { v ->
                        if (v.region != lastRegion && ui.voices.any { it.region != v.region }) {
                            lastRegion = v.region
                            item(key = "r-" + v.region) { Text(v.region, Modifier.padding(start = 20.dp, top = 12.dp), style = MaterialTheme.typography.labelLarge) }
                        }
                        item(key = v.name) {
                            RadioRow(v.label, v.detail, s.ttsVoice == v.name, dim = !v.usable) {
                                if (v.usable) vm.selectVoice(v) else openVoiceInstall(context, ui.engineInUse)
                            }
                        }
                    }
                    if (ui.voices.isEmpty()) item {
                        Text("Nenhuma voz em português neste mecanismo.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                TextButton(onClick = { openVoiceInstall(context, ui.engineInUse) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Baixar mais vozes") }
            }

            item { SectionHeader("Velocidade e tom") }
            item {
                AdjustSlider("Velocidade", s.ttsRate, onCommit = { vm.setRate(it) })
                AdjustSlider("Tom", s.ttsPitch, onCommit = { vm.setPitch(it) })
                if (s.ttsRate != null || s.ttsPitch != null) TextButton(onClick = { vm.resetAdjustments() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Usar velocidade e tom do celular")
                }
            }
            item {
                Text("Vozes “no aparelho” funcionam sem internet. Vozes online enviam o texto lido ao Google ou à Samsung.",
                    Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RadioRow(title: String, detail: String, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float?, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value ?: 1f) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (value == null && local == 1f) "do celular" else String.format(Locale.forLanguageTag("pt-BR"), "%.1fx", local),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(local, { local = Math.round(it * 10) / 10f }, valueRange = 0.5f..2f, steps = 14,
            onValueChangeFinished = { onCommit(local) })
    }
}
