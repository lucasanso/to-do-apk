package com.taskflow.domain.voice

import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceRule
import com.taskflow.domain.model.RecurrenceType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Rule-based Portuguese (pt-BR) interpreter for spoken commands. Runs fully offline and is
 * deterministic, so it is also used to resolve dates/times when Gemini Nano is available.
 *
 * Examples:
 *  - "me lembra de pagar a fatura amanhã às 3 da tarde" → Create("Pagar a fatura", amanhã 15:00)
 *  - "reunião com o time toda segunda às 9h30 por uma hora" → Create(weekly Mon, 09:30, 60 min)
 *  - "já paguei a fatura" / "marca relatório como feito" / "concluí o relatório" → Complete(...)
 *  - "apaga a tarefa estudar SQL" / "exclui a reunião de amanhã" / "pode remover o dentista" → Delete(...)
 */
object VoiceCommandParser {

    fun parse(text: String, now: LocalDateTime, categories: List<String> = emptyList()): VoiceIntent {
        val s = TokenStream(text)
        if (s.toks.isEmpty()) return VoiceIntent.Unknown(text)
        detectDeletion(s, now, text)?.let { return it }
        detectCompletion(s)?.let { return it }
        s.take(CREATE_PREFIX)
        val draft = extractDraft(s, now, categories)
        return if (draft.title.isBlank()) VoiceIntent.Unknown(text) else VoiceIntent.Create(draft)
    }

    /** Reads the sentence as a new task, skipping intent detection ("cancela a assinatura" as a to-do). */
    fun parseAsCreate(text: String, now: LocalDateTime, categories: List<String> = emptyList()): TaskDraft {
        val s = TokenStream(text)
        s.take(CREATE_PREFIX)
        return extractDraft(s, now, categories)
    }

    /** Parses only date/time/duration expressions from a phrase (used for Gemini Nano's "when" field). */
    fun parseWhen(phrase: String, now: LocalDateTime): TaskDraft = extractDraft(TokenStream(phrase), now, emptyList())

    // ---------------------------------------------------------------- intent

    private const val DONE_WORD = """(?:feit[ao]s?|conclu[i]d[ao]s?|pront[ao]s?|finalizad[ao]s?|completad[ao]s?|realizad[ao]s?|ok)"""
    private const val LEAD = """^(?:(?:ok|ei|hey|ola|oi) )?(?:taskflow )?(?:por favor )?"""

    private val COMPLETE_PAST = Regex("""${LEAD}(?:eu )?(?:ja )?(?:conclui|terminei|finalizei|completei|acabei(?: de)?|realizei|cumpri)\b(?: (?:a tarefa|o habito|a atividade))?""")
    private val COMPLETE_IMPERATIVE = Regex("""${LEAD}(?:pode )?(?:conclua|finaliza|finalize|completa|complete|termina|termine|risca|risque|(?:concluir|finalizar|completar|terminar) (?:a tarefa|o habito|a atividade))\b(?: (?:a tarefa|o habito|a atividade))?""")
    private val COMPLETE_BAIXA = Regex("""${LEAD}(?:pode )?(?:da|dar|de) baixa (?:em|no|na|nos|nas)\b""")
    private val MARK_PREFIX = Regex("""${LEAD}(?:pode )?(?:marca|marque|marcar|coloca|coloque|colocar|deixa|deixe|deixar|registra|registre)\b(?: (?:a tarefa|o habito|a atividade))?""")
    private val MARK_SUFFIX = Regex("""\bcomo $DONE_WORD$""")
    private val DONE_SUFFIX = Regex("""\b(?:ja )?(?:(?:esta|ta|foi|estao|tao|foram) )?$DONE_WORD$""")
    private const val PAST = """(?:fiz|li|corri|escrevi|bebi|dormi|assisti|vi|fui|[a-z]{3,}ei)"""
    private val PAST_VERB = Regex("""${LEAD}(?:eu )?(?:ja )?(?=$PAST\b)""")
    private val REGISTER_PAST = Regex("""${LEAD}(?:registra|registre|anota|anote|marca|marque)(?: ai)?(?: que)?(?: eu)?(?: ja)? (?=$PAST\b)""")
    private const val NOUN = """(?:tarefa|atividade|lembrete|compromisso|item|evento)"""
    /**
     * Command words addressed to the assistant. Imperatives ("cria", "agenda") are always stripped;
     * infinitives ("criar", "agendar") are kept unless followed by a noun like "tarefa", because
     * "criar dashboard" or "agendar dentista" are the task itself.
     */
    private val CREATE_PREFIX = Regex(
        """${LEAD}(?:(?:me )?(?:lembra|lembrar|lembre|lembrete)(?: me)?(?: (?:de|que|para|pra))?|""" +
            """(?:adiciona|adicione|cria|crie|anota|anote|registra|registre|coloca|coloque|bota|inclui|inclua|agenda|agende|marca|marque)""" +
            """(?: (?:uma|um))?(?: (?:nova|novo))?(?: $NOUN)?(?: (?:de|para|pra|que))?|""" +
            """(?:adicionar|criar|anotar|registrar|colocar|incluir|botar)(?: (?:uma|um))?(?: (?:nova|novo))? $NOUN(?: (?:de|para|pra|que))?|""" +
            """(?:nova|novo)(?: $NOUN)?(?: (?:de|para|pra|que))?|""" +
            """(?:eu )?(?:preciso|tenho que|tenho de|devo|vou ter que)(?: de)?|$NOUN(?: (?:de|para|pra))?)\b"""
    )

