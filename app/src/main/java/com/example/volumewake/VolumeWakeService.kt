package com.example.volumewake

import android.accessibilityservice.AccessibilityService
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeWakeService : AccessibilityService() {
    private lateinit var pm: PowerManager

    override fun onServiceConnected() {
        pm = getSystemService(POWER_SERVICE) as PowerManager
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP ||
            event.action != KeyEvent.ACTION_DOWN ||
            pm.isInteractive
        ) return false
        wakeScreen()
        return true
    }

    // Flag ini deprecated, tapi ACQUIRE_CAUSES_WAKEUP masih jadi cara paling sederhana.
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "volumewake:wake"
        ).acquire(3000) // lepas otomatis setelah 3 detik
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
