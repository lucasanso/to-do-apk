package com.taskflow.presentation.voice

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.taskflow.domain.usecase.ListedItem
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import com.taskflow.presentation.util.formatDue
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.domain.usecase.VoiceOutcome
import com.taskflow.domain.voice.CompletionCandidate
import com.taskflow.domain.voice.InterpretationSource
import com.taskflow.presentation.theme.TaskFlowColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One place to capture anything: speak (default) or type. Understands both
 * "reunião amanhã às 15h" (creates) and "já paguei a fatura" (completes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCaptureSheet(
    vm: VoiceViewModel,
    startWithVoice: Boolean,
    onDismiss: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenEditor: () -> Unit,
    initialText: String? = null,
    autoClose: Boolean = false,
    onOpenRoute: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val speech = remember { SpeechInput(context.applicationContext) }
    // Spoken answers: created on first use; only replies produced after this sheet opened are read.
    var voiceReply by remember { mutableStateOf<VoiceReply?>(null) }
    val reply by vm.reply.collectAsStateWithLifecycle()
    val firstReplyId = remember { vm.reply.value?.id ?: 0L }
    LaunchedEffect(reply) {
        val r = reply ?: return@LaunchedEffect
        if (r.id <= firstReplyId) return@LaunchedEffect
        // Same voice as chosen in Settings; a new choice recreates the reader.
        val current = voiceReply?.takeIf { it.prefs == r.voice } ?: run {
            voiceReply?.shutdown()
            VoiceReply(context, r.voice).also { voiceReply = it }
        }
        current.speak(r.text)
    }
    var typing by remember { mutableStateOf(!startWithVoice || initialText != null) }
    var text by remember { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val systemDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !said.isNullOrBlank()) vm.submit(said, fromVoice = true) else vm.idle()
    }

    fun listen() {
        // If the app was talking, give the audio a moment to be released before opening the microphone.
        val wasTalking = voiceReply?.isSpeaking() == true
        voiceReply?.stop()
        typing = false
        if (!speech.isAvailable()) {
            // No recognition service bound: use the system's speech dialog, or fall back to typing.
            try { systemDialog.launch(SpeechInput.recognizerIntent()) } catch (e: ActivityNotFoundException) {
                typing = true; vm.error("Reconhecimento de voz indisponível neste aparelho. Digite o comando.")
            }
            return
        }
        vm.listening("", 0f)
        speech.start(delayMs = if (wasTalking) 350 else 0) { e ->
            when (e) {
                SpeechInput.Event.Ready -> Unit
                is SpeechInput.Event.Level -> vm.listening("", e.rms)
                is SpeechInput.Event.Partial -> vm.listening(e.text, 0.3f)
                is SpeechInput.Event.Final -> vm.submit(e.text, fromVoice = true)
                is SpeechInput.Event.Error -> vm.error(e.message)
                SpeechInput.Event.Cancelled -> vm.idle()
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else { typing = true; vm.error("Sem acesso ao microfone. Você pode digitar o comando.") }
    }

    fun requestListen() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) {
        vm.idle()
        if (initialText != null) vm.submit(initialText) else if (!typing) requestListen()
    }
    DisposableEffect(Unit) { onDispose { speech.stop(); voiceReply?.shutdown() } }
    LaunchedEffect(ui) {
        val u = ui
        if (autoClose && u is VoiceUi.Done && !u.undone) { delay(3_500); onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = { speech.stop(); onDismiss() }, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp).imePadding().heightIn(min = 220.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(ui, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it::class }, label = "voice") { state ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (state) {
                        VoiceUi.Idle -> Hint(typing)
                        is VoiceUi.Listening -> Listening(state) { speech.finish() }
                        is VoiceUi.Thinking -> Thinking(state.text)
                        is VoiceUi.Done -> Result(state, vm, onOpenTask)
                        is VoiceUi.Choose -> Choose(state, vm.time.today()) { vm.choose(state.text, it, state.action) }
                        is VoiceUi.ConfirmDelete -> ConfirmDelete(state, vm.time.today(), onConfirm = vm::confirmDelete, onCancel = vm::idle)
                        is VoiceUi.Message -> Message(state) { vm.createInstead(it) }
                        is VoiceUi.Listing -> ListingView(state, vm.time.today(), onToggle = vm::toggleListed,
                            onOpenItem = { if (it.kind == CompletionCandidate.Kind.TASK) onOpenTask(it.id) },
                            onOpenAll = vm.routeFor(state.listing)?.let { route -> { onOpenRoute(route) } })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (typing) {
                val focus = remember { FocusRequester() }
                fun send() { if (text.isNotBlank()) { vm.submit(text); text = "" } }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { requestListen() }) { Icon(Icons.Outlined.Mic, "Falar") }
                    OutlinedTextField(
                        text, { text = it }, Modifier.weight(1f).focusRequester(focus),
                        placeholder = { Text("Ex.: dentista sexta às 14h") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    IconButton(onClick = { send() }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, "Enviar") }
                }
                LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { speech.stop(); vm.idle(); typing = true }) { Icon(Icons.Outlined.Keyboard, "Digitar") }
                    if (ui !is VoiceUi.Listening && ui !is VoiceUi.Thinking)
                        FilledIconButton(onClick = { requestListen() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Mic, "Falar de novo") }
                    TextButton(onClick = { speech.stop(); onOpenEditor() }) { Text("Formulário") }
                }
            }
            (ui as? VoiceUi.Done)?.let { d ->
                Text(if (d.source == InterpretationSource.ON_DEVICE_AI) "Entendido com Gemini Nano, no aparelho" else "Entendido no aparelho",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Hint(typing: Boolean) {
    Text(if (typing) "Digite o que precisa fazer" else "Toque no microfone e fale", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text("“Reunião amanhã às 15h por uma hora”\n“Já paguei a fatura” · “O que eu tenho hoje?”", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun Listening(state: VoiceUi.Listening, onStop: () -> Unit) {
    val level by animateFloatAsState(state.level, label = "level")
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(112.dp).scale(0.7f + 0.3f * level).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)))
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onStop)
                .semantics { contentDescription = "Parar e enviar" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Stop, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp)) }
    }
    Spacer(Modifier.height(12.dp))
    Text(state.partial.ifEmpty { "Ouvindo…" }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        color = if (state.partial.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun Thinking(text: String) {
    CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
    Spacer(Modifier.height(12.dp))
    Text("“$text”", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun Result(state: VoiceUi.Done, vm: VoiceViewModel, onOpenTask: (Long) -> Unit) {
    val o = state.outcome
    val (headline, title, detail, taskId) = when (o) {
        is VoiceOutcome.Created -> Quad("Tarefa criada", o.title, vm.summary(o), o.taskId)
        is VoiceOutcome.Completed -> Quad(
            if (o.candidate.kind == CompletionCandidate.Kind.HABIT) "Hábito feito hoje" else "Concluída",
            o.candidate.title, if (o.nextOccurrenceId != null) "Próxima ocorrência criada" else "",
            if (o.candidate.kind == CompletionCandidate.Kind.TASK) o.candidate.id else null)
        is VoiceOutcome.AlreadyDone -> Quad("Já estava concluída", o.candidate.title, "", null)
        is VoiceOutcome.Deleted -> Quad(if (o.candidate.kind == CompletionCandidate.Kind.HABIT) "Hábito apagado" else "Tarefa apagada",
            o.candidate.title, "", null)
        is VoiceOutcome.Failed -> Quad("Não deu certo", o.message, "", null)
    }
    Icon(if (o is VoiceOutcome.Deleted) Icons.Outlined.DeleteOutline else Icons.Outlined.CheckCircle, null,
        tint = if (state.undone) MaterialTheme.colorScheme.onSurfaceVariant else if (o is VoiceOutcome.Deleted) MaterialTheme.colorScheme.error else TaskFlowColors.success,
        modifier = Modifier.size(40.dp))
    Spacer(Modifier.height(8.dp))
    Text(if (state.undone) "Desfeito" else headline, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    if (!state.undone && (o is VoiceOutcome.Created || o is VoiceOutcome.Completed || o is VoiceOutcome.Deleted)) Row(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = vm::undo) { Text("Desfazer") }
        if (taskId != null && o is VoiceOutcome.Created) TextButton(onClick = { onOpenTask(taskId) }) { Text("Ajustar") }
    }
}

private data class Quad(val a: String, val b: String, val c: String, val d: Long?)

/** Due date or status, to tell similar items apart ("Reunião" today vs. "Reunião" next week). */
private fun detailOf(c: CompletionCandidate, today: java.time.LocalDate): String = listOfNotNull(
    if (c.kind == CompletionCandidate.Kind.HABIT) "hábito" else null,
    formatDue(c.dueDate, null, today),
    if (!c.open) (if (c.kind == CompletionCandidate.Kind.HABIT) "pausado" else "concluída") else null,
).joinToString(" · ")

@Composable
private fun Choose(state: VoiceUi.Choose, today: java.time.LocalDate, onPick: (CompletionCandidate) -> Unit) {
    Text(if (state.action == VoiceUi.Action.DELETE) "Qual delas você quer apagar?" else "Qual delas você concluiu?",
        style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    state.options.forEach { c ->
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onPick(c) }.padding(vertical = 14.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(detailOf(c, today), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConfirmDelete(state: VoiceUi.ConfirmDelete, today: java.time.LocalDate, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val c = state.candidate
    val habit = c.kind == CompletionCandidate.Kind.HABIT
    Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
    Spacer(Modifier.height(8.dp))
    Text(if (habit) "Apagar este hábito?" else "Apagar esta tarefa?", style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Text(c.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    val detail = detailOf(c, today)
    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (habit) Text("O histórico de dias feitos também será apagado.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel) { Text("Cancelar") }
        Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError)) { Text("Apagar") }
    }
}

@Composable
private fun Message(state: VoiceUi.Message, onCreate: (String) -> Unit) {
    state.text?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    Spacer(Modifier.height(8.dp))
    Text(state.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        modifier = Modifier.width(320.dp).semantics { liveRegion = LiveRegionMode.Polite })
    if (state.offerCreate && state.text != null) TextButton(onClick = { onCreate(state.text) }, modifier = Modifier.padding(top = 4.dp)) {
        Text("Criar como nova tarefa")
    }
}

@Composable
private fun ListingView(
    state: VoiceUi.Listing,
    today: java.time.LocalDate,
    onToggle: (ListedItem) -> Unit,
    onOpenItem: (ListedItem) -> Unit,
    onOpenAll: (() -> Unit)?,
) {
    val l = state.listing
    Text(l.query.label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(if (l.total == 0) l.spoken else "${l.total} ${if (l.total == 1) "item" else "itens"}",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite })
    Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        l.sections.forEach { sec ->
            if (l.sections.size > 1) Text(sec.title.uppercase(), style = com.taskflow.presentation.theme.SectionLabel,
                color = if (sec.title == "Atrasadas") TaskFlowColors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
            sec.items.take(MAX_ROWS).forEach { item -> ListedRow(item, onToggle, onOpenItem) }
            if (sec.items.size > MAX_ROWS) Text("+ ${sec.items.size - MAX_ROWS}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 44.dp, top = 4.dp))
        }
    }
    if (onOpenAll != null && l.total > 0) TextButton(onClick = onOpenAll, modifier = Modifier.padding(top = 4.dp)) { Text("Abrir na lista") }
}

private const val MAX_ROWS = 10

@Composable
private fun ListedRow(item: ListedItem, onToggle: (ListedItem) -> Unit, onOpenItem: (ListedItem) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenItem(item) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable { onToggle(item) }
                .semantics { contentDescription = if (item.done) "Desmarcar ${item.title}" else "Concluir ${item.title}" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape)
                .background(if (item.done) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                if (item.done) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (item.detail.isNotEmpty()) Text(item.detail, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