    // ---------------------------------------------------------------- delete

    private const val ITEM_NOUN = """(?:a tarefa|o habito|a atividade|o lembrete|o compromisso|o evento|o item|a reuniao marcada)"""
    private const val DEL_IMPERATIVE = """(?:apaga|apague|exclui|exclua|remove|remova|deleta|delete|cancela|cancele|elimina|elimine|descarta|descarte|esquece|esqueca|joga fora|jogue fora)"""
    private const val DEL_INFINITIVE = """(?:apagar|excluir|remover|deletar|cancelar|eliminar|descartar|tirar)"""
    private const val DEL_TAIL = """(?: (?:de|do|da|que|chamad[ao]))?"""
    private val DELETE_SUFFIX = Regex("""\b(?:da (?:minha )?lista|da (?:minha )?agenda|das (?:minhas )?tarefas|dos (?:meus )?habitos|do app|do taskflow)\b""")

    /** "apaga X", "exclui a tarefa X", "esquece X". Imperatives addressed to the app always mean delete. */
    private val DELETE_IMP = Regex("""${LEAD}(?:pode )?$DEL_IMPERATIVE\b(?: $ITEM_NOUN)?$DEL_TAIL""")
    /** "pode apagar X", "quero excluir X". */
    private val DELETE_MODAL = Regex("""${LEAD}(?:eu )?(?:pode|quero|queria|tem como|da para|da pra|consegue)(?: me)? $DEL_INFINITIVE\b(?: $ITEM_NOUN)?$DEL_TAIL""")
    /** "apagar a tarefa X". A bare infinitive ("cancelar assinatura") is a new task, not a delete. */
    private val DELETE_INF_NOUN = Regex("""${LEAD}$DEL_INFINITIVE $ITEM_NOUN\b$DEL_TAIL""")
    /** "tira X da lista": "tira" alone is too ambiguous ("tirar o lixo"), so the list must be named. */
    private val DELETE_TIRA = Regex("""${LEAD}(?:pode )?(?:tira|tire)\b(?: $ITEM_NOUN)?$DEL_TAIL(?=.*$DELETE_SUFFIX)""")

