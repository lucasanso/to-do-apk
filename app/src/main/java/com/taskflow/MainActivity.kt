package com.taskflow

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.taskflow.data.settings.AppSettings
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.navigation.TaskFlowNavHost
import com.taskflow.notifications.Notifier
import com.taskflow.presentation.theme.TaskFlowTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_QUICK_ADD = "quick_add"
        private const val PREFS = "app_state"
    }

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var notifier: Notifier

    /** Pending deep-link coming from notifications, widget or shortcuts. */
    private val pendingRoute = mutableStateOf<String?>(null)
    private val pendingQuickAdd = mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        maybeAskNotificationPermission()
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(AppSettings())
            TaskFlowTheme(s.themeMode, s.dynamicColor) {
                TaskFlowNavHost(
                    pendingRoute = pendingRoute,
                    pendingQuickAdd = pendingQuickAdd,
                    requestNotificationPermission = ::requestNotificationPermission,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
        if (intent.getBooleanExtra(EXTRA_QUICK_ADD, false) || intent.action == "com.taskflow.QUICK_ADD") pendingQuickAdd.value = true
    }

    /** Ask once, on first launch, on Android 13+. Later requests come from Settings. */
    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        if (!notifier.canPost()) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).not() &&
            getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("asked_notifications", false)) {
            // Permanently denied: send the user to system settings.
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        } else if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
