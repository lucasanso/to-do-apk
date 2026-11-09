package com.taskflow.presentation.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val PtBr: Locale = Locale.forLanguageTag("pt-BR")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val shortFmt = DateTimeFormatter.ofPattern("d MMM", PtBr)
private val longFmt = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", PtBr)

fun LocalTime.hhmm(): String = format(timeFmt)

fun formatDue(date: LocalDate?, time: LocalTime?, today: LocalDate): String? {
    date ?: return null
    val d = when (date) {
        today -> "Hoje"
        today.plusDays(1) -> "Amanhã"
        today.minusDays(1) -> "Ontem"
        else -> if (date.isAfter(today) && date.isBefore(today.plusDays(7)))
            date.dayOfWeek.getDisplayName(TextStyle.SHORT, PtBr).replaceFirstChar { it.uppercase() }.removeSuffix(".")
        else date.format(shortFmt).replace(".", "")
    }
    return if (time != null) "$d ${time.hhmm()}" else d
}

fun formatLongDate(date: LocalDate): String = date.format(longFmt).replaceFirstChar { it.uppercase() }
fun formatShortDate(date: LocalDate): String = date.format(shortFmt).replace(".", "")

fun formatMinutes(min: Int): String = when {
    min < 60 -> "${min}min"
    min % 60 == 0 -> "${min / 60}h"
    else -> "${min / 60}h ${min % 60}min"
}

fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Bom dia"
    in 12..17 -> "Boa tarde"
    else -> "Boa noite"
}