    private fun detectDeletion(s: TokenStream, now: LocalDateTime, text: String): VoiceIntent? {
        val joined = s.joined
        val r = listOf(DELETE_TIRA, DELETE_INF_NOUN, DELETE_MODAL, DELETE_IMP).firstOrNull { it.containsMatchIn(joined) } ?: return null
        s.take(r)
        s.take(DELETE_SUFFIX)
        s.takeAll(Regex("""\b(?:por favor|pra mim|para mim|agora)\b"""))
        // Dates in the sentence narrow the search ("a reunião de amanhã"); they are not part of the name.
        val d = extractDraft(s, now, emptyList())
        val date = d.dueDate.takeIf { d.recurrence == null }
        return if (d.title.isBlank() && date == null) VoiceIntent.Unknown(text) else VoiceIntent.Delete(d.title, date)
    }

    // ---------------------------------------------------------------- complete

    private fun detectCompletion(s: TokenStream): VoiceIntent.Complete? {
        val joined = s.joined
        // "marca X como feito"
        if (MARK_PREFIX.containsMatchIn(joined) && MARK_SUFFIX.containsMatchIn(joined)) {
            s.take(MARK_PREFIX); s.take(MARK_SUFFIX)
            return completion(s)
        }
        for (r in listOf(COMPLETE_PAST, COMPLETE_IMPERATIVE, COMPLETE_BAIXA, REGISTER_PAST)) {
            if (r.containsMatchIn(joined)) { s.take(r); return completion(s) }
        }
        if (!CREATE_PREFIX.containsMatchIn(joined) && DONE_SUFFIX.containsMatchIn(joined)) {
            s.take(DONE_SUFFIX); return completion(s)
        }
        // "paguei a fatura", "estudei SQL": the verb stays in the query, it helps matching.
        if (!CREATE_PREFIX.containsMatchIn(joined) && PAST_VERB.containsMatchIn(joined)) {
            s.take(PAST_VERB)
            return completion(s)
        }
        return null
    }

    private fun completion(s: TokenStream): VoiceIntent.Complete? {
        s.takeAll(Regex("""\b(?:hoje|ontem|agora|agorinha|ainda pouco|agora ha pouco)\b"""))
        s.takeAll(PERIOD_ONLY)
        val q = cleanTitle(s.remainingWords())
        return if (q.isBlank()) null else VoiceIntent.Complete(q)
    }

    // ---------------------------------------------------------------- entities

    private const val WD = """(segunda|terca|quarta|quinta|sexta|sabado|domingo)s?(?: feiras?)?"""
    private const val MONTHS = """(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)"""
    private const val PERIOD = """(?: (da manha|de manha|da tarde|de tarde|da noite|de noite|da madrugada))?"""
    private const val MIN_PART = """(?: e (meia|\d{1,2})(?: minutos?)?)?"""

    private val REC_DAILY = Regex("""\b(?:todo dia|todos os dias|toda dia|diariamente)\b""")
    private val REC_WORKDAYS = Regex("""\b(?:(?:em|nos|todos os) )?(?:dias uteis|de segunda a sexta(?: feira)?)\b""")
    private val REC_WEEKDAYS = Regex("""\b(?:tod[ao]s?|cada) (?:as |os )?$WD((?:(?:,| e|, e) (?:as |os |a |o )?$WD)*)""")
    private val REC_WEEKLY = Regex("""\b(?:toda semana|todas as semanas|semanalmente|uma vez por semana)\b""")
    private val REC_MONTHLY = Regex("""\b(?:todo mes|todos os meses|mensalmente)\b""")
    private val REC_EVERY_N = Regex("""\ba cada (\d{1,3}) dias\b""")

    private val REL_TIME = Regex("""\b(?:daqui a|daqui|em|dentro de) (\d{1,3}|meia) (minutos?|horas?)(?: e (meia|\d{1,2})(?: minutos?)?)?\b""")
    private val REMINDER = Regex("""\b(?:e )?(?:me )?(?:avisa|avise|avisar|lembra|lembre|lembrar|lembrete|notifica|notifique)(?: me)? (?:com )?(\d{1,3}|meia) (minutos?|min|horas?|hora|dias?) antes\b""")
    private val DURATION = Regex("""\b(?:por|durante|leva|levar|vai levar|dura|duracao de|com duracao de|de duracao)(?: cerca de| uns| umas)? (\d{1,3}|meia) ?(min|minutos?|h|horas?)$MIN_PART\b""")
    private val HALF_HOUR = Regex("""\b(?:por |durante )?meia hora\b""")

