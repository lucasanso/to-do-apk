package com.taskflow.domain.usecase

import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.DueRules
import com.taskflow.domain.engine.HabitStreaks
import com.taskflow.domain.engine.PriorityEngine
import com.taskflow.domain.engine.RecurrenceCalculator
import com.taskflow.domain.engine.SmartFilter
import com.taskflow.domain.engine.TaskFilters
import com.taskflow.domain.engine.TaskFlag
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.Task
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.repository.CategoryRepository
import com.taskflow.domain.repository.HabitRepository
import com.taskflow.domain.repository.TaskRepository
import com.taskflow.domain.voice.CompletionCandidate.Kind
import com.taskflow.domain.voice.ListKind
import com.taskflow.domain.voice.ListQuery
import com.taskflow.domain.voice.ListStatus
import com.taskflow.domain.voice.PtDates
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class ListedItem(val id: Long, val kind: Kind, val title: String, val detail: String, val done: Boolean)
data class ListSection(val title: String, val items: List<ListedItem>)

/**
 * The answer to a spoken question: what to show and what to say.
 * [smartFilter]/[categoryId] let the UI open the same list in the Tasks screen; [habitsOnly] points at Habits instead.
 */
data class VoiceListing(
    val query: ListQuery,
    val sections: List<ListSection>,
    val spoken: String,
    val smartFilter: SmartFilter? = null,
    val categoryId: Long? = null,
    val habitsOnly: Boolean = false,
) {
    val total: Int get() = sections.sumOf { it.items.size }
}

