package com.taskflow.domain.engine

import com.taskflow.domain.model.Category
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.Task
import com.taskflow.domain.model.TaskStatus
import java.text.Normalizer
import java.time.LocalDateTime

enum class SmartFilter(val label: String) {
    ALL("Todas"), TODAY("Hoje"), TOMORROW("Amanhã"), THIS_WEEK("Esta semana"), OVERDUE("Atrasadas"),
    NO_DATE("Sem prazo"), HIGH_PRIORITY("Alta prioridade"), DONE("Concluídas"), ARCHIVED("Arquivadas"),
}

object TaskFilters {
    fun apply(tasks: List<Task>, filter: SmartFilter, now: LocalDateTime): List<Task> {
        val today = now.toLocalDate()
        return tasks.filter { t ->
            when (filter) {
                SmartFilter.ALL -> t.status.isOpen
                SmartFilter.TODAY -> t.status.isOpen && t.dueDate == today
                SmartFilter.TOMORROW -> t.status.isOpen && t.dueDate == today.plusDays(1)
                SmartFilter.THIS_WEEK -> t.status.isOpen && t.dueDate != null && DueRules.isThisWeek(t.dueDate, today)
                SmartFilter.OVERDUE -> DueRules.isOverdue(t, now)
                SmartFilter.NO_DATE -> t.status.isOpen && t.dueDate == null
                SmartFilter.HIGH_PRIORITY -> t.status.isOpen && t.priority >= Priority.HIGH
                SmartFilter.DONE -> t.status == TaskStatus.DONE
                SmartFilter.ARCHIVED -> t.status == TaskStatus.ARCHIVED
            }
        }
    }

    /** Accent/case-insensitive search across title, description, category name and tags. */
    fun search(tasks: List<Task>, query: String, categories: Map<Long, Category>): List<Task> {
        val q = norm(query.trim())
        if (q.isEmpty()) return tasks
        val terms = q.split(Regex("\\s+"))
        return tasks.filter { t ->
            val haystack = norm(buildString {
                append(t.title).append(' ').append(t.description).append(' ')
                t.categoryId?.let(categories::get)?.let { append(it.name).append(' ') }
                t.tags.forEach { append('#').append(it).append(' ') }
                t.subtasks.forEach { append(it.title).append(' ') }
            })
            terms.all { haystack.contains(it.removePrefix("#")) }
        }
    }

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
