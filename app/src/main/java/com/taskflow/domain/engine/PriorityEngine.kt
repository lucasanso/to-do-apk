package com.taskflow.domain.engine

import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.Task
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Eisenhower quadrant: urgency and importance are evaluated independently. */
enum class Quadrant(val label: String, val hint: String) {
    DO_NOW("Fazer agora", "Urgente e importante"),
    SCHEDULE("Planejar", "Importante, sem urgência"),
    QUICK("Resolver rápido", "Urgente, pouco importante"),
    LATER("Depois", "Nem urgente, nem importante"),
}

enum class TaskFlag(val label: String) {
    URGENT("Urgente"), IMPORTANT("Importante"), QUICK_WIN("Rápida"), OVERDUE("Atrasada"),
    PROCRASTINATED("Adiada com frequência"), BLOCKED("Bloqueada"), BLOCKING("Desbloqueia outras"),
}

data class ScoreFactor(val label: String, val points: Int)

data class PriorityScore(
    val taskId: Long,
    val score: Int,
    val quadrant: Quadrant,
    val flags: Set<TaskFlag>,
    val factors: List<ScoreFactor>,
) {
    /** Short human explanation of the top contributing factors. */
    val explanation: String
        get() = factors.filter { it.points > 0 }.sortedByDescending { it.points }.take(3).joinToString(" · ") { it.label }
}

/**
 * Rule-based, explainable scoring. Score is clamped to 0..100.
 *
 * Components (max contribution):
 *  - Importance (user priority): 8 / 18 / 30 / 40
 *  - Urgency (deadline proximity): up to 30, plus up to 10 for days overdue
 *  - Quick win (estimated duration): +6 (≤15 min) / +3 (≤30 min); large tasks w/o urgency −3
 *  - Waiting time (age while pending): up to +6
 *  - Dependencies: +4 per open task it unblocks (max 12); −25 if blocked by open tasks
 *  - Procrastination: +2 per postponement (max 10) and +1 per day slipped from the original deadline (max 6)
 */
object PriorityEngine {
    const val QUICK_MINUTES = 15
    const val PROCRASTINATION_THRESHOLD = 3

