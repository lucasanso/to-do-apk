package com.taskflow.domain.engine

import com.taskflow.domain.model.Priority
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class QuickCapture(
    val title: String,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val priority: Priority? = null,
    val tags: List<String> = emptyList(),
    val categoryHint: String? = null,
    val estimatedMinutes: Int? = null,
)

/**
 * Parses quick-capture text. Supported tokens (anywhere in the text):
 *  hoje · amanhã · seg..dom · dd/mm · 14h / 14:30 · !baixa !media !alta !critica (or !1..!4)
 *  #tag · @categoria · 30min / 1h30m / 2hrs (duration)
 */
object QuickCaptureParser {
    private val timeRegex = Regex("^(\\d{1,2})(?::(\\d{2})|h(\\d{2})?)$")
    /** Durations: 30min · 45m · 1h30m · 2hrs · 1,5h ("19h" alone is a time, not a duration). */
    private val durRegex = Regex("^(\\d{1,3})(min|m)$|^(\\d{1,2})h(\\d{1,2})m(in)?$|^(\\d{1,2}(?:[.,]5)?)hrs?$|^(\\d{1,2}[.,]5)h$")
    private val dateRegex = Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$")

    fun parse(input: String, today: LocalDate): QuickCapture {
        var date: LocalDate? = null
        var time: LocalTime? = null
        var priority: Priority? = null
        var category: String? = null
        var minutes: Int? = null
        val tags = mutableListOf<String>()
        val words = mutableListOf<String>()

        for (raw in input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val w = normalize(raw)
            when {
                w == "hoje" -> date = today
                w == "amanha" -> date = today.plusDays(1)
                weekday(w) != null -> {
                    val target = weekday(w)!!
                    var d = today.plusDays(1)
                    while (d.dayOfWeek != target) d = d.plusDays(1)
                    date = d
                }
                raw.startsWith("!") && parsePriority(w.drop(1)) != null -> priority = parsePriority(w.drop(1))
                raw.startsWith("#") && raw.length > 1 -> tags += raw.drop(1)
                raw.startsWith("@") && raw.length > 1 -> category = raw.drop(1)
                dateRegex.matches(w) -> date = parseDate(w, today) ?: run { words += raw; date }
                durRegex.matches(w) && parseDuration(w) != null -> minutes = parseDuration(w)
                timeRegex.matches(w) -> {
                    val m = timeRegex.find(w)!!
                    val h = m.groupValues[1].toInt()
                    val min = (m.groupValues[2].ifEmpty { m.groupValues[3] }).ifEmpty { "0" }.toInt()
                    if (h in 0..23 && min in 0..59) time = LocalTime.of(h, min) else words += raw
                }
                else -> words += raw
            }
        }
        if (time != null && date == null) date = today
        return QuickCapture(words.joinToString(" "), date, time, priority, tags.distinct(), category, minutes)
    }

    private fun parsePriority(s: String): Priority? = when (s) {
        "baixa", "1" -> Priority.LOW
        "media", "2" -> Priority.MEDIUM
        "alta", "3" -> Priority.HIGH
        "critica", "urgente", "4" -> Priority.CRITICAL
        else -> null
    }

    private fun parseDuration(w: String): Int? {
        val m = durRegex.find(w) ?: return null
        val g = m.groupValues
        val minutes = when {
            g[1].isNotEmpty() -> g[1].toInt()
            g[3].isNotEmpty() -> g[3].toInt() * 60 + g[4].toInt()
            g[6].isNotEmpty() -> (g[6].replace(',', '.').toDouble() * 60).toInt()
            else -> (g[7].replace(',', '.').toDouble() * 60).toInt()
        }
        return minutes.takeIf { it in 1..24 * 60 }
    }

    private fun parseDate(w: String, today: LocalDate): LocalDate? {
        val m = dateRegex.find(w) ?: return null
        val day = m.groupValues[1].toInt(); val month = m.groupValues[2].toInt()
        val yearRaw = m.groupValues[3]
        val year = when { yearRaw.isEmpty() -> today.year; yearRaw.length == 2 -> 2000 + yearRaw.toInt(); else -> yearRaw.toInt() }
        val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return if (yearRaw.isEmpty() && d.isBefore(today)) d.plusYears(1) else d
    }

    private fun weekday(w: String): DayOfWeek? = when (w) {
        "seg", "segunda" -> DayOfWeek.MONDAY; "ter", "terca" -> DayOfWeek.TUESDAY
        "qua", "quarta" -> DayOfWeek.WEDNESDAY; "qui", "quinta" -> DayOfWeek.THURSDAY
        "sex", "sexta" -> DayOfWeek.FRIDAY; "sab", "sabado" -> DayOfWeek.SATURDAY
        "dom", "domingo" -> DayOfWeek.SUNDAY; else -> null
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
