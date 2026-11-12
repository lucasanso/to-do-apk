package com.taskflow.domain.voice

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

enum class ListStatus { OPEN, DONE, OVERDUE }
enum class ListKind { ALL, TASKS, HABITS }

/** What a spoken question asks to see. Ranges are inclusive due dates (completion dates for DONE). */
data class ListQuery(
    val label: String,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val status: ListStatus = ListStatus.OPEN,
    val highPriority: Boolean = false,
    val noDate: Boolean = false,
    /** "o que devo fazer agora?": the top of the priority ranking, with reasons. */
    val recommend: Boolean = false,
    val kind: ListKind = ListKind.ALL,
    val category: String? = null,
    val search: String? = null,
) {
    val singleDay: LocalDate? get() = from?.takeIf { it == to }
}

/**
 * Recognizes questions and list requests in pt-BR and works out what to show:
 *  "o que eu tenho hoje?" · "quais as tarefas de amanhã?" · "tenho algo na sexta?" · "o que está atrasado?"
 *  "o que eu fiz hoje?" · "o que devo fazer agora?" · "quais hábitos faltam hoje?" · "mostra as tarefas de trabalho da semana"
 * Works on the normalized sentence (lowercase, no accents, number words as digits).
 */
internal object VoiceQueryParser {
    private const val LEAD = """^(?:(?:ok|ei|hey|ola|oi) )?(?:taskflow )?(?:por favor )?(?:e )?"""
    private const val NOUNS = """(?:tarefas?|habitos?|pendencias?|compromissos?|atividades?|afazeres|coisas|agenda|prioridades|lembretes|planos)"""

    private val QUESTION_WORD = Regex("""${LEAD}(?:quais|qual|quantas|quantos)\b""")
    private val WHAT = Regex("""${LEAD}(?:o que|oque)\b.*\b(?:tenho|tem|falta|faltam|preciso|devo|deveria|esta|ta|estao|fiz|conclui|terminei|completei|sobrou|sobra|vence|vencem|ficou|ha|rola|agendado|marcado|planejado|fazer)\b""")
    private val HAVE_ANY = Regex("""${LEAD}(?:eu )?(?:tenho|tem|ha|existe|existem)(?: (?:ainda|mais))? (?:alguma|algum|algo|nada|coisa|$NOUNS)\b""")
    private val SHOW = Regex("""${LEAD}(?:me )?(?:lista|liste|listar|mostra|mostre|mostrar|exibe|exiba|ve|ver|veja|quero ver|abre|abra|le|leia|diz|diga|fala|fale|conta|conte|repassa|repasse|resume|resuma)\b(?: me)?.*\b$NOUNS\b""")
    private val NOUN_FIRST = Regex("""${LEAD}(?:minhas |meus |minha |as |os )?(?:tarefas|habitos|pendencias|agenda|compromissos|prioridades)(?: (?:de|do|da|dos|das|pra|para|que|em|na|no|nas|nos|dessa|desta|desse|deste|nessa|nesta|nesse|neste|essa|esta|esse|este|hoje|amanha|atrasadas|pendentes|concluidas|abertas|importantes|urgentes|sem)\b.*)?$""")
    private val WHERE_START = Regex("""${LEAD}por onde (?:eu )?(?:comeco|começo|comecar)\b""")

    private val RECOMMEND = Regex("""\b(?:(?:devo|deveria|preciso) fazer (?:agora|primeiro|antes)|o que fazer agora|por onde (?:eu )?comec|(?:qual|quais) (?:e |sao )?(?:a |as )?(?:minhas? )?prioridades?|mais importante|meu foco|foco de hoje)""")
    private val DONE = Regex("""\b(?:fiz|conclui|terminei|completei|finalizei|feitas?|feitos?|concluidas?|concluidos?|terminadas?|finalizadas?|completadas?)\b""")
    private val NOT_DONE = Regex("""\bnao (?:fiz|conclui|terminei|completei|finalizei|feitas?|concluidas?)\b""")
    private val OVERDUE = Regex("""\b(?:atrasad[ao]s?|vencid[ao]s?|em atraso|passou do prazo|passaram do prazo)\b""")
    private val HIGH = Regex("""\b(?:importantes?|urgentes?|prioritari[ao]s?|alta prioridade|prioridade alta|criticas?)\b""")
    private val NO_DATE = Regex("""\bsem (?:prazo|data)\b""")
    private val HABITS = Regex("""\bhabitos?\b""")
    private val TASKS = Regex("""\btarefas?\b""")
    private val SEARCH = Regex("""\b(?:sobre|contendo|com a palavra|relacionad[ao]s? (?:a|ao|com))\s+(.+)$""")

