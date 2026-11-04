package com.example.volumewake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Menangkap tombol volume lewat MediaSession "remote volume". Android mengirim tombol
 * volume ke sesi ini walau layar mati, berbeda dengan AccessibilityService yang biasanya
 * tidak menerima tombol saat layar mati.
 */
class VolumeWakeService : Service() {
    private lateinit var pm: PowerManager
    private lateinit var am: AudioManager
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        pm = getSystemService(POWER_SERVICE) as PowerManager
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        startAsForeground()
        startSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        session?.release()
        session = null
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

    private fun startSession() {
        val s = MediaSession(this, "VolumeWake")
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build()
        )
        s.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (pm.isInteractive) {
                    // Layar menyala: teruskan ke volume media supaya tombol tetap berfungsi.
                    // Pakai adjustStreamVolume (bukan adjustSuggestedStreamVolume) agar tidak berputar balik ke sesi ini.
                    if (direction != 0) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                    }
                } else if (direction > 0) {
                    wakeScreen()
                }
            }
        })
        s.isActive = true
        session = s
    }

    // Flag ini deprecated, tapi ACQUIRE_CAUSES_WAKEUP masih jadi cara paling sederhana.
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "volumewake:wake"
        ).acquire(3000) // lepas otomatis setelah 3 detik
    }

    private companion object {
        const val CHANNEL_ID = "volumewake"
        const val NOTIF_ID = 1
    }
}
