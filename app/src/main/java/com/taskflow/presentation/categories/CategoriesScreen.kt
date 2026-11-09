package com.taskflow.presentation.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskflow.core.AppResult
import com.taskflow.domain.model.Category
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.usecase.SaveCategoryUseCase
import com.taskflow.navigation.Routes
import com.taskflow.presentation.components.CategoryIcons
import com.taskflow.presentation.components.LinearBar
import com.taskflow.presentation.components.LocalMessenger
import com.taskflow.presentation.components.asColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryStats(val category: Category, val open: Int, val done: Int)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repo: CategoryRepository,
    tasks: TaskRepository,
    private val save: SaveCategoryUseCase,
) : ViewModel() {
    val state: StateFlow<List<CategoryStats>> = combine(repo.observeAll(), tasks.observeAll()) { cats, all ->
        cats.map { c -> CategoryStats(c, all.count { it.categoryId == c.id && it.status.isOpen }, all.count { it.categoryId == c.id && it.status == TaskStatus.DONE }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(c: Category, onResult: (String?) -> Unit) = viewModelScope.launch {
        when (val r = save.invoke(c)) { is AppResult.Success -> onResult(null); is AppResult.Failure -> onResult(r.error.message) }
    }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit, navigate: (String) -> Unit, vm: CategoriesViewModel = hiltViewModel()) {
    val list by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Category?>(null) }
    val messenger = LocalMessenger.current

    Scaffold(topBar = {
        TopAppBar(title = { Text("Categorias") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } },
            actions = { IconButton(onClick = { editing = Category(name = "", colorArgb = CategoryIcons.palette.first(), icon = "star") }) { Icon(Icons.Outlined.Add, "Nova categoria") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            items(list, key = { it.category.id }) { st ->
                val c = st.category
                Row(Modifier.fillMaxWidth().clickable { navigate(Routes.tasks(category = c.id)) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.colorArgb.asColor().copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(CategoryIcons.of(c.icon), null, tint = c.colorArgb.asColor(), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        val total = st.open + st.done
                        Text("${st.open} abertas · ${st.done} concluídas", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (total > 0) LinearBar(st.done / total.toFloat(), Modifier.fillMaxWidth().padding(top = 6.dp), c.colorArgb.asColor())
                    }
                    IconButton(onClick = { editing = c }) { Icon(Icons.Outlined.Edit, "Editar ${c.name}") }
                }
            }
        }
    }

    editing?.let { initial ->
        var name by remember(initial) { mutableStateOf(initial.name) }
        var color by remember(initial) { mutableStateOf(initial.colorArgb) }
        var icon by remember(initial) { mutableStateOf(initial.icon) }
        var error by remember(initial) { mutableStateOf<String?>(null) }
        var confirmDelete by remember(initial) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (initial.id == 0L) "Nova categoria" else "Editar categoria") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it; error = null }, label = { Text("Nome") }, singleLine = true, isError = error != null,
                        supportingText = error?.let { e -> { Text(e) } })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryIcons.palette.forEach { p ->
                            Box(Modifier.size(28.dp).clip(CircleShape).background(p.asColor())
                                .then(if (p == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = p }.semantics { contentDescription = "Cor" })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CategoryIcons.all.forEach { (k, v) ->
                            IconButton(onClick = { icon = k }) {
                                Icon(v, k, tint = if (k == icon) color.asColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (initial.id != 0L) TextButton(onClick = { confirmDelete = true }) { Text("Excluir categoria", color = MaterialTheme.colorScheme.error) }
                    if (confirmDelete) Text("Toque em Excluir novamente para confirmar. As tarefas ficarão sem categoria.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                if (confirmDelete) TextButton(onClick = { vm.delete(initial.id); editing = null; messenger.show("Categoria excluída") }) { Text("Excluir") }
                else TextButton(onClick = { vm.save(initial.copy(name = name, colorArgb = color, icon = icon)) { e -> if (e == null) editing = null else error = e } }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}