    private val WEEK_NEXT = Regex("""\b(?:semana que vem|proxima semana)\b""")
    private val WEEK_THIS = Regex("""\b(?:essa|esta|nessa|nesta|dessa|desta|da) semana\b""")
    private val MONTH_NEXT = Regex("""\b(?:mes que vem|proximo mes)\b""")
    private val MONTH_THIS = Regex("""\b(?:esse|este|nesse|neste|desse|deste|do) mes\b""")
    private val WEEKEND = Regex("""\b(?:fim|final) de semana\b""")
    private val NEXT_DAYS = Regex("""\bproximos (\d{1,2}) dias\b""")
    private val NEXT_DAYS_PLAIN = Regex("""\bproximos dias\b""")
    private val NOW_WORD = Regex("""\bagora\b""")

    private val SYNONYMS = mapOf("faculdade" to "universidade", "facul" to "universidade", "servico" to "trabalho", "emprego" to "trabalho")

    fun detect(t: String, now: LocalDateTime, categories: List<String>): ListQuery? {
        val isQuery = QUESTION_WORD.containsMatchIn(t) || WHAT.containsMatchIn(t) || HAVE_ANY.containsMatchIn(t) ||
            SHOW.containsMatchIn(t) || NOUN_FIRST.containsMatchIn(t) || WHERE_START.containsMatchIn(t)
        return if (isQuery) scope(t, now, categories) else null
    }

    /** Reads the scope of a sentence already known to be a question (also used when Gemini Nano says "list"). */
    fun scope(t: String, now: LocalDateTime, categories: List<String>): ListQuery {
        val today = now.toLocalDate()
        val recommend = RECOMMEND.containsMatchIn(t)
        val status = when {
            recommend -> ListStatus.OPEN
            OVERDUE.containsMatchIn(t) -> ListStatus.OVERDUE
            // "o que eu ainda não fiz?" asks for what is open.
            DONE.containsMatchIn(t) && !NOT_DONE.containsMatchIn(t) -> ListStatus.DONE
            else -> ListStatus.OPEN
        }
        val kind = when {
            HABITS.containsMatchIn(t) && !TASKS.containsMatchIn(t) -> ListKind.HABITS
            TASKS.containsMatchIn(t) && !HABITS.containsMatchIn(t) -> ListKind.TASKS
            else -> ListKind.ALL
        }
        val category = categoryIn(t, categories)
        val search = SEARCH.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        // Date range
        var label: String? = null
        var from: LocalDate? = null
        var to: LocalDate? = null
        fun range(a: LocalDate, b: LocalDate, l: String) { from = a; to = b; label = l }
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        when {
            WEEK_NEXT.containsMatchIn(t) -> range(monday.plusWeeks(1), monday.plusWeeks(1).plusDays(6), "Semana que vem")
            WEEK_THIS.containsMatchIn(t) -> range(monday, monday.plusDays(6), "Esta semana")
            MONTH_NEXT.containsMatchIn(t) -> today.plusMonths(1).withDayOfMonth(1).let { range(it, it.withDayOfMonth(it.lengthOfMonth()), "Mês que vem") }
            MONTH_THIS.containsMatchIn(t) -> range(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()), "Este mês")
            WEEKEND.containsMatchIn(t) -> {
                val sat = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.minusDays(1) else today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                range(sat, sat.plusDays(1), "Fim de semana")
            }
            NEXT_DAYS.containsMatchIn(t) -> {
                val n = NEXT_DAYS.find(t)!!.groupValues[1].toLong().coerceIn(1, 60)
                range(today, today.plusDays(n), "Próximos $n dias")
            }
            NEXT_DAYS_PLAIN.containsMatchIn(t) -> range(today, today.plusDays(7), "Próximos 7 dias")
            else -> {
                // Single dates reuse the command parser: hoje, amanhã, sexta, dia 20, 15/12…
                val d = VoiceCommandParser.extractDraft(TokenStream(t), now, emptyList()).dueDate
                    ?: if (NOW_WORD.containsMatchIn(t) && !recommend) today else null
                if (d != null) range(d, d, PtDates.dayLabel(d, today))
            }
        }

