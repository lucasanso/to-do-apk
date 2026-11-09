package com.taskflow.domain.engine

import com.taskflow.domain.model.Category
import com.taskflow.domain.model.DayPlanItem
import com.taskflow.domain.model.Task
import com.taskflow.domain.model.TaskStatus
import java.time.LocalDate
import java.time.ZoneId

data class DayCount(val date: LocalDate, val count: Int)
data class WeekCount(val weekStart: LocalDate, val count: Int)
data class CategoryVolume(val category: Category?, val total: Int, val done: Int) {
    val rate: Double get() = if (total == 0) 0.0 else done.toDouble() / total
}
data class PlanVsDone(val date: LocalDate, val planned: Int, val done: Int)

data class ProductivityReport(
    val completedPerDay: List<DayCount>,
    val completedPerWeek: List<WeekCount>,
    /** Completed / (completed + still open) for tasks due or created in the period. */
    val completionRate: Double,
    val completedInPeriod: Int,
    val openInPeriod: Int,
    val categories: List<CategoryVolume>,
    val mostPostponed: List<Task>,
    val planVsDone: List<PlanVsDone>,
    /** Average daily completions in the last 7 days minus the previous 7 days. */
    val trendDelta: Double,
)

object ProductivityCalculator {

    fun report(
        tasks: List<Task>,
        categories: List<Category>,
        plan: List<DayPlanItem>,
        today: LocalDate,
        zone: ZoneId,
        days: Int = 14,
        weeks: Int = 8,
    ): ProductivityReport {
        val doneDates = tasks.mapNotNull { t -> t.completedAt?.takeIf { t.status == TaskStatus.DONE || t.status == TaskStatus.ARCHIVED }?.atZone(zone)?.toLocalDate() }
        val perDay = (days - 1 downTo 0).map { off ->
            val d = today.minusDays(off.toLong()); DayCount(d, doneDates.count { it == d })
        }
        val thisWeekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val perWeek = (weeks - 1 downTo 0).map { off ->
            val start = thisWeekStart.minusWeeks(off.toLong())
            WeekCount(start, doneDates.count { !it.isBefore(start) && it.isBefore(start.plusDays(7)) })
        }

        val periodStart = today.minusDays(days - 1L)
        fun inPeriod(t: Task): Boolean {
            val created = t.createdAt.atZone(zone).toLocalDate()
            val ref = t.dueDate ?: created
            return !ref.isBefore(periodStart) && !ref.isAfter(today)
        }
        val periodTasks = tasks.filter { it.status != TaskStatus.ARCHIVED || it.completedAt != null }.filter(::inPeriod)
        val completed = periodTasks.count { it.status == TaskStatus.DONE }
        val open = periodTasks.count { it.status.isOpen }
        val rate = if (completed + open == 0) 0.0 else completed.toDouble() / (completed + open)

        val catById = categories.associateBy { it.id }
        val volumes = tasks.filter { it.status != TaskStatus.ARCHIVED }.groupBy { it.categoryId }
            .map { (cid, list) -> CategoryVolume(cid?.let(catById::get), list.size, list.count { it.status == TaskStatus.DONE }) }
            .sortedByDescending { it.total }

        val postponed = tasks.filter { it.postponeCount >= 2 }.sortedByDescending { it.postponeCount }.take(5)

        val byId = tasks.associateBy { it.id }
        val planVsDone = (6 downTo 0).map { off ->
            val d = today.minusDays(off.toLong())
            val items = plan.filter { it.date == d }
            val done = items.count { item ->
                val t = byId[item.taskId]
                t?.completedAt?.atZone(zone)?.toLocalDate()?.let { !it.isAfter(d) } == true && t.status != TaskStatus.PENDING
            }
            PlanVsDone(d, items.size, done)
        }

        val last7 = perDay.takeLast(7).sumOf { it.count } / 7.0
        val prev7 = perDay.dropLast(7).takeLast(7).let { if (it.isEmpty()) 0.0 else it.sumOf { c -> c.count } / 7.0 }

        return ProductivityReport(perDay, perWeek, rate, completed, open, volumes, postponed, planVsDone, last7 - prev7)
    }

    /** Daily progress: done today / (done today + open tasks that are due today, overdue or planned today). */
    fun dailyProgress(tasks: List<Task>, plannedToday: Set<Long>, today: LocalDate, zone: ZoneId): Pair<Int, Int> {
        val doneToday = tasks.count { it.status == TaskStatus.DONE && it.completedAt?.atZone(zone)?.toLocalDate() == today }
        val remaining = tasks.count { t ->
            t.status.isOpen && ((t.dueDate != null && !t.dueDate.isAfter(today)) || t.id in plannedToday)
        }
        return doneToday to (doneToday + remaining)
    }
}
