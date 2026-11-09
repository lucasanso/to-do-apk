package com.taskflow.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taskflow.MainActivity
import com.taskflow.R
import com.taskflow.domain.model.Habit
import com.taskflow.domain.model.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        const val CH_REMINDERS = "reminders"
        const val CH_OVERDUE = "overdue"
        const val CH_SUMMARY = "summary"
        const val CH_HABITS = "habits"
        const val SUMMARY_ID = 1
        const val OVERDUE_ID = 2
        fun taskNotificationId(taskId: Long) = (taskId % 1_000_000_000L).toInt() + 1000
        fun habitNotificationId(habitId: Long) = -(habitId % 1_000_000_000L).toInt() - 1000
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_REMINDERS, "Lembretes de tarefas", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_OVERDUE, "Tarefas atrasadas", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_SUMMARY, "Resumo diário", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_HABITS, "Hábitos", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(route: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(action: String, id: Long, requestCode: Int): PendingIntent {
        val i = Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(NotificationActionReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun taskReminder(task: Task, text: String) {
        if (!canPost()) return
        val nid = taskNotificationId(task.id)
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp("task/${task.id}", nid))
            .addAction(0, "Concluir", action(NotificationActionReceiver.ACTION_COMPLETE, task.id, nid + 1))
            .addAction(0, "Adiar 1 dia", action(NotificationActionReceiver.ACTION_POSTPONE, task.id, nid + 2))
            .build()
        post(nid, n)
    }

    fun habitReminder(habit: Habit) {
        if (!canPost()) return
        val nid = habitNotificationId(habit.id)
        val n = NotificationCompat.Builder(context, CH_HABITS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(habit.title)
            .setContentText("Hora de manter a sequência")
            .setAutoCancel(true)
            .setContentIntent(openApp("habits", nid))
            .addAction(0, "Feito", action(NotificationActionReceiver.ACTION_HABIT_DONE, habit.id, nid - 1))
            .build()
        post(nid, n)
    }

    fun summary(title: String, lines: List<String>) {
        if (!canPost()) return
        val style = NotificationCompat.InboxStyle().also { s -> lines.forEach { s.addLine(it) } }
        val n = NotificationCompat.Builder(context, CH_SUMMARY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openApp(null, SUMMARY_ID))
            .build()
        post(SUMMARY_ID, n)
    }

    fun overdue(count: Int, titles: List<String>) {
        if (!canPost()) return
        val style = NotificationCompat.InboxStyle().also { s -> titles.take(5).forEach { s.addLine(it) } }
        val n = NotificationCompat.Builder(context, CH_OVERDUE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count == 1) "1 tarefa atrasada" else "$count tarefas atrasadas")
            .setContentText(titles.firstOrNull().orEmpty())
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openApp("tasks?filter=OVERDUE", OVERDUE_ID))
            .build()
        post(OVERDUE_ID, n)
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    @Suppress("MissingPermission")
    private fun post(id: Int, n: android.app.Notification) {
        if (canPost()) NotificationManagerCompat.from(context).notify(id, n)
    }
}
