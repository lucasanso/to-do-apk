package com.taskflow.data.backup

import com.taskflow.domain.model.HistoryType
import com.taskflow.domain.model.RecurrenceRule

/** Structural and referential validation; a backup is only applied if this returns no errors. */
object BackupValidator {
    private const val MAX_ITEMS = 100_000

    fun validate(b: BackupDto): List<String> {
        val e = mutableListOf<String>()
        if (b.format != BackupDto.FORMAT) e += "Arquivo não é um backup do TaskFlow"
        if (b.schemaVersion !in 1..BackupDto.SCHEMA_VERSION) e += "Versão de backup não suportada (${b.schemaVersion})"
        if (b.tasks.size > MAX_ITEMS || b.history.size > MAX_ITEMS * 5) e += "Backup grande demais"

        val catIds = b.categories.map { it.id }
        if (catIds.size != catIds.toSet().size) e += "IDs de categoria duplicados"
        if (b.categories.map { it.name.lowercase() }.toSet().size != b.categories.size) e += "Nomes de categoria duplicados"
        b.categories.forEach { if (it.name.isBlank()) e += "Categoria sem nome (id ${it.id})" }

        val taskIds = b.tasks.map { it.id }
        if (taskIds.size != taskIds.toSet().size) e += "IDs de tarefa duplicados"
        val taskSet = taskIds.toSet(); val catSet = catIds.toSet()
        b.tasks.forEach { t ->
            val ref = "tarefa ${t.id}"
            if (t.id <= 0) e += "ID inválido em $ref"
            if (t.title.isBlank()) e += "Título vazio em $ref"
            if (t.title.length > 200) e += "Título longo demais em $ref"
            if (t.priority !in 0..3) e += "Prioridade inválida em $ref"
            if (t.status !in 0..3) e += "Status inválido em $ref"
            if (t.categoryId != null && t.categoryId !in catSet) e += "Categoria inexistente em $ref"
            if (t.dueTime != null && t.dueTime !in 0 until 24 * 60) e += "Horário inválido em $ref"
            if (t.estimatedMinutes != null && t.estimatedMinutes !in 1..24 * 60) e += "Duração inválida em $ref"
            if (t.postponeCount < 0) e += "Contador de adiamentos inválido em $ref"
            if (t.recurrence != null && RecurrenceRule.decode(t.recurrence) == null) e += "Recorrência inválida em $ref"
            t.dependsOn.forEach { d -> if (d !in taskSet || d == t.id) e += "Dependência inválida em $ref" }
        }
        val habitIds = b.habits.map { it.id }
        if (habitIds.size != habitIds.toSet().size) e += "IDs de hábito duplicados"
        b.habits.forEach { h ->
            if (h.title.isBlank()) e += "Hábito sem nome (id ${h.id})"
            if (RecurrenceRule.decode(h.rule) == null) e += "Regra inválida no hábito ${h.id}"
            if (h.categoryId != null && h.categoryId !in catSet) e += "Categoria inexistente no hábito ${h.id}"
            if (h.reminderTime != null && h.reminderTime !in 0 until 24 * 60) e += "Horário inválido no hábito ${h.id}"
        }
        val habitSet = habitIds.toSet()
        if (b.habitLogs.any { it.habitId !in habitSet }) e += "Registros de hábito sem hábito correspondente"
        if (b.habitLogs.map { it.habitId to it.date }.toSet().size != b.habitLogs.size) e += "Registros de hábito duplicados"
        if (b.dayPlan.any { it.taskId !in taskSet }) e += "Planejamento referencia tarefa inexistente"
        if (b.dayPlan.map { it.date to it.taskId }.toSet().size != b.dayPlan.size) e += "Planejamento duplicado"
        if (b.dayMeta.any { it.availableMinutes !in 0..24 * 60 }) e += "Tempo disponível inválido"
        if (b.history.any { h -> HistoryType.entries.none { it.name == h.type } }) e += "Tipo de histórico desconhecido"
        if (hasCycle(b.tasks)) e += "Dependências circulares entre tarefas"
        return e.distinct()
    }

    private fun hasCycle(tasks: List<TaskDto>): Boolean {
        val graph = tasks.associate { it.id to it.dependsOn }
        val state = mutableMapOf<Long, Int>() // 1 visiting, 2 done
        fun dfs(n: Long): Boolean {
            when (state[n]) { 1 -> return true; 2 -> return false }
            state[n] = 1
            if (graph[n].orEmpty().any(::dfs)) return true
            state[n] = 2; return false
        }
        return graph.keys.any(::dfs)
    }
}
