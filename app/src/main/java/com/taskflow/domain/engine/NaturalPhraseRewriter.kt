package com.taskflow.domain.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Rewrites spoken/written Portuguese phrases into the compact tokens [QuickCaptureParser] understands,
 * so "reunião sexta-feira às 3 da tarde, urgente" behaves like "reunião sex 15:00 !critica".
 *
 * Pure and deterministic (depends only on [today]); runs before tokenization.
 * Words it consumes ("às", "dia", "na próxima") disappear from the title.
 */
object NaturalPhraseRewriter {
    private val O = setOf(RegexOption.IGNORE_CASE)
    private const val B = "(?<![\\p{L}\\d])"   // left word boundary (Unicode-aware)
    private const val E = "(?![\\p{L}\\d])"     // right word boundary

    private val months = listOf("janeiro", "fevereiro", "mar[çc]o", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")
    private val monthAlt = months.joinToString("|")
    private val numberWords = mapOf("um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "três" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7)
    private const val NUM = "(\\d{1,2}|um|uma|dois|duas|tr[êe]s|quatro|cinco|seis|sete)"

    private val dayAfterTomorrow = Regex("${B}depois de amanh[ãa]$E", O)
    private val inNDays = Regex("$B(?:daqui a|daqui|em)\\s+$NUM\\s+(dias?|semanas?)$E", O)
    private val nextWeek = Regex("$B(?:n?a\\s+)?(?:pr[óo]xima\\s+semana|semana\\s+que\\s+vem)$E", O)
    private val weekend = Regex("$B(?:n?o\\s+)?(?:fim|final)\\s+de\\s+semana$E", O)
    private val weekdayWithPrep = Regex(
        "$B(?:(?:n[oa]|nest[ae]|ness[ae]|at[ée]|pr[óo]xim[oa]|n[oa]\\s+pr[óo]xim[oa])\\s+)(segunda|ter[çc]a|quarta|quinta|sexta|s[áa]bado|domingo)(?:[- ]feira)?$E", O)
    private val weekdayFeira = Regex("$B(segunda|ter[çc]a|quarta|quinta|sexta)[- ]feira$E", O)
    private val dayOfMonthNamed = Regex("$B(?:(?:n?o\\s+)?dia\\s+)?(\\d{1,2})\\s+de\\s+($monthAlt)(?:\\s+de\\s+(\\d{4}))?$E", O)
    private val dayOfMonth = Regex("$B(?:n?o\\s+)?dia\\s+(\\d{1,2})$E(?!\\s*[/:h])", O)

    private val noon = Regex("$B(?:(?:[àa]o?)\\s+)?meio[- ]dia(?:\\s+e\\s+meia)?$E", O)
    private val midnight = Regex("$B(?:(?:[àa])\\s+)?meia[- ]noite$E", O)
    /** "às 15h", "as 15:30", "às 3 da tarde", "às 9 e meia", "às 14 horas". */
    private val atTime = Regex(
        "$B(?:[àa]s|[àa])\\s+(\\d{1,2})(?:(?::|h)(\\d{2})|\\s*(?:h|hs|horas?)$E)?(?:\\s+e\\s+(meia|\\d{1,2})(?:\\s+minutos)?)?(?:\\s+da\\s+(manh[ãa]|tarde|noite))?(?![\\p{L}\\d/])", O)
    private val periodTime = Regex("$B(\\d{1,2})(?:\\s*(?:h|horas?))?\\s+da\\s+(manh[ãa]|tarde|noite)$E", O)
    private val hoursWord = Regex("$B(por|durante|leva|levar|de)?\\s*(\\d{1,2}(?:[.,]5)?)\\s+horas?$E", O)
    private val hourAndHalf = Regex("$B(?:uma\\s+)?hora\\s+e\\s+meia$E", O)
    private val halfHour = Regex("${B}meia\\s+hora$E", O)
    private val oneHour = Regex("$B(?:por|durante)?\\s*uma\\s+hora$E", O)
    private val minutesWord = Regex("$B(\\d{1,3})\\s+minutos?$E", O)

    private val critical = Regex("$B(?:[ée]\\s+)?(?:urgente|urgent[íi]ssimo|com urg[êe]ncia|prioridade\\s+m[áa]xima)$E", O)
    private val high = Regex("$B(?:[ée]\\s+)?(?:muito\\s+importante|importante|prioridade\\s+alta|alta\\s+prioridade)$E", O)
    private val low = Regex("$B(?:sem\\s+pressa|quando\\s+der|baixa\\s+prioridade|prioridade\\s+baixa)$E", O)

    /** Leading "preciso / tenho que / lembrar de ..." that carry no meaning in a task title. */
    private val filler = Regex(
        "^\\s*(?:(?:eu|a gente|n[óo]s)\\s+)?(?:preciso|precisamos|precisa|tenho que|tenho de|temos que|tem que|n[ãa]o\\s+(?:posso\\s+)?esquecer\\s+de|lembrar\\s+de|me\\s+lembr[ae]\\s+de|lembrete:?|vou\\s+ter\\s+que|devo|vou|vamos)\\s+", O)

    fun rewrite(input: String, today: LocalDate): String {
        var s = input
        s = dayAfterTomorrow.replace(s) { tok(date(today.plusDays(2))) }
        s = inNDays.replace(s) {
            val n = num(it.groupValues[1]) ?: return@replace it.value
            val days = if (it.groupValues[2].lowercase().startsWith("semana")) n * 7L else n.toLong()
            tok(date(today.plusDays(days)))
        }
        s = nextWeek.replace(s) { tok(date(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))) }
        s = weekend.replace(s) {
            val d = if (today.dayOfWeek == DayOfWeek.SATURDAY) today else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
            tok(date(d))
        }
        s = weekdayWithPrep.replace(s) { tok(weekdayToken(it.groupValues[1])) }
        s = weekdayFeira.replace(s) { tok(weekdayToken(it.groupValues[1])) }
        s = dayOfMonthNamed.replace(s) {
            val day = it.groupValues[1].toInt()
            val month = monthIndex(it.groupValues[2])
            val year = it.groupValues[3].toIntOrNull()
            val d = runCatching { LocalDate.of(year ?: today.year, month, day) }.getOrNull() ?: return@replace it.value
            tok(date(if (year == null && d.isBefore(today)) d.plusYears(1) else d))
        }
        s = dayOfMonth.replace(s) {
            val day = it.groupValues[1].toInt()
            nextDayOfMonth(day, today)?.let { d -> tok(date(d)) } ?: it.value
        }

        s = noon.replace(s) { m -> tok(if (m.value.contains("meia", true)) "12:30" else "12:00") }
        s = midnight.replace(s) { tok("00:00") }
        s = atTime.replace(s) { m ->
            val g = m.groupValues
            val hasUnit = g[2].isNotEmpty() || g[3].isNotEmpty() || g[4].isNotEmpty() || Regex("\\d\\s*(h|hs|horas?)$", O).containsMatchIn(m.value)
            if (!hasUnit && !m.value.trimStart().startsWith("à", ignoreCase = true)) return@replace m.value
            var h = g[1].toInt()
            val min = when { g[2].isNotEmpty() -> g[2].toInt(); g[3].equals("meia", true) -> 30; g[3].isNotEmpty() -> g[3].toInt(); else -> 0 }
            h = applyPeriod(h, g[4])
            if (h in 0..23 && min in 0..59) tok(time(h, min)) else m.value
        }
        s = periodTime.replace(s) { m ->
            val h = applyPeriod(m.groupValues[1].toInt(), m.groupValues[2])
            if (h in 0..23) tok(time(h, 0)) else m.value
        }
        s = hourAndHalf.replace(s) { tok("90min") }
        s = halfHour.replace(s) { tok("30min") }
        s = oneHour.replace(s) { tok("60min") }
        s = minutesWord.replace(s) { tok("${it.groupValues[1]}min") }
        s = hoursWord.replace(s) { m ->
            val prep = m.groupValues[1]
            val raw = m.groupValues[2].replace(',', '.')
            val n = raw.toDouble()
            // "15 horas" is a clock time; "2 horas" / "durante 3 horas" is a duration.
            if (prep.isEmpty() && n >= 7 && n <= 23 && n % 1.0 == 0.0) tok(time(n.toInt(), 0))
            else (if (prep.equals("de", true)) "de " else "") + tok("${(n * 60).toInt()}min")
        }

        s = critical.replace(s) { tok("!critica") }
        s = high.replace(s) { tok("!alta") }
        s = low.replace(s) { tok("!baixa") }

        s = filler.replace(s, "")
        // Tokens may now be glued to punctuation ("15:00,") — put the punctuation after a space.
        s = Regex("(\\S)([,;])(?=\\s|$)").replace(s) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Next date (today or later) whose day-of-month is [day]; null when impossible (e.g. 31 in short months forever). */
    fun nextDayOfMonth(day: Int, today: LocalDate): LocalDate? {
        if (day !in 1..31) return null
        var month = today.withDayOfMonth(1)
        repeat(12) {
            if (day <= month.lengthOfMonth()) {
                val d = month.withDayOfMonth(day)
                if (!d.isBefore(today)) return d
            }
            month = month.plusMonths(1)
        }
        return null
    }

    private fun applyPeriod(h: Int, period: String): Int {
        val p = period.lowercase()
        return when {
            (p == "tarde" || p == "noite") && h in 1..11 -> h + 12
            p.startsWith("manh") && h == 12 -> 0
            else -> h
        }
    }

    private fun weekdayToken(w: String): String = when (w.lowercase().take(3)) {
        "seg" -> "seg"; "ter" -> "ter"; "qua" -> "qua"; "qui" -> "qui"; "sex" -> "sex"; "sab", "sáb" -> "sab"; else -> "dom"
    }

    private fun monthIndex(m: String): Int {
        val n = m.lowercase().replace('ç', 'c')
        return months.indexOfFirst { Regex(it).matches(n) || it.replace("[çc]", "c") == n } + 1
    }

    private fun num(w: String): Int? = w.toIntOrNull() ?: numberWords[w.lowercase()]
    private fun date(d: LocalDate) = "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)
    private fun time(h: Int, m: Int) = "%02d:%02d".format(h, m)
    private fun tok(t: String) = " $t "
}
