package com.taskflow.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val DASHBOARD = "dashboard"
    const val TASKS = "tasks?filter={filter}&category={category}&search={search}"
    const val MY_DAY = "myday"
    const val HABITS = "habits"
    const val CALENDAR = "calendar"
    const val TASK_DETAIL = "task/{id}"
    const val TASK_EDIT = "edit/{id}?date={date}"
    const val HABIT_EDIT = "habit_edit/{id}"
    const val STATS = "stats"
    const val CATEGORIES = "categories"
    const val SETTINGS = "settings"
    const val HISTORY = "history"

    fun tasks(filter: String? = null, category: Long? = null, search: Boolean = false) =
        "tasks?filter=${filter ?: ""}&category=${category ?: -1}&search=$search"
    fun taskDetail(id: Long) = "task/$id"
    fun taskEdit(id: Long = 0, date: java.time.LocalDate? = null) = "edit/$id?date=${date?.toEpochDay() ?: Long.MIN_VALUE}"
    fun habitEdit(id: Long = 0) = "habit_edit/$id"
}

enum class TopLevel(val route: String, val navRoute: String, val label: String, val icon: ImageVector) {
    DASHBOARD(Routes.DASHBOARD, Routes.DASHBOARD, "Hoje", Icons.Outlined.WbSunny),
    TASKS(Routes.TASKS, Routes.tasks(), "Tarefas", Icons.Outlined.Checklist),
    HABITS(Routes.HABITS, Routes.HABITS, "Hábitos", Icons.Outlined.Loop),
    CALENDAR(Routes.CALENDAR, Routes.CALENDAR, "Agenda", Icons.Outlined.CalendarMonth),
}