    private val D_AFTER_TOMORROW = Regex("""\b(?:depois de amanha)\b""")
    private val D_TOMORROW = Regex("""\b(?:amanha)\b""")
    private val D_TODAY = Regex("""\b(?:hoje)\b""")
    private val D_DAY_MONTH = Regex("""\b(?:(?:no )?dia )?(\d{1,2}|primeiro)(?:o)? de $MONTHS(?: de (\d{4}))?\b""")
    private val D_NUMERIC = Regex("""\b(?:(?:no )?dia )?(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
    private val D_WEEKDAY = Regex("""\b(?:(?:na|no|nesta|neste|nessa|nesse|esta|este|essa|esse|proxima|proximo|ate|para|pra) )?$WD(?: (?:que vem|da semana que vem))?\b""")
    private val D_DAY_ONLY = Regex("""\b(?:(?:no|ate o|para o|pro) )?dia (\d{1,2})\b""")
    private val D_REL_DAYS = Regex("""\b(?:daqui a|daqui|em|dentro de) (\d{1,3}) (dias?|semanas?|mes|meses)\b""")
    private val D_NEXT_WEEK = Regex("""\b(?:na )?(?:proxima semana|semana que vem)\b""")
    private val D_NEXT_MONTH = Regex("""\b(?:no )?(?:proximo mes|mes que vem)\b""")
    private val D_WEEKEND = Regex("""\b(?:no |neste |nesse |ate o )?(?:fim|final) de semana\b""")

    private val T_CLOCK = Regex("""\b(?:(?:as|a|pras|para as|ate as|a partir das|das) )?(\d{1,2})(?::|h)(\d{2})$PERIOD\b""")
    private val T_AT = Regex("""\b(?:as|pras|para as|ate as|a partir das|das|a(?= 1\b)) (\d{1,2})(?: ?(?:h|hs|horas?))?$MIN_PART$PERIOD\b""")
    private val T_NOON = Regex("""\b(?:(?:ao|as|a|ate o) )?(meio dia|meia noite)(?: e (meia|\d{1,2}))?\b""")
    private val T_PERIOD_HOUR = Regex("""\b(\d{1,2})$MIN_PART (da manha|de manha|da tarde|de tarde|da noite|de noite|da madrugada)\b""")
    private val T_COMPACT = Regex("""\b(\d{1,2}) ?h\b""")
    private val T_HOURS_WORD = Regex("""\b(\d{1,2}) horas$MIN_PART\b""")
    private val BARE_MINUTES = Regex("""\b(\d{1,3}) ?(?:min|minutos?)\b""")
    private val PERIOD_ONLY = Regex("""\b(?:de manha|pela manha|cedo|cedinho|a tarde|de tarde|pela tarde|a noite|de noite|pela noite|no fim do dia|no final do dia|fim do dia|final do dia)\b""")

    private val P_CRITICAL = Regex("""\b(?:(?:e |com )?(?:urgente|urgentissimo|com urgencia|prioridade (?:maxima|critica|urgente))|e critico|e critica)\b""")
    private val P_HIGH = Regex("""\b(?:(?:e |com )?(?:muito importante|importante|prioridade alta|alta prioridade))\b""")
    private val P_MEDIUM = Regex("""\b(?:(?:com )?(?:prioridade media|prioridade normal|media prioridade))\b""")
    private val P_LOW = Regex("""\b(?:(?:com )?(?:prioridade baixa|baixa prioridade|sem pressa|quando der|sem urgencia))\b""")
    private val TAG = Regex("""\b(?:com )?(?:a )?(?:tag|etiqueta|hashtag) (\w+)\b""")

    private fun extractDraft(s: TokenStream, now: LocalDateTime, categories: List<String>): TaskDraft {
        val today = now.toLocalDate()
        var recurrence: RecurrenceRule? = null
        var date: LocalDate? = null
        var time: LocalTime? = null
        var minutes: Int? = null
        var reminder: Int? = null
        var priority: Priority? = null
        var exactTime = false
        val tags = mutableListOf<String>()

        // Recurrence first so "toda segunda" is not read as a single Monday.
        when {
            s.take(REC_WORKDAYS) != null -> recurrence = RecurrenceRule(RecurrenceType.WEEKDAYS, 1, DayOfWeek.entries.take(5).toSet())
            else -> s.take(REC_WEEKDAYS)?.let { m ->
                val days = Regex(WD).findAll(m.value).mapNotNull { weekday(it.groupValues[1]) }.toSet()
                if (days.isNotEmpty()) recurrence = RecurrenceRule(RecurrenceType.WEEKDAYS, 1, days)
            }
        }
        if (recurrence == null) recurrence = when {
            s.take(REC_DAILY) != null -> RecurrenceRule(RecurrenceType.DAILY)
            s.take(REC_WEEKLY) != null -> RecurrenceRule(RecurrenceType.WEEKLY)
            s.take(REC_MONTHLY) != null -> RecurrenceRule(RecurrenceType.MONTHLY)
            else -> s.take(REC_EVERY_N)?.let { RecurrenceRule(RecurrenceType.CUSTOM, it.groupValues[1].toInt().coerceIn(1, 365)) }
        }

        s.take(REL_TIME)?.let { m ->
            val n = if (m.groupValues[1] == "meia") 30 else m.groupValues[1].toInt() * (if (m.groupValues[2].startsWith("hora")) 60 else 1)
            val extra = m.groupValues[3].let { if (it == "meia") 30 else it.toIntOrNull() ?: 0 }
            val at = now.plusMinutes((n + extra).toLong())
            date = at.toLocalDate(); time = at.toLocalTime().withSecond(0).withNano(0)
            exactTime = true
        }
        s.take(REMINDER)?.let { m -> reminder = amount(m.groupValues[1], m.groupValues[2]) }
        s.take(DURATION)?.let { m -> minutes = amount(m.groupValues[1], m.groupValues[2]) + extraMinutes(m.groupValues[3]) }
        if (minutes == null && s.take(HALF_HOUR) != null) minutes = 30

        // Dates
        if (date == null) date = when {
            s.take(D_AFTER_TOMORROW) != null -> today.plusDays(2)
            s.take(D_TOMORROW) != null -> today.plusDays(1)
            s.take(D_TODAY) != null -> today
            else -> null
        }
        if (date == null) s.take(D_DAY_MONTH)?.let { m ->
            val day = if (m.groupValues[1] == "primeiro") 1 else m.groupValues[1].toInt()
            date = dayMonth(day, MONTH_NAMES.indexOf(m.groupValues[2]) + 1, m.groupValues[3].toIntOrNull(), today)
        }
        if (date == null) s.take(D_NUMERIC)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            date = dayMonth(m.groupValues[1].toInt(), m.groupValues[2].toInt(), y, today)
        }
        if (date == null && recurrence == null) s.take(D_WEEKDAY)?.let { m ->
            weekday(m.groupValues[1])?.let { d -> date = today.with(TemporalAdjusters.next(d)) }
        }
        if (date == null) s.take(D_DAY_ONLY)?.let { m -> date = dayOfMonth(m.groupValues[1].toInt(), today) }
        if (date == null) s.take(D_REL_DAYS)?.let { m ->
            val n = m.groupValues[1].toLong()
            date = when {
                m.groupValues[2].startsWith("dia") -> today.plusDays(n)
                m.groupValues[2].startsWith("semana") -> today.plusWeeks(n)
                else -> today.plusMonths(n)
            }
        }
        if (date == null && s.take(D_NEXT_WEEK) != null) date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        if (date == null && s.take(D_NEXT_MONTH) != null) date = today.plusMonths(1).withDayOfMonth(1)
        if (date == null && s.take(D_WEEKEND) != null) date =
            if (today.dayOfWeek >= DayOfWeek.SATURDAY) today else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))

        // Times
        var explicitPeriod = exactTime
        if (time == null) s.take(T_CLOCK)?.let { m ->
            explicitPeriod = m.groupValues[3].isNotEmpty()
            time = clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3])
        }
        if (time == null) s.take(T_AT)?.let { m ->
            explicitPeriod = m.groupValues[3].isNotEmpty()
            time = clock(m.groupValues[1].toInt(), extraMinutes(m.groupValues[2]), m.groupValues[3])
        }
        if (time == null) s.take(T_NOON)?.let { m ->
            explicitPeriod = true
            val base = if (m.groupValues[1] == "meio dia") 12 else 0
            time = LocalTime.of(base, extraMinutes(m.groupValues[2]).coerceIn(0, 59))
        }
        if (time == null) s.take(T_PERIOD_HOUR)?.let { m ->
            explicitPeriod = true
            time = clock(m.groupValues[1].toInt(), extraMinutes(m.groupValues[2]), m.groupValues[3])
        }
        if (time == null) s.take(T_COMPACT)?.let { m -> time = clock(m.groupValues[1].toInt(), 0, "") }
        if (time == null) {
            // "15 horas" is a time; "2 horas" without "às" is a duration.
            T_HOURS_WORD.find(s.joined)?.let { m ->
                val h = m.groupValues[1].toInt()
                if (h > 12) { s.take(T_HOURS_WORD); time = clock(h, extraMinutes(m.groupValues[2]), "") }
            }
        }
        if (minutes == null) s.take(T_HOURS_WORD)?.let { m -> minutes = m.groupValues[1].toInt() * 60 + extraMinutes(m.groupValues[2]) }
        if (minutes == null) s.take(BARE_MINUTES)?.let { m -> minutes = m.groupValues[1].toInt() }
        s.take(PERIOD_ONLY)?.let { m ->
            if (time == null) time = when {
                m.value.contains("manha") -> LocalTime.of(9, 0)
                m.value.contains("cedo") -> LocalTime.of(8, 0)
                m.value.contains("tarde") -> LocalTime.of(15, 0)
                m.value.contains("noite") -> LocalTime.of(20, 0)
                else -> LocalTime.of(18, 0)
            }
            explicitPeriod = true
        }

        // Ambiguous hours without "da tarde/da noite": 1–5 → afternoon; if today and already past, use PM.
        time?.let { t ->
            if (!explicitPeriod && t.hour in 1..11) {
                val pm = t.plusHours(12)
                // "às 9" said at 10:00 means 21:00 — but only for one-off items; routines keep the morning.
                val onToday = date == today || (date == null && recurrence == null)
                time = when {
                    t.hour <= 5 -> pm
                    onToday && t.isBefore(now.toLocalTime()) && pm.isAfter(now.toLocalTime()) -> pm
                    else -> t
                }
            }
        }

        // Priority, tags, category
        priority = when {
            s.take(P_CRITICAL) != null -> Priority.CRITICAL
            s.take(P_HIGH) != null -> Priority.HIGH
            s.take(P_LOW) != null -> Priority.LOW
            s.take(P_MEDIUM) != null -> Priority.MEDIUM
            else -> null
        }
        s.takeAll(TAG).forEach { tags += it.groupValues[1] }
        val category = takeCategory(s, categories)

        // Fill in what was implied.
        val rec = recurrence
        if (date == null && rec != null) {
            val t = time
            date = if (RecurrenceCalculator.occursOn(rec, today, today) && (t == null || t.isAfter(now.toLocalTime()))) today
            else RecurrenceCalculator.nextOccurrence(rec, today, today)
        }
        time?.let { t -> if (date == null) date = if (t.isAfter(now.toLocalTime())) today else today.plusDays(1) }
        if (reminder == null && time != null) reminder = 0

        return TaskDraft(
            title = cleanTitle(s.remainingWords()),
            dueDate = date, dueTime = time, estimatedMinutes = minutes?.takeIf { it in 1..24 * 60 },
            priority = priority, categoryHint = category, tags = tags.distinct(), recurrence = rec,
            reminderMinutesBefore = if (date != null) reminder else null,
        )
    }

    private fun takeCategory(s: TokenStream, categories: List<String>): String? {
        if (categories.isEmpty()) return null
        val byNorm = categories.associateBy { PtText.norm(it).replace('-', ' ') }
        // Longest names first so "Engenharia de Dados" wins over a shorter prefix.
        for ((n, name) in byNorm.entries.sortedByDescending { it.key.length }) {
            val r = Regex("""\b(?:(?:na|da|para a|pra|em) )?(?:categoria|area|lista) ${Regex.escape(n)}\b""")
            if (s.take(r) != null) return name
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private val MONTH_NAMES = listOf("janeiro", "fevereiro", "marco", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

    private fun weekday(w: String): DayOfWeek? = when (w) {
        "segunda" -> DayOfWeek.MONDAY; "terca" -> DayOfWeek.TUESDAY; "quarta" -> DayOfWeek.WEDNESDAY
        "quinta" -> DayOfWeek.THURSDAY; "sexta" -> DayOfWeek.FRIDAY; "sabado" -> DayOfWeek.SATURDAY
        "domingo" -> DayOfWeek.SUNDAY; else -> null
    }

    private fun amount(n: String, unit: String): Int {
        val v = if (n == "meia") 0.5 else n.toDouble()
        return when {
            unit.startsWith("h") -> (v * 60).toInt()
            unit.startsWith("dia") -> (v * 24 * 60).toInt()
            else -> if (n == "meia") 30 else v.toInt()
        }
    }

    private fun extraMinutes(s: String): Int = when { s == "meia" -> 30; s.isEmpty() -> 0; else -> s.toIntOrNull() ?: 0 }

    private fun clock(h: Int, m: Int, period: String): LocalTime? {
        if (h !in 0..23 || m !in 0..59) return null
        val hour = when {
            period.contains("tarde") || period.contains("noite") -> if (h in 1..11) h + 12 else if (h == 12 && period.contains("noite")) 0 else h
            period.contains("manha") -> if (h == 12) 0 else h
            else -> h
        }
        return LocalTime.of(hour, m)
    }

    private fun dayMonth(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? {
        val d = runCatching { LocalDate.of(year ?: today.year, month, day) }.getOrNull() ?: return null
        return if (year == null && d.isBefore(today)) d.plusYears(1) else d
    }

    private fun dayOfMonth(day: Int, today: LocalDate): LocalDate? {
        if (day !in 1..31) return null
        var month = today.withDayOfMonth(1)
        repeat(13) {
            if (day <= month.lengthOfMonth()) {
                val d = month.withDayOfMonth(day)
                if (!d.isBefore(today)) return d
            }
            month = month.plusMonths(1)
        }
        return null
    }

    private val EDGE_WORDS = setOf(
        "para", "pra", "pro", "ate", "no", "na", "nos", "nas", "de", "do", "da", "dos", "das", "as", "a", "o", "os",
        "em", "e", "pelo", "pela", "com", "que", "por", "favor", "obrigado", "obrigada", "pfv", "pf", "ai", "tarefa",
    )

    /** Removes dangling connectors at the edges and capitalizes. */
    internal fun cleanTitle(words: List<String>): String {
        val w = words.map { it.trim(',', '.', ';', '!', '?', ':') }.filter { it.isNotEmpty() }.toMutableList()
        while (w.isNotEmpty() && PtText.norm(w.first()) in EDGE_WORDS) w.removeAt(0)
        while (w.isNotEmpty() && PtText.norm(w.last()) in EDGE_WORDS) w.removeAt(w.lastIndex)
        return w.joinToString(" ").replaceFirstChar { it.uppercase() }
    }
}
