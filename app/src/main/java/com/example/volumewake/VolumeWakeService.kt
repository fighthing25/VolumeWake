package com.example.volumewake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Layanan latar yang menampung pemicu non-aksesibilitas:
 * - MediaSession "remote volume" (tombol volume saat layar mati)
 * - Goyang HP (akselerometer)
 * - Sensor jarak (lambai / keluarkan dari saku)
 */
class VolumeWakeService : Service(), SensorEventListener {
    private lateinit var pm: PowerManager
    private lateinit var am: AudioManager
    private lateinit var sm: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var sensorLock: PowerManager.WakeLock? = null

    private var shakeHits = 0
    private var shakeStart = 0L
    private var lastSpike = 0L
    private var lastShakeFire = 0L
    private var wasNear: Boolean? = null
    private var lastProxFire = 0L

    override fun onCreate() {
        super.onCreate()
        pm = getSystemService(POWER_SERVICE) as PowerManager
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        applyPrefs()
        if (intent != null && intent.action == ACTION_TEST) {
            runWakeTest(intent.getStringExtra(EXTRA_MODE) ?: "lock")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        sm.unregisterListener(this)
        stopSession()
        sensorLock?.release()
        sensorLock = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** Pasang/lepas pemicu sesuai pengaturan di app. Dipanggil tiap onStartCommand. */
    private fun applyPrefs() {
        if (Core.isEnabled(this, Core.VOL_SESSION)) startSession() else stopSession()

        sm.unregisterListener(this)
        wasNear = null
        var needLock = false
        if (Core.isEnabled(this, Core.SHAKE)) {
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
                needLock = true
            }
        }
        if (Core.isEnabled(this, Core.PROX)) {
            val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            if (prox != null) {
                sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
                needLock = true
            }
        }
        // Sensor berhenti saat CPU tidur, jadi butuh partial wake lock selama pemicu sensor aktif.
        if (needLock) {
            if (sensorLock == null) {
                sensorLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "volumewake:sensors").also { it.acquire() }
            }
        } else {
            sensorLock?.release()
            sensorLock = null
        }
    }

    private fun startSession() {
        if (session != null) return
        val ctx = this
        val s = MediaSession(this, "VolumeWake")
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build()
        )
        s.setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (direction > 0 && Core.onTrigger(ctx, Core.VOL_SESSION)) return
                // Layar menyala: teruskan ke volume media. Pakai adjustStreamVolume (bukan
                // adjustSuggestedStreamVolume) agar tidak berputar balik ke sesi ini.
                if (pm.isInteractive && direction != 0) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                }
            }
        })
        s.isActive = true
        session = s
    }

    private fun stopSession() {
        session?.release()
        session = null
    }

    /** Tes: 10 detik lagi nyalakan layar dengan cara yang dipilih. CPU dijaga bangun selama tes. */
    private fun runWakeTest(mode: String) {
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "volumewake:test").acquire(15000)
        handler.postDelayed({ Core.wakeWith(this, mode) }, 10000)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleShake(event)
            Sensor.TYPE_PROXIMITY -> handleProximity(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handleShake(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < 2.3f) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSpike < 150) return
        lastSpike = now
        if (now - shakeStart > 1200) {
            shakeStart = now
            shakeHits = 0
        }
        shakeHits++
        if (shakeHits >= 3 && now - lastShakeFire > 2000) {
            shakeHits = 0
            lastShakeFire = now
            Core.onTrigger(this, Core.SHAKE)
        }
    }

    private fun handleProximity(e: SensorEvent) {
        val v = e.values[0]
        val near = v < 5f && v < e.sensor.maximumRange
        val prev = wasNear
        wasNear = near
        val now = SystemClock.elapsedRealtime()
        // Dekat lalu jauh = tangan dilambaikan atau HP dikeluarkan dari saku.
        if (prev == true && !near && now - lastProxFire > 1500) {
            lastProxFire = now
            Core.onTrigger(this, Core.PROX)
        }
    }

    companion object {
        const val ACTION_TEST = "com.example.volumewake.TEST"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL_ID = "volumewake"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }
}
