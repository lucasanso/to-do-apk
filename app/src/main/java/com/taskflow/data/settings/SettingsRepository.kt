package com.taskflow.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Escuro") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val taskReminders: Boolean = true,
    val overdueAlerts: Boolean = true,
    val dailySummary: Boolean = true,
    /** minute of day */
    val dailySummaryTime: Int = 8 * 60,
    val habitReminders: Boolean = true,
    val defaultAvailableMinutes: Int = 6 * 60,
    val defaultReminderMinutes: Int = 30,
    /** Use Gemini Nano (on-device) to understand voice commands when the device supports it. */
    val useOnDeviceAi: Boolean = true,
    /** The capture button starts listening right away (otherwise opens the keyboard). */
    val voiceFirst: Boolean = true,
    /** Read answers aloud (on-device text-to-speech) when the command was spoken. */
    val speakReplies: Boolean = true,
    /** Text-to-speech engine package for replies; null = the phone's default engine. */
    val ttsEngine: String? = null,
    /** Voice name inside that engine; null = the engine's default Portuguese voice. */
    val ttsVoice: String? = null,
    /** Speech rate and pitch (1.0 = normal); null = keep the phone's settings. */
    val ttsRate: Float? = null,
    val ttsPitch: Float? = null,
)

private val Context.dataStore by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val reminders = booleanPreferencesKey("task_reminders")
        val overdue = booleanPreferencesKey("overdue_alerts")
        val summary = booleanPreferencesKey("daily_summary")
        val summaryTime = intPreferencesKey("daily_summary_time")
        val habits = booleanPreferencesKey("habit_reminders")
        val available = intPreferencesKey("default_available")
        val defaultReminder = intPreferencesKey("default_reminder")
        val onDeviceAi = booleanPreferencesKey("on_device_ai")
        val voiceFirst = booleanPreferencesKey("voice_first")
        val speakReplies = booleanPreferencesKey("speak_replies")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsRate = floatPreferencesKey("tts_rate")
        val ttsPitch = floatPreferencesKey("tts_pitch")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::map)

    suspend fun current(): AppSettings = settings.first()

    private fun map(p: Preferences) = AppSettings(
        themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = p[K.dynamic] ?: false,
        taskReminders = p[K.reminders] ?: true,
        overdueAlerts = p[K.overdue] ?: true,
        dailySummary = p[K.summary] ?: true,
        dailySummaryTime = p[K.summaryTime] ?: (8 * 60),
        habitReminders = p[K.habits] ?: true,
        defaultAvailableMinutes = p[K.available] ?: (6 * 60),
        defaultReminderMinutes = p[K.defaultReminder] ?: 30,
        useOnDeviceAi = p[K.onDeviceAi] ?: true,
        voiceFirst = p[K.voiceFirst] ?: true,
        speakReplies = p[K.speakReplies] ?: true,
        ttsEngine = p[K.ttsEngine],
        ttsVoice = p[K.ttsVoice],
        ttsRate = p[K.ttsRate],
        ttsPitch = p[K.ttsPitch],
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(map(p))
            p[K.theme] = s.themeMode.name
            p[K.dynamic] = s.dynamicColor
            p[K.reminders] = s.taskReminders
            p[K.overdue] = s.overdueAlerts
            p[K.summary] = s.dailySummary
            p[K.summaryTime] = s.dailySummaryTime.coerceIn(0, 24 * 60 - 1)
            p[K.habits] = s.habitReminders
            p[K.available] = s.defaultAvailableMinutes.coerceIn(15, 24 * 60)
            p[K.defaultReminder] = s.defaultReminderMinutes.coerceIn(0, 7 * 24 * 60)
            p[K.onDeviceAi] = s.useOnDeviceAi
            p[K.voiceFirst] = s.voiceFirst
            p[K.speakReplies] = s.speakReplies
            s.ttsEngine?.let { p[K.ttsEngine] = it } ?: p.remove(K.ttsEngine)
            s.ttsVoice?.let { p[K.ttsVoice] = it } ?: p.remove(K.ttsVoice)
            s.ttsRate?.let { p[K.ttsRate] = it.coerceIn(0.5f, 2f) } ?: p.remove(K.ttsRate)
            s.ttsPitch?.let { p[K.ttsPitch] = it.coerceIn(0.5f, 2f) } ?: p.remove(K.ttsPitch)
        }
    }
}
