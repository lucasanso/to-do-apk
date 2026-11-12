package com.taskflow.ui

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.notifications.Notifier
import com.taskflow.presentation.voice.VoiceCaptureActivity
import com.taskflow.shake.ShakeController
import com.taskflow.shake.ShakeDetector
import com.taskflow.shake.ShakeService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/** Shake to talk with the app closed: the background service, its guards and how it opens the voice screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShakeServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(app)
    private val sensors get() = app.getSystemService(SensorManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private var clockMs = 10_000L

    @Before fun setUp() {
        shadowOf(sensors).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        VoiceCaptureActivity.isVisible = false
        VoiceCaptureActivity.lastShownAt = 0L
        runBlocking { settings.update { it.copy(shakeToTalk = true, shakeSensitivity = 1) } }
    }

    @After fun tearDown() {
        runBlocking { settings.update { it.copy(shakeToTalk = false) } }
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun start(): ServiceController<ShakeService> {
        val c = Robolectric.buildService(ShakeService::class.java).create().startCommand(0, 1)
        settle()
        return c
    }

    /** Lets the DataStore read reach the service (it hops between threads). */
    private fun settle() = repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }

    private fun reading(x: Float, y: Float = 0f, z: Float = ShakeDetector.GRAVITY) {
        val e = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER)
        e.values[0] = x; e.values[1] = y; e.values[2] = z
        e.timestamp = clockMs * 1_000_000
        clockMs += 20
        shadowOf(sensors).sendSensorEventToListeners(e)
    }

    /** A firm back-and-forth shake: ~3 g swings every 200 ms for one second. */
    private fun shake() {
        repeat(5) {
            reading(3f * ShakeDetector.GRAVITY); repeat(4) { reading(0f) }
            reading(-3f * ShakeDetector.GRAVITY); repeat(4) { reading(0f) }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun voiceStarts(): List<Intent> = generateSequence { shadowOf(app).nextStartedActivity }
        .filter { it.component?.className == VoiceCaptureActivity::class.java.name }.toList()

    @Test fun runsAsForegroundServiceWithTurnOffAction() {
        val c = start()
        val shadow = shadowOf(c.get())
        assertEquals(Notifier.SHAKE_SERVICE_ID, shadow.lastForegroundNotificationId)
        val n = shadow.lastForegroundNotification
        assertEquals("Balançar para falar está ativo", n.extras.getString("android.title"))
        assertEquals("Desligar", n.actions.single().title)
        assertTrue(c.get().isListening)
    }

    @Test fun shakeOpensVoiceScreenWhenAllowedToAppearOverApps() {
        ShadowSettings.setCanDrawOverlays(true)
        start()
        shake()
        val started = voiceStarts()
        assertEquals(1, started.size)
        val i = started.single()
        assertEquals(VoiceCaptureActivity.ACTION_VOICE, i.action)
        assertTrue(i.getBooleanExtra(ShakeController.EXTRA_FROM_SHAKE, false))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // The screen showed up: no tap-to-talk notification.
        VoiceCaptureActivity.lastShownAt = android.os.SystemClock.elapsedRealtime()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShakeController.FALLBACK_CHECK_MS + 100))
        assertNull(shadowOf(notifications).getNotification(Notifier.SHAKE_PROMPT_ID))
    }

    @Test fun withoutPermissionShakeShowsTapToTalkNotification() {
        ShadowSettings.setCanDrawOverlays(false)
        start()
        shake()
        assertTrue(voiceStarts().isEmpty())
        val prompt = shadowOf(notifications).getNotification(Notifier.SHAKE_PROMPT_ID)
        assertNotNull(prompt)
        assertEquals("Falar com o TaskFlow", prompt.extras.getString("android.title"))
        // Tapping it opens the voice screen.
        val tap = shadowOf(prompt.contentIntent).savedIntent
        assertEquals(VoiceCaptureActivity::class.java.name, tap.component?.className)
    }

    @Test fun blockedBackgroundStartFallsBackToNotification() {
        ShadowSettings.setCanDrawOverlays(true)
        start()
        shake()
        assertEquals(1, voiceStarts().size)
        // The system silently held the screen back (it never came on top).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShakeController.FALLBACK_CHECK_MS + 100))
        assertNotNull(shadowOf(notifications).getNotification(Notifier.SHAKE_PROMPT_ID))
    }

    @Test fun gentleMovementDoesNothing() {
        ShadowSettings.setCanDrawOverlays(true)
        start()
        repeat(200) { reading((if (it % 25 < 12) 0.5f else -0.5f) * ShakeDetector.GRAVITY) }   // walking
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(voiceStarts().isEmpty())
        assertNull(shadowOf(notifications).getNotification(Notifier.SHAKE_PROMPT_ID))
    }

    @Test fun sensorOffWhileLockedOrScreenOff() {
        val c = start()
        assertTrue(c.get().isListening)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(c.get().isListening)

        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(c.get().isListening)

        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(c.get().isListening)
        assertFalse(shadowOf(sensors).hasListener(c.get()))
    }

    @Test fun noShakeDuringCallsOrWhileVoiceScreenIsOpen() {
        ShadowSettings.setCanDrawOverlays(true)
        start()
        val audio = app.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_CALL
        shake()
        assertTrue(voiceStarts().isEmpty())

        audio.mode = AudioManager.MODE_NORMAL
        VoiceCaptureActivity.isVisible = true
        clockMs += 5_000
        shake()
        assertTrue(voiceStarts().isEmpty())

        VoiceCaptureActivity.isVisible = false
        clockMs += 5_000
        shake()
        assertEquals(1, voiceStarts().size)
    }

    @Test fun turnOffFromNotificationStopsAndSavesSetting() {
        val c = start()
        c.withIntent(Intent(app, ShakeService::class.java).setAction(ShakeService.ACTION_TURN_OFF)).startCommand(0, 2)
        settle()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertFalse(runBlocking { settings.current() }.shakeToTalk)
    }

    @Test fun stopsWhenFeatureIsDisabled() {
        val c = start()
        assertFalse(shadowOf(c.get()).isStoppedBySelf)
        runBlocking { settings.update { it.copy(shakeToTalk = false) } }
        settle()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test fun syncStartsAndStopsTheService() {
        runBlocking { ShakeController.sync(app, settings.current()) }
        val started = shadowOf(app).nextStartedService
        assertEquals(ShakeService::class.java.name, started.component?.className)
        ShakeController.sync(app, runBlocking { settings.current() }.copy(shakeToTalk = false))
        assertEquals(ShakeService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
    }
}
