package com.example.volumewake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.PowerManager

data class Trigger(val id: String, val label: String, val hint: String)

object Core {
    const val VOL_SESSION = "vol_session"
    const val VOL_ACCESS = "vol_access"
    const val SHAKE = "shake"
    const val PROX = "prox"
    const val WAKE_NOTIF_ID = 2
    private const val WAKE_CHANNEL = "vw_wake"

    val TRIGGERS = listOf(
        Trigger(VOL_SESSION, "Volume Up via MediaSession", "Layanan harus berjalan. Tekan Volume Up saat layar mati."),
        Trigger(VOL_ACCESS, "Volume Up via Aksesibilitas", "Aksesibilitas harus aktif. Tekan Volume Up saat layar mati."),
        Trigger(SHAKE, "Goyang HP (boros baterai)", "Layanan harus berjalan. Goyang HP kuat 3 kali saat layar mati."),
        Trigger(PROX, "Sensor jarak: lambai / keluarkan dari saku", "Layanan harus berjalan. Tutup lalu buka sensor dekat kamera depan dengan tangan saat layar mati.")
    )

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("vw", Context.MODE_PRIVATE)

    fun isEnabled(c: Context, id: String): Boolean =
        prefs(c).getBoolean("en_$id", id == VOL_SESSION || id == VOL_ACCESS)

    fun wakeMode(c: Context): String = prefs(c).getString("wake_mode", "lock") ?: "lock"

    /**
     * Dipanggil setiap pemicu terdeteksi. Selalu dicatat (untuk uji).
     * Return true hanya jika layar mati, pemicu aktif, dan layar dinyalakan.
     */
    fun onTrigger(c: Context, id: String): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive
        val p = prefs(c)
        val key = if (screenOn) "${id}_on" else "${id}_off"
        p.edit()
            .putInt(key, p.getInt(key, 0) + 1)
            .putLong("${id}_last", System.currentTimeMillis())
            .apply()
        if (screenOn || !isEnabled(c, id)) return false
        wakeWith(c, wakeMode(c))
        return true
    }

    fun resetCounters(c: Context) {
        val e = prefs(c).edit()
        for (t in TRIGGERS) {
            e.remove("${t.id}_off")
            e.remove("${t.id}_on")
            e.remove("${t.id}_last")
        }
        e.apply()
    }

    fun wakeWith(c: Context, mode: String) {
        if (mode == "fullscreen") wakeFullScreen(c) else wakeLock(c)
    }

    // Flag ini deprecated, tapi ACQUIRE_CAUSES_WAKEUP masih jadi cara paling sederhana.
    @Suppress("DEPRECATION")
    private fun wakeLock(c: Context) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "volumewake:wake"
        ).acquire(3000) // lepas otomatis setelah 3 detik
    }

    // Cara alternatif: notifikasi layar-penuh membuka WakeActivity yang menyalakan layar.
    private fun wakeFullScreen(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(WAKE_CHANNEL, "Nyalakan layar", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            c, 0,
            Intent(c, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, WAKE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Volume Wake")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        nm.notify(WAKE_NOTIF_ID, n)
    }
}