        // "o que falta (fazer)?" / "o que tenho pra fazer?" with no date means today.
        if (from == null && !recommend && status == ListStatus.OPEN && !NO_DATE.containsMatchIn(t) && !HIGH.containsMatchIn(t) &&
            kind != ListKind.TASKS && Regex("""\b(?:falta|faltam|tenho pra fazer|tenho para fazer|tem pra fazer|tem para fazer|tenho hoje)\b""").containsMatchIn(t)
        ) range(today, today, "Hoje")
        if (from == null && kind == ListKind.HABITS && !recommend) range(today, today, "Hoje")
        // Completed with no date: the last 7 days.
        if (from == null && status == ListStatus.DONE) range(today.minusDays(6), today, "Últimos 7 dias")

        val finalLabel = when {
            recommend -> "Por onde começar"
            status == ListStatus.OVERDUE -> "Atrasadas"
            status == ListStatus.DONE -> "Concluídas · " + (label ?: "")
            NO_DATE.containsMatchIn(t) -> "Sem prazo"
            HIGH.containsMatchIn(t) -> "Alta prioridade" + (label?.let { " · $it" } ?: "")
            kind == ListKind.HABITS -> "Hábitos · " + (label ?: "Hoje")
            else -> label ?: "Abertas"
        } + (category?.let { " · $it" } ?: "") + (search?.let { " · “$it”" } ?: "")

        return ListQuery(
            label = finalLabel.removeSuffix(" · "),
            from = if (recommend || status == ListStatus.OVERDUE) null else from,
            to = if (recommend || status == ListStatus.OVERDUE) null else to,
            status = status,
            highPriority = HIGH.containsMatchIn(t) && !recommend,
            noDate = NO_DATE.containsMatchIn(t),
            recommend = recommend,
            kind = if (recommend) ListKind.TASKS else kind,
            category = category,
            search = search,
        )
    }

    private fun categoryIn(t: String, categories: List<String>): String? {
        val text = SYNONYMS.entries.fold(" $t ") { acc, (k, v) -> acc.replace(" $k ", " $v ") }
        return categories.sortedByDescending { it.length }
            .firstOrNull { c -> Regex("""\b${Regex.escape(PtText.norm(c).replace('-', ' '))}\b""").containsMatchIn(text) }
    }
}

/** Short pt-BR date words for labels and spoken answers. */
object PtDates {
    private val days = mapOf(
        DayOfWeek.MONDAY to "segunda", DayOfWeek.TUESDAY to "terça", DayOfWeek.WEDNESDAY to "quarta", DayOfWeek.THURSDAY to "quinta",
        DayOfWeek.FRIDAY to "sexta", DayOfWeek.SATURDAY to "sábado", DayOfWeek.SUNDAY to "domingo",
    )

    fun weekday(d: LocalDate): String = days.getValue(d.dayOfWeek)

    /** "Hoje", "Amanhã", "Ontem", "Sexta, 13/11". */
    fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Hoje"
        today.plusDays(1) -> "Amanhã"
        today.minusDays(1) -> "Ontem"
        else -> weekday(d).replaceFirstChar { it.uppercase() } + ", %02d/%02d".format(d.dayOfMonth, d.monthValue)
    }

    /** "A", "A e B", "A, B e C". */
    fun join(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " e " + items.last()
    }
}
