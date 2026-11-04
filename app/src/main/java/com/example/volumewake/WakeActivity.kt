package com.example.volumewake

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/** Activity transparan yang hanya menyalakan layar lalu menutup diri. */
class WakeActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        getSystemService(NotificationManager::class.java).cancel(Core.WAKE_NOTIF_ID)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
    }
}
