package com.taskflow.presentation.quickadd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.QuickCaptureParser
import com.taskflow.domain.usecase.QuickAddTaskUseCase
import com.taskflow.presentation.util.formatDue
import com.taskflow.presentation.util.formatMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val quickAdd: QuickAddTaskUseCase,
    val time: TimeProvider,
) : ViewModel() {
    fun submit(text: String, defaultDate: LocalDate?, onResult: (String?) -> Unit) = viewModelScope.launch {
        when (val r = quickAdd(text, defaultDate)) {
            is AppResult.Success -> onResult(null)
            is AppResult.Failure -> onResult(r.error.message)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    vm: QuickAddViewModel,
    defaultDate: LocalDate?,
    onDismiss: () -> Unit,
    onOpenFullEditor: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = vm.time.today()
    val preview = remember(text) { QuickCaptureParser.parse(text, today) }

    fun submit(keepOpen: Boolean) {
        if (text.isBlank()) return
        vm.submit(text, defaultDate) { err ->
            if (err == null) {
                onMessage("Tarefa criada")
                text = ""
                if (!keepOpen) onDismiss()
            } else error = err
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).imePadding()) {
            Text("Nova tarefa", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Ex.: Reunião com o João sexta às 15h, levar contrato") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "Escreva natural: “dia 12 às 3 da tarde, urgente” · #tag · @categoria") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit(keepOpen = true) }),
                )
                FilledIconButton(onClick = { submit(keepOpen = false) }, enabled = text.isNotBlank(), modifier = Modifier.padding(start = 8.dp, bottom = 20.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Adicionar")
                }
            }
            val chips = buildList {
                (preview.dueDate ?: defaultDate)?.let { add(formatDue(it, preview.dueTime, today) ?: "") }
                preview.priority?.let { add("Prioridade ${it.label.lowercase()}") }
                preview.estimatedMinutes?.let { add(formatMinutes(it)) }
                preview.categoryHint?.let { add("@$it") }
                preview.tags.forEach { add("#$it") }
                if (preview.subtasks.isNotEmpty()) add("${preview.subtasks.size} subtarefa(s)")
            }
            if (chips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.take(5).forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
            }
            Row {
                TextButton(onClick = onOpenFullEditor) { Text("Abrir editor completo") }
                TextButton(onClick = {
                    context.startActivity(com.taskflow.presentation.capture.CaptureActivity.intent(context, text))
                    onDismiss()
                }) { Text("✨ Várias tarefas") }
            }
        }
        // Inside the sheet's own composition, so the text field is attached before focusing.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(150)
            runCatching { focus.requestFocus() }
        }
    }
}
