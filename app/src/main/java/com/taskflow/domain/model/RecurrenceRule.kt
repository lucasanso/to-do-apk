package com.taskflow.domain.model

import java.time.DayOfWeek

enum class RecurrenceType(val label: String) {
    DAILY("Diária"), WEEKDAYS("Dias da semana"), WEEKLY("Semanal"), MONTHLY("Mensal"), CUSTOM("A cada N dias")
}

/**
 * Recurrence description shared by recurring tasks and habits.
 * - DAILY: every [interval] days.
 * - WEEKDAYS: on the listed [days] every week.
 * - WEEKLY: every [interval] weeks on the weekday of the anchor date.
 * - MONTHLY: every [interval] months on the anchor's day of month (clamped to month length).
 * - CUSTOM: every [interval] days (explicit "a cada N dias").
 */
data class RecurrenceRule(
    val type: RecurrenceType,
    val interval: Int = 1,
    val days: Set<DayOfWeek> = emptySet(),
) {
    init {
        require(interval >= 1) { "interval must be >= 1" }
    }

    fun encode(): String = "${type.name};$interval;${days.sortedBy { it.value }.joinToString(",") { it.value.toString() }}"

    fun describe(): String = when (type) {
        RecurrenceType.DAILY -> if (interval == 1) "Todos os dias" else "A cada $interval dias"
        RecurrenceType.CUSTOM -> "A cada $interval dias"
        RecurrenceType.WEEKLY -> if (interval == 1) "Toda semana" else "A cada $interval semanas"
        RecurrenceType.MONTHLY -> if (interval == 1) "Todo mês" else "A cada $interval meses"
        RecurrenceType.WEEKDAYS -> days.sortedBy { it.value }.joinToString(", ") { shortDay(it) }
    }

    companion object {
        fun decode(raw: String?): RecurrenceRule? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(";")
            if (parts.size < 2) return null
            val type = runCatching { RecurrenceType.valueOf(parts[0]) }.getOrNull() ?: return null
            val interval = parts[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val days = parts.getOrNull(2).orEmpty().split(",").filter { it.isNotBlank() }
                .mapNotNull { it.toIntOrNull()?.takeIf { v -> v in 1..7 }?.let(DayOfWeek::of) }.toSet()
            if (type == RecurrenceType.WEEKDAYS && days.isEmpty()) return null
            return RecurrenceRule(type, interval, days)
        }

        fun shortDay(d: DayOfWeek): String = when (d) {
            DayOfWeek.MONDAY -> "Seg"; DayOfWeek.TUESDAY -> "Ter"; DayOfWeek.WEDNESDAY -> "Qua"
            DayOfWeek.THURSDAY -> "Qui"; DayOfWeek.FRIDAY -> "Sex"; DayOfWeek.SATURDAY -> "Sáb"; DayOfWeek.SUNDAY -> "Dom"
        }
    }
}
