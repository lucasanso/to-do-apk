package com.taskflow.domain.usecase

import com.taskflow.core.AppResult
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.ExtractedTask
import com.taskflow.domain.engine.TaskFilters
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.Subtask
import com.taskflow.domain.model.Task
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.TaskExtractor
import javax.inject.Inject

class ExtractTasksUseCase @Inject constructor(private val extractor: TaskExtractor) {
    suspend operator fun invoke(text: String) = extractor.extract(text.take(MAX_INPUT_CHARS))

    companion object {
        /** ~1h of transcript. Larger inputs are truncated rather than freezing the phone. */
        const val MAX_INPUT_CHARS = 60_000
    }
}

/** Saves the tasks the user kept on the review screen. Returns how many were created. */
class SaveExtractedTasksUseCase @Inject constructor(
    private val save: SaveTaskUseCase,
    private val categories: CategoryRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(items: List<ExtractedTask>, sourceLabel: String?): AppResult<Int> {
        val cats = categories.getAll()
        var created = 0
        var lastError: AppResult.Failure? = null
        for (item in items) {
            val categoryId = item.categoryHint?.let { hint ->
                val n = TaskFilters.norm(hint)
                cats.firstOrNull { TaskFilters.norm(it.name).replace(" ", "").startsWith(n) }?.id
            }
            val description = buildString {
                if (item.source.isNotBlank() && item.source.trim() != item.title.trim()) append("“${item.source.trim()}”")
                if (!sourceLabel.isNullOrBlank()) { if (isNotEmpty()) append("\n\n"); append("Origem: $sourceLabel") }
            }
            val r = save(
                Task(
                    title = item.title,
                    description = description,
                    createdAt = time.now(),
                    dueDate = item.dueDate,
                    dueTime = item.dueTime,
                    priority = item.priority ?: Priority.MEDIUM,
                    estimatedMinutes = item.estimatedMinutes,
                    tags = item.tags,
                    categoryId = categoryId,
                    subtasks = item.subtasks.mapIndexed { i, s -> Subtask(title = s, position = i) },
                    reminderMinutesBefore = if (item.dueTime != null) 0 else null,
                )
            )
            when (r) { is AppResult.Success -> created++; is AppResult.Failure -> lastError = r }
        }
        return if (created == 0 && lastError != null) lastError!! else AppResult.Success(created)
    }
}
