package com.taskflow.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taskflow.presentation.calendar.CalendarScreen
import com.taskflow.presentation.categories.CategoriesScreen
import com.taskflow.presentation.components.LocalMessenger
import com.taskflow.presentation.components.Messenger
import com.taskflow.presentation.dashboard.DashboardScreen
import com.taskflow.presentation.habits.HabitEditScreen
import com.taskflow.presentation.habits.HabitsScreen
import com.taskflow.presentation.history.HistoryScreen
import com.taskflow.presentation.myday.MyDayScreen
import com.taskflow.presentation.quickadd.QuickAddSheet
import com.taskflow.presentation.quickadd.QuickAddViewModel
import com.taskflow.presentation.settings.SettingsScreen
import com.taskflow.presentation.stats.StatsScreen
import com.taskflow.presentation.taskdetail.TaskDetailScreen
import com.taskflow.presentation.taskedit.TaskEditScreen
import com.taskflow.presentation.tasks.TasksScreen

@Composable
fun TaskFlowNavHost(
    pendingRoute: MutableState<String?>,
    pendingQuickAdd: MutableState<Boolean>,
    requestNotificationPermission: () -> Unit,
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messenger = remember { Messenger(snackbar, scope) }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = TopLevel.entries.firstOrNull { it.route == currentRoute }
    var quickAdd by rememberSaveable { mutableStateOf(false) }
    val quickVm: QuickAddViewModel = hiltViewModel()

    val navigate: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
    val back: () -> Unit = { if (!nav.popBackStack()) Unit }

    LaunchedEffect(pendingRoute.value) {
        pendingRoute.value?.let { route -> runCatching { nav.navigate(route) { launchSingleTop = true } }; pendingRoute.value = null }
    }
    LaunchedEffect(pendingQuickAdd.value) { if (pendingQuickAdd.value) { quickAdd = true; pendingQuickAdd.value = false } }

    CompositionLocalProvider(LocalMessenger provides messenger) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (topLevel != null) NavigationBar {
                    TopLevel.entries.forEach { item ->
                        NavigationBarItem(
                            selected = topLevel == item,
                            onClick = {
                                nav.navigate(item.navRoute) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (topLevel != null && topLevel != TopLevel.HABITS) FloatingActionButton(onClick = { quickAdd = true }) { Icon(Icons.Outlined.Add, "Nova tarefa") }
                else if (topLevel == TopLevel.HABITS) FloatingActionButton(onClick = { navigate(Routes.habitEdit()) }) { Icon(Icons.Outlined.Add, "Novo hábito") }
            },
        ) { pad ->
            NavHost(nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(pad),
                enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                composable(Routes.DASHBOARD) { DashboardScreen(navigate) }
                composable(Routes.TASKS, arguments = listOf(
                    navArgument("filter") { type = NavType.StringType; defaultValue = "" },
                    navArgument("category") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("search") { type = NavType.BoolType; defaultValue = false },
                )) { e -> TasksScreen(navigate, startWithSearch = e.arguments?.getBoolean("search") == true) }
                composable(Routes.MY_DAY) { MyDayScreen(navigate) }
                composable(Routes.HABITS) { HabitsScreen(navigate) }
                composable(Routes.CALENDAR) { CalendarScreen(navigate) }
                composable(Routes.TASK_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) { TaskDetailScreen(back, navigate) }
                composable(Routes.TASK_EDIT, arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("date") { type = NavType.LongType; defaultValue = Long.MIN_VALUE },
                )) { TaskEditScreen(back) }
                composable(Routes.HABIT_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { HabitEditScreen(back) }
                composable(Routes.STATS) { StatsScreen(back, navigate) }
                composable(Routes.CATEGORIES) { CategoriesScreen(back, navigate) }
                composable(Routes.SETTINGS) { SettingsScreen(back, requestNotificationPermission) }
                composable(Routes.HISTORY) { HistoryScreen(back, navigate) }
            }
        }
        if (quickAdd) {
            val defaultDate = if (topLevel == TopLevel.MY_DAY || topLevel == TopLevel.DASHBOARD) quickVm.time.today() else null
            QuickAddSheet(quickVm, defaultDate, onDismiss = { quickAdd = false },
                onOpenFullEditor = { quickAdd = false; navigate(Routes.taskEdit(date = defaultDate)) }, onMessage = { messenger.show(it) })
        }
    }
}