/** Answers list questions ("o que eu tenho hoje?") from the current data. Read-only. */
class AnswerVoiceQueryUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
    private val categories: CategoryRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(q: ListQuery): VoiceListing {
        val now = time.nowDateTime(); val today = now.toLocalDate(); val zone = time.zone()
        val cats = categories.getAll(); val catById = cats.associateBy { it.id }
        val cat = q.category?.let { n -> cats.firstOrNull { it.name.equals(n, ignoreCase = true) } }
        var all = tasks.getAll()
        if (cat != null) all = all.filter { it.categoryId == cat.id }
        if (q.search != null) all = TaskFilters.search(all, q.search, catById)
        val habitList = habits.getAll().filter { !it.archived && (cat == null || it.categoryId == cat.id) }
            .filter { h -> q.search == null || TaskFilters.norm(h.title).contains(TaskFilters.norm(q.search)) }

        fun detail(t: Task, showDate: Boolean) = listOfNotNull(
            if (showDate) t.dueDate?.let { PtDates.dayLabel(it, today) } else null,
            t.dueTime?.let(::hhmm),
            t.categoryId?.let(catById::get)?.name,
        ).joinToString(" · ")
        fun item(t: Task, showDate: Boolean) = ListedItem(t.id, Kind.TASK, t.title, detail(t, showDate), t.status == TaskStatus.DONE)
        fun spokenTask(t: Task, showDate: Boolean) = t.title + listOfNotNull(
            if (showDate) t.dueDate?.let { PtDates.dayLabel(it, today).lowercase().substringBefore(",") } else null,
            t.dueTime?.let { "às " + hhmm(it) },
        ).joinToString("") { ", $it" }
        val byDue = compareBy<Task>({ it.dueDate ?: LocalDate.MAX }, { it.dueTime ?: LocalTime.MAX }, { -it.priority.level })

        // ---- "o que devo fazer agora?"
        if (q.recommend) {
            val top = PriorityEngine.rank(all, now).filter { TaskFlag.BLOCKED !in it.second.flags }.take(5)
            val items = top.map { (t, s) -> ListedItem(t.id, Kind.TASK, t.title, s.explanation, false) }
            val spoken = if (top.isEmpty()) "Você não tem tarefas abertas." else buildString {
                val (first, score) = top.first()
                append("Comece por ").append(first.title)
                score.factors.filter { it.points > 0 }.sortedByDescending { it.points }.take(2).map { it.label.lowercase() }
                    .takeIf { it.isNotEmpty() }?.let { append(": ").append(PtDates.join(it)) }
                append('.')
                if (top.size > 1) append(" Depois, ").append(PtDates.join(top.drop(1).take(2).map { it.first.title })).append('.')
            }
            return VoiceListing(q, listOf(ListSection("Recomendadas", items)).filter { it.items.isNotEmpty() }, spoken, SmartFilter.ALL, cat?.id)
        }

        // ---- atrasadas
        if (q.status == ListStatus.OVERDUE) {
            val list = all.filter { DueRules.isOverdue(it, now) }.sortedWith(byDue)
            val spoken = if (list.isEmpty()) "Nenhuma tarefa atrasada." else
                "Você tem ${count(list.size, "tarefa atrasada", "tarefas atrasadas")}: ${speakList(list.map { spokenTask(it, true) })}."
            return VoiceListing(q, section("Atrasadas", list.map { item(it, true) }), spoken, SmartFilter.OVERDUE, cat?.id)
        }

        val from = q.from; val to = q.to
        fun inRange(d: LocalDate?) = d != null && from != null && to != null && !d.isBefore(from) && !d.isAfter(to)
        val single = q.singleDay

        // ---- concluídas
        if (q.status == ListStatus.DONE) {
            val doneTasks = all.filter { it.completedAt != null && it.status != TaskStatus.PENDING && it.status != TaskStatus.IN_PROGRESS }
                .filter { inRange(it.completedAt!!.atZone(zone).toLocalDate()) }.sortedByDescending { it.completedAt }
            val logs = habitList.associateWith { habits.logsFor(it.id).filter(::inRange) }.filterValues { it.isNotEmpty() }
            val habitItems = if (q.kind == ListKind.TASKS) emptyList() else logs.map { (h, days) ->
                ListedItem(h.id, Kind.HABIT, h.title, if (single != null) "" else count(days.size, "dia", "dias"), true)
            }
            val taskItems = if (q.kind == ListKind.HABITS) emptyList() else doneTasks.map { t ->
                ListedItem(t.id, Kind.TASK, t.title, if (single == null) PtDates.dayLabel(t.completedAt!!.atZone(zone).toLocalDate(), today) else "", true)
            }
            val when_ = if (single == today) "Hoje" else if (single != null) PtDates.dayLabel(single, today) else "Nos últimos dias"
            val spoken = when {
                taskItems.isEmpty() && habitItems.isEmpty() ->
                    if (single == today) "Você ainda não concluiu nada hoje." else "Nenhuma conclusão registrada nesse período."
                else -> buildString {
                    append(when_).append(" você ")
                    if (taskItems.isNotEmpty()) append("concluiu ${count(taskItems.size, "tarefa", "tarefas")}: ${speakList(taskItems.map { it.title })}")
                    if (habitItems.isNotEmpty()) {
                        if (taskItems.isNotEmpty()) append(". E ")
                        append("fez ${count(habitItems.size, "hábito", "hábitos")}: ${speakList(habitItems.map { it.title })}")
                    }
                    append('.')
                }
            }
            return VoiceListing(q, section("Tarefas", taskItems) + section("Hábitos", habitItems), spoken, SmartFilter.DONE, cat?.id)
        }

        // ---- hábitos sem tarefas
        if (q.kind == ListKind.HABITS) {
            val day = single ?: today
            val scheduled = habitList.filter { RecurrenceCalculator.occursOn(it.rule, it.startDate, day) }
            val items = scheduled.map { h ->
                val log = habits.logsFor(h.id)
                val streak = HabitStreaks.current(h.rule, h.startDate, log, today)
                ListedItem(h.id, Kind.HABIT, h.title, listOfNotNull(h.reminderTime?.let(::hhmm), if (streak > 0) "🔥 $streak" else null).joinToString(" · "), day in log)
            }.sortedBy { it.done }
            val pending = items.filter { !it.done }
            val spoken = when {
                items.isEmpty() -> "Nenhum hábito para ${dayWords(day, today)}."
                pending.isEmpty() -> "Você já fez todos os hábitos de ${dayWords(day, today)}."
                day == today -> "${if (pending.size == 1) "Falta" else "Faltam"} ${count(pending.size, "hábito", "hábitos")}: ${speakList(pending.map { it.title })}."
                else -> "${PtDates.dayLabel(day, today)}: ${speakList(items.map { it.title })}."
            }
            return VoiceListing(q, section("Hábitos", items), spoken, habitsOnly = true)
        }

        var open = all.filter { it.status.isOpen }
        if (q.highPriority) open = open.filter { it.priority >= Priority.HIGH }
        if (q.noDate) open = open.filter { it.dueDate == null }

        // ---- sem data: tudo que está aberto, na ordem de prioridade
        if (from == null || to == null) {
            val ranked = PriorityEngine.rank(open, now).map { it.first }
            val label = if (q.noDate) "sem prazo" else if (q.highPriority) "de alta prioridade" else "abertas"
            val spoken = if (ranked.isEmpty()) "Nenhuma tarefa $label." else
                "Você tem ${count(ranked.size, "tarefa", "tarefas")} $label. ${if (ranked.size > 1) "As principais" else "É"}: ${speakList(ranked.map { it.title }, 3)}."
            val filter = when { q.noDate -> SmartFilter.NO_DATE; q.highPriority -> SmartFilter.HIGH_PRIORITY; else -> SmartFilter.ALL }
            return VoiceListing(q, section("Tarefas", ranked.map { item(it, true) }), spoken, filter, cat?.id)
        }

        // ---- um dia ou um período
        val inPeriod = open.filter { inRange(it.dueDate) }.sortedWith(byDue)
        val includesToday = !today.isBefore(from) && !today.isAfter(to)
        val overdue = if (includesToday && !q.noDate) open.filter { DueRules.isOverdue(it, now) && it.dueDate!!.isBefore(from) }.sortedWith(byDue) else emptyList()
        val habitItems = if (single == null) emptyList() else habitList.filter { RecurrenceCalculator.occursOn(it.rule, it.startDate, single) }.map { h ->
            ListedItem(h.id, Kind.HABIT, h.title, h.reminderTime?.let(::hhmm) ?: "", single in habits.logsFor(h.id))
        }.sortedBy { it.done }
        val pendingHabits = habitItems.filter { !it.done }
        val showDate = single == null

        val spoken = buildString {
            val lead = if (single != null) PtDates.dayLabel(single, today).substringBefore(",").let { if (single == today || single == today.plusDays(1)) it else (if (single.dayOfWeek in WEEKEND_DAYS) "No " else "Na ") + it.lowercase() }
                else q.label.substringBefore(" · ")
            if (inPeriod.isEmpty() && overdue.isEmpty() && pendingHabits.isEmpty()) {
                append(if (single == today) "Hoje você não tem nada pendente." else "$lead você não tem nada agendado.")
                return@buildString
            }
            if (inPeriod.isNotEmpty()) append("$lead você tem ${count(inPeriod.size, "tarefa", "tarefas")}: ${speakList(inPeriod.map { spokenTask(it, showDate) })}.")
            else append("$lead não há tarefas com prazo.")
            if (overdue.isNotEmpty()) append(" Também ${count(overdue.size, "atrasada", "atrasadas")}: ${speakList(overdue.map { it.title }, 3)}.")
            if (pendingHabits.isNotEmpty()) append(if (single == today) " Hábitos que faltam: " else " Hábitos: ").append(speakList(pendingHabits.map { it.title }, 4)).append('.')
        }
        val filter = when {
            q.highPriority -> SmartFilter.HIGH_PRIORITY
            single == today -> SmartFilter.TODAY
            single == today.plusDays(1) -> SmartFilter.TOMORROW
            from == today.with(java.time.DayOfWeek.MONDAY) && to == from.plusDays(6) -> SmartFilter.THIS_WEEK
            else -> null
        }
        return VoiceListing(
            q,
            section("Atrasadas", overdue.map { item(it, true) }) + section("Tarefas", inPeriod.map { item(it, showDate) }) +
                section("Hábitos", habitItems),
            spoken, filter, cat?.id,
        )
    }

    /** "No sábado/domingo", "Na segunda…sexta". */
    private val WEEKEND_DAYS = setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)

    private fun section(title: String, items: List<ListedItem>) = if (items.isEmpty()) emptyList() else listOf(ListSection(title, items))

    private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

    private fun dayWords(d: LocalDate, today: LocalDate) = when (d) { today -> "hoje"; today.plusDays(1) -> "amanhã"; else -> PtDates.weekday(d) }

    /** Speaks at most [max] names, then "e mais N". Items that carry ", às 18:00" are separated by ";" so they stay distinct. */
    private fun speakList(names: List<String>, max: Int = 5): String {
        val shown = names.take(max)
        val sep = if (shown.any { ',' in it }) "; " else ", "
        val body = if (shown.size == 1) shown[0] else shown.dropLast(1).joinToString(sep) + (if (sep == "; ") "; e " else " e ") + shown.last()
        val more = names.size - max
        return if (more > 0) shown.joinToString(sep) + (if (sep == "; ") "; e mais $more" else " e mais $more") else body
    }
}
