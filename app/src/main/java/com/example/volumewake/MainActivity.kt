package com.example.volumewake

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, VolumeWakeService::class.java))
        Toast.makeText(this, R.string.started, Toast.LENGTH_SHORT).show()
        finish()
    }
}
