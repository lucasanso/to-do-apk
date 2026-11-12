package com.taskflow.shake

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.taskflow.R
import com.taskflow.data.settings.AppSettings
import com.taskflow.notifications.Notifier
import com.taskflow.presentation.voice.VoiceCaptureActivity

/** Starts/stops the shake service and opens the voice capture when a shake happens. */
object ShakeController {

    /**
     * Brings the service in line with the settings. Only call while the app is in the foreground, at boot
     * or after an update (the moments Android allows starting a foreground service).
     */
    fun sync(context: Context, s: AppSettings) {
        val intent = Intent(context, ShakeService::class.java)
        if (s.shakeToTalk) runCatching { ContextCompat.startForegroundService(context, intent) }
        else context.stopService(intent)
    }

    /** "Aparecer sobre outros apps": lets the app open the voice screen by itself from the background. */
    fun canOpenDirectly(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    @android.annotation.SuppressLint("BatteryLife")
    fun batteryIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Opens the voice capture. With "Aparecer sobre outros apps" granted the screen opens by itself; if the
     * permission is missing, or the system still holds the screen back, a heads-up notification shows up
     * instead and one tap on it opens the microphone.
     */
    fun openVoice(context: Context) {
        val app = context.applicationContext
        vibrate(app)
        val requestedAt = SystemClock.elapsedRealtime()
        val launched = canOpenDirectly(app) && runCatching {
            app.startActivity(voiceIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.isSuccess
        if (!launched) { postPrompt(app); return }
        // A blocked background start fails silently: check that the screen really appeared.
        Handler(Looper.getMainLooper()).postDelayed({
            if (VoiceCaptureActivity.lastShownAt < requestedAt) postPrompt(app)
        }, FALLBACK_CHECK_MS)
    }

    fun voiceIntent(context: Context) =
        Intent(context, VoiceCaptureActivity::class.java).setAction(VoiceCaptureActivity.ACTION_VOICE).putExtra(EXTRA_FROM_SHAKE, true)

    private fun postPrompt(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!Notifier(context).canPost()) return
        val tap = PendingIntent.getActivity(context, 7003, voiceIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, Notifier.CH_SHAKE_PROMPT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Falar com o TaskFlow")
            .setContentText("Toque para registrar uma tarefa por voz")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(tap)
            .build()
        runCatching { nm.notify(Notifier.SHAKE_PROMPT_ID, n) }
    }

    fun cancelPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(Notifier.SHAKE_PROMPT_ID)
    }

    private fun vibrate(context: Context) = runCatching {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    const val EXTRA_FROM_SHAKE = "from_shake"
    const val FALLBACK_CHECK_MS = 1_500L
}
