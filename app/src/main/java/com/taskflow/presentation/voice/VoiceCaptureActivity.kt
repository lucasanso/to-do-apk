package com.taskflow.presentation.voice

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.MainActivity
import com.taskflow.data.settings.AppSettings
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.presentation.theme.TaskFlowTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Lightweight voice entry point shown over whatever is on screen.
 *
 * Reached from: the "TaskFlow Voz" launcher entry (pick it for the S26 side button double-press, or say
 * "abrir TaskFlow Voz" to Bixby/Gemini), the home-screen widget mic, the app-icon shortcut, and
 * "Compartilhar"/text selection from any app (the shared text is interpreted directly).
 */
@AndroidEntryPoint
class VoiceCaptureActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository
    private val vm: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = sharedText(intent)
        val typing = intent?.action == ACTION_TYPE
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(AppSettings())
            TaskFlowTheme(s.themeMode, s.dynamicColor) {
                VoiceCaptureSheet(
                    vm = vm,
                    startWithVoice = !typing,
                    initialText = shared,
                    autoClose = true,
                    onDismiss = { finish() },
                    onOpenTask = { id -> openApp("task/$id") },
                    onOpenEditor = { openApp("edit/0?date=${Long.MIN_VALUE}") },
                    onOpenRoute = { route -> openApp(route) },
                )
            }
        }
    }

    private fun openApp(route: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun sharedText(i: Intent?): String? = when (i?.action) {
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }?.trim()?.take(500)?.takeIf { it.isNotEmpty() }

    companion object {
        const val ACTION_VOICE = "com.taskflow.VOICE"
        const val ACTION_TYPE = "com.taskflow.TYPE"
    }
}