    fun score(task: Task, allTasks: Map<Long, Task>, dependents: Map<Long, List<Long>>, now: LocalDateTime): PriorityScore {
        val today = now.toLocalDate()
        val factors = mutableListOf<ScoreFactor>()
        val flags = mutableSetOf<TaskFlag>()

        // 1. Importance
        val importance = when (task.priority) {
            Priority.LOW -> 8; Priority.MEDIUM -> 18; Priority.HIGH -> 30; Priority.CRITICAL -> 40
        }
        factors += ScoreFactor("Prioridade ${task.priority.label.lowercase()}", importance)
        val important = task.priority >= Priority.HIGH
        if (important) flags += TaskFlag.IMPORTANT

        // 2. Urgency
        val overdue = DueRules.isOverdue(task, now)
        val daysLeft = DueRules.daysUntilDue(task, today)
        var urgency = 0
        if (overdue) {
            val late = DueRules.daysOverdue(task, today)
            urgency = 30 + (late * 2).coerceAtMost(10).toInt()
            factors += ScoreFactor(if (late == 0L) "Horário já passou" else "Atrasada há $late dia(s)", urgency)
            flags += TaskFlag.OVERDUE
        } else if (daysLeft != null) {
            urgency = when {
                daysLeft == 0L -> {
                    val t = task.dueTime
                    if (t != null && ChronoUnit.MINUTES.between(now.toLocalTime(), t) in 0..120) 30 else 26
                }
                daysLeft == 1L -> 20
                daysLeft <= 3 -> 13
                daysLeft <= 7 -> 7
                daysLeft <= 14 -> 3
                else -> 1
            }
            val label = when (daysLeft) { 0L -> "Vence hoje"; 1L -> "Vence amanhã"; else -> "Vence em $daysLeft dias" }
            factors += ScoreFactor(label, urgency)
        }
        val urgent = overdue || (daysLeft != null && daysLeft <= 1)
        if (urgent) flags += TaskFlag.URGENT

        // 3. Duration
        val est = task.estimatedMinutes
        if (est != null) {
            when {
                est <= QUICK_MINUTES -> { factors += ScoreFactor("Rápida (≤ $QUICK_MINUTES min)", 6); flags += TaskFlag.QUICK_WIN }
                est <= 30 -> factors += ScoreFactor("Curta (≤ 30 min)", 3)
                est >= 240 && !urgent -> factors += ScoreFactor("Longa, sem urgência", -3)
            }
        }

        // 4. Waiting time
        val ageDays = ChronoUnit.DAYS.between(task.createdAt.atZone(java.time.ZoneOffset.UTC).toLocalDate(), today).coerceAtLeast(0)
        val agePts = (ageDays / 3).coerceAtMost(6).toInt()
        if (agePts > 0) factors += ScoreFactor("Pendente há $ageDays dias", agePts)

        // 5. Dependencies
        val openDependents = dependents[task.id].orEmpty().count { allTasks[it]?.status?.isOpen == true }
        if (openDependents > 0) {
            factors += ScoreFactor("Desbloqueia $openDependents tarefa(s)", (openDependents * 4).coerceAtMost(12))
            flags += TaskFlag.BLOCKING
        }
        val openBlockers = task.dependsOn.count { allTasks[it]?.status?.isOpen == true }
        if (openBlockers > 0) {
            factors += ScoreFactor("Aguardando $openBlockers dependência(s)", -25)
            flags += TaskFlag.BLOCKED
        }

        // 6. Procrastination & slip
        if (task.postponeCount > 0) {
            factors += ScoreFactor("Adiada ${task.postponeCount}x", (task.postponeCount * 2).coerceAtMost(10))
            if (task.postponeCount >= PROCRASTINATION_THRESHOLD) flags += TaskFlag.PROCRASTINATED
        }
        val slip = DueRules.slipDays(task)
        if (slip > 0) factors += ScoreFactor("Prazo deslocado $slip dia(s)", slip.coerceAtMost(6).toInt())

        val total = factors.sumOf { it.points }.coerceIn(0, 100)
        val quadrant = when {
            urgent && important -> Quadrant.DO_NOW
            important -> Quadrant.SCHEDULE
            urgent -> Quadrant.QUICK
            else -> Quadrant.LATER
        }
        return PriorityScore(task.id, total, quadrant, flags, factors)
    }

    /** Scores all open tasks and returns them ordered from most to least recommended. */
    fun rank(tasks: List<Task>, now: LocalDateTime): List<Pair<Task, PriorityScore>> {
        val byId = tasks.associateBy { it.id }
        val dependents = buildDependents(tasks)
        return tasks.filter { it.status.isOpen }
            .map { it to score(it, byId, dependents, now) }
            .sortedWith(compareBy<Pair<Task, PriorityScore>> { TaskFlag.BLOCKED in it.second.flags }
                .thenByDescending { it.second.score }
                .thenBy { it.first.dueDate ?: java.time.LocalDate.MAX }
                .thenBy { it.first.id })
    }

    fun buildDependents(tasks: List<Task>): Map<Long, List<Long>> {
        val map = mutableMapOf<Long, MutableList<Long>>()
        tasks.forEach { t -> t.dependsOn.forEach { dep -> map.getOrPut(dep) { mutableListOf() } += t.id } }
        return map
    }

    /**
     * "Foco de hoje": up to [limit] unblocked tasks that are due/overdue today, planned for today,
     * or have a high score, ordered by score.
     */
    fun focusToday(tasks: List<Task>, plannedToday: Set<Long>, now: LocalDateTime, limit: Int = 5): List<Pair<Task, PriorityScore>> {
        val today = now.toLocalDate()
        return rank(tasks, now).filter { (t, s) ->
            TaskFlag.BLOCKED !in s.flags && (
                t.id in plannedToday || TaskFlag.OVERDUE in s.flags ||
                    (t.dueDate != null && !t.dueDate.isAfter(today)) || s.quadrant == Quadrant.DO_NOW || s.score >= 50
                )
        }.take(limit)
    }
}
