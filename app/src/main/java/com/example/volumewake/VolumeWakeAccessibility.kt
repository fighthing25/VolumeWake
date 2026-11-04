package com.example.volumewake

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeWakeAccessibility : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP || event.action != KeyEvent.ACTION_DOWN) return false
        // Tombol hanya "dimakan" kalau layar memang dinyalakan; selain itu volume normal.
        return Core.onTrigger(this, Core.VOL_ACCESS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
