package com.taskflow.shake

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.taskflow.MainActivity
import com.taskflow.R
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.notifications.Notifier
import com.taskflow.presentation.voice.VoiceCaptureActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Listens for the shake gesture while the app is closed.
 *
 * Android only delivers accelerometer events to background apps through a foreground service, which
 * must show a notification (type "specialUse": a shake detector fits no other category). To save battery
 * and avoid pocket triggers, the sensor is on only while the screen is on and the phone is unlocked,
 * and nothing fires during calls. On a shake it opens the voice capture (see [ShakeController.openVoice]).
 */
@AndroidEntryPoint
class ShakeService : Service(), SensorEventListener {
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var detector = ShakeDetector()
    private var listening = false

    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateListening()
    }

    override fun onCreate() {
        super.onCreate()
        // Android may refuse (e.g. a restart while the app is in the background and battery-restricted):
        // then the service quits quietly and comes back the next time the app is opened.
        if (runCatching { startAsForeground() }.isFailure) { stopSelf(); return }
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ContextCompat.registerReceiver(this, screenEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            settings.settings.collect { s ->
                if (!s.shakeToTalk) { stopSelf(); return@collect }
                detector = ShakeDetector(ShakeDetector.Sensitivity.of(s.shakeSensitivity).thresholdG)
            }
        }
        updateListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            scope.launch { settings.update { it.copy(shakeToTalk = false) } }
            stopSelf()
            return START_NOT_STICKY
        }
        updateListening()
        // If Android kills the process, it brings the service back.
        return START_STICKY
    }

    private fun startAsForeground() {
        val n = foregroundNotification(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.SHAKE_SERVICE_ID, n, type)
    }

    /** Sensor on only with the screen on and the phone unlocked. */
    fun shouldListen(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        return power?.isInteractive == true && keyguard?.isKeyguardLocked != true
    }

    fun updateListening() {
        val sm = sensorManager ?: return
        val sensor = accelerometer ?: return
        val should = shouldListen()
        if (should && !listening) {
            detector.reset()
            listening = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (!should && listening) {
            sm.unregisterListener(this)
            listening = false
        }
    }

    val isListening: Boolean get() = listening

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
        val ms = event.timestamp / 1_000_000
        if (detector.onReading(ms, event.values[0], event.values[1], event.values[2])) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onShake() {
        if (VoiceCaptureActivity.isVisible || inCall()) return
        ShakeController.openVoice(this)
    }

    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        listening = false
        runCatching { unregisterReceiver(screenEvents) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_TURN_OFF = "com.taskflow.shake.TURN_OFF"

        fun foregroundNotification(context: Context): Notification {
            val open = PendingIntent.getActivity(context, 7001,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val off = PendingIntent.getService(context, 7002, Intent(context, ShakeService::class.java).setAction(ACTION_TURN_OFF),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, Notifier.CH_SHAKE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Balançar para falar está ativo")
                .setContentText("Balance o celular para registrar uma tarefa por voz")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .addAction(0, "Desligar", off)
                .build()
        }
    }
}
